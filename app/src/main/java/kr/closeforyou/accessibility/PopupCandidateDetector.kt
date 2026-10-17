package kr.closeforyou.accessibility

import kr.closeforyou.core.model.PopupSignal
import kr.closeforyou.core.model.PopupTrigger
import kr.closeforyou.core.model.ScreenBounds

/** A-private evidence: salted identity excludes mutable bounds/child counts/text. */
internal data class DetectionOutline(
    val fingerprint: String,
    val bounds: ScreenBounds,
    val childCount: Int,
    val semanticOverlay: Boolean,
    val path: String = "0",
    val pane: Boolean = false,
    val visible: Boolean = true,
    val scrollable: Boolean = false,
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

internal enum class DetectionDisposition { POPUP_CANDIDATE, NO_POPUP, UNCERTAIN }
internal enum class DetectionInput { WINDOW, CONTENT, SCROLL, NOTIFICATION, INTERACTION }
internal enum class DetectionReason {
    BOUNDED_WINDOW, SEMANTIC_DIALOG, PANE_WITH_UNDERLAY,
    BASELINE_ONLY, UNCHANGED, ORDINARY_CONTENT_CHANGE, UNCONFIRMED_STRUCTURE,
    UNCONFIRMED_TRANSITION, INVALID_GEOMETRY,
}
internal data class DetectionAssessment(
    val disposition: DetectionDisposition,
    val reason: DetectionReason,
    val signals: Set<PopupSignal> = emptySet(),
)

/** F1 evidence only. Neither a candidate nor uncertainty authorizes any external action.
 * Full-screen/unnamed structure changes remain explicit uncertainty, not confirmed popups. */
internal class PopupCandidateDetector {
    private data class Baseline(val frame: DetectionFrame, val popupKeys: Set<String>)
    private var previous: Baseline? = null
    fun clear() { previous = null }

    fun assess(frame: DetectionFrame): DetectionAssessment {
        val before = previous?.takeIf {
            it.frame.applicationDigest == frame.applicationDigest && it.frame.screenBounds == frame.screenBounds
        }
        val sameWindow = before?.frame?.activeWindowId == frame.activeWindowId
        val priorNodes = before?.frame?.outlines.orEmpty().associateBy { it.fingerprint }
        fun finish(result: DetectionAssessment, keys: Set<String> = emptySet()): DetectionAssessment {
            previous = Baseline(frame.copy(outlines = frame.outlines.toList(),
                applicationWindowIds = frame.applicationWindowIds.toSet()), keys.toSet())
            return result
        }
        if (frame.screenBounds.width <= 0 || frame.screenBounds.height <= 0 ||
            !contains(frame.screenBounds, frame.windowBounds) || frame.windowBounds.width <= 0 || frame.windowBounds.height <= 0) {
            previous = null
            return DetectionAssessment(DetectionDisposition.UNCERTAIN, DetectionReason.INVALID_GEOMETRY)
        }
        // Both axes must be inset: status/navigation bars on an ordinary Activity are not a Dialog.
        if (bounded(frame.windowBounds, frame.screenBounds) &&
            frame.windowBounds.width.toLong() * 100 < frame.screenBounds.width.toLong() * 96 &&
            frame.windowBounds.height.toLong() * 100 < frame.screenBounds.height.toLong() * 90) {
            val key = "window:${frame.activeWindowId}"
            return finish(if (before?.popupKeys?.contains(key) == true)
                DetectionAssessment(DetectionDisposition.NO_POPUP, DetectionReason.UNCHANGED)
            else DetectionAssessment(DetectionDisposition.POPUP_CANDIDATE, DetectionReason.BOUNDED_WINDOW,
                setOf(PopupSignal.NEW_WINDOW)), setOf(key))
        }
        val scrollPaths = frame.outlines.filter { it.scrollable }.map { it.path }
        val eligible = frame.outlines.filter { node ->
            node.visible && bounded(node.bounds, frame.windowBounds) &&
                scrollPaths.none { ancestor(it, node.path) }
        }
        val semantic = eligible.filter { it.semanticOverlay }
        val panes = eligible.filter { pane -> pane.pane && sameWindow &&
            priorNodes[pane.fingerprint]?.visible != true &&
            frame.outlines.any { underlay ->
                underlay.visible && underlay.fingerprint != pane.fingerprint &&
                    !ancestor(underlay.path, pane.path) && !ancestor(pane.path, underlay.path) &&
                    overlap(underlay.bounds, pane.bounds) &&
                    priorNodes[underlay.fingerprint]?.let { it.visible && it.bounds == underlay.bounds } == true
            }
        }
        val popupNodes = semantic + panes
        val keys = popupNodes.mapTo(mutableSetOf()) { "${frame.activeWindowId}:${it.fingerprint}" }
        if (keys.isNotEmpty()) {
            val fresh = keys.any { before?.popupKeys?.contains(it) != true }
            return finish(if (fresh) DetectionAssessment(DetectionDisposition.POPUP_CANDIDATE,
                if (semantic.isNotEmpty()) DetectionReason.SEMANTIC_DIALOG else DetectionReason.PANE_WITH_UNDERLAY,
                setOf(PopupSignal.SAME_WINDOW_OVERLAY))
            else DetectionAssessment(DetectionDisposition.NO_POPUP, DetectionReason.UNCHANGED), keys)
        }
        if (before == null) return finish(DetectionAssessment(DetectionDisposition.NO_POPUP, DetectionReason.BASELINE_ONLY))
        if (sameWindow && before.frame.structureDigest == frame.structureDigest) {
            return finish(DetectionAssessment(DetectionDisposition.NO_POPUP, DetectionReason.UNCHANGED))
        }
        if (!sameWindow || frame.trigger == PopupTrigger.FULL_SCREEN_TRANSITION) {
            return finish(DetectionAssessment(DetectionDisposition.UNCERTAIN, DetectionReason.UNCONFIRMED_TRANSITION,
                setOf(PopupSignal.FULL_SCREEN_TRANSITION, PopupSignal.INSUFFICIENT_EVIDENCE)))
        }
        val unknownContainer = eligible.any { node -> node.childCount >= 2 &&
            priorNodes[node.fingerprint]?.visible != true }
        return finish(if (unknownContainer)
            DetectionAssessment(DetectionDisposition.UNCERTAIN, DetectionReason.UNCONFIRMED_STRUCTURE,
                setOf(PopupSignal.INSUFFICIENT_EVIDENCE))
        else DetectionAssessment(DetectionDisposition.NO_POPUP, DetectionReason.ORDINARY_CONTENT_CHANGE))
    }

    private fun ancestor(parent: String, child: String) = child.startsWith("$parent/")
    private fun overlap(a: ScreenBounds, b: ScreenBounds) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
    private fun contains(outer: ScreenBounds, inner: ScreenBounds) = inner.left >= outer.left &&
        inner.top >= outer.top && inner.right <= outer.right && inner.bottom <= outer.bottom
    private fun bounded(bounds: ScreenBounds, screen: ScreenBounds): Boolean = contains(screen, bounds) &&
        bounds.width >= 32 && bounds.height >= 24 && screen.width > 0 && screen.height > 0 &&
        bounds.width.toLong() * bounds.height < screen.width.toLong() * screen.height * 0.92
}
