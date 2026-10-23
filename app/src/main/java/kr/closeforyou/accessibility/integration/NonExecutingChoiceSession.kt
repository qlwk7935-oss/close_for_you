package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicReference
import kr.closeforyou.accessibility.CollectionReleaseBatch
import kr.closeforyou.accessibility.action.GateClock
import kr.closeforyou.accessibility.action.NonExecutingActionExecutor
import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionCandidateWiring
import kr.closeforyou.decision.DecisionChoiceHandler
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.ui.voice.AnnouncementBatcher
import kr.closeforyou.ui.voice.integration.*
import kr.closeforyou.ui.voice.overlay.ChoicePromptPresenter
import kr.closeforyou.ui.voice.overlay.ChoiceOrigin

/** A-local subscription barrier. The real SharedFlow invokes subscribed AFTER registration. */
internal class ChoiceSubscription {
    private val ready = CompletableDeferred<Unit>()
    fun subscribed() { ready.complete(Unit) }
    suspend fun await() { ready.await(); currentCoroutineContext().ensureActive() }
    fun close() { ready.cancel() }
}

/** No Android action port. Even an unexpected gate error cannot become a possibly attempted click. */
internal class ReviewOnlyChoiceExecutor(clock: () -> Long) : ActionExecutor {
    private val gate = NonExecutingActionExecutor(clock = GateClock(clock))
    var reviews = 0
        private set
    override suspend fun execute(request: ActionRequest): ActionResult {
        currentCoroutineContext().ensureActive()
        reviews++
        try { gate.execute(request) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Fixed, never-executed result below. */ }
        currentCoroutineContext().ensureActive()
        return ActionResult(request.decision.token, ActionStatus.NOT_EXECUTED,
            SafetyReason.NOT_IMPLEMENTED, attempted = false, semanticSafetyConfirmedByUser = false)
    }
}

/** Identity only: a late completion/cancellation cannot clear a newer speech operation. */
internal class SpeechOperationOwner {
    private val active = AtomicReference<Any?>()
    fun begin(): Any = Any().also(active::set)
    fun owns(operation: Any): Boolean = active.get() === operation
    fun clear(operation: Any): Boolean = active.compareAndSet(operation, null)
    fun clear() { active.set(null) }
    override fun toString() = "SpeechOperationOwner(redacted)"
}

/** C's listener launches children on A's service scope. Nonfatal failures close only this UI session. */
internal fun choiceFailureHandler(close: () -> Unit) = CoroutineExceptionHandler { _, failure ->
    if (failure is Error) throw failure
    close() // No retained exception, cause, stack or screen payload.
}

/** C's repeat coroutine and synchronous stop button have their own UI scope. Fence our port here. */
internal class FailureBoundChoiceControls(
    private val delegate: PopupAnnouncer,
    private val onFailure: () -> Unit,
) : PopupAnnouncer {
    private var failed = false
    private fun reject() {
        if (failed) return
        failed = true
        try { onFailure() } catch (_: Exception) { /* Already refused; never leak a callback exception. */ }
    }
    override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
        currentCoroutineContext().ensureActive()
        if (failed) return unavailable()
        return try { delegate.announce(request) }
        catch (cancelled: CancellationException) {
            // Ordinary cancellation of an old C listener/control is not a new UI failure.
            if (currentCoroutineContext().isActive) reject()
            throw cancelled
        }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); reject(); unavailable() }
    }
    override suspend fun repeatLast(): AnnouncementReceipt {
        currentCoroutineContext().ensureActive()
        if (failed) return unavailable()
        return try { delegate.repeatLast() }
        catch (cancelled: CancellationException) {
            // Ordinary cancellation of an old C listener/control is not a new UI failure.
            if (currentCoroutineContext().isActive) reject()
            throw cancelled
        }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); reject(); unavailable() }
    }
    override fun stop() {
        if (failed) return
        try { delegate.stop() }
        catch (cancelled: CancellationException) { reject(); throw cancelled }
        catch (_: Exception) { reject() }
    }
    private fun unavailable() = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
    override fun toString() = "FailureBoundChoiceControls(redacted)"
}

