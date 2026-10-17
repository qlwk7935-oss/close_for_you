package kr.closeforyou.accessibility

import kr.closeforyou.accessibility.capture.EvidenceIssue
import kr.closeforyou.accessibility.capture.CaptureBufferPolicy
import kr.closeforyou.core.model.PrivacyBlockReason
import kr.closeforyou.core.model.WindowType
import kr.closeforyou.core.model.ScreenBounds
import org.junit.Assert.*
import org.junit.Test

class CaptureDiagnosticsTest {
    private val host = "synthetic.host"
    private fun trace(session: CaptureDiagnosticSession, epoch: Long, window: Int = 7) =
        CaptureTrace(session, window, epoch).also { it.root(host, window) }

    @Test fun unrelatedSystemEventCannotSupplyHostBlockOrEvidence() {
        val session = CaptureDiagnosticSession(host)
        val hostTrace = trace(session, 1)
        hostTrace.stage(CaptureStage.REGION)
        hostTrace.evidence(EvidenceIssue.REGION_UNCERTAIN)
        session.excludedEvent("android")
        CaptureTrace(session, 8, 2).also {
            it.root("android", 8)
            it.block(PrivacyBlockReason.SYSTEM_WINDOW)
            it.detail(CaptureDetail.NON_APPLICATION_FOCUS, WindowType.SYSTEM)
        }
        assertEquals(1, session.snapshot()!!.attempt)
        assertNull(session.snapshot()!!.block)
        assertEquals(EvidenceIssue.REGION_UNCERTAIN, session.snapshot()!!.evidence)
        assertEquals(1, session.ignoredEvents())
    }

    @Test fun newHostAttemptResetsBothCodesAndRejectsLatePreviousCallback() {
        val session = CaptureDiagnosticSession(host)
        val first = trace(session, 1)
        first.block(PrivacyBlockReason.SYSTEM_WINDOW)
        first.evidence(EvidenceIssue.PRIVACY_BLOCKED)
        val second = trace(session, 2)
        second.stage(CaptureStage.REGION)
        second.evidence(EvidenceIssue.REGION_UNCERTAIN)
        first.detail(CaptureDetail.BUFFER_GEOMETRY)
        first.stage(CaptureStage.CANCELLED)
        assertNull(session.snapshot()!!.block)
        assertEquals(CaptureDetail.NONE, session.snapshot()!!.detail)
        assertEquals(CaptureStage.REGION, session.snapshot()!!.stage)
        assertEquals(2, session.snapshot()!!.attempt)
    }

    @Test fun sameAttemptPreflightDoesNotRebindToAnotherRoot() {
        val session = CaptureDiagnosticSession(host)
        val trace = trace(session, 11)
        trace.stage(CaptureStage.F0_A)
        trace.root("foreign.app", 19)
        trace.detail(CaptureDetail.PACKAGE_GATE)
        trace.block(PrivacyBlockReason.APP_NOT_ALLOWED)
        assertEquals(1, session.snapshot()!!.attempt)
        assertEquals(PrivacyBlockReason.APP_NOT_ALLOWED, session.snapshot()!!.block)
        assertEquals(CaptureStage.F0_A, session.snapshot()!!.stage)
    }

    @Test fun cancellationAndEndDiscardLateCallbacks() {
        val session = CaptureDiagnosticSession(host)
        val trace = trace(session, 1)
        trace.stage(CaptureStage.CAPTURE_API)
        trace.stage(CaptureStage.CANCELLED)
        trace.detail(CaptureDetail.BUFFER_GEOMETRY)
        trace.stage(CaptureStage.OCR)
        assertEquals(CaptureStage.CANCELLED, session.snapshot()!!.stage)
        assertEquals(CaptureDetail.NONE, session.snapshot()!!.detail)
        session.close()
        trace.evidence(EvidenceIssue.NONE)
        trace(session, 2)
        assertNull(session.snapshot())
    }

