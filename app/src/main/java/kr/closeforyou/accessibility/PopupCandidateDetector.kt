package kr.closeforyou.accessibility

import kr.closeforyou.core.model.PopupSignal
import kr.closeforyou.core.model.PopupTrigger
import kr.closeforyou.core.model.ScreenBounds

/** Contains geometry and salted structural digests only; no text or Android objects. */
internal data class DetectionOutline(
    val fingerprint: String,
    val bounds: ScreenBounds,
    val childCount: Int,
    val semanticOverlay: Boolean,
)

internal data class DetectionFrame(
    val applicationDigest: String,
    val activeWindowId: Int,
    val applicationWindowIds: Set<Int>,
    val structureDigest: String,
    val screenBounds: ScreenBounds,
    val outlines: List<DetectionOutline>,
    val trigger: PopupTrigger,
    val windowBounds: ScreenBounds = screenBounds,
)

/** F1 emits candidates only. In particular a full-screen transition is never an ad verdict. */
internal class PopupCandidateDetector {
    private data class Baseline(
        val applicationDigest: String,
        val activeWindowId: Int,
        val applicationWindowIds: Set<Int>,
        val structureDigest: String,
        val outlineDigests: Set<String>,
    )

    private var previous: Baseline? = null

    fun clear() { previous = null }

    fun detect(frame: DetectionFrame): Set<PopupSignal> {
        val before = previous?.takeIf { it.applicationDigest == frame.applicationDigest }
        val signals = mutableSetOf<PopupSignal>()
        // #29: Android's Dialog root is often a FrameLayout, including the first observed popup.
        // An independently bounded APPLICATION window scopes the popup without guessing from text.
        // Both axes must be inset: a normal Activity below status/navigation bars is not a dialog.
        val boundedWindow = isBounded(frame.windowBounds, frame.screenBounds) &&
            frame.windowBounds.width.toLong() * 100 < frame.screenBounds.width.toLong() * 96 &&
            frame.windowBounds.height.toLong() * 100 < frame.screenBounds.height.toLong() * 90
        val hasNewWindow = (before != null && frame.activeWindowId !in before.applicationWindowIds) ||
            (boundedWindow && (before == null || before.activeWindowId != frame.activeWindowId))
        if (hasNewWindow) signals += PopupSignal.NEW_WINDOW

        val boundedOverlay = frame.outlines.any { outline ->
            val bounded = isBounded(outline.bounds, frame.screenBounds)
            val newlyAdded = before != null && outline.fingerprint !in before.outlineDigests
            bounded && ((outline.semanticOverlay && (before == null || newlyAdded)) ||
                (newlyAdded && outline.childCount >= 2))
        }
        if (boundedOverlay && !hasNewWindow) signals += PopupSignal.SAME_WINDOW_OVERLAY

        val changedStructure = before == null || before.structureDigest != frame.structureDigest
        if (signals.isEmpty() && frame.trigger != PopupTrigger.CONTENT_CHANGED && changedStructure) {
            signals += PopupSignal.FULL_SCREEN_TRANSITION
            signals += PopupSignal.INSUFFICIENT_EVIDENCE
        }
        previous = Baseline(
            frame.applicationDigest, frame.activeWindowId, frame.applicationWindowIds.toSet(),
            frame.structureDigest, frame.outlines.mapTo(mutableSetOf()) { it.fingerprint },
        )
        return signals.toSet()
    }

    private fun isBounded(bounds: ScreenBounds, screen: ScreenBounds): Boolean {
        val area = (bounds.right.toLong() - bounds.left) * (bounds.bottom.toLong() - bounds.top)
        val screenArea = (screen.right.toLong() - screen.left) * (screen.bottom.toLong() - screen.top)
        return bounds.right > bounds.left && bounds.bottom > bounds.top && screenArea > 0L &&
            bounds.left >= screen.left && bounds.top >= screen.top &&
            bounds.right <= screen.right && bounds.bottom <= screen.bottom &&
            area.toDouble() / screenArea < 0.96
    }
}
