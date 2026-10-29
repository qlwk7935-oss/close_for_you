package kr.closeforyou.accessibility.integration

import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionCandidateAttacher
import kr.closeforyou.ui.voice.PressSuccess
import kr.closeforyou.ui.voice.settings.AutoCloseConsent
import org.junit.Assert.*
import org.junit.Test

/** Real adapter/manual refresh/B policy/F9 session; Android port and monotonic time are fake. */
@OptIn(ExperimentalCoroutinesApi::class)
class F9EligibilityAdapterTest {
    private class Rig(scope: TestScope) {
        val f = F9AutoCloserTest.Fixture()
        val body = f.node.copy(path = "0/1", className = "android.widget.TextView", text = "광고 상품 안내",
            clickable = false, bounds = ScreenBounds(30, 40, 170, 80), actions = persistentSetOf())
        // B's real SAME_WINDOW_OVERLAY finder needs an actual unambiguous Dialog subtree.
        // Do not substitute NEW_WINDOW or inject candidates to make this fixture pass.
        val dialog = body.copy(path = "0", className = "android.app.Dialog", text = null,
            bounds = f.windows.last().bounds)
        val snapshot = f.snapshot.copy(nodes = persistentListOf(dialog, f.node, body))
        fun detection(s: PopupSnapshot = snapshot, signal: PopupSignal = PopupSignal.SAME_WINDOW_OVERLAY) =
            PopupDetection(s.token, persistentSetOf(signal))
        fun analysis(s: PopupSnapshot = snapshot) = DecisionCandidateAttacher().attach(PopupEvent.Candidate(s, detection(s)),
            PopupAnalysis(s.token, texts = F9CollectedSource.texts(s)))
        fun choice(s: PopupSnapshot = snapshot, a: PopupAnalysis = analysis(s)) =
            UserChoice(s.token, ChoiceKind.CLOSE_ONCE, a.closeCandidates.single().id, buttonMeaningConfirmed = true)
        val codes = mutableListOf<ManualActionCode>()
        val trace = ManualActionTrace(codes::add)
        var settings = F9SessionAccess(true, true, true, false, false, AutoCloseConsent.NONE, true)
        var current = true
        var cleanup = true
        val session = F9ServiceSession(F9CollectedSource { f.now }, f.port, F9Clock { f.now }, { settings },
            { cleanup }, {}, dispatcher = StandardTestDispatcher(scope.testScheduler))
        init { f.scene = f.before.copy(nodes = snapshot.nodes) }
        suspend fun prepare(s: PopupSnapshot = snapshot, d: PopupDetection = detection(s), a: PopupAnalysis = analysis(s),
            scene: F9Scene? = f.scene, time: Long = f.now, c: UserChoice = choice(s, a)): PopupAnalysis =
            F9EligibilityAdapter.prepareUser(s, d, a, scene, time, c, trace)
        suspend fun execute(s: PopupSnapshot = snapshot, d: PopupDetection = detection(s), a: PopupAnalysis = analysis(s),
            c: UserChoice = choice(s, a)): ActionResult = session.executeUser(s, a, c, d, trace) { current }
        fun report(check: F9EligibilityCheck) = codes.mapNotNull { it.eligibility }.single { it.check == check }
    }

