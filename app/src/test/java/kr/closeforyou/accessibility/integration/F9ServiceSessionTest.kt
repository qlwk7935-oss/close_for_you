package kr.closeforyou.accessibility.integration

import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionActionPolicy
import kr.closeforyou.ui.voice.settings.AutoCloseConsent
import org.junit.Assert.*
import org.junit.Test

/** Same A source → real B wiring/policy → per-request F9 bridge as production. Fake screen only. */
@OptIn(ExperimentalCoroutinesApi::class)
class F9ServiceSessionTest {
    @Test fun pr59LabeledImageButtonAndClickableTextViewRequireRealBManualApprovalAndPressOnce() = runTest {
        for (widget in listOf("android.widget.ImageButton", "android.widget.TextView")) {
            val r = Rig(this, false)
            val node = r.f.node.copy(className = widget, text = if (widget.endsWith("ImageButton")) null else "닫기",
                contentDescription = if (widget.endsWith("ImageButton")) "닫기" else null)
            r.snapshot = r.snapshot.copy(nodes = persistentListOf(node, r.body))
            r.f.scene = r.f.before.copy(nodes = r.snapshot.nodes); r.publish()
            val a = r.session.wiring.extractor.extract(r.snapshot)
            val choice = UserChoice(r.snapshot.token, ChoiceKind.CLOSE_ONCE, a.closeCandidates.single().id, true)
            val result = r.session.executeUser(r.snapshot, a, choice, r.detection()) { true }
            assertTrue(result.attempted); assertEquals(1, r.f.clicks); assertEquals(1, r.decisions)
            assertFalse(r.session.executeUser(r.snapshot, a, choice, r.detection()) { true }.attempted)
            assertEquals(1, r.f.clicks); assertTrue(r.reports.isEmpty())
        }
    }
    @Test fun pr59UnlabeledRiskyAmbiguousOrMultipleTargetsNeverPress() = runTest {
        for (widget in listOf("android.widget.ImageButton", "android.widget.TextView")) {
            for (label in listOf(null, "X", "구매", "결제", "동의", "권한 허용", "쿠폰 받기", "오늘 하루 보지 않기", "확인")) {
                val r = Rig(this, false)
                val node = r.f.node.copy(className = widget, text = label, contentDescription = null)
                r.snapshot = r.snapshot.copy(nodes = persistentListOf(node, r.body))
                r.f.scene = r.f.before.copy(nodes = r.snapshot.nodes); r.publish()
                val a = r.session.wiring.extractor.extract(r.snapshot)
                val choice = UserChoice(r.snapshot.token, ChoiceKind.CLOSE_ONCE, a.closeCandidates.firstOrNull()?.id ?: "unavailable", true)
                assertFalse(r.session.executeUser(r.snapshot, a, choice, r.detection()) { true }.attempted)
                assertEquals(0, r.f.clicks)
            }
            val r = Rig(this, false)
            val node = r.f.node.copy(className = widget)
            r.snapshot = r.snapshot.copy(nodes = persistentListOf(node, node.copy(path = "0/2", viewId = "second"), r.body))
            r.f.scene = r.f.before.copy(nodes = r.snapshot.nodes); r.publish()
            val a = r.session.wiring.extractor.extract(r.snapshot)
            assertFalse(r.session.executeUser(r.snapshot, a, UserChoice(r.snapshot.token, ChoiceKind.CLOSE_ONCE,
                a.closeCandidates.first().id, true), r.detection()) { true }.attempted)
            assertEquals(0, r.f.clicks)
        }
    }
    @Test fun pr59WidgetsDoNotWidenAutomaticOrCustomClassScopeAndFinalChangesStillReject() = runTest {
        for (widget in listOf("android.widget.ImageButton", "android.widget.TextView")) {
            assertFalse(F9AutoCloser.supportedCloseWidget(widget, false))
            val r = Rig(this, false)
            r.snapshot = r.snapshot.copy(nodes = persistentListOf(r.f.node.copy(className = widget), r.body))
            r.f.scene = r.f.before.copy(nodes = r.snapshot.nodes); r.publish()
            val a = r.session.wiring.extractor.extract(r.snapshot)
            r.f.beforePress = { r.f.scene = r.f.scene?.copy(nodes = listOf(r.f.node.copy(text = "확인"), r.body)) }
            assertFalse(r.session.executeUser(r.snapshot, a, UserChoice(r.snapshot.token, ChoiceKind.CLOSE_ONCE,
                a.closeCandidates.single().id, true), r.detection()) { true }.attempted)
            assertEquals(0, r.f.clicks)
        }
        assertFalse(F9AutoCloser.supportedCloseWidget("custom.CloseButton", true))
        assertFalse(F9AutoCloser.supportedCloseWidget("android.widget.CheckBox", true))
        assertFalse(F9AutoCloser.supportedCloseWidget(null, true))
    }
    @Test fun manualCannotInventPopupScopeForActualSameWindowOrUncertainEvidence() = runTest {
        for (signals in listOf(persistentSetOf(PopupSignal.SAME_WINDOW_OVERLAY),
            persistentSetOf(PopupSignal.INSUFFICIENT_EVIDENCE), persistentSetOf<PopupSignal>())) {
            val r = Rig(this, false)
            // A real same-window Activity has no separate bounded APPLICATION window. A
            // historical SAME_WINDOW_OVERLAY signal alone does not change current geometry.
            if (PopupSignal.SAME_WINDOW_OVERLAY in signals) {
                r.snapshot = r.snapshot.copy(windows = r.snapshot.windows.map {
                    if (it.id == r.snapshot.token.windowId) it.copy(bounds = r.snapshot.screenBounds) else it
                }.toPersistentList())
                r.f.scene = r.f.scene?.copy(windows = r.snapshot.windows)
            }
            r.publish()
            val analysis = r.session.wiring.extractor.extract(r.snapshot)
            val choice = UserChoice(r.snapshot.token, ChoiceKind.CLOSE_ONCE, analysis.closeCandidates.single().id, buttonMeaningConfirmed = true)
            val result = r.session.executeUser(r.snapshot, analysis, choice, PopupDetection(r.snapshot.token, signals)) { true }
            assertFalse(result.attempted); assertEquals(0, r.f.clicks); assertEquals(listOf(result), r.results)
        }
    }
    @Test fun explicitChoiceStillRejectsPaymentConsentAndScamWithExactBReason() = runTest {
        for ((text, reason) in listOf("결제 선택 필요" to SafetyReason.IMPORTANT_DECISION,
            "동의 선택 필요" to SafetyReason.IMPORTANT_DECISION, "바이러스 감염 즉시 설치" to SafetyReason.SUSPICIOUS_CONTENT)) {
            val r = Rig(this, false); r.publish()
            val plain = r.session.wiring.extractor.extract(r.snapshot)
            val analysis = plain.copy(texts = persistentListOf(ExtractedText(text, r.body.bounds, TextSource.ACCESSIBILITY_TREE, null)))
            val result = r.session.executeUser(r.snapshot, analysis, UserChoice(r.snapshot.token, ChoiceKind.CLOSE_ONCE,
                plain.closeCandidates.single().id, buttonMeaningConfirmed = true), r.detection()) { true }
            assertFalse(result.attempted); assertEquals(0, r.f.clicks); assertEquals(reason, result.reason)
            assertEquals(listOf(result), r.results); assertTrue(r.reports.isEmpty())
        }
    }
    @Test fun cancelledManualAttemptDeliversExactlyOneUnknownToBAndNoAutomaticReport() = runTest {
        val r = Rig(this, false); r.publish()
        val analysis = r.session.wiring.extractor.extract(r.snapshot)
        val choice = UserChoice(r.snapshot.token, ChoiceKind.CLOSE_ONCE, analysis.closeCandidates.single().id, buttonMeaningConfirmed = true)
        val job = launch { r.session.executeUser(r.snapshot, analysis, choice, r.detection()) { true } }; runCurrent()
        assertEquals(1, r.f.clicks); job.cancelAndJoin()
        assertEquals(1, r.results.size); assertTrue(r.results.single().attempted)
        assertEquals(ClickOutcome.UNKNOWN, r.results.single().outcome); assertTrue(r.reports.isEmpty())
    }
    @Test fun explicitChoiceUsesOnePolicyAndOneResultWithProductionAutomationAndConsentOff() = runTest {
        val r = Rig(this, enabled = false); r.currentAccess = r.currentAccess.copy(consent = AutoCloseConsent.NONE)
        r.publish()
        val analysis = r.session.wiring.extractor.extract(r.snapshot)
        val choice = UserChoice(r.snapshot.token, ChoiceKind.CLOSE_ONCE, analysis.closeCandidates.single().id,
            buttonMeaningConfirmed = true)
        val result = r.session.executeUser(r.snapshot, analysis, choice, r.detection()) { true }
        assertTrue(kr.closeforyou.ui.voice.PressSuccess.isConfirmed(result))
        assertEquals(1, r.factories); assertEquals(1, r.decisions); assertEquals(1, r.f.clicks)
        assertEquals(listOf(result), r.results); assertTrue(r.reports.isEmpty()) // manual voice belongs to C flow
        assertFalse(r.session.executeUser(r.snapshot, analysis, choice, r.detection()) { true }.attempted)
        assertEquals(1, r.decisions); assertEquals(1, r.results.size)
    }

