package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionCandidateAttacher
import kr.closeforyou.ui.voice.overlay.*
import org.junit.Assert.*
import org.junit.Test

/** Same window selection/comparison/review ordering used by Android. Framework objects and
 * Binder timing are not exercised here; native ownership remains a separate prerequisite. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualDisplayReviewTest {
    private class Rig {
        val f = F9AutoCloserTest.Fixture()
        val overlay = WindowSnapshot(30, WindowType.ACCESSIBILITY_OVERLAY, f.snapshot.screenBounds, true, true, 3)
        var scene = f.before.copy(windows = f.windows.map {
            if (it.id == 20) it.copy(active = false, focused = false) else it
        } + overlay)
        fun verdict() = ManualDisplayContinuity.assess(f.snapshot, scene, 30)
    }
    @Test fun onlyContentAndWindowStateCanRequestTheNewReadOnlyReview() {
        assertTrue(manualDisplayReviewEvent(E.TYPE_WINDOW_CONTENT_CHANGED)); assertTrue(manualDisplayReviewEvent(E.TYPE_WINDOW_STATE_CHANGED))
        for (t in listOf(E.TYPE_WINDOWS_CHANGED, E.TYPE_VIEW_TEXT_CHANGED, E.TYPE_VIEW_SCROLLED, E.TYPE_VIEW_CLICKED,
            E.TYPE_TOUCH_INTERACTION_START, E.TYPE_VIEW_ACCESSIBILITY_FOCUSED)) assertFalse(manualDisplayReviewEvent(t))
    }
    @Test fun sameWholePopupAndNativeOverlayRetainDisplayWithoutCallingItOwnedEvent() {
        val r = Rig(); assertEquals(ManualDisplayVerdict.SAME, r.verdict())
        assertTrue(r.scene.windows.single { it.id == 30 }.active)
        assertTrue(r.f.snapshot.windows.single { it.id == 20 }.active) // source was never rewritten
    }
    @Test fun oldOrForeignMetadataCannotReplaceTheMandatoryCurrentScreenCheck() {
        val r = Rig(); var reads = 0; var retired = 0; var removed = 0
        assertEquals(ManualDisplayVerdict.SAME, reviewManualDisplay({ retired++ }, { reads++; r.verdict() }, { removed++ }))
        r.scene = r.scene.copy(nodes = listOf(r.f.node.copy(text = "바뀐 팝업")))
        assertEquals(ManualDisplayVerdict.CHANGED, reviewManualDisplay({ retired++ }, { reads++; r.verdict() }, { removed++ }))
        assertEquals(2, reads); assertEquals(2, retired); assertEquals(1, removed)
    }
    @Test fun bodyOrButtonLabelPathBoundsAndActionChangesAreRejected() {
        for (mode in listOf("TEXT", "LABEL", "PATH", "BOUNDS", "ACTION", "ENABLED")) {
            val r = Rig(); val n = r.f.node
            r.scene = r.scene.copy(nodes = listOf(when (mode) {
                "TEXT" -> n.copy(text = "다른 팝업")
                "LABEL" -> n.copy(contentDescription = "다른 버튼")
                "PATH" -> n.copy(path = "0/1")
                "BOUNDS" -> n.copy(bounds = ScreenBounds(30, 131, 100, 180))
                "ACTION" -> n.copy(actions = persistentSetOf())
                else -> n.copy(enabled = false)
            }))
            assertEquals(ManualDisplayVerdict.CHANGED, r.verdict())
        }
    }
    @Test fun disappearanceAndPopupReplacementNeverRetainThePrompt() {
        for (mode in listOf("REMOVED", "REPLACED")) {
            val r = Rig(); r.scene = r.scene.copy(windows = r.scene.windows.filter { it.id != 20 })
            if (mode == "REPLACED") r.scene = r.scene.copy(windows = r.scene.windows + r.f.windows[1].copy(id = 21))
            assertNotEquals(ManualDisplayVerdict.SAME, r.verdict())
        }
    }
    @Test fun anotherActiveApplicationOrSystemWindowIsNotMistakenForC() {
        for (kind in listOf(WindowType.APPLICATION, WindowType.SYSTEM)) {
            val r = Rig(); r.scene = r.scene.copy(windows = r.scene.windows + WindowSnapshot(40, kind,
                r.f.snapshot.screenBounds, true, true, 5))
            assertNull(ManualDisplayContinuity.target(r.scene.windows, 20, 30))
            assertNotEquals(ManualDisplayVerdict.SAME, r.verdict())
        }
    }
    @Test fun missingExtraReusedOrWrongTypeNativeWindowCannotGrantDisplayContinuity() {
        for (mode in listOf("MISSING", "EXTRA", "REUSED", "TYPE", "LOW_LAYER", "IME")) {
            val r = Rig()
            r.scene = r.scene.copy(windows = when (mode) {
                "MISSING" -> r.scene.windows.filter { it.id != 30 }
                "EXTRA" -> r.scene.windows + r.overlay.copy(id = 31)
                "REUSED" -> r.scene.windows + r.overlay
                "TYPE" -> r.scene.windows.filter { it.id != 30 } + r.overlay.copy(type = WindowType.SYSTEM)
                "LOW_LAYER" -> r.scene.windows.filter { it.id != 30 } + r.overlay.copy(layer = 1)
                else -> r.scene.windows + r.overlay.copy(id = 40, type = WindowType.INPUT_METHOD)
            })
            assertNotEquals(ManualDisplayVerdict.SAME, r.verdict())
        }
        assertNull(ManualDisplayContinuity.target(Rig().scene.windows, 20, 20))
    }
    @Test fun inactiveOrUnfocusedUnderlyingSceneWithoutCFocusIsRejected() {
        for (mode in listOf("ACTIVE", "FOCUSED")) {
            val r = Rig(); r.scene = r.scene.copy(windows = r.scene.windows.map {
                if (it.id != 30) it else if (mode == "ACTIVE") it.copy(active = false) else it.copy(focused = false)
            })
            assertNotEquals(ManualDisplayVerdict.SAME, r.verdict())
        }
    }
    @Test fun otherWindowMetadataPopupGeometryOrDisplayChangesAreNotNormalizedAway() {
        for (mode in listOf("BASE", "GEOMETRY", "LAYER", "FRAME")) {
            val r = Rig()
            r.scene = if (mode == "FRAME") r.scene.copy(screen = ScreenBounds(0, 0, 301, 500)) else r.scene.copy(windows = r.scene.windows.map {
                when {
                    mode == "BASE" && it.id == 10 -> it.copy(layer = 0)
                    mode == "GEOMETRY" && it.id == 20 -> it.copy(bounds = ScreenBounds(21, 20, 200, 200))
                    mode == "LAYER" && it.id == 20 -> it.copy(layer = 1)
                    else -> it
                }
            })
            assertEquals(ManualDisplayVerdict.CHANGED, r.verdict())
        }
    }
    @Test fun packageOrRootWindowMismatchIsRejectedEvenWithTheSameDescriptors() {
        val r = Rig(); r.scene = r.scene.copy(packageName = "other.app")
        assertEquals(ManualDisplayVerdict.CHANGED, r.verdict())
        r.scene = r.f.before.copy(windowId = 21, windows = r.scene.windows)
        assertEquals(ManualDisplayVerdict.CHANGED, r.verdict())
    }
    @Test fun incompleteEmptyAndMalformedGeometryCannotBecomeSame() {
        for (mode in listOf("INCOMPLETE", "EMPTY", "RECT", "FULLSCREEN")) {
            val r = Rig()
            r.scene = when (mode) {
                "INCOMPLETE" -> r.scene.copy(complete = false)
                "EMPTY" -> r.scene.copy(nodes = emptyList())
                "RECT" -> r.scene.copy(nodes = listOf(r.f.node.copy(bounds = ScreenBounds(10, 10, 10, 10))))
                else -> r.scene.copy(windows = r.scene.windows.map { if (it.id == 20) it.copy(bounds = r.scene.screen) else it })
            }
            assertNotEquals(ManualDisplayVerdict.SAME, r.verdict())
        }
    }
    @Test fun dangerousTextIsStillRejectedEvenWhenBothOldAndCurrentTreesContainIt() {
        for (text in listOf("결제", "구매", "동의", "권한 허용", "바이러스 감염 즉시 설치")) {
            val r = Rig(); val nodes = persistentListOf(r.f.node.copy(text = text))
            assertEquals(ManualDisplayVerdict.UNAVAILABLE, ManualDisplayContinuity.assess(r.f.snapshot.copy(nodes = nodes),
                r.scene.copy(nodes = nodes), 30))
        }
    }
    @Test fun actualPrivateExecutionEvidenceIsRetiredBeforeTheReadAndNeverRestored() {
        val r = Rig(); val raw = F9CollectedSource { r.f.now }
        raw.publish(r.f.snapshot, PopupDetection(r.f.token, persistentSetOf(PopupSignal.NEW_WINDOW)))
        assertEquals(r.f.token, raw.currentToken()); var removed = false
        val result = reviewManualDisplay(retire = { raw.invalidate(2) }, verify = {
            assertNull(raw.currentToken()); r.verdict()
        }, remove = { removed = true })
        assertEquals(ManualDisplayVerdict.SAME, result); assertNull(raw.currentToken()); assertFalse(removed)
    }
    @Test fun theRealInvalidationContractRetainsOnlyTheDisplayWhileReviewIsPending() {
        var revoked = 0; var stopped = 0
        assertTrue(applyManualInvalidation(ManualPromptCause.DISPLAY_REVIEW_PENDING, true, true, false, false,
            { revoked++ }, { stopped++ }))
        assertEquals(0, revoked); assertEquals(0, stopped)
        assertFalse(applyManualInvalidation(ManualPromptCause.CONTENT_INVALIDATED, true, true, false, false,
            { revoked++ }, { stopped++ }))
        assertEquals(1, revoked); assertEquals(1, stopped)
    }
    @Test fun changedUnavailableAndReadTimeoutRemoveAfterEvidenceRetirement() {
        for (v in listOf(ManualDisplayVerdict.CHANGED, ManualDisplayVerdict.UNAVAILABLE)) {
            val order = mutableListOf<String>()
            assertEquals(v, reviewManualDisplay({ order += "RETIRE" }, { order += "READ"; v }, { order += "REMOVE" }))
            assertEquals(listOf("RETIRE", "READ", "REMOVE"), order)
        }
    }
    @Test fun originalReadCancellationSurvivesCleanupFailureAndStillRemoves() {
        val cancel = CancellationException("FIXED_CANCEL"); var removed = 0
        try { reviewManualDisplay({}, { throw cancel }, { removed++; error("FIXED_RELEASE") }); fail("CANCEL_REQUIRED") }
        catch (actual: CancellationException) { assertSame(cancel, actual) }
        assertEquals(1, removed)
    }
    @Test fun retireAndReadFailuresCannotBecomeSameOrSkipRemoval() {
        for (duringRetire in listOf(true, false)) {
            val failure = IllegalStateException("FIXED_FAILURE"); var removed = false
            try {
                reviewManualDisplay({ if (duringRetire) throw failure }, { throw failure }, { removed = true })
                fail("FAILURE_REQUIRED")
            } catch (actual: IllegalStateException) { assertSame(failure, actual) }
            assertTrue(removed)
        }
    }
    @Test fun displayComparisonCannotAuthorizeF9WhileCIsPresent() = runTest {
        val r = Rig(); assertEquals(ManualDisplayVerdict.SAME, r.verdict()); r.f.scene = r.scene
        val result = r.f.closer(StandardTestDispatcher(testScheduler)).execute(r.f.reference)
        assertFalse(result.attempted); assertEquals(0, r.f.clicks)
    }
    @Test fun sameDisplayDoesNotRenewThirtySecondLeaseOrOriginalCaptureAge() {
        val r = Rig(); val originalAge = r.f.snapshot.capturedAtUptimeMillis
        val lease = ManualPromptLifetime { r.f.now }.also { it.shown(r.f.token, OverlayWindowState(OverlayWindowStatus.SHOWING, 1)) }
        r.f.now += 6_000; assertEquals(ManualDisplayVerdict.SAME, r.verdict()); assertEquals(r.f.token, lease.current())
        assertEquals(originalAge, r.f.snapshot.capturedAtUptimeMillis)
        r.f.now += 24_000; assertNull(lease.current())
    }
    @Test fun explicitChoiceAfterRetainedDisplayStillNeedsANewWholeF0AfterRemoval() = runTest {
        val r = Rig(); val detected = PopupDetection(r.f.token, persistentSetOf(PopupSignal.NEW_WINDOW))
        val analysis = DecisionCandidateAttacher().attach(PopupEvent.Candidate(r.f.snapshot, detected),
            PopupAnalysis(r.f.token, texts = F9CollectedSource.texts(r.f.snapshot)))
        val choice = UserChoice(r.f.token, ChoiceKind.CLOSE_ONCE, analysis.closeCandidates.single().id, buttonMeaningConfirmed = true)
        r.f.now += 6_000; assertEquals(ManualDisplayVerdict.SAME, r.verdict())
        val order = mutableListOf<String>()
        val result = ManualChoiceRefresh.afterRemoval(r.f.snapshot, analysis, choice, detected, { r.f.now }, { true },
            awaitRemoval = { order += "REMOVED" }, awaitQuiet = { order += "QUIET" }) { checkpoint ->
            checkpoint(); order += "NEW_F0"
            r.f.snapshot.copy(token = r.f.token.copy(screenVersion = 2, captureEpoch = 2), capturedAtUptimeMillis = r.f.now)
        }
        assertNotNull(result); assertEquals(listOf("REMOVED", "QUIET", "NEW_F0"), order)
        assertNotEquals(r.f.token, result!!.snapshot.token); assertEquals(0, r.f.clicks)
    }
}
