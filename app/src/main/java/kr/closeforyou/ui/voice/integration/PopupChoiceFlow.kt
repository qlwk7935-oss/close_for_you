package kr.closeforyou.ui.voice.integration

import kotlin.coroutines.resume
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kr.closeforyou.R
import kr.closeforyou.core.contract.AnnouncementKind
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.contract.EvidenceExtractor
import kr.closeforyou.core.contract.PopupAnnouncer
import kr.closeforyou.core.contract.PopupClassifier
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.contract.SnapshotSource
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.core.model.UserChoice
import kr.closeforyou.ui.voice.AnnouncementBatcher
import kr.closeforyou.ui.voice.PressFailure
import kr.closeforyou.ui.voice.PressResultSpeech
import kr.closeforyou.ui.voice.PressSuccess
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.MoveToButtonFeature
import kr.closeforyou.ui.voice.choice.MoveToButtonRequest
import kr.closeforyou.ui.voice.choice.PressableLabelPolicy
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.choice.RememberChoiceFeature
import kr.closeforyou.ui.voice.choice.RememberChoiceSpeech
import kr.closeforyou.ui.voice.choice.SharedLabelPolicy
import kr.closeforyou.ui.voice.choice.VideoAdAnnouncementFeature
import kr.closeforyou.ui.voice.choice.VideoAdInfo
import kr.closeforyou.ui.voice.overlay.ChoiceOrigin
import kr.closeforyou.ui.voice.overlay.ChoicePromptPresenter
import kr.closeforyou.ui.voice.overlay.OverlayWindowKeeper
import kr.closeforyou.ui.voice.overlay.OverlayWindowStatus
import kr.closeforyou.ui.voice.overlay.PromptAnswer
import kr.closeforyou.ui.voice.overlay.PromptShowResult
import kr.closeforyou.ui.voice.overlay.RevalidatingFeature
import kr.closeforyou.ui.voice.overlay.RevalidationCause
import kr.closeforyou.ui.voice.overlay.RevalidationEnd

/**
 * A (or B) hands C's choice to the safety path: B's ActionPolicy, then A's single action gate.
 * C never calls ActionExecutor itself. Return the gate's [ActionResult] for CLOSE_ONCE/SKIP_ONCE,
 * or null for choices that press nothing (KEEP_OPEN). It never receives REMEMBER_EXACT_POPUP:
 * [PopupChoiceFlow] sends that as this popup's one-time press first (see [PopupChoiceFlow]).
 */