    @Test fun manualChoiceDoesNotRenewOriginalTtlAtRebasedEpoch() = runTest {
        for (age in listOf(4_999L, 5_000L)) {
            val r = Rig(this, false); r.publish()
            val old = r.session.wiring.extractor.extract(r.snapshot)
            val token = r.snapshot.token.copy(screenVersion = 9, captureEpoch = 9)
            val snapshot = r.snapshot.copy(token = token); val analysis = old.copy(token = token)
            r.f.now += age
            val result = r.session.executeUser(snapshot, analysis,
                UserChoice(token, ChoiceKind.CLOSE_ONCE, analysis.closeCandidates.single().id, buttonMeaningConfirmed = true), r.detection(token)) { true }
            assertEquals(age < 5_000, result.attempted); assertEquals(if (age < 5_000) 1 else 0, r.f.clicks)
            assertEquals(listOf(result), r.results); assertTrue(r.reports.isEmpty())
        }
    }

    @Test fun manualConcurrentDuplicateCannotConsumeOtherApprovalAndNoSuccessForRemainingPopup() = runTest {
        val r = Rig(this, false); r.publish(); r.f.after = r.f.scene
        val analysis = r.session.wiring.extractor.extract(r.snapshot)
        val choice = UserChoice(r.snapshot.token, ChoiceKind.CLOSE_ONCE, analysis.closeCandidates.single().id, buttonMeaningConfirmed = true)
        val first = async { r.session.executeUser(r.snapshot, analysis, choice, r.detection()) { true } }; runCurrent()
        assertEquals(1, r.f.clicks)
        assertFalse(r.session.executeUser(r.snapshot, analysis, choice, r.detection()) { true }.attempted)
        assertTrue(r.results.isEmpty())
        val result = first.await()
        assertEquals(ClickOutcome.STILL_PRESENT, result.outcome)
        assertFalse(kr.closeforyou.ui.voice.PressSuccess.isConfirmed(result))
        assertEquals(listOf(result), r.results); assertEquals(1, r.decisions)
    }

