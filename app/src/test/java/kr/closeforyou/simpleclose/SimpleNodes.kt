package kr.closeforyou.simpleclose

import kotlinx.collections.immutable.persistentSetOf
import kr.closeforyou.core.model.NodeAction
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds

/**
 * Test trees built from the real-device dumps (LG Velvet, Chrome; structure only, 2026-10-30/31).
 * Texts here are public page wording used as fixtures, not captured screen data.
 */
object SimpleNodes {
    val SCREEN = ScreenBounds(0, 0, 1080, 2340)

    fun node(
        path: String,
        className: String = "android.view.View",
        text: String? = null,
        desc: String? = null,
        clickable: Boolean = false,
        bounds: ScreenBounds = ScreenBounds(0, 0, 1080, 100),
        viewId: String? = null,
        enabled: Boolean = true,
    ) = NodeSnapshot(
        path, viewId, className, text, desc, clickable, enabled, bounds,
        if (clickable) persistentSetOf(NodeAction.CLICK) else persistentSetOf(),
    )

    /** Chrome toolbar + WebView with page content, a bottom AlertDialog install sheet and a zero-height footer. */
    fun elevenStreet(): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.widget.EditText", text = "m.11st.co.kr", clickable = true, bounds = ScreenBounds(100, 60, 900, 180), viewId = "com.android.chrome:id/url_bar"),
        node("0/1", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/1/0", text = "11번가 앱에서 앱 한정 혜택 받기", bounds = ScreenBounds(0, 200, 800, 300)),
        node("0/1/1", "android.widget.Button", text = "앱 열기", clickable = true, bounds = ScreenBounds(800, 200, 1000, 300)),
        node("0/1/2", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(1000, 200, 1080, 300)),
        node("0/1/3", text = "오늘의 특가 상품", bounds = ScreenBounds(0, 320, 1080, 420)),
        node("0/1/4", "android.app.AlertDialog", bounds = ScreenBounds(0, 1632, 1080, 2337)),
        node("0/1/4/0", "android.widget.Button", text = "앱설치 안내 레이어 닫기", clickable = true, bounds = ScreenBounds(960, 1650, 1060, 1750)),
        node("0/1/4/1", bounds = ScreenBounds(40, 1700, 1040, 1900)),
        node("0/1/4/1/0", "android.widget.TextView", text = "앱에서만 제공되는 혜택을 놓치고 계신 건 아닌가요?", bounds = ScreenBounds(40, 1700, 1040, 1800)),
        node("0/1/4/1/1", "android.widget.TextView", text = "지금 바로 11번가 앱에서 확인해보세요!", bounds = ScreenBounds(40, 1800, 1040, 1900)),
        node("0/1/4/2", text = "11번가 앱에서 확인", clickable = true, bounds = ScreenBounds(40, 1950, 1040, 2100)),
        node("0/1/4/3", "android.widget.Button", text = "모바일 웹으로 볼래요", clickable = true, bounds = ScreenBounds(40, 2150, 1040, 2300)),
        // Footer links the page keeps at height 0: invisible, never part of a popup.
        node("0/1/5", text = "로그인", clickable = true, bounds = ScreenBounds(0, 2337, 200, 2337)),
        node("0/1/6", text = "[당첨자공지] 10월15일 이벤트", clickable = true, bounds = ScreenBounds(0, 2337, 600, 2337)),
    )

    /** Auction: a centred AlertDialog holding an image with alt text and a 닫기 button. */
    fun auction(): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/0/0", text = "옥션 오늘의 쇼핑", bounds = ScreenBounds(0, 200, 1080, 300)),
        node("0/0/1", "android.app.AlertDialog", bounds = ScreenBounds(90, 600, 990, 1700)),
        node("0/0/1/0", "android.widget.Image", desc = "가을 특가 광고, 최대 50% 할인", clickable = true, bounds = ScreenBounds(90, 600, 990, 1550)),
        node("0/0/1/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(90, 1550, 990, 1700)),
    )

    /** Olive Young app: a clickable dim background covering the screen, a card with 닫기 and an unlabeled X. */
    fun oliveYoung(): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", text = "올리브영 홈", bounds = ScreenBounds(0, 0, 1080, 200)),
        node("0/1", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/1/0", desc = "배경을 눌러 닫기", clickable = true, bounds = SCREEN, viewId = "kr.co.oliveyoung:id/dimV3"),
        node("0/1/1", "android.widget.LinearLayout", bounds = ScreenBounds(60, 700, 1020, 1700)),
        node("0/1/1/0", "android.widget.TextView", text = "오늘만 특가 쿠폰 이벤트", bounds = ScreenBounds(60, 700, 1020, 800)),
        node("0/1/1/1", "android.widget.ImageView", clickable = true, bounds = ScreenBounds(940, 700, 1020, 780)),
        node("0/1/1/2", text = "쿠폰 받기", clickable = true, bounds = ScreenBounds(60, 1500, 540, 1600)),
        node("0/1/1/3", text = "닫기", clickable = true, bounds = ScreenBounds(540, 1500, 1020, 1600)),
    )

    /** YouTube: an unnamed clickable box whose child text says 건너뛰기. */
    fun youTubeSkip(): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.app.Dialog", bounds = ScreenBounds(0, 400, 1080, 1100)),
        node("0/0/0", "android.widget.TextView", text = "광고 · 5초 후 건너뛰기 가능", bounds = ScreenBounds(0, 400, 1080, 500)),
        node("0/0/1", clickable = true, bounds = ScreenBounds(700, 900, 1080, 1100)),
        node("0/0/1/0", "android.widget.TextView", text = "건너뛰기", bounds = ScreenBounds(720, 920, 1060, 1080)),
    )

    /** Chrome permission bar: a decision ("허용") that must never be pressed. */
    fun chromePermission(): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.app.AlertDialog", bounds = ScreenBounds(0, 1800, 1080, 2340)),
        node("0/0/0", "android.widget.TextView", text = "m.example.com에서 위치 정보 사용 권한을 요청합니다", bounds = ScreenBounds(0, 1800, 1080, 1950)),
        node("0/0/1", "android.widget.Button", text = "차단", clickable = true, bounds = ScreenBounds(0, 2100, 540, 2300)),
        node("0/0/2", "android.widget.Button", text = "허용", clickable = true, bounds = ScreenBounds(540, 2100, 1080, 2300)),
    )
}
