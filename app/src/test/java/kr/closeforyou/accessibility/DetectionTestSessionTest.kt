package kr.closeforyou.accessibility

import kr.closeforyou.accessibility.testing.DetectionTestSession
import kr.closeforyou.core.model.PrivacyBlockReason
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.PopupTrigger
import org.junit.Assert.*
import org.junit.Test

class DetectionTestSessionTest {
    private val host = DetectionTestSession.HOST
    private fun candidate() = DetectionAssessment(DetectionDisposition.POPUP_CANDIDATE, DetectionReason.BOUNDED_WINDOW)
    @Test fun `unrelated app or another window cannot become a target result`() {
        val session = DetectionTestSession({ 0L }, { true })
        session.assessed("other.fixed.app", 1, candidate())
        session.assessed(host, 2, candidate())
        assertEquals("UNOBSERVED", session.report(1)?.actual)
        assertEquals("DETECTED", session.report(2)?.actual)
    }
    @Test fun `raw events alone do not prove a successful tree assessment`() {
        val session = DetectionTestSession({ 0L }, { true })
        session.event(host, 1, DetectionInput.WINDOW)
        session.event(host, 1, DetectionInput.CONTENT)
        assertEquals("UNOBSERVED", session.report(1)?.actual)
    }
    @Test fun `scroll and toast exclusion are observed separately from detection`() {
        val session = DetectionTestSession({ 0L }, { true })
        session.event(host, 1, DetectionInput.SCROLL)
        session.event(host, -1, DetectionInput.NOTIFICATION)
        val report = requireNotNull(session.report(1))
        assertEquals("NO_POPUP", report.actual)
        assertEquals(1, report.scrolls)
        assertEquals(1, report.toasts)
        assertEquals(0, report.candidates)
    }
    @Test fun `blocked and candidate in same owned window are a conflict`() {
        val session = DetectionTestSession({ 0L }, { true })
        session.assessed(host, 1, candidate())
        session.blocked(host, 1, PrivacyBlockReason.PASSWORD)
        assertEquals("CONFLICT", session.report(1)?.actual)
    }
    @Test fun `block from another window cannot mask candidate or negative`() {
        val session = DetectionTestSession({ 0L }, { true })
        session.blocked(host, 2, PrivacyBlockReason.SYSTEM_WINDOW)
        session.assessed(host, 1, candidate())
        assertEquals("DETECTED", session.report(1)?.actual)
        assertTrue(requireNotNull(session.report(1)).blockReasons.isEmpty())
    }
    @Test fun `uncertainty is not erased by a later unchanged frame`() {
        val session = DetectionTestSession({ 0L }, { true })
        session.assessed(host, 1, DetectionAssessment(DetectionDisposition.UNCERTAIN, DetectionReason.UNCONFIRMED_TRANSITION))
        session.assessed(host, 1, DetectionAssessment(DetectionDisposition.NO_POPUP, DetectionReason.UNCHANGED))
        assertEquals("UNCERTAIN", session.report(1)?.actual)
    }
    @Test fun `revoked settings discard all observations`() {
        var allowed = true
        val session = DetectionTestSession({ 0L }, { allowed })
        session.assessed(host, 1, candidate())
        allowed = false
        assertNull(session.report(1))
        allowed = true
        assertNull(session.report(1))
    }
    @Test fun `expiry and close cannot be reset into a valid session`() {
        var clock = 0L
        val session = DetectionTestSession({ clock }, { true })
        session.assessed(host, 1, candidate())
        clock = 45_000
        assertNull(session.report(1))
        session.reset()
        assertNull(session.report(1))
    }
    @Test fun `overflow fails observation instead of silently dropping an error`() {
        val session = DetectionTestSession({ 0L }, { true })
        repeat(257) { session.event(host, 1, DetectionInput.CONTENT) }
        assertTrue(requireNotNull(session.report(1)).overflow)
        assertEquals("UNOBSERVED", session.report(1)?.actual)
    }
    @Test fun `observation reset isolates the next phase without new detector settings`() {
        val session = DetectionTestSession({ 0L }, { true })
        session.assessed(host, 1, candidate())
        session.reset()
        assertEquals("UNOBSERVED", session.report(1)?.actual)
        assertTrue(session.valid())
    }
    @Test fun `policy or lifecycle reset closes an installed debug observer`() {
        val session = DetectionTestSession({ 0L }, { true })
        session.assessed(host, 1, candidate())
        DetectionObservationHook.session = session
        try {
            DetectionObservationHook.policyChanged()
            assertNull(DetectionObservationHook.session)
            assertNull(session.report(1))
        } finally { DetectionObservationHook.policyChanged() }
    }
    @Test fun `each failure preserves its own phase field scope and reason tuple`() {
        val session = DetectionTestSession({ 0L }, { true })
        val target = CollectionProtectionDiagnostic(CollectionPhase.PROTECTION_PASS, CaptureDetail.TREE_PROTECTION,
            CollectionTree.TARGET, CollectionField.INPUT_TYPE, CollectionFinding.NON_ZERO_INPUT_TYPE)
        val background = target.copy(tree = CollectionTree.SAME_APP_BACKGROUND, field = CollectionField.TEXT,
            finding = CollectionFinding.SENSITIVE_TEXT)
        session.blocked(host, 1, PrivacyBlockReason.EDITABLE_FIELD, target)
        session.blocked(host, 1, PrivacyBlockReason.SENSITIVE_CONTENT, background)
        session.blocked(host, 2, PrivacyBlockReason.PASSWORD, target)
        assertEquals(listOf("1:BLOCKED:EDITABLE_FIELD:${target.encoded()}", "2:BLOCKED:SENSITIVE_CONTENT:${background.encoded()}"),
            session.report(1)?.attempts)
        assertEquals("BLOCKED", session.report(1)?.actual)
        session.reset()
        assertTrue(requireNotNull(session.report(1)).attempts.isEmpty())
    }
    @Test fun `geometry assessment and collection exception remain different attempts`() {
        val session = DetectionTestSession({ 0L }, { true })
        val first = session.beginAttempt()
        session.assessed(host, 1, DetectionAssessment(DetectionDisposition.UNCERTAIN, DetectionReason.INVALID_GEOMETRY), first)
        val second = session.beginAttempt()
        val diagnostic = CollectionProtectionDiagnostic(CollectionPhase.SNAPSHOT_PASS, CaptureDetail.COLLECTION_FAILED,
            CollectionTree.TARGET, finding = CollectionFinding.COLLECTION_EXCEPTION,
            operation = CollectionOperation.NODE_ACTION_READ, exception = CollectionExceptionKind.ILLEGAL_STATE)
        session.blocked(host, 1, PrivacyBlockReason.UNREADABLE_TREE, diagnostic, second)
        val attempts = requireNotNull(session.report(1)).attempts
        assertEquals(listOf("1:ASSESSED:UNCERTAIN:INVALID_GEOMETRY:UNOBSERVED:UNOBSERVED", "2:BLOCKED:UNREADABLE_TREE:${diagnostic.encoded()}"), attempts)
        assertFalse(attempts[0].contains("COLLECTION_FAILED"))
        assertFalse(attempts[1].contains("INVALID_GEOMETRY"))
    }
    @Test fun `reset rejects an in flight collection and does not reuse its ordinal`() {
        val session = DetectionTestSession({ 0L }, { true })
        val old = session.beginAttempt()
        session.reset()
        session.assessed(host, 1, candidate(), old)
        assertEquals("UNOBSERVED", session.report(1)?.actual)
        session.assessed(host, 1, candidate())
        assertEquals(listOf("2:ASSESSED:POPUP_CANDIDATE:BOUNDED_WINDOW:UNOBSERVED:UNOBSERVED"), session.report(1)?.attempts)
    }
    @Test fun `ticket cannot produce two results or enter a different session`() {
        val first = DetectionTestSession({ 0L }, { true })
        val second = DetectionTestSession({ 0L }, { true })
        val ticket = first.beginAttempt()
        second.assessed(host, 1, candidate(), ticket)
        first.assessed(host, 1, candidate(), ticket)
        first.blocked(host, 1, PrivacyBlockReason.PASSWORD, attempt = ticket)
        assertEquals("UNOBSERVED", second.report(1)?.actual)
        assertEquals("DETECTED", first.report(1)?.actual)
        assertEquals(1, first.report(1)?.attempts?.size)
        first.close()
        first.assessed(host, 1, candidate(), first.beginAttempt())
        assertNull(first.report(1))
    }
    @Test fun `attempt cap is bounded even when results were discarded across resets`() {
        val session = DetectionTestSession({ 0L }, { true })
        repeat(256) { assertNotNull(session.beginAttempt()); session.reset() }
        assertNull(session.beginAttempt())
        assertEquals("UNOBSERVED", session.report(1)?.actual)
        assertTrue(requireNotNull(session.report(1)).overflow)
    }
    @Test fun `node rejection and assessed frame relations cannot cross attempt boundaries`() {
        val session = DetectionTestSession({ 0L }, { true })
        val frame = ScreenBounds(0, 0, 100, 200)
        val geometry = DetectionGeometryCheck.inspect(frame, ScreenBounds(-1, 0, 100, 200))
        session.assessed(host, 1, DetectionAssessment(DetectionDisposition.UNCERTAIN, DetectionReason.INVALID_GEOMETRY,
            geometry = geometry, windowAge = WindowBoundsAge.OLDER))
        val bounds = NodeBoundsDiagnostic(NodeRectShape.REVERSED_Y, NodeVisibility.NOT_VISIBLE, NodeAncestry.SCROLL_ANCESTOR, NodeRole.DESCENDANT)
        val diagnostic = CollectionProtectionDiagnostic(CollectionPhase.SNAPSHOT_PASS, CaptureDetail.TREE_PROTECTION, CollectionTree.TARGET,
            finding = CollectionFinding.MALFORMED_NODE_BOUNDS, operation = CollectionOperation.NODE_BOUNDS_VALIDATE,
            nodeBounds = bounds, windowAge = WindowBoundsAge.WITHIN_ONE_SECOND, trigger = PopupTrigger.CONTENT_CHANGED)
        session.blocked(host, 1, PrivacyBlockReason.UNREADABLE_TREE, diagnostic)
        val rows = requireNotNull(session.report(1)).attempts
        assertTrue(rows[0].contains("WINDOW_OUTSIDE_FRAME"))
        assertFalse(rows[0].contains("REVERSED_Y"))
        assertTrue(rows[1].contains("REVERSED_Y:NOT_VISIBLE:SCROLL_ANCESTOR:DESCENDANT"))
        assertFalse(rows[1].contains("WINDOW_OUTSIDE_FRAME"))
        assertEquals("BLOCKED", session.report(1)?.actual)
    }
}
