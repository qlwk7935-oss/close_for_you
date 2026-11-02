package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.ui.voice.settings.HandlingPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F40 (총괄 11/2): "광고만 닫기" with only "오늘 하루 보지 않기" is asked; other presets press it as before. */
class F40Test {
    private fun close(label: String) =
        CloseCandidate(label, "0/1", null, label, ScreenBounds(0, 0, 100, 50), EvidenceLevel.E2_LABELED_NODE, ButtonRole.CLOSE)

    private val hideToday = close("오늘 하루 보지 않기")
    private val ad = PopupClassification.ADVERTISEMENT

    private fun decided(preset: HandlingPreset, candidates: List<CloseCandidate>) =
        SimpleCloseDecider.decide(ad, false, candidates, preset.autoCloseKinds(), strongEnabled = false)

    @Test fun adsOnlyAsksWhenHideTodayIsTheOnlyClose() {
        val d = decided(HandlingPreset.CLOSE_ADS_ONLY, listOf(hideToday))
        // The decider alone would press it; the gate turns it into a question.
        assertEquals(hideToday, (d as SimpleDecision.AutoClose).candidate)
        assertTrue(HideTodaySafePreset.asks(HandlingPreset.CLOSE_ADS_ONLY.autoCloseKinds(), hideToday))
        assertTrue(HideTodaySafePreset.asks(HandlingPreset.CLOSE_ADS_ONLY.autoCloseKinds(), close("7일간 보지 않기")))
    }

    @Test fun otherPresetsKeepPressingIt() {
        assertFalse(HideTodaySafePreset.asks(HandlingPreset.CLOSE_ADS_AND_COUPONS.autoCloseKinds(), hideToday))
        assertFalse(HideTodaySafePreset.asks(HandlingPreset.CLOSE_ALL.autoCloseKinds(), hideToday))
        // Strong mode (전부 닫기) still picks a plain close first and hide-today next.
        assertEquals("닫기", StrongCloseRules.pick(listOf(hideToday, close("닫기")))?.label)
        assertEquals(hideToday, StrongCloseRules.pick(listOf(hideToday)))
    }

    @Test fun aPlainCloseInAdsOnlyIsPressedAsBefore() {
        val plain = close("닫기")
        assertFalse(HideTodaySafePreset.asks(HandlingPreset.CLOSE_ADS_ONLY.autoCloseKinds(), plain))
        assertEquals(plain, (decided(HandlingPreset.CLOSE_ADS_ONLY, listOf(plain)) as SimpleDecision.AutoClose).candidate)
        // Switch off (nothing consented): nothing to gate.
        assertFalse(HideTodaySafePreset.asks(kotlinx.collections.immutable.persistentSetOf(), hideToday))
    }
}