    @Test fun boundedSeparateWindowWithoutHistoricalNewWindowEarnsOnlyVerifiedChecks() = runTest {
        val r = Rig(this)
        val prepared = r.prepare()
        assertTrue(prepared.autoEligibility!!.complete)
        assertEquals(EligibilityCheck.entries.toSet(), prepared.autoEligibility!!.checks)
        assertEquals(r.snapshot.capturedAtUptimeMillis, prepared.autoEligibility!!.capturedAtUptimeMillis)
        assertTrue(r.codes.all { it.state == ManualActionState.PASSED })
        assertEquals(9, r.codes.size)
        assertTrue(r.codes.mapNotNull { it.eligibility }.all { it.mode == F9EligibilityMode.USER_CHOICE })
    }
    @Test fun automaticPathStillRequiresNewWindowAndDoesNotInheritManualReceipt() = runTest {
        val r = Rig(this)
        val wired = r.analysis()
        assertNull(F9EligibilityAdapter.prepare(r.snapshot, r.detection(), wired, r.f.scene, r.f.now).autoEligibility)
        assertNotNull(F9EligibilityAdapter.prepare(r.snapshot, r.detection(signal = PopupSignal.NEW_WINDOW), wired, r.f.scene, r.f.now).autoEligibility)
        assertFalse(F9ServiceExecutionFeature.enabled)
    }
    @Test fun actualSessionUsesRealBUserChoiceAndClicksOnceWithObservedClosure() = runTest {
        val r = Rig(this)
        val result = r.execute()
        assertTrue(result.attempted); assertEquals(1, r.f.clicks)
        assertEquals(ClickOutcome.CLOSED_CONFIRMED, result.outcome); assertTrue(PressSuccess.isConfirmed(result))
        assertTrue(r.codes.any { it.step == ManualActionStep.B_DECISION && it.state == ManualActionState.APPROVED && it.next == NextStep.EXECUTE })
        assertTrue(r.codes.any { it.step == ManualActionStep.F9_VALIDATION && it.state == ManualActionState.PASSED })
        assertFalse(r.execute().attempted); assertEquals(1, r.f.clicks)
    }
    @Test fun originalSixSecondsOldRequiresActualFreshReadAndRecomputedBTargets() = runTest {
        val r = Rig(this); r.f.now += 6_000
        var reads = 0
        val fresh = ManualChoiceRefresh.collect(r.snapshot, r.analysis(), r.choice(), r.detection(), { r.f.now }, { true }) { checkpoint ->
            checkpoint(); reads++
            r.snapshot.copy(token = r.snapshot.token.copy(screenVersion = 2, captureEpoch = 2), capturedAtUptimeMillis = r.f.now)
        }!!
        assertEquals(1, reads); assertNull(fresh.analysis.autoEligibility)
        val result = r.execute(fresh.snapshot, fresh.detection, fresh.analysis, fresh.choice)
        assertTrue(result.attempted); assertEquals(ClickOutcome.CLOSED_CONFIRMED, result.outcome); assertEquals(1, r.f.clicks)
    }
    @Test fun oldSnapshotCannotRenewTtlByChangingOnlyEpoch() = runTest {
        val r = Rig(this); r.f.now += 6_000
        val rebased = r.snapshot.copy(token = r.snapshot.token.copy(screenVersion = 2, captureEpoch = 2))
        assertFalse(r.execute(rebased).attempted); assertEquals(0, r.f.clicks)
        assertEquals(F9EligibilityDetail.TTL_EXPIRED, r.report(F9EligibilityCheck.CAPTURE_CURRENT).detail)
    }
    @Test fun strictFiveSecondBoundaryAndClockErrorsAreDiagnosedWithoutRenewal() = runTest {
        for ((time, detail) in listOf(-1L to F9EligibilityDetail.CLOCK_REVERSED,
            6_000L to F9EligibilityDetail.TTL_EXPIRED, 6_001L to F9EligibilityDetail.TTL_EXPIRED)) {
            val r = Rig(this)
            assertNull(r.prepare(time = time).autoEligibility)
            assertEquals(detail, r.report(F9EligibilityCheck.CAPTURE_CURRENT).detail)
            assertEquals(ManualActionState.NOT_ATTEMPTED, r.report(F9EligibilityCheck.F0_PASSED).state)
        }
        val r = Rig(this); assertNotNull(r.prepare(time = 5_999L).autoEligibility)
        val invalid = Rig(this); assertNull(invalid.prepare(s = invalid.snapshot.copy(capturedAtUptimeMillis = -1)).autoEligibility)
        assertEquals(F9EligibilityDetail.NEGATIVE_CAPTURE, invalid.report(F9EligibilityCheck.CAPTURE_CURRENT).detail)
    }
    @Test fun missingFreshScreenNeverManufacturesAnyPassedF0Check() = runTest {
        val r = Rig(this); r.f.scene = null
        assertFalse(r.execute().attempted); assertEquals(0, r.f.clicks)
        assertEquals(F9EligibilityDetail.SCENE_UNAVAILABLE, r.report(F9EligibilityCheck.SCREEN_AVAILABLE).detail)
        assertEquals(ManualActionState.NOT_ATTEMPTED, r.report(F9EligibilityCheck.TREE_COMPLETE).state)
    }
    @Test fun analysisOrDetectionTokenMismatchCannotProduceEligibility() = runTest {
        for (analysisMismatch in listOf(true, false)) {
            val r = Rig(this); val other = r.snapshot.token.copy(screenVersion = 2)
            assertNull(r.prepare(d = if (analysisMismatch) r.detection() else r.detection().copy(token = other),
                a = if (analysisMismatch) r.analysis().copy(token = other) else r.analysis()).autoEligibility)
            assertEquals(if (analysisMismatch) F9EligibilityDetail.ANALYSIS_TOKEN else F9EligibilityDetail.DETECTION_TOKEN,
                r.report(F9EligibilityCheck.TOKEN_BINDING).detail)
        }
    }
    @Test fun completeSceneAndEveryExactComparisonRemainRequired() = runTest {
        val changes: List<Pair<F9EligibilityDetail, (F9Scene) -> F9Scene>> = listOf(
            F9EligibilityDetail.SCENE_INCOMPLETE to { it.copy(complete = false) },
            F9EligibilityDetail.PACKAGE_CHANGED to { it.copy(packageName = "synthetic.other") },
            F9EligibilityDetail.WINDOW_CHANGED to { it.copy(windowId = 999) },
            F9EligibilityDetail.FRAME_CHANGED to { it.copy(screen = ScreenBounds(0, 0, 301, 500)) },
            F9EligibilityDetail.NODES_CHANGED to { it.copy(nodes = it.nodes.dropLast(1)) },
            F9EligibilityDetail.WINDOWS_CHANGED to { it.copy(windows = it.windows.reversed()) })
        for ((detail, change) in changes) {
            val r = Rig(this); r.f.scene = change(requireNotNull(r.f.scene))
            assertFalse(r.execute().attempted); assertEquals(0, r.f.clicks)
            assertEquals(detail, r.report(F9EligibilityCheck.F0_PASSED).detail)
        }
    }
    @Test fun incompleteSnapshotNeverReceivesTreeComplete() = runTest {
        val r = Rig(this)
        val s = r.snapshot.copy(completeness = SnapshotCompleteness.TRUNCATED)
        assertNull(r.prepare(s).autoEligibility)
        assertEquals(F9EligibilityDetail.SNAPSHOT_INCOMPLETE, r.report(F9EligibilityCheck.TREE_COMPLETE).detail)
    }
    @Test fun realSameWindowFullscreenAndOutOfFrameStayBlocked() = runTest {
        for (bounds in listOf(ScreenBounds(0, 0, 300, 500), ScreenBounds(-1, 20, 200, 200))) {
            val r = Rig(this)
            val s = r.snapshot.copy(windows = r.snapshot.windows.map {
                if (it.id == r.snapshot.token.windowId) it.copy(bounds = bounds) else it
            }.toPersistentList())
            r.f.scene = r.f.scene?.copy(windows = s.windows)
            assertFalse(r.execute(s).attempted); assertEquals(0, r.f.clicks)
            assertEquals(F9EligibilityDetail.WINDOW_UNBOUNDED, r.report(F9EligibilityCheck.POPUP_SCOPE_CONFIRMED).detail)
        }
    }
    @Test fun ambiguousMissingNonApplicationInactiveOrUnfocusedWindowIsNotScope() = runTest {
        val edits: List<Pair<F9EligibilityDetail, (List<WindowSnapshot>) -> List<WindowSnapshot>>> = listOf(
            F9EligibilityDetail.POPUP_WINDOW_UNAVAILABLE to { it + it.last() },
            F9EligibilityDetail.POPUP_WINDOW_UNAVAILABLE to { it.dropLast(1) },
            F9EligibilityDetail.WINDOW_TYPE to { it.dropLast(1) + it.last().copy(type = WindowType.OTHER) },
            F9EligibilityDetail.WINDOW_INACTIVE to { it.dropLast(1) + it.last().copy(active = false) },
            F9EligibilityDetail.WINDOW_UNFOCUSED to { it.dropLast(1) + it.last().copy(focused = false) })
        for ((detail, edit) in edits) {
            val r = Rig(this); val s = r.snapshot.copy(windows = edit(r.snapshot.windows).toPersistentList())
            r.f.scene = r.f.scene?.copy(windows = s.windows)
            assertNull(r.prepare(s).autoEligibility)
            assertEquals(detail, r.report(F9EligibilityCheck.POPUP_SCOPE_CONFIRMED).detail)
        }
    }
    @Test fun insufficientFullscreenOrMissingSignalsCannotUseManualScope() = runTest {
        for (signals in listOf(persistentSetOf<PopupSignal>(), persistentSetOf(PopupSignal.INSUFFICIENT_EVIDENCE),
            persistentSetOf(PopupSignal.FULL_SCREEN_TRANSITION), persistentSetOf(PopupSignal.NEW_WINDOW, PopupSignal.INSUFFICIENT_EVIDENCE))) {
            val r = Rig(this)
            assertFalse(r.execute(d = PopupDetection(r.snapshot.token, signals)).attempted)
            assertEquals(0, r.f.clicks)
            assertEquals(F9EligibilityDetail.DETECTION_UNCERTAIN, r.report(F9EligibilityCheck.POPUP_SCOPE_CONFIRMED).detail)
        }
    }
    @Test fun boundExplicitCloseChoiceIsRequiredWithNoVisualOrMissingTargetSubstitution() = runTest {
        for ((detail, change) in listOf<Pair<F9EligibilityDetail, (UserChoice) -> UserChoice>>(
            F9EligibilityDetail.CHOICE_TOKEN to { it.copy(token = it.token.copy(screenVersion = 2)) },
            F9EligibilityDetail.CHOICE_KIND to { it.copy(kind = ChoiceKind.KEEP_OPEN) },
            F9EligibilityDetail.CHOICE_MEANING to { it.copy(buttonMeaningConfirmed = false) },
            F9EligibilityDetail.CHOICE_TARGET to { it.copy(candidateId = "unavailable") })) {
            val r = Rig(this); assertFalse(r.execute(c = change(r.choice())).attempted)
            assertEquals(0, r.f.clicks); assertEquals(detail, r.report(F9EligibilityCheck.MANUAL_CHOICE).detail)
        }
        val r = Rig(this); val a = r.analysis()
        assertNull(r.prepare(a = a.copy(closeCandidates = persistentListOf(a.closeCandidates.single().copy(evidence = EvidenceLevel.E4_VISUAL_ONLY))),
            c = r.choice()).autoEligibility)
        assertEquals(F9EligibilityDetail.CHOICE_TARGET, r.report(F9EligibilityCheck.MANUAL_CHOICE).detail)
    }
    @Test fun omittedOrSummarizedPopupTextCannotPassDecisionTextScoped() = runTest {
        val r = Rig(this)
        assertNull(r.prepare(a = r.analysis().copy(texts = persistentListOf())).autoEligibility)
        assertEquals(F9EligibilityDetail.TEXT_NOT_COMPLETE, r.report(F9EligibilityCheck.DECISION_TEXT_SCOPED).detail)
    }
    @Test fun candidatePathAndBoundsMustBeInsideActualPopup() = runTest {
        val r = Rig(this); val a = r.analysis()
        val outside = a.closeCandidates.single().copy(bounds = ScreenBounds(0, 0, 5, 5))
        assertNull(r.prepare(a = a.copy(closeCandidates = persistentListOf(outside))).autoEligibility)
        assertEquals(F9EligibilityDetail.TARGET_OUTSIDE_POPUP, r.report(F9EligibilityCheck.TARGETS_IN_POPUP).detail)
    }
    @Test fun protectedPackageNeverEarnsF0Eligibility() = runTest {
        val r = Rig(this); val s = r.snapshot.copy(token = r.snapshot.token.copy(packageName = "com.kbstar.kbbank"))
        r.f.scene = r.f.scene?.copy(packageName = s.token.packageName)
        assertFalse(r.execute(s).attempted); assertEquals(0, r.f.clicks)
        assertEquals(F9EligibilityDetail.PACKAGE_PROTECTED, r.report(F9EligibilityCheck.F0_PASSED).detail)
    }
    @Test fun paymentPurchaseConsentPermissionSubscriptionDeleteAndScamNeverClick() = runTest {
        for (text in listOf("결제", "구매", "동의", "권한 허용", "구독", "삭제", "바이러스 감염 즉시 설치")) {
            val r = Rig(this); val s = r.snapshot.copy(nodes = persistentListOf(r.dialog, r.f.node, r.body.copy(text = text)))
            r.f.scene = r.f.scene?.copy(nodes = s.nodes)
            assertFalse(r.execute(s).attempted); assertEquals(0, r.f.clicks)
            assertEquals(F9EligibilityDetail.TEXT_PROTECTED, r.report(F9EligibilityCheck.F0_PASSED).detail)
            assertEquals(ManualActionState.REJECTED, r.report(F9EligibilityCheck.DECISION_TEXT_SCOPED).state)
        }
    }
    @Test fun finalTargetChangeStillPreventsPressDespiteEarlierCompleteEligibility() = runTest {
        val r = Rig(this)
        r.f.beforePress = { r.f.scene = r.f.scene?.copy(nodes = listOf(r.f.node.copy(text = "구매"), r.body)) }
        assertFalse(r.execute().attempted); assertEquals(0, r.f.clicks)
        assertEquals(ManualActionState.PASSED, r.report(F9EligibilityCheck.POPUP_SCOPE_CONFIRMED).state)
    }
    @Test fun ttlConsentCurrentnessServiceAndCleanupAreRecheckedImmediatelyBeforeClick() = runTest {
        for (mode in listOf("TTL", "CONSENT", "CURRENT", "INTERACTION", "SERVICE", "CLEANUP")) {
            val r = Rig(this)
            r.f.beforePress = { when (mode) {
                "TTL" -> r.f.now += 5_000
                "CONSENT" -> r.settings = r.settings.copy(collectionConsent = false)
                "CURRENT" -> r.current = false
                "INTERACTION" -> r.settings = r.settings.copy(userInteracting = true)
                "SERVICE" -> r.session.close(2)
                else -> r.cleanup = false
            } }
            assertFalse(mode, r.execute().attempted); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun mechanicalClickReturnWithRemainingPopupIsNeverClosureSuccess() = runTest {
        val r = Rig(this); r.f.after = r.f.scene
        val result = r.execute()
        assertTrue(result.attempted); assertEquals(1, r.f.clicks); assertEquals(ClickOutcome.STILL_PRESENT, result.outcome)
        assertFalse(PressSuccess.isConfirmed(result))
    }
    @Test fun unknownPostClickScreenCannotBeReportedAsClosed() = runTest {
        val r = Rig(this); r.f.after = null
        val result = r.execute()
        assertTrue(result.attempted); assertEquals(1, r.f.clicks); assertEquals(ClickOutcome.UNKNOWN, result.outcome)
        assertFalse(PressSuccess.isConfirmed(result))
    }
    @Test fun cancellationDuringFreshReadPropagatesWithoutClickOrInventedFailureCode() = runTest {
        val r = Rig(this); val cancellation = CancellationException("FIXED_TEST_CANCEL")
        val port = object : F9ScreenPort {
            override fun read(checkpoint: () -> Unit): F9Scene? { throw cancellation }
            override fun press(reference: F9Reference, accept: (F9Scene) -> Boolean): F9Press = error("MUST_NOT_PRESS")
        }
        val session = F9ServiceSession(F9CollectedSource { r.f.now }, port, F9Clock { r.f.now }, { r.settings }, { true }, {},
            dispatcher = StandardTestDispatcher(testScheduler))
        assertSame(cancellation, runCatching { session.executeUser(r.snapshot, r.analysis(), r.choice(), r.detection(), r.trace) { true } }.exceptionOrNull())
        assertEquals(0, r.f.clicks); assertTrue(r.codes.none { it.eligibility != null })
    }
    @Test fun diagnosticFailureDoesNotChangeEligibilityOrRepeatWorkAndCodesAreFixedOnly() = runTest {
        val r = Rig(this); var reports = 0
        val throwing = ManualActionTrace { code ->
            code.eligibility?.let { assertEquals(F9EligibilityMode.USER_CHOICE, it.mode) }
            reports++; error("FIXED_LOG_FAILURE")
        }
        val result = r.session.executeUser(r.snapshot, r.analysis(), r.choice(), r.detection(), throwing) { true }
        assertTrue(result.attempted); assertEquals(1, r.f.clicks); assertTrue(reports > 0)
        assertTrue(PressSuccess.isConfirmed(result))
    }
}
