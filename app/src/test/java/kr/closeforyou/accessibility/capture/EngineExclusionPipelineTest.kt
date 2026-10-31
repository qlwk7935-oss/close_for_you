package kr.closeforyou.accessibility.capture

import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.*
import kr.closeforyou.app.CoreSettings
import kr.closeforyou.core.contract.CaptureAvailability
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.*
import org.junit.Test

/** Real exclusion lifecycle + existing evidence pipeline; fake pixels/OCR, no Android execution. */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineExclusionPipelineTest {
    private class Rig(scope: TestScope) {
        var state = OtherEngineState.DISABLED
        var changed: () -> Unit = {}
        var settings = CoreSettings(consentGranted = true, paused = false)
        val freshness = ScreenFreshness()
        var token: SnapshotToken? = null
        var job: Job? = null
        var captures = 0
        var closed = 0
        var recognized = 0
        var afterCapture: () -> Unit = {}
        var recognize: suspend () -> Unit = {}
        val gate = AEngineExclusion(object : EnabledEngineSource {
            override fun read() = state
            override fun observe(onChange: () -> Unit): AutoCloseable {
                changed = onChange
                return AutoCloseable { }
            }
        }, StandardTestDispatcher(scope.testScheduler)) {
            job?.cancel()
            freshness.invalidate()
            token = null
        }
        fun current(candidate: SnapshotToken) = gate.allowsCollection(settings) &&
            token == candidate && freshness.isCurrent(candidate.screenVersion)
        val shots = object : ScreenshotPort {
            override fun availability(token: SnapshotToken) = if (current(token)) CaptureAvailability.AVAILABLE else CaptureAvailability.PRIVACY_BLOCKED
            override fun denial(token: SnapshotToken) = if (current(token)) null else EvidenceIssue.SERVICE_DISABLED
            override suspend fun capture(snapshot: PopupSnapshot, region: CaptureRegion): CaptureFrame {
                check(current(snapshot.token))
                captures++
                val frame = object : CaptureFrame {
                    override val bounds = region.bounds
                    override fun close() { closed++ }
                }
                afterCapture()
                return frame
            }
        }
        val pipeline = CaptureOcrPipeline(shots, OcrPort {
            recognized++
            recognize()
            listOf(OcrLine("점검 공지", ScreenBounds(100, 500, 900, 560)))
        }, ::current, { if (gate.allowsCollection(settings)) null else PrivacyBlockReason.PAUSED })
        fun snapshot(): PopupSnapshot {
            val base = MockPopupFixtures.notice().snapshot
            return base.copy(token = base.token.copy(screenVersion = freshness.version, captureEpoch = freshness.version),
                nodes = base.nodes.toPersistentList().add(NodeSnapshot(
                    "0/image", null, "android.widget.ImageView", null, null, false, true,
                    ScreenBounds(100, 500, 950, 1300),
                )))
        }
    }

    @Test fun cAlreadyEnabledStopsBeforeScreenCaptureAndOcr() = runTest {
        val r = Rig(this).apply { state = OtherEngineState.ENABLED }
        r.gate.start(this); runCurrent()
        val snapshot = r.snapshot(); r.token = snapshot.token
        val result = async { r.pipeline.analyze(snapshot) }
        runCurrent()
        assertTrue(result.isCancelled)
        assertEquals(0, r.captures); assertEquals(0, r.recognized)
        assertEquals(CaptureAvailability.PRIVACY_BLOCKED, r.shots.availability(snapshot.token))
        r.gate.stop()
    }

    @Test fun enablingCDuringOcrCancelsEvidenceReleasesFrameAndRevokesToken() = runTest {
        val r = Rig(this)
        r.gate.start(this); runCurrent()
        val snapshot = r.snapshot(); r.token = snapshot.token
        r.recognize = { awaitCancellation() }
        val result = async { r.pipeline.analyze(snapshot) }.also { r.job = it }
        runCurrent()
        assertEquals(1, r.captures); assertEquals(1, r.recognized)
        r.state = OtherEngineState.ENABLED; r.changed(); runCurrent()
        assertTrue(result.isCancelled)
        assertEquals(1, r.closed)
        assertNull(r.token)
        assertFalse(r.current(snapshot.token))
        r.gate.stop()
    }

    @Test fun cChangeAtCaptureReturnStopsOcrAndReleasesReturnedFrame() = runTest {
        val r = Rig(this)
        r.gate.start(this); runCurrent()
        val snapshot = r.snapshot(); r.token = snapshot.token
        r.afterCapture = { r.state = OtherEngineState.ENABLED; r.changed() }
        val result = async { r.pipeline.analyze(snapshot) }.also { r.job = it }
        runCurrent()
        assertTrue(result.isCancelled)
        assertEquals(1, r.captures); assertEquals(0, r.recognized); assertEquals(1, r.closed)
        r.gate.stop()
    }

    @Test fun completedEvidenceCannotPublishOrCaptureAgainAfterCOnOff() = runTest {
        val r = Rig(this)
        r.gate.start(this); runCurrent()
        val snapshot = r.snapshot(); r.token = snapshot.token
        val result = r.pipeline.analyze(snapshot)
        assertTrue(result is EvidenceOutcome.Ready)
        r.state = OtherEngineState.ENABLED; r.changed(); runCurrent()
        r.state = OtherEngineState.DISABLED; r.changed(); runCurrent()
        assertTrue(r.gate.engineAvailable)
        assertFalse(r.current(snapshot.token))
        assertNull(r.token)
        assertEquals(CaptureAvailability.PRIVACY_BLOCKED, r.shots.availability(snapshot.token))
        // Same final gate used at service publish: C OFF never restores the old runtime token.
        var published = 0
        if (r.current(snapshot.token)) published++
        assertEquals(0, published)
        val fresh = r.snapshot(); r.token = fresh.token
        assertNotEquals(snapshot.token.screenVersion, fresh.token.screenVersion)
        assertTrue(r.pipeline.analyze(fresh) is EvidenceOutcome.Ready)
        assertEquals(2, r.captures); assertEquals(2, r.closed)
        r.gate.stop()
    }

    @Test fun returningOcrResultIsRejectedEvenWhenNativeWorkFinishesAfterCancellation() = runTest {
        val r = Rig(this)
        val release = CompletableDeferred<Unit>()
        r.gate.start(this); runCurrent()
        val snapshot = r.snapshot(); r.token = snapshot.token
        r.recognize = { withContext(NonCancellable) { release.await() } }
        val result = async { r.pipeline.analyze(snapshot) }.also { r.job = it }
        runCurrent()
        r.state = OtherEngineState.ENABLED; r.changed(); runCurrent()
        r.state = OtherEngineState.DISABLED; r.changed(); runCurrent()
        release.complete(Unit); runCurrent()
        assertTrue(result.isCancelled)
        assertEquals(1, r.closed)
        assertNull(r.token)
        r.gate.stop()
    }
}
