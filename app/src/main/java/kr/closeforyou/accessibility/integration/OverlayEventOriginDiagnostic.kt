package kr.closeforyou.accessibility.integration

import kr.closeforyou.accessibility.diagnosticWrite

internal enum class OverlayEventIdRelation {
    CURRENT_C_ID_MATCH, CURRENT_TARGET_ID_MATCH, PREVIOUS_C_PENDING_ID_MATCH,
    PREVIOUS_C_ACKED_ID_MATCH, AMBIGUOUS_OR_REUSED, UNMATCHED, UNKNOWN,
}
internal enum class OverlayEventTimeRelation { BEFORE_CURRENT_SHOW, AFTER_CURRENT_SHOW, UNKNOWN }
internal enum class OverlayCleanupPoint {
    C_BIND_CONFIRMED, C_RETIRE_INTENT, C_REMOVE_RETURNED, C_REMOVE_EVENT_HANDLED,
    C_CLEANUP_CONFIRMED, C_SHOWN, C_NATIVE_DETACHED,
    C_NATIVE_WAITING_ATTACH, C_NATIVE_WAITING_ID, C_NATIVE_ID_RESOLVED,
}

/** Optional Debug-only assembly instance, never an authorization source. One retired identity,
 * scoped to this service and the immediately following presentation. No Android handles, token,
 * package, text or raw event is retained; only fixed codes leave this object. ID matches cannot
 * exclude unobserved ABA reuse because AccessibilityEvent has no C generation.
 */
