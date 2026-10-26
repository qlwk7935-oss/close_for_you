package kr.closeforyou.accessibility.action

import kotlinx.coroutines.*
import kr.closeforyou.core.model.*
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
    val visiblePaths: Set<String>, val complete: Boolean, val pageDigest: String? = null) {
    override fun toString() = "F9Scene(redacted)"
}
internal interface F9ScreenPort {
    fun read(checkpoint: () -> Unit): F9Scene?
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

    suspend fun execute(reference: F9Reference, requestPolicy: F9PolicyBridge = policy): F9Result {
        // One delivery owner per invocation, including early rejects and cancellation at dispatch.
        // Callbacks run on the same dispatcher as B's session state. A throwing receiver is never retried.
        val delivered = java.util.concurrent.atomic.AtomicBoolean()
        var policyConsulted = false
        fun complete(result: F9Result): F9Result {
            if (delivered.compareAndSet(false, true)) {
                try { requestPolicy.onExecutionResult(result.toActionResult(), policyConsulted) } catch (_: Exception) { /* No screen-data logging. */ }
            }
            return result
        }
        return try {
            withContext(dispatcher) {
                executeOnMain(reference, ::complete, requestPolicy) { scene ->
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
        authorize: suspend (F9Scene) -> F9Approval?): F9Result {
        val token = reference.snapshot.token
        fun blocked(reason: F9Block) = complete(F9Result(token, false, block = reason))
        val jobContext = currentCoroutineContext()
        fun checkpoint() { jobContext.ensureActive(); check(!synchronized(lock) { stopped }) }
        jobContext.ensureActive()
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
            val initial = screen.read(::checkpoint) ?: return blocked(F9Block.SCREEN_UNVERIFIED)
            if (!matches(reference, initial)) return blocked(F9Block.SCREEN_UNVERIFIED)
            val approval = authorize(initial) ?: return blocked(F9Block.POLICY_DENIED)
            checkpoint()
            if (!approved(reference, approval)) return blocked(F9Block.POLICY_DENIED)
            var finalBlock: F9Block? = null
            press = screen.press(reference) { fresh ->
                // Synchronous on Main. No queued callback/coroutine can split this check and invocation.
                checkpoint()
                finalBlock = stateBlock(reference)
                finalBlock == null && approved(reference, approval) && fresh == initial && matches(reference, fresh)
            }
            if (!press.attempted) return blocked(finalBlock ?: F9Block.SCREEN_UNVERIFIED)
            for (pause in observationDelays) {
                delay(pause)
                checkpoint()
                val state = runtime()
                if (!state.connected || state.serviceGeneration != reference.serviceGeneration || state.userInteracting ||
                    state.paused || !state.collectionConsent || !state.appAllowed ||
                    (reference.userChoice == null && !state.autoCloseConsent) ||
                    !state.cleanupReady || state.policyRevision != reference.policyRevision) break
                val observed = screen.read(::checkpoint)
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

    private fun matches(r: F9Reference, scene: F9Scene): Boolean {
        val s = r.snapshot; val c = r.candidate
        if (!scene.complete || s.completeness != SnapshotCompleteness.COMPLETE || scene.packageName != s.token.packageName ||
            F9ContentSafety.protectedPackage(scene.packageName) || scene.windowId != s.token.windowId ||
            scene.pageDigest == null || scene.screen != s.screenBounds ||
            scene.nodes != s.nodes || scene.windows != s.windows || scene.nodes.size !in 1..1_200 ||
            scene.nodes.any { listOfNotNull(it.text, it.contentDescription).any(F9ContentSafety::blocked) }) return false
        val window = scene.windows.singleOrNull { it.id == scene.windowId } ?: return false
        if (window.type != WindowType.APPLICATION || !window.active || !window.focused || !contains(scene.screen, window.bounds)) return false
        // Some Android versions hide the page window behind a modal. No page is invented from
        // the popup: require an independently F0-read page observed BEFORE this popup, still within
        // the original five-second evidence horizon. Fullscreen/unscoped transitions stay blocked.
        if (scene.pageWindowId == scene.windowId) {
            val page = r.pageBeforePopup ?: return false
            val now = clock.nowMillis()
            if (page.packageName != scene.packageName || page.windowId == scene.windowId || page.digest.isBlank() ||
                page.capturedAtUptimeMillis < 0 || page.capturedAtUptimeMillis > s.capturedAtUptimeMillis ||
                now < page.capturedAtUptimeMillis || now - page.capturedAtUptimeMillis >= 5_000L || window.bounds == scene.screen) return false
        }
        val node = scene.nodes.singleOrNull { it.path == c.nodePath } ?: return false
        val closeNodes = scene.nodes.filter { n -> listOfNotNull(n.text, n.contentDescription).any { role(it) != null } && n.clickable }
        return closeNodes.size == 1 && closeNodes.single() == node && c.evidence == EvidenceLevel.E2_LABELED_NODE &&
            c.role == ButtonRole.CLOSE && role(c.label) == ButtonRole.CLOSE && c.viewId == node.viewId &&
            c.bounds == node.bounds && node.enabled && node.clickable && supportedCloseWidget(node.className, r.userChoice != null) &&
            node.path in scene.visiblePaths && NodeAction.CLICK in node.actions && contains(window.bounds, node.bounds) &&
            listOfNotNull(node.text, node.contentDescription).filter { it.isNotBlank() }.let { labels ->
                labels.isNotEmpty() && c.label in labels && labels.all { role(it) == ButtonRole.CLOSE }
            }
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
