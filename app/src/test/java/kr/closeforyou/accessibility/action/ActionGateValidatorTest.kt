package kr.closeforyou.accessibility.action

import kr.closeforyou.core.model.*
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.*
import org.junit.Test

class ActionGateValidatorTest {
    private val f = ActionGateTestFixture()
    private val validator = ActionGateValidator()
    private fun evaluate(c: ActionGateContext? = f.context, now: Long = 100) = validator.evaluate(f.request, c, now)
    private fun denied(c: ActionGateContext?, reason: GateDenial) {
        val result = evaluate(c)
        assertTrue(result.denials.contains(reason))
        assertFalse(result.syntheticChecksSatisfied)
        assertFalse(result.productionReady)
    }

    @Test fun completeSyntheticFixtureNeverGrantsProductionPermission() {
        val result = evaluate()
        assertTrue(result.syntheticChecksSatisfied)
        assertEquals(setOf(GateDenial.ANDROID_REVALIDATION_NOT_IMPLEMENTED), result.denials)
        assertFalse(result.productionReady)
    }

    @Test fun arbitraryAllowOnceWithoutRegistrationFailsClosed() {
        denied(null, GateDenial.UNREGISTERED_REQUEST)
    }

    @Test fun defaultEvidenceAndProtectionStatesCannotPass() {
        denied(f.context.copy(origin = GateEvidenceOrigin.UNAVAILABLE), GateDenial.EVIDENCE_UNAVAILABLE)
        denied(f.context.copy(checks = GateChecks()), GateDenial.COLLECTION_INCOMPLETE)
    }

    @Test fun denyConfirmationAndInconsistentAllowReasonsAreRefused() {
        for (authorization in listOf(ActionAuthorization.DENY, ActionAuthorization.NEEDS_CONFIRMATION)) {
            val r = validator.evaluate(f.request.copy(decision = f.request.decision.copy(authorization = authorization)), f.context, 100)
            assertTrue(GateDenial.NOT_ALLOW_ONCE in r.denials)
        }
        val r = validator.evaluate(f.request.copy(decision = f.request.decision.copy(reasons = persistentSetOf(SafetyReason.INCOMPLETE_EVIDENCE))), f.context, 100)
        assertTrue(GateDenial.DECISION_HAS_REASONS in r.denials)
    }

    @Test fun everySuppliedCandidateFieldIsBoundToRegistration() {
        val changes = listOf(f.candidate.copy(id = "other"), f.candidate.copy(nodePath = "0/2"),
            f.candidate.copy(viewId = null), f.candidate.copy(label = "동의"),
            f.candidate.copy(bounds = ScreenBounds(0, 0, 10, 10)),
            f.candidate.copy(evidence = EvidenceLevel.E1_DISMISS), f.candidate.copy(role = ButtonRole.SKIP))
        for (candidate in changes) {
            val r = validator.evaluate(f.request.copy(candidate = candidate), f.context, 100)
            assertTrue(GateDenial.CANDIDATE_MISMATCH in r.denials)
        }
        val r = validator.evaluate(f.request.copy(decision = f.request.decision.copy(candidateId = "other")), f.context, 100)
        assertTrue(GateDenial.CANDIDATE_MISMATCH in r.denials)
    }

    @Test fun allTokenComponentsAreBoundAndMissingTokenIsStale() {
        val changes = listOf(f.token.copy(screenVersion = 2), f.token.copy(windowId = 8),
            f.token.copy(packageName = "example.other"), f.token.copy(popupInstanceId = "new-uuid"),
            f.token.copy(structureDigest = "changed"), f.token.copy(contentDigest = "changed-date"),
            f.token.copy(captureEpoch = 2))
        for (token in changes) {
            denied(f.context.copy(currentToken = token), GateDenial.STALE_TOKEN)
            val r = validator.evaluate(f.request.copy(decision = f.request.decision.copy(token = token)), f.context, 100)
            assertTrue(GateDenial.STALE_TOKEN in r.denials)
        }
        denied(f.context.copy(currentToken = null), GateDenial.STALE_TOKEN)
    }

    @Test fun originalFiveSecondDeadlineIsExclusiveAndCannotBeRenewed() {
        assertTrue(evaluate(now = 4_999).syntheticChecksSatisfied)
        assertTrue(GateDenial.TTL_EXPIRED in evaluate(now = 5_000).denials)
        assertTrue(GateDenial.TTL_EXPIRED in evaluate(now = -1).denials)
        denied(f.context.copy(registration = f.registration.copy(expiresAtMillis = 5_001)), GateDenial.INVALID_TTL)
        denied(f.context.copy(registration = f.registration.copy(expiresAtMillis = 0)), GateDenial.INVALID_TTL)
        denied(f.context.copy(registration = f.registration.copy(issuedAtMillis = Long.MAX_VALUE - 1)), GateDenial.INVALID_TTL)
    }

