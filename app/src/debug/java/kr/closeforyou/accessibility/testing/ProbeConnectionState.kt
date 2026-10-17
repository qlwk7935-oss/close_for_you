package kr.closeforyou.accessibility.testing

import kr.closeforyou.accessibility.ServiceLifecyclePhase
import kr.closeforyou.accessibility.ServiceLifecycleSnapshot
import kr.closeforyou.app.CoreSettings

internal enum class ProbeConnectionCode {
    READY, SYSTEM_NOT_ENABLED, CALLBACK_NOT_OBSERVED, SERVICE_CREATED_NOT_CONNECTED,
    SERVICE_INITIALIZING, SERVICE_UNBOUND, SERVICE_DESTROYED, SERVICE_INITIALIZATION_FAILED,
    RUNTIME_DISCONNECTED, PROCESS_MISMATCH, CLASSLOADER_MISMATCH, CALLBACK_THREAD_MISMATCH, MAIN_LOOP_UNRESPONSIVE,
}

/** Neither system-enabled nor framework-bound alone is proof of a usable app connection. */
internal fun connectionCode(enabled: Boolean, runtimeConnected: Boolean, lifecycle: ServiceLifecycleSnapshot,
    probePid: Int, mainResponsive: Boolean): ProbeConnectionCode = when {
    !enabled -> ProbeConnectionCode.SYSTEM_NOT_ENABLED
    lifecycle.phase == ServiceLifecyclePhase.NOT_CREATED -> ProbeConnectionCode.CALLBACK_NOT_OBSERVED
    lifecycle.ownerPid != probePid -> ProbeConnectionCode.PROCESS_MISMATCH
    !lifecycle.loaderMatchesRuntime -> ProbeConnectionCode.CLASSLOADER_MISMATCH
    !lifecycle.callbackOnMain -> ProbeConnectionCode.CALLBACK_THREAD_MISMATCH
    lifecycle.phase == ServiceLifecyclePhase.CREATED -> ProbeConnectionCode.SERVICE_CREATED_NOT_CONNECTED
    lifecycle.phase == ServiceLifecyclePhase.CONNECTING -> ProbeConnectionCode.SERVICE_INITIALIZING
    lifecycle.phase == ServiceLifecyclePhase.UNBOUND -> ProbeConnectionCode.SERVICE_UNBOUND
    lifecycle.phase == ServiceLifecyclePhase.DESTROYED -> ProbeConnectionCode.SERVICE_DESTROYED
    lifecycle.phase == ServiceLifecyclePhase.INITIALIZATION_FAILED -> ProbeConnectionCode.SERVICE_INITIALIZATION_FAILED
    !runtimeConnected -> ProbeConnectionCode.RUNTIME_DISCONNECTED
    !mainResponsive -> ProbeConnectionCode.MAIN_LOOP_UNRESPONSIVE
    else -> ProbeConnectionCode.READY
}

internal fun syntheticSettingsReady(state: CoreSettings): Boolean =
    state.collectionEnabled && state.captureConsentGranted &&
        state.allowedPackages == setOf("kr.closeforyou.capturetest") &&
        state.captureAllowedPackages == setOf("kr.closeforyou.capturetest")