    @Test fun manualFinalGateRejectsRevocationInteractingInvalidationAndCleanup() = runTest {
        for (mode in listOf("CONSENT", "INTERACTION", "CURRENT", "CLEANUP", "SERVICE")) {
            val r = Rig(this, false); r.publish(); var current = true
            val analysis = r.session.wiring.extractor.extract(r.snapshot)
            r.f.beforePress = {
                when (mode) {
                    "CONSENT" -> r.currentAccess = r.currentAccess.copy(collectionConsent = false)
                    "INTERACTION" -> r.currentAccess = r.currentAccess.copy(userInteracting = true)
                    "CURRENT" -> current = false
                    "SERVICE" -> r.session.close(2)
                    else -> r.cleanup.retiring(99, 1, 20)
                }
            }
            val result = r.session.executeUser(r.snapshot, analysis,
                UserChoice(r.snapshot.token, ChoiceKind.CLOSE_ONCE, analysis.closeCandidates.single().id, buttonMeaningConfirmed = true), r.detection()) { current }
            assertFalse(mode, result.attempted); assertEquals(0, r.f.clicks)
            assertEquals(listOf(result), r.results); assertTrue(r.reports.isEmpty())
        }
    }
    @Test fun privateTreeStreamRejectsSensitiveContextSplitAcrossNodes() = runTest {
        val r = Rig(this)
        val nodes = persistentListOf(r.f.node, r.body.copy(text = "비밀"),
            r.body.copy(path = "0/2", text = "번호"), r.body.copy(path = "0/3", text = "광고"))
        val snapshot = r.snapshot.copy(nodes = nodes)
        r.raw.publish(snapshot, PopupDetection(snapshot.token, persistentSetOf(PopupSignal.NEW_WINDOW)))
        assertNull(r.raw.currentToken())
        assertEquals(0, r.decisions); assertEquals(0, r.f.clicks)
    }
    private class Rig(scope: TestScope, enabled: Boolean = true) {
        val f = F9AutoCloserTest.Fixture()
        val body = f.node.copy(path = "0/1", viewId = "body", className = "android.widget.TextView", text = "광고 신상 운동화",
            clickable = false, bounds = ScreenBounds(30, 40, 170, 80), actions = persistentSetOf())
        var snapshot = f.snapshot.copy(nodes = persistentListOf(f.node, body))
        val raw = F9CollectedSource { f.now }
        var currentAccess = F9SessionAccess(true, true, true, false, false,
            AutoCloseConsent(true, persistentSetOf(PopupClassification.ADVERTISEMENT, PopupClassification.COUPON), 1), true)
        val cleanup = OverlayCleanupBarrier(clock = { f.now })
        val results = mutableListOf<ActionResult>()
        val reports = mutableListOf<HandlingReport>()
        var factories = 0
        var decisions = 0
        var afterDecision: () -> Unit = {}
        val policy = DecisionActionPolicy(allowAutoSkip = false, nowUptimeMillis = { f.now })
        val session = F9ServiceSession(raw, f.port, F9Clock { f.now }, { currentAccess }, { cleanup.ready }, reports::add,
            { enabled }, policyFactory = { clock ->
                factories++; assertEquals(f.now, clock())
                object : ActionPolicy by policy {
                    override suspend fun decide(analysis: PopupAnalysis, context: DecisionContext): ActionDecision {
                        decisions++; return policy.decide(analysis, context).also { afterDecision() }
                    }
                    override fun onActionResult(result: ActionResult) { results += result; policy.onActionResult(result) }
                }
            }, dispatcher = StandardTestDispatcher(scope.testScheduler))
        private var event: PopupEvent.Candidate? = null
        init {
            f.scene = f.before.copy(nodes = snapshot.nodes)
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                session.wiring.source.events.collect { if (it is PopupEvent.Candidate) event = it }
            }
        }
        fun publish(): PopupEvent.Candidate {
            raw.publish(snapshot, PopupDetection(snapshot.token, persistentSetOf(PopupSignal.NEW_WINDOW)))
            return requireNotNull(event)
        }
        fun detection(token: SnapshotToken = snapshot.token) = PopupDetection(token, persistentSetOf(PopupSignal.NEW_WINDOW))
    }

    @Test fun actualWiringAndPolicyApproveOnceAndOnlyObservedRemovalIsSuccessful() = runTest {
        val r = Rig(this)
        val result = r.session.handle(r.publish())
        assertTrue(requireNotNull(result.result).closedConfirmed)
        assertEquals(1, r.factories); assertEquals(1, r.decisions); assertEquals(1, r.f.clicks)
        assertEquals(listOf(result.result!!.toActionResult()), r.results)
        assertEquals(r.results.single(), r.reports.single().result); assertNull(r.reports.single().title)
        assertNull(r.session.handle(r.publish()).decision)
        assertEquals(1, r.decisions); assertEquals(1, r.results.size)
    }

    @Test fun clickTrueWithRemainingPopupIsNotConfirmed() = runTest {
        val r = Rig(this); r.f.after = r.f.scene
        val result = requireNotNull(r.session.handle(r.publish()).result)
        assertTrue(result.attempted); assertEquals(ClickOutcome.STILL_PRESENT, result.outcome)
        assertFalse(result.closedConfirmed); assertFalse(r.reports.single().closedConfirmed)
    }

    @Test fun concurrentDuplicateCannotDecideAgainOrConsumeTheInFlightBReceipt() = runTest {
        val r = Rig(this); val event = r.publish()
        val first = async { r.session.handle(event) }; runCurrent()
        assertEquals(1, r.f.clicks); assertTrue(r.results.isEmpty())
        assertNull(r.session.handle(event).decision)
        val result = requireNotNull(first.await().result)
        assertTrue(result.closedConfirmed); assertEquals(1, r.decisions)
        assertEquals(listOf(result.toActionResult()), r.results)
        assertEquals(1, r.reports.size)
    }

    @Test fun ttlUsesTheOriginalCollectionAt4999And5000WithoutRenewal() = runTest {
        val accepted = Rig(this); val event = accepted.publish(); accepted.f.now += 4_999
        assertTrue(requireNotNull(accepted.session.handle(event).result).attempted)
        val expired = Rig(this); val old = expired.publish(); expired.f.now += 5_000
        assertNull(expired.session.handle(old).decision)
        assertEquals(0, expired.decisions); assertEquals(0, expired.f.clicks)
    }

    @Test fun offGateAndMissingConsentNeverProduceAnExecuteOrAttempt() = runTest {
        assertFalse(F9ServiceExecutionFeature.enabled)
        for (enabled in listOf(false, true)) {
            val r = Rig(this, enabled)
            if (enabled) r.currentAccess = r.currentAccess.copy(consent = AutoCloseConsent.NONE)
            val handled = r.session.handle(r.publish())
            assertNotEquals(NextStep.EXECUTE, handled.decision?.next)
            assertNull(handled.result); assertEquals(0, r.f.clicks); assertTrue(r.results.isEmpty())
        }
    }

    @Test fun revocationAfterBApprovalIsReportedOnceEvenBeforeF9ConsultsItsReceipt() = runTest {
        for (mode in listOf("CONSENT", "PRESET_RANGE", "FEEDBACK")) {
            val r = Rig(this)
            r.afterDecision = {
                r.currentAccess = when (mode) {
                    "CONSENT" -> r.currentAccess.copy(consent = AutoCloseConsent.NONE)
                    "PRESET_RANGE" -> r.currentAccess.copy(consent = r.currentAccess.consent.copy(
                        autoCloseKinds = persistentSetOf(PopupClassification.COUPON)))
                    else -> r.currentAccess.copy(announcementAvailable = false)
                }
            }
            val result = requireNotNull(r.session.handle(r.publish()).result)
            assertFalse(result.attempted); assertEquals(0, r.f.clicks)
            assertEquals(listOf(result.toActionResult()), r.results)
            assertEquals(r.results.single(), r.reports.single().result)
        }
    }

    @Test fun screenInteractionAndNativeCleanupPendingAtFinalGateNeverPress() = runTest {
        for (mode in listOf("SCREEN", "INTERACTION", "CLEANUP")) {
            val r = Rig(this)
            r.f.beforePress = {
                when (mode) {
                    "SCREEN" -> r.session.invalidate(2, false)
                    "INTERACTION" -> { r.currentAccess = r.currentAccess.copy(userInteracting = true); r.session.invalidate(2, true) }
                    else -> r.cleanup.retiring(99, 1, 20)
                }
            }
            val result = requireNotNull(r.session.handle(r.publish()).result)
            assertFalse(result.attempted); assertEquals(0, r.f.clicks)
            assertEquals(listOf(result.toActionResult()), r.results); assertEquals(1, r.reports.size)
        }
    }

    @Test fun incompleteTextNodeMismatchImportantDecisionAndScamCannotEarnFiveChecks() = runTest {
        for (text in listOf("광고 결제 동의", "계정 비밀번호", "보안 경고 원격 설치", "피싱 계좌 송금")) {
            val r = Rig(this)
            r.snapshot = r.snapshot.copy(nodes = persistentListOf(r.f.node, r.body.copy(text = text)))
            r.f.scene = r.f.before.copy(nodes = r.snapshot.nodes)
            r.raw.publish(r.snapshot, PopupDetection(r.snapshot.token, persistentSetOf(PopupSignal.NEW_WINDOW)))
            if (r.raw.currentToken() == null) { assertEquals(0, r.f.clicks); continue }
            val result = r.session.handle(r.publish())
            assertNotEquals(NextStep.EXECUTE, result.decision?.next); assertEquals(0, r.f.clicks)
        }
        val r = Rig(this); val event = r.publish()
        val wired = r.session.wiring.extractor.extract(r.snapshot)
        for (altered in listOf(wired.copy(texts = persistentListOf()), wired.copy(closeCandidates = persistentListOf()))) {
            val prepared = F9EligibilityAdapter.prepare(r.snapshot, event.detection, altered, r.f.scene, r.f.now)
            assertNull(prepared.autoEligibility)
        }
        r.f.scene = r.f.before // mismatched/missing body despite the original snapshot
        assertNotEquals(NextStep.EXECUTE, r.session.handle(event).decision?.next)
        assertEquals(0, r.f.clicks)
    }

    @Test fun protectedAppCannotExecuteEvenWhenItsPopupLooksLikeAnAd() = runTest {
        val r = Rig(this); val pkg = "com.kbstar.kbbank"
        r.snapshot = r.snapshot.copy(token = r.snapshot.token.copy(packageName = pkg))
        r.f.scene = r.f.before.copy(packageName = pkg, nodes = r.snapshot.nodes)
        r.raw.publish(r.snapshot, PopupDetection(r.snapshot.token, persistentSetOf(PopupSignal.NEW_WINDOW)))
        assertNull(r.raw.currentToken()); assertEquals(0, r.decisions)
        assertEquals(0, r.f.clicks)
    }

    @Test fun twoFreshPopupsUseTheSameSessionPolicyAndOldEventsCannotReenter() = runTest {
        val r = Rig(this); val first = r.publish()
        assertTrue(requireNotNull(r.session.handle(first).result).closedConfirmed)
        r.f.clicks = 0; r.f.now += 100
        r.snapshot = r.snapshot.copy(token = r.snapshot.token.copy(screenVersion = 2, captureEpoch = 2,
            popupInstanceId = "second", structureDigest = "second-structure"), capturedAtUptimeMillis = r.f.now)
        r.f.scene = r.f.before.copy(nodes = r.snapshot.nodes)
        r.session.invalidate(2, false)
        assertNull(r.session.handle(first).decision)
        assertTrue(requireNotNull(r.session.handle(r.publish()).result).closedConfirmed)
        assertEquals(1, r.factories); assertEquals(2, r.decisions); assertEquals(2, r.results.size)
    }

    @Test fun serviceStopAndCancellationAfterPressStillDeliverOneUnknownAttempt() = runTest {
        val r = Rig(this); val event = r.publish()
        val job = launch { r.session.handle(event) }; runCurrent()
        assertEquals(1, r.f.clicks)
        r.session.close(2); job.cancelAndJoin()
        assertEquals(1, r.results.size); assertTrue(r.results.single().attempted)
        assertEquals(ClickOutcome.UNKNOWN, r.results.single().outcome)
        assertEquals(1, r.reports.size); assertNull(r.session.handle(event).decision)
    }
}
