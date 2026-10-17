package kr.closeforyou.accessibility

/** In-memory callback facts only. Never an authorization or a replacement for Runtime.connected. */
internal enum class ServiceLifecyclePhase { NOT_CREATED, CREATED, CONNECTING, CONNECTED, UNBOUND, DESTROYED, INITIALIZATION_FAILED }

internal data class ServiceLifecycleSnapshot(
    val phase: ServiceLifecyclePhase = ServiceLifecyclePhase.NOT_CREATED,
    val creates: Int = 0,
    val connections: Int = 0,
    val ownerPid: Int? = null,
    val loaderMatchesRuntime: Boolean = false,
    val callbackOnMain: Boolean = false,
)

internal class ServiceLifecycleMonitor {
    private var state = ServiceLifecycleSnapshot()
    @Synchronized fun snapshot(): ServiceLifecycleSnapshot = state
    @Synchronized fun created(pid: Int, loaderMatchesRuntime: Boolean, onMain: Boolean) {
        state = state.copy(phase = ServiceLifecyclePhase.CREATED, creates = state.creates + 1,
            ownerPid = pid, loaderMatchesRuntime = loaderMatchesRuntime, callbackOnMain = onMain)
    }
    @Synchronized fun connecting(onMain: Boolean) {
        state = state.copy(phase = ServiceLifecyclePhase.CONNECTING, callbackOnMain = onMain)
    }
    @Synchronized fun phase(phase: ServiceLifecyclePhase) {
        state = state.copy(phase = phase, connections = state.connections + if (phase == ServiceLifecyclePhase.CONNECTED) 1 else 0)
    }
}
