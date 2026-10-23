package kr.closeforyou.accessibility.action

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.*
import org.junit.Test

class ReadOnlyTargetProbeTest {
    private class Node(val target: NodeSnapshot, val token: SnapshotToken, val pathRoot: Boolean = false) : ProbeNode {
        var pkg: String? = token.packageName; var win = token.windowId
        var next: Node? = null; var properties = 0; var releases = 0
        var raw: CharSequence? = target.text; var hidden = false; var secret = false
        var closeFailure = false; var beforeBounds: () -> Unit = {}; var onClose: () -> Unit = {}
        var rawBounds: ProbeRect? = null
        var handle: Any = Any()
        override fun sameHandle(other: ProbeNode) = other is Node && handle === other.handle
        override fun packageName() = pkg
        override fun windowId() = win
        override fun childCount(): Int { properties++; return if (next == null) 0 else 1 }
        override fun child(index: Int): ProbeNode? { properties++; return if (index == 0) next else null }
        override fun password(): Boolean { properties++; return secret }
        override fun editable() = false
        override fun inputType() = 0
        override fun text(field: ProbeText): CharSequence? {
            properties++
            return when (field) { ProbeText.TEXT -> raw; ProbeText.DESCRIPTION -> target.contentDescription
                ProbeText.VIEW_ID -> target.viewId; ProbeText.CLASS_NAME -> target.className; else -> null }
        }
        override fun bounds(): ProbeRect { properties++; beforeBounds(); return rawBounds ?: target.bounds.let { ProbeRect(it.left,it.top,it.right,it.bottom) } }
        override fun actions() = target.actions
        override fun clickable() = target.clickable
        override fun enabled() = target.enabled
        override fun visible() = !hidden
        override fun close() { releases++; onClose(); if (closeFailure) throw IllegalStateException("PRIVATE") }
    }
    private class Source(private val label: String? = null, private val description: String? = null) : TargetProbeSource {
        val base = MockPopupFixtures.notice()
        val token = base.snapshot.token.copy(captureEpoch = 1)
        val target = base.snapshot.nodes[1].copy(path = "0/0",
            text = label ?: base.snapshot.nodes[1].text,
            contentDescription = description ?: base.snapshot.nodes[1].contentDescription)
        val snapshot = base.snapshot.copy(token = token, nodes = persistentListOf(base.snapshot.nodes[0].copy(path = "0"), target))
        var ref: TargetReadReference? = TargetReadReference(snapshot, base.analysis.closeCandidates.single().copy(
            nodePath = "0/0", label = label ?: base.analysis.closeCandidates.single().label), 7)
        var runtime = ProbeRuntimeState(7, token, true, true, false, true, false)
        val child = Node(target, token)
        val rootNode = Node(snapshot.nodes[0], token, true).also { it.next = child }
        var rootReads = 0; var windowReads = 0; var now = 1_000L
        var windowsFailure = false
        var windowChangedAtEnd = false
        override fun reference() = ref
        override fun state() = runtime
        override fun root(): ProbeNode { rootReads++; return rootNode }
        override fun windowFrame(windowId: Int): ProbeWindowFrame {
            windowReads++; if (windowsFailure) throw IllegalArgumentException("PRIVATE")
            val screen = snapshot.screenBounds; val window = snapshot.windows.single().bounds
            return ProbeWindowFrame(0, ProbeRect(screen.left,screen.top,screen.right,screen.bottom),
                ProbeRect(window.left,window.top,window.right + if (windowChangedAtEnd && windowReads > 1) 1 else 0,window.bottom),true,false)
        }
        fun probe() = ReadOnlyTargetProbe(this, GateClock { now })
    }
    @Test fun normalReadOnlyComparisonNeverProvesF0IdentityChoiceOrExecution() = runTest {
        val s = Source(); val result = s.probe().inspect()
        assertEquals(ReadOnlyTargetProbe.UNPROVEN, result.denials); assertFalse(result.productionReady)
        assertEquals(1, s.rootNode.releases); assertEquals(1, s.child.releases)
    }
    @Test fun noRegisteredReferenceMeansNoAndroidRootOrWindowReads() = runTest {
        val s = Source(); s.ref = null
        assertTrue(ProbeDenial.NO_REFERENCE in s.probe().inspect().denials)
        assertEquals(0, s.rootReads); assertEquals(0, s.windowReads)
    }
    @Test fun rootPackageMismatchOrUnknownRejectsBeforeDataOrGeometryGetters() = runTest {
        for (pkg in listOf(null, "other")) {
            val s = Source(); s.rootNode.pkg = pkg
            assertTrue(ProbeDenial.OWNER_MISMATCH in s.probe().inspect().denials)
            assertEquals(0, s.rootNode.properties); assertEquals(0, s.windowReads); assertEquals(1, s.rootNode.releases)
        }
    }
    @Test fun wrongRootWindowRejectsBeforeAnyDataGetter() = runTest {
        val s = Source(); s.rootNode.win++
        assertTrue(ProbeDenial.OWNER_MISMATCH in s.probe().inspect().denials)
        assertEquals(0,s.rootNode.properties); assertEquals(0,s.windowReads)
    }
    @Test fun childOwnershipIsCheckedBeforeItsFieldsAndReleasesBothHandles() = runTest {
        val s = Source(); s.child.pkg = "other"
        assertTrue(ProbeDenial.OWNER_MISMATCH in s.probe().inspect().denials)
        assertEquals(0,s.child.properties); assertEquals(1,s.child.releases); assertEquals(1,s.rootNode.releases)
    }
    @Test fun missingOrHiddenTargetCannotMatch() = runTest {
        val missing = Source(); missing.rootNode.next = null
        assertTrue(ProbeDenial.TARGET_MISSING in missing.probe().inspect().denials)
        val hidden = Source(); hidden.child.hidden = true
        assertTrue(ProbeDenial.TARGET_CHANGED in hidden.probe().inspect().denials)
    }
    @Test fun protectedTargetAndOversizedOrSensitiveTextAreRejected() = runTest {
        val password = Source(); password.child.secret = true
        assertTrue(ProbeDenial.PROTECTED_TARGET in password.probe().inspect().denials)
        val text = Source(); text.child.raw = "password"
        assertTrue(ProbeDenial.PROTECTED_TARGET in text.probe().inspect().denials)
        val huge = Source(); huge.child.raw = "x".repeat(4_097)
        assertTrue(ProbeDenial.LIMIT in huge.probe().inspect().denials)
    }
    @Test fun changedMutableCopyCannotBeComparedAsOriginalLabel() = runTest {
        val s = Source(); s.child.raw = StringBuilder("close")
        assertTrue(ProbeDenial.TARGET_CHANGED in s.probe().inspect().denials)
    }

