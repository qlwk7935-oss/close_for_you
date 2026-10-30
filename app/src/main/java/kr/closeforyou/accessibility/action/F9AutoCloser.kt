package kr.closeforyou.accessibility.action

import kotlinx.coroutines.*
import kr.closeforyou.core.model.*
import kr.closeforyou.accessibility.integration.ManualActionTrace
import kr.closeforyou.accessibility.integration.ManualActionStep
import kr.closeforyou.accessibility.integration.ManualActionState
import kr.closeforyou.accessibility.integration.ManualActionReason
import kr.closeforyou.ui.voice.PressSuccess

/** F9-local clock: no dependency on the separate, unmerged #29 read-only gate. */
internal fun interface F9Clock { fun nowMillis(): Long }

/** A-owned receipt containing the merged #38 contracts. Display classification never grants it. */
internal enum class F9ApprovalOrigin { CONTRACT_V2, SYNTHETIC_TEST }
internal data class F9Approval(val decision: ActionDecision, val eligibility: AutoEligibility, val origin: F9ApprovalOrigin) {
    override fun toString() = "F9Approval(redacted)"
}
internal data class F9PageProof(val packageName: String, val windowId: Int, val digest: String, val capturedAtUptimeMillis: Long) {
    override fun toString() = "F9PageProof(redacted)"
}
internal data class F9Reference(val snapshot: PopupSnapshot, val candidate: CloseCandidate,
    val serviceGeneration: Long, val policyRevision: Long, val pageBeforePopup: F9PageProof? = null,
    val userChoice: UserChoice? = null) {
    override fun toString() = "F9Reference(redacted)"
}
internal data class F9RuntimeState(val token: SnapshotToken? = null, val serviceGeneration: Long = -1,
    val policyRevision: Long = -1, val connected: Boolean = false, val collectionConsent: Boolean = false,
    val appAllowed: Boolean = false, val autoCloseConsent: Boolean = false, val presetAllowsAd: Boolean = false,
    val paused: Boolean = true, val userInteracting: Boolean = true, val cleanupReady: Boolean = false) {
    override fun toString() = "F9RuntimeState(redacted)"
}
internal enum class F9Block { POLICY_UNAVAILABLE, POLICY_DENIED, STALE, SETTINGS, CLEANUP, SCREEN_UNVERIFIED,
    ALREADY_RESERVED, SESSION_ENDED, LEFT_APP_BEFORE, LIMIT, READ_FAILED, CANCELLED }
internal data class F9Result(val token: SnapshotToken, val attempted: Boolean, val clickReturned: Boolean? = null,
    val outcome: ClickOutcome? = null, val block: F9Block? = null) {
    init { require(outcome == null || attempted); require(!attempted || block == null) }
    val closedConfirmed get() = PressSuccess.isConfirmed(toActionResult())
    /** Preserve mechanical delivery separately from the observed outcome in the shared contract. */
    fun toActionResult() = ActionResult(token, when {
        !attempted && block == F9Block.CANCELLED -> ActionStatus.NOT_EXECUTED
        !attempted && block == F9Block.STALE -> ActionStatus.STALE
        !attempted -> ActionStatus.BLOCKED
        clickReturned == true -> ActionStatus.MECHANICAL_SUCCESS
        clickReturned == false -> ActionStatus.ATTEMPT_FAILED
        else -> ActionStatus.OUTCOME_UNKNOWN
    }, when (block) {
        null, F9Block.CANCELLED -> null
        F9Block.POLICY_UNAVAILABLE -> SafetyReason.NOT_IMPLEMENTED
        F9Block.POLICY_DENIED -> SafetyReason.NOT_AUTO_ELIGIBLE
        F9Block.STALE, F9Block.SESSION_ENDED -> SafetyReason.STALE_SCREEN
        F9Block.SETTINGS -> SafetyReason.EXPLICIT_PERMISSION_REQUIRED
        F9Block.ALREADY_RESERVED -> SafetyReason.ALREADY_ATTEMPTED
        F9Block.LEFT_APP_BEFORE -> SafetyReason.LEFT_APP_BEFORE
        F9Block.LIMIT -> SafetyReason.AUTO_LIMIT_REACHED
        else -> SafetyReason.UNVERIFIED_TARGET
    }, attempted = attempted, outcome = outcome)
    override fun toString() = "F9Result(attempted=$attempted, outcome=$outcome, block=$block)"
}
internal data class F9Press(val attempted: Boolean, val returned: Boolean?)