/** C speech is transient and bound to the current A token, including the repeat button. */
internal class CurrentChoiceAnnouncer(
    private val delegate: PopupAnnouncer,
    private val current: () -> SnapshotToken?,
    private val forget: () -> Unit,
) : PopupAnnouncer {
    private var last: SnapshotToken? = null
    private var disposed = false
    private val operations = SpeechOperationOwner()
    override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
        currentCoroutineContext().ensureActive()
        val token = request.token
        if (disposed || token == null || current() != token) return unavailable()
        val operation = operations.begin()
        last = token
        return try {
            val result = delegate.announce(request)
            currentCoroutineContext().ensureActive()
            if (!operations.owns(operation)) unavailable()
            else if (disposed || current() != token) { stop(); unavailable() } else result
        } catch (cancelled: CancellationException) {
            try { if (operations.owns(operation)) stop() } catch (_: Exception) { /* Preserve cancellation. */ }
            throw cancelled
        } catch (_: Exception) {
            try { if (operations.owns(operation)) stop() } catch (_: Exception) { /* Preserve the primary failure. */ }
            throw IllegalStateException("CHOICE_SPEECH_FAILED")
        }
    }
    override suspend fun repeatLast(): AnnouncementReceipt {
        currentCoroutineContext().ensureActive()
        val token = last
        if (disposed || token == null || current() != token) { stop(); return unavailable() }
        val operation = operations.begin()
        return try {
            val result = delegate.repeatLast()
            currentCoroutineContext().ensureActive()
            if (!operations.owns(operation)) unavailable()
            else if (disposed || current() != token) { stop(); unavailable() } else result
        } catch (cancelled: CancellationException) {
            try { if (operations.owns(operation)) stop() } catch (_: Exception) { /* Preserve cancellation. */ }
            throw cancelled
        } catch (_: Exception) {
            try { if (operations.owns(operation)) stop() } catch (_: Exception) { /* Preserve the primary failure. */ }
            throw IllegalStateException("CHOICE_SPEECH_FAILED")
        }
    }
    override fun stop() {
        operations.clear()
        last = null
        val cleanup = CollectionReleaseBatch()
        cleanup.attempt(forget); cleanup.attempt(delegate::stop); cleanup.finish()
    }
    fun dispose() { disposed = true; stop() }
    fun permitsSpeech(): Boolean = !disposed && last != null && current() == last
    private fun unavailable() = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
    override fun toString() = "CurrentChoiceAnnouncer(redacted)"
}

/** Same cleanup path in JVM and service: every non-suspending callback is attempted independently. */
internal class ChoiceResources(private val releases: List<() -> Unit>) {
    private var closed = false
    var failed = false
        private set
    fun close(): Boolean {
        if (closed) return !failed
        closed = true
        releases.forEach { release -> try { release() } catch (_: Exception) { failed = true } }
        return !failed
    }
}

