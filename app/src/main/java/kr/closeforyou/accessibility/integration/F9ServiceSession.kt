package kr.closeforyou.accessibility.integration

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.*
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionActionPolicy
import kr.closeforyou.decision.DecisionCandidateWiring
import kr.closeforyou.ui.voice.settings.AutoCloseConsent

/** Independent A rollout gate. It is never enabled by onboarding, an F12 rule or a debug setting. */
internal object F9ServiceExecutionFeature { const val enabled = false }

internal data class F9SessionAccess(val connected: Boolean = false, val collectionConsent: Boolean = false,
    val appAllowed: Boolean = false, val paused: Boolean = true, val userInteracting: Boolean = true,
    val consent: AutoCloseConsent = AutoCloseConsent.NONE, val announcementAvailable: Boolean = false)

internal data class F9Handled(val decision: ActionDecision? = null, val result: F9Result? = null) {
    override fun toString() = "F9Handled(redacted)"
}

/** The same session is used by the real service and the synthetic harness. The harness supplies
 * test consent/execution input; production always uses the OFF rollout gate above. Call on Main.
 * DecisionCandidateWiring.source must be collected, so it remembers each detection before handle.
 */
internal class F9ServiceSession(
    private val raw: F9CollectedSource,
    private val screen: F9ScreenPort,
    private val clock: F9Clock,
    private val access: (String) -> F9SessionAccess,
    private val cleanupReady: () -> Boolean,
    private val onReport: (HandlingReport) -> Unit,
    private val executionEnabled: () -> Boolean = { F9ServiceExecutionFeature.enabled },
    policyFactory: (() -> Long) -> ActionPolicy = { now -> DecisionActionPolicy(allowAutoSkip = false, nowUptimeMillis = now) },
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    val wiring = DecisionCandidateWiring(raw, raw)
    private val generation = generations.incrementAndGet()
    private val policy = policyFactory(clock::nowMillis) // exactly one monotonic-clock B policy per session
    private var revision = 0L
    private var closed = false
    private var running = false
    private var activePackage: String? = null
    private var activeClassification: PopupClassification? = null
    private var manualToken: SnapshotToken? = null
    private var manualCurrent: () -> Boolean = { false }
    private var page: F9PageProof? = null
    private val issued = mutableSetOf<String>()
    private val closer = F9AutoCloser(screen, ::runtime, clock, dispatcher = dispatcher)

    private fun runtime(): F9RuntimeState {
        val token = if (manualToken != null) manualToken.takeIf { manualCurrent() } else raw.currentToken()
        val settings = access(activePackage ?: token?.packageName.orEmpty())
        val consent = settings.consent
        val permitted = executionEnabled() && consent.granted && activeClassification in consent.autoCloseKinds && settings.announcementAvailable
        return F9RuntimeState(token, generation, revision, !closed && settings.connected, settings.collectionConsent,
            settings.appAllowed, permitted, permitted, settings.paused || (manualToken != null && !settings.announcementAvailable),
            settings.userInteracting, cleanupReady())
    }

    /** A new F0 read of a normal fullscreen page, not a popup masquerading as its own baseline. */
    fun observePage() {
        if (closed || running || !cleanupReady()) return
        val captured = clock.nowMillis()
        val scene = screen.read {} ?: return
        val window = scene.windows.singleOrNull { it.id == scene.windowId } ?: return
        if (scene.complete && window.bounds == scene.screen && scene.pageWindowId == scene.windowId && scene.pageDigest != null) {
            page = F9PageProof(scene.packageName, scene.windowId, scene.pageDigest, captured)
        }
    }

    suspend fun handle(event: PopupEvent.Candidate): F9Handled {
        if (closed || running || raw.currentToken() != event.snapshot.token) return F9Handled()
        val snapshot = event.snapshot
        val key = snapshot.token.packageName + "\u0000" + snapshot.token.structureDigest + "\u0000" + snapshot.token.contentDigest
        if (key in issued || issued.size >= 256) return F9Handled()
        running = true; activePackage = snapshot.token.packageName
        try {
            val wired = wiring.extractor.extract(snapshot)
            currentCoroutineContext().ensureActive()
            if (raw.currentToken() != snapshot.token || closed) return F9Handled()
            val job = currentCoroutineContext()
            val fresh = if (cleanupReady()) screen.read { job.ensureActive(); currentCoroutineContextCheck() } else null
            val analysis = F9EligibilityAdapter.prepare(snapshot, event.detection, wired, fresh, clock.nowMillis())
            activeClassification = analysis.classification
            val a = access(snapshot.token.packageName)
            val kinds = if (executionEnabled() && a.connected && a.collectionConsent && a.appAllowed && !a.paused &&
                !a.userInteracting && a.consent.granted && cleanupReady()) a.consent.autoCloseKinds else persistentSetOf()
            val context = DecisionContext(raw.currentToken(), null, null, a.userInteracting, a.announcementAvailable, kinds)
            val decision = policy.decide(analysis, context)
            val automatic = decision.source == DecisionSource.PRESET || decision.source == DecisionSource.REMEMBERED_RULE
            fun report(result: ActionResult? = null) {
                if (automatic) try { onReport(HandlingReport(snapshot.token, analysis.classification, decision, result, title = null)) }
                catch (_: Exception) { /* Result delivery never retries or logs screen data. */ }
            }
            if (decision.next != NextStep.EXECUTE) { report(); return F9Handled(decision) }

            issued += key
            val delivered = AtomicBoolean()
            fun complete(result: ActionResult) {
                if (!delivered.compareAndSet(false, true)) return
                try { policy.onActionResult(result) } finally { report(result) }
            }
            val candidate = analysis.closeCandidates.singleOrNull { it.id == decision.candidateId }
            val eligibility = analysis.autoEligibility
            if (candidate == null || eligibility == null) {
                val result = F9Result(snapshot.token, false, block = F9Block.POLICY_DENIED)
                complete(result.toActionResult()); return F9Handled(decision, result)
            }
            // This receipt belongs to ONE already-approved execute call, including initial-gate
            // rejection/cancellation. It never calls decide again. Other requests cannot consume it.
            val requestBridge = object : F9PolicyBridge {
                override suspend fun authorize(reference: F9Reference, scene: F9Scene) =
                    F9Approval(decision, eligibility, F9ApprovalOrigin.CONTRACT_V2)
                override fun onActionResult(result: ActionResult) = complete(result)
                override fun onExecutionResult(result: ActionResult, policyConsulted: Boolean) = complete(result)
            }
            val result = closer.execute(F9Reference(snapshot, candidate, generation, revision, page), requestBridge)
            return F9Handled(decision, result)
        } finally { running = false; activePackage = null; activeClassification = null }
    }

    // Android scan is synchronous on Main; cancellation is also checked before/after it and by F9.
    private fun currentCoroutineContextCheck() { check(!closed) { "F9_SESSION_ENDED" } }
    fun invalidate(version: Long, permissionOrInteraction: Boolean) {
        raw.invalidate(version)
        if (permissionOrInteraction) { revision++; page = null }
    }
    /** Explicit, generation-fenced C choice only. The caller first confirms native cleanup and
     * revalidates the unchanged target at the new event epoch, retaining ORIGINAL capture time.
     * This never uses auto consent and never emits an automatic HandlingReport.
     */
    suspend fun executeUser(snapshot: PopupSnapshot, analysis: PopupAnalysis, choice: UserChoice, detection: PopupDetection,
        current: () -> Boolean): ActionResult {
        fun refused(reason: SafetyReason) = ActionResult(snapshot.token, ActionStatus.NOT_EXECUTED, reason)
        if (closed || running || !current()) return refused(SafetyReason.STALE_SCREEN)
        running = true; activePackage = snapshot.token.packageName; manualToken = snapshot.token; manualCurrent = current
        try {
            val key = snapshot.token.packageName + "\u0000" + snapshot.token.structureDigest + "\u0000" + snapshot.token.contentDigest
            if (key in issued || issued.size >= 256) return refused(SafetyReason.ALREADY_ATTEMPTED)
            val fresh = screen.read { check(current() && !closed) }
            val prepared = F9EligibilityAdapter.prepare(snapshot, detection, analysis, fresh, clock.nowMillis())
            activeClassification = prepared.classification
            val a = access(snapshot.token.packageName)
            val decision = policy.decide(prepared, DecisionContext(if (current()) snapshot.token else null,
                choice, null, a.userInteracting, a.announcementAvailable))
            val delivered = AtomicBoolean()
            fun complete(result: ActionResult) {
                if (delivered.compareAndSet(false, true)) policy.onActionResult(result)
            }
            val candidate = prepared.closeCandidates.singleOrNull { it.id == decision.candidateId }
            val eligibility = prepared.autoEligibility
            if (decision.next != NextStep.EXECUTE || candidate == null || eligibility == null || choice.kind != ChoiceKind.CLOSE_ONCE) {
                val result = ActionResult(snapshot.token, ActionStatus.BLOCKED,
                    decision.reasons.firstOrNull() ?: SafetyReason.UNVERIFIED_TARGET)
                complete(result); return result
            }
            issued += key
            val bridge = object : F9PolicyBridge {
                override suspend fun authorize(reference: F9Reference, scene: F9Scene) = F9Approval(decision, eligibility, F9ApprovalOrigin.CONTRACT_V2)
                override fun onActionResult(result: ActionResult) = complete(result)
                override fun onExecutionResult(result: ActionResult, policyConsulted: Boolean) = complete(result)
            }
            return closer.execute(F9Reference(snapshot, candidate, generation, revision, page, choice), bridge).toActionResult()
        } finally { running = false; activePackage = null; activeClassification = null; manualToken = null; manualCurrent = { false } }
    }
    fun close(version: Long) { closed = true; closer.close(); revision++; page = null; raw.invalidate(version) }
    companion object { private val generations = AtomicLong() }
}
