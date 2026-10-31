package kr.closeforyou.ui.voice.command

import androidx.annotation.StringRes
import kr.closeforyou.R
import kr.closeforyou.core.contract.VoiceCommand
import kr.closeforyou.core.contract.VoiceEvent
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.PromptOption
import kr.closeforyou.ui.voice.choice.PromptOptionKind

/**
 * C-only result of one speak-button session. The contract's [VoiceEvent] has a single Cancelled
 * for every non-command, but the prompt must say different things for "not understood",
 * "pick it on screen" and "I cannot press that". Never carries the recognized sentence.
 */
sealed interface VoiceUtterance {
    /** One of the contract's commands; still goes through [VoicePolicy] and then B and A. */
    data class Command(val command: VoiceCommand) : VoiceUtterance

    /** "항상 닫아", "다시 묻지 마": only a tap on the button that names the target may confirm it. */
    data object NeedsTapConfirm : VoiceUtterance

    /** Payment, purchase, subscription, delete, permission, consent or login: never by voice. */
    data object Refused : VoiceUtterance

    /** Something was said, but not one clear command. */
    data object NotUnderstood : VoiceUtterance

    /** Nothing was said before the recognizer gave up: end quietly. */
    data object Silent : VoiceUtterance

    /** The recognizer itself cannot run now (busy, language missing, permission revoked). */
    data object Unavailable : VoiceUtterance

    /**
     * F23: "○○ 버튼으로 이동" ("결제 버튼 어디야" too): only TalkBack's focus or the button's place, never a press,
     * so a payment or permission button may be named (CLAUDE.local 3-1: 위치 안내까지). [spoken] is the normalized
     * sentence, kept in memory for this one match against the prompt's own button names; never logged or shown.
     */
    class MoveTo(val spoken: String) : VoiceUtterance {
        override fun toString(): String = "MoveTo(redacted)"
    }

    /** What the contract's [VoiceEvent] stream reports for this result. */
    fun toEvent(token: SnapshotToken): VoiceEvent = when (this) {
        is Command -> VoiceEvent.Recognized(command, token)
        is MoveTo -> VoiceEvent.Recognized(VoiceCommand.MOVE_TO_BUTTON, token)
        Unavailable -> VoiceEvent.Unavailable
        NeedsTapConfirm, Refused, NotUnderstood, Silent -> VoiceEvent.Cancelled
    }
}

/** What the prompt does with one [VoiceUtterance]. */
sealed interface VoiceOutcome {
    /** The same option a tap would pick; the prompt sends it down the tap path. */
    data class Choose(val option: PromptOption) : VoiceOutcome

    /** Nothing runs; this short fixed sentence is shown and announced (liveRegion). */
    data class Say(@StringRes val textRes: Int) : VoiceOutcome

    /** Nothing runs and nothing is said. */
    data object Quiet : VoiceOutcome
}

/**
 * CLAUDE.md 3-1 voice policy as one pure table:
 * - direct request: close, skip, read, keep — only when that option is on the prompt now;
 * - tap confirmation: "always"/"stop asking" — with those features off, only "pick on screen";
 * - refused: payment, purchase, subscription, delete, permission, consent, login.
 * Sentences are fixed resources; the recognized words are never repeated back.
 */
object VoicePolicy {
    fun decide(utterance: VoiceUtterance, prompt: ChoicePrompt): VoiceOutcome = when (utterance) {
        is VoiceUtterance.Command -> when (val action = VoiceChoiceMapper.map(utterance.command, prompt)) {
            is VoiceAction.Choose -> VoiceOutcome.Choose(action.option)
            VoiceAction.CannotPressForUser -> VoiceOutcome.Say(R.string.ui_choice_cannot_press)
            is VoiceAction.NotHandledHere -> VoiceOutcome.Say(R.string.ui_voice_not_here)
        }
        VoiceUtterance.NeedsTapConfirm -> VoiceOutcome.Say(R.string.ui_voice_pick_on_screen)
        VoiceUtterance.Refused -> VoiceOutcome.Say(R.string.ui_voice_refused)
        VoiceUtterance.NotUnderstood -> VoiceOutcome.Say(R.string.ui_voice_not_understood)
        VoiceUtterance.Unavailable -> VoiceOutcome.Say(R.string.ui_voice_unavailable)
        VoiceUtterance.Silent -> VoiceOutcome.Quiet
        is VoiceUtterance.MoveTo -> moveTo(utterance.spoken, prompt)
    }

    /**
     * F23: the [버튼으로 이동] option whose button name was said ("허용 버튼으로 가", spaces ignored); with only one
     * such option, that one. Several and none named: the person picks on screen. Only [PromptOptionKind.MOVE_TO_BUTTON]
     * is ever chosen here: a move presses nothing.
     */
    fun moveTo(spoken: String, prompt: ChoicePrompt): VoiceOutcome {
        val moves = prompt.options.filter { it.kind == PromptOptionKind.MOVE_TO_BUTTON }
        if (moves.isEmpty()) return VoiceOutcome.Say(R.string.ui_voice_not_here)
        val named = moves.filter { option ->
            val name = option.buttonLabel?.let(KoreanVoiceCommandMatcher::normalize).orEmpty()
            name.isNotEmpty() && (name in spoken || spoken.substringBefore("버튼").let { it.length >= 2 && it in name })
        }
        val pick = named.singleOrNull() ?: moves.singleOrNull()?.takeIf { named.isEmpty() }
        return pick?.let { VoiceOutcome.Choose(it) } ?: VoiceOutcome.Say(R.string.ui_voice_pick_on_screen)
    }
}
