package kr.closeforyou.ui.voice.integration

import kotlin.coroutines.resume
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.suspendCancellableCoroutine
import kr.closeforyou.core.contract.AnnouncementKind
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.contract.EvidenceExtractor
import kr.closeforyou.core.contract.PopupAnnouncer
import kr.closeforyou.core.contract.PopupClassifier
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.contract.SnapshotSource
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.UserChoice
import kr.closeforyou.ui.voice.AnnouncementBatcher
import kr.closeforyou.ui.voice.PressResultSpeech
import kr.closeforyou.ui.voice.choice.AGateLabelPolicy
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.MoveToButtonFeature
import kr.closeforyou.ui.voice.choice.MoveToButtonRequest
import kr.closeforyou.ui.voice.choice.PressableLabelPolicy
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.overlay.ChoicePromptPresenter

/**
 * A (or B) hands C's choice to the safety path: B's ActionPolicy, then A's single action gate.
 * C never calls ActionExecutor itself. Return the gate's [ActionResult] for CLOSE_ONCE/SKIP_ONCE,
 * or null for choices that press nothing (KEEP_OPEN).
 */
fun interface ChoiceHandler {
    suspend fun handle(choice: UserChoice, analysis: PopupAnalysis): ActionResult?
}

/** Resolves C's string resources; injected so the flow can be unit tested without Android. */
fun interface UiText {
    fun get(resId: Int): String
}

/**
 * C's connecting piece (Mock-tested only, not yet wired to A's real service):
 * `PopupEvent.Candidate` → evidence (A) → classification (B) → "닫을까요?" prompt (C) → [UserChoice]
 * → [ChoiceHandler] (B/A) → result speech (C).
 *
 * - `Blocked` / `Invalidated` / a newer candidate: the prompt is closed and speech stops
 *   (collectLatest cancels the old popup's work). Old analysis is never shown again (AGENTS 6-7).
 * - The token is re-checked after every suspension (same as `IntegrationExample`).
 * - [내용 다시 읽기] speaks the popup text and keeps the prompt open.
 * - A press that did not succeed is announced at once with the next choices; a success is
 *   counted by [batcher]. Nothing is retried (AGENTS 6-6).
 *
 * Call [run] from a main-thread coroutine owned by the service; cancel it when the service stops.
 */
class PopupChoiceFlow(
    private val source: SnapshotSource,
    private val extractor: EvidenceExtractor,
    private val classifier: PopupClassifier,
    private val presenter: ChoicePromptPresenter,
    private val announcer: PopupAnnouncer,
    private val batcher: AnnouncementBatcher,
    private val handler: ChoiceHandler,
    private val text: UiText,
    private val contentText: (PopupAnalysis) -> String,
    private val onMoveToButton: (MoveToButtonRequest) -> Unit = {},
    private val labelPolicy: PressableLabelPolicy = AGateLabelPolicy,
    private val moveToButtonEnabled: Boolean = MoveToButtonFeature.enabled,
) {
    suspend fun run() {
        source.events.collectLatest { event ->
            when (event) {
                is PopupEvent.Candidate -> try {
                    handleCandidate(event)
                } finally {
                    // Covers cancellation by a newer event as well as normal completion.
                    closePrompt()
                }
                is PopupEvent.Blocked, is PopupEvent.Invalidated -> closePrompt()
            }
        }
    }

    private suspend fun handleCandidate(event: PopupEvent.Candidate) {
        val token = event.snapshot.token
        if (source.currentToken() != token) return
        val evidence = extractor.extract(event.snapshot)
        if (source.currentToken() != token) return
        val classification = classifier.classify(evidence)
        val analysis = evidence.copy(classification = classification)
        if (source.currentToken() != token) return

        var prompt: ChoicePrompt? =
            ChoicePromptPlanner.plan(analysis, classification, labelPolicy, moveToButtonEnabled)
        while (prompt != null) {
            prompt = when (val answer = awaitAnswer(prompt)) {
                is Answer.Move -> {
                    onMoveToButton(answer.request)
                    null
                }
                is Answer.Picked -> afterChoice(prompt, answer.choice, analysis)
            }
            if (source.currentToken() != token) return
        }
    }

    /** Returns the prompt to show next, or null when this popup is done. */
    private suspend fun afterChoice(prompt: ChoicePrompt, choice: UserChoice, analysis: PopupAnalysis): ChoicePrompt? {
        if (choice.kind == ChoiceKind.READ_CONTENT) {
            announcer.announce(AnnouncementRequest(choice.token, AnnouncementKind.CONTENT, contentText(analysis)))
            return prompt // still on screen; wait for the next tap
        }
        val result = handler.handle(choice, analysis) ?: return null
        if (!PressResultSpeech.isPressChoice(choice.kind)) return null
        val failure = PressResultSpeech.failureOf(result)
        if (failure == null) {
            AnnouncementBatcher.outcomeOf(result, choice.kind)?.let(batcher::add)
            return null
        }
        val followUp = if (source.currentToken() == choice.token) {
            ChoicePromptPlanner.afterFailedPress(prompt, failure, moveToButtonEnabled)
        } else {
            null
        }
        // Always said here, whatever the prompt announce setting: the person must hear that it
        // was not closed. The follow-up prompt itself stays silent (PromptAnnouncementListener).
        val hasMove = followUp?.options?.any { it.kind == PromptOptionKind.MOVE_TO_BUTTON } == true
        val sentence = PressResultSpeech.sentence(failure, hasMove, text::get)
        announcer.announce(AnnouncementRequest(choice.token, AnnouncementKind.ACTION_RESULT, sentence))
        return followUp
    }

    private sealed interface Answer {
        data class Picked(val choice: UserChoice) : Answer
        data class Move(val request: MoveToButtonRequest) : Answer
    }

    /** The prompt currently on screen, and who receives its next tap (null while busy). */
    private var shown: ChoicePrompt? = null
    private var pending: ((Answer) -> Unit)? = null

    /**
     * Shows [prompt] unless it is already on screen (READ_CONTENT keeps it open), then suspends
     * until one tap. Taps that arrive while nobody waits are dropped. Cancelling this coroutine
     * leaves closing the prompt to [run]'s finally block.
     */
    private suspend fun awaitAnswer(prompt: ChoicePrompt): Answer = suspendCancellableCoroutine { cont ->
        pending = { answer ->
            pending = null
            if (cont.isActive) cont.resume(answer)
        }
        cont.invokeOnCancellation { pending = null }
        if (shown !== prompt || !presenter.isShowing) {
            shown = prompt
            presenter.show(prompt, onMoveToButton = { deliver(Answer.Move(it)) }) { deliver(Answer.Picked(it)) }
        }
    }

    private fun deliver(answer: Answer) {
        pending?.invoke(answer)
    }

    private fun closePrompt() {
        shown = null
        pending = null
        presenter.dismiss()
        announcer.stop()
    }
}