fun interface ChoiceHandler {
    suspend fun handle(choice: UserChoice, analysis: PopupAnalysis): ActionResult?

    /**
     * What [PopupChoiceFlow] calls: [handle] plus how the person chose (C-internal, not in
     * core/contract). Override it to record the origin; the default ignores it. Never the words heard.
     */
    suspend fun handle(choice: UserChoice, analysis: PopupAnalysis, origin: ChoiceOrigin): ActionResult? =
        handle(choice, analysis)
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
 * - The prompt could not be shown, or its window could not be closed after a choice: this popup
 *   ends with no choice, [handler] is not called, and one short sentence says so (review #18-1·2).
 * - Only answers of the prompt generation now on screen are taken; a late tap or voice result of
 *   an older, closed or failed prompt is dropped (review #18).
 * - [앞으로 항상 닫기] (REMEMBER_EXACT_POPUP, team decision 10/20 (가)): B's policy does not press
 *   for REMEMBER_EXACT_POPUP, so [handler] gets the same token and candidate as CLOSE_ONCE (SKIP_ONCE
 *   for a skip button, as B checks the role). The rule key is prepared by [rememberStore] before
 *   the press (team decision 10/21); only [PressSuccess.isConfirmed] (attempted + MECHANICAL_SUCCESS +
 *   CLOSED_CONFIRMED) commits it, once; any other result
 *   drops it and is spoken like a one-time press. The handler is still called at most once per tap.
 * - REVALIDATING ([revalidatingEnabled], off by default; off = everything above unchanged):
 *   `Invalidated` puts the prompt on screen into "다시 확인 중" instead of closing it (signal 1).
 *   The next `Candidate` for which A's [isSamePopup] says yes refills the same window (signal 2);
 *   any other `Candidate` closes it with one sentence and shows its own prompt, `Blocked` closes it
 *   silently (signal 3). Whether it is "the same popup" is A's rule only: C never guesses, and
 *   until A provides [isSamePopup] every candidate counts as different. A can also call
 *   [ChoicePromptPresenter.endRevalidation] directly; with no answer the window closes after
 *   [kr.closeforyou.ui.voice.overlay.RevalidatingFeature.TIMEOUT_MILLIS].
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
    private val labelPolicy: PressableLabelPolicy = SharedLabelPolicy,
    private val moveToButtonEnabled: Boolean = MoveToButtonFeature.enabled,
    private val rememberChoicesEnabled: Boolean = RememberChoiceFeature.enabled,
    private val rememberStore: RememberedPopupStore = RememberedPopupStore.NONE,
    /** C's per-app auto close setting (default off); decides the sentence after remembering. */
    private val autoCloseEnabledForApp: (packageName: String) -> Boolean = { false },
    /**
     * B's F5 facts for this analysis, from A's assembly:
     * `{ VideoAdInfo.from(DecisionVideoAd.inspect(it)) }`. Wording only (see [ChoicePromptPlanner.plan]).
     */
    private val videoAdOf: (PopupAnalysis) -> VideoAdInfo? = { null },
    private val videoAdEnabled: Boolean = VideoAdAnnouncementFeature.enabled,
    /** [RevalidatingFeature]; false keeps the old "invalidated → close" path exactly. */
    private val revalidatingEnabled: Boolean = RevalidatingFeature.enabled,
    /**
     * A's signal 2, asked on the main thread only while C's window is REVALIDATING: true when A's
     * new F0 check says [PopupEvent.Candidate] is the popup the prompt was asking about.
     * [팀 확정 필요] No A signal exists yet, so the default says "different" for every candidate.
     */
    private val isSamePopup: (PopupEvent.Candidate) -> Boolean = { false },
) {
    suspend fun run() {
        if (!revalidatingEnabled) {
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
            return
        }
        try {
            source.events.collectLatest { event ->
                when (event) {
                    is PopupEvent.Candidate -> {
                        val same = presenter.windowState.status == OverlayWindowStatus.REVALIDATING && isSamePopup(event)
                        if (!same) closeOrEndRevalidation(RevalidationEnd.DIFFERENT_POPUP)
                        try {
                            handleCandidate(event, confirmSamePopup = same)
                        } finally {
                            // Cancelled by a newer event: that event decides what happens to the
                            // window (an Invalidated one may keep it for the re-check). Finished: close.
                            if (currentCoroutineContext().isActive) closePrompt() else forgetPrompt()
                        }
                    }
                    is PopupEvent.Invalidated -> {
                        forgetPrompt()
                        announcer.stop()
                        presenter.beginRevalidation(RevalidationCause.SCREEN_EVENT)
                    }
                    is PopupEvent.Blocked -> closePrompt()
                }
            }
        } finally {
            // The flow ends (service stops) while a window may be revalidating with no work running.
            closePrompt()
        }
    }

    /** A new candidate that is not "the same popup": a revalidating window says why it closes. */
    private fun closeOrEndRevalidation(end: RevalidationEnd) {
        if (presenter.windowState.status == OverlayWindowStatus.REVALIDATING) {
            forgetPrompt()
            presenter.endRevalidation(end)
        } else {
            closePrompt()
        }
    }

    private suspend fun handleCandidate(event: PopupEvent.Candidate, confirmSamePopup: Boolean = false) {
        val token = event.snapshot.token
        if (source.currentToken() != token) return
        val evidence = extractor.extract(event.snapshot)
        if (source.currentToken() != token) return
        val classification = classifier.classify(evidence)
        val analysis = evidence.copy(classification = classification)
        if (source.currentToken() != token) return

        val videoAd = if (videoAdEnabled) videoAdOf(analysis) else null
        var prompt: ChoicePrompt? = ChoicePromptPlanner.plan(
            analysis, classification, labelPolicy, moveToButtonEnabled, rememberChoicesEnabled, videoAd, videoAdEnabled,
        )
        // Only the first prompt of a confirmed same popup goes into the revalidating window.
        var refill = confirmSamePopup
        while (prompt != null) {
            val answer = awaitAnswer(prompt, refill)
            refill = false
            prompt = when (answer) {
                is Answer.Move -> {
                    onMoveToButton(answer.request)
                    null
                }
                is Answer.Picked -> afterChoice(prompt, answer.choice, answer.origin, event.snapshot, analysis)
                is Answer.NotShown -> {
                    // The popup is still there for TalkBack; the person handles it directly.
                    say(token, AnnouncementKind.POPUP_FOUND, R.string.ui_prompt_show_failed)
                    null
                }
                is Answer.Lost -> {
                    say(token, AnnouncementKind.ACTION_RESULT, R.string.ui_prompt_window_stuck)
                    null
                }
            }
            if (source.currentToken() != token) return
        }
    }

    /** Returns the prompt to show next, or null when this popup is done. */
    private suspend fun afterChoice(
        prompt: ChoicePrompt,
        choice: UserChoice,
        origin: ChoiceOrigin,
        snapshot: PopupSnapshot,
        analysis: PopupAnalysis,
    ): ChoicePrompt? {
        if (choice.kind == ChoiceKind.READ_CONTENT) {
            announcer.announce(AnnouncementRequest(choice.token, AnnouncementKind.CONTENT, contentText(analysis)))
            return prompt // still on screen; wait for the next tap
        }
        val remember = choice.kind == ChoiceKind.REMEMBER_EXACT_POPUP
        val sent = if (remember) oneTimePressFor(choice, analysis) ?: return null else choice
        // The key is made before the press, on the same token: after a successful press the popup
        // is gone and the fingerprint cannot find its button. Null still presses once, saves nothing.
        // [팀 확정 필요] prepare/commit live in C's flow for now; where they sit is agreed when A assembles.
        val prepared = if (remember) rememberStore.prepare(choice, snapshot, analysis) else null
        val result = handler.handle(sent, analysis, origin) ?: return null
        if (!PressResultSpeech.isPressChoice(sent.kind)) return null
        if (PressSuccess.isConfirmed(result)) {
            AnnouncementBatcher.outcomeOf(result, sent.kind)?.let(batcher::add)
            if (remember && result.token == choice.token) rememberAfterSuccess(choice, prepared)
            return null
        }
        // Not pressed or not confirmed closed (any status without attempted + MECHANICAL_SUCCESS +
        // CLOSED_CONFIRMED): the prepared key is dropped here, nothing is saved.
        val failure = PressResultSpeech.failureOf(result) ?: PressFailure.NOT_CONFIRMED
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

    /**
     * [앞으로 항상 닫기] → this popup's one-time press on the same button. B checks that the kind
     * matches the button's role, so a skip button becomes SKIP_ONCE. Null when the button is not
     * in this analysis (nothing is sent).
     */
    private fun oneTimePressFor(choice: UserChoice, analysis: PopupAnalysis): UserChoice? {
        val role = analysis.closeCandidates.firstOrNull { it.id == choice.candidateId }?.role ?: return null
        val kind = when (role) {
            ButtonRole.CLOSE -> ChoiceKind.CLOSE_ONCE
            ButtonRole.SKIP -> ChoiceKind.SKIP_ONCE
            else -> return null
        }
        return UserChoice(
            token = choice.token,
            kind = kind,
            candidateId = choice.candidateId,
            buttonMeaningConfirmed = choice.buttonMeaningConfirmed,
            repeatAutomationExplicitlyAllowed = false,
        )
    }

    /**
     * Saves the key prepared before the press, once, and says what happened. The popup is gone by
     * now, so A's next event may cancel this popup's work; the short local save the person asked
     * for still finishes. The close itself is counted by [batcher]; this adds only the remember
     * sentence. A failed save (no key, D rejected the rule) is told, never swallowed.
     */
    private suspend fun rememberAfterSuccess(choice: UserChoice, prepared: PreparedRule?) {
        val saved = prepared != null && prepared.token == choice.token &&
            withContext(NonCancellable) { rememberStore.commit(prepared) }
        val sentence = if (saved) {
            text.get(R.string.ui_remember_saved) + " " +
                text.get(RememberChoiceSpeech.afterRememberRes(autoCloseEnabledForApp(choice.token.packageName)))
        } else {
            text.get(R.string.ui_remember_not_saved)
        }
        announcer.announce(AnnouncementRequest(choice.token, AnnouncementKind.ACTION_RESULT, sentence))
    }

    private suspend fun say(token: SnapshotToken, kind: AnnouncementKind, resId: Int) {
        announcer.announce(AnnouncementRequest(token, kind, text.get(resId)))
    }

    private sealed interface Answer {
        data class Picked(val choice: UserChoice, val origin: ChoiceOrigin) : Answer
        data class Move(val request: MoveToButtonRequest) : Answer

        /** [ChoicePromptPresenter.present] did not show the prompt (failed or blocked). */
        data object NotShown : Answer

        /** The person chose but the window could not be closed; the choice was dropped. */
        data object Lost : Answer
    }

    /** The prompt currently on screen, its generation, and who receives its next tap (null while busy). */
    private var shown: ChoicePrompt? = null
    private var shownGeneration = OverlayWindowKeeper.NO_GENERATION
    private var pending: ((Answer) -> Unit)? = null

    /**
     * Shows [prompt] unless it is already on screen (READ_CONTENT keeps it open), then suspends
     * until one tap. Taps that arrive while nobody waits are dropped. Cancelling this coroutine
     * leaves closing the prompt to [run]'s finally block.
     */
    private suspend fun awaitAnswer(prompt: ChoicePrompt, refill: Boolean = false): Answer = suspendCancellableCoroutine { cont ->
        pending = { answer ->
            pending = null
            if (cont.isActive) cont.resume(answer)
        }
        cont.invokeOnCancellation { pending = null }
        if (shown !== prompt || !presenter.isShowing) {
            shown = prompt
            shownGeneration = OverlayWindowKeeper.NO_GENERATION
            // confirmSamePopup falls back to a new window when the re-check already timed out.
            val result = if (refill) presenter.confirmSamePopup(prompt, ::deliver) else presenter.present(prompt, ::deliver)
            if (result == PromptShowResult.SHOWN) {
                shownGeneration = presenter.windowState.generation
            } else {
                shown = null
                pending?.invoke(Answer.NotShown)
            }
        }
    }

    /** Drops answers of any other prompt generation (a presenter without generations reports none). */
    private fun deliver(answer: PromptAnswer) {
        if (shownGeneration != OverlayWindowKeeper.NO_GENERATION && answer.generation != shownGeneration) return
        val next = when (answer) {
            is PromptAnswer.Picked -> Answer.Picked(answer.choice, answer.origin)
            is PromptAnswer.Move -> Answer.Move(answer.request)
            is PromptAnswer.Lost -> Answer.Lost
        }
        pending?.invoke(next)
    }

    private fun closePrompt() {
        forgetPrompt()
        presenter.dismiss()
        announcer.stop()
    }

    /** Lets go of the prompt without touching the window: no answer of it reaches B/A any more. */
    private fun forgetPrompt() {
        shown = null
        shownGeneration = OverlayWindowKeeper.NO_GENERATION
        pending = null
    }
}
