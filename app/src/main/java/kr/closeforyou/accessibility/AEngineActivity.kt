package kr.closeforyou.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.closeforyou.app.CoreSettings

internal enum class AEngineState { YIELDING_TO_C, COLLECTING, PAUSED }

internal data class AEngineActivityStatus(
    val state: AEngineState = AEngineState.PAUSED,
    val collectionAttempts: Long = 0,
)

/** Read-only developer observation, never an admission gate or execution authority.
 * Service-main writes only. COLLECTING includes waiting for a new eligible event;
 * per-app privacy/freshness checks still decide whether any read may actually begin.
 * Counts all F0 invocations (including evidence revalidation and blocked/failed reads),
 * independently of OCR output. Reset on an actual A service connection, not C ON/OFF.
 * Stores only booleans, enums and a count; no app identity, token, nodes or screen content. */
internal class AEngineActivity {
    private val mutableStatus = MutableStateFlow(AEngineActivityStatus())
    val status = mutableStatus.asStateFlow()
    private var connected = false
    private var otherEngine = OtherEngineState.UNKNOWN
    private var collectionConfigured = false
    private var touching = false

    fun connected(settings: CoreSettings) {
        connected = true
        otherEngine = OtherEngineState.UNKNOWN
        touching = false
        mutableStatus.value = AEngineActivityStatus()
        settingsChanged(settings)
    }

    fun disconnected() {
        connected = false
        otherEngine = OtherEngineState.UNKNOWN
        refresh()
    }

    fun engineObserved(state: OtherEngineState) {
        otherEngine = state
        refresh()
    }

    fun settingsChanged(settings: CoreSettings) {
        collectionConfigured = settings.collectionEnabled && settings.allowedPackages.isNotEmpty()
        refresh()
    }

    fun interactionChanged(inProgress: Boolean) {
        touching = inProgress
        refresh()
    }

    /** Called only at the service's real collector.capture boundaries, after existing gates.
     * Always delegates exactly once; does not catch cancellation/errors or alter the result. */
    fun <T> collectionAttempt(read: () -> T): T {
        val current = mutableStatus.value
        mutableStatus.value = current.copy(collectionAttempts =
            if (current.collectionAttempts == Long.MAX_VALUE) Long.MAX_VALUE else current.collectionAttempts + 1)
        return read()
    }

    private fun refresh() {
        val state = when {
            !connected -> AEngineState.PAUSED
            otherEngine == OtherEngineState.ENABLED -> AEngineState.YIELDING_TO_C
            otherEngine != OtherEngineState.DISABLED || !collectionConfigured || touching -> AEngineState.PAUSED
            else -> AEngineState.COLLECTING
        }
        mutableStatus.value = mutableStatus.value.copy(state = state)
    }
}
