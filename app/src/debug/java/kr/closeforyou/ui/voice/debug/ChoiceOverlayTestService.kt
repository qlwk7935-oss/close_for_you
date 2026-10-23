package kr.closeforyou.ui.voice.debug

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kr.closeforyou.core.contract.AnnouncementKind
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.UserChoice
import kr.closeforyou.ui.voice.TtsPopupAnnouncer
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.overlay.AccessibilityChoiceOverlay
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.PromptAnnouncementListener
import kr.closeforyou.ui.voice.overlay.PromptControlsSupport
import kr.closeforyou.ui.voice.settings.UiVoicePreferences
import java.lang.ref.WeakReference

/**
 * Debug-only C test service: lets the preview screen show the real overlay above other apps.
 * It performs no actions and never invalidates its prompt. The produced UserChoice is only shown
 * on the preview screen. Normally it gets only our own announcement events and ignores them; the
 * issue #29 diagnosis ([C29OverlayDiagnostics], adb extra only) reads window ids, types and layers (logged as run-local numbers)
 * for one show and logs numbers only.
 */
class ChoiceOverlayTestService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var overlay: AccessibilityChoiceOverlay? = null
    private var announcer: TtsPopupAnnouncer? = null
    private val diagnostics = C29OverlayDiagnostics(this, handler) { overlay?.windowState ?: OverlayWindowState.HIDDEN }
    private var diagnose = false

    override fun onServiceConnected() {
        announcer = TtsPopupAnnouncer.create(this)
        current = WeakReference(this)
        mutableConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (diagnose && event != null) diagnostics.onEvent(event)
        if (event != null) OverlayNodeDump.onEvent(this, event)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (diagnose) diagnostics.disarm()
        OverlayNodeDump.stopWatch(this)
        overlay?.dismiss()
        overlay = null
        announcer?.close()
        announcer = null
        scope.cancel()
        current = null
        mutableConnected.value = false
        super.onDestroy()
    }

    private fun showLater(prompt: ChoicePrompt, content: String, delayMillis: Long, ttsDelayMillis: Long, diagnose: Boolean) {
        handler.removeCallbacksAndMessages(null)
        if (this.diagnose) diagnostics.disarm()
        this.diagnose = diagnose
        if (diagnose) diagnostics.arm()
        handler.postDelayed({
            val tts = announcer ?: return@postDelayed
            overlay?.dismiss()
            // A new overlay per show so each test can use its own announcement delay.
            val next = AccessibilityChoiceOverlay(
                this,
                PromptAnnouncementListener(
                    this, tts, scope,
                    mode = { UiVoicePreferences.get(this).current().promptAnnounceMode },
                    ttsDelayMillis = ttsDelayMillis,
                    detail = { UiVoicePreferences.get(this).current().detail },
                ),
                // Same [말하기]·[다시 듣기]·[그만 말하기] as the real prompt (synthetic fixtures only).
                PromptControlsSupport.create(this, tts),
            )
            overlay = next
            val startedAt = SystemClock.uptimeMillis()
            val result = next.show(prompt) { choice ->
                mutableLastChoice.value = choice
                if (choice.kind == ChoiceKind.READ_CONTENT) readContent(choice, content)
            }
            if (diagnose) diagnostics.onShown(startedAt, result.ordinal)
        }, delayMillis)
    }

    private fun watchEvents(millis: Long) {
        if (diagnose) diagnostics.disarm()
        diagnose = true
        diagnostics.watch(millis)
    }

    /** Synthetic fixture text only; spoken, never logged. */
    private fun readContent(choice: UserChoice, content: String) {
        val tts = announcer ?: return
        scope.launch { tts.announce(AnnouncementRequest(choice.token, AnnouncementKind.CONTENT, content)) }
    }

    companion object {
        private var current: WeakReference<ChoiceOverlayTestService>? = null
        private val mutableConnected = MutableStateFlow(false)
        private val mutableLastChoice = MutableStateFlow<UserChoice?>(null)

        val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()
        val lastChoice: StateFlow<UserChoice?> = mutableLastChoice.asStateFlow()

        /**
         * Issue #29 watch mode (adb extra only): shows nothing, logs what this service receives with A's
         * event types for [millis] under C29Diag. Main thread only. False when the test service is off.
         */
        fun watchEvents(millis: Long): Boolean {
            val service = current?.get() ?: return false
            service.watchEvents(millis)
            return true
        }

        /** Logs C's own prompt node tree under C34Nodes (flags and lengths only). False when the service is off. */
        fun dumpOwnNodes(): Boolean {
            val service = current?.get() ?: return false
            OverlayNodeDump.dump(service)
            return true
        }

        /** Logs accessibility-focus events (TalkBack swipes) for [millis] under C34Nodes. */
        fun watchFocus(millis: Long): Boolean {
            val service = current?.get() ?: return false
            OverlayNodeDump.watchFocus(service, millis)
            return true
        }

        /** Main thread only. False when the test service is not turned on. */
        fun scheduleShow(
            prompt: ChoicePrompt,
            content: String,
            delayMillis: Long,
            ttsDelayMillis: Long = PromptAnnouncementListener.DEFAULT_TTS_DELAY_MILLIS,
            /** Issue #29 diagnosis for this show (adb extra only): logs window facts under C29Diag. */
            diagnose: Boolean = false,
        ): Boolean {
            val service = current?.get() ?: return false
            mutableLastChoice.value = null
            service.showLater(prompt, content, delayMillis, ttsDelayMillis, diagnose)
            return true
        }
    }
}
