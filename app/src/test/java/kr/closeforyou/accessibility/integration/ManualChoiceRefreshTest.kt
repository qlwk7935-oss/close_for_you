package kr.closeforyou.accessibility.integration

import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionCandidateAttacher
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualChoiceRefreshTest {
    private class Rig(scope: TestScope, text: String = "광고 신상 안내") {
        val f = F9AutoCloserTest.Fixture()
        val body = f.node.copy(path = "0/1", className = "android.widget.TextView", text = text,
            viewId = "body", clickable = false, actions = persistentSetOf())
        val old = f.snapshot.copy(nodes = persistentListOf(f.node, body))
        val detection = PopupDetection(old.token, persistentSetOf(PopupSignal.NEW_WINDOW))
        val displayed = DecisionCandidateAttacher().attach(PopupEvent.Candidate(old, detection),
            PopupAnalysis(old.token, texts = F9CollectedSource.texts(old)))
        val choice = UserChoice(old.token, ChoiceKind.CLOSE_ONCE, displayed.closeCandidates.single().id, true)
        var reads = 0; var current = true
        var access = F9SessionAccess(true, true, true, false, false, announcementAvailable = true)
        val raw = F9CollectedSource { f.now }
        val session = F9ServiceSession(raw, f.port, F9Clock { f.now }, { access }, { true }, {},
            dispatcher = StandardTestDispatcher(scope.testScheduler))
        fun fresh() = old.copy(token = old.token.copy(screenVersion = 2, captureEpoch = 2, popupInstanceId = "fresh-read"),
            capturedAtUptimeMillis = f.now)
        suspend fun refresh(read: (() -> Unit) -> PopupSnapshot? = { check -> check(); fresh() }): ManualChoiceEvidence? =
            ManualChoiceRefresh.collect(old, displayed, choice, detection, { f.now }, { current }) {
                reads++; read(it)
            }
        suspend fun execute(e: ManualChoiceEvidence): ActionResult {
            f.scene = f.before.copy(nodes = e.snapshot.nodes, windows = e.snapshot.windows)
            return session.executeUser(e.snapshot, e.analysis, e.choice, e.detection) { current }
        }
    }
    @Test fun p01AfterSixSecondsUsesActualReadNewTreeNewEligibilityAndOneClick() = runTest {
        val r = Rig(this); r.f.now += 6_000
        val e = requireNotNull(r.refresh())
        assertEquals(1, r.reads); assertNotEquals(r.old.token, e.snapshot.token)
        assertEquals(r.f.now, e.snapshot.capturedAtUptimeMillis); assertEquals(e.snapshot.token, e.choice.token)
        assertNull(e.analysis.autoEligibility) // Only session's NEW F0 comparison + B may create it.
        assertTrue(r.execute(e).attempted); assertEquals(1, r.f.clicks)
        assertFalse(r.execute(e).attempted); assertEquals(1, r.f.clicks)
    }
    @Test fun oldSnapshotOrOnlyRebasedEpochNeverRenewsItsFiveSecondEvidence() = runTest {
        val r = Rig(this); r.f.now += 6_000
        assertNull(r.refresh { r.old })
        assertNull(r.refresh { r.old.copy(token = r.old.token.copy(screenVersion = 2, captureEpoch = 2)) })
        assertEquals(0, r.f.clicks)
    }
    @Test fun p02AndP03LikeCouponAndNoticeRequireTheSameNewPolicyAndFreshFullTree() = runTest {
        for (text in listOf("쿠폰 할인 안내", "업데이트 공지 안내")) {
            val r = Rig(this, text); r.f.now += 6_000
            assertTrue(r.execute(requireNotNull(r.refresh())).attempted); assertEquals(1, r.f.clicks)
        }
    }
    @Test fun changedPopupWindowButtonBoundsOrTargetLabelAllRefuse() = runTest {
        val r = Rig(this); r.f.now += 6_000
        val good = r.fresh()
        for (bad in listOf(good.copy(token = good.token.copy(contentDigest = "other")),
            good.copy(token = good.token.copy(windowId = 21)),
            good.copy(nodes = persistentListOf(r.f.node.copy(text = "구매"), r.body)),
            good.copy(nodes = persistentListOf(r.f.node.copy(bounds = ScreenBounds(1, 1, 4, 4)), r.body)),
            good.copy(completeness = SnapshotCompleteness.TRUNCATED))) {
            assertNull(r.refresh { bad }); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun failedCollectionOrRevocationDuringReadCannotBecomeEvidence() = runTest {
        val r = Rig(this); assertNull(r.refresh { null })
        try { r.refresh { check -> r.current = false; check(); r.fresh() }; fail("STALE_REQUIRED") }
        catch (_: CancellationException) { }
        assertEquals(0, r.f.clicks)
    }
    @Test fun freshReadCancellationPropagatesWithoutClick() = runTest {
        val r = Rig(this)
        try { r.refresh { throw CancellationException("SYNTHETIC") }; fail("CANCEL_REQUIRED") }
        catch (_: CancellationException) { }
        assertEquals(0, r.f.clicks)
    }
    @Test fun scamPaymentConsentPermissionAndSplitSensitiveTextNeverPress() = runTest {
        for (text in listOf("바이러스 감염 즉시 설치", "결제 선택 필요", "구매 선택 필요", "동의 선택 필요", "권한 허용 선택 필요")) {
            val r = Rig(this, text); r.f.now += 6_000
            val e = r.refresh()
            if (e != null) assertFalse(text, r.execute(e).attempted)
            assertEquals(text, 0, r.f.clicks)
        }
    }
    @Test fun newEvidenceStillExpiresAtFiveSecondsAndConsentOrInteractionStopsFinalPress() = runTest {
        for (mode in listOf("TTL", "CONSENT", "INTERACTION", "CURRENT", "SERVICE")) {
            val r = Rig(this); r.f.now += 6_000; val e = requireNotNull(r.refresh())
            r.f.beforePress = {
                when (mode) {
                    "TTL" -> r.f.now += 5_000
                    "CONSENT" -> r.access = r.access.copy(collectionConsent = false)
                    "INTERACTION" -> r.access = r.access.copy(userInteracting = true)
                    "CURRENT" -> r.current = false
                    else -> r.session.close(3)
                }
            }
            assertFalse(mode, r.execute(e).attempted); assertEquals(mode, 0, r.f.clicks)
        }
    }
    @Test fun p12NextPopupCannotUsePreviousIntentOrToken() = runTest {
        val r = Rig(this); val e = requireNotNull(r.refresh())
        assertNull(r.refresh { r.fresh().copy(token = r.fresh().token.copy(structureDigest = "next-popup")) })
        r.current = false; assertFalse(r.execute(e).attempted); assertEquals(0, r.f.clicks)
    }
    @Test fun evidenceAndReadFailuresHaveRedactedRepresentations() = runTest {
        val r = Rig(this); assertEquals("ManualChoiceEvidence(redacted)", requireNotNull(r.refresh()).toString())
    }
    @Test fun actualAdmissionContractWaitsForRemovalThenTouchEndBeforeReading() = runTest {
        val r = Rig(this); val removed = CompletableDeferred<Unit>(); val quiet = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val work = async {
            ManualChoiceRefresh.afterRemoval(r.old, r.displayed, r.choice, r.detection, { r.f.now }, { r.current },
                { calls += "REMOVE"; removed.await() }, { calls += "QUIET"; quiet.await() }) { check ->
                calls += "F0"; check(); r.fresh()
            }
        }
        runCurrent(); assertEquals(listOf("REMOVE"), calls)
        removed.complete(Unit); runCurrent(); assertEquals(listOf("REMOVE", "QUIET"), calls)
        quiet.complete(Unit); assertNotNull(work.await()); assertEquals(listOf("REMOVE", "QUIET", "F0"), calls)
        assertEquals(0, r.f.clicks) // Revalidation alone is not B authorization or a click.
    }
    @Test fun cleanupFailureTimeoutAndCancellationNeverReachTheCollector() = runTest {
        val r = Rig(this); var reads = 0
        assertNull(ManualChoiceRefresh.afterRemoval(r.old, r.displayed, r.choice, r.detection, { r.f.now }, { true },
            { delay(5_000) }, {}) { reads++; r.fresh() })
        try {
            ManualChoiceRefresh.afterRemoval(r.old, r.displayed, r.choice, r.detection, { r.f.now }, { true },
                { throw CancellationException("CLEANUP_UNAVAILABLE") }, {}) { reads++; r.fresh() }
            fail("CANCEL_REQUIRED")
        } catch (_: CancellationException) { }
        assertEquals(0, reads); assertEquals(0, r.f.clicks)
    }
    @Test fun consentRevocationWhileWaitingCannotUseTheNextSuccessfulRead() = runTest {
        val r = Rig(this)
        try {
            ManualChoiceRefresh.afterRemoval(r.old, r.displayed, r.choice, r.detection, { r.f.now }, { r.current },
                { r.current = false }, {}) { r.reads++; r.fresh() }
            fail("STALE_REQUIRED")
        } catch (_: CancellationException) { }
        assertEquals(0, r.reads); assertEquals(0, r.f.clicks)
    }
}
