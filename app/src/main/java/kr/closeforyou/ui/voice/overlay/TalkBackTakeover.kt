package kr.closeforyou.ui.voice.overlay

import android.content.Context
import android.view.accessibility.AccessibilityManager
import kr.closeforyou.R
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.settings.UiVoicePreferences

/**
 * "선택 창이 뜨면 안내를 맡기기" (AGENTS.md 6-0 demo mode, relaxes 6-10 and C rule 3-2 until the final
 * presentation). While TalkBack is on and the prompt appears:
 * 1. TalkBack's accessibility focus goes to the prompt's first item, so TalkBack stops reading the popup;
 * 2. our TTS reads the popup's own text as extracted (never a summary or a rewording);
 * 3. when that playback finished (UtteranceProgressListener.onDone), focus goes to "'닫기' 버튼 눌러 주기"
 *    (or the first of C's buttons that presses nothing in the popup).
 * Focus is moved by A's service on C's own window only, never in another app. Device unverified.
 */
object TalkBackTakeoverFeature {
    /**
     * The one constant to flip: true for the demo build (6-0), false once the app is released so the
     * person turns it on in settings. It is the default of [kr.closeforyou.ui.voice.settings.UiVoiceSettings.talkBackTakeover].
     */
    const val DEFAULT_ON = true

    /** Feature flag: false removes the takeover and its settings row whatever the setting says. */
    val enabled: Boolean get() = true

    /** The window reaches A's window list a little after addView; tried this often, this far apart. */
    const val FIRST_FOCUS_ATTEMPTS = 10
    const val FIRST_FOCUS_RETRY_MILLIS = 100L

    /** Lets TalkBack read the focused first item (the short summary) before our TTS starts (unverified). */
    const val SPEECH_DELAY_MILLIS = 1_000L

    fun isActive(settingOn: Boolean, touchExplorationOn: Boolean): Boolean =
        enabled && settingOn && touchExplorationOn

    /** Feature, setting and TalkBack (touch exploration) on right now. */
    fun isActive(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        return isActive(
            UiVoicePreferences.get(context).current().talkBackTakeover,
            manager?.isTouchExplorationEnabled == true,
        )
    }
}

/** Where C's window puts TalkBack's focus. [tag] is a Compose testTag exposed as the node's view id. */
enum class TakeoverFocusTarget(val tag: String) {
    /** The first item: the summary line ("광고로 보여요" …). */
    FIRST_ITEM("ui_takeover_first"),

    /** The button to land on after our speech ([TalkBackTakeoverPlan.buttonIndex]). */
    CHOICE_BUTTON("ui_takeover_button"),
}

/**
 * Moves TalkBack's focus inside one shown prompt window. The overlay binds it to that window's
 * generation, so it does nothing once the window is closed, replaced or revalidating.
 * [NONE] = takeover off for this window (the listener then announces as before).
 */
interface PromptFocus {
    val enabled: Boolean

    /** Main thread. True only when the focus action was accepted on a node of the current window. */
    fun moveTo(target: TakeoverFocusTarget): Boolean

    companion object {
        val NONE = object : PromptFocus {
            override val enabled = false
            override fun moveTo(target: TakeoverFocusTarget) = false
        }
    }
}

/** Pure choices of the takeover, JVM-tested. */
object TalkBackTakeoverPlan {
    /** These press a button in the other app; the rest are C's own (read, keep, stop asking …). */
    private val pressingKinds = setOf(PromptOptionKind.PRESS_CANDIDATE, PromptOptionKind.REMEMBER_ALWAYS)

    /**
     * The option to land on after our speech: the first "'X' 버튼 눌러 주기"; without one, the first
     * option that presses nothing in the popup. Landing there never presses it; the person double-taps.
     */
    fun buttonIndex(prompt: ChoicePrompt): Int? {
        val press = prompt.options.indexOfFirst { it.kind == PromptOptionKind.PRESS_CANDIDATE }
        if (press >= 0) return press
        return prompt.options.indexOfFirst { it.kind !in pressingKinds }.takeIf { it >= 0 }
    }

    /**
     * What our TTS says after TalkBack read the summary: the popup's text as extracted, then only C's
     * fixed safety lines ("대신 닫을 수 없어요", a video ad's wait). No popup text: the usual short
     * sentence. Null for a failed-press follow-up: its result was just spoken by the caller.
     */
    fun speech(prompt: ChoicePrompt, text: (Int, List<Any>) -> String, fallback: () -> String): String? {
        if (prompt.pressFailure != null) return null
        val popupText = prompt.popupText?.takeIf { it.isNotBlank() } ?: return fallback()
        val countdown = prompt.countdownSeconds
        val extra = when {
            countdown != null -> text(R.string.ui_choice_video_ad_wait, listOf(countdown))
            prompt.cannotPressForUser -> text(R.string.ui_choice_cannot_press, emptyList())
            else -> null
        }
        return if (extra == null) popupText else "$popupText\n$extra"
    }
}