/** Real B wiring + C flow. No rules, microphone, focus moves or executing action implementation. */
internal class NonExecutingChoiceSession(
    source: SnapshotSource,
    extractor: EvidenceExtractor,
    presenter: ChoicePromptPresenter,
    private val announcer: PopupAnnouncer,
    userInteracting: () -> Boolean,
    announcementAvailable: () -> Boolean,
    private val clock: () -> Long,
    private val text: UiText,
    private val contentText: (PopupAnalysis) -> String,
    private val resources: ChoiceResources,
    private val presentationHealth: ChoicePresentationHealth = ChoicePresentationHealth(),
    private val diagnostic: ChoiceFlowDiagnostic? = null,
) {
    private var accepted: SnapshotToken? = null
    private var lastDelivered: SnapshotToken? = null
    private var closed = false
    val executor = ReviewOnlyChoiceExecutor(clock)
    private val safeSource = object : SnapshotSource {
        override fun currentToken(): SnapshotToken? =
            if (!closed && !presentationHealth.failed) source.currentToken()?.takeIf { it == accepted } else null
        override val events = flow {
            source.events.collect { event ->
                if (event is PopupEvent.Candidate) diagnostic?.record(ChoiceFlowPoint.CANDIDATE_RECEIVED)
                if (closed || presentationHealth.failed) {
                    if (event is PopupEvent.Candidate)
                        diagnostic?.record(ChoiceFlowPoint.CURRENT_REJECTED, ChoiceFlowDetail.SESSION_UNAVAILABLE)
                    return@collect
                }
                when (event) {
                    is PopupEvent.Candidate -> {
                        val token = event.snapshot.token
                        if (source.currentToken() != token) {
                            diagnostic?.record(ChoiceFlowPoint.CURRENT_REJECTED, ChoiceFlowDetail.TOKEN_MISMATCH)
                            return@collect
                        }
                        val signals = event.detection.signals
                        val now = clock()
                        val valid = event.snapshot.completeness == SnapshotCompleteness.COMPLETE &&
                            event.detection.token == token && event.detection.requiresUserReview &&
                            signals.any { it == PopupSignal.NEW_WINDOW || it == PopupSignal.SAME_WINDOW_OVERLAY } &&
                            PopupSignal.INSUFFICIENT_EVIDENCE !in signals && PopupSignal.FULL_SCREEN_TRANSITION !in signals &&
                            event.snapshot.capturedAtUptimeMillis >= 0 && now >= event.snapshot.capturedAtUptimeMillis &&
                            now - event.snapshot.capturedAtUptimeMillis < 5_000
                        if (!valid) {
                            diagnostic?.record(ChoiceFlowPoint.CURRENT_REJECTED, ChoiceFlowDetail.INVALID_CANDIDATE)
                            accepted = null
                            emit(PopupEvent.Blocked(PrivacyBlockReason.UNREADABLE_TREE, token.screenVersion))
                        } else if (lastDelivered != token) {
                            accepted = token; lastDelivered = token
                            diagnostic?.record(ChoiceFlowPoint.CURRENT_ACCEPTED)
                            emit(event)
                        } else {
                            diagnostic?.record(ChoiceFlowPoint.CURRENT_REJECTED, ChoiceFlowDetail.DUPLICATE)
                        }
                    }
                    is PopupEvent.Blocked, is PopupEvent.Invalidated -> {
                        diagnostic?.record(ChoiceFlowPoint.INVALIDATED, ChoiceFlowDetail.CONSUMER_BOUNDARY)
                        accepted = null
                        lastDelivered = null
                        emit(event)
                    }
                }
            }
        }
    }
    private val wiring = DecisionCandidateWiring(safeSource, extractor)
    private val choiceHandler = DecisionChoiceHandler(wiring.source, executor, userInteracting, announcementAvailable)
    private val safePresenter = GuardedChoicePresenter(presenter, safeSource::currentToken,
        userInteracting, presentationHealth, ::close)
    private var batcher: AnnouncementBatcher? = null
    private var runOwner: Job? = null
    suspend fun run() = coroutineScope {
        runOwner = currentCoroutineContext()[Job]
        val batch = AnnouncementBatcher(this, onFlush = { /* No executed actions can be batched. */ })
        batcher = batch
        try {
            PopupChoiceFlow(wiring.source, wiring.extractor, diagnosedClassifier(DecisionPopupClassifier(), diagnostic),
                diagnosedPresenter(safePresenter, diagnostic),
                announcer, batch, object : ChoiceHandler {
                    override suspend fun handle(choice: UserChoice, analysis: PopupAnalysis) = reviewChoice(choice, analysis)
                    override suspend fun handle(choice: UserChoice, analysis: PopupAnalysis, origin: ChoiceOrigin) =
                        reviewChoice(choice, analysis) // C metadata is not a proof of user authorization.
                }, text, contentText, moveToButtonEnabled = false, rememberChoicesEnabled = false,
                rememberStore = RememberedPopupStore.NONE, autoCloseEnabledForApp = { false }).run()
        } catch (cancelled: CancellationException) {
            diagnostic?.record(ChoiceFlowPoint.SESSION_CANCELLED)
            throw cancelled
        } catch (failure: Exception) {
            diagnostic?.record(ChoiceFlowPoint.SESSION_EXCEPTION)
            throw failure
        } finally {
            runOwner = null
            withContext(NonCancellable) { close() }
        }
    }
    private suspend fun reviewChoice(choice: UserChoice, analysis: PopupAnalysis): ActionResult? {
        currentCoroutineContext().ensureActive()
        return try { choiceHandler.handle(choice, analysis) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ActionResult(analysis.token, ActionStatus.NOT_EXECUTED,
            SafetyReason.NOT_IMPLEMENTED, attempted = false) }
    }
    fun invalidate() {
        accepted = null
        safePresenter.revoke()
    }
    fun close() {
        closed = true; accepted = null; lastDelivered = null
        safePresenter.revoke()
        runOwner?.cancel(CancellationException("CHOICE_SESSION_CLOSED")); runOwner = null
        batcher?.cancel(); batcher = null
        resources.close()
        if (resources.failed) presentationHealth.fail()
    }
    override fun toString() = "NonExecutingChoiceSession(redacted)"
}
