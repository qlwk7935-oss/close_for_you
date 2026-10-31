package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ScreenBounds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F14: which subtrees the tree walk skips (every child is a binder call). */
class TreeWalkRulesTest {
    private val screen = ScreenBounds(0, 0, 1080, 2400)

    @Test fun visibleNodesAreAlwaysWalked() {
        assertTrue(TreeWalkRules.descend(true, ScreenBounds(0, 5000, 1080, 6000), screen))
        assertTrue(TreeWalkRules.descend(true, ScreenBounds(0, 1700, 1080, 2400), screen))
    }

    @Test fun anInvisibleSubtreeWhollyBelowOrAboveTheScreenIsSkipped() {
        assertFalse(TreeWalkRules.descend(false, ScreenBounds(0, 2400, 1080, 9000), screen))
        assertFalse(TreeWalkRules.descend(false, ScreenBounds(0, -3000, 1080, 0), screen))
        assertFalse(TreeWalkRules.descend(false, ScreenBounds(1080, 0, 2000, 2400), screen))
    }

    @Test fun anInvisibleNodeTouchingTheScreenIsStillWalked() {
        // A popup's buttons sit under on-screen containers; a hidden but on-screen wrapper keeps its children.
        assertTrue(TreeWalkRules.descend(false, ScreenBounds(0, 2000, 1080, 5000), screen))
        assertTrue(TreeWalkRules.descend(false, ScreenBounds(0, 0, 1080, 2400), screen))
    }

    @Test fun aZeroSizeWrapperIsStillWalked() {
        // Modal portals are often zero-size wrappers around a fixed-position dialog.
        assertTrue(TreeWalkRules.descend(false, ScreenBounds(0, 9000, 0, 9000), screen))
        assertTrue(TreeWalkRules.descend(false, ScreenBounds(0, 0, 1080, 0), screen))
    }
}
