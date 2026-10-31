package kr.closeforyou.accessibility

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.capture.*
import kr.closeforyou.app.CoreSettings
import kr.closeforyou.core.contract.CaptureAvailability
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.*
import org.junit.Test

/** Real exclusion controller and the same observation/delegation boundary used by the service.
 * Framework collection is a fake here; these tests do not certify Android/OEM delivery timing. */
@OptIn(ExperimentalCoroutinesApi::class)
class AEngineActivityTest {
    private val consent = CoreSettings(consentGranted = true, paused = false,
        allowedPackages = setOf("com.example.synthetic"))

    private class Source : EnabledEngineSource {
        var state = OtherEngineState.DISABLED
        var failRead = false
        var failRegister = false
        var failClose = false
        var reads = 0
        var duringRead: () -> Unit = {}
        val callbacks = mutableListOf<() -> Unit>()
        override fun observe(onChange: () -> Unit): AutoCloseable {
            if (failRegister) throw IllegalStateException()
            callbacks += onChange
            return AutoCloseable { if (failClose) throw IllegalStateException() }
        }
        override fun read(): OtherEngineState {
            reads++
            if (failRead) throw IllegalStateException()
            val result = state
            duringRead()
            return result
        }
        fun changed() = callbacks.last().invoke()
    }

    @Test fun settingsAloneOrPendingObservationNeverClaimsCollection() {
        val activity = AEngineActivity()
        activity.settingsChanged(consent)
        activity.engineObserved(OtherEngineState.DISABLED)
        assertEquals(AEngineState.PAUSED, activity.status.value.state)
        activity.connected(consent)
        assertEquals(AEngineState.PAUSED, activity.status.value.state)
        assertEquals(0L, activity.status.value.collectionAttempts)
    }

