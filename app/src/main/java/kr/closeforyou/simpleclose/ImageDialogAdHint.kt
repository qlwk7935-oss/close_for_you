package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification

/**
 * Demo-mode structural hint ([SimpleCloseEngineFeature.IMAGE_DIALOG_AD_HINT]): a dialog root whose
 * content is mostly one image, with a verified close button and little text, is an advertisement
 * banner in practice (옥션 10/31). B's classifier sees only words, so such a popup is UNKNOWN to it;
 * this hint is applied only then, and never when B finds a decision or a scam signal.
 * Pure Kotlin; JVM tested.
 *
 * LG 10/31 21:00 (0b15105): 옥션 stayed CLASSIFIED_UNKNOWN → ASK with no hint, reason unknown. So
 * [check] names the first condition that failed ([AdHintMiss], debug log `AD_HINT_MISS_*`), and the
 * conditions were widened: image 40% → 25% of the root, the image need not be clickable itself (a web
 * banner is often an `<img>` inside a link), a dim-overlay root counts too, and the image's own alt
 * text is not counted as body text.
 */
object ImageDialogAdHint {
    /** The image covers at least this much of the popup root. */
    private const val IMAGE_MIN_AREA_RATIO = 0.25

    /** With more text than this the popup says enough for B to judge; the hint stays out of its way. */
    private const val MAX_TEXT_CHARS = 80

    private val ROOT_KINDS = setOf(PopupRootKind.ALERT_DIALOG, PopupRootKind.DIM_OVERLAY)

    fun applies(popup: SimplePopup, classification: PopupClassification, suspicious: Boolean): Boolean =
        check(popup, classification, suspicious) == null

    /** Null = the hint applies; otherwise the first condition that failed, in this order. */
    fun check(popup: SimplePopup, classification: PopupClassification, suspicious: Boolean): AdHintMiss? {
        if (suspicious) return AdHintMiss.SCAM
        if (classification != PopupClassification.UNKNOWN) return AdHintMiss.NOT_UNKNOWN
        if (popup.root.kind !in ROOT_KINDS) return AdHintMiss.NOT_DIALOG_ROOT
        if (popup.scan.candidates.none { it.role == ButtonRole.CLOSE }) return AdHintMiss.NO_VERIFIED_CLOSE
        val inside = popup.snapshot.nodes.filter(popup.root::contains)
        val rootArea = area(popup.root).toDouble()
        if (rootArea <= 0) return AdHintMiss.IMAGE_TOO_SMALL
        val images = inside.filter { it.className?.contains("image", ignoreCase = true) == true }
        if (images.none { area(it).toDouble() / rootArea >= IMAGE_MIN_AREA_RATIO }) return AdHintMiss.IMAGE_TOO_SMALL
        val textChars = inside.filter { !it.clickable && it !in images }
            .sumOf { (it.text?.length ?: 0) + (it.contentDescription?.length ?: 0) }
        return if (textChars <= MAX_TEXT_CHARS) null else AdHintMiss.TOO_MUCH_TEXT
    }

    private fun area(node: NodeSnapshot): Long = node.bounds.width.toLong() * node.bounds.height
    private fun area(root: PopupRoot): Long = root.bounds.width.toLong() * root.bounds.height
}

/** Why [ImageDialogAdHint] did not apply (debug log only, no screen text). */
enum class AdHintMiss {
    SCAM,
    NOT_UNKNOWN,
    NOT_DIALOG_ROOT,
    NO_VERIFIED_CLOSE,
    IMAGE_TOO_SMALL,
    TOO_MUCH_TEXT,
}
