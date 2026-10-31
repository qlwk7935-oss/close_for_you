package kr.closeforyou.simpleclose

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.test.runTest
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.choice.RememberChoiceRules
import kr.closeforyou.ui.voice.settings.HandlingPreset
import kr.closeforyou.ui.voice.settings.UiVoiceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleCloseDeciderTest {
    private fun close(id: String) = CloseCandidate(id, id.removePrefix("node:"), null, "닫기", ScreenBounds(0, 0, 10, 10), EvidenceLevel.E2_LABELED_NODE, ButtonRole.CLOSE)
    private val adsOnly = persistentSetOf(PopupClassification.ADVERTISEMENT)

    private val consented = UiVoiceSettings(
        handlingPreset = HandlingPreset.CLOSE_ADS_ONLY, onboardingDone = true,
        autoCloseConsent = true, autoCloseConsentPreset = HandlingPreset.CLOSE_ADS_ONLY,
    )

    @Test fun adWithOneCloseButtonAndConsentClosesWithoutAsking() {
        val decision = SimpleCloseDecider.decide(PopupClassification.ADVERTISEMENT, false, listOf(close("node:1")), consented)
        assertEquals(SimpleDecision.AutoClose(close("node:1")), decision)
    }

    @Test fun presetAloneWithoutTheSwitchStillAsks() {
        val noSwitch = consented.copy(autoCloseConsent = false)
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.ADVERTISEMENT, false, listOf(close("node:1")), noSwitch))
        val notOnboarded = consented.copy(onboardingDone = false)
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.ADVERTISEMENT, false, listOf(close("node:1")), notOnboarded))
    }

    @Test fun kindsThePresetAsksAboutAreAsked() {
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.NOTICE, false, listOf(close("node:1")), adsOnly))
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.COUPON, false, listOf(close("node:1")), adsOnly))
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.UNKNOWN, false, listOf(close("node:1")), adsOnly))
    }

    @Test fun unknownIsAskedInEveryPresetWhenTheDemoSwitchIsOff() {
        HandlingPreset.entries.forEach { preset ->
            assertEquals(preset.name, SimpleDecision.Ask,
                SimpleCloseDecider.decide(PopupClassification.UNKNOWN, false, listOf(close("node:1")), preset.autoCloseKinds(), unknownEnabled = false))
        }
    }

    // ---- F7 (재호 10/31, demo mode 6-0): "전부 자동 닫기" closes a safe UNKNOWN popup ----

    private fun skip(id: String) = close(id).copy(label = "건너뛰기", role = ButtonRole.SKIP)

    /** Three presets × (all conditions met / a forbidden button / two close buttons / suspicious). */
    @Test fun unknownPopupMatrix() {
        val one = listOf(close("node:1"))
        val two = listOf(close("node:1"), close("node:2"))
        HandlingPreset.entries.forEach { preset ->
            val kinds = preset.autoCloseKinds()
            fun decide(candidates: List<CloseCandidate>, forbidden: Int = 0, suspicious: Boolean = false) =
                SimpleCloseDecider.decide(PopupClassification.UNKNOWN, suspicious, candidates, kinds, forbidden, unknownEnabled = true)
            val expectedClean = if (preset == HandlingPreset.CLOSE_ALL) SimpleDecision.AutoClose(close("node:1"), unknown = true) else SimpleDecision.Ask
            assertEquals("${preset.name} clean", expectedClean, decide(one))
            assertEquals("${preset.name} forbidden", SimpleDecision.Ask, decide(one, forbidden = 1))
            assertEquals("${preset.name} two close", SimpleDecision.Ask, decide(two))
            assertEquals("${preset.name} suspicious", SimpleDecision.DenySuspicious, decide(one, suspicious = true))
        }
    }

    @Test fun unknownNeedsTheOnlyButtonToBeAClose() {
        val all = HandlingPreset.CLOSE_ALL.autoCloseKinds()
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.UNKNOWN, false, listOf(skip("node:1")), all, unknownEnabled = true))
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.UNKNOWN, false, listOf(close("node:1"), skip("node:2")), all, unknownEnabled = true))
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.UNKNOWN, false, emptyList(), all, unknownEnabled = true))
        // A decision is never closed, whatever the switch.
        assertEquals(SimpleDecision.DenyDecision, SimpleCloseDecider.decide(PopupClassification.USER_DECISION, false, listOf(close("node:1")), all, unknownEnabled = true))
    }

    @Test fun unknownNeedsTheLiveConsentNotJustThePreset() {
        val closeAll = UiVoiceSettings(handlingPreset = HandlingPreset.CLOSE_ALL, onboardingDone = true, autoCloseConsent = true, autoCloseConsentPreset = HandlingPreset.CLOSE_ALL)
        assertTrue(SimpleCloseDecider.decide(PopupClassification.UNKNOWN, false, listOf(close("node:1")), closeAll) is SimpleDecision.AutoClose)
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.UNKNOWN, false, listOf(close("node:1")), closeAll.copy(autoCloseConsent = false)))
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.UNKNOWN, false, listOf(close("node:1")), closeAll, forbiddenCount = 1))
    }

    @Test fun elevenStreetSheetStaysAskedUnderCloseAll() = runTest {
        // "11번가 앱에서 확인" is a forbidden button and there are two closes: asked even if B said UNKNOWN.
        val nodes = SimpleNodes.elevenStreet()
        val popup = SimplePopupAnalysis.build(nodes, PopupRootFinder.find(nodes, SCREEN)!!, "com.android.chrome", 7, SCREEN, 1L, 1000L)
        assertTrue(popup.scan.forbiddenCount > 0)
        val all = HandlingPreset.CLOSE_ALL.autoCloseKinds()
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.UNKNOWN, false, popup.scan.candidates, all, popup.scan.forbiddenCount, unknownEnabled = true))
    }

    @Test fun noOrSeveralCloseButtonsAsk() {
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.ADVERTISEMENT, false, emptyList(), adsOnly))
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.ADVERTISEMENT, false, listOf(close("node:1"), close("node:2")), adsOnly))
    }

    @Test fun decisionAndSuspiciousAreDeniedWhateverThePreset() {
        val all = HandlingPreset.CLOSE_ALL.autoCloseKinds()
        assertEquals(SimpleDecision.DenyDecision, SimpleCloseDecider.decide(PopupClassification.USER_DECISION, false, listOf(close("node:1")), all))
        assertEquals(SimpleDecision.DenySuspicious, SimpleCloseDecider.decide(PopupClassification.ADVERTISEMENT, true, listOf(close("node:1")), all))
    }

    @Test fun auctionDumpThroughBIsAnAdWithOneCloseButton() = runTest {
        val nodes = SimpleNodes.auction()
        val root = PopupRootFinder.find(nodes, SCREEN)!!
        val popup = SimplePopupAnalysis.build(nodes, root, "com.android.chrome", 7, SCREEN, 1L, 1000L)
        val classification = DecisionPopupClassifier().classify(popup.analysis)
        assertEquals(PopupClassification.ADVERTISEMENT, classification)
        assertFalse(RememberChoiceRules.suspiciousByB(popup.analysis))
        val decision = SimpleCloseDecider.decide(classification, false, popup.scan.candidates, consented)
        assertTrue(decision is SimpleDecision.AutoClose)
        assertEquals("node:0/0/1/1", (decision as SimpleDecision.AutoClose).candidate.id)
    }

    @Test fun elevenStreetDumpIsAskedAndThePromptOffersBothCloseButtons() = runTest {
        val nodes = SimpleNodes.elevenStreet()
        val root = PopupRootFinder.find(nodes, SCREEN)!!
        val popup = SimplePopupAnalysis.build(nodes, root, "com.android.chrome", 7, SCREEN, 1L, 1000L)
        // Only the sheet's texts reach B: the footer's "로그인" and "당첨자공지" are invisible.
        assertTrue(popup.analysis.texts.none { "당첨" in it.text || "로그인" in it.text })
        val classification = DecisionPopupClassifier().classify(popup.analysis)
        assertFalse(RememberChoiceRules.suspiciousByB(popup.analysis))
        // Two close buttons: the person picks (B on main still calls this install sheet UNKNOWN).
        val decision = SimpleCloseDecider.decide(classification, false, popup.scan.candidates, consented)
        assertEquals(SimpleDecision.Ask, decision)
        val prompt = ChoicePromptPlanner.plan(popup.analysis, classification, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false)
        assertEquals(listOf("앱설치 안내 레이어 닫기", "모바일 웹으로 볼래요"), prompt.options.filter { it.kind == PromptOptionKind.PRESS_CANDIDATE }.map { it.buttonLabel })
        assertFalse(prompt.cannotPressForUser)
    }

    @Test fun chromePermissionDumpIsADecisionWithNothingToPress() = runTest {
        val nodes = SimpleNodes.chromePermission()
        val root = PopupRootFinder.find(nodes, SCREEN)!!
        val popup = SimplePopupAnalysis.build(nodes, root, "com.android.chrome", 7, SCREEN, 1L, 1000L)
        val classification = DecisionPopupClassifier().classify(popup.analysis)
        assertEquals(PopupClassification.USER_DECISION, classification)
        assertTrue(popup.scan.candidates.isEmpty())
        assertEquals(SimpleDecision.DenyDecision, SimpleCloseDecider.decide(classification, false, popup.scan.candidates, HandlingPreset.CLOSE_ALL.autoCloseKinds()))
    }

    @Test fun fingerprintIsStableForTheSameTreeAndDiffersForAnother() {
        val a = SimplePopupAnalysis.build(SimpleNodes.auction(), PopupRootFinder.find(SimpleNodes.auction(), SCREEN)!!, "p", 7, SCREEN, 1L, 0L)
        val b = SimplePopupAnalysis.build(SimpleNodes.auction(), PopupRootFinder.find(SimpleNodes.auction(), SCREEN)!!, "p", 7, SCREEN, 2L, 5L)
        val c = SimplePopupAnalysis.build(SimpleNodes.elevenStreet(), PopupRootFinder.find(SimpleNodes.elevenStreet(), SCREEN)!!, "p", 7, SCREEN, 3L, 9L)
        assertEquals(a.fingerprint, b.fingerprint)
        assertTrue(a.fingerprint != c.fingerprint)
    }
}
