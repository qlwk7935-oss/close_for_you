package kr.closeforyou.accessibility.capture

import kr.closeforyou.accessibility.DetectionFrame
import kr.closeforyou.accessibility.DetectionAssessment
import kr.closeforyou.accessibility.DetectionDisposition
import kr.closeforyou.accessibility.DetectionReason
import kr.closeforyou.accessibility.DetectionGeometry
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.SnapshotCompleteness
import kr.closeforyou.core.model.WindowType

/** A narrow accessibility class/visibility observation, never proof of playback or advertising.
 * Use only the snapshot and outline from one successful collector result, after F0 checks.
 * No framework getters, pixels, sound, class loading, subclass guesses or persistent identities.
 * False also means unavailable/unmatched evidence; it does not establish absence of video.
 */
internal object VideoSurfaceObservation {
    fun observed(snapshot: PopupSnapshot, frame: DetectionFrame?, assessment: DetectionAssessment?): Boolean {
        if (frame == null || assessment?.disposition != DetectionDisposition.POPUP_CANDIDATE ||
            assessment.geometry?.result != DetectionGeometry.VALID ||
            snapshot.completeness != SnapshotCompleteness.COMPLETE ||
            frame.activeWindowId != snapshot.token.windowId ||
            frame.structureDigest != snapshot.token.structureDigest ||
            frame.screenBounds != snapshot.screenBounds || frame.trigger != snapshot.trigger ||
            snapshot.token.windowId !in frame.applicationWindowIds ||
            snapshot.nodes.isEmpty() || snapshot.nodes.size > 1_200 || frame.outlines.size != snapshot.nodes.size) return false
        val window = snapshot.windows.singleOrNull { it.id == snapshot.token.windowId } ?: return false
        if (window.type != WindowType.APPLICATION || !window.active || window.bounds != frame.windowBounds ||
            !PopupRegionResolver.contains(snapshot.screenBounds, window.bounds)) return false
        val outlines = frame.outlines.associateBy { it.path }
        if (outlines.size != frame.outlines.size || snapshot.nodes.map { it.path }.toSet().size != snapshot.nodes.size ||
            snapshot.nodes.any { outlines[it.path]?.bounds != it.bounds }) return false
        val region = PopupRegionResolver.resolve(snapshot) ?: return false
        // A background surface in the same Activity must not become evidence about its Dialog.
        // For a bounded target window the whole window is the popup region. Otherwise require
        // the unique known Dialog subtree; unnamed panes/full-screen content remain unknown.
        val (popupBounds, dialogPath) = when (assessment.reason) {
            DetectionReason.BOUNDED_WINDOW -> {
                if (region.bounds != window.bounds) return false
                region.bounds to null
            }
            DetectionReason.SEMANTIC_DIALOG -> {
                val dialog = snapshot.nodes.singleOrNull {
                    it.className?.contains("dialog", ignoreCase = true) == true &&
                        outlines.getValue(it.path).visible && PopupRegionResolver.contains(region.bounds, it.bounds)
                } ?: return false
                dialog.bounds to (dialog.path + "/")
            }
            else -> return false
        }
        return snapshot.nodes.any { node ->
            node.className in CLASSES && outlines.getValue(node.path).visible &&
                PopupRegionResolver.contains(popupBounds, node.bounds) &&
                (dialogPath == null || node.path.startsWith(dialogPath))
        }
    }

    // Exact reported Android class names only. Similar names and custom subclasses stay unknown.
    private val CLASSES = setOf("android.view.SurfaceView", "android.view.TextureView", "android.widget.VideoView")
}