/** Only immutable descriptors leave the synchronous Android reader. Node handles never do. */
internal data class F9Scene(val packageName: String, val windowId: Int, val pageWindowId: Int,
    val screen: ScreenBounds, val windows: List<WindowSnapshot>, val nodes: List<NodeSnapshot>,
    val visiblePaths: Set<String>, val complete: Boolean, val pageDigest: String? = null,
    val nativeActions: kr.closeforyou.accessibility.NativeNodeActions? = null) {
    override fun toString() = "F9Scene(redacted)"
}
internal interface F9ScreenPort {
    fun read(checkpoint: () -> Unit): F9Scene?
    /** Same single read, with an A-local diagnostic purpose. Existing/fake ports keep their behavior. */
    fun readFor(purpose: F9ReadPurpose, checkpoint: () -> Unit): F9Scene? = read(checkpoint)
    /** Reacquire the whole screen and target, call accept immediately before exactly one ACTION_CLICK.
     * No suspension, parent fallback, gestures or retries. Exceptions AFTER invocation are attempted.
     */
    fun press(reference: F9Reference, accept: (F9Scene) -> Boolean): F9Press
}
internal interface F9PolicyBridge {
    /** B must examine ALL popup text and supply its v2 PRESET/EXECUTE authorization + five checks.
     * A pre-v2 ALLOW_ONCE for a UserChoice is deliberately NOT an automatic authorization.
     */
    suspend fun authorize(reference: F9Reference, scene: F9Scene): F9Approval?
    fun onActionResult(result: ActionResult)
    /** One terminal result per A invocation. A rejection before authorize must not consume B's
     * pending approval for another in-flight invocation of the same popup. Test/audit bridges may
     * record both; the real B bridge forwards only requests for which its policy was consulted.
     */
    fun onExecutionResult(result: ActionResult, policyConsulted: Boolean) = onActionResult(result)
    companion object {
        val UNAVAILABLE = object : F9PolicyBridge {
            override suspend fun authorize(reference: F9Reference, scene: F9Scene): F9Approval? = null
            override fun onActionResult(result: ActionResult) = Unit
        }
    }
}

/** Session-owned, main-thread action coordinator. Separate from the old read-only gate: none of its
 * synthetic PASSED values or read-only probe outcomes can authorize this executing path.
 * Production remains disconnected until B's policy receipt and C's preset/consent are integrated.
 */
