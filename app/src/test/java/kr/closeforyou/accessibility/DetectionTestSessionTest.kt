package kr.closeforyou.accessibility

import kr.closeforyou.accessibility.testing.DetectionTestSession
import kr.closeforyou.core.model.PrivacyBlockReason
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
        assertEquals(setOf("EDITABLE_FIELD:${target.encoded()}", "SENSITIVE_CONTENT:${background.encoded()}"),
            session.report(1)?.blockDiagnostics)
        assertEquals("BLOCKED", session.report(1)?.actual)
        session.reset()
        assertTrue(requireNotNull(session.report(1)).blockDiagnostics.isEmpty())
    }
}
