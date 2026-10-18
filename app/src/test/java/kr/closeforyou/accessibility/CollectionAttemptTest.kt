package kr.closeforyou.accessibility

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.*
import org.junit.Test

/** Contract-only scripted observations. No Android F0, runtime token, clock, session or TTL test. */
class CollectionAttemptTest {
    private fun attempt() = CollectionAttempt.syntheticReview()
    private fun key(id: Int = 7, role: EvidenceTreeRole = EvidenceTreeRole.TARGET) = ProtectionRootKey(id, role)
    private fun observation(id: Int = 7, role: EvidenceTreeRole = EvidenceTreeRole.TARGET) =
        ProtectionRootEvidence(id, role, ProtectionCompletion.COMPLETED_WITHOUT_BLOCK, TreeCompletion.COMPLETE)
    private fun protected(a: CollectionAttempt = attempt()): CollectionAttempt {
        assertTrue(a.fixSyntheticScope(listOf(key())))
        val receipt = a.reviewSyntheticRoot(observation())!!
        assertTrue(a.finishSyntheticProtection(listOf(receipt)))
        return a
    }
    private fun recorded(a: CollectionAttempt = protected()): CollectionAttempt {
        assertTrue(a.recordSyntheticTarget(ProjectionFixture.evidence().target, ProjectionFixture.metadata()))
        return a
    }

