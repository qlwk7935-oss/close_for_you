package kr.closeforyou.simpleclose

import kotlinx.collections.immutable.toImmutableList
import kr.closeforyou.core.model.ButtonLabelRules
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
     * F53: something may lie over the popup's button. No press and no "always" (not by tap, not by voice); its buttons as
     * [버튼으로 이동] only, the person finds out which one is in front.
     */
    fun overlapped(prompt: ChoicePrompt, buttons: List<CloseCandidate>): ChoicePrompt = adjust(
        prompt.copy(options = prompt.options.filterNot { it.kind == PromptOptionKind.PRESS_CANDIDATE || it.kind == PromptOptionKind.REMEMBER_ALWAYS }.toImmutableList()),
        buttons,
    ).copy(summary = PromptSummary.POPUPS_OVERLAP, cannotPressForUser = false, briefQuestion = false)

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
