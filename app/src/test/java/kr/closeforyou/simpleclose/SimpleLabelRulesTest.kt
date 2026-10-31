package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimpleLabelRulesTest {
    private fun role(label: String?) = SimpleLabelRules.roleOf(label, v2 = true)
    private fun reject(label: String?) = SimpleLabelRules.judge(label, v2 = true).reject

    @Test fun sharedContractLabelsStillWork() {
        assertEquals(ButtonRole.CLOSE, role("닫기"))
        assertEquals(ButtonRole.CLOSE, role(" 팝업 닫기 "))
        assertEquals(ButtonRole.CLOSE, role("Close"))
        assertEquals(ButtonRole.SKIP, role("건너뛰기"))
        assertEquals(ButtonRole.SKIP, role("Skip ad"))
    }

    @Test fun v2EndsWithCloseIsClose() {
        assertEquals(ButtonRole.CLOSE, role("앱설치 안내 레이어 닫기"))
        assertEquals(ButtonRole.CLOSE, role("레이어 닫기"))
        assertEquals(ButtonRole.CLOSE, role("배너 닫기"))
        assertEquals(ButtonRole.CLOSE, role("속보 닫기"))
        assertEquals(ButtonRole.CLOSE, role("[닫기]"))
    }

    @Test fun v2WebStayAndDimBackgroundAreClose() {
        assertEquals(ButtonRole.CLOSE, role("모바일 웹으로 볼래요"))
        assertEquals(ButtonRole.CLOSE, role("모바일웹으로 볼게요"))
        assertEquals(ButtonRole.CLOSE, role("웹으로 계속 보기"))
        assertEquals(ButtonRole.CLOSE, role("배경을 눌러 닫기"))
    }

    @Test fun forbiddenWordsWinEvenWhenLabelEndsWithClose() {
        for (label in listOf("구매창 닫기", "결제 닫기", "동의 후 닫기", "오늘 하루 무료 체험 닫기")) {
            assertEquals(label, SimpleLabelReject.FORBIDDEN, reject(label))
        }
        // F16 (v3): the whole phrases "오늘 하루 닫기", "오늘 하루 보지 않기", "다시 보지 않기" are closes ([StrongCloseTest]).
        for (label in listOf("오늘 하루 닫기", "오늘 하루 보지 않기", "다시 보지 않기")) {
            assertEquals(label, ButtonRole.CLOSE, role(label))
        }
    }

    @Test fun decisionAndNavigationLabelsAreNeverPressed() {
        for (label in listOf(
            "구매하기", "결제하기", "주문하기", "동의", "허용", "권한 허용", "앱 설치", "설치", "구독", "삭제", "로그인", "회원가입",
            "쿠폰 받기", "자세히 보기", "더보기", "11번가 앱에서 확인", "앱 열기", "다운로드", "취소",
            "Allow", "Install", "Buy now", "Sign up", "Get coupon", "OK", "확인",
        )) {
            assertNull(label, role(label))
        }
        assertEquals(SimpleLabelReject.FORBIDDEN, reject("허용"))
        assertEquals(SimpleLabelReject.FORBIDDEN, reject("쿠폰 받기"))
        assertEquals(SimpleLabelReject.FORBIDDEN, reject("Allow"))
    }

    @Test fun ambiguousOrUnrelatedLabelsAreNotClose() {
        assertEquals(SimpleLabelReject.NOT_CLOSE, reject("차단"))
        assertEquals(SimpleLabelReject.NOT_CLOSE, reject("가을 특가 광고, 최대 50% 할인"))
        assertEquals(SimpleLabelReject.NO_LABEL, reject(null))
        assertEquals(SimpleLabelReject.NO_LABEL, reject("   "))
    }

    @Test fun symbolOnlyXIsNotCloseWithoutAWord() {
        assertNull(role("X"))
        assertNull(role("×"))
    }

    @Test fun v2OffFallsBackToTheSharedContractOnly() {
        assertNull(SimpleLabelRules.roleOf("앱설치 안내 레이어 닫기", v2 = false))
        assertNull(SimpleLabelRules.roleOf("모바일 웹으로 볼래요", v2 = false))
        assertEquals(ButtonRole.CLOSE, SimpleLabelRules.roleOf("닫기", v2 = false))
        // Forbidden words are refused with v2 off as well.
        assertEquals(SimpleLabelReject.FORBIDDEN, SimpleLabelRules.judge("허용", v2 = false).reject)
    }
}
