package kr.closeforyou.ui.voice.debug

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
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
import kr.closeforyou.ui.voice.overlay.PromptAnnouncementListener
import kr.closeforyou.ui.voice.settings.UiVoicePreferences
import java.lang.ref.WeakReference

/**
 * Debug-only C test service: lets the preview screen show the real overlay above other apps.
 * It reads no screen content (canRetrieveWindowContent=false), ignores every event and performs
 * no actions. The produced UserChoice is only shown on the preview screen.
 */
class ChoiceOverlayTestService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var overlay: AccessibilityChoiceOverlay? = null
    private var announcer: TtsPopupAnnouncer? = null

    override fun onServiceConnected() {
        announcer = TtsPopupAnnouncer.create(this)
        current = WeakReference(this)
        mutableConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        overlay?.dismiss()
        overlay = null
        announcer?.close()
        announcer = null
        scope.cancel()
        current = null
        mutableConnected.value = false
        super.onDestroy()
    }

    private fun showLater(prompt: ChoicePrompt, content: String, delayMillis: Long, ttsDelayMillis: Long) {
        handler.removeCallbacksAndMessages(null)
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
            )
            overlay = next
            next.show(prompt) { choice ->
                mutableLastChoice.value = choice
                if (choice.kind == ChoiceKind.READ_CONTENT) readContent(choice, content)
            }
        }, delayMillis)
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

        /** Main thread only. False when the test service is not turned on. */
        fun scheduleShow(
            prompt: ChoicePrompt,
            content: String,
            delayMillis: Long,
            ttsDelayMillis: Long = PromptAnnouncementListener.DEFAULT_TTS_DELAY_MILLIS,
        ): Boolean {
            val service = current?.get() ?: return false
            mutableLastChoice.value = null
            service.showLater(prompt, content, delayMillis, ttsDelayMillis)
            return true
        }
    }
}
