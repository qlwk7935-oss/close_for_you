package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseButtonFinderTest {
    private fun scan(nodes: List<kr.closeforyou.core.model.NodeSnapshot>, bounded: Boolean = false): ButtonScan {
        val root = PopupRootFinder.find(nodes, SCREEN, bounded)!!
        return CloseButtonFinder.scan(nodes, root)
    }

    @Test fun elevenStreetOffersBothCloseLabelsAndRefusesTheAppLink() {
        val scan = scan(SimpleNodes.elevenStreet())
        assertEquals(listOf("모바일 웹으로 볼래요", "앱설치 안내 레이어 닫기"), scan.candidates.map { it.label }) // F21 order
        assertTrue(scan.candidates.all { it.role == ButtonRole.CLOSE })
        assertEquals(listOf("node:0/1/4/3", "node:0/1/4/0"), scan.candidates.map { it.id })
        // "11번가 앱에서 확인" holds a forbidden word; the page's own 닫기 banner is outside the root.
        assertEquals(1, scan.forbiddenCount)
        assertEquals(0, scan.unlabeledCount)
    }

    @Test fun auctionOffersOnlyTheCloseButtonNotTheAdImage() {
        val scan = scan(SimpleNodes.auction())
        assertEquals(listOf("닫기"), scan.candidates.map { it.label })
        assertEquals(1, scan.otherCount)
    }

    @Test fun oliveYoungOffersCloseAndDimButNotCouponOrUnlabeledX() {
        val scan = scan(SimpleNodes.oliveYoung())
        assertEquals(setOf("배경을 눌러 닫기", "닫기"), scan.candidates.map { it.label }.toSet())
        assertEquals(1, scan.forbiddenCount) // 쿠폰 받기
        assertEquals(1, scan.unlabeledCount) // the X image
    }

    @Test fun youTubeUnnamedBoxIsNamedByItsChildText() {
        val scan = scan(SimpleNodes.youTubeSkip())
        assertEquals(1, scan.candidates.size)
        assertEquals("건너뛰기", scan.candidates.single().label)
        assertEquals(ButtonRole.SKIP, scan.candidates.single().role)
        assertEquals("node:0/0/1", scan.candidates.single().id)
    }

    @Test fun chromePermissionBarOffersNothing() {
        val scan = scan(SimpleNodes.chromePermission())
        assertTrue(scan.candidates.isEmpty())
        assertEquals(1, scan.forbiddenCount) // 허용
        assertEquals(1, scan.otherCount) // 차단
    }

    @Test fun disabledButtonIsNotACandidate() {
        val nodes = listOf(
            SimpleNodes.node("0", bounds = SCREEN),
            SimpleNodes.node("0/0", "android.app.AlertDialog", bounds = kr.closeforyou.core.model.ScreenBounds(0, 1000, 1080, 2000)),
            SimpleNodes.node("0/0/0", text = "닫기", clickable = true, enabled = false, bounds = kr.closeforyou.core.model.ScreenBounds(0, 1800, 1080, 2000)),
        )
        assertTrue(scan(nodes).candidates.isEmpty())
    }
}
