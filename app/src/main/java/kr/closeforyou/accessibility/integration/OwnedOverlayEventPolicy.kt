package kr.closeforyou.accessibility.integration

import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.OverlayWindowStatus

/** An event exclusion is UI bookkeeping, never evidence that the underlying screen stayed still. */
internal enum class OverlayEventKind { CONTENT, WINDOW_STATE, OWN_WINDOW_LAYOUT, FOCUS, INTERACTION, UNKNOWN }
internal object OwnedOverlayEventPolicy {
    // C may identify a stuck window; A still does not exempt its events or permit choices.
    fun mayTrack(state: OverlayWindowState): Boolean =
        state.status == OverlayWindowStatus.SHOWING && state.generation > 0L
    fun mayIgnore(kind: OverlayEventKind, boundWindow: Boolean, liveOwnership: Boolean,
        packageMatches: Boolean, packageMissing: Boolean, currentToken: Boolean): Boolean =
        boundWindow && liveOwnership && currentToken &&
            (packageMatches || (packageMissing && kind == OverlayEventKind.OWN_WINDOW_LAYOUT)) &&
            kind in setOf(OverlayEventKind.CONTENT, OverlayEventKind.WINDOW_STATE,
                OverlayEventKind.OWN_WINDOW_LAYOUT, OverlayEventKind.FOCUS)
}

/** Window identity is bound only from a new, uniquely owned window, including delayed add events. */
internal class OwnedOverlayBinding {
    private var before: Set<Int>? = null
    private var token: SnapshotToken? = null
    private var bound: Int? = null
    private var generation = 0L
    fun expect(ownedBeforeShow: Set<Int>?, current: SnapshotToken, currentGeneration: Long) {
        clear()
        if (ownedBeforeShow != null && ownedBeforeShow.size <= 32 && currentGeneration > 0L) {
            before = ownedBeforeShow.toSet(); token = current; generation = currentGeneration
        }
    }
    fun matches(eventWindow: Int, liveOwned: Set<Int>?, current: SnapshotToken?, currentGeneration: Long): Boolean {
        val previous = before ?: return false
        if (generation <= 0L || generation != currentGeneration || token == null || token != current || liveOwned == null || liveOwned.size > 32) return false
        val known = bound
        if (known != null) return eventWindow == known && known in liveOwned
        val created = liveOwned.filter { it !in previous }.singleOrNull() ?: return false
        if (eventWindow != created) return false
        bound = created
        return true
    }
    fun clear() { before = null; token = null; bound = null; generation = 0L }
    override fun toString() = "OwnedOverlayBinding(redacted)"
}
