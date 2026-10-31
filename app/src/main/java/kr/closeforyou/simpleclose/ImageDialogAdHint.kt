package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification

/**
 * Demo-mode structural hint ([SimpleCloseEngineFeature.IMAGE_DIALOG_AD_HINT]): a Dialog root whose
 * content is mostly one clickable image, with a verified close button and little text, is an
 * advertisement banner in practice (옥션 10/31). B's classifier sees only words, so such a popup is
 * UNKNOWN to it; this hint is applied only then, and never when B finds a decision or a scam signal.
 * Pure Kotlin; JVM tested.
 */
object ImageDialogAdHint {
    /** The image covers at least this much of the popup root. */
    private const val IMAGE_MIN_AREA_RATIO = 0.4

    /** With more text than this the popup says enough for B to judge; the hint stays out of its way. */
    private const val MAX_TEXT_CHARS = 80

    fun applies(popup: SimplePopup, classification: PopupClassification, suspicious: Boolean): Boolean {
        if (suspicious || classification != PopupClassification.UNKNOWN) return false
        if (popup.root.kind != PopupRootKind.ALERT_DIALOG) return false
        if (popup.scan.candidates.none { it.role == ButtonRole.CLOSE }) return false
        val inside = popup.snapshot.nodes.filter(popup.root::contains)
        val rootArea = area(popup.root).toDouble()
        if (rootArea <= 0) return false
        val bigImage = inside.any { node ->
            node.clickable && node.className?.contains("image", ignoreCase = true) == true &&
                area(node).toDouble() / rootArea >= IMAGE_MIN_AREA_RATIO
        }
        if (!bigImage) return false
        val textChars = inside.filter { !it.clickable }.sumOf { (it.text?.length ?: 0) + (it.contentDescription?.length ?: 0) }
        return textChars <= MAX_TEXT_CHARS
    }

    private fun area(node: NodeSnapshot): Long = node.bounds.width.toLong() * node.bounds.height
    private fun area(root: PopupRoot): Long = root.bounds.width.toLong() * root.bounds.height
}
