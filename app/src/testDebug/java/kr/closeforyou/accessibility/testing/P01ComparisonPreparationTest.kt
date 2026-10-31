package kr.closeforyou.accessibility.testing

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.channels.Channel
import kotlinx.collections.immutable.toPersistentList
import kr.closeforyou.accessibility.*
import kr.closeforyou.accessibility.integration.P01StabilityFixture
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

class P01ComparisonPreparationTest {
    private fun complete(ready: CollectionResult.Ready=P01StabilityFixture.sample(1)): P01ReaderComparisonService.Read {
        val s=ready.snapshot
        return P01ReaderComparisonService.Read(ReaderSample(s.token.packageName,s.token.windowId,s.screenBounds,
            s.windows,s.nodes,s,ready.nativeActions,mapOf(s.token.windowId to listOf(ready.frame.windowBounds)),null),ready,
            status=ComparisonStatus.COMPLETE,f0Release=CaptureReadRelease.COMPLETED)
    }
    private fun absent() = P01ReaderComparisonService.Read(null,null,status=ComparisonStatus.BLOCKED,
        block=PrivacyBlockReason.UNREADABLE_TREE,f0Detail=CaptureDetail.ROOT_UNAVAILABLE,
        f0Point=CaptureReadPoint.ROOT,f0Release=CaptureReadRelease.UNKNOWN)
    private fun timeout() = absent().copy(f0Detail=CaptureDetail.TIME_LIMIT,
        f0Point=CaptureReadPoint.SNAPSHOT_PASS,f0Release=CaptureReadRelease.COMPLETED)
    private class Rig {
        var time=0L; var calls=0; var waits=0; var accepts=0; var valid=true
        var next: (Int)->P01ReaderComparisonService.Read = { error("NO_READ") }
        var event: (Long)->Boolean = { time++; true }
        val rows=mutableListOf<String>()
        val accepted=mutableListOf<P01ReaderComparisonService.Read>()
        suspend fun run()=P01ComparisonPreparation.run({time},{if(!valid) throw ComparisonAbort(ComparisonIssue.INVALIDATED)},
            { next(++calls) },{ waits++; event(it) },{ accepts++; accepted+=it },rows::add)
    }
    @Test fun preparationTriggerRejectsPreviousSessionUnknownWindowAndForeignEvents() {
        val checks=ComparisonRunChecks(100)
        assertEquals(false,checks.event(true,2,99,120))
        assertEquals(false,checks.event(false,2,110,120))
        assertEquals(false,checks.event(true,-1,110,120))
        assertEquals(false,checks.event(true,2,121,120))
        assertFalse(checks.receiving)
        assertEquals(true,checks.event(true,2,110,120))
    }
    @Test fun preparationTriggerCannotReuseDuplicateOrOutOfOrderEvent() {
        val checks=ComparisonRunChecks(100)
        assertEquals(false,checks.event(true,2,100,120)) // arm boundary is ambiguous
        assertEquals(true,checks.event(true,2,110,120))
        assertEquals(false,checks.event(true,2,110,121))
        assertEquals(false,checks.event(true,2,109,122))
        assertEquals(true,checks.event(true,2,111,123))
        val next=ComparisonRunChecks(200)
        assertEquals(false,next.event(true,2,111,201))
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun suspendedEventWaitAcceptsOneValidatedTriggerAndTimesOutWithoutAnother() = runTest {
        val checks=ComparisonRunChecks(0)
        val changes=Channel<Unit>(Channel.CONFLATED)
        var reads=0; val rows=mutableListOf<String>()
        val result=async {
            P01ComparisonPreparation.run({testScheduler.currentTime},{}, {reads++; absent()},
                { remaining -> withTimeoutOrNull(remaining) { changes.receive(); true } ?: false },
                {error("NO_READY")},rows::add)
        }
        fun deliver(fromHost: Boolean, occurred: Long) {
            if(checks.event(fromHost,2,occurred,testScheduler.currentTime)) changes.trySend(Unit)
        }
        runCurrent(); assertEquals(1,reads); assertFalse(result.isCompleted)
        advanceTimeBy(10)
        deliver(true,-1); deliver(false,5); runCurrent()
        assertEquals(1,reads) // service-loop work runs while receive is suspended
        deliver(true,5); deliver(true,5); runCurrent()
        assertEquals(2,reads)
        deliver(true,5); deliver(true,4); runCurrent()
        assertEquals(2,reads) // neither duplicate delivery nor an old signal buys another read
        advanceTimeBy(1_490); runCurrent()
        assertEquals(PreparationEnd.TIME_LIMIT,result.await())
        assertTrue(rows.last().contains("ATTEMPTS=2;UNAVAILABLE_READS=2"))
        changes.close()
    }
    @Test fun hostReceptionWithoutRootIsNotReadyAndWaitsForRealEvent() = runTest {
        val r=Rig(); r.next={absent()}; r.event={r.time+=it;false}
        assertEquals(PreparationEnd.TIME_LIMIT,r.run())
        assertEquals(1,r.calls); assertEquals(0,r.accepts)
        assertTrue(r.rows.last().contains("PREPARATION=NOT_READY"))
        assertTrue(r.rows.last().contains("FIRST_F0_DETAIL=ROOT_UNAVAILABLE"))
    }
    @Test fun noMoreThanFourReadsAndNoSleepApproval() = runTest {
        val r=Rig(); r.next={timeout()}
        assertEquals(PreparationEnd.ATTEMPT_LIMIT,r.run())
        assertEquals(4,r.calls); assertEquals(3,r.waits); assertEquals(0,r.accepts)
        assertTrue(r.rows.last().contains("UNAVAILABLE_READS=4"))
    }
    @Test fun budgetConsumedByFirstReadRejectsEvenValidPopup() = runTest {
        val r=Rig(); r.next={r.time=1_500;complete()}
        assertEquals(PreparationEnd.TIME_LIMIT,r.run())
        assertEquals(1,r.calls); assertEquals(0,r.accepts)
    }
    @Test fun deadlineStillAppliesAtFinalAcceptanceBoundary() = runTest {
        var time=0L
        val rows=mutableListOf<String>()
        val end=P01ComparisonPreparation.run({time},{}, {complete()}, {error("NO_RETRY")},
            {time=1_500},rows::add)
        assertEquals(PreparationEnd.TIME_LIMIT,end)
        assertTrue(rows.last().contains("PREPARATION=NOT_READY"))
    }
    @Test fun expiredEventWaitDoesNotStartAnotherRead() = runTest {
        val r=Rig(); r.next={absent()}; r.event={r.time+=it;true}
        assertEquals(PreparationEnd.TIME_LIMIT,r.run()); assertEquals(1,r.calls)
    }
    @Test fun actualBDetectedBoundedPopupOnlyAcceptsLastFreshObjectAndKeepsEarlierFailures() = runTest {
        val r=Rig(); val last=complete(P01StabilityFixture.sample(3))
        r.next={when(it){1->absent();2->timeout();else->last}}
        assertEquals(PreparationEnd.READY,r.run())
        assertSame(last,r.accepted.single()); assertEquals(3,r.calls)
        assertEquals(2,r.rows.count { it.contains("OBSERVATION=WAITING") })
        assertTrue(r.rows.last().contains("UNAVAILABLE_READS=2"))
        assertTrue(r.rows.last().contains("FIRST_F0_DETAIL=ROOT_UNAVAILABLE"))
        assertTrue(r.rows.last().contains("FIRST_F0_RELEASE=UNKNOWN"))
    }
    @Test fun backgroundActivityIsOnlyWaitingNotReady() {
        val base=P01StabilityFixture.sample(1)
        val s=base.snapshot.copy(windows=base.snapshot.windows.map {
            if(it.id==base.snapshot.token.windowId) it.copy(bounds=base.snapshot.screenBounds) else it
        }.toPersistentList())
        val read=complete(P01StabilityFixture.result(s))
        assertEquals(PreparationState.WAITING,P01ComparisonPreparation.assess(read).state)
    }
    @Test fun ambiguousOrMissingCloseCandidateInBoundedPopupRejects() {
        val base=P01StabilityFixture.sample(1).snapshot
        val missing=base.copy(nodes=base.nodes.map {
            if(it.path=="0/0/3") it.copy(text="알 수 없음") else it }.toPersistentList())
        assertEquals(PreparationState.REJECTED,P01ComparisonPreparation.assess(complete(P01StabilityFixture.result(missing))).state)
        val doubled=base.copy(nodes=base.nodes.map { if(it.path=="0/0/2") it.copy(text="닫기") else it }.toPersistentList())
        assertEquals(PreparationState.REJECTED,P01ComparisonPreparation.assess(complete(P01StabilityFixture.result(doubled))).state)
    }
    @Test fun protectionUnknownAndReleaseFailuresStopImmediately() = runTest {
        val failures=listOf(
            absent().copy(block=PrivacyBlockReason.SENSITIVE_CONTENT,f0Detail=CaptureDetail.TREE_PROTECTION),
            absent().copy(f0Detail=CaptureDetail.COLLECTION_EXCEPTION),
            timeout().copy(f0Release=CaptureReadRelease.UNKNOWN),
            timeout().copy(f0Release=CaptureReadRelease.FAILED),
            timeout().copy(f0Release=CaptureReadRelease.CANCELLED),
            complete().copy(f0Release=CaptureReadRelease.FAILED_AND_CANCELLED),
            absent().copy(status=ComparisonStatus.OWNERSHIP_UNAVAILABLE),
            absent().copy(f0Point=CaptureReadPoint.END_ACTIVE_ROOT))
        for (failure in failures) {
            val r=Rig(); r.next={failure}
            assertEquals(PreparationEnd.REJECTED,r.run())
            assertEquals(1,r.calls); assertEquals(0,r.waits); assertEquals(0,r.accepts)
        }
    }
    @Test fun cancellationPropagatesWithFailureSummaryAndNoAcceptance() = runTest {
        val cancelled=CancellationException("SYNTHETIC")
        val r=Rig(); r.next={throw cancelled}
        try { r.run(); fail() } catch(e: CancellationException) { assertSame(cancelled,e) }
        assertEquals(0,r.accepts); assertTrue(r.rows.last().contains("END=CANCELLED"))
    }
    @Test fun interactionConsentOrServiceInvalidationDuringWaitStopsBeforeNextRead() = runTest {
        val r=Rig(); r.next={absent()}; r.event={r.valid=false;true}
        try { r.run(); fail() } catch(e: ComparisonAbort) { assertEquals(ComparisonIssue.INVALIDATED,e.issue) }
        assertEquals(1,r.calls); assertEquals(0,r.accepts)
    }
    @Test fun wrongPackageFrameOwnerOrMissingNativeMetadataCannotPrepare() {
        val good=complete(); val s=good.ready!!.snapshot; val sample=good.sample!!
        for (sampleOverride in listOf(
            ReaderSample("different",sample.window,sample.frame,sample.windows,sample.nodes,s,sample.actions,sample.regions,null),
            ReaderSample(sample.packageName,sample.window,sample.frame.copy(right=401),sample.windows,sample.nodes,s,sample.actions,sample.regions,null),
            ReaderSample(sample.packageName,sample.window,sample.frame,sample.windows,sample.nodes,s.copy(),sample.actions,sample.regions,null),
            ReaderSample(sample.packageName,sample.window,sample.frame,sample.windows,sample.nodes,s,null,sample.regions,null),
            ReaderSample(sample.packageName,sample.window,sample.frame,sample.windows,sample.nodes,s,sample.actions,null,null)))
            assertEquals(PreparationState.REJECTED,P01ComparisonPreparation.assess(good.copy(sample=sampleOverride)).state)
    }
    @Test fun interveningWindowOcclusionImeOrAmbiguousTargetRejects() {
        val base=P01StabilityFixture.sample(1).snapshot
        for (extra in listOf(
            WindowSnapshot(3,WindowType.SYSTEM,base.windows.last().bounds,false,false,2),
            WindowSnapshot(3,WindowType.APPLICATION,base.windows.last().bounds,false,false,2),
            WindowSnapshot(3,WindowType.INPUT_METHOD,ScreenBounds(0,500,400,600),false,false,0),
            base.windows.last())) {
            val changed=base.copy(windows=(base.windows+extra).toPersistentList())
            // A duplicate target cannot use the fixture's single-target builder. The real
            // readiness boundary must reject it before native-action binding is considered.
            val ready=if(extra.id==base.token.windowId) P01StabilityFixture.sample(1).copy(snapshot=changed)
                else P01StabilityFixture.result(changed)
            val read=complete(ready)
            assertEquals(PreparationState.REJECTED,P01ComparisonPreparation.assess(read).state)
        }
    }
    @Test fun preparationCannotHideReadEightTimeoutOrOriginalBaselineFailure() = runTest {
        val r=Rig(); r.next={complete()}; assertEquals(PreparationEnd.READY,r.run())
        assertFalse(comparisonComplete(ComparisonIssue.BASELINE_UNAVAILABLE,true,1,true,2))
        assertFalse(comparisonComplete(ComparisonIssue.NONE,true,1,true,5))
        assertTrue(comparisonComplete(ComparisonIssue.NONE,true,0,true,5))
    }
    @Test fun allFourConditionsUseSamePreparationAndDoNotSeedMeasurement() = runTest {
        for (condition in ComparisonCondition.entries) for(order in ComparisonOrder.entries) {
            val r=Rig(); val prepared=complete(P01StabilityFixture.sample(1)); r.next={prepared}
            assertEquals(PreparationEnd.READY,r.run())
            val fresh=complete(P01StabilityFixture.sample(2))
            assertNotSame(prepared.ready,fresh.ready)
            assertNotEquals(prepared.ready!!.snapshot.token,fresh.ready!!.snapshot.token)
            assertEquals(ComparisonValue.UNKNOWN,ReaderComparison.compare(null,fresh.sample).binding)
            assertEquals(6,ReaderComparison.order(order).size)
            assertFalse(expectedComparisonGuard(condition,ComparisonPhase.INITIAL,ComparisonReader.COLLECTOR_FRESH,null))
        }
    }
    @Test fun preparedAnchorRejectsReplacementAndRemovalReadditionButDoesNotApproveGeometry() {
        val original=complete().sample!!
        val checks=ComparisonRunChecks(0); checks.event(true,original.window,1,1); checks.preparedTarget(original)
        val moved=complete(P01StabilityFixture.sample(2,expanded=true)).sample!!
        assertEquals(ComparisonIssue.NONE,checks.sample(moved))
        assertEquals(ComparisonValue.CHANGED,ReaderComparison.compare(original,moved).root)
        // No prepared candidate/path seeds the measured close comparison.
        assertEquals(ComparisonValue.UNKNOWN,ReaderComparison.compare(original,moved).close)
        val base=P01StabilityFixture.sample(3).snapshot
        val changed=complete(P01StabilityFixture.result(base.copy(nodes=base.nodes.map {
            if(it.path=="0/0/3") it.copy(text="다른 버튼") else it }.toPersistentList()))).sample!!
        assertEquals(ComparisonIssue.TARGET_CHANGED,checks.sample(changed))
        val next=ComparisonRunChecks(0); next.event(true,original.window,1,1); next.preparedTarget(original)
        next.targetLifecycle(original.window,true)
        next.targetLifecycle(original.window,true)
        assertEquals(ComparisonIssue.TARGET_CHANGED,next.issue)
    }
    @Test fun noRawScreenDataInPreparationReports() = runTest {
        val r=Rig(); r.next={complete()}; r.run()
        val text=r.rows.joinToString()
        for (forbidden in listOf("kr.closeforyou", "synthetic", "0/0", "광고", "닫기", "ScreenBounds", "SnapshotToken"))
            assertFalse(text.contains(forbidden))
        assertTrue(text.contains("EXTERNAL_CLICK=NOT_WIRED"))
    }
}
