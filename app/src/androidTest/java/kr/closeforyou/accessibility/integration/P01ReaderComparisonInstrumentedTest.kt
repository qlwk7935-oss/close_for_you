package kr.closeforyou.accessibility.integration

import android.app.UiAutomation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.collections.immutable.toPersistentSet
import kr.closeforyou.accessibility.*
import kr.closeforyou.accessibility.capture.*
import kr.closeforyou.accessibility.testing.*
import kr.closeforyou.core.contract.CaptureAvailability
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionCloseCandidateFinder
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.overlay.PromptShowResult
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No automatic consent/service enablement, target click, focus action, gesture, or OCR.
 * A and B are distinct fresh P01 launches; never interpret their raw window IDs as equal.
 * Only within-run intervals are compared. This isolates readers/C native presentation, NOT
 * the normal service's admission/recovery/TTS/cleanup outcome. */
@RunWith(AndroidJUnit4::class)
class P01ReaderComparisonInstrumentedTest {
    @Test fun withoutCForward() = runBlocking { run(ComparisonCondition.WITHOUT_C,ComparisonOrder.FORWARD) }
    @Test fun withoutCReverse() = runBlocking { run(ComparisonCondition.WITHOUT_C,ComparisonOrder.REVERSE) }
    @Test fun withCForward() = runBlocking { run(ComparisonCondition.WITH_C,ComparisonOrder.FORWARD) }
    @Test fun withCReverse() = runBlocking { run(ComparisonCondition.WITH_C,ComparisonOrder.REVERSE) }

