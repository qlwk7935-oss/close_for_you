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

    private val strings: Map<String, String> = Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""")
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
        else -> listOf(res("notice_title"), catalogText(NOTICES[id] ?: error("시험 fixture 없음: $id")), res("close"))
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
            val withWindow = int1(texts + listOf(res("background_title"), res("background_body"), id))
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
            "USER_DECISION" -> return "ASK"
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