internal class OverlayEventOriginDiagnostic(
    private val clock: () -> Long,
    private val write: (OverlayCleanupPoint) -> Unit,
) {
    private class Previous(val id: Int, val generation: Long, var acked: Boolean = false) {
        var returned = false
        var handled = false
        var ambiguous = false
    }
    private var previous: Previous? = null
    private var generation = 0L
    private var target: Int? = null
    private var bound: Int? = null
    private var ambiguous = false
    private var showStart: Long? = null
    private var showEnd: Long? = null
    private var disabled = false

    fun beforeShow() {
        showStart = now(); showEnd = null
    }

    fun shown(currentGeneration: Long, targetId: Int, ownedBefore: Set<Int>?) = diagnosticWrite {
        if (disabled) return@diagnosticWrite
        // Retain a predecessor for ONE successor only, never a growing ID history. Keeping a
        // pending record also makes an unexpected presentation during cleanup diagnosable.
        previous?.let { if (generation != it.generation) previous = null }
        generation = currentGeneration; target = targetId; bound = null; ambiguous = false
        showEnd = now()
        previous?.let { old ->
            if (currentGeneration <= old.generation || old.id == targetId || ownedBefore?.contains(old.id) == true)
                old.ambiguous = true
        }
        if (currentGeneration <= 0 || targetId < 0) { uncertain(); return@diagnosticWrite }
        emit(OverlayCleanupPoint.C_SHOWN)
    }

    fun bound(id: Int, currentGeneration: Long) = diagnosticWrite {
        if (disabled || currentGeneration != generation || id < 0) return@diagnosticWrite
        if (bound != null && bound != id) ambiguous = true
        if (id == target) ambiguous = true
        previous?.let { if (it.id == id) it.ambiguous = true }
        if (bound == null) { bound = id; emit(OverlayCleanupPoint.C_BIND_CONFIRMED) }
    }

    // Called inside the original owned-window loop using its existing ID read, never a new query.
    fun ownedId(id: Int, duplicate: Boolean) = diagnosticWrite {
        if (disabled) return@diagnosticWrite
        if (duplicate) ambiguous = true
        previous?.let { if (it.acked && it.id == id && generation != it.generation) it.ambiguous = true }
    }

    fun retiring(id: Int, currentGeneration: Long, nativeIdentity: Boolean = false) = diagnosticWrite {
        if (disabled) return@diagnosticWrite
        if (generation != currentGeneration || (bound != id && !(nativeIdentity && bound == null)) || ambiguous || id == target) {
            uncertain(); return@diagnosticWrite
        }
        val reused = previous?.let { it.id == id || it.ambiguous } == true
        previous = Previous(id, currentGeneration).also { it.ambiguous = reused }
        bound = null
        emit(OverlayCleanupPoint.C_RETIRE_INTENT)
    }

    fun removalReturned() = diagnosticWrite {
        if (disabled) return@diagnosticWrite
        previous?.let { if (!it.returned) { it.returned = true; emit(OverlayCleanupPoint.C_REMOVE_RETURNED) } }
    }

    fun removalHandled() = diagnosticWrite {
        if (disabled) return@diagnosticWrite
        previous?.let { if (!it.handled) { it.handled = true; emit(OverlayCleanupPoint.C_REMOVE_EVENT_HANDLED) } }
    }

    fun nativeRemovalHandled() = diagnosticWrite {
        if (disabled) return@diagnosticWrite
        previous?.let { it.handled = true; emit(OverlayCleanupPoint.C_NATIVE_DETACHED) }
    }

    fun nativeIdentityPending(currentGeneration: Long, beforeAttach: Boolean) = diagnosticWrite {
        if (disabled || generation != currentGeneration) return@diagnosticWrite
        emit(if (beforeAttach) OverlayCleanupPoint.C_NATIVE_WAITING_ATTACH else OverlayCleanupPoint.C_NATIVE_WAITING_ID)
    }

    fun nativeIdentityResolved(id: Int, currentGeneration: Long) = diagnosticWrite {
        retiring(id, currentGeneration, nativeIdentity = true)
        if (!disabled) emit(OverlayCleanupPoint.C_NATIVE_ID_RESOLVED)
    }

    fun cleanupConfirmed() = diagnosticWrite {
        if (disabled) return@diagnosticWrite
        previous?.let {
            if (!it.returned || !it.handled) { uncertain(); return@diagnosticWrite }
            if (!it.acked) { it.acked = true; emit(OverlayCleanupPoint.C_CLEANUP_CONFIRMED) }
        }
    }

    fun relation(eventId: Int, targetId: Int?, currentGeneration: Long): OverlayEventIdRelation = try {
        when {
            disabled || eventId < 0 || targetId == null || targetId < 0 || currentGeneration <= 0 ||
                currentGeneration != generation || targetId != target -> OverlayEventIdRelation.UNKNOWN
            ambiguous || previous?.ambiguous == true -> OverlayEventIdRelation.AMBIGUOUS_OR_REUSED
            else -> {
                val old = previous
                val isCurrent = eventId == bound
                val isTarget = eventId == targetId
                val isPrevious = old != null && eventId == old.id
                when {
                    listOf(isCurrent, isTarget, isPrevious).count { it } > 1 -> OverlayEventIdRelation.AMBIGUOUS_OR_REUSED
                    isPrevious -> if (old!!.acked) OverlayEventIdRelation.PREVIOUS_C_ACKED_ID_MATCH
                        else OverlayEventIdRelation.PREVIOUS_C_PENDING_ID_MATCH
                    isCurrent -> OverlayEventIdRelation.CURRENT_C_ID_MATCH
                    isTarget -> OverlayEventIdRelation.CURRENT_TARGET_ID_MATCH
                    else -> OverlayEventIdRelation.UNMATCHED
                }
            }
        }
    } catch (_: Exception) { OverlayEventIdRelation.UNKNOWN }

    /** eventTime and clock are uptimeMillis in Android assembly. The show interval is deliberately
     * UNKNOWN: an event dispatched while add/show was in progress has no reliable before/after. */
    fun timeRelation(eventTime: Long?): OverlayEventTimeRelation {
        if (disabled) return OverlayEventTimeRelation.UNKNOWN
        val start = showStart ?: return OverlayEventTimeRelation.UNKNOWN
        val end = showEnd ?: return OverlayEventTimeRelation.UNKNOWN
        val current = now() ?: return OverlayEventTimeRelation.UNKNOWN
        return when {
            eventTime == null || eventTime <= 0 || start <= 0 || end < start || current < end || eventTime > current ->
                OverlayEventTimeRelation.UNKNOWN
            eventTime < start -> OverlayEventTimeRelation.BEFORE_CURRENT_SHOW
            eventTime > end -> OverlayEventTimeRelation.AFTER_CURRENT_SHOW
            else -> OverlayEventTimeRelation.UNKNOWN
        }
    }

    /** Also used for close/reconnect. A failed lifetime cannot resurrect diagnostic history. */
    fun uncertain() {
        disabled = true; previous = null; bound = null; target = null; generation = 0
        ambiguous = false; showStart = null; showEnd = null
    }
    private fun now(): Long? = try { clock() } catch (_: Exception) { null }
    private fun emit(point: OverlayCleanupPoint) = diagnosticWrite { write(point) }
    override fun toString() = "OverlayEventOriginDiagnostic(redacted)"
}
