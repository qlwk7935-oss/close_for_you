package kr.closeforyou.accessibility.integration

import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.*
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.*
import kr.closeforyou.ui.voice.settings.AutoCloseConsent
import org.junit.Assert.*
import org.junit.Test

/** Actual F1 continuation + B wiring/policy + A session/F9 coordinator. Android reads/presses
 * are fake ports; synthetic success is not Samsung or LG instrumentation success. */
@OptIn(ExperimentalCoroutinesApi::class)
class DelayedAutoFlowTest {
    private class Rig(scope: TestScope, val hiddenPage: Boolean = false) {
        val f = F9AutoCloserTest.Fixture()
        val pkg = "kr.closeforyou.popuptest"
        var enabled = true
        var cleanupReady = true
        var pageDigest = "unchanged-base-page"
        var access = F9SessionAccess(true, true, true, false, false,
            AutoCloseConsent(true, persistentSetOf(PopupClassification.COUPON, PopupClassification.ADVERTISEMENT), 1), true)
        val root = f.node.copy(path = "0", viewId = "root", className = "android.widget.LinearLayout", text = null,
            clickable = false, bounds = f.bounds, actions = persistentSetOf())
        val body = root.copy(path = "0/0", viewId = "body", className = "android.widget.TextView",
            text = "적립금 쿠폰 도착", bounds = ScreenBounds(30, 30, 180, 100))
        val old = f.snapshot.copy(token = f.token.copy(packageName = pkg), nodes = persistentListOf(root, body),
            windows = if (hiddenPage) persistentListOf(f.windows[1]) else f.windows)
        var snapshot = old
        val frame get() = DetectionFrame("same-app", 20, snapshot.windows.filter { it.type == WindowType.APPLICATION }.mapTo(mutableSetOf()) { it.id }, snapshot.token.structureDigest,
            snapshot.screenBounds, emptyList(), PopupTrigger.CONTENT_CHANGED, snapshot.windows.single { it.id == 20 }.bounds)
        var enabledAtCancel: DelayedAutoPhase? = null
        val watch = DelayedPopupWatch({ f.now }) { enabled = false; enabledAtCancel = it }
        val detector = PopupCandidateDetector()
        val raw = F9CollectedSource { f.now }
        val reports = mutableListOf<HandlingReport>()
        val session = F9ServiceSession(raw, f.port, F9Clock { f.now }, { access }, { cleanupReady }, {
            reports += it
            if (it.result != null) enabled = false
        }, executionEnabled = { enabled }, dispatcher = StandardTestDispatcher(scope.testScheduler),
            executionScope = { it == pkg },
            onWaiting = { s, d, fresh, a, page -> watch.arm(s, d, frame, fresh, a, page) },
            executionGuard = watch::permitsExecution, onExecutionStart = watch::executing)
        private var event: PopupEvent.Candidate? = null
        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                session.wiring.source.events.collect { if (it is PopupEvent.Candidate) event = it }
            }
        }
        fun scene() = f.before.copy(packageName = pkg, nodes = snapshot.nodes, windows = snapshot.windows,
            visiblePaths = snapshot.nodes.mapTo(mutableSetOf()) { it.path }, pageDigest = if (hiddenPage) "popup-digest" else pageDigest,
            pageWindowId = if (hiddenPage) 20 else 10)
        fun observeNormalPage(): F9PageObservation {
            val scene = requireNotNull(f.after).copy(packageName = pkg)
            val token = old.token.copy(windowId = 10)
            val source = old.copy(token = token, nodes = scene.nodes.toPersistentList(), windows = scene.windows.toPersistentList())
            f.scene = scene
            return session.observePage(source)
        }
        suspend fun first(): F9Handled {
            f.scene = scene(); val signals = detector.detect(frame)
            raw.publish(snapshot, PopupDetection(snapshot.token, signals.toPersistentSet()))
            return session.handle(requireNotNull(event))
        }
        fun changed(add: Boolean = true) {
            f.now += 3_000
            val bounds = ScreenBounds(20, 20, 200, 240)
            snapshot = old.copy(token = old.token.copy(screenVersion = 2, captureEpoch = 2, structureDigest = "with-button", contentDigest = "new-text"),
                capturedAtUptimeMillis = f.now, windows = old.windows.map { if (it.id == 20) it.copy(bounds = bounds) else it }.toPersistentList(),
                nodes = if (add) persistentListOf(root.copy(bounds = bounds), body,
                    f.node.copy(path = "0/1", viewId = "close")) else old.nodes)
        }
        fun invalidate(cause: ManualPromptCause = ManualPromptCause.CONTENT_INVALIDATED,
            event: DelayedContentEvent? = DelayedContentEvent(20, pkg), interaction: Boolean = false) {
            watch.invalidated(2, interaction, cause, event); session.invalidate(2, interaction)
        }
        suspend fun next(): F9Handled {
            val normal = detector.detect(frame)
            val signals = if (watch.reobserved(snapshot, frame)) detector.reobserveBoundedApplicationWindow(frame) else normal
            f.scene = scene()
            f.after = f.after?.copy(packageName = pkg)
            raw.publish(snapshot, PopupDetection(snapshot.token, signals.toPersistentSet()))
            return session.handle(requireNotNull(event))
        }
    }
    @Test fun realPageAdmissionThenHiddenBackgroundWaitAndNewButtonReachOneConfirmedPress() = runTest {
        val r = Rig(this, hiddenPage = true)
        assertEquals(F9PageObservation.VERIFIED, r.observeNormalPage())
        assertEquals(NextStep.WAIT, r.first().decision?.next); assertEquals(0, r.f.clicks)
        r.invalidate(); r.changed()
        assertTrue(requireNotNull(r.next().result).closedConfirmed); assertEquals(1, r.f.clicks)
    }
    @Test fun hiddenBackgroundWithNoIndependentPageCannotEstablishExecutableWait() = runTest {
        val r = Rig(this, hiddenPage = true)
        assertEquals(NextStep.WAIT, r.first().decision?.next)
        assertFalse(r.watch.waiting); assertEquals(0, r.f.clicks)
    }
    @Test fun hiddenPageProofKeepsItsOriginalAgeEvenWhenPopupAndButtonAreFresh() = runTest {
        val r = Rig(this, hiddenPage = true)
        assertEquals(F9PageObservation.VERIFIED, r.observeNormalPage())
        r.f.now += 2_000
        assertEquals(NextStep.WAIT, r.first().decision?.next)
        r.invalidate(); r.changed()
        val result = r.next()
        assertFalse(result.result?.attempted == true); assertEquals(0, r.f.clicks)
        assertFalse(r.enabled)
    }
    @Test fun pageMismatchDoesNotBecomeAHiddenBackgroundProofOrAPress() = runTest {
        val r = Rig(this, hiddenPage = true); r.f.after = r.f.after!!.copy(complete = false)
        assertEquals(F9PageObservation.INCOMPLETE, r.observeNormalPage())
        r.first(); assertFalse(r.watch.waiting); assertEquals(0, r.f.clicks)
    }
    @Test fun realWaitThenNewCollectionAndBApprovalReachExactlyOneF9ClickAndConfirmedClosure() = runTest {
        val r = Rig(this); val waiting = r.first()
        assertEquals(NextStep.WAIT, waiting.decision?.next)
        assertTrue(SafetyReason.WAITING_FOR_TARGET in requireNotNull(waiting.decision).reasons)
        assertTrue(r.watch.waiting); assertEquals(0, r.f.clicks)
        r.invalidate(); r.changed(); val done = r.next()
        assertEquals(NextStep.EXECUTE, done.decision?.next)
        assertTrue(requireNotNull(done.result).closedConfirmed); assertEquals(1, r.f.clicks)
        assertFalse(r.enabled)
        assertEquals(ClickOutcome.CLOSED_CONFIRMED, r.reports.last().result?.outcome)
    }
    @Test fun repeatedMissingTargetReadsWaitWithoutPressOrRenewingIdentityDeadline() = runTest {
        val r = Rig(this); r.first(); val before = r.watch.remaining()
        r.invalidate(); r.changed(add = false)
        assertEquals(NextStep.WAIT, r.next().decision?.next)
        assertEquals(before - 3_000, r.watch.remaining()); assertEquals(0, r.f.clicks)
    }
    @Test fun noWaitProofCannotAuthorizeAnInitiallyPresentButtonEvenIfBAgrees() = runTest {
        val r = Rig(this); r.changed(); val result = r.first()
        assertEquals(NextStep.EXECUTE, result.decision?.next)
        assertFalse(requireNotNull(result.result).attempted); assertEquals(0, r.f.clicks)
    }
    @Test fun purchaseOrUnlabelledReplacementCannotPassContinuationOrCauseAPress() = runTest {
        for (label in listOf("지금 구매하기", "결제", "동의", "권한 허용", "확인", "X", "")) {
            val r = Rig(this); r.first(); r.invalidate(); r.changed()
            r.snapshot = r.snapshot.copy(nodes = r.snapshot.nodes.toPersistentList().set(2, r.snapshot.nodes[2].copy(text = label)))
            assertFalse(r.watch.reobserved(r.snapshot, r.frame)); assertFalse(r.enabled); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun differentBodyWindowAppDisplayOrMissingOldNodeCancelsInsteadOfTransferringWait() = runTest {
        for (mode in listOf("BODY", "WINDOW", "APP", "DISPLAY", "MISSING", "CLASS", "MULTIPLE")) {
            val r = Rig(this); r.first(); r.invalidate(); r.changed()
            val s = r.snapshot
            r.snapshot = when (mode) {
                "BODY" -> s.copy(nodes = s.nodes.toPersistentList().set(1, r.body.copy(text = "다른 팝업")))
                "WINDOW" -> s.copy(token = s.token.copy(windowId = 21))
                "APP" -> s.copy(token = s.token.copy(packageName = "synthetic.other"))
                "DISPLAY" -> s.copy(screenBounds = ScreenBounds(0, 0, 800, 400))
                "MISSING" -> s.copy(nodes = persistentListOf(r.root, s.nodes[2]))
                "CLASS" -> s.copy(nodes = s.nodes.toPersistentList().set(1, r.body.copy(className = "different")))
                else -> s.copy(nodes = s.nodes.toPersistentList().add(s.nodes[2].copy(path = "0/2")))
            }
            assertFalse(r.watch.reobserved(r.snapshot, r.frame)); assertFalse(r.enabled); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun userTouchScrollFocusPolicyAndOtherWindowEventsCancelWaiting() = runTest {
        for (cause in listOf(ManualPromptCause.TOUCH_STARTED, ManualPromptCause.SCROLL_INVALIDATED,
            ManualPromptCause.FOCUS_INVALIDATED_NO_REVIEW, ManualPromptCause.POLICY_CHANGED,
            ManualPromptCause.SERVICE_STOPPED, ManualPromptCause.WINDOW_STATE_INVALIDATED)) {
            val r = Rig(this); r.first(); r.invalidate(cause, null, true)
            assertFalse(r.watch.waiting); assertFalse(r.enabled); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun invalidContentAttributionCannotCarryTheWaitingEvidence() = runTest {
        for (e in listOf(DelayedContentEvent(-1, null), DelayedContentEvent(21, "kr.closeforyou.popuptest"),
            DelayedContentEvent(20, "synthetic.other"), DelayedContentEvent(20, null))) {
            val r = Rig(this); r.first(); r.invalidate(event = e)
            assertFalse(r.enabled); assertFalse(r.watch.waiting)
        }
    }
    @Test fun boundsOnlyEventCanRequestFreshValidationButCannotItselfAuthorizeAClick() = runTest {
        val r = Rig(this); r.first(); r.invalidate(ManualPromptCause.WINDOW_SET_INVALIDATED,
            DelayedContentEvent(20, null, boundsOnly = true))
        assertFalse(r.watch.permitsExecution(r.old)); assertNull(r.raw.currentToken()); assertEquals(0, r.f.clicks)
        r.changed(); assertTrue(requireNotNull(r.next().result).closedConfirmed)
    }
    @Test fun ttlInvalidatesOldEvidenceAndARealLaterReadIsStillRequired() = runTest {
        val r = Rig(this); r.first(); r.f.now += 5_000
        r.invalidate(ManualPromptCause.ORIGINAL_EVIDENCE_EXPIRED, null)
        assertNull(r.raw.currentToken()); assertTrue(r.watch.waiting); assertFalse(r.watch.permitsExecution(r.old))
        r.invalidate(); r.changed(); assertTrue(requireNotNull(r.next().result).attempted)
    }
    @Test fun thirtySecondIdentityExpiryDoesNotRenewEvidenceOrPress() = runTest {
        val r = Rig(this); r.first(); r.invalidate(); r.changed(); r.f.now += 30_000
        assertFalse(r.watch.reobserved(r.snapshot, r.frame)); assertEquals(DelayedAutoPhase.EXPIRED, r.enabledAtCancel)
        assertEquals(0, r.f.clicks)
    }
    @Test fun trueClickWithPopupRemainingNeverReportsConfirmedCloseOrRetries() = runTest {
        val r = Rig(this); r.first(); r.invalidate(); r.changed(); r.f.after = r.scene()
        val result = requireNotNull(r.next().result)
        assertTrue(result.attempted); assertEquals(ClickOutcome.STILL_PRESENT, result.outcome)
        assertFalse(result.closedConfirmed); assertEquals(1, r.f.clicks); assertFalse(r.enabled)
    }
    @Test fun postPressWindowRemovalInvalidatesOldTokenButStillRequiresAnObservedUnchangedPage() = runTest {
        val r = Rig(this); r.first(); r.invalidate(); r.changed()
        r.f.afterPress = { r.invalidate(ManualPromptCause.WINDOW_SET_INVALIDATED, null) }
        val result = requireNotNull(r.next().result)
        assertNull(r.raw.currentToken()); assertTrue(result.closedConfirmed); assertEquals(1, r.f.clicks)
    }
    @Test fun interactionDuringResultObservationCannotAnnounceSuccessOrRetry() = runTest {
        val r = Rig(this); r.first(); r.invalidate(); r.changed()
        r.f.afterPress = { r.invalidate(ManualPromptCause.TOUCH_STARTED, null, true) }
        val result = requireNotNull(r.next().result)
        assertTrue(result.attempted); assertEquals(ClickOutcome.UNKNOWN, result.outcome)
        assertEquals(1, r.f.clicks); assertFalse(r.enabled)
    }
    @Test fun repeatedDeliveryAfterAttemptDoesNotPressAgain() = runTest {
        val r = Rig(this); r.first(); r.invalidate(); r.changed()
        assertTrue(requireNotNull(r.next().result).attempted)
        assertNull(r.next().result); assertEquals(1, r.f.clicks)
    }
    @Test fun cleanupUncertaintyBlocksBOrF9AndCannotBecomeAnAttempt() = runTest {
        val r = Rig(this); r.first(); r.invalidate(); r.changed()
        r.f.beforePress = { r.cleanupReady = false }
        assertFalse(requireNotNull(r.next().result).attempted); assertEquals(0, r.f.clicks)
    }
    @Test fun settingsRevocationOrFinalTargetChangeAfterBApprovalNeverPresses() = runTest {
        for (mode in listOf("CONSENT", "TARGET", "TTL")) {
            val r = Rig(this); r.first(); r.invalidate(); r.changed()
            r.f.beforePress = {
                when (mode) {
                    "CONSENT" -> r.access = r.access.copy(collectionConsent = false)
                    "TTL" -> r.f.now += 5_000
                    else -> r.f.scene = r.f.scene?.copy(nodes = listOf(r.root, r.body))
                }
            }
            assertFalse(requireNotNull(r.next().result).attempted); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun delayedFirstReadIsRejectedWithoutCompleteFreshF0AndValidGeometry() = runTest {
        val r = Rig(this); val detected = PopupDetection(r.old.token, persistentSetOf(PopupSignal.NEW_WINDOW))
        val analysis = PopupAnalysis(r.old.token, classification = PopupClassification.COUPON)
        assertFalse(r.watch.arm(r.old, detected, r.frame, null, analysis))
        assertFalse(r.watch.waiting); assertEquals(0, r.f.clicks)
        r.detector.detect(r.frame)
        assertTrue(r.detector.reobserveBoundedApplicationWindow(r.frame.copy(windowBounds = r.old.screenBounds)).isEmpty())
    }
    @Test fun noAutomaticConsentOrWrongPackageScopeCannotObtainAnExecuteDecision() = runTest {
        for (mode in listOf("OFF", "CONSENT", "APP")) {
            val r = Rig(this)
            when (mode) {
                "OFF" -> r.enabled = false
                "CONSENT" -> r.access = r.access.copy(consent = AutoCloseConsent.NONE)
                else -> r.access = r.access.copy(appAllowed = false)
            }
            val result = r.first()
            assertNotEquals(NextStep.EXECUTE, result.decision?.next); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun purchasePaymentConsentPermissionAndScamBodyCannotEnterAutomaticWaitOrExecute() = runTest {
        for (text in listOf("광고 지금 구매하기", "쿠폰 결제하기", "쿠폰 동의", "광고 권한 허용", "광고 사기 의심")) {
            val r = Rig(this)
            r.snapshot = r.old.copy(nodes = persistentListOf(r.root, r.body.copy(text = text)))
            val handled = r.first()
            assertNotEquals(NextStep.EXECUTE, handled.decision?.next)
            assertFalse(r.watch.waiting); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun backgroundPageChangedWithoutMatchingContentAttributionCannotTransferAuthorization() = runTest {
        val r = Rig(this); r.first(); r.invalidate(); r.changed()
        r.pageDigest = "different-safe-page"
        assertFalse(requireNotNull(r.next().result).attempted)
        assertFalse(r.enabled); assertEquals(0, r.f.clicks)
    }
    @Test fun mismatchedFrameOrAnalysisCannotEstablishWaitingIdentity() = runTest {
        for (mode in listOf("TOKEN", "STRUCTURE", "WINDOW_SET", "APP")) {
            val r = Rig(this)
            val frame = when (mode) {
                "STRUCTURE" -> r.frame.copy(structureDigest = "other")
                "WINDOW_SET" -> r.frame.copy(applicationWindowIds = setOf(20))
                "APP" -> r.frame.copy(applicationDigest = "")
                else -> r.frame
            }
            val token = if (mode == "TOKEN") r.old.token.copy(screenVersion = 99) else r.old.token
            assertFalse(r.watch.arm(r.old, PopupDetection(r.old.token, persistentSetOf(PopupSignal.NEW_WINDOW)),
                frame, r.scene(), PopupAnalysis(token, classification = PopupClassification.COUPON)))
            assertFalse(r.watch.waiting); assertEquals(0, r.f.clicks)
        }
    }
}
