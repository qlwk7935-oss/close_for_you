package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B_DEMO_RULES.md 10절: the next popup in a chain is not the closed popup back, whatever sits at the same place. */
class ChainPopupReappearTest {
    private val card = ScreenBounds(90, 900, 990, 1500)

    private fun dialog(vararg words: String): List<NodeSnapshot> {
        val body = words.mapIndexed { i, word ->
            node("0/0/$i", "android.widget.TextView", text = word, bounds = ScreenBounds(130, 950 + i * 100, 950, 1040 + i * 100))
        }
        return listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", "android.app.AlertDialog", bounds = card),
        ) + body + node("0/0/9", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(130, 1380, 950, 1480))
    }

    private fun popupOf(nodes: List<NodeSnapshot>): SimplePopup =
        SimplePopupAnalysis.build(nodes, PopupRootFinder.find(nodes, SCREEN)!!, "com.android.chrome", 7, SCREEN, 1L, 0L)

    private fun back(before: List<NodeSnapshot>, after: List<NodeSnapshot>): Boolean =
        PopupMemory.of(popupOf(before)).reappearance(PopupRootFinder.find(after, SCREEN)!!, after, SCREEN) != null

    private fun replaced(before: List<NodeSnapshot>, after: List<NodeSnapshot>): Boolean =
        PopupMemory.of(popupOf(before)).replacedByAnotherPopup(PopupRootFinder.find(after, SCREEN)!!, after, SCREEN)

    private val coupon = dialog("가을 할인 쿠폰", "지금 받으면 30퍼센트 할인")

    @Test fun theNextPopupWithTheSameCloseInTheSamePlaceIsNotThisPopupBack() {
        assertFalse(back(coupon, dialog("서비스 점검 안내", "내일 새벽에 점검합니다")))
    }

    @Test fun theSamePopupFoundAgainIsStillBack() {
        assertTrue(back(coupon, coupon))
    }

    @Test fun oneWordLeftIsEnoughToBeTheSamePopup() {
        assertTrue(back(coupon, dialog("가을 할인 쿠폰", "남은 시간 3초")))
    }

    @Test fun aPopupWithoutWordsOfItsOwnIsJudgedAsBefore() {
        val noWords = dialog()
        assertTrue(back(noWords, noWords))
        assertTrue(back(noWords, coupon))
    }

    // Not closed must stay not closed (우진 11/3): the ways a popup that stayed can look a little different.
    @Test fun aPopupThatStayedAndMovedIsStillBack() {
        val moved = coupon.map { it.copy(bounds = it.bounds.copy(top = it.bounds.top + 200, bottom = it.bounds.bottom + 200)) }
        assertTrue(back(coupon, moved))
        assertFalse(replaced(coupon, moved))
    }

    @Test fun aPopupThatStayedWithAnotherButtonNameIsStillBack() {
        val renamed = coupon.map { if (it.text == "닫기") it.copy(text = "오늘 그만 보기") else it }
        assertTrue(back(coupon, renamed))
        assertFalse(replaced(coupon, renamed))
    }

    @Test fun aPopupThatStayedAndGainedAWordIsStillBack() {
        assertTrue(back(coupon, dialog("가을 할인 쿠폰", "지금 받으면 30퍼센트 할인", "남은 시간 3초")))
    }

    // The risk the team knows of: every word changed in place. It reads as closed, and the code says so in the log.
    @Test fun everyWordChangedInPlaceReadsAsClosedAndIsLogged() {
        val other = dialog("서비스 점검 안내", "내일 새벽에 점검합니다")
        assertFalse(back(coupon, other))
        assertTrue(replaced(coupon, other))
    }

    @Test fun theLogCodeStaysOutForAPopupElsewhereOnTheScreen() {
        val elsewhere = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", "android.app.AlertDialog", bounds = ScreenBounds(90, 1600, 990, 2200)),
            node("0/0/0", "android.widget.TextView", text = "서비스 점검 안내", bounds = ScreenBounds(130, 1650, 950, 1740)),
            node("0/0/9", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(130, 2080, 950, 2180)),
        )
        assertFalse(back(coupon, elsewhere))
        assertFalse(replaced(coupon, elsewhere))
    }

    @Test fun theNextPopupWithoutWordsIsNotCalledClosed() {
        assertTrue(back(coupon, dialog()))
    }

    @Test fun anImageAdsAltTextCountsAsItsWords() {
        val auction = SimpleNodes.auction()
        assertTrue(back(auction, auction))
        val other = auction.map { if (it.contentDescription != null) it.copy(contentDescription = "새 공지 안내") else it }
        assertFalse(back(auction, other))
    }

    @Test fun theNextPopupIsStillAskedByTheChainGuard() {
        val guard = ChainGuard()
        guard.pressed("app", "first", 1_000L, automatic = true)
        assertEquals(ChainBlock.OPENED_BY_OUR_PRESS, guard.check("app", "second", 2_000L))
    }
}
