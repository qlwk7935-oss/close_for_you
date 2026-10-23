package kr.closeforyou.decision

import androidx.test.ext.junit.runners.AndroidJUnit4
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.core.model.TextSource
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The JVM DecisionEnglishBoundaryTest table on Android's own regex engine (ICU), where `\b` next to
 * Hangul does not hold: with `\b`, half of these were missed on the emulator while the JVM passed.
 * Copied verbatim from EnglishBoundaryCases (androidTest cannot see test sources). Failures name the
 * case index only.
 */
@RunWith(AndroidJUnit4::class)
class DecisionEnglishBoundaryInstrumentedTest {
    private val token = SnapshotToken(1, 1, "test.app", "p", "s", "c", 1)

    private val display = listOf(
        "sign up하기" to PopupClassification.USER_DECISION,
        "allow알림" to PopupClassification.USER_DECISION,
        "Premium login하세요" to PopupClassification.USER_DECISION,
        "AD운동화 특별전" to PopupClassification.ADVERTISEMENT,
        "sponsored콘텐츠" to PopupClassification.ADVERTISEMENT,
        "ADD 버튼 안내" to PopupClassification.NOTICE,
        "payday 안내" to PopupClassification.NOTICE,
        "read me" to PopupClassification.UNKNOWN,
        // "_" is a word character as in \b: identifiers do not match (PR #37 review).
        "AD_banner" to PopupClassification.UNKNOWN,
        "_AD" to PopupClassification.UNKNOWN,
        "sponsored_campaign" to PopupClassification.UNKNOWN,
        "pay_day 안내" to PopupClassification.NOTICE,
        "sign_up 안내" to PopupClassification.NOTICE,
    )
    private val riskyLabels = listOf("ok버튼" to true, "install하기" to true, "okay" to true, "booking" to false, "ok_button" to false)
    private val countdowns = listOf("skip in 5초" to 5, "Skip Ad in 7s" to 7, "Skip in 12 seconds" to 12, "skip in 5000" to null, "skip_in 5" to null)

    @Test fun englishNextToHangulMatchesTheJvmOnAndroid() {
        val wrong = display.withIndex().filter { (_, case) ->
            DecisionPopupClassifier().displayClassification(PopupAnalysis(token, texts = persistentListOf(
                ExtractedText(case.first, ScreenBounds(0, 0, 100, 100), TextSource.ACCESSIBILITY_TREE, null)))) != case.second
        }.map { "display ${it.index}" } +
            riskyLabels.withIndex().filter { (_, c) -> DecisionKeywords.isRiskyButtonLabel(c.first) != c.second }.map { "label ${it.index}" } +
            countdowns.withIndex().filter { (_, c) -> DecisionVideoAd.countdownIn(c.first) != c.second }.map { "countdown ${it.index}" }
        assertTrue("wrong: $wrong", wrong.isEmpty())
    }
}
