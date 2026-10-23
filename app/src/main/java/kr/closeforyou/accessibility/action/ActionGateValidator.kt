package kr.closeforyou.accessibility.action

import kr.closeforyou.core.contract.ActionRequest
import kr.closeforyou.core.model.*

/** Pure deny-first inspection. No callback, Android API, clock mutation or action permission. */
internal class ActionGateValidator {
    fun evaluate(request: ActionRequest, context: ActionGateContext?, now: Long): GateEvaluation {
        val denials = linkedSetOf(GateDenial.ANDROID_REVALIDATION_NOT_IMPLEMENTED)
        fun reject(condition: Boolean, reason: GateDenial) { if (condition) denials += reason }
        if (context == null) return GateEvaluation(denials + GateDenial.UNREGISTERED_REQUEST)
        val r = context.registration
        reject(context.origin != GateEvidenceOrigin.SYNTHETIC_TEST, GateDenial.EVIDENCE_UNAVAILABLE)
        reject(request.decision.authorization != ActionAuthorization.ALLOW_ONCE, GateDenial.NOT_ALLOW_ONCE)
        reject(request.decision.reasons.isNotEmpty(), GateDenial.DECISION_HAS_REASONS)
        reject(request.decision.candidateId != r.candidate.id || request.candidate != r.candidate,
            GateDenial.CANDIDATE_MISMATCH)
        reject(request.decision.token != r.token || context.currentToken != r.token, GateDenial.STALE_TOKEN)
        reject(r.sessionGeneration != context.currentSessionGeneration, GateDenial.SESSION_CHANGED)
        reject(!context.connected, GateDenial.SERVICE_STOPPED)
        // Subtraction after overflow-safe addition checks; a refreshed collection cannot renew TTL.
        val validDeadline = r.issuedAtMillis >= 0 && r.issuedAtMillis <= Long.MAX_VALUE - TTL &&
            r.expiresAtMillis > r.issuedAtMillis && r.expiresAtMillis <= r.issuedAtMillis + TTL
        reject(!validDeadline, GateDenial.INVALID_TTL)
        reject(now < r.issuedAtMillis || now >= r.expiresAtMillis, GateDenial.TTL_EXPIRED)
        reject(context.userInteracting, GateDenial.USER_INTERACTION)
        reject(!context.consentGranted, GateDenial.NO_CONSENT)
        reject(context.paused, GateDenial.PAUSED)
        reject(!context.appAllowed, GateDenial.APP_NOT_ALLOWED)
        reject(context.popupContinuity != GateCheck.PASSED, GateDenial.POPUP_IDENTITY_UNPROVEN)
        val checks = context.checks
        reject(checks.collection != GateCollection.COMPLETE, GateDenial.COLLECTION_INCOMPLETE)
        listOf(
            checks.f0A to GateDenial.F0_A_UNVERIFIED, checks.f0B to GateDenial.F0_B_UNVERIFIED,
            checks.f1 to GateDenial.F1_UNVERIFIED, checks.geometry to GateDenial.GEOMETRY_UNVERIFIED,
            checks.occlusion to GateDenial.OCCLUSION_UNVERIFIED,
            checks.popupMembership to GateDenial.POPUP_MEMBERSHIP_UNVERIFIED,
            checks.noImportantDecision to GateDenial.IMPORTANT_DECISION,
            checks.buttonMeaning to GateDenial.BUTTON_MEANING_UNVERIFIED,
        ).forEach { (check, reason) -> reject(check != GateCheck.PASSED, reason) }

        val role = when (r.choice.kind) {
            ChoiceKind.CLOSE_ONCE -> ButtonRole.CLOSE
            ChoiceKind.SKIP_ONCE -> ButtonRole.SKIP
            else -> null // No rules, repeat automation, remembered permission or voice-only proof.
        }
        reject(r.choiceOrigin != GateChoiceOrigin.SYNTHETIC_EXPLICIT_SELECTION ||
            r.choice.token != r.token || r.choice.candidateId != r.candidate.id ||
            !r.choice.buttonMeaningConfirmed || role == null || role != r.candidate.role,
            GateDenial.CHOICE_UNVERIFIED)
        val c = r.candidate
        val expectedAction = when (c.evidence) {
            EvidenceLevel.E1_DISMISS -> NodeAction.DISMISS
            EvidenceLevel.E2_LABELED_NODE -> NodeAction.CLICK
            else -> null
        }
        reject(expectedAction == null, GateDenial.UNSUPPORTED_EVIDENCE)
        reject(ButtonLabelRules.roleOf(c.label) != c.role,
            GateDenial.BUTTON_MEANING_UNVERIFIED)

        val node = context.currentNode
        val g = context.currentGeometry
        reject(node == null || node != r.node || node.path != c.nodePath || node.viewId != c.viewId ||
            node.bounds != c.bounds || node.packageName != r.token.packageName ||
            node.windowId != r.token.windowId || node.className.isNullOrBlank() ||
            !node.enabled || !node.visible || node.password || node.editable || node.inputType != 0 ||
            expectedAction !in node.actions || (expectedAction == NodeAction.CLICK && !node.clickable),
            GateDenial.TARGET_UNVERIFIED)
        if (node != null) {
            val labels = listOfNotNull(node.text, node.description).filter { it.isNotBlank() }
            reject(labels.isEmpty() || labels.none { it == c.label } ||
                ButtonLabelRules.judgeNode(node.text, node.description).role != c.role, GateDenial.BUTTON_MEANING_UNVERIFIED)
        }
        reject(g == null || g != r.geometry || !contains(g.screen, g.window) ||
            !contains(g.window, g.popup) || node == null || !contains(g.popup, node.bounds),
            GateDenial.GEOMETRY_UNVERIFIED)
        reject(g == null || node == null || g.popupRootPath.isBlank() ||
            (node.path != g.popupRootPath && !node.path.startsWith("${g.popupRootPath}/")),
            GateDenial.POPUP_MEMBERSHIP_UNVERIFIED)
        return GateEvaluation(denials.toSet())
    }

    private fun contains(outer: ScreenBounds, inner: ScreenBounds): Boolean =
        outer.right.toLong() > outer.left && outer.bottom.toLong() > outer.top &&
            inner.right.toLong() > inner.left && inner.bottom.toLong() > inner.top &&
            inner.left >= outer.left && inner.top >= outer.top &&
            inner.right <= outer.right && inner.bottom <= outer.bottom

    private companion object { const val TTL = 5_000L }
}
