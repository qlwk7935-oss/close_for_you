package kr.closeforyou.ui.voice.debug

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
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
import kr.closeforyou.ui.voice.overlay.PromptAnswer
import kr.closeforyou.ui.voice.overlay.PromptShowResult
import kr.closeforyou.ui.voice.overlay.PromptAnnouncementListener
import kr.closeforyou.ui.voice.overlay.PromptControlsSupport
import kr.closeforyou.ui.voice.overlay.RevalidationCause
import kr.closeforyou.ui.voice.settings.UiVoicePreferences
import java.lang.ref.WeakReference

/**
 * Debug-only C test service: lets the preview screen show the real overlay above other apps.
 * It performs no actions and never invalidates its prompt. The produced UserChoice is only shown
 * on the preview screen. Normally it gets only our own announcement events and ignores them; the
 * issue #29 diagnosis ([C29OverlayDiagnostics], adb extra only) reads window ids, types and layers (logged as run-local numbers)
 * for one show and logs numbers only. The C34 focus watch ([OverlayNodeDump]) is read-only unless its
 * adb extra asks to move focus. Both debug tools are stopped, with their service info restored and every
 * pending callback cancelled, on interrupt, unbind and destroy; only one of them changes the service info at a time.
 */
class ChoiceOverlayTestService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var overlay: AccessibilityChoiceOverlay? = null
    private var announcer: TtsPopupAnnouncer? = null
    private val diagnostics = C29OverlayDiagnostics(this, handler) { overlay?.windowState ?: OverlayWindowState.HIDDEN }
    private var diagnose = false

    /** This connection's number; [OverlayNodeDump.NO_SESSION] when not connected. */
    private var session = OverlayNodeDump.NO_SESSION
    private val nodeDump = OverlayNodeDump(this, { session }, { overlay?.windowState ?: OverlayWindowState.HIDDEN })

    override fun onServiceConnected() {
        lastSession += 1
        session = lastSession
        announcer = TtsPopupAnnouncer.create(this)
        // The TalkBack takeover finds C's window and its tagged nodes the way A's service does
        // (A's config has both flags); this debug service's XML has neither.
        serviceInfo?.let { info ->
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            serviceInfo = info
        }
        current = WeakReference(this)
        mutableConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (diagnose && event != null) diagnostics.onEvent(event)
        if (event != null) nodeDump.onEvent(event)
    }

    override fun onInterrupt() {
        stopDebugTools(OverlayNodeDump.StopReason.INTERRUPT)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopDebugTools(OverlayNodeDump.StopReason.UNBIND)
        session = OverlayNodeDump.NO_SESSION
        if (current?.get() === this) current = null
        mutableConnected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopDebugTools(OverlayNodeDump.StopReason.DESTROY)
        session = OverlayNodeDump.NO_SESSION
        overlay?.dismiss()
        overlay = null
        announcer?.close()
        announcer = null
        scope.cancel()
        current = null
        mutableConnected.value = false
        super.onDestroy()
    }

    /** Debug watches end; the service info they changed is restored. */
    private fun stopDebugTools(reason: OverlayNodeDump.StopReason) {
        diagnostics.disarm()
        diagnose = false
        nodeDump.stopWatch(reason)
    }

    private fun showLater(
        prompt: ChoicePrompt,
        content: String,
        delayMillis: Long,
        ttsDelayMillis: Long,
        diagnose: Boolean,
        revalidateDemo: RevalidateDemo,
    ) {
        handler.removeCallbacksAndMessages(null)
        diagnostics.disarm()
        this.diagnose = diagnose
        if (diagnose) {
            nodeDump.stopWatch(OverlayNodeDump.StopReason.OTHER_DIAGNOSIS)
            diagnostics.arm()
        }
        handler.postDelayed({
            val tts = announcer ?: return@postDelayed
            nodeDump.onPromptGone()
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
                revalidatingEnabled = revalidateDemo != RevalidateDemo.OFF,
            )
            overlay = next
            val onAnswer: (PromptAnswer) -> Unit = { answer ->
                if (answer is PromptAnswer.Picked) {
                    nodeDump.onPromptGone()
                    mutableLastChoice.value = answer.choice
                    if (answer.choice.kind == ChoiceKind.READ_CONTENT) readContent(answer.choice, content)
                }
            }
            val startedAt = SystemClock.uptimeMillis()
            val result = next.present(prompt, onAnswer)
            if (diagnose) diagnostics.onShown(startedAt, result.ordinal)
            if (result == PromptShowResult.SHOWN) {
                nodeDump.onPromptShown()
                if (revalidateDemo != RevalidateDemo.OFF) demoRevalidation(next, prompt, onAnswer, revalidateDemo)
            }
        }, delayMillis)
    }

    /**
     * REVALIDATING demo (adb extra only): stands in for A's three signals with the same synthetic
     * prompt. Each step runs only if this overlay is still the one shown; the handler is cleared on the
     * next show and on destroy, and the overlay cancels its own timeout when it closes.
     */
    private fun demoRevalidation(
        target: AccessibilityChoiceOverlay,
        prompt: ChoicePrompt,
        onAnswer: (PromptAnswer) -> Unit,
        demo: RevalidateDemo,
    ) {
        handler.postDelayed({
            if (overlay !== target) return@postDelayed
            if (!target.beginRevalidation(RevalidationCause.OTHER) || demo != RevalidateDemo.CONFIRM) return@postDelayed
            handler.postDelayed({
                if (overlay === target) target.confirmSamePopup(prompt, onAnswer)
            }, DEMO_CONFIRM_AFTER_MILLIS)
        }, DEMO_BEGIN_AFTER_MILLIS)
    }

    private fun watchEvents(millis: Long) {
        diagnostics.disarm()
        nodeDump.stopWatch(OverlayNodeDump.StopReason.OTHER_DIAGNOSIS)
        diagnose = true
        diagnostics.watch(millis)
    }

    /** Synthetic fixture text only; spoken, never logged. */
    private fun readContent(choice: UserChoice, content: String) {
        val tts = announcer ?: return
        scope.launch { tts.announce(AnnouncementRequest(choice.token, AnnouncementKind.CONTENT, content)) }
    }

    private fun watchFocus(millis: Long, moveFocus: Boolean) {
        diagnostics.disarm()
        diagnose = false
        nodeDump.watchFocus(millis, moveFocus)
    }

    companion object {
        private const val DEMO_BEGIN_AFTER_MILLIS = 3_000L
        private const val DEMO_CONFIRM_AFTER_MILLIS = 2_000L
        private var lastSession = OverlayNodeDump.NO_SESSION
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
            service.nodeDump.dump()
            return true
        }

        /**
         * Logs accessibility-focus events (TalkBack swipes) for [millis] under C34Nodes, and C's nodes 4 s after
         * each prompt shown meanwhile. Read-only unless [moveFocus] (explicit adb extra): then also puts TalkBack's
         * focus on C's first node once per shown prompt, if the same window is still up. Main thread only.
         */
        fun watchFocus(millis: Long, moveFocus: Boolean = false): Boolean {
            val service = current?.get() ?: return false
            service.watchFocus(millis, moveFocus)
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
            /** REVALIDATING demo for this show (adb extra only). */
            revalidateDemo: RevalidateDemo = RevalidateDemo.OFF,
        ): Boolean {
            val service = current?.get() ?: return false
            mutableLastChoice.value = null
            service.showLater(prompt, content, delayMillis, ttsDelayMillis, diagnose, revalidateDemo)
            return true
        }
    }
}

/** Debug REVALIDATING demo: off, "다시 확인 중" then confirmed, or "다시 확인 중" until the timeout. */
enum class RevalidateDemo { OFF, CONFIRM, TIMEOUT }
