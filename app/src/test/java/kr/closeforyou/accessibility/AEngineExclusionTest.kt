package kr.closeforyou.accessibility

import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.app.CoreSettings
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AEngineExclusionTest {
    private val consent = CoreSettings(consentGranted = true, paused = false, allowedPackages = setOf("com.example.target"))

    private class Source : EnabledEngineSource {
        var state = OtherEngineState.DISABLED
        var reads = 0
        var registrations = 0
        var closed = 0
        var failRead = false
        var failRegister = false
        var failClose = false
        var duringRead: () -> Unit = {}
        val callbacks = mutableListOf<() -> Unit>()
        override fun observe(onChange: () -> Unit): AutoCloseable {
            if (failRegister) throw SecurityException("PRIVATE_FIXTURE")
            registrations++
            callbacks += onChange
            return AutoCloseable { closed++; if (failClose) throw SecurityException("PRIVATE_FIXTURE") }
        }
        override fun read(): OtherEngineState {
            reads++
            if (failRead) throw SecurityException("PRIVATE_FIXTURE")
            val result = state
            duringRead()
            return result
        }
        fun changed() = callbacks.last().invoke()
    }

    @Test fun disconnectedOrFirstQueryPendingCannotCollect() = runTest {
        val source = Source()
        val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler)) {}
        assertFalse(gate.allowsCollection(consent))
        gate.start(this)
        assertFalse(gate.allowsCollection(consent))
        assertEquals(0, source.reads)
        runCurrent()
        assertTrue(gate.allowsCollection(consent))
        gate.stop()
        assertFalse(gate.allowsCollection(consent))
    }

    @Test fun cEnabledAndUnknownBlockEvenWithAConsent() = runTest {
        for (state in listOf(OtherEngineState.ENABLED, OtherEngineState.UNKNOWN)) {
            val source = Source().apply { this.state = state }
            val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler)) {}
            gate.start(this); runCurrent()
            assertFalse(gate.allowsCollection(consent))
            gate.stop()
        }
    }

    @Test fun changeRevokesSynchronouslyBeforeQueryAndNeverRestoresOldEpoch() = runTest {
        val source = Source()
        val freshness = ScreenFreshness()
        var token: Long? = null
        var baseline: String? = null
        var pending: Job? = null
        var evidence: Job? = null
        var expired: Job? = null
        var finished = 0
        val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler)) {
            pending?.cancel(); evidence?.cancel(); expired?.cancel()
            token = null; baseline = null; freshness.invalidate()
        }
        gate.start(this); runCurrent()
        val old = freshness.version
        token = old; baseline = "SYNTHETIC"
        pending = launch { delay(180); finished++ }
        evidence = launch { awaitCancellation() }
        expired = launch { delay(5000); finished++ }
        runCurrent()
        source.state = OtherEngineState.ENABLED
        source.changed()
        assertFalse(gate.engineAvailable)
        assertNull(token); assertNull(baseline)
        assertFalse(freshness.isCurrent(old))
        assertTrue(pending!!.isCancelled && evidence!!.isCancelled && expired!!.isCancelled)
        source.state = OtherEngineState.DISABLED
        source.changed()
        advanceUntilIdle()
        assertTrue(gate.allowsCollection(consent))
        assertNull(token); assertNull(baseline)
        assertEquals(0, finished)
        gate.stop()
    }

    @Test fun cOffDoesNotOverrideConsentOrPauseOrStartARead() = runTest {
        val source = Source().apply { state = OtherEngineState.ENABLED }
        var work = 0
        val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler)) {}
        fun newEvent(settings: CoreSettings) { if (gate.allowsCollection(settings)) work++ }
        gate.start(this); runCurrent()
        newEvent(consent)
        source.state = OtherEngineState.DISABLED; source.changed(); runCurrent()
        assertEquals(0, work)
        newEvent(consent.copy(consentGranted = false))
        newEvent(consent.copy(paused = true))
        assertEquals(0, work)
        newEvent(consent)
        assertEquals(1, work)
        gate.stop()
    }

    @Test fun readFailureClosesGateAndNoPollingRetriesIt() = runTest {
        val source = Source()
        val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler)) {}
        gate.start(this); runCurrent()
        source.failRead = true; source.changed(); runCurrent()
        assertFalse(gate.engineAvailable)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, source.reads)
        source.failRead = false; source.changed(); runCurrent()
        assertTrue(gate.engineAvailable)
        gate.stop()
    }

    @Test fun supersededQueryCannotReopenGateAndNotificationsCoalesce() = runTest {
        val source = Source()
        val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler)) {}
        source.duringRead = {
            source.duringRead = {}
            source.state = OtherEngineState.ENABLED
            repeat(20) { source.changed() }
        }
        gate.start(this); runCurrent()
        assertFalse(gate.engineAvailable)
        assertEquals(2, source.reads)
        gate.stop()
    }

    @Test fun reconnectAndRepeatedStopReleaseEachSubscriptionAndRejectOldCallback() = runTest {
        val source = Source()
        var revokes = 0
        val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler)) { revokes++ }
        gate.start(this); runCurrent()
        val oldCallback = source.callbacks.first()
        gate.start(this); runCurrent()
        assertEquals(2, source.registrations)
        assertEquals(1, source.closed)
        val before = revokes
        oldCallback(); runCurrent()
        assertEquals(before, revokes)
        assertEquals(2, source.reads)
        gate.stop(); gate.stop()
        assertEquals(2, source.closed)
        val reads = source.reads
        source.changed(); runCurrent()
        assertEquals(reads, source.reads)
        assertFalse(gate.engineAvailable)
    }

    @Test fun registrationOrUnregisterFailureNeverReopensServiceInstance() = runTest {
        for (register in listOf(true, false)) {
            val source = Source().apply { failRegister = register; failClose = !register }
            val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler)) {}
            gate.start(this); runCurrent(); gate.stop()
            source.failRegister = false; source.failClose = false
            gate.start(this); runCurrent()
            assertFalse(gate.engineAvailable)
            assertEquals(if (register) 0 else 1, source.registrations)
            gate.stop()
        }
    }

    @Test fun cancellationBeforeQueryNeverEnablesCollection() = runTest {
        val source = Source()
        val service = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gate = AEngineExclusion(source, StandardTestDispatcher(testScheduler)) {}
        gate.start(service)
        gate.stop(); service.cancel(); runCurrent()
        assertEquals(0, source.reads)
        assertFalse(gate.engineAvailable)
    }

    @Test fun slowWorkerReadDoesNotBlockEventThreadAndLateResultAfterStopIsDiscarded() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val eventThread = Thread.currentThread()
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val source = object : EnabledEngineSource {
            override fun observe(onChange: () -> Unit) = AutoCloseable { }
            override fun read(): OtherEngineState {
                assertNotSame(eventThread, Thread.currentThread())
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return OtherEngineState.DISABLED
            }
        }
        val gate = AEngineExclusion(source, worker) {}
        try {
            gate.start(this); runCurrent()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(gate.engineAvailable)
            gate.stop()
            release.countDown()
        } finally { release.countDown(); gate.stop(); worker.close() }
        advanceUntilIdle()
        assertFalse(gate.engineAvailable)
    }
}