    @Test fun serviceSessionConsentInteractionAndAppRevocationIndependentlyDeny() {
        listOf(f.context.copy(currentSessionGeneration = 10) to GateDenial.SESSION_CHANGED,
            f.context.copy(connected = false) to GateDenial.SERVICE_STOPPED,
            f.context.copy(userInteracting = true) to GateDenial.USER_INTERACTION,
            f.context.copy(consentGranted = false) to GateDenial.NO_CONSENT,
            f.context.copy(paused = true) to GateDenial.PAUSED,
            f.context.copy(appAllowed = false) to GateDenial.APP_NOT_ALLOWED).forEach { (c, reason) -> denied(c, reason) }
    }

    @Test fun allIncompleteCollectionCausesDenyWithoutGeometryRepair() {
        GateCollection.entries.filter { it != GateCollection.COMPLETE }.forEach {
            denied(f.context.copy(checks = f.checks.copy(collection = it)), GateDenial.COLLECTION_INCOMPLETE)
        }
    }

    @Test fun everyMissingBlockedOrUncertainProtectionCheckDenies() {
        for (state in GateCheck.entries.filter { it != GateCheck.PASSED }) {
            listOf(f.checks.copy(f0A = state) to GateDenial.F0_A_UNVERIFIED,
                f.checks.copy(f0B = state) to GateDenial.F0_B_UNVERIFIED,
                f.checks.copy(f1 = state) to GateDenial.F1_UNVERIFIED,
                f.checks.copy(geometry = state) to GateDenial.GEOMETRY_UNVERIFIED,
                f.checks.copy(occlusion = state) to GateDenial.OCCLUSION_UNVERIFIED,
                f.checks.copy(popupMembership = state) to GateDenial.POPUP_MEMBERSHIP_UNVERIFIED,
                f.checks.copy(noImportantDecision = state) to GateDenial.IMPORTANT_DECISION,
                f.checks.copy(buttonMeaning = state) to GateDenial.BUTTON_MEANING_UNVERIFIED)
                .forEach { (checks, reason) -> denied(f.context.copy(checks = checks), reason) }
        }
    }

    @Test fun choiceNeedsProvenanceCurrentTargetAndExplicitMeaning() {
        val changes = listOf(f.registration.copy(choiceOrigin = GateChoiceOrigin.UNKNOWN),
            f.registration.copy(choice = f.registration.choice.copy(token = f.token.copy(screenVersion = 2))),
            f.registration.copy(choice = f.registration.choice.copy(candidateId = "other")),
            f.registration.copy(choice = f.registration.choice.copy(buttonMeaningConfirmed = false)))
        changes.forEach { denied(f.context.copy(registration = it), GateDenial.CHOICE_UNVERIFIED) }
        ChoiceKind.entries.filter { it !in setOf(ChoiceKind.CLOSE_ONCE, ChoiceKind.SKIP_ONCE) }.forEach {
            denied(f.context.copy(registration = f.registration.copy(choice = f.registration.choice.copy(kind = it))), GateDenial.CHOICE_UNVERIFIED)
        }
    }

    @Test fun e3AndE4RemainUnsupportedEvenWithMeaningConfirmation() {
        for (level in listOf(EvidenceLevel.E3_USER_BOUND_NODE, EvidenceLevel.E4_VISUAL_ONLY)) {
            val candidate = f.candidate.copy(evidence = level)
            val c = f.context.copy(registration = f.registration.copy(candidate = candidate))
            val r = validator.evaluate(f.request.copy(candidate = candidate), c, 100)
            assertTrue(GateDenial.UNSUPPORTED_EVIDENCE in r.denials)
        }
    }

    @Test fun labeledDismissUsesOnlyDismissEvidenceAndNeverFallsBackToClick() {
        val candidate = f.candidate.copy(evidence = EvidenceLevel.E1_DISMISS)
        val request = f.request.copy(candidate = candidate)
        val registration = f.registration.copy(candidate = candidate)
        val c = f.context.copy(registration = registration)
        assertTrue(GateDenial.TARGET_UNVERIFIED in validator.evaluate(request, c, 100).denials)
        val node = f.node.copy(actions = persistentSetOf(NodeAction.DISMISS), clickable = false)
        assertTrue(validator.evaluate(request, c.copy(registration = registration.copy(node = node), currentNode = node), 100).syntheticChecksSatisfied)
    }

