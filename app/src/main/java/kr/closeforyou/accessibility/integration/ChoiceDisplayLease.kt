package kr.closeforyou.accessibility.integration

import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.OverlayWindowStatus

/** Main-thread presentation identity only. Never an evidence/current-token or execution permit.
 * No snapshot, analysis, button or Android object is retained. Only TTL may detach presentation;
 * every other invalidation clears it before cleanup. The deadline is measured from actual show.
 */
internal class ChoiceDisplayLease(private val clock: () -> Long) {
    private var token: SnapshotToken? = null
    private var generation = 0L
    private var shownAt = 0L
    private var detached = false

    fun shown(token: SnapshotToken, state: OverlayWindowState) {
        clear()
        val now = clock()
        if (state.status != OverlayWindowStatus.SHOWING || state.generation <= 0 || now < 0) return
        this.token = token; generation = state.generation; shownAt = now
    }

    fun detach(state: OverlayWindowState, bound: Boolean): Boolean {
        if (!bound || !matches(state)) { clear(); return false }
        detached = true
        return true
    }

    fun retains(state: OverlayWindowState): Boolean = detached && matches(state)
    fun speechToken(state: OverlayWindowState): SnapshotToken? = if (retains(state)) token else null
    fun remainingMillis(state: OverlayWindowState): Long =
        if (retains(state)) MAX_DISPLAY_MILLIS - (clock() - shownAt) else 0L

    private fun matches(state: OverlayWindowState): Boolean {
        val now = clock()
        val valid = token != null && state.status == OverlayWindowStatus.SHOWING && state.generation == generation &&
            now >= shownAt && now - shownAt < MAX_DISPLAY_MILLIS
        if (!valid) clear() // An uncertain identity/clock must never become valid again.
        return valid
    }

    fun clear() { token = null; generation = 0; shownAt = 0; detached = false }
    override fun toString() = "ChoiceDisplayLease(redacted)"
    companion object { const val MAX_DISPLAY_MILLIS = 30_000L }
}
