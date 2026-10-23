package kr.closeforyou.decision

import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * English words right next to Hangul ("sign up하기", "AD광고"): `\b` matched there on the JDK but not
 * on Android's ICU engine, so today's classifier missed them on phones while these JVM tests passed.
 * The same table runs on the emulator in DecisionKindInstrumentedTest.
 */
class DecisionEnglishBoundaryTest {
    @Test fun displayClassifierCatchesEnglishNextToHangul() {
        for ((text, expected) in EnglishBoundaryCases.display) {
            val analysis = PopupAnalysis(MockPopupFixtures.notice().snapshot.token,
                texts = persistentListOf(ExtractedText(text, ScreenBounds(0, 0, 100, 100), TextSource.ACCESSIBILITY_TREE, null)))
            assertEquals(text, expected, DecisionPopupClassifier().displayClassification(analysis))
        }
    }

    @Test fun buttonGradesAndSkipCountdownsToo() {
        for ((label, risky) in EnglishBoundaryCases.riskyLabels) assertEquals(label, risky, DecisionKeywords.isRiskyButtonLabel(label))
        for ((text, seconds) in EnglishBoundaryCases.countdowns) assertEquals(text, seconds, DecisionVideoAd.countdownIn(text))
    }
}

/** Shared expectations, copied verbatim into the instrumented test (androidTest cannot see test sources). */
internal object EnglishBoundaryCases {
    val display: List<Pair<String, PopupClassification>> = listOf(
        "sign up하기" to PopupClassification.USER_DECISION,
        "allow알림" to PopupClassification.USER_DECISION,
        "Premium login하세요" to PopupClassification.USER_DECISION,
        "AD운동화 특별전" to PopupClassification.ADVERTISEMENT,
        "sponsored콘텐츠" to PopupClassification.ADVERTISEMENT,
        // Still not words inside other words.
        "ADD 버튼 안내" to PopupClassification.NOTICE,
        "payday 안내" to PopupClassification.NOTICE,
        "read me" to PopupClassification.UNKNOWN,
    )
    val riskyLabels: List<Pair<String, Boolean>> = listOf("ok버튼" to true, "install하기" to true, "okay" to true, "booking" to false)
    val countdowns: List<Pair<String, Int?>> = listOf("skip in 5초" to 5, "Skip Ad in 7s" to 7, "Skip in 12 seconds" to 12, "skip in 5000" to null)
}