internal class F9AutoCloser(
    private val screen: F9ScreenPort,
    private val runtime: () -> F9RuntimeState,
    private val clock: F9Clock,
    private val policy: F9PolicyBridge = F9PolicyBridge.UNAVAILABLE,
    private val announce: (F9Result) -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val observationDelays: List<Long> = listOf(100L, 200L, 400L, 500L),
    private val allowSyntheticPolicy: Boolean = false,
) {
    private data class Key(val generation: Long, val app: String, val structure: String, val content: String) {
        override fun toString() = "F9Key(redacted)"
    }
    private val lock = Any()
    private val reserved = mutableSetOf<Key>()
    private val leftApps = mutableSetOf<String>()
    private var stopped = false
    private var running = false

    fun close() = synchronized(lock) { stopped = true }

    suspend fun execute(reference: F9Reference, requestPolicy: F9PolicyBridge = policy, trace: ManualActionTrace? = null): F9Result {
        // One delivery owner per invocation, including early rejects and cancellation at dispatch.
        // Callbacks run on the same dispatcher as B's session state. A throwing receiver is never retried.
        val delivered = java.util.concurrent.atomic.AtomicBoolean()
        var policyConsulted = false
        var matchFailure: F9MatchFailure? = null
        fun complete(result: F9Result): F9Result {
            if (delivered.compareAndSet(false, true)) {
                if (!result.attempted) trace?.record(ManualActionStep.F9_VALIDATION, ManualActionState.REJECTED, block = result.block, screenMatch = matchFailure)
                trace?.record(ManualActionStep.CLICK_ATTEMPT,
                    if (result.attempted) ManualActionState.ATTEMPTED else ManualActionState.NOT_ATTEMPTED)
                trace?.record(ManualActionStep.CLICK_RESULT,
                    if (result.attempted) ManualActionState.COMPLETED else ManualActionState.NOT_ATTEMPTED,
                    reason = when (result.clickReturned) {
                        true -> ManualActionReason.DELIVERY_TRUE
                        false -> ManualActionReason.DELIVERY_FALSE
                        null -> ManualActionReason.DELIVERY_UNKNOWN
                    }, block = result.block, outcome = result.outcome)
                try { requestPolicy.onExecutionResult(result.toActionResult(), policyConsulted) } catch (_: Exception) { /* No screen-data logging. */ }
            }
            return result
        }
        return try {
            withContext(dispatcher) {
                executeOnMain(reference, ::complete, requestPolicy, trace, { failure ->
                    // Fixed code only; no diagnostic I/O in the final acceptance/press boundary.
                    if (trace != null && matchFailure == null) matchFailure = failure
                }) { scene ->
                    policyConsulted = true
                    requestPolicy.authorize(reference, scene)
                }
            }
        } catch (cancelled: CancellationException) {
            // An attempted cancellation was already reported inside executeOnMain. Otherwise send
            // a truthful no-press result, even if cancellation prevented the Main block from starting.
            withContext(NonCancellable + dispatcher) {
                complete(F9Result(reference.snapshot.token, false, block = F9Block.CANCELLED))
            }
            throw cancelled
        }
    }

    private suspend fun executeOnMain(reference: F9Reference, complete: (F9Result) -> F9Result, requestPolicy: F9PolicyBridge,
        trace: ManualActionTrace?, matchRejected: (F9MatchFailure) -> Unit,
        authorize: suspend (F9Scene) -> F9Approval?): F9Result {
        val token = reference.snapshot.token
        fun blocked(reason: F9Block) = complete(F9Result(token, false, block = reason))
        val jobContext = currentCoroutineContext()
        fun checkpoint() { jobContext.ensureActive(); check(!synchronized(lock) { stopped }) }
        jobContext.ensureActive()
        trace?.record(ManualActionStep.F9_VALIDATION, ManualActionState.STARTED)
        if (requestPolicy === F9PolicyBridge.UNAVAILABLE) return blocked(F9Block.POLICY_UNAVAILABLE)
        val stateDenial = try { stateBlock(reference) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { F9Block.READ_FAILED }
        stateDenial?.let { return blocked(it) }
        val key = Key(reference.serviceGeneration, token.packageName, token.structureDigest, token.contentDigest)
        val denial = synchronized(lock) {
            when {
                stopped -> F9Block.SESSION_ENDED
                token.packageName in leftApps -> F9Block.LEFT_APP_BEFORE
                running || key in reserved -> F9Block.ALREADY_RESERVED
                reserved.size >= 256 -> F9Block.LIMIT // Never evict history and accidentally retry.
                else -> { reserved += key; running = true; null }
            }
        }
        if (denial != null) return blocked(denial)
        var press = F9Press(false, null)
        var outcome = ClickOutcome.UNKNOWN
        var published = false
        fun publish(): F9Result {
            val result = F9Result(token, true, press.returned, outcome)
            synchronized(lock) { if (outcome == ClickOutcome.LEFT_APP) leftApps += token.packageName }
            // Exactly one callback each. Observer errors cannot retry an already invoked action.
            if (!published) {
                published = true
                complete(result)
                try { announce(result) } catch (_: Exception) { /* No payload logging. */ }
            }
            return result
        }
        return try {
            checkpoint()
            val initial = screen.readFor(F9ReadPurpose.PRE_CLICK, ::checkpoint) ?: return blocked(F9Block.SCREEN_UNVERIFIED)
            if (!matches(reference, initial, matchRejected)) return blocked(F9Block.SCREEN_UNVERIFIED)
            val approval = authorize(initial) ?: return blocked(F9Block.POLICY_DENIED)
            checkpoint()
            if (!approved(reference, approval)) return blocked(F9Block.POLICY_DENIED)
            var finalBlock: F9Block? = null
            var finalAccepted = false
            press = screen.press(reference) { fresh ->
                // Synchronous on Main. No queued callback/coroutine can split this check and invocation.
                checkpoint()
                finalBlock = stateBlock(reference)
                (finalBlock == null && approved(reference, approval) && fresh == initial && matches(reference, fresh, matchRejected))
                    .also { finalAccepted = it } // No diagnostic I/O between validation and ACTION_CLICK.
            }
            if (finalAccepted) trace?.record(ManualActionStep.F9_VALIDATION, ManualActionState.PASSED)
            trace?.record(ManualActionStep.CLICK_ATTEMPT,
                if (press.attempted) ManualActionState.ATTEMPTED else ManualActionState.NOT_ATTEMPTED)
            if (!press.attempted) return blocked(finalBlock ?: F9Block.SCREEN_UNVERIFIED)
            for (pause in observationDelays) {
                delay(pause)
                checkpoint()
                val state = runtime()
                if (!state.connected || state.serviceGeneration != reference.serviceGeneration || state.userInteracting ||
                    state.paused || !state.collectionConsent || !state.appAllowed ||
                    (reference.userChoice == null && !state.autoCloseConsent) ||
                    !state.cleanupReady || state.policyRevision != reference.policyRevision) break
                val observed = screen.readFor(F9ReadPurpose.POST_CLICK, ::checkpoint)
                outcome = observedOutcome(initial, observed, reference.pageBeforePopup)
                if (outcome == ClickOutcome.CLOSED_CONFIRMED || outcome == ClickOutcome.LEFT_APP) break
            }
            publish()
        } catch (cancelled: CancellationException) {
            if (press.attempted) { outcome = ClickOutcome.UNKNOWN; publish() }
            throw cancelled
        } catch (_: Exception) {
            if (press.attempted) { outcome = ClickOutcome.UNKNOWN; publish() }
            else blocked(if (synchronized(lock) { stopped }) F9Block.SESSION_ENDED else F9Block.READ_FAILED)
        } finally { synchronized(lock) { running = false } }
    }

    private fun approved(reference: F9Reference, approval: F9Approval): Boolean =
        (approval.origin == F9ApprovalOrigin.CONTRACT_V2 || allowSyntheticPolicy) &&
            approval.decision.token == reference.snapshot.token &&
            approval.decision.authorization == ActionAuthorization.ALLOW_ONCE && approval.decision.reasons.isEmpty() &&
            (if (reference.userChoice == null) approval.decision.source == DecisionSource.PRESET else
                approval.decision.source == DecisionSource.USER_CHOICE && reference.userChoice.token == reference.snapshot.token &&
                    reference.userChoice.kind == ChoiceKind.CLOSE_ONCE && reference.userChoice.buttonMeaningConfirmed &&
                    reference.userChoice.candidateId == reference.candidate.id) && approval.decision.next == NextStep.EXECUTE &&
            approval.decision.candidateId == reference.candidate.id &&
            approval.eligibility.capturedAtUptimeMillis == reference.snapshot.capturedAtUptimeMillis &&
            approval.eligibility.capturedAtUptimeMillis >= 0 && approval.eligibility.validFor(reference.snapshot.token, clock.nowMillis())

    private fun stateBlock(r: F9Reference): F9Block? {
        if (synchronized(lock) { stopped }) return F9Block.SESSION_ENDED
        val state = runtime(); val captured = r.snapshot.capturedAtUptimeMillis; val now = clock.nowMillis()
        return when {
            !state.connected || state.serviceGeneration != r.serviceGeneration -> F9Block.SESSION_ENDED
            state.token != r.snapshot.token || r.snapshot.token.captureEpoch != r.snapshot.token.screenVersion ||
                captured < 0 || now < captured || now - captured >= 5_000 -> F9Block.STALE
            !state.collectionConsent || !state.appAllowed ||
                (r.userChoice == null && (!state.autoCloseConsent || !state.presetAllowsAd)) ||
                state.paused || state.userInteracting || state.policyRevision != r.policyRevision -> F9Block.SETTINGS
            !state.cleanupReady -> F9Block.CLEANUP
            else -> null
        }
    }

    private fun matches(r: F9Reference, scene: F9Scene, rejected: (F9MatchFailure) -> Unit): Boolean {
        fun reject(reason: F9MatchFailure): Boolean { rejected(reason); return false }
        val s = r.snapshot; val c = r.candidate
        // Preserve the original left-to-right short circuit and the original lazy clock read.
        if (!scene.complete) return reject(F9MatchFailure.SCENE_INCOMPLETE)
        if (s.completeness != SnapshotCompleteness.COMPLETE) return reject(F9MatchFailure.SNAPSHOT_INCOMPLETE)
        if (scene.packageName != s.token.packageName) return reject(F9MatchFailure.PACKAGE_MISMATCH)
        if (F9ContentSafety.protectedPackage(scene.packageName)) return reject(F9MatchFailure.PROTECTED_PACKAGE)
        if (scene.windowId != s.token.windowId) return reject(F9MatchFailure.WINDOW_ID_MISMATCH)
        if (scene.pageDigest == null) return reject(F9MatchFailure.PAGE_DIGEST_UNAVAILABLE)
        if (scene.screen != s.screenBounds) return reject(F9MatchFailure.SCREEN_BOUNDS_MISMATCH)
        if (scene.nodes != s.nodes) return reject(F9MatchFailure.NODES_MISMATCH)
        if (scene.windows != s.windows) return reject(F9MatchFailure.WINDOWS_MISMATCH)
        if (scene.nodes.size !in 1..1_200) return reject(F9MatchFailure.NODE_COUNT_INVALID)
        if (scene.nodes.any { listOfNotNull(it.text, it.contentDescription).any(F9ContentSafety::blocked) })
            return reject(F9MatchFailure.PROTECTED_TEXT)
        val window = scene.windows.singleOrNull { it.id == scene.windowId }
            ?: return reject(F9MatchFailure.TARGET_WINDOW_UNAVAILABLE)
        if (window.type != WindowType.APPLICATION) return reject(F9MatchFailure.TARGET_WINDOW_TYPE)
        if (!window.active) return reject(F9MatchFailure.TARGET_WINDOW_INACTIVE)
        if (!window.focused) return reject(F9MatchFailure.TARGET_WINDOW_UNFOCUSED)
        if (!contains(scene.screen, window.bounds)) return reject(F9MatchFailure.TARGET_WINDOW_OUTSIDE_FRAME)
        // Keep the independent pre-popup page's ORIGINAL five-second horizon. The new manual
        // popup capture does not renew this separate background proof.
        if (scene.pageWindowId == scene.windowId) {
            val page = r.pageBeforePopup ?: return reject(F9MatchFailure.PAGE_PROOF_UNAVAILABLE)
            val now = clock.nowMillis()
            if (page.packageName != scene.packageName) return reject(F9MatchFailure.PAGE_PACKAGE_MISMATCH)
            if (page.windowId == scene.windowId) return reject(F9MatchFailure.PAGE_WINDOW_EQUALS_POPUP)
            if (page.digest.isBlank()) return reject(F9MatchFailure.PAGE_DIGEST_BLANK)
            if (page.capturedAtUptimeMillis < 0) return reject(F9MatchFailure.PAGE_TIMESTAMP_NEGATIVE)
            if (page.capturedAtUptimeMillis > s.capturedAtUptimeMillis) return reject(F9MatchFailure.PAGE_TIMESTAMP_AFTER_CAPTURE)
            if (now < page.capturedAtUptimeMillis) return reject(F9MatchFailure.PAGE_TIMESTAMP_IN_FUTURE)
            if (now - page.capturedAtUptimeMillis >= 5_000L) return reject(F9MatchFailure.PAGE_PROOF_EXPIRED)
            if (window.bounds == scene.screen) return reject(F9MatchFailure.FULLSCREEN_WITH_HIDDEN_PAGE)
        }
        val node = scene.nodes.singleOrNull { it.path == c.nodePath }
            ?: return reject(F9MatchFailure.TARGET_NODE_UNAVAILABLE)
        val closeNodes = scene.nodes.filter { n -> listOfNotNull(n.text, n.contentDescription).any { role(it) != null } && n.clickable }
        if (closeNodes.size != 1) return reject(F9MatchFailure.CLOSE_NODE_COUNT)
        if (closeNodes.single() != node) return reject(F9MatchFailure.CLOSE_NODE_MISMATCH)
        if (c.evidence != EvidenceLevel.E2_LABELED_NODE) return reject(F9MatchFailure.CANDIDATE_EVIDENCE)
        if (c.role != ButtonRole.CLOSE) return reject(F9MatchFailure.CANDIDATE_ROLE)
        if (role(c.label) != ButtonRole.CLOSE) return reject(F9MatchFailure.CANDIDATE_LABEL)
        if (c.viewId != node.viewId) return reject(F9MatchFailure.CANDIDATE_VIEW_ID)
        if (c.bounds != node.bounds) return reject(F9MatchFailure.CANDIDATE_BOUNDS)
        if (!node.enabled) return reject(F9MatchFailure.NODE_DISABLED)
        if (!node.clickable) return reject(F9MatchFailure.NODE_NOT_CLICKABLE)
        if (!supportedCloseWidget(node.className, r.userChoice != null)) return reject(F9MatchFailure.WIDGET_UNSUPPORTED)
        if (node.path !in scene.visiblePaths) return reject(F9MatchFailure.NODE_NOT_VISIBLE)
        if (NodeAction.CLICK !in node.actions) return reject(F9MatchFailure.CLICK_ACTION_UNAVAILABLE)
        if (!contains(window.bounds, node.bounds)) return reject(F9MatchFailure.NODE_OUTSIDE_WINDOW)
        val labels = listOfNotNull(node.text, node.contentDescription).filter { it.isNotBlank() }
        if (labels.isEmpty()) return reject(F9MatchFailure.LABELS_EMPTY)
        if (c.label !in labels) return reject(F9MatchFailure.LABEL_NOT_PRESENT)
        if (!labels.all { role(it) == ButtonRole.CLOSE }) return reject(F9MatchFailure.LABELS_NOT_ALL_CLOSE)
        return true
    }

    private fun observedOutcome(before: F9Scene, after: F9Scene?, pageBeforePopup: F9PageProof?): ClickOutcome {
        if (after == null) return ClickOutcome.UNKNOWN
        if (after.packageName != before.packageName) return ClickOutcome.LEFT_APP
        if (!after.complete) return ClickOutcome.UNKNOWN
        val pageId = if (before.pageWindowId == before.windowId) pageBeforePopup?.windowId else before.pageWindowId
        val pageDigest = if (before.pageWindowId == before.windowId) pageBeforePopup?.digest else before.pageDigest
        if (after.pageDigest != null && after.pageWindowId == pageId && after.pageDigest != pageDigest)
            return ClickOutcome.LEFT_APP
        if (after.windowId == before.windowId && after.nodes == before.nodes && after.windows.any { it.id == before.windowId })
            return ClickOutcome.STILL_PRESENT
        // Separate-window popup disappearance plus unchanged underlying page, same app and no new windows.
        if (pageId != null && after.windowId == pageId && after.pageWindowId == pageId &&
            after.screen == before.screen && after.pageDigest != null && after.pageDigest == pageDigest &&
            after.windows.none { it.id == before.windowId } &&
            after.windows.map { it.id }.toSet() == before.windows.filter { it.id != before.windowId }.map { it.id }.toSet() + pageId)
            return ClickOutcome.CLOSED_CONFIRMED
        return ClickOutcome.UNKNOWN
    }

    companion object {
        /** Exact native classes only. New widget support is manual USER_CHOICE only; every
         * label, unique-target, whole-screen and immediate-before-press check still applies. */
        internal fun supportedCloseWidget(name: String?, manual: Boolean): Boolean =
            name == "android.widget.Button" || (manual && name in setOf("android.widget.ImageButton", "android.widget.TextView"))
        fun role(label: String?): ButtonRole? = when (label) {
            "닫기", "창 닫기", "팝업 닫기", "close", "Close", "dismiss" -> ButtonRole.CLOSE
            "건너뛰기", "스킵", "skip", "Skip" -> ButtonRole.SKIP
            else -> null
        }
        fun contains(outer: ScreenBounds, inner: ScreenBounds) = inner.width > 0 && inner.height > 0 &&
            outer.width > 0 && outer.height > 0 && inner.left >= outer.left && inner.top >= outer.top &&
            inner.right <= outer.right && inner.bottom <= outer.bottom
    }
}
