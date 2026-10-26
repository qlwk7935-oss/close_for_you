package kr.closeforyou.qa

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.runBlocking
import kr.closeforyou.accessibility.PrivacyPolicy
import kr.closeforyou.accessibility.capture.DecisionEvidence
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.PopupTrigger
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.SnapshotCompleteness
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.decision.DecisionKind
import kr.closeforyou.decision.DecisionKinds
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.ui.voice.settings.HandlingPreset
import kr.closeforyou.ui.voice.settings.PopupHandlingPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * D: 팝업 재현 앱 정답지(docs/templates/popup_answer_key.csv)가 지금 A·B·C 코드와 맞는지 본다(10/25).
 *
 * - `int1_kind`·`int1_path`: 첫 통합 예상. A 경로를 CaptureOcrPipeline과 같은 순서로 흉내 낸다.
 *   F0-A 민감·불확실 글자 → 차단, 결정 글자 → 고정 요약(DecisionEvidence), 나머지 → 트리 글자 그대로 B 분류.
 *   OCR·화면 캡처·후보 찾기·감지(F1)는 하지 않는다.
 * - 재현 앱이 실제로 어느 창 노드를 넘기는지는 실측으로 봐야 하므로, 팝업 글자만 넘길 때와 같은 창의
 *   본문·제목까지 넘길 때가 같은 결과인지도 확인한다.
 * - `preset_*`: 사람 기준 종류에 C `HandlingPreset`을 적용한 목표와 같은지.
 *
 * 실기기 결과가 아니다. 시험 문구는 합성이며 로그로 남기지 않는다.
 */
class AnswerKeyPipelineTest {
    private val root = listOf(File(".."), File(".")).first { File(it, "docs/templates/popup_answer_key.csv").exists() }

    private fun read(path: String) = File(root, path).readText(Charsets.UTF_8)

    private val rows: List<Map<String, String>> = read("docs/templates/popup_answer_key.csv").lines()
        .filter { it.isNotBlank() }
        .let { lines ->
            val header = lines.first().split(',')
            lines.drop(1).map { header.zip(it.split(',')).toMap() }
        }