    @Test fun fakeCloseImportantDecisionConfirmationAndBareXLabelsAreRefused() {
        for (label in listOf("오늘 하루 닫기", "닫기 전 저장", "결제", "동의", "권한 허용", "구매", "확인", "OK", "설치", "X", "×", "닫기 및 동의")) {
            val candidate = f.candidate.copy(label = label)
            val node = f.node.copy(text = label)
            val c = f.context.copy(registration = f.registration.copy(candidate = candidate, node = node), currentNode = node)
            val r = validator.evaluate(f.request.copy(candidate = candidate), c, 100)
            assertTrue(GateDenial.BUTTON_MEANING_UNVERIFIED in r.denials)
        }
    }

    @Test fun conflictingLabelsAndUnsafeOrChangedFreshNodesAreRefused() {
        val changes = listOf(f.node.copy(description = "동의"), f.node.copy(visible = false),
            f.node.copy(enabled = false), f.node.copy(password = true), f.node.copy(editable = true),
            f.node.copy(clickable = false), f.node.copy(actions = persistentSetOf()),
            f.node.copy(packageName = "example.foreign"), f.node.copy(windowId = 8),
            f.node.copy(path = "0/2"), f.node.copy(viewId = "other"), f.node.copy(className = "other"))
        changes.forEach { denied(f.context.copy(currentNode = it), GateDenial.TARGET_UNVERIFIED) }
        denied(f.context.copy(currentNode = null), GateDenial.TARGET_UNVERIFIED)
    }

    @Test fun unsafeNodeCannotPassEvenIfRegistrationAndFreshReaderAgree() {
        val unsafe = listOf(f.node.copy(password = true), f.node.copy(editable = true),
            f.node.copy(inputType = 0x81), f.node.copy(inputType = 1),
            f.node.copy(visible = false), f.node.copy(enabled = false),
            f.node.copy(clickable = false), f.node.copy(actions = persistentSetOf(NodeAction.DISMISS)),
            f.node.copy(packageName = "example.foreign"), f.node.copy(windowId = 8), f.node.copy(className = null))
        for (node in unsafe) denied(f.context.copy(registration = f.registration.copy(node = node),
            currentNode = node), GateDenial.TARGET_UNVERIFIED)
        val conflict = f.node.copy(description = "동의")
        denied(f.context.copy(registration = f.registration.copy(node = conflict),
            currentNode = conflict), GateDenial.BUTTON_MEANING_UNVERIFIED)
    }

    @Test fun lossOfPopupContinuityIsNotOrdinaryNoPopup() {
        GateCheck.entries.filter { it != GateCheck.PASSED }.forEach {
            denied(f.context.copy(popupContinuity = it), GateDenial.POPUP_IDENTITY_UNPROVEN)
        }
    }

    @Test fun windowOutsideFrameEmptyBoundsAndWrongSubtreeAreRefusedEvenWithPassedFlags() {
        val changes = listOf(f.geometry.copy(window = ScreenBounds(0, 0, 501, 800)),
            f.geometry.copy(window = ScreenBounds(-1, 0, 500, 800)),
            f.geometry.copy(popup = ScreenBounds(0, 0, 0, 0)),
            f.geometry.copy(popup = ScreenBounds(300, 300, 400, 400)))
        for (geometry in changes) denied(f.context.copy(registration = f.registration.copy(geometry = geometry),
            currentGeometry = geometry), GateDenial.GEOMETRY_UNVERIFIED)
        val geometry = f.geometry.copy(popupRootPath = "0/10")
        denied(f.context.copy(registration = f.registration.copy(geometry = geometry), currentGeometry = geometry), GateDenial.POPUP_MEMBERSHIP_UNVERIFIED)
    }

    @Test fun knownSafeSkipStillOnlyPassesSyntheticChecks() {
        val candidate = f.candidate.copy(label = "건너뛰기", role = ButtonRole.SKIP)
        val node = f.node.copy(text = candidate.label)
        val choice = f.registration.choice.copy(kind = ChoiceKind.SKIP_ONCE)
        val c = f.context.copy(registration = f.registration.copy(candidate = candidate, node = node, choice = choice), currentNode = node)
        val r = validator.evaluate(f.request.copy(candidate = candidate), c, 100)
        assertTrue(r.syntheticChecksSatisfied)
        assertFalse(r.productionReady)
    }

    @Test fun printablePrivateContextDoesNotExposeTextCoordinatesOrDigests() {
        val printed = listOf(f.context, f.registration, f.node, f.geometry, f.registration.episode).joinToString()
        listOf("닫기", "example.synthetic", "synthetic-content", "synthetic-structure", "left=", "windowId=", "synthetic:id/").forEach {
            assertFalse(printed.contains(it))
        }
    }
}
