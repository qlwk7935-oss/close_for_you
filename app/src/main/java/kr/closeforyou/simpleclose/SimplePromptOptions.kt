package kr.closeforyou.simpleclose

import kotlinx.collections.immutable.toImmutableList
import kr.closeforyou.R
import kr.closeforyou.core.model.ButtonLabelRules
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.PromptOption
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.choice.PromptSummary

/** Where a button is on the screen, for "'허용' 버튼은 화면 아래쪽에 있어요" (TalkBack off). */
enum class ScreenPlace { TOP, MIDDLE, BOTTOM }

/**
 * F20/F21: the engine's own changes to C's planned prompt. Pure; JVM tested.
 * - Buttons that were pressed on this popup and did not close it ([failedLabels], normalized names; the
 *   engine keeps them per popup for the session, F21) are not offered at all: the next button is offered
 *   as the press instead (AGENTS 6-6: no retry).
 * - Right after such a failure ([failedNow]) the first line says "대신 닫지 못했어요.".
 * - Risky buttons (결제·동의·권한·구독·삭제…): [버튼으로 이동] only, at most [MAX_MOVES] (CLAUDE.local 3-1:
 *   위치 안내까지). They are never a press option.
 */
object SimplePromptOptions {
    const val MAX_MOVES = 3

    fun adjust(prompt: ChoicePrompt, riskButtons: List<CloseCandidate>, failedLabels: Set<String> = emptySet(), failedNow: Boolean = false): ChoicePrompt {
        val kept = prompt.options.filterNot {
            (it.kind == PromptOptionKind.PRESS_CANDIDATE || it.kind == PromptOptionKind.REMEMBER_ALWAYS) &&
                it.buttonLabel?.let(ButtonLabelRules::normalize) in failedLabels
        }
        val moves = riskButtons.distinctBy { it.id }.take(MAX_MOVES).filter { !it.label.isNullOrBlank() }
            .map { PromptOption(PromptOptionKind.MOVE_TO_BUTTON, candidateId = it.id, candidateRole = it.role, buttonLabel = it.label?.trim()) }
        // Moves go right after the press buttons, before 내용 다시 읽기 / 그대로 두기.
        val presses = kept.takeWhile { it.kind == PromptOptionKind.PRESS_CANDIDATE || it.kind == PromptOptionKind.REMEMBER_ALWAYS }
        val options = presses + moves + kept.drop(presses.size)
        if (!failedNow && moves.isEmpty() && kept.size == prompt.options.size) return prompt
        return prompt.copy(
            summary = if (failedNow) PromptSummary.AUTO_CLOSE_FAILED else prompt.summary,
            cannotPressForUser = options.none { it.kind == PromptOptionKind.PRESS_CANDIDATE } &&
                prompt.summary != PromptSummary.NEEDS_REVIEW && prompt.summary != PromptSummary.SUSPICIOUS,
            briefQuestion = prompt.briefQuestion && !failedNow,
            options = options.toImmutableList(),
        )
    }

    /**
     * F53/F57: something may lie over the popup's button. No press and no "always" (not by tap, not by voice), and its own
     * buttons are not places to go either: a double tap there would be the press just refused. [frontKnown]: one move, to the popup
     * taken to be in front. Not known: the sentence says so and only [그대로 두기] stays.
     * F59: [pressLabel], the name of the front popup's close that passed [OverlapPressRules]: offered first, as one press, never "always".
     * [failedNow]: that press has just not closed it.
     */
    fun overlapped(prompt: ChoicePrompt, frontKnown: Boolean, pressLabel: String? = null, failedNow: Boolean = false): ChoicePrompt {
        val rest = prompt.options.filterNot {
            it.kind == PromptOptionKind.PRESS_CANDIDATE || it.kind == PromptOptionKind.REMEMBER_ALWAYS || it.kind == PromptOptionKind.MOVE_TO_BUTTON
        }
        val press = pressLabel?.takeIf { frontKnown && it.isNotBlank() }
        val options = if (frontKnown) {
            listOfNotNull(press?.let { PromptOption(PromptOptionKind.PRESS_CANDIDATE, candidateId = FRONT_PRESS_ID, candidateRole = ButtonRole.CLOSE, buttonLabel = it.trim(), labelRes = R.string.ui_choice_press_front) }) +
                PromptOption(PromptOptionKind.MOVE_TO_BUTTON, candidateId = FRONT_ID, labelRes = R.string.ui_choice_move_to_front) + rest
        } else {
            rest.filter { it.kind == PromptOptionKind.KEEP_OPEN }
        }
        return prompt.copy(
            summary = when {
                failedNow -> PromptSummary.AUTO_CLOSE_FAILED
                press != null -> PromptSummary.POPUPS_OVERLAP_PRESS
                frontKnown -> PromptSummary.POPUPS_OVERLAP
                else -> PromptSummary.POPUPS_OVERLAP_NO_MOVE
            },
            cannotPressForUser = false,
            briefQuestion = false,
            options = options.toImmutableList(),
        )
    }

    /** F57: the move option's id: where it goes is the prompt's [StackHit.front], not one of the popup's own buttons. */
    const val FRONT_ID = "overlap:front"

    /** F59: the press option's id: what it presses is the prompt's [FrontPress], never one of the stopped popup's buttons. */
    const val FRONT_PRESS_ID = "overlap:front-press"

    fun place(bounds: ScreenBounds, screen: ScreenBounds): ScreenPlace {
        val centre = (bounds.top + bounds.bottom) / 2 - screen.top
        val third = screen.height / 3
        return when {
            centre < third -> ScreenPlace.TOP
            centre < third * 2 -> ScreenPlace.MIDDLE
            else -> ScreenPlace.BOTTOM
        }
    }
}