    private suspend fun run(condition: ComparisonCondition, order: ComparisonOrder) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("p01ReaderComparison")=="true")
        assumeTrue(InstrumentationRegistry.getArguments().getString("dedicatedEmulator")=="true")
        val preparedStart=InstrumentationRegistry.getArguments().getString("p01PreparedStart")=="true"
        var ui: UiAutomation?=null
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            requireNotNull(ui).executeShellCommand(command)).use { it.readBytes() }
        fun report(codes: String) = instrumentation.sendStatus(0,Bundle().apply {
            putString("p01_reader","CONDITION=$condition;ORDER=$order;START_MODE=${if(preparedStart) "PREPARED" else "IMMEDIATE"};$codes")
        })
        var service: P01ReaderComparisonService?=null
        var problem=ComparisonIssue.NONE
        var phases=0
        var armed=false
        var primary: Throwable?=null
        var complete=0
        var blocked=0
        var expectedGuard=0
        report("SESSION=STARTED;UIAUTOMATION=DONT_SUPPRESS;RENDER=UNOBSERVED")
        try {
            ui=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                ?: throw ComparisonAbort(ComparisonIssue.UIAUTOMATION_UNAVAILABLE)
            service=withTimeoutOrNull(3_000) {
                withContext(Dispatchers.Main) {
                    while (P01ReaderComparisonService.connected==null) delay(25)
                    P01ReaderComparisonService.connected
                }
            }
            val actual=service ?: throw ComparisonAbort(ComparisonIssue.SERVICE_UNAVAILABLE)
            withContext(Dispatchers.Main) {
                actual.arm(); armed=true; actual.sink=::report
                report("SERVICE=CONNECTED;RECEPTION=UNCONFIRMED")
            }
            // Existing D fixture, unchanged; no input tap. am-start completion is not layout completion.
            shell("am force-stop kr.closeforyou.popuptest")
            shell("am start -W -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01 --ez f9_defer_popup true")
            shell("am start -W -f 0x20000000 -n kr.closeforyou.popuptest/.PopupScenarioActivity --ez f9_show_deferred_popup true")
            withContext(Dispatchers.Main) {
                actual.awaitReception()
                if (preparedStart) {
                    if (actual.prepareComparison()!=PreparationEnd.READY)
                        throw ComparisonAbort(ComparisonIssue.PREPARATION_NOT_READY)
                    // Deliver queued invalidations before INITIAL. No prepared DTO seeds any pair,
                    // measurement counter, candidate, token or evidence pipeline.
                    yield(); actual.checkLive()
                    report("MEASUREMENT=STARTED;PREPARATION=READY;INITIAL_SOURCE=NEW_READ")
                }
                val previous=mutableMapOf<ComparisonReader,ReaderSample?>()
                var adjacent: ReaderSample?=null
                var adjacentReader: ComparisonReader?=null
                var lastReady: CollectionResult.Ready?=null
                var closePath: String?=null
                var readNumber=0
                suspend fun phase(value: ComparisonPhase) {
                    report("PHASE=$value;PHASE_STATUS=STARTED")
                    for (kind in ReaderComparison.order(order)) {
                        currentCoroutineContext().ensureActive()
                        val read=actual.read(kind,value,++readNumber,closePath)
                        if (read.sample!=null) complete++
                        else if (expectedComparisonGuard(condition,value,kind,read.detail)) expectedGuard++
                        else blocked++
                        read.ready?.let { ready ->
                            lastReady=ready
                            val signals=PopupCandidateDetector().detect(ready.frame)
                            val detection=PopupDetection(ready.snapshot.token,signals.toPersistentSet(),true)
                            closePath=DecisionCloseCandidateFinder().find(ready.snapshot,detection)
                                .singleOrNull { it.role==ButtonRole.CLOSE }?.nodePath
                        }
                        report("PAIR=REPEAT;SOURCE=$kind;PHASE=$value;" + ReaderComparison.compare(previous[kind],read.sample).fixedCodes())
                        report("PAIR=ADJACENT;FROM=${adjacentReader?.name ?: "NONE"};SOURCE=$kind;PHASE=$value;" +
                            ReaderComparison.compare(adjacent,read.sample).fixedCodes())
                        previous[kind]=read.sample; adjacent=read.sample; adjacentReader=kind
                        // A posted coroutine turn allows queued events; not a stability witness.
                        yield()
                    }
                    phases++
                    report("PHASE=$value;PHASE_STATUS=COMPLETED")
                }
                phase(ComparisonPhase.INITIAL)
                phase(ComparisonPhase.BEFORE_C)
                // A blocked baseline is an incomplete experiment, never permission to show C
                // using the last earlier successful read (especially an occlusion rejection).
                if (blocked!=0) throw ComparisonAbort(ComparisonIssue.BASELINE_UNAVAILABLE)
                val seed=lastReady ?: throw ComparisonAbort(ComparisonIssue.BASELINE_UNAVAILABLE)
                val signals=PopupCandidateDetector().detect(seed.frame)
                if (PopupSignal.NEW_WINDOW !in signals) throw ComparisonAbort(ComparisonIssue.TARGET_UNCONFIRMED)
                // Real tree evidence path. Screenshot/OCR ports are structurally uncallable.
                val pipeline=CaptureOcrPipeline(object: ScreenshotPort {
                    override fun availability(token: SnapshotToken)=CaptureAvailability.PRIVACY_BLOCKED
                    override fun denial(token: SnapshotToken)=EvidenceIssue.NOT_REQUESTED
                    override suspend fun capture(snapshot: PopupSnapshot,region: CaptureRegion): CaptureFrame = error("CAPTURE_FORBIDDEN")
                }, OcrPort { error("OCR_FORBIDDEN") }, { token ->
                    actual.checkLive(); token==seed.snapshot.token &&
                        SystemClock.uptimeMillis()-seed.snapshot.capturedAtUptimeMillis in 0 until 5_000
                }, {
                    val check=actual.read(ComparisonReader.COLLECTOR_FRESH,ComparisonPhase.BEFORE_C,++readNumber,closePath)
                    if (check.ready!=null && PrePresentationStability.sameExact(seed.snapshot,seed.nativeActions,
                            check.ready.snapshot,check.ready.nativeActions)) null else PrivacyBlockReason.UNREADABLE_TREE
                })
                val evidence=pipeline.analyze(seed.snapshot) as? EvidenceOutcome.Ready
                if (evidence==null) throw ComparisonAbort(ComparisonIssue.EVIDENCE_UNAVAILABLE)
                val detection=PopupDetection(seed.snapshot.token,signals.toPersistentSet(),true)
                val candidates=DecisionCloseCandidateFinder().find(seed.snapshot,detection)
                if (candidates.count { it.role==ButtonRole.CLOSE }!=1) throw ComparisonAbort(ComparisonIssue.TARGET_UNCONFIRMED)
                val analysis=evidence.analysis.copy(closeCandidates=candidates)
                val classification=DecisionPopupClassifier().classify(analysis)
                if (classification==PopupClassification.USER_DECISION) throw ComparisonAbort(ComparisonIssue.POLICY_BLOCKED)
                val prompt=ChoicePromptPlanner.plan(analysis,classification,moveToButtonEnabled=false,
                    rememberChoicesEnabled=false)
                // The same evidence/B work is done in A; only native C creation differs in B.
                if (condition==ComparisonCondition.WITH_C) {
                    if (actual.show(prompt,seed.snapshot)!=PromptShowResult.SHOWN)
                        throw ComparisonAbort(ComparisonIssue.C_NOT_SHOWN)
                } else report("PRESENT=NOT_CREATED;CLICK=NOT_WIRED")
                // Anchor AFTER present returns: a slow addView must not consume the intended
                // post-SHOWN 117–140ms observation interval. This still does not prove rendering.
                val boundary=SystemClock.uptimeMillis()
                report("BOUNDARY=AFTER_PRESENT_RETURN;RENDER=UNOBSERVED")
                phase(ComparisonPhase.AFTER_0)
                for ((at,p) in listOf(150L to ComparisonPhase.AFTER_150,300L to ComparisonPhase.AFTER_300)) {
                    val remaining=boundary+at-SystemClock.uptimeMillis()
                    if (remaining>0) delay(remaining)
                    report("PHASE=$p;BOUNDARY_ELAPSED_MS=${SystemClock.uptimeMillis()-boundary}")
                    phase(p)
                }
                // Do not retain DTOs after the bounded experiment.
                previous.clear(); adjacent=null; lastReady=null
            }
        } catch (failure: Throwable) {
            // Avoid JUnit printing raw framework exception messages or suppressed causes.
            primary=failure
            problem=(failure as? ComparisonAbort)?.issue ?: service?.issue?.takeUnless { it==ComparisonIssue.NONE }
                ?: if (failure is CancellationException) ComparisonIssue.CANCELLED else ComparisonIssue.UNEXPECTED_FAILURE
        } finally {
            var released=false
            if (armed) withContext(NonCancellable+Dispatchers.Main) {
                try { released=service?.finish()==true }
                catch (failure: Throwable) {
                    if (primary==null) primary=failure
                    if (problem==ComparisonIssue.NONE) problem=if (failure is CancellationException)
                        ComparisonIssue.CANCELLED else ComparisonIssue.CLEANUP_FAILED
                }
            }
            // Always attempt the separate fixture teardown, even when C teardown failed.
            if (armed) try { shell("am force-stop kr.closeforyou.popuptest") }
                catch (_: Exception) { released=false }
            if (problem==ComparisonIssue.NONE) problem=service?.issue ?: ComparisonIssue.SERVICE_UNAVAILABLE
            if (problem==ComparisonIssue.NONE && !released) problem=ComparisonIssue.CLEANUP_FAILED
            if (problem==ComparisonIssue.NONE && blocked>0) problem=ComparisonIssue.READ_UNAVAILABLE
            val received=service?.receiving==true
            val observed=comparisonComplete(problem,released,blocked,received,phases)
            report("SUMMARY=${if (observed) {
                if (expectedGuard>0) "OBSERVED_WITH_GUARD_BLOCK" else "OBSERVED"
            } else "INCONCLUSIVE"};ISSUE=$problem;SESSION=ENDED;COMPLETED_PHASES=$phases;"+
                "RECEPTION=${if(received) "CONFIRMED" else "UNCONFIRMED"};"+
                "COMPLETE_READS=$complete;UNAVAILABLE_READS=$blocked;EXPECTED_GUARD_BLOCKS=$expectedGuard;CLEANUP=${if(released) "CONFIRMED" else "UNCONFIRMED"};EXTERNAL_CLICK=NOT_WIRED")
            if (primary is CancellationException) throw CancellationException("COMPARISON_CANCELLED")
            assertTrue("COMPARISON_INCONCLUSIVE",observed && primary==null)
        }
    }
}