    private val strings: Map<String, String> = Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""")
        .findAll(read("popup-test-app/src/main/res/values/strings.xml"))
        .associate { it.groupValues[1] to it.groupValues[2] }

    private val catalogSource = read("popup-test-app/src/main/java/kr/closeforyou/popuptest/ScenarioCatalog.kt")
    private val activitySource = read("popup-test-app/src/main/java/kr/closeforyou/popuptest/PopupScenarioActivity.kt")
    private val webPage = read("popup-test-app/src/main/assets/popup_test_page.html")

    private fun res(name: String): String = strings[name] ?: error("strings.xml에 없음: $name")

    private fun fmt(name: String, vararg args: Int): String = String.format(Locale.ROOT, res(name), *args.toTypedArray())

    private fun catalogText(text: String): String {
        assertTrue("ScenarioCatalog.kt에 없는 문구", "\"$text\"" in catalogSource)
        return text
    }

    private fun webText(text: String): String {
        assertTrue("popup_test_page.html에 없는 문구", text in webPage)
        return text
    }

    /** 시나리오 팝업이 보여 주는 글자(버튼 이름 포함). 팝업이 아니면 null. */
    private fun popupTexts(id: String): List<String>? = when (id) {
        "P01" -> listOf(res("ad_coupon_title"), res("ad_coupon_body"), res("ad_open"), res("close"))
        "P02" -> listOf(res("ad_signup_title"), res("ad_signup_body"), res("ad_open"), res("close"))
        "P03" -> listOf(res("ad_banner_body"), res("ad_install"), res("close"))
        "P04" -> listOf(res("ad_game_title"), res("ad_game_body"), res("ad_open"))
        "P05" -> emptyList()
        "P06" -> listOf(res("ad_fake_x_title"), res("ad_fake_x_body"), res("close"))
        "P07" -> listOf(res("close"))
        "P08" -> listOf(res("pay_title"), res("pay_body"), res("cancel"), res("pay"))
        "P09" -> listOf(res("sub_title"), res("sub_body"), res("later"), res("cancel"))
        "P10" -> listOf(res("consent_title"), res("consent_body"), res("deny"), res("agree"))
        "P11" -> listOf(res("login_title"), res("login_body"), res("login"), res("close"))
        "P12" -> listOf(res("ad_coupon_title"), res("ad_coupon_body"), res("close"))
        // 팝업이 떠 있는 동안 페이지 목록(nav)은 숨긴다.
        "P13" -> listOf(
            webText("시험 본문 화면(웹)"), webText("팝업을 닫은 뒤 이 첫 문단을 찾을 수 있어야 합니다. 이 페이지는 합성 시험용입니다."),
            webText(res("ad_coupon_title")), webText(res("ad_coupon_body")), webText(res("ad_open")), webText(res("close")),
        )
        "P14", "P15" -> null
        "P16" -> listOf(res("video_title"), fmt("video_playing", 0, 3), res("ad_open"), fmt("video_skip_countdown", 2))
        "P17" -> listOf(res("two_ads_first_title"), res("two_ads_body"), res("skip"), res("close"))
        "P18" -> listOf(res("sale_title"), fmt("sale_remaining", 4, 59), res("sale_delivery"), res("ad_open"), res("close"))
        "P19" -> listOf(res("leave_title"), res("leave_body"), res("close"))
        "P20" -> listOf(res("ad_series_1_title"), res("ad_series_1_body"), res("close"))
        "P21" -> listOf(res("prize_title"), res("prize_body"), res("prize_claim"), res("close"))
        "P22" -> listOf(res("infected_title"), res("infected_body"), res("scan_now"), res("close"))
        // 버튼이 생기기 전 화면.
        "P23" -> listOf(res("delayed_title"), res("delayed_body"))
        "P24" -> listOf(
            webText("시험 본문 화면(웹)"), webText("팝업을 닫은 뒤 이 첫 문단을 찾을 수 있어야 합니다. 이 페이지는 합성 시험용입니다."),
            webText("결제 수단을 바꾸려면 약관에 동의해 주세요(시험용 뒤 페이지 문구)."),
            webText(res("ad_coupon_title")), webText(res("ad_coupon_body")), webText(res("ad_open")), webText(res("close")),
        )
        "P25", "P29" -> moaAd(labeled = true)
        "P26" -> moaAd(labeled = false)
        "P27" -> listOf(
            res("moa_coupon_kicker"), res("moa_coupon_title"), res("moa_coupon_condition"), res("moa_coupon_valid"),
            res("moa_coupon_get"), res("close"),
        )
        "P28" -> listOf(
            res("moa_notice_title"), res("close"), res("moa_notice_body"), res("moa_notice_when_label"),
            res("moa_notice_when"), res("moa_notice_what_label"), res("moa_notice_what"), res("moa_confirm"),
        )
        "P30" -> listOf(
            res("moa_ad_badge"), res("moa_video_title"), fmt("moa_video_time", 0, 15), res("moa_video_caption"),
            res("moa_learn_more"), fmt("moa_skip_countdown", 5),
        )
        "P31" -> listOf(
            res("moa_ad_badge"), res("moa_ad2_title"), res("moa_ad2_sub"), res("moa_ad2_old_price"), res("moa_ad2_price"),
            res("moa_ad2_percent"), res("moa_buy_now"), fmt("moa_close_countdown", 3),
        )
        "P32" -> listOf(res("prize_title"), res("prize_body"), res("prize_claim"), res("close"))
        else -> listOf(res("notice_title"), catalogText(NOTICES[id] ?: error("시험 fixture 없음: $id")), res("close"))
    }

    /** 모아마켓 광고(P25·P26). 라벨 있는 X는 설명 "닫기"가 트리에 들어간다. */
    private fun moaAd(labeled: Boolean): List<String> = listOfNotNull(
        res("moa_ad_badge"), if (labeled) res("close") else null,
        res("moa_ad1_title"), res("moa_ad1_sub"), res("moa_ad1_old_price"), res("moa_ad1_price"), res("moa_ad1_percent"),
        res("moa_buy_now"), res("moa_hide_today"),
    )

    /** 같은 창 글자. 모아마켓은 홈 화면, 나머지는 재현 앱 본문 화면. */
    private fun windowTexts(id: String): List<String> = if (id in MOA_MARKET) {
        listOf(
            "moa_brand", "moa_bell", "moa_cart", "moa_search", "moa_chip_fashion", "moa_chip_appliance", "moa_chip_food",
            "moa_chip_living", "moa_chip_camping", "moa_banner_title", "moa_banner_sub", "moa_today",
            "moa_p1_name", "moa_p1_discount", "moa_p1_price", "moa_p2_name", "moa_p2_discount", "moa_p2_price",
            "moa_p3_name", "moa_p3_discount", "moa_p3_price", "moa_p4_name", "moa_p4_discount", "moa_p4_price",
            "moa_nav_home", "moa_nav_category", "moa_nav_search", "moa_nav_wish", "moa_nav_cart",
        ).map { res(it) } + id
    } else {
        listOf(res("background_title"), res("background_body"), id)
    }

    private fun int1(texts: List<String>): Pair<String, String> {
        val extracted = texts.mapIndexed { i, text ->
            ExtractedText(text, ScreenBounds(0, i * 10, 100, i * 10 + 10), TextSource.ACCESSIBILITY_TREE, null)
        }
        val content = extracted.joinToString("\n") { it.text }
        if (PrivacyPolicy.hasSensitiveText(content) || PrivacyPolicy.hasUncertainText(content)) return "BLOCKED" to "A_BLOCK"
        DecisionEvidence.summarize(SNAPSHOT, extracted)?.let { return classify(it.analysis).name to "A_SUMMARY" }
        return classify(PopupAnalysis(SNAPSHOT.token, texts = extracted.toPersistentList())).name to "TREE"
    }

    private fun classify(analysis: PopupAnalysis): PopupClassification =
        runBlocking { DecisionPopupClassifier().classify(analysis) }

    @Test
    fun int1ColumnsMatchTodaysCode() {
        val problems = mutableListOf<String>()
        rows.forEach { row ->
            val id = row.getValue("scenario_id")
            val expected = row["int1_kind"].orEmpty() to row["int1_path"].orEmpty()
            val texts = popupTexts(id)
            if (texts == null) {
                if (expected != ("NONE" to "NONE")) problems += "$id: 팝업 아님인데 정답지 $expected"
                return@forEach
            }
            val popupOnly = int1(texts)
            val withWindow = int1(texts + windowTexts(id))
            if (popupOnly != expected) problems += "$id: 정답지 $expected, 코드 $popupOnly"
            if (withWindow != popupOnly) problems += "$id: 같은 창 본문·제목까지 넣으면 $withWindow"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** 시나리오 이름("광고"·"공지"·"동의" 등)이 같은 창 글자로 잡혀 분류에 섞이지 않게 한다. */
    @Test
    fun screenTitleAndWebListDoNotLeakIntoTheAnalysis() {
        assertTrue("title = scenario?.id ?: getString(R.string.unknown_scenario)" in activitySource)
        assertTrue("nav.hidden = true" in webPage)
    }

    /** S17: 이어서 나오는 광고 4개가 모두 광고로 분류되어야 속도 제한(4번째 묻기)을 볼 수 있다. */
    @Test
    fun adSeriesIsAllAdvertisements() {
        (1..4).forEach { i ->
            val texts = listOf(res("ad_series_${i}_title"), res("ad_series_${i}_body"), res("close"))
            assertEquals("ad_series_$i", "ADVERTISEMENT" to "TREE", int1(texts))
        }
    }

    /** S18: 사기 의심 팝업은 B가 SUSPICIOUS로 본다(화면 분류 UNKNOWN, 정책은 SUSPICIOUS_CONTENT로 거부). */
    @Test
    fun scamPopupsAreSuspiciousToB() {
        listOf("P21", "P22").forEach { id ->
            val texts = popupTexts(id)!!.map {
                ExtractedText(it, ScreenBounds(0, 0, 100, 10), TextSource.ACCESSIBILITY_TREE, null)
            }
            assertEquals(id, DecisionKind.SUSPICIOUS, DecisionKinds.of(PopupAnalysis(SNAPSHOT.token, texts = texts.toPersistentList())))
        }
    }

    /**
     * 참고용: 시연 영상 문구의 '구매'만 바꾸면 지금 코드에서 광고·쿠폰으로 분류된다는 분류 동작만 기록한다.
     * 문구 치환은 안전 판정 우회책으로 채택하지 않았다(#59 리뷰). 재현 앱과 시연은 영상 문구 그대로이고,
     * 구매 버튼은 어떤 경우에도 대신 누르지 않는다(docs/DEMO_SCRIPT.md 5절 ①).
     */
    @Test
    fun demoWordingWithoutThePurchaseWordWouldBeClassifiedAsInTheVideo() {
        val buyNow = res("moa_buy_now")
        assertEquals("ADVERTISEMENT" to "TREE", int1(moaAd(labeled = true).map { if (it == buyNow) "지금 보러 가기" else it }))
        assertEquals("COUPON" to "TREE", int1(popupTexts("P27")!!.map { it.replace("구매 시", "주문 시") }))
        assertEquals("ADVERTISEMENT" to "TREE", int1(popupTexts("P31")!!.map { if (it == buyNow) "지금 보러 가기" else it }))
    }

    @Test
    fun presetGoalColumnsAgreeWithCHandlingPreset() {
        val columns = mapOf(
            HandlingPreset.CLOSE_ALL to "preset_close_all",
            HandlingPreset.CLOSE_ADS_AND_COUPONS to "preset_close_ads_and_coupons",
            HandlingPreset.CLOSE_ADS_ONLY to "preset_close_ads_only",
        )
        assertEquals(HandlingPreset.entries.toSet(), columns.keys)
        rows.forEach { row ->
            columns.forEach { (preset, column) ->
                assertEquals("${row["scenario_id"]} $column", goal(row, preset), row[column])
            }
        }
    }

    private fun goal(row: Map<String, String>, preset: HandlingPreset): String {
        val classification = when (val kind = row["kind"]) {
            "AD" -> PopupClassification.ADVERTISEMENT
            "COUPON" -> PopupClassification.COUPON
            "NOTICE" -> PopupClassification.NOTICE
            "USER_DECISION", "SUSPICIOUS" -> return "ASK"
            "SENSITIVE" -> return "BLOCK"
            "NOT_POPUP" -> return "NONE"
            else -> error("알 수 없는 kind: $kind")
        }
        val preference = preset.preferenceFor(classification)
        val target = row["app_allowed_target"].orEmpty()
        // #46: 자동 건너뛰기는 꺼져 있어 건너뛰기만 있으면 묻는다.
        val closable = target.isNotEmpty() && target != "btn_skip"
        return if (preference == PopupHandlingPreference.CLOSE && !closable) "ASK" else preference.name
    }

    private companion object {
        /** 시연 영상 "모아마켓" 화면(재현 앱 ScenarioCatalog.MOA_MARKET_IDS와 같음). */
        val MOA_MARKET = setOf("P25", "P26", "P27", "P28", "P29", "P30", "P31", "P32")

        val SNAPSHOT = PopupSnapshot(
            token = SnapshotToken(1, 2, "kr.closeforyou.popuptest", "popup", "s", "c", 1),
            capturedAtUptimeMillis = 1_000,
            nodes = persistentListOf(),
            windows = persistentListOf(),
            screenBounds = ScreenBounds(0, 0, 1080, 2400),
            trigger = PopupTrigger.WINDOW_CHANGED,
            completeness = SnapshotCompleteness.COMPLETE,
        )

        private const val NOTICE_BASE = "서비스 점검 안내: 10월 25일 02:00~04:00 동안 주문 기능을 쓸 수 없습니다."

        val NOTICES = mapOf(
            "N01" to NOTICE_BASE,
            "N02" to NOTICE_BASE,
            "N03" to "서비스 점검 안내: 10월 26일 02:00~04:00 동안 주문 기능을 쓸 수 없습니다.",
            "N04" to "서비스 점검 안내: 10월 25일 03:00~06:00 동안 주문 기능을 쓸 수 없습니다.",
            "N05" to "서비스 점검 안내: 10월 25일 02:00~04:00 동안 주문 기능을 쓸 수 없습니다. 보상 3,000포인트 지급.",
            "N06" to "서비스 점검 안내: 10월 25일 02:00~04:00 동안 주문 기능을 쓸 수 없습니다. 점검 전 앱을 업데이트하세요.",
            "N07" to "이용약관이 11월 1일부터 변경됩니다.",
            "N08" to NOTICE_BASE,
        )
    }
}
