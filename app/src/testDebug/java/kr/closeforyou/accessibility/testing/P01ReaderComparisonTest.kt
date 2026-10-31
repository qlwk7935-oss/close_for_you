package kr.closeforyou.accessibility.testing

import android.view.accessibility.AccessibilityNodeInfo as N
import kr.closeforyou.accessibility.*
import kr.closeforyou.accessibility.action.F9ReadDetail
import kr.closeforyou.accessibility.integration.P01StabilityFixture
import kr.closeforyou.core.model.*
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.*
import org.junit.Test

class P01ReaderComparisonTest {
    @Test fun connectedButNoDeliveredTargetEventCannotCompleteExperiment() {
        val gate=ComparisonRunChecks(100)
        assertEquals(ComparisonIssue.EVENT_UNCONFIRMED,gate.sample(sample()))
        assertFalse(comparisonComplete(ComparisonIssue.NONE,true,0,false,5))
    }
    @Test fun previousLaunchFutureOrForeignEventsCannotConfirmReception() {
        val gate=ComparisonRunChecks(100)
        val value=sample()
        gate.event(true,value.window,99,110)
        gate.event(true,value.window,111,110)
        gate.event(false,value.window,105,110)
        gate.event(true,-1,105,110)
        assertFalse(gate.receiving)
        assertEquals(ComparisonIssue.EVENT_UNCONFIRMED,gate.sample(value))
    }
    @Test fun receivedDifferentWindowDoesNotConfirmTheCollectedTarget() {
        val gate=ComparisonRunChecks(100)
        gate.event(true,999,101,102)
        assertEquals(ComparisonIssue.EVENT_UNCONFIRMED,gate.sample(sample()))
    }
    @Test fun firstTargetStaysAnchoredEvenWhenReplacementHasDeliveredEvents() {
        val gate=ComparisonRunChecks(100)
        val original=sample(); val changed=sample(2,wrongWindow=true)
        val replacement=ReaderSample(changed.packageName,changed.window,changed.frame,
            changed.windows.map { if(it.id==original.window) it.copy(id=changed.window) else it },
            changed.nodes,changed.actionOwner,changed.actions,changed.regions,changed.closePath)
        gate.event(true,original.window,101,102)
        gate.event(true,replacement.window,103,104)
        assertEquals(ComparisonIssue.NONE,gate.sample(original))
        assertEquals(ComparisonIssue.TARGET_CHANGED,gate.sample(replacement))
        gate.sample(original)
        assertEquals(ComparisonIssue.TARGET_CHANGED,gate.issue)
    }
    @Test fun frameChangeCannotBeInterpretedAsOnlyRootMotionWithinOneCondition() {
        val gate=ComparisonRunChecks(100)
        gate.event(true,sample().window,101,102)
        assertEquals(ComparisonIssue.NONE,gate.sample(sample()))
        assertEquals(ComparisonIssue.FRAME_CHANGED,gate.sample(sample(2,frameChange=true)))
    }
    @Test fun independentExactReadsKeepTheSameRunAnchorButDoNotProveNativeGeneration() {
        val gate=ComparisonRunChecks(100)
        gate.event(true,sample().window,101,102)
        assertEquals(ComparisonIssue.NONE,gate.sample(sample()))
        assertEquals(ComparisonIssue.NONE,gate.sample(sample(2)))
        assertTrue(comparisonComplete(gate.issue,true,0,gate.receiving,5))
    }
    @Test fun oldOrEndedCallbackCannotBeAcceptedByNextCondition() {
        val gate=ComparisonRunChecks(100)
        assertTrue(gate.callback(true,2,2))
        assertFalse(gate.callback(true,1,2))
        assertEquals(ComparisonIssue.STALE_CALLBACK,gate.issue)
        assertFalse(ComparisonRunChecks(100).callback(false,2,2))
    }
    @Test fun shownAndNativeAttachmentAreSeparateAndLossCannotBeHidden() {
        assertEquals(ComparisonCState.NOT_CREATED,comparisonCState(false,false,false,false))
        assertEquals(ComparisonCState.UNCONFIRMED,comparisonCState(true,true,false,false))
        assertEquals(ComparisonCState.NATIVE_ATTACHED,comparisonCState(true,true,true,false))
        assertEquals(ComparisonCState.LOST,comparisonCState(true,true,false,true))
        assertEquals(ComparisonCState.LOST,comparisonCState(true,false,true,true))
    }
    @Test fun cleanupFailureUnavailableReadIncompletePhasesAndCancellationNeverComplete() {
        assertFalse(comparisonComplete(ComparisonIssue.NONE,false,0,true,5))
        assertFalse(comparisonComplete(ComparisonIssue.NONE,true,1,true,5))
        assertFalse(comparisonComplete(ComparisonIssue.NONE,true,0,true,4))
        for (issue in ComparisonIssue.entries.filter { it!=ComparisonIssue.NONE })
            assertFalse(comparisonComplete(issue,true,0,true,5))
    }
    @Test fun firstProblemSurvivesLaterCleanupOrStaleCallbackFailure() {
        val gate=ComparisonRunChecks(100)
        gate.fail(ComparisonIssue.CONFIGURATION_CHANGED)
        gate.fail(ComparisonIssue.CLEANUP_FAILED)
        gate.callback(false,1,1)
        assertEquals(ComparisonIssue.CONFIGURATION_CHANGED,gate.issue)
    }
    @Test fun teardownClearsTargetAndWindowEvidenceButKeepsOnlyCompletionFacts() {
        val gate=ComparisonRunChecks(100)
        gate.event(true,sample().window,101,102)
        assertEquals(ComparisonIssue.NONE,gate.sample(sample()))
        gate.clearObservations()
        assertTrue(gate.receiving)
        assertEquals(ComparisonIssue.NONE,gate.issue)
        assertEquals(ComparisonIssue.EVENT_UNCONFIRMED,gate.sample(sample()))
    }
    private fun sample(sequence: Int=1, moveRoot: Boolean=false, moveClose: Boolean=false,
        moveBody: Boolean=false, unknown: Boolean=false, wrongOwner: Boolean=false,
        regionChange: Boolean=false, actionChange: Boolean=false, replaced: Boolean=false,
        frameChange: Boolean=false, wrongWindow: Boolean=false, missingRegion: Boolean=false): ReaderSample {
        val base=P01StabilityFixture.sample(sequence).snapshot
        val nodes=base.nodes.mapIndexed { i,n ->
            when {
                i==0 && moveRoot -> n.copy(bounds=n.bounds.copy(right=n.bounds.right+1))
                i==2 && moveBody -> n.copy(bounds=n.bounds.copy(bottom=n.bounds.bottom+1))
                i==5 && moveClose -> n.copy(bounds=n.bounds.copy(left=n.bounds.left+1))
                i==5 && replaced -> n.copy(text="다른 합성 버튼")
                else -> n
            }
        }.toPersistentList()
        val s=base.copy(nodes=nodes)
        val rows=nodes.mapIndexed { i,n -> if(unknown && i==1) NativeActionSummary.UNKNOWN else
            NativeActionSummary.fromIds(when {
                i==5 && actionChange -> listOf(N.ACTION_CLICK,N.ACTION_LONG_CLICK)
                n.clickable -> listOf(N.ACTION_CLICK)
                else -> emptyList()
            }) }
        val actions=NativeNodeActions.forSnapshotSummaries(s,rows)
        return ReaderSample(s.token.packageName,if(wrongWindow) 999 else s.token.windowId,
            if(frameChange) s.screenBounds.copy(right=s.screenBounds.right+1) else s.screenBounds,
            s.windows,s.nodes,if(wrongOwner) s.copy() else s,actions,
            if(missingRegion) null else mapOf(s.token.windowId to listOf(
                s.windows.last().bounds.let { if(regionChange) it.copy(left=it.left+1) else it })),"0/0/3")
    }
    @Test fun onlyTheRealPostCStrictActiveOverlayGuardIsAnExpectedNegativeControl() {
        assertTrue(expectedComparisonGuard(ComparisonCondition.WITH_C,ComparisonPhase.AFTER_0,
            ComparisonReader.F9_ACTIVE,F9ReadDetail.ACCESSIBILITY_OVERLAY_PRESENT))
        assertFalse(expectedComparisonGuard(ComparisonCondition.WITHOUT_C,ComparisonPhase.AFTER_0,
            ComparisonReader.F9_ACTIVE,F9ReadDetail.ACCESSIBILITY_OVERLAY_PRESENT))
        assertFalse(expectedComparisonGuard(ComparisonCondition.WITH_C,ComparisonPhase.BEFORE_C,
            ComparisonReader.F9_ACTIVE,F9ReadDetail.ACCESSIBILITY_OVERLAY_PRESENT))
    }
    @Test fun missingOwnershipProtectionTimeoutAndDisplayReadFailuresStayUnavailable() {
        for (detail in F9ReadDetail.entries.filter { it!=F9ReadDetail.ACCESSIBILITY_OVERLAY_PRESENT }) {
            assertFalse(expectedComparisonGuard(ComparisonCondition.WITH_C,ComparisonPhase.AFTER_150,
                ComparisonReader.F9_ACTIVE,detail))
        }
        assertFalse(expectedComparisonGuard(ComparisonCondition.WITH_C,ComparisonPhase.AFTER_150,
            ComparisonReader.F9_WINDOW,F9ReadDetail.ACCESSIBILITY_OVERLAY_PRESENT))
        assertFalse(expectedComparisonGuard(ComparisonCondition.WITH_C,ComparisonPhase.AFTER_150,
            ComparisonReader.F9_ACTIVE,null))
    }
    @Test fun actualDriverOrderPairsEveryReaderAndReversesCrossReadDirection() {
        val forward=ReaderComparison.order(ComparisonOrder.FORWARD)
        assertEquals(forward.reversed(),ReaderComparison.order(ComparisonOrder.REVERSE))
        assertEquals(ComparisonReader.entries.toSet(),forward.toSet())
        assertTrue(forward.chunked(2).all { it.size==2 && it[0]==it[1] })
    }
    @Test fun independentExactReadsCompareWithoutTokenCopyOrDigestEquality() {
        val result=ReaderComparison.compare(sample(1),sample(2))
        assertEquals(ComparisonValue.SAME,result.nodes)
        assertEquals(ComparisonValue.SAME,result.actions)
        assertEquals(ComparisonValue.SAME,result.close)
    }
    @Test fun rootAndWindowRegionsAreDifferentObservables() {
        val d=ReaderComparison.compare(sample(),sample(2,moveRoot=true))
        assertEquals(ComparisonValue.CHANGED,d.root)
        assertEquals(ComparisonValue.SAME,d.region)
        assertEquals(ComparisonValue.SAME,d.windowBounds)
        assertEquals(ComparisonValue.SAME,d.close)
    }
    @Test fun regionCanChangeWithoutBoundingRectChange() {
        val d=ReaderComparison.compare(sample(),sample(2,regionChange=true))
        assertEquals(ComparisonValue.CHANGED,d.region)
        assertEquals(ComparisonValue.SAME,d.windowBounds)
        assertEquals(ComparisonValue.SAME,d.root)
    }
    @Test fun bodyAndCloseMovementAreNotConflated() {
        val d=ReaderComparison.compare(sample(),sample(2,moveBody=true))
        assertEquals(ComparisonValue.CHANGED,d.nodes)
        assertEquals(ComparisonValue.SAME,d.close)
    }
    @Test fun closeMovementIsReportedAfterStablePreamble() {
        assertEquals(ComparisonValue.SAME,ReaderComparison.compare(sample(1),sample(2)).close)
        assertEquals(ComparisonValue.CHANGED,ReaderComparison.compare(sample(2),sample(3,moveClose=true)).close)
    }
    @Test fun changedButtonIdentityCannotCertifySameCandidateGeometry() {
        assertEquals(ComparisonValue.UNKNOWN,ReaderComparison.compare(sample(),sample(2,replaced=true)).close)
    }
    @Test fun fullNativeActionDifferenceSurvivesSameDtoActions() {
        val d=ReaderComparison.compare(sample(),sample(2,actionChange=true))
        assertEquals(ComparisonValue.SAME,d.nodes)
        assertEquals(ComparisonValue.CHANGED,d.actions)
    }
    @Test fun unsupportedActionIsUnknownNotEqual() {
        assertEquals(ComparisonValue.UNKNOWN,ReaderComparison.compare(sample(1,unknown=true),sample(2,unknown=true)).actions)
    }
    @Test fun copiedObjectCannotBorrowActionSidecar() {
        assertEquals(ComparisonValue.UNKNOWN,ReaderComparison.compare(sample(),sample(2,wrongOwner=true)).actions)
    }
    @Test fun failedReadCannotReusePreviousSample() {
        val d=ReaderComparison.compare(sample(),null)
        assertEquals(ComparisonValue.UNKNOWN,d.nodes)
        assertEquals(ComparisonValue.UNKNOWN,d.binding)
        assertEquals(ComparisonValue.UNKNOWN,d.actions)
    }
    @Test fun differentWindowNeverComparesNodesAsSamePopup() {
        val d=ReaderComparison.compare(sample(),sample(2,wrongWindow=true))
        assertEquals(ComparisonValue.CHANGED,d.binding)
        assertEquals(ComparisonValue.UNKNOWN,d.nodes)
    }
    @Test fun missingRegionRemainsUnknown() {
        assertEquals(ComparisonValue.UNKNOWN,ReaderComparison.compare(sample(),sample(2,missingRegion=true)).region)
    }
    @Test fun frameChangeIsNotRootMotion() {
        val d=ReaderComparison.compare(sample(),sample(2,frameChange=true))
        assertEquals(ComparisonValue.CHANGED,d.frame)
        assertEquals(ComparisonValue.SAME,d.root)
    }
    @Test fun codesCannotLeakRawDescriptorsAndComparisonsDoNotMutateEvidence() {
        val a=sample(); val b=sample(2,moveClose=true)
        val before=a.actions?.observedSummary(a.actionOwner,a.nodes.size,5)
        repeat(3) {
            val line=ReaderComparison.compare(a,b).fixedCodes()
            assertTrue(line.matches(Regex("[A-Z_=;]+")))
            assertFalse(line.contains("kr.closeforyou")); assertFalse(line.contains("0/0/3"))
        }
        assertEquals(before,a.actions?.observedSummary(a.actionOwner,a.nodes.size,5))
        assertEquals("ReaderSample(redacted)",a.toString())
    }
}