    @Test fun cOnOffOnShowsYieldRunYieldWithoutInventingReads() = runTest {
        val activity = AEngineActivity()
        val source = Source().apply { state = OtherEngineState.ENABLED }
        val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler), activity::engineObserved) {}
        activity.connected(consent)
        gate.start(this); runCurrent()
        assertEquals(AEngineState.YIELDING_TO_C, activity.status.value.state)
        assertFalse(gate.allowsCollection(consent))
        source.state = OtherEngineState.DISABLED; source.changed()
        assertEquals(AEngineState.PAUSED, activity.status.value.state)
        runCurrent()
        assertEquals(AEngineState.COLLECTING, activity.status.value.state)
        assertEquals(0L, activity.status.value.collectionAttempts)
        if (gate.allowsCollection(consent)) activity.collectionAttempt { }
        assertEquals(1L, activity.status.value.collectionAttempts)
        source.state = OtherEngineState.ENABLED; source.changed(); runCurrent()
        assertEquals(AEngineState.YIELDING_TO_C, activity.status.value.state)
        if (gate.allowsCollection(consent)) activity.collectionAttempt { fail("C ON must block") }
        assertEquals(1L, activity.status.value.collectionAttempts)
        assertEquals(3, source.reads) // UI/count observation never queries Android settings.
        gate.stop()
    }

    @Test fun consentPauseAndAllowedAppChangesPreventRunningStatus() = runTest {
        val activity = AEngineActivity()
        val gate = AEngineExclusion(Source(), StandardTestDispatcher(testScheduler), activity::engineObserved) {}
        activity.connected(consent); gate.start(this); runCurrent()
        for (blocked in listOf(consent.copy(consentGranted = false), consent.copy(paused = true),
            consent.copy(allowedPackages = emptySet()))) {
            activity.settingsChanged(blocked)
            assertEquals(AEngineState.PAUSED, activity.status.value.state)
            activity.settingsChanged(consent)
            assertEquals(AEngineState.COLLECTING, activity.status.value.state)
        }
        gate.stop()
    }

    @Test fun cOffWithConsentRevokedDoesNotClaimResumption() = runTest {
        val activity = AEngineActivity()
        val source = Source().apply { state = OtherEngineState.ENABLED }
        val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler), activity::engineObserved) {}
        activity.connected(consent); gate.start(this); runCurrent()
        val revoked = consent.copy(consentGranted = false, paused = true, allowedPackages = emptySet())
        activity.settingsChanged(revoked)
        source.state = OtherEngineState.DISABLED; source.changed(); runCurrent()
        assertEquals(AEngineState.PAUSED, activity.status.value.state)
        assertFalse(gate.allowsCollection(revoked))
        assertEquals(0L, activity.status.value.collectionAttempts)
        gate.stop()
    }

    @Test fun touchBlocksRunningStatusAcrossCOffUntilInteractionEnds() = runTest {
        val activity = AEngineActivity()
        val source = Source().apply { state = OtherEngineState.ENABLED }
        val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler), activity::engineObserved) {}
        activity.connected(consent); gate.start(this); runCurrent()
        activity.interactionChanged(true)
        assertEquals(AEngineState.YIELDING_TO_C, activity.status.value.state)
        source.state = OtherEngineState.DISABLED; source.changed(); runCurrent()
        assertEquals(AEngineState.PAUSED, activity.status.value.state)
        activity.interactionChanged(false)
        assertEquals(AEngineState.COLLECTING, activity.status.value.state)
        gate.stop()
    }

    @Test fun unknownAndReadFailureStayPausedWithoutPolling() = runTest {
        val activity = AEngineActivity()
        val source = Source()
        val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler), activity::engineObserved) {}
        activity.connected(consent); gate.start(this); runCurrent()
        source.state = OtherEngineState.UNKNOWN; source.changed(); runCurrent()
        assertEquals(AEngineState.PAUSED, activity.status.value.state)
        source.failRead = true; source.changed(); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(AEngineState.PAUSED, activity.status.value.state)
        assertEquals(3, source.reads)
        assertFalse(gate.engineAvailable)
        gate.stop()
    }

    @Test fun staleOffReadCannotPublishRunningStatus() = runTest {
        val activity = AEngineActivity()
        val source = Source()
        val observed = mutableListOf<OtherEngineState>()
        val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler), {
            observed += it; activity.engineObserved(it)
        }) {}
        source.duringRead = {
            source.duringRead = {}
            source.state = OtherEngineState.ENABLED
            source.changed()
        }
        activity.connected(consent); gate.start(this); runCurrent()
        assertFalse(OtherEngineState.DISABLED in observed)
        assertEquals(AEngineState.YIELDING_TO_C, activity.status.value.state)
        gate.stop()
    }

    @Test fun reconnectResetsCountButOldCallbacksCannotChangeNewStatus() = runTest {
        val activity = AEngineActivity()
        val source = Source()
        val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler), activity::engineObserved) {}
        activity.connected(consent); gate.start(this); runCurrent()
        activity.collectionAttempt { }
        val oldCallback = source.callbacks.single()
        activity.disconnected(); gate.stop()
        assertEquals(AEngineState.PAUSED, activity.status.value.state)
        assertEquals(1L, activity.status.value.collectionAttempts)
        activity.connected(consent); gate.start(this); runCurrent()
        oldCallback(); runCurrent()
        assertEquals(AEngineState.COLLECTING, activity.status.value.state)
        assertEquals(0L, activity.status.value.collectionAttempts)
        assertEquals(2, source.reads)
        activity.disconnected(); gate.stop()
        source.changed(); runCurrent()
        assertEquals(AEngineState.PAUSED, activity.status.value.state)
    }

    @Test fun registrationOrCleanupFailureDoesNotClaimRunningAfterRestart() = runTest {
        for (registerFailure in listOf(true, false)) {
            val activity = AEngineActivity()
            val source = Source().apply { failRegister = registerFailure; failClose = !registerFailure }
            val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler), activity::engineObserved) {}
            activity.connected(consent); gate.start(this); runCurrent()
            activity.disconnected(); gate.stop()
            source.failRegister = false; source.failClose = false
            activity.connected(consent); gate.start(this); runCurrent()
            assertEquals(AEngineState.PAUSED, activity.status.value.state)
            assertFalse(gate.engineAvailable)
            gate.stop()
        }
    }

    @Test fun observerFailureCannotChangeGateRevocationOrReopening() = runTest {
        val source = Source()
        var invalidated = 0
        val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler), { throw IllegalStateException() }) {
            invalidated++
        }
        gate.start(this); runCurrent()
        assertTrue(gate.engineAvailable)
        val previous = invalidated
        source.state = OtherEngineState.ENABLED; source.changed(); runCurrent()
        assertFalse(gate.engineAvailable)
        assertEquals(previous + 1, invalidated)
        source.state = OtherEngineState.DISABLED; source.changed(); runCurrent()
        assertTrue(gate.engineAvailable)
        gate.stop()
        assertFalse(gate.engineAvailable)
    }

    @Test fun enablingCCancelsScheduledReadWithoutIncrementingAttempts() = runTest {
        val activity = AEngineActivity()
        val source = Source()
        var pending: Job? = null
        val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler), activity::engineObserved) {
            pending?.cancel()
        }
        activity.connected(consent); gate.start(this); runCurrent()
        pending = launch { delay(180); if (gate.allowsCollection(consent)) activity.collectionAttempt { } }
        runCurrent()
        source.state = OtherEngineState.ENABLED; source.changed(); advanceUntilIdle()
        assertTrue(pending!!.isCancelled)
        assertEquals(0L, activity.status.value.collectionAttempts)
        assertEquals(AEngineState.YIELDING_TO_C, activity.status.value.state)
        gate.stop()
    }

    @Test fun actualReadWithZeroOcrAndRevalidationEachCountExactlyOnce() {
        val activity = AEngineActivity()
        activity.connected(consent)
        var calls = 0
        // Zero image-text regions is still a read, not an absent collection attempt.
        val ocrRegions = activity.collectionAttempt { calls++; 0 }
        assertEquals(0, ocrRegions)
        assertEquals(1L, activity.status.value.collectionAttempts)
        repeat(3) { activity.collectionAttempt { calls++ } }
        assertEquals(4, calls)
        assertEquals(4L, activity.status.value.collectionAttempts)
    }

    @Test fun blockedReadIsCountedAndDoesNotBecomeSuccessfulEvidence() {
        val activity = AEngineActivity()
        activity.connected(consent)
        assertEquals(PrivacyBlockReason.PROTECTED_APP,
            activity.collectionAttempt { PrivacyBlockReason.PROTECTED_APP })
        assertEquals(1L, activity.status.value.collectionAttempts)
        assertEquals(AEngineState.PAUSED, activity.status.value.state) // Count grants no authority.
    }

    @Test fun realEvidencePipelineCanFinishWithZeroOcrWhileF0AttemptsRemainVisible() = runTest {
        val activity = AEngineActivity()
        activity.connected(consent)
        val gate = AEngineExclusion(Source(), StandardTestDispatcher(testScheduler), activity::engineObserved) {}
        gate.start(this); runCurrent()
        val snapshot = activity.collectionAttempt { MockPopupFixtures.notice().snapshot }
        val shots = object : ScreenshotPort {
            override fun availability(token: SnapshotToken) = CaptureAvailability.PRIVACY_BLOCKED
            override fun denial(token: SnapshotToken) = EvidenceIssue.NOT_REQUESTED
            override suspend fun capture(snapshot: PopupSnapshot, region: CaptureRegion): CaptureFrame =
                error("No screenshot consent; must not request pixels")
        }
        var validations = 0
        val pipeline = CaptureOcrPipeline(shots, OcrPort { error("OCR must not run") },
            { gate.allowsCollection(consent) }, {
                activity.collectionAttempt { validations++; null }
            })
        val result = pipeline.analyze(snapshot) as EvidenceOutcome.Ready
        assertEquals(0, result.analysis.texts.count { it.source == TextSource.OCR })
        assertEquals(1, validations)
        assertEquals(2L, activity.status.value.collectionAttempts) // Initial + real pipeline preflight.
        assertEquals(AEngineState.COLLECTING, activity.status.value.state)
        gate.stop()
    }

    @Test fun cancellationAndFailureAreNotSwallowedAndCleanupStillRuns() {
        val activity = AEngineActivity()
        activity.connected(consent)
        var released = 0
        for (failure in listOf(CancellationException(), IllegalStateException())) {
            try {
                activity.collectionAttempt {
                    try { throw failure } finally { released++ }
                }
                fail("Must propagate original failure")
            } catch (actual: Exception) { assertSame(failure, actual) }
        }
        assertEquals(2, released)
        assertEquals(2L, activity.status.value.collectionAttempts)
    }
}
