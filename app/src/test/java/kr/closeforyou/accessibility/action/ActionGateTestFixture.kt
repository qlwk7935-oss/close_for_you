package kr.closeforyou.accessibility.action

import kr.closeforyou.core.contract.ActionRequest
import kr.closeforyou.core.model.*
import kotlinx.collections.immutable.persistentSetOf

/** Synthetic evidence only. Nothing here represents Android collection or user consent. */
internal class ActionGateTestFixture {
    val ledger = ActionAttemptLedger()
    val token = SnapshotToken(1, 7, "example.synthetic", "synthetic-uuid", "synthetic-structure", "synthetic-content", 1)
    val candidate = CloseCandidate("synthetic-close", "0/1", "synthetic:id/close", "닫기",
        ScreenBounds(100, 100, 200, 150), EvidenceLevel.E2_LABELED_NODE, ButtonRole.CLOSE)
    val node = GateNode(candidate.nodePath!!, candidate.viewId, "android.widget.Button", token.packageName,
        token.windowId, candidate.bounds, candidate.label, null, persistentSetOf(NodeAction.CLICK),
        clickable = true, enabled = true, visible = true)
    val geometry = GateGeometry(ScreenBounds(0, 0, 500, 800), ScreenBounds(0, 0, 500, 800),
        ScreenBounds(50, 50, 450, 500), "0")
    val registration = GateRegistration(9, token, 0, 5_000, ledger.syntheticEpisode(), candidate, node, geometry,
        UserChoice(token, ChoiceKind.CLOSE_ONCE, candidate.id, buttonMeaningConfirmed = true),
        GateChoiceOrigin.SYNTHETIC_EXPLICIT_SELECTION)
    val checks = GateChecks(GateCollection.COMPLETE, GateCheck.PASSED, GateCheck.PASSED, GateCheck.PASSED,
        GateCheck.PASSED, GateCheck.PASSED, GateCheck.PASSED, GateCheck.PASSED, GateCheck.PASSED)
    val context = ActionGateContext(registration, GateEvidenceOrigin.SYNTHETIC_TEST, 9, token,
        connected = true, userInteracting = false, consentGranted = true, paused = false,
        appAllowed = true, popupContinuity = GateCheck.PASSED, checks = checks,
        currentNode = node, currentGeometry = geometry)
    val request = ActionRequest(ActionDecision(token, ActionAuthorization.ALLOW_ONCE, persistentSetOf(), candidate.id), candidate)
}
