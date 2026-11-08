package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.ScreenBounds

/**
 * 11/7 G마켓 웹 (준영 SM-M446K, 69fe45e, 5회 중 4회): the page's tab bar (0,2135~1080,2273) was taken for a
 * [PopupRootKind.BOTTOM_BANNER] because a coupon bubble "…쿠폰 찾았나요? ×" floats above it while it sits in the bar's
 * subtree; its × (705,1966~787,2048, outside the bar's box) was pressed, nothing closed. Membership by path is not enough
 * for a container banner: its close must be drawn inside it. Only for a container banner (a flat banner's [PopupRoot.members]
 * are a row the finder already measured); other popups keep their X outside the card (11번가). Pure; JVM tested.
 */
object BannerBounds {
    /** A container bottom banner: its subtree is its content. */
    fun applies(root: PopupRoot): Boolean = root.kind == PopupRootKind.BOTTOM_BANNER && root.members == null

    /** The button's middle lies inside [box]. */
    fun holds(box: ScreenBounds, button: ScreenBounds): Boolean {
        val x = (button.left + button.right) / 2
        val y = (button.top + button.bottom) / 2
        return x >= box.left && x < box.right && y >= box.top && y < box.bottom
    }

    /**
     * Right before the press: the button as read again ([fresh], from the popup read again) must still be a candidate,
     * which by [CloseButtonFinder] means still inside the banner. True = stop, quietly.
     */
    fun movedOut(root: PopupRoot, button: CloseCandidate, fresh: List<CloseCandidate>): Boolean =
        applies(root) && fresh.none { it.nodePath == button.nodePath }
}
