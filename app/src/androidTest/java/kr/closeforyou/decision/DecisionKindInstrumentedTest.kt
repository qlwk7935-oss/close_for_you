package kr.closeforyou.decision

import androidx.test.ext.junit.runners.AndroidJUnit4
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.core.model.TextSource
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The auto-close kinds on Android's own regex engine (ICU) and NFKC, where `\b` next to Hangul and
 * Unicode folding differ from the JDK (AUTO_CLOSE.md 3절). Same cases as the JVM review tests in
 * DecisionAutoCloseTest; failures name the case only, never screen text in logs.
 */
@RunWith(AndroidJUnit4::class)
class DecisionKindInstrumentedTest {
    private val token = SnapshotToken(1, 1, "test.app", "p", "s", "c", 1)

    private fun kindOf(vararg texts: String) = DecisionKinds.of(PopupAnalysis(token, texts = texts.mapIndexed { i, t ->
        ExtractedText(t, ScreenBounds(0, i * 100, 1000, i * 100 + 90), TextSource.ACCESSIBILITY_TREE, null)
    }.toPersistentList()))

    private val cases: List<Pair<List<String>, DecisionKind>> = listOf(
        // Mixed scripts: ASCII boundaries must match next to Hangul on ICU as on the JDK.
        listOf("Premium으로 upgrade하세요", "광고") to DecisionKind.USER_DECISION,
        listOf("checkout하기", "SALE") to DecisionKind.USER_DECISION,
        listOf("sign up하고 혜택", "광고") to DecisionKind.USER_DECISION,
        listOf("coupon받기") to DecisionKind.COUPON,
        listOf("SALE중", "닫기") to DecisionKind.ADVERTISEMENT,
        // Folding: full width, spacing, zero width and Hangul fillers.
        listOf("AD", "Ａｌｌｏｗ notifications") to DecisionKind.USER_DECISION,
        listOf("광고", "ＳＩＧＮ ＵＰ today") to DecisionKind.USER_DECISION,
        listOf("광고", "알림 수신을 허 용해 주세요") to DecisionKind.USER_DECISION,
        listOf("알림을 허ㅤ용해 주세요", "광고") to DecisionKind.USER_DECISION,
        listOf("경품 당​첨! 상품권 받기") to DecisionKind.SUSPICIOUS,
        listOf("경품 당ㅤ첨 쿠폰") to DecisionKind.SUSPICIOUS,
        listOf("ＣＯＮＧＲＡＴＵＬＡＴＩＯＮＳ", "Get your coupon") to DecisionKind.SUSPICIOUS,
        // The plain kinds.
        listOf("쿠폰이 도착했어요", "닫기") to DecisionKind.COUPON,
        listOf("광고", "신상 운동화", "닫기") to DecisionKind.ADVERTISEMENT,
        listOf("공지", "서비스 점검 안내", "닫기") to DecisionKind.NOTICE,
        listOf("마일리지 소멸 예정 안내", "닫기") to DecisionKind.NOTICE,
        listOf("특가 상품을 주문하시겠어요?", "주문하기") to DecisionKind.USER_DECISION,
        listOf("아직도 정가로 사세요? 최대 50% 할인") to DecisionKind.ADVERTISEMENT,
        listOf("Selected items 50% off") to DecisionKind.ADVERTISEMENT,
        listOf("You have won a prize", "Close") to DecisionKind.SUSPICIOUS,
    )

    // Same table as EnglishBoundaryCases in the JVM DecisionEnglishBoundaryTest (today's classifier).
    private val display = listOf(
        "sign up하기" to PopupClassification.USER_DECISION,
        "allow알림" to PopupClassification.USER_DECISION,
        "Premium login하세요" to PopupClassification.USER_DECISION,
        "AD운동화 특별전" to PopupClassification.ADVERTISEMENT,
        "sponsored콘텐츠" to PopupClassification.ADVERTISEMENT,
        "ADD 버튼 안내" to PopupClassification.NOTICE,
        "payday 안내" to PopupClassification.NOTICE,
        "read me" to PopupClassification.UNKNOWN,
    )
    private val riskyLabels = listOf("ok버튼" to true, "install하기" to true, "okay" to true, "booking" to false)
    private val countdowns = listOf("skip in 5초" to 5, "Skip Ad in 7s" to 7, "Skip in 12 seconds" to 12, "skip in 5000" to null)

    @Test fun todaysClassifierCatchesEnglishNextToHangulOnAndroid() {
        val wrong = display.withIndex().filter { (_, case) ->
            DecisionPopupClassifier().displayClassification(PopupAnalysis(token, texts = listOf(
                ExtractedText(case.first, ScreenBounds(0, 0, 100, 100), TextSource.ACCESSIBILITY_TREE, null)).toPersistentList())) != case.second
        }.map { "display ${it.index}" } +
            riskyLabels.withIndex().filter { (_, c) -> DecisionKeywords.isRiskyButtonLabel(c.first) != c.second }.map { "label ${it.index}" } +
            countdowns.withIndex().filter { (_, c) -> DecisionVideoAd.countdownIn(c.first) != c.second }.map { "countdown ${it.index}" }
        assertTrue("wrong: $wrong", wrong.isEmpty())
    }

    @Test fun everyCaseGetsTheSameKindOnAndroid() {
        val wrong = cases.withIndex().filter { (_, case) -> kindOf(*case.first.toTypedArray()) != case.second }.map { "case ${it.index}" }
        assertTrue("wrong: $wrong", wrong.isEmpty())
    }
}
