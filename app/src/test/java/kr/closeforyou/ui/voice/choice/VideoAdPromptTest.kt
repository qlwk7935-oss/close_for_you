package kr.closeforyou.ui.voice.choice

import kotlinx.collections.immutable.toPersistentList
import kr.closeforyou.R
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.decision.DecisionVideoAd
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.command.KoreanVoiceCommandMatcher
import kr.closeforyou.ui.voice.overlay.PromptSpeech
import kr.closeforyou.ui.voice.overlay.SpeechPart
import kr.closeforyou.ui.voice.settings.AnnouncementDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F5 video ad wording, fed by B's real DecisionVideoAd (Mock analyses, not a real video). */
class VideoAdPromptTest {
    private val video = MockPopupFixtures.videoWithoutAdEvidence().analysis
    private val bounds = video.texts.first().bounds

    private fun text(value: String, source: TextSource = TextSource.ACCESSIBILITY_TREE) = ExtractedText(value, bounds, source, null)

    private fun skip(id: String, label: String, evidence: EvidenceLevel = EvidenceLevel.E2_LABELED_NODE) =
        CloseCandidate(id, "0/$id", null, label, bounds, evidence, ButtonRole.SKIP)

    private fun analysis(vararg texts: ExtractedText, candidates: List<CloseCandidate> = emptyList()) =
        video.copy(texts = texts.toList().toPersistentList(), closeCandidates = candidates.toPersistentList(), videoSurfaceObserved = true)

    private fun info(analysis: PopupAnalysis) = VideoAdInfo.from(DecisionVideoAd.inspect(analysis))

    private fun plan(
        analysis: PopupAnalysis,
        classification: PopupClassification = PopupClassification.ADVERTISEMENT,
        enabled: Boolean = true,
        videoAd: VideoAdInfo? = info(analysis),
    ) = ChoicePromptPlanner.plan(analysis, classification, videoAd = videoAd, videoAdEnabled = enabled)

    private fun spoken(prompt: ChoicePrompt) = PromptSpeech.parts(prompt, AnnouncementDetail.SHORT)

    private fun pressOptions(prompt: ChoicePrompt) = prompt.options.filter { it.kind == PromptOptionKind.PRESS_CANDIDATE }

    @Test fun countdownWithoutButtonSaysWaitAndOffersNoPress() {
        val countdown = analysis(text("광고 · 가상 상품"), text("5초 후 건너뛰기"))
        val prompt = plan(countdown)
        assertEquals(PromptSummary.LIKELY_VIDEO_AD, prompt.summary)
        assertEquals(5, prompt.countdownSeconds)
        assertTrue(pressOptions(prompt).isEmpty())
        assertEquals(
            listOf(SpeechPart(R.string.ui_choice_summary_video_ad), SpeechPart(R.string.ui_choice_video_ad_wait, listOf(5)), SpeechPart(R.string.ui_choice_where)),
            spoken(prompt),
        )
        // Only read/keep: nothing that would wait and press when the countdown ends.
        assertEquals(listOf(PromptOptionKind.READ_CONTENT, PromptOptionKind.KEEP_OPEN), prompt.options.map { it.kind })
    }

    @Test fun conflictingCountdownsGiveNoNumber() {
        val conflict = analysis(text("광고"), text("5초 후 건너뛰기"), text("3초 후 건너뛰기", TextSource.OCR))
        val prompt = plan(conflict)
        assertNull(info(conflict)!!.countdownSeconds)
        assertEquals(PromptSummary.LIKELY_VIDEO_AD, prompt.summary)
        assertNull(prompt.countdownSeconds)
        assertEquals(
            listOf(R.string.ui_choice_summary_video_ad, R.string.ui_choice_cannot_press, R.string.ui_choice_where),
            spoken(prompt).map { it.resId },
        )
    }

    @Test fun noCountdownAndNoButtonIsVideoAdThatCannotBePressed() {
        val prompt = plan(analysis(text("광고 · 가상 상품")))
        assertEquals(PromptSummary.LIKELY_VIDEO_AD, prompt.summary)
        assertNull(prompt.countdownSeconds)
        assertTrue(prompt.cannotPressForUser)
    }

