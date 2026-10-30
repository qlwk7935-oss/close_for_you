package kr.closeforyou.accessibility

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kr.closeforyou.accessibility.capture.EvidenceIssue
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.*
import org.junit.Test

/** The exact state owned by AccessibilityRuntime, with synthetic DTOs and a monotonic fake clock. */
class LiveEvidenceStateTest {
    private var time = 1_000L
    private val state = LiveEvidenceState({ time })
    private fun snapshot(version: Long = 1, created: Long = 1_000) = MockPopupFixtures.notice().snapshot.let {
        it.copy(token = it.token.copy(screenVersion = version, captureEpoch = version), capturedAtUptimeMillis = created)
    }
    private fun start(s: PopupSnapshot = snapshot()): EvidencePublicationPermit {
        state.connect(); state.invalidate(s.token.screenVersion)
        return requireNotNull(state.begin(s))
    }
    private fun detection(s: PopupSnapshot) = PopupDetection(s.token, persistentSetOf(PopupSignal.NEW_WINDOW), true)
    private fun analysis(s: PopupSnapshot) = PopupAnalysis(s.token)
    private fun publish(p: EvidencePublicationPermit, s: PopupSnapshot = snapshot()) =
        state.publish(p, s, detection(s), analysis(s), EvidenceIssue.NOT_REQUESTED)

