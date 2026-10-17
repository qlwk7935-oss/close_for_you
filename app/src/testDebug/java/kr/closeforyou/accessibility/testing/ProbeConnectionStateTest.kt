package kr.closeforyou.accessibility.testing

import kr.closeforyou.accessibility.*
import kr.closeforyou.app.CoreSettings
import org.junit.Assert.*
import org.junit.Test

class ProbeConnectionStateTest {
    private val connected = ServiceLifecycleSnapshot(ServiceLifecyclePhase.CONNECTED, 1, 1, 42, true, true)
    private fun check(state: ServiceLifecycleSnapshot = connected, enabled: Boolean = true,
        runtime: Boolean = true, pid: Int = 42, main: Boolean = true) = connectionCode(enabled, runtime, state, pid, main)

    @Test fun readyRequiresTheActualCallbackAndTheRuntimeTogether() {
        assertEquals(ProbeConnectionCode.READY, check())
        assertEquals(ProbeConnectionCode.SYSTEM_NOT_ENABLED, check(enabled = false))
        assertEquals(ProbeConnectionCode.RUNTIME_DISCONNECTED, check(runtime = false))
        assertEquals(ProbeConnectionCode.CALLBACK_NOT_OBSERVED, check(ServiceLifecycleSnapshot()))
    }

    @Test fun frameworkEnabledCannotPromoteAnUninitializedOrDisconnectedService() {
        val cases = mapOf(
            ServiceLifecyclePhase.CREATED to ProbeConnectionCode.SERVICE_CREATED_NOT_CONNECTED,
            ServiceLifecyclePhase.CONNECTING to ProbeConnectionCode.SERVICE_INITIALIZING,
            ServiceLifecyclePhase.UNBOUND to ProbeConnectionCode.SERVICE_UNBOUND,
            ServiceLifecyclePhase.DESTROYED to ProbeConnectionCode.SERVICE_DESTROYED,
            ServiceLifecyclePhase.INITIALIZATION_FAILED to ProbeConnectionCode.SERVICE_INITIALIZATION_FAILED,
        )
        cases.forEach { (phase, expected) -> assertEquals(expected, check(connected.copy(phase = phase))) }
    }

    @Test fun processAndLoaderMismatchCannotBecomeReadyEvenWithConnectedTrue() {
        assertEquals(ProbeConnectionCode.PROCESS_MISMATCH, check(pid = 99))
        assertEquals(ProbeConnectionCode.CLASSLOADER_MISMATCH, check(connected.copy(loaderMatchesRuntime = false)))
    }

    @Test fun callbackThreadAndMainHeartbeatFailuresAreExplicit() {
        assertEquals(ProbeConnectionCode.CALLBACK_THREAD_MISMATCH, check(connected.copy(callbackOnMain = false)))
        assertEquals(ProbeConnectionCode.MAIN_LOOP_UNRESPONSIVE, check(main = false))
    }

    @Test fun lifecycleCreationDoesNotInventAConnectionAndDestructionIsObserved() {
        val monitor = ServiceLifecycleMonitor()
        assertEquals(ServiceLifecyclePhase.NOT_CREATED, monitor.snapshot().phase)
        monitor.created(42, true, true)
        assertEquals(ProbeConnectionCode.SERVICE_CREATED_NOT_CONNECTED, check(monitor.snapshot()))
        assertEquals(0, monitor.snapshot().connections)
        monitor.phase(ServiceLifecyclePhase.CONNECTING)
        assertEquals(ProbeConnectionCode.SERVICE_INITIALIZING, check(monitor.snapshot()))
        monitor.phase(ServiceLifecyclePhase.CONNECTED)
        assertEquals(1, monitor.snapshot().connections)
        monitor.phase(ServiceLifecyclePhase.UNBOUND)
        assertEquals(ProbeConnectionCode.SERVICE_UNBOUND, check(monitor.snapshot()))
        monitor.phase(ServiceLifecyclePhase.DESTROYED)
        assertEquals(ProbeConnectionCode.SERVICE_DESTROYED, check(monitor.snapshot()))
        monitor.created(42, true, true)
        assertEquals(2, monitor.snapshot().creates)
        assertEquals(ProbeConnectionCode.SERVICE_CREATED_NOT_CONNECTED, check(monitor.snapshot()))
    }

    @Test fun syntheticCaptureRequiresAllExistingUserChoicesAndOnlyTheFixedHost() {
        val host = setOf("kr.closeforyou.capturetest")
        val ready = CoreSettings(true, false, host, true, host)
        assertTrue(syntheticSettingsReady(ready))
        for (state in listOf(CoreSettings(), ready.copy(consentGranted = false), ready.copy(paused = true),
            ready.copy(captureConsentGranted = false), ready.copy(allowedPackages = emptySet()),
            ready.copy(captureAllowedPackages = emptySet()), ready.copy(allowedPackages = host + "org.example.synthetic"),
            ready.copy(captureAllowedPackages = host + "org.example.synthetic"))) {
            assertFalse(syntheticSettingsReady(state))
        }
        assertEquals(host, ready.allowedPackages)
        assertFalse(ready.paused)
    }

}