    @Test fun happyPathHasOnlyForwardSyntheticStatesAndNoRuntimeAuthority() {
        val a = attempt()
        assertEquals(AttemptState.STARTED, a.state)
        assertTrue(a.fixSyntheticScope(listOf(key())))
        assertEquals(AttemptState.SCOPE_FIXED, a.state)
        val r = a.reviewSyntheticRoot(observation())!!
        assertEquals(AttemptState.SCOPE_FIXED, a.state)
        assertTrue(a.finishSyntheticProtection(listOf(r)))
        assertEquals(AttemptState.PROTECTION_REVIEWED, a.state)
        recorded(a)
        assertEquals(AttemptState.TARGET_RECORDED, a.state)
        val c = a.finishSyntheticReview()!!
        assertEquals(AttemptState.FINAL_REVIEWED, a.state)
        assertFalse(a.productionReady)
        assertEquals(SyntheticConsumption.REVIEW_CONSUMED, a.consumeSyntheticReview(c))
        assertEquals(AttemptState.CONSUMED, a.state)
        assertFalse(a.productionReady)
        assertNull(a.firstFailure)
    }
    @Test fun rootReviewBeforeScopeDiscards() {
        val a = attempt()
        assertNull(a.reviewSyntheticRoot(observation()))
        rejected(a, AttemptFailure.INVALID_ORDER)
    }
    @Test fun finishProtectionBeforeScopeDiscards() {
        val a = attempt()
        assertFalse(a.finishSyntheticProtection(emptyList()))
        rejected(a, AttemptFailure.INVALID_ORDER)
    }
    @Test fun targetBeforeProtectionDiscards() {
        val a = attempt()
        assertFalse(a.recordSyntheticTarget(ProjectionFixture.evidence().target, ProjectionFixture.metadata()))
        rejected(a, AttemptFailure.INVALID_ORDER)
    }
    @Test fun finalReviewWithoutTargetDiscards() {
        val a = protected()
        assertNull(a.finishSyntheticReview())
        rejected(a, AttemptFailure.INVALID_ORDER)
    }
    @Test fun scopeCannotBeReplaced() {
        val a = attempt()
        assertTrue(a.fixSyntheticScope(listOf(key())))
        assertFalse(a.fixSyntheticScope(listOf(key(9))))
        rejected(a, AttemptFailure.INVALID_ORDER)
    }
    @Test fun emptyScopeDenies() {
        val a = attempt(); assertFalse(a.fixSyntheticScope(emptyList())); rejected(a, AttemptFailure.SCOPE_INVALID)
    }
    @Test fun duplicateWindowEvenWithDifferentRolesDenies() {
        val a = attempt()
        assertFalse(a.fixSyntheticScope(listOf(key(), key(role = EvidenceTreeRole.SAME_APP_BACKGROUND))))
        rejected(a, AttemptFailure.SCOPE_INVALID)
    }
    @Test fun missingOrMultipleTargetsDeny() {
        for (scope in listOf(listOf(key(role = EvidenceTreeRole.SAME_APP_BACKGROUND)), listOf(key(), key(8)))) {
            val a = attempt(); assertFalse(a.fixSyntheticScope(scope)); rejected(a, AttemptFailure.SCOPE_INVALID)
        }
    }
    @Test fun scopeOver32Denies() {
        val a = attempt()
        assertFalse(a.fixSyntheticScope((0..32).map { key(it, if (it == 7) EvidenceTreeRole.TARGET else EvidenceTreeRole.SAME_APP_BACKGROUND) }))
        rejected(a, AttemptFailure.SCOPE_INVALID)
    }
    @Test fun mutableInputRosterCannotChangeFixedScope() {
        val input = mutableListOf(key())
        val a = attempt(); assertTrue(a.fixSyntheticScope(input)); input[0] = key(8)
        assertNotNull(a.reviewSyntheticRoot(observation()))
    }
    @Test fun unknownRootAndChangedRoleDeny() {
        for (o in listOf(observation(8), observation(role = EvidenceTreeRole.SAME_APP_BACKGROUND))) {
            val a = attempt(); a.fixSyntheticScope(listOf(key()))
            assertNull(a.reviewSyntheticRoot(o)); rejected(a, AttemptFailure.SCOPE_CHANGED)
        }
    }
    @Test fun duplicateReceiptIssuanceDenies() {
        val a = attempt(); a.fixSyntheticScope(listOf(key())); assertNotNull(a.reviewSyntheticRoot(observation()))
        assertNull(a.reviewSyntheticRoot(observation())); rejected(a, AttemptFailure.DUPLICATE_ROOT)
    }
    @Test fun everyNonCompleteProtectionObservationDenies() {
        for (value in ProtectionCompletion.entries.filter { it != ProtectionCompletion.COMPLETED_WITHOUT_BLOCK }) {
            val a = attempt(); a.fixSyntheticScope(listOf(key()))
            assertNull(a.reviewSyntheticRoot(observation().copy(protection = value)))
            rejected(a, AttemptFailure.PROTECTION_INCOMPLETE)
        }
    }
    @Test fun everyIncompleteTraversalObservationDenies() {
        for (value in TreeCompletion.entries.filter { it != TreeCompletion.COMPLETE }) {
            val a = attempt(); a.fixSyntheticScope(listOf(key()))
            assertNull(a.reviewSyntheticRoot(observation().copy(traversal = value)))
            rejected(a, AttemptFailure.PROTECTION_INCOMPLETE)
        }
    }
    @Test fun missingBackgroundReceiptDeniesEvenWithCompleteTarget() {
        val a = attempt(); a.fixSyntheticScope(listOf(key(), key(8, EvidenceTreeRole.SAME_APP_BACKGROUND)))
        val r = a.reviewSyntheticRoot(observation())!!
        assertFalse(a.finishSyntheticProtection(listOf(r))); rejected(a, AttemptFailure.SCOPE_INVALID)
    }
    @Test fun issuedButOmittedReceiptDenies() {
        val a = attempt(); a.fixSyntheticScope(listOf(key())); a.reviewSyntheticRoot(observation())
        assertFalse(a.finishSyntheticProtection(emptyList())); rejected(a, AttemptFailure.SCOPE_INVALID)
    }
    @Test fun duplicateReceiptCannotReplaceMissingBackground() {
        val a = attempt(); a.fixSyntheticScope(listOf(key(), key(8, EvidenceTreeRole.SAME_APP_BACKGROUND)))
        val r = a.reviewSyntheticRoot(observation())!!
        a.reviewSyntheticRoot(observation(8, EvidenceTreeRole.SAME_APP_BACKGROUND))
        assertFalse(a.finishSyntheticProtection(listOf(r, r))); rejected(a, AttemptFailure.SCOPE_INVALID)
    }
    @Test fun receiptFromIdenticalOtherAttemptDenies() {
        val a = attempt(); val b = attempt()
        a.fixSyntheticScope(listOf(key())); b.fixSyntheticScope(listOf(key()))
        a.reviewSyntheticRoot(observation())
        val foreign = b.reviewSyntheticRoot(observation())!!
        assertFalse(a.finishSyntheticProtection(listOf(foreign))); rejected(a, AttemptFailure.MIXED_ATTEMPT)
    }
    @Test fun backgroundProtectionDoesNotRequireGeometryOrOutlineData() {
        val a = attempt(); a.fixSyntheticScope(listOf(key(), key(8, EvidenceTreeRole.SAME_APP_BACKGROUND)))
        val targetReceipt = a.reviewSyntheticRoot(observation())!!
        val backgroundReceipt = a.reviewSyntheticRoot(observation(8, EvidenceTreeRole.SAME_APP_BACKGROUND))!!
        assertTrue(a.finishSyntheticProtection(listOf(backgroundReceipt, targetReceipt)))
        val m = ProjectionFixture.metadata()
        assertTrue(a.recordSyntheticTarget(ProjectionFixture.evidence().target, m.copy(windows = persistentListOf(
            m.windows[0], m.windows[0].copy(id = 8, active = false)))))
        assertNotNull(a.finishSyntheticReview())
    }
    @Test fun completeBackgroundCannotOverrideMalformedTarget() {
        val a = protected()
        val t = ProjectionFixture.evidence().target
        assertFalse(a.recordSyntheticTarget(t.copy(nodes = persistentListOf(t.nodes[0].copy(
            rect = CollectedRect(0, 2, 1, 0)))), ProjectionFixture.metadata()))
        rejected(a, AttemptFailure.PROJECTION_REJECTED)
        assertEquals(ProjectionRejection.GEOMETRY_NOT_COMPLETE, a.firstProjectionFailure)
    }
    @Test fun incompleteOrWrongTargetCannotBeRecorded() {
        val t = ProjectionFixture.evidence().target
        for (bad in listOf(t.copy(windowId = 8), t.copy(role = EvidenceTreeRole.SAME_APP_BACKGROUND),
            t.copy(traversal = TreeCompletion.TIME_LIMIT), t.copy(nodes = persistentListOf()))) {
            val a = protected(); assertFalse(a.recordSyntheticTarget(bad, ProjectionFixture.metadata()))
            rejected(a, AttemptFailure.TARGET_INCOMPLETE)
        }
    }
    @Test fun hiddenTargetNodeProtectionFailureStillDenies() {
        val a = protected(); val t = ProjectionFixture.evidence().target
        assertFalse(a.recordSyntheticTarget(t.copy(nodes = persistentListOf(t.nodes[0].copy(
            visible = false, protection = ProtectionCompletion.BLOCKED))), ProjectionFixture.metadata()))
        assertEquals(ProjectionRejection.PROTECTION_NOT_COMPLETE, a.firstProjectionFailure)
    }
    @Test fun consumedCompletionCannotBeReused() {
        val a = recorded(); val c = a.finishSyntheticReview()!!
        assertEquals(SyntheticConsumption.REVIEW_CONSUMED, a.consumeSyntheticReview(c))
        assertEquals(SyntheticConsumption.REJECTED, a.consumeSyntheticReview(c))
        assertEquals(AttemptState.CONSUMED, a.state)
    }
    @Test fun completionFromIdenticalOtherAttemptDenies() {
        val a = recorded(); val b = recorded(); a.finishSyntheticReview()
        assertEquals(SyntheticConsumption.REJECTED, a.consumeSyntheticReview(b.finishSyntheticReview()!!))
        rejected(a, AttemptFailure.MIXED_ATTEMPT)
    }
    @Test fun cancellationAtEveryUnconsumedPhaseIsTerminal() = terminalAtEveryPhase { it.cancel() }
    @Test fun invalidationAtEveryUnconsumedPhaseIsTerminal() = terminalAtEveryPhase { it.invalidate() }
    @Test fun discardAtEveryUnconsumedPhaseIsTerminal() = terminalAtEveryPhase { it.discard() }
    @Test fun firstFailureAndProjectionReasonAreNeverOverwritten() {
        val a = protected(); val t = ProjectionFixture.evidence().target
        a.recordSyntheticTarget(t.copy(nodes = persistentListOf(t.nodes[0].copy(rect = null))), ProjectionFixture.metadata())
        a.cancel(); a.invalidate(); a.discard(); a.finishSyntheticReview()
        rejected(a, AttemptFailure.PROJECTION_REJECTED)
        assertEquals(ProjectionRejection.GEOMETRY_NOT_COMPLETE, a.firstProjectionFailure)
    }
    @Test fun simultaneousConsumptionSucceedsAtMostOnce() {
        val a = recorded(); val c = a.finishSyntheticReview()!!
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val calls = (0..1).map { pool.submit<SyntheticConsumption> { start.await(); a.consumeSyntheticReview(c) } }
            start.countDown()
            val results = calls.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == SyntheticConsumption.REVIEW_CONSUMED })
            assertEquals(1, results.count { it == SyntheticConsumption.REJECTED })
        } finally { pool.shutdownNow() }
    }
    @Test fun privateReceiptAndCompletionHaveNoCopyOrDataPayloadAccessors() {
        val a = attempt(); a.fixSyntheticScope(listOf(key()))
        val r = a.reviewSyntheticRoot(observation())!!; a.finishSyntheticProtection(listOf(r))
        recorded(a); val c = a.finishSyntheticReview()!!
        for (handle in listOf(r, c)) {
            assertTrue(Modifier.isPrivate(handle.javaClass.modifiers))
            assertFalse(handle.javaClass.declaredMethods.any { it.name == "copy" || it.name.startsWith("component") })
        }
        assertTrue(SyntheticReviewCompletion::class.java.declaredMethods.isEmpty())
        assertTrue(SyntheticProtectionReceipt::class.java.declaredMethods.isEmpty())
    }
    @Test fun cancelledInputReadRethrowsWithoutCompletingScope() {
        val a = attempt()
        val cancelled = java.util.concurrent.CancellationException("SYNTHETIC_PRIVATE")
        val input = object : AbstractList<ProtectionRootKey>() {
            override val size = 1
            override fun get(index: Int): ProtectionRootKey = throw cancelled
        }
        val failure = assertThrows(java.util.concurrent.CancellationException::class.java) {
            a.fixSyntheticScope(input)
        }
        assertNotSame(cancelled, failure)
        assertEquals("COLLECTION_REVIEW_CANCELLED", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.toString().contains("SYNTHETIC_PRIVATE"))
        rejected(a, AttemptFailure.CANCELLED)
        assertNull(a.finishSyntheticReview())
    }
    @Test fun reentrantInvalidationDuringInputReadCannotResurrectAttempt() {
        val a = attempt()
        val input = object : AbstractList<ProtectionRootKey>() {
            override val size = 1
            override fun get(index: Int): ProtectionRootKey { a.invalidate(); return key() }
        }
        assertFalse(a.fixSyntheticScope(input)); rejected(a, AttemptFailure.INVALIDATED)
    }
    @Test fun inputReadFailureIsTerminalAndDoesNotRetainMessage() {
        val a = attempt()
        val input = object : AbstractList<ProtectionRootKey>() {
            override val size = 1
            override fun get(index: Int): ProtectionRootKey = error("SYNTHETIC_PRIVATE")
        }
        assertFalse(a.fixSyntheticScope(input)); rejected(a, AttemptFailure.REVIEW_FAILED)
        assertFalse(a.firstFailure.toString().contains("SYNTHETIC_PRIVATE"))
    }
    @Test fun scopeCardinalityChangeDuringCopyDenies() {
        val a = attempt(); var reads = 0
        val input = object : AbstractList<ProtectionRootKey>() {
            override val size get() = if (reads == 0) 1 else 2
            override fun get(index: Int): ProtectionRootKey { reads++; return key() }
        }
        assertFalse(a.fixSyntheticScope(input)); rejected(a, AttemptFailure.SCOPE_CHANGED)
    }
    @Test fun largeRosterIsRejectedWithoutReadingEntries() {
        val a = attempt()
        val input = object : AbstractList<ProtectionRootKey>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): ProtectionRootKey = error("must not read")
        }
        assertFalse(a.fixSyntheticScope(input)); rejected(a, AttemptFailure.SCOPE_INVALID)
    }
    @Test fun receiptListAboveLimitIsRejected() {
        val a = attempt(); a.fixSyntheticScope(listOf(key())); val r = a.reviewSyntheticRoot(observation())!!
        assertFalse(a.finishSyntheticProtection(List(33) { r })); rejected(a, AttemptFailure.SCOPE_INVALID)
    }
    @Test fun consumptionDropsOwnerPayloadReferences() {
        val a = recorded(); a.consumeSyntheticReview(a.finishSyntheticReview()!!)
        assertCleared(a)
    }
    @Test fun invalidationDropsOwnerPayloadReferences() {
        val a = recorded(); a.finishSyntheticReview(); a.invalidate()
        assertCleared(a)
    }
    @Test fun sensitiveValuesNeverAppearInOwnerHandlesDiagnosticsOrOutput() {
        val output = ByteArrayOutputStream(); val stream = PrintStream(output)
        val oldOut = System.out; val oldErr = System.err
        try {
            System.setOut(stream); System.setErr(stream)
            val a = attempt(); a.fixSyntheticScope(listOf(key()))
            val r = a.reviewSyntheticRoot(observation())!!; a.finishSyntheticProtection(listOf(r))
            val t = ProjectionFixture.evidence().target
            val m = ProjectionFixture.metadata()
            assertTrue(a.recordSyntheticTarget(t.copy(nodes = persistentListOf(t.nodes[0].copy(
                text = "PRIVATE_BODY", outlineFingerprint = "PRIVATE_OUTLINE"))),
                m.copy(token = m.token.copy(popupInstanceId = "PRIVATE_TOKEN", contentDigest = "PRIVATE_DIGEST"))))
            val c = a.finishSyntheticReview()!!
            val text = listOf(a, r, c, key(), a.state, a.firstFailure).joinToString()
            assertFalse(text.contains("PRIVATE_")); assertFalse(text.contains("0/0"))
            a.consumeSyntheticReview(c)
            assertEquals("", output.toString())
        } finally { System.setOut(oldOut); System.setErr(oldErr); stream.close() }
    }

    private fun terminalAtEveryPhase(stop: (CollectionAttempt) -> Unit) {
        for (stage in 0..4) {
            val a = attempt()
            if (stage >= 1) a.fixSyntheticScope(listOf(key()))
            if (stage >= 2) a.finishSyntheticProtection(listOf(a.reviewSyntheticRoot(observation())!!))
            if (stage >= 3) recorded(a)
            val handle = if (stage >= 4) a.finishSyntheticReview() else null
            stop(a)
            val first = a.firstFailure
            assertNull(a.finishSyntheticReview()); assertFalse(a.fixSyntheticScope(listOf(key())))
            if (handle != null) assertEquals(SyntheticConsumption.REJECTED, a.consumeSyntheticReview(handle))
            assertEquals(AttemptState.DISCARDED, a.state); assertEquals(first, a.firstFailure)
        }
    }
    private fun rejected(a: CollectionAttempt, reason: AttemptFailure) {
        assertEquals(AttemptState.DISCARDED, a.state); assertEquals(reason, a.firstFailure)
        assertFalse(a.productionReady)
    }
    private fun assertCleared(a: CollectionAttempt) {
        // Checks owner references only, not JVM heap erasure or Android object lifetime.
        fun read(name: String): Any? = CollectionAttempt::class.java.getDeclaredField(name).let {
            it.isAccessible = true; it.get(a)
        }
        assertTrue((read("scope") as List<*>).isEmpty())
        assertTrue((read("issued") as Map<*, *>).isEmpty())
        assertNull(read("projected")); assertNull(read("completion"))
    }
}
