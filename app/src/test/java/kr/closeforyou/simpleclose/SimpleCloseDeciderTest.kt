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

    @Test fun unknownIsAskedEvenWhenEveryKindIsInThePreset() {
        val all = HandlingPreset.CLOSE_ALL.autoCloseKinds()
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.UNKNOWN, false, listOf(close("node:1")), all))
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
