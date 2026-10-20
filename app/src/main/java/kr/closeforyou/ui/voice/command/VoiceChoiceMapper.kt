package kr.closeforyou.ui.voice.command

import kr.closeforyou.core.contract.VoiceCommand
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.PromptOption
import kr.closeforyou.ui.voice.choice.PromptOptionKind

/** What C does with a voice command while a "닫을까요?" prompt is open. */
sealed interface VoiceAction {
    /** Same option a tap would pick; the resulting UserChoice still goes through B and A. */
    data class Choose(val option: PromptOption) : VoiceAction

    /** No single verified close/skip button: say "대신 닫을 수 없어요" and offer [버튼으로 이동]. */
    data object CannotPressForUser : VoiceAction

    /** Not C's to execute (Back, image description; focus move while the feature is off or ambiguous). */
    data class NotHandledHere(val command: VoiceCommand) : VoiceAction
}

/**
 * CLAUDE.md 3-1: close/skip/read/keep may run from voice, but only on the prompt's own options.
 * Decision popups never have a press option, so voice can never confirm a payment or consent.
 * Two candidates with the same role are ambiguous by voice and are not picked.
 */
object VoiceChoiceMapper {
    fun map(command: VoiceCommand, prompt: ChoicePrompt): VoiceAction = when (command) {
        VoiceCommand.CLOSE -> pressByRole(prompt, ButtonRole.CLOSE)
        VoiceCommand.SKIP -> pressByRole(prompt, ButtonRole.SKIP)
        VoiceCommand.READ_CONTENT -> option(prompt, PromptOptionKind.READ_CONTENT, command)
        VoiceCommand.KEEP_OPEN -> option(prompt, PromptOptionKind.KEEP_OPEN, command)
        VoiceCommand.MOVE_TO_BUTTON -> moveToButton(prompt, command)
        VoiceCommand.GO_BACK, VoiceCommand.DESCRIBE_IMAGE -> VoiceAction.NotHandledHere(command)
    }

    private fun pressByRole(prompt: ChoicePrompt, role: ButtonRole): VoiceAction {
        val matches = prompt.options.filter { it.kind == PromptOptionKind.PRESS_CANDIDATE && it.candidateRole == role }
        return matches.singleOrNull()?.let { VoiceAction.Choose(it) } ?: VoiceAction.CannotPressForUser
    }

    /**
     * Only when the prompt offers exactly one [PromptOptionKind.MOVE_TO_BUTTON] (feature on). With
     * several buttons the voice cannot say which one, so the person picks on screen.
     */
    private fun moveToButton(prompt: ChoicePrompt, command: VoiceCommand): VoiceAction =
        prompt.options.singleOrNull { it.kind == PromptOptionKind.MOVE_TO_BUTTON }
            ?.let { VoiceAction.Choose(it) } ?: VoiceAction.NotHandledHere(command)

    /** Only an option the prompt shows now; a hidden choice is never run by voice. */
    private fun option(prompt: ChoicePrompt, kind: PromptOptionKind, command: VoiceCommand): VoiceAction =
        prompt.options.firstOrNull { it.kind == kind }?.let { VoiceAction.Choose(it) }
            ?: VoiceAction.NotHandledHere(command)
}
