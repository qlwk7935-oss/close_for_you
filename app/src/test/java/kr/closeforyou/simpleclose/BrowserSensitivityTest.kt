package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B_DEMO_RULES.md 2절 (proposal): browser screens the engine leaves alone, and the ones it must not. */
class BrowserSensitivityTest {
    private fun chrome(address: String, vararg page: NodeSnapshot): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.widget.EditText", text = address, clickable = true, bounds = ScreenBounds(100, 60, 900, 180), viewId = "com.android.chrome:id/url_bar"),
        node("0/1", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
    ) + page

    @Test fun shoppingHomePagesAreNotSensitive() {
        // 11번가 as dumped: footer links "로그인", "개인정보처리방침" at zero height, the url bar an EditText.
        assertNull(BrowserSensitivity.check(SimpleNodes.elevenStreet(), SCREEN))
        val home = chrome("m.11st.co.kr/page/main/home",
            node("0/1/0", text = "로그인", clickable = true, bounds = ScreenBounds(40, 300, 200, 360)),
            node("0/1/1", "android.widget.EditText", text = "검색어를 입력하세요", bounds = ScreenBounds(40, 400, 1040, 500)),
            node("0/1/2", text = "고객센터 1599-0110", bounds = ScreenBounds(40, 2000, 600, 2060)))
        assertNull(BrowserSensitivity.check(home, SCREEN))
    }

    @Test fun sensitiveAddresses() {
        for (a in listOf("nid.naver.com/nidlogin.login", "accounts.kakao.com/login", "m.kbstar.com", "www.shinhancard.com/mob", "pay.naver.com/o/orderStatus",
            "mobile.inicis.com/smart/payment", "www.hometax.go.kr", "m.11st.co.kr/order/checkout", "m.coupang.com/np/checkout", "login.11st.co.kr/auth/front/login.tmall",
            "https://www.gov.kr/mw/AA020InfoCappView.do")) {
            assertTrue(a, BrowserSensitivity.sensitiveAddress(a))
        }
        for (a in listOf("m.11st.co.kr/page/main/home", "m.naver.com", "m.oliveyoung.co.kr/m/mtn", "namu.wiki/w/대문", "m.daum.net", "오늘 날씨 서울")) {
            assertFalse(a, BrowserSensitivity.sensitiveAddress(a))
        }
        assertEquals(BrowserSensitiveReason.SENSITIVE_ADDRESS, BrowserSensitivity.check(chrome("nid.naver.com/nidlogin.login"), SCREEN))
    }

    @Test fun aCredentialFormIsSensitiveWhateverTheAddress() {
        val form = chrome("m.example.co.kr/",
            node("0/1/0", text = "아이디", bounds = ScreenBounds(40, 400, 400, 460)),
            node("0/1/1", "android.widget.EditText", bounds = ScreenBounds(40, 470, 1040, 560)),
            node("0/1/2", text = "비밀번호", bounds = ScreenBounds(40, 600, 400, 660)),
            node("0/1/3", "android.widget.EditText", bounds = ScreenBounds(40, 670, 1040, 760)))
        assertEquals(BrowserSensitiveReason.CREDENTIAL_FORM, BrowserSensitivity.check(form, SCREEN))
        // A "비밀번호 찾기" link without any input box is not a form.
        val link = chrome("m.example.co.kr/", node("0/1/0", text = "비밀번호 찾기", clickable = true, bounds = ScreenBounds(40, 400, 400, 460)))
        assertNull(BrowserSensitivity.check(link, SCREEN))
    }

    @Test fun aPaymentPageNeedsTwoCheckoutSignals() {
        val pay = chrome("m.example.co.kr/p",
            node("0/1/0", text = "결제 수단", bounds = ScreenBounds(40, 400, 400, 460)),
            node("0/1/1", text = "최종 결제 금액 41,000원", bounds = ScreenBounds(40, 500, 1040, 560)))
        assertEquals(BrowserSensitiveReason.PAYMENT_PAGE, BrowserSensitivity.check(pay, SCREEN))
        // One signal alone (a "결제하기" button on a product page) is not a payment page.
        val product = chrome("m.example.co.kr/p", node("0/1/0", "android.widget.Button", text = "결제하기", clickable = true, bounds = ScreenBounds(40, 400, 400, 460)))
        assertNull(BrowserSensitivity.check(product, SCREEN))
    }

    @Test fun personalDataOnScreenIsSensitive() {
        for (t in listOf("카드 1234-5678-9012-3456", "900101-1234567", "연락처 010-1234-5678", "hong@example.com")) {
            val page = chrome("m.example.co.kr/", node("0/1/0", text = t, bounds = ScreenBounds(40, 400, 1040, 460)))
            assertEquals(t, BrowserSensitiveReason.PERSONAL_DATA, BrowserSensitivity.check(page, SCREEN))
        }
        // A company phone number is not a mobile number; prices are not card numbers.
        val shop = chrome("m.example.co.kr/", node("0/1/0", text = "고객센터 1588-1234 · 41,000원", bounds = ScreenBounds(40, 400, 1040, 460)))
        assertNull(BrowserSensitivity.check(shop, SCREEN))
    }
}
