package kr.closeforyou.simpleclose

import kotlinx.collections.immutable.toImmutableList
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.PromptOption
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.choice.PromptSummary

/** Where a button is on the screen, for "'허용' 버튼은 화면 아래쪽에 있어요" (TalkBack off). */
enum class ScreenPlace { TOP, MIDDLE, BOTTOM }

/**
 * F20: the engine's own changes to C's planned prompt. Pure; JVM tested.
 * - After a failed automatic close ([failedCandidateId]): the first line says "대신 닫지 못했어요.", that
 *   button is not offered as a press again (AGENTS 6-6: no retry), only as [버튼으로 이동]; other buttons stay.
 * - Risky buttons (결제·동의·권한·구독·삭제…): [버튼으로 이동] only, at most [MAX_MOVES] (CLAUDE.local 3-1:
 *   위치 안내까지). They are never a press option.
 */
object SimplePromptOptions {
    const val MAX_MOVES = 3

    fun adjust(prompt: ChoicePrompt, riskButtons: List<CloseCandidate>, failedCandidateId: String?, failed: CloseCandidate? = null): ChoicePrompt {
        val kept = prompt.options.filterNot {
            failedCandidateId != null && it.candidateId == failedCandidateId &&
                (it.kind == PromptOptionKind.PRESS_CANDIDATE || it.kind == PromptOptionKind.REMEMBER_ALWAYS)
        }
        val moveTargets = (listOfNotNull(failed) + riskButtons).distinctBy { it.id }.take(MAX_MOVES)
            .filter { target -> !target.label.isNullOrBlank() && kept.none { it.kind == PromptOptionKind.MOVE_TO_BUTTON && it.candidateId == target.id } }
        val moves = moveTargets.map { PromptOption(PromptOptionKind.MOVE_TO_BUTTON, candidateId = it.id, candidateRole = it.role, buttonLabel = it.label?.trim()) }
        // Moves go right after the press buttons, before 내용 다시 읽기 / 그대로 두기.
        val presses = kept.takeWhile { it.kind == PromptOptionKind.PRESS_CANDIDATE || it.kind == PromptOptionKind.REMEMBER_ALWAYS }
        val options = presses + moves + kept.drop(presses.size)
        if (failedCandidateId == null && moves.isEmpty()) return prompt
        return prompt.copy(
            summary = if (failedCandidateId != null) PromptSummary.AUTO_CLOSE_FAILED else prompt.summary,
            cannotPressForUser = options.none { it.kind == PromptOptionKind.PRESS_CANDIDATE } && prompt.summary != PromptSummary.NEEDS_REVIEW,
            briefQuestion = prompt.briefQuestion && failedCandidateId == null,
            options = options.toImmutableList(),
        )
    }

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
