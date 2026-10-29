package kr.closeforyou.ui.voice.choice

import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.settings.HandlingPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where [앞으로 항상 닫기] shows (C 재호 decision 10/30). Synthetic words only. */
class RememberChoiceRulesTest {
    private val asked = listOf(PopupClassification.ADVERTISEMENT, PopupClassification.NOTICE, PopupClassification.COUPON)
    private val bounds = ScreenBounds(0, 0, 10, 10)
    private fun text(value: String) = ExtractedText(value, bounds, TextSource.ACCESSIBILITY_TREE, null)
    private fun candidate(id: String = "c", nodePath: String? = "1") =
        CloseCandidate(id, nodePath, null, "닫기", bounds, EvidenceLevel.E2_LABELED_NODE, ButtonRole.CLOSE)

    private fun offers(classification: PopupClassification, preset: HandlingPreset?, text: String? = "이벤트 안내", suspicious: Boolean = false) =
        RememberChoiceRules.offers(classification, text, preset, suspicious)

    /** The team's table (재호 10/30): preset × kind → shown. Fixed; change only with the team. */
    private val table = mapOf(
        HandlingPreset.CLOSE_ALL to mapOf(
            PopupClassification.ADVERTISEMENT to false, PopupClassification.COUPON to false, PopupClassification.NOTICE to false,
        ),
        HandlingPreset.CLOSE_ADS_AND_COUPONS to mapOf(
            PopupClassification.ADVERTISEMENT to false, PopupClassification.COUPON to false, PopupClassification.NOTICE to true,
        ),
        HandlingPreset.CLOSE_ADS_ONLY to mapOf(
            PopupClassification.ADVERTISEMENT to false, PopupClassification.COUPON to true, PopupClassification.NOTICE to true,
        ),
    )

    @Test fun nineCellsOfThePresetTable() {
        assertEquals(9, table.values.sumOf { it.size })
        table.forEach { (preset, row) ->
            row.forEach { (kind, shown) -> assertEquals("$preset $kind", shown, offers(kind, preset)) }
        }
    }

    @Test fun nineCellsThroughThePlanner() {
        table.forEach { (preset, row) ->
            row.forEach { (kind, shown) -> assertEquals("$preset $kind", if (shown) 1 else 0, plan(kind, preset).rememberCount()) }
        }
    }

    @Test fun unknownWithInformationShownUnderEveryPreset() {
        HandlingPreset.entries.forEach { assertEquals("$it", 1, plan(PopupClassification.UNKNOWN, it).rememberCount()) }
    }

    @Test fun unknownWithoutInformationHiddenUnderEveryPreset() {
        HandlingPreset.entries.forEach {
            assertEquals("$it", 0, plan(PopupClassification.UNKNOWN, it, texts = emptyList()).rememberCount())
        }
    }

    @Test fun unknownUnderEveryPreset() {
        (HandlingPreset.entries + listOf<HandlingPreset?>(null)).forEach {
            assertTrue("$it", offers(PopupClassification.UNKNOWN, it))
        }
    }

    @Test fun unknownPresetHidesNoKind() {
        asked.forEach { assertTrue("$it", offers(it, null)) }
    }

    @Test fun importantDecisionNeverUnderAnyPreset() {
        (HandlingPreset.entries + listOf<HandlingPreset?>(null)).forEach {
            assertFalse("$it", offers(PopupClassification.USER_DECISION, it))
        }
    }

    @Test fun scamLikeNeverEvenWhenUnknown() {
        assertFalse(offers(PopupClassification.UNKNOWN, HandlingPreset.CLOSE_ADS_ONLY, suspicious = true))
        assertFalse(offers(PopupClassification.NOTICE, HandlingPreset.CLOSE_ADS_ONLY, suspicious = true))
    }

    @Test fun nothingToRecogniseItByHides() {
        assertFalse(offers(PopupClassification.UNKNOWN, HandlingPreset.CLOSE_ADS_ONLY, text = null))
        assertFalse(offers(PopupClassification.UNKNOWN, HandlingPreset.CLOSE_ADS_ONLY, text = "  "))
    }

    @Test fun suspiciousComesFromBsOwnRule() {
        val scam = MockPopupFixtures.advertisement().analysis.copy(texts = persistentListOf(text("축하합니다 1등 당첨")))
        val plain = MockPopupFixtures.advertisement().analysis.copy(texts = persistentListOf(text("오늘의 할인 상품")))
        assertTrue(RememberChoiceRules.suspiciousByB(scam))
        assertFalse(RememberChoiceRules.suspiciousByB(plain))
    }

    // --- Through the planner ---

    private fun plan(
        classification: PopupClassification,
        preset: HandlingPreset?,
        texts: List<String> = listOf("이벤트 안내"),
        vararg candidates: CloseCandidate = arrayOf(candidate()),
    ) = ChoicePromptPlanner.plan(
        MockPopupFixtures.advertisement().analysis.copy(
            texts = texts.map(::text).let { persistentListOf(*it.toTypedArray()) },
            closeCandidates = persistentListOf(*candidates),
        ),
        classification,
        rememberChoicesEnabled = true,
        preset = preset,
    )

    private fun ChoicePrompt.rememberCount() = options.count { it.kind == PromptOptionKind.REMEMBER_ALWAYS }

    @Test fun plannerShowsItForAnAskedCoupon() {
        assertEquals(1, plan(PopupClassification.COUPON, HandlingPreset.CLOSE_ADS_ONLY).rememberCount())
    }

    @Test fun plannerHidesItUnderCloseAll() {
        assertEquals(0, plan(PopupClassification.ADVERTISEMENT, HandlingPreset.CLOSE_ALL).rememberCount())
        assertEquals(1, plan(PopupClassification.UNKNOWN, HandlingPreset.CLOSE_ALL).rememberCount())
    }

    @Test fun plannerHidesItForAnImageOnlyPopup() {
        val prompt = plan(PopupClassification.UNKNOWN, HandlingPreset.CLOSE_ADS_ONLY, texts = emptyList())
        assertEquals(0, prompt.rememberCount())
        // The one-time press is still offered.
        assertTrue(prompt.options.any { it.kind == PromptOptionKind.PRESS_CANDIDATE })
    }

    @Test fun plannerHidesItForAScamLikePopupByDefault() {
        val prompt = plan(PopupClassification.UNKNOWN, HandlingPreset.CLOSE_ADS_ONLY, texts = listOf("축하합니다 1등 당첨"))
        assertEquals(0, prompt.rememberCount())
    }

    @Test fun onlyButtonsDCouldKeyGetIt() {
        val prompt = plan(
            PopupClassification.NOTICE, HandlingPreset.CLOSE_ADS_ONLY, listOf("이벤트 안내"),
            candidate("with", "1"), candidate("without", null),
        )
        val remember = prompt.options.filter { it.kind == PromptOptionKind.REMEMBER_ALWAYS }
        assertEquals(listOf("with"), remember.map { it.candidateId })
    }
}