    @Test fun realSkipButtonIsOfferedAsSkipOnceAndEndsTheWait() {
        val withButton = analysis(text("광고 · 가상 상품"), text("5초 후 건너뛰기"), candidates = listOf(skip("s", "광고 건너뛰기")))
        val prompt = plan(withButton)
        val press = pressOptions(prompt).single()
        assertEquals("s", press.candidateId)
        assertEquals(ChoiceKind.SKIP_ONCE, ChoicePromptPlanner.toUserChoice(prompt, press).kind)
        assertNull("a button is there now, so no wait sentence", prompt.countdownSeconds)
        assertEquals(listOf(R.string.ui_choice_summary_video_ad, R.string.ui_choice_where), spoken(prompt).map { it.resId })
    }

    @Test fun countdownLabelOrWeakEvidenceIsNotAPressButton() {
        val notReal = analysis(
            text("광고 · 가상 상품"), text("5초 후 건너뛰기"),
            candidates = listOf(skip("b", "5초 후 건너뛰기"), skip("d", "Skip Ad", EvidenceLevel.E3_USER_BOUND_NODE)),
        )
        val prompt = plan(notReal)
        assertTrue(pressOptions(prompt).isEmpty())
        assertEquals(5, prompt.countdownSeconds)
    }

    @Test fun decisionPopupNeverOffersPressOrVideoAdWording() {
        val withButton = analysis(text("광고 · 가상 상품"), candidates = listOf(skip("s", "광고 건너뛰기")))
        val prompt = plan(withButton, PopupClassification.USER_DECISION, videoAd = VideoAdInfo(5, listOf("s")))
        assertTrue(pressOptions(prompt).isEmpty())
        assertEquals(PromptSummary.NEEDS_REVIEW, prompt.summary)
        assertNull(prompt.videoAd)
    }

    @Test fun onlyAnAdvertisementGetsVideoAdWording() {
        val prompt = plan(analysis(text("공지")), PopupClassification.NOTICE, videoAd = VideoAdInfo(5, emptyList()))
        assertEquals(PromptSummary.LIKELY_NOTICE, prompt.summary)
        assertNull(prompt.videoAd)
    }

    @Test fun aVideoWithoutAdEvidenceGivesNoInfo() {
        assertNull(info(video))
        assertNull(info(analysis(text("광고")).copy(videoSurfaceObserved = false)))
    }

    @Test fun flagOffIsExactlyThePromptWithoutVideoAd() {
        for (a in listOf(
            analysis(text("광고 · 가상 상품"), text("5초 후 건너뛰기")),
            analysis(text("광고 · 가상 상품"), candidates = listOf(skip("s", "광고 건너뛰기"))),
            analysis(text("광고 · 가상 상품")),
        )) {
            val off = plan(a, enabled = false)
            assertEquals(ChoicePromptPlanner.plan(a, PopupClassification.ADVERTISEMENT), off)
            assertEquals(PromptSummary.LIKELY_AD, off.summary)
            assertNull(off.videoAd)
        }
        assertFalse("off until the team turns it on", VideoAdAnnouncementFeature.enabled)
    }

    @Test fun bNumberIsUsedAsIsAndNonPositiveIsDropped() {
        val a = analysis(text("광고 · 가상 상품"))
        assertEquals(42, plan(a, videoAd = VideoAdInfo(42, emptyList())).countdownSeconds)
        assertNull(plan(a, videoAd = VideoAdInfo(0, emptyList())).countdownSeconds)
    }

    /** Spoken while the microphone may be open: no command words (same rule as ui_voice_ lines). */
    @Test fun videoAdSentencesHaveNoCommandWords() {
        val strings = java.io.File("src/main/res/values/ui_voice_strings.xml").readText()
        val lines = listOf("ui_choice_summary_video_ad", "ui_choice_video_ad_wait").map { name ->
            Regex("""<string name="$name">([^<]*)</string>""").find(strings)!!.groupValues[1].replace("%1\$d", "5")
        }
        lines.forEach { assertNull("'$it'", KoreanVoiceCommandMatcher.match(it)) }
    }

    @Test fun noticeCarriesNoScreenText() {
        val prompt = plan(analysis(text("광고 · 비밀 상품명"), text("5초 후 건너뛰기")))
        assertFalse(prompt.toString().contains("비밀"))
        assertFalse(prompt.toString().contains("건너뛰기"))
    }
}