    @Test fun sharedLabelsRemainReadOnlyEvenWhenNodeUsesDifferentSafeDescription() = runTest {
        for (label in listOf("광고 닫기", "ＣＬＯＳＥ")) {
            val s = Source(label, "close")
            val result = s.probe().inspect()
            assertEquals(label, ReadOnlyTargetProbe.UNPROVEN, result.denials)
            assertFalse(result.productionReady)
        }
    }

    @Test fun sharedNodeContractRejectsConflictingOrRiskyDescriptions() = runTest {
        for (description in listOf("건너뛰기", "동의", "닫기.")) {
            val s = Source("닫기", description)
            val result = s.probe().inspect()
            assertTrue(description, ProbeDenial.TARGET_CHANGED in result.denials)
            assertFalse(result.productionReady)
        }
    }

    @Test fun originalTtlBoundaryIsNotRenewedByRead() = runTest {
        val before = Source(); before.now = 5_999
        assertEquals(ReadOnlyTargetProbe.UNPROVEN, before.probe().inspect().denials)
        val expired = Source(); expired.now = 6_000
        assertTrue(ProbeDenial.TTL_EXPIRED in expired.probe().inspect().denials)
        assertEquals(0,expired.rootReads)
    }
    @Test fun sessionAndCurrentTokenChangesAreRejected() = runTest {
        val s = Source(); s.child.beforeBounds = { s.runtime = s.runtime.copy(serviceGeneration = 8) }
        assertTrue(ProbeDenial.SESSION_CHANGED in s.probe().inspect().denials)
        val t = Source(); t.child.beforeBounds = { t.runtime = t.runtime.copy(token = t.token.copy(popupInstanceId = "other")) }
        assertTrue(ProbeDenial.STALE_TOKEN in t.probe().inspect().denials)
    }
    @Test fun disconnectConsentTouchAndPauseCannotReadNodes() = runTest {
        for (change in listOf<(ProbeRuntimeState)->ProbeRuntimeState>({it.copy(connected=false)},
            {it.copy(consent=false)},{it.copy(appAllowed=false)},{it.copy(paused=true)},{it.copy(userInteracting=true)})) {
            val s=Source();s.runtime=change(s.runtime)
            assertTrue(ProbeDenial.POLICY_BLOCKED in s.probe().inspect().denials); assertEquals(0,s.rootReads)
        }
    }
    @Test fun readAndCleanupFailuresCannotProduceComparisonOnlyMatch() = runTest {
        val s = Source(); s.windowsFailure = true; s.rootNode.closeFailure = true
        assertTrue(ProbeDenial.READ_FAILED in s.probe().inspect().denials)
        assertEquals(1,s.rootNode.releases)
        val c = Source(); c.child.closeFailure = true
        assertTrue(ProbeDenial.CLEANUP_FAILED in c.probe().inspect().denials)
        assertEquals(1,c.rootNode.releases)
    }
    @Test fun timeoutOrTtlExpiryDuringCleanupCannotBeAccepted() = runTest {
        val s = Source(); s.child.onClose = {s.now += 101}
        assertTrue(ProbeDenial.TIME_LIMIT in s.probe().inspect().denials)
        val t = Source(); t.child.onClose = {t.now += 5_000}
        assertTrue(ProbeDenial.TTL_EXPIRED in t.probe().inspect().denials)
    }
    @Test fun cancellationDuringTargetReadReleasesAllNodesAndNeverRetries() = runTest {
        val s = Source(); val cancelled = CancellationException("PRIVATE")
        s.child.beforeBounds = {throw cancelled}; s.rootNode.closeFailure = true
        val probe = s.probe()
        assertSame(cancelled, assertThrows(CancellationException::class.java) { runBlocking { probe.inspect() } })
        assertEquals(1,s.rootNode.releases);assertEquals(1,s.child.releases)
        assertTrue(ProbeDenial.ALREADY_READ in probe.inspect().denials)
    }
    @Test fun duplicateConcurrentReadsHaveAtMostOneNodeTraversal() = runTest {
        val s = Source(); val probe = s.probe()
        val results = awaitAll(async {probe.inspect()},async {probe.inspect()})
        assertEquals(1,s.rootReads);assertEquals(1,results.count {ProbeDenial.ALREADY_READ in it.denials})
        assertTrue(results.none {it.productionReady})
    }
    @Test fun referenceAndResultRepresentationsNeverPrintTokensPathsOrValues() = runTest {
        val s=Source();val outcome=s.probe().inspect()
        listOf(s.ref.toString(),s.probe().toString(),outcome.toString(),s.runtime.toString()).forEach {
            assertFalse(it.contains(s.token.packageName));assertFalse(it.contains(s.token.structureDigest));assertFalse(it.contains("0/0"))
        }
    }
    @Test fun unchangedTokenDoesNotHideWindowGeometryChangingAtTheEnd() = runTest {
        val s=Source();s.windowChangedAtEnd=true
        assertTrue(ProbeDenial.GEOMETRY_CHANGED in s.probe().inspect().denials)
        assertEquals(2,s.windowReads);assertEquals(1,s.rootNode.releases);assertEquals(1,s.child.releases)
    }
    @Test fun rawReversedEmptyOrOverflowSizedTargetBoundsCannotBeRepairedToMatch() = runTest {
        for (bounds in listOf(ProbeRect(5,10,4,20),ProbeRect(5,20,10,19),ProbeRect(1,1,1,1),
            ProbeRect(Int.MIN_VALUE,Int.MIN_VALUE,Int.MAX_VALUE,Int.MAX_VALUE))) {
            val s=Source();s.child.rawBounds=bounds
            assertTrue(ProbeDenial.TARGET_CHANGED in s.probe().inspect().denials)
            assertEquals(1,s.child.releases)
        }
    }
    @Test fun twoWrappersForOneFrameworkHandleAreRejectedAndReleasedOnlyOnce() = runTest {
        val s = Source(); s.child.handle = s.rootNode.handle
        assertTrue(ProbeDenial.TARGET_CHANGED in s.probe().inspect().denials)
        assertEquals(1, s.rootNode.releases); assertEquals(0, s.child.releases)
        assertEquals(0, s.child.properties)
    }
    @Test fun actualJobCancellationDuringLastClockReadIsNotAComparisonResult() = runTest {
        val s = Source(); val owner = Job(); var calls = 0
        val probe = ReadOnlyTargetProbe(s, GateClock {
            calls++
            if (s.rootNode.releases > 0) owner.cancel()
            s.now
        })
        try {
            withContext(owner) { probe.inspect() }
            fail("CANCELLATION_REQUIRED")
        } catch (_: CancellationException) { /* Fixed test assertion, no source exception output. */ }
        assertTrue(calls > 1); assertTrue(owner.isCancelled)
        assertEquals(1, s.rootNode.releases); assertEquals(1, s.child.releases)
        assertTrue(ProbeDenial.ALREADY_READ in probe.inspect().denials)
    }
}
