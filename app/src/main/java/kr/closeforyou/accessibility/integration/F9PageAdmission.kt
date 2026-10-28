package kr.closeforyou.accessibility.integration

import kr.closeforyou.accessibility.action.*
import kr.closeforyou.core.model.*

internal enum class F9PageObservation {
    SESSION_UNAVAILABLE, READ_FAILED, INCOMPLETE, STALE, PACKAGE_MISMATCH, WINDOW_MISMATCH,
    FRAME_MISMATCH, WINDOW_SET_MISMATCH, NODES_MISMATCH,
    WINDOW_UNCONFIRMED, NOT_FULL_PAGE, BACKGROUND_UNCONFIRMED, VERIFIED,
}

/** Bind the independent F9 page read to the actual preceding complete F0 result. The full
 * logical-frame equality is deliberately retained: an inset activity is NOT silently accepted.
 * Agreement of these separate reads is not an atomic Android snapshot or a fresh TTL grant. */
internal object F9PageAdmission {
    fun assess(source: PopupSnapshot, scene: F9Scene?, now: Long): F9PageObservation {
        if (scene == null) return F9PageObservation.READ_FAILED
        if (!scene.complete || source.completeness != SnapshotCompleteness.COMPLETE) return F9PageObservation.INCOMPLETE
        if (source.capturedAtUptimeMillis < 0 || now < source.capturedAtUptimeMillis ||
            now - source.capturedAtUptimeMillis >= AutoEligibility.TTL_MILLIS ||
            source.token.captureEpoch != source.token.screenVersion) return F9PageObservation.STALE
        if (scene.packageName != source.token.packageName) return F9PageObservation.PACKAGE_MISMATCH
        if (scene.windowId != source.token.windowId) return F9PageObservation.WINDOW_MISMATCH
        if (scene.screen != source.screenBounds) return F9PageObservation.FRAME_MISMATCH
        if (scene.windows != source.windows) return F9PageObservation.WINDOW_SET_MISMATCH
        if (scene.nodes != source.nodes) return F9PageObservation.NODES_MISMATCH
        val window = scene.windows.singleOrNull { it.id == scene.windowId }
            ?: return F9PageObservation.WINDOW_UNCONFIRMED
        if (window.type != WindowType.APPLICATION || !window.active || !window.focused)
            return F9PageObservation.WINDOW_UNCONFIRMED
        if (window.bounds != scene.screen) return F9PageObservation.NOT_FULL_PAGE
        if (scene.pageWindowId != scene.windowId || scene.pageDigest.isNullOrBlank())
            return F9PageObservation.BACKGROUND_UNCONFIRMED
        return F9PageObservation.VERIFIED
    }
}