    @Test fun completedEvidenceIsExtractableOnlyAfterPublication() {
        val s = snapshot(); val p = start(s)
        assertNull(state.extract(s)); assertTrue(publish(p, s)); assertEquals(analysis(s), state.extract(s))
    }
    @Test fun disconnectedCannotBeginOrPublish() {
        val s = snapshot(); assertNull(state.begin(s)); val p = start(s)
        state.disconnect(); assertFalse(publish(p)); assertNull(state.currentToken()); assertNull(state.extract(s))
    }
    @Test fun eventInvalidationDropsTokenAndAnalysisAndLatePublication() {
        val s = snapshot(); val p = start(s); assertTrue(publish(p))
        state.invalidate(2); assertNull(state.currentToken()); assertNull(state.extract(s)); assertFalse(publish(p))
    }
    @Test fun policyBlockDiscardsEverything() {
        val s = snapshot(); val p = start(s); publish(p)
        assertTrue(state.block(1)); assertNull(state.currentToken()); assertNull(state.extract(s)); assertFalse(publish(p))
    }
    @Test fun staleBlockDoesNotDiscardNewerSessionEvidence() {
        val p = start(); state.invalidate(2); val s = snapshot(2); val next = requireNotNull(state.begin(s))
        assertTrue(publish(next, s)); assertFalse(state.block(1)); assertFalse(publish(p)); assertEquals(s.token, state.currentToken())
    }
    @Test fun staleInvalidationCannotRewindVersionOrDiscardNewerEvidence() {
        start(); state.invalidate(3); val s = snapshot(3); val p = requireNotNull(state.begin(s)); publish(p, s)
        assertFalse(state.invalidate(2)); assertEquals(analysis(s), state.extract(s))
    }
    @Test fun connectionOwnerRejectsOldOperationEvenWithIdenticalTokenAndSnapshot() {
        val s = snapshot(); val old = start(s); state.disconnect()
        val next = start(s)
        assertFalse(publish(old, s)); assertTrue(publish(next, s))
    }
    @Test fun lateBlockFromOldConnectionCannotDiscardNewPublicationWithSameVersion() {
        val s = snapshot(); val old = start(s); val next = start(s)
        assertTrue(publish(next, s)); assertFalse(state.block(1, old)); assertEquals(analysis(s), state.extract(s))
        assertTrue(state.block(1, next)); assertNull(state.extract(s))
    }
    @Test fun replacementTokenDiscardsOldEvidenceAndPermit() {
        val old = start(); state.invalidate(2); val next = snapshot(2); val p = requireNotNull(state.begin(next))
        assertFalse(publish(old)); assertTrue(publish(p, next)); assertNull(state.extract(snapshot()))
    }
    @Test fun beginCannotExtendTtlByReusingTokenOrNewUuidInSameVersion() {
        val s = snapshot(); val p = start(s); time = 2_000
        assertNull(state.begin(s)); assertNull(state.begin(s.copy(token = s.token.copy(popupInstanceId = "synthetic-new"))))
        assertTrue(publish(p)); time = 6_000; assertNull(state.currentToken()); assertNull(state.begin(s))
    }
    @Test fun duplicatePublishIsRejectedWithoutReplacingAnalysis() {
        val p = start(); assertTrue(publish(p)); assertFalse(publish(p)); assertEquals(analysis(snapshot()), state.extract(snapshot()))
    }
    @Test fun ttlIsMeasuredFromOriginalSnapshotNotBegin() {
        val s = snapshot(created = 0); time = 4_999; val p = start(s)
        assertTrue(publish(p, s)); time = 5_000
        assertNull(state.extract(s)); assertFalse(publish(p, s)); assertNull(state.currentToken())
    }
    @Test fun exactFiveSecondsRejectsBeginAndLateCompletion() {
        val s = snapshot(); val p = start(s); time = 6_000
        assertFalse(publish(p)); assertNull(state.begin(s)); assertNull(state.extract(s))
    }
    @Test fun clockIsReadAgainImmediatelyBeforeCommit() {
        var reads = 0
        val live = LiveEvidenceState({ reads++; if (reads >= 5) 6_000L else 1_000L })
        live.connect(); live.invalidate(1); val s = snapshot(); val p = requireNotNull(live.begin(s))
        assertFalse(live.publish(p, s, detection(s), analysis(s), EvidenceIssue.NONE)); assertNull(live.extract(s))
    }
    @Test fun futureOrNegativeCaptureTimeAndOverflowReject() {
        time = 1_000; state.connect(); state.invalidate(1)
        assertNull(state.begin(snapshot(created = -1))); assertNull(state.begin(snapshot(created = 1_001)))
        time = Long.MAX_VALUE; assertNull(state.begin(snapshot(created = Long.MAX_VALUE)))
    }
    @Test fun clockRollbackRevokesToken() {
        val p = start(); time = 999; assertNull(state.currentToken()); assertFalse(publish(p))
    }
    @Test fun incompleteSnapshotNeverBegins() {
        state.connect(); state.invalidate(1)
        SnapshotCompleteness.entries.filter { it != SnapshotCompleteness.COMPLETE }.forEach {
            assertNull(state.begin(snapshot().copy(completeness = it)))
        }
    }
    @Test fun mismatchedEpochOrVersionCannotBegin() {
        state.connect(); state.invalidate(2)
        assertNull(state.begin(snapshot())); assertNull(state.begin(snapshot(2).let { it.copy(token = it.token.copy(captureEpoch = 1)) }))
    }
    @Test fun mismatchedDetectionTokenNeverPublishes() {
        val s = snapshot(); val p = start(s)
        assertFalse(state.publish(p, s, detection(snapshot(2)), analysis(s), EvidenceIssue.NONE)); assertNull(state.extract(s))
    }
    @Test fun mismatchedAnalysisTokenNeverPublishes() {
        val s = snapshot(); val p = start(s)
        assertFalse(state.publish(p, s, detection(s), analysis(snapshot(2)), EvidenceIssue.NONE))
    }
    @Test fun modifiedSnapshotWithSameTokenCannotPublishOrExtract() {
        val s = snapshot(); val p = start(s)
        val forged = s.copy(nodes = persistentListOf())
        assertFalse(publish(p, forged)); assertTrue(publish(p, s)); assertNull(state.extract(forged))
    }
    @Test fun decisionSummarySupportsExistingRedactedSnapshotOnly() {
        val s = snapshot(); val p = start(s); val redacted = s.copy(nodes = persistentListOf())
        assertTrue(state.publish(p, redacted, detection(s), analysis(s), EvidenceIssue.DECISION_SUMMARY_ONLY))
        assertNull(state.extract(s)); assertEquals(analysis(s), state.extract(redacted))
    }
    @Test fun evenSummaryCannotMutateGeometryOrToken() {
        val s = snapshot(); val p = start(s)
        assertFalse(state.publish(p, s.copy(nodes = persistentListOf(), screenBounds = ScreenBounds(0, 0, 1, 1)),
            detection(s), analysis(s), EvidenceIssue.DECISION_SUMMARY_ONLY))
    }
    @Test fun candidateMustRequireUserReview() {
        val s = snapshot(); val p = start(s)
        assertFalse(state.publish(p, s, detection(s).copy(requiresUserReview = false), analysis(s), EvidenceIssue.NONE))
    }
    @Test fun concurrentDuplicateCompletionHasOnlyOneWinner() {
        val p = start(); val successes = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(8) { Thread { if (publish(p)) successes.incrementAndGet() } }
        threads.forEach { it.start() }; threads.forEach { it.join() }; assertEquals(1, successes.get())
    }
    @Test fun outputContainsNoTokenOrScreenValue() {
        val p = start(); assertEquals("PublicationPermit(redacted)", p.toString())
        assertEquals("LiveEvidenceState(redacted)", state.toString())
    }
    @Test fun policyRevocationWithoutObserverInvalidationBlocksPublication() {
        var allowed = true
        val live = LiveEvidenceState({ time }, { allowed }); val s = snapshot()
        live.connect(); live.invalidate(1); val p = requireNotNull(live.begin(s)); allowed = false
        assertFalse(live.publish(p, s, detection(s), analysis(s), EvidenceIssue.NONE)); assertNull(live.currentToken())
    }
    @Test fun policyRevocationWithoutObserverInvalidationAlsoDropsAlreadyPublishedAnalysis() {
        var allowed = true
        val live = LiveEvidenceState({ time }, { allowed }); val s = snapshot()
        live.connect(); live.invalidate(1); val p = requireNotNull(live.begin(s))
        assertTrue(live.publish(p, s, detection(s), analysis(s), EvidenceIssue.NONE)); allowed = false
        assertNull(live.extract(s)); assertNull(live.currentToken()); assertNull(live.begin(s))
    }
    @Test fun policyIsCheckedAgainAtPublicationCommitBoundary() {
        var checks = 0
        val live = LiveEvidenceState({ time }, { ++checks < 3 }); val s = snapshot()
        live.connect(); live.invalidate(1); val p = requireNotNull(live.begin(s))
        assertFalse(live.publish(p, s, detection(s), analysis(s), EvidenceIssue.NONE)); assertNull(live.extract(s))
    }
    @Test fun cancelledOrFailedOperationDiscardsIntermediateAndPublishedReferences() {
        val p = start(); assertTrue(state.discard(p)); assertNull(state.currentToken()); assertFalse(publish(p))
        val next = start(); assertTrue(publish(next)); assertTrue(state.discard(next)); assertNull(state.extract(snapshot()))
    }
    @Test fun lateFinallyCannotDiscardAnotherOperationsPublication() {
        val old = start(); val next = start(); assertTrue(publish(next))
        assertFalse(state.discard(old)); assertEquals(analysis(snapshot()), state.extract(snapshot()))
    }
    @Test fun expiryAfterAnalysisCommitCannotEmitCandidate() {
        val p = start(); assertTrue(publish(p)); time = 6_000; var emits = 0
        assertFalse(state.deliver(p, snapshot()) { emits++ }); assertEquals(0, emits)
    }
    @Test fun deliveryIsOnceAndReentrantInvalidationDoesNotCauseASecondCandidate() {
        val p = start(); assertTrue(publish(p)); var emits = 0
        assertTrue(state.deliver(p, snapshot()) { emits++; state.invalidate(2) })
        assertFalse(state.deliver(p, snapshot()) { emits++ }); assertEquals(1, emits); assertNull(state.currentToken())
    }
    @Test fun policyLookupCostCannotCarryCandidatePastTtl() {
        var delayPolicy = false
        val live = LiveEvidenceState({ time }, { if (delayPolicy) time = 6_000; true }); val s = snapshot()
        live.connect(); live.invalidate(1); val p = requireNotNull(live.begin(s))
        assertTrue(live.publish(p, s, detection(s), analysis(s), EvidenceIssue.NONE))
        delayPolicy = true; var emits = 0
        assertFalse(live.deliver(p, s) { emits++ }); assertEquals(0, emits)
    }
    @Test fun actualWorkJobCancellationAfterCommitPreventsEventDelivery() {
        val owner = kotlinx.coroutines.Job(); val s = snapshot()
        state.connect(); state.invalidate(1); val p = requireNotNull(state.begin(s) { owner.isCancelled })
        assertTrue(publish(p, s)); owner.cancel(); var emitted = false
        assertFalse(state.deliver(p, s) { emitted = true }); assertFalse(emitted); assertNull(state.extract(s))
    }
    @Test fun successfulWorkJobCompletionKeepsPublishedEvidenceUntilOriginalTtl() {
        val owner = kotlinx.coroutines.Job(); val s = snapshot()
        state.connect(); state.invalidate(1); val p = requireNotNull(state.begin(s) { owner.isCancelled })
        assertTrue(publish(p, s)); assertTrue(state.deliver(p, s) {})
        owner.complete(); assertEquals(analysis(s), state.extract(s)); time = 6_000; assertNull(state.extract(s))
    }
}
