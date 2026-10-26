package kr.closeforyou.ui.voice.overlay

internal enum class NativeWindowIdObservation { ATTACH, FIRST_DRAW, BEFORE_REMOVE, DETACH }

/** A creates one identity for one C View in one service assembly. No Android handle or token.
 * Only C's real View callbacks/removal adapter update it; it grants no choice/event exemption. */
internal class OverlayNativeWindowLifetime(val generation: Long, private val session: Any) {
    private var id: Int? = null
    private var attached = false
    private var requested = false
    private var detached = false
    private var returned = false
    private var invalid = generation <= 0
    private var addObserved = false
    private var added = false
    private var observationSites = 0

    val hasAttached: Boolean get() = attached
    val identityUnavailable: Boolean get() = invalid || (detached && id == null)

    fun addReturned(success: Boolean) {
        if (addObserved || !success) invalid = true
        addObserved = true; added = success
    }
    /** At most one read per real lifecycle site. Missing ID is not a failed ownership proof;
     * it grants nothing and can be followed only by another bounded, real View callback. */
    fun observeWindowId(stage: NativeWindowIdObservation, read: () -> Int?) {
        val bit = 1 shl stage.ordinal
        if (invalid || !attached || detached || id != null || observationSites and bit != 0) return
        observationSites = observationSites or bit
        val observed = try { read() } catch (_: Exception) { null }
        if (!invalid && attached && !detached && observed != null && observed >= 0) id = observed
    }

    fun attached(windowId: Int?) {
        if (invalid || detached || attached) { invalid = true; return }
        attached = true
        id = windowId?.takeIf { it >= 0 }
    }
    fun removalRequested() { if (requested) invalid = true; requested = true }
    fun detached() {
        if (!attached || !requested || detached) invalid = true
        detached = true
    }
    fun removalReturned(success: Boolean) {
        if (!requested || returned || !success) invalid = true
        returned = success
    }
    /** Read-only identity of THIS attached native View, not a package/title guess. */
    fun liveWindow(owner: Any, expectedGeneration: Long): Int? =
        if (!invalid && session === owner && generation == expectedGeneration && added && attached &&
            !requested && !detached) id else null
    fun retirementWindow(owner: Any, expectedGeneration: Long): Int? =
        if (!invalid && session === owner && generation == expectedGeneration && attached && requested) id else null
    /** Registration may reserve blocked cleanup before attach, never complete it. */
    fun registrationConfirmed(owner: Any, expectedGeneration: Long): Boolean =
        !invalid && session === owner && generation == expectedGeneration && requested && (added || attached)
    fun removalComplete(owner: Any, expectedGeneration: Long, expectedWindow: Int): Boolean =
        retirementWindow(owner, expectedGeneration) == expectedWindow && detached && returned
    override fun toString() = "OverlayNativeWindowLifetime(redacted)"
}

/** Registered once before presentation; the old View retains its original observer/session. */
internal interface OverlayNativeWindowObserver {
    fun created(generation: Long): OverlayNativeWindowLifetime
    fun changed(lifetime: OverlayNativeWindowLifetime)
}