    @Test fun matchingPublicationRequiresSameEpochWindowAndF0bPass() {
        val session = CaptureDiagnosticSession(host)
        val trace = trace(session, 11)
        trace.evidence(EvidenceIssue.NONE)
        assertNull(session.completed(11, 7))
        trace.stage(CaptureStage.F0_B_PASSED)
        assertNull(session.completed(11, 7)) // A fake frame alone is insufficient.
        trace.api(CaptureApi.WINDOW)
        trace.stage(CaptureStage.CAPTURE_API)
        assertNotNull(session.completed(11, 7))
        assertNull(session.completed(10, 7))
        assertNull(session.completed(11, 8))
        trace.evidence(EvidenceIssue.REGION_UNCERTAIN)
        assertNull(session.completed(11, 7))
    }

    @Test fun coordinateDiagnosticsRetainOnlyRelationsAndResetWithTheAttempt() {
        val session = CaptureDiagnosticSession(host)
        val trace = trace(session, 1)
        val frame = ScreenBounds(0, 0, 1080, 2400)
        val window = ScreenBounds(40, 900, 1040, 1320)
        trace.frameStatus(ScreenFrameStatus.VERIFIED)
        trace.coordinates(window, frame, window) { ScreenBounds(0, 0, 0, 0) }
        trace.syntheticWindow { "합성 이미지 창" }
        trace.nodeClasses(false)
        val diagnostic = session.snapshot()!!
        assertEquals(FrameRelation.EMPTY, diagnostic.legacyFrame)
        assertEquals(setOf(OutsideEdge.RIGHT, OutsideEdge.BOTTOM), diagnostic.legacyOutside)
        assertTrue(diagnostic.windowOutside.isEmpty())
        assertEquals(true, diagnostic.rootWithinWindow)
        assertEquals(false, diagnostic.dialogClassPresent)
        assertEquals(SyntheticWindowRole.SYNTHETIC_DIALOG, diagnostic.syntheticRole)
        assertFalse(diagnostic.toString().contains("1080"))
        assertFalse(diagnostic.toString().contains("합성 이미지 창"))
        trace(session, 2)
        assertEquals(FrameRelation.UNKNOWN, session.snapshot()!!.legacyFrame)
        assertEquals(SyntheticWindowRole.UNKNOWN, session.snapshot()!!.syntheticRole)
    }

    @Test fun bufferRelationsResetPerAttemptAndReleasedRequiresCurrentTrace() {
        val session = CaptureDiagnosticSession(host)
        val first = trace(session, 1)
        val window = ScreenBounds(40, 900, 1040, 1320)
        val diagnostic = CaptureBufferPolicy.assess(1080, 2400, 1, true,
            window, ScreenBounds(0, 0, 1080, 2400), window, true).diagnostic
        first.buffer(diagnostic)
        assertEquals(false, session.snapshot()!!.buffer!!.released)
        first.bufferReleased()
        assertEquals(true, session.snapshot()!!.buffer!!.released)
        val second = trace(session, 2)
        assertNull(session.snapshot()!!.buffer)
        first.buffer(diagnostic)
        first.bufferReleased()
        assertNull(session.snapshot()!!.buffer)
        second.stage(CaptureStage.CANCELLED)
        second.buffer(diagnostic)
        second.bufferReleased()
        assertNull(session.snapshot()!!.buffer)
    }

    @Test fun eventRootWindowRelationIsCodeOnly() {
        val session = CaptureDiagnosticSession(host)
        CaptureTrace(session, 6, 1).root(host, 7)
        assertEquals(EventWindowRelation.DIFFERENT, session.snapshot()!!.eventWindow)
        assertFalse(session.snapshot().toString().contains(host))
        CaptureTrace(session, -1, 2).root(host, 7)
        assertEquals(EventWindowRelation.UNKNOWN, session.snapshot()!!.eventWindow)
    }
}
