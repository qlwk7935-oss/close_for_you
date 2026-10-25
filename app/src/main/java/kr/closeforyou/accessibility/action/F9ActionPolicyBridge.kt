package kr.closeforyou.accessibility.action

import kr.closeforyou.core.contract.ActionPolicy
import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.DecisionSource
import kr.closeforyou.core.model.HandlingReport
import kr.closeforyou.core.model.NextStep

/** A-owned, ephemeral evidence input. A must supply the original scoped analysis and consent;
 * this bridge does not manufacture F0/AutoEligibility or enable an automatic preset.
 */
internal data class F9PolicyInput(val analysis: PopupAnalysis, val context: DecisionContext) {
    override fun toString() = "F9PolicyInput(redacted)"
}

/** Uses the SAME B policy instance for deciding and for its F9 results, including no-press results.
 * Default evidence source is absent: result delivery is connected while automatic execution is OFF.
 * The owning F9AutoCloser is the sole delivery owner; callers must not send the returned result again.
 */
internal class F9ActionPolicyBridge(
    private val actionPolicy: ActionPolicy,
    private val evidence: suspend (F9Reference, F9Scene) -> F9PolicyInput? = { _, _ -> null },
    /** Queue this immutable, title-free report to C in the owning service's scope. */
    private val onReport: (HandlingReport) -> Unit = {},
) : F9PolicyBridge {
    // F9AutoCloser serializes policy requests on Main. Duplicates rejected BEFORE authorize do
    // not touch this receipt. No original analysis/text or Android handle is retained here.
    private var pendingReport: HandlingReport? = null

    override suspend fun authorize(reference: F9Reference, scene: F9Scene): F9Approval? {
        val input = evidence(reference, scene) ?: return null
        if (input.analysis.token != reference.snapshot.token || input.context.currentToken != reference.snapshot.token) return null
        val decision = actionPolicy.decide(input.analysis, input.context)
        if (decision.source == DecisionSource.PRESET || decision.source == DecisionSource.REMEMBERED_RULE) {
            val report = HandlingReport(input.analysis.token, input.analysis.classification, decision, title = null)
            if (decision.next == NextStep.EXECUTE) pendingReport = report else emit(report)
        }
        val eligibility = input.analysis.autoEligibility ?: return null
        return F9Approval(decision, eligibility, F9ApprovalOrigin.CONTRACT_V2)
    }

    override fun onActionResult(result: ActionResult) = actionPolicy.onActionResult(result)

    override fun onExecutionResult(result: ActionResult, policyConsulted: Boolean) {
        // B correlates pending approvals by popup fingerprint, not A invocation. A concurrent
        // pre-policy rejection must not clear the original request's pending result.
        if (!policyConsulted) return
        val report = pendingReport
        pendingReport = null // Clear BEFORE calling either receiver, even when it throws.
        try { onActionResult(result) }
        finally {
            if (report != null && report.token == result.token) emit(report.copy(result = result))
        }
    }

    private fun emit(report: HandlingReport) {
        try { onReport(report) } catch (_: Exception) { /* No retry or screen-data logging. */ }
    }
}
