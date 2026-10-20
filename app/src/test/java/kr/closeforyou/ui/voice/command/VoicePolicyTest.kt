package kr.closeforyou.ui.voice.command

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kr.closeforyou.R
import kr.closeforyou.core.contract.VoiceCommand
import kr.closeforyou.core.contract.VoiceEvent
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePolicyTest {
    private fun candidate(id: String, label: String, role: ButtonRole) =
        CloseCandidate(id, "1", null, label, ScreenBounds(0, 0, 10, 10), EvidenceLevel.E2_LABELED_NODE, role)

    private fun prompt(
        vararg candidates: CloseCandidate,
        classification: PopupClassification = PopupClassification.ADVERTISEMENT,
    ) = ChoicePromptPlanner.plan(
        MockPopupFixtures.advertisement().analysis.copy(closeCandidates = persistentListOf(*candidates)),
        classification,
    )

    private val adWithClose = prompt(candidate("c", "닫기", ButtonRole.CLOSE), candidate("s", "건너뛰기", ButtonRole.SKIP))

    private fun chosenKind(sentence: String, prompt: ChoicePrompt): ChoiceKind? =
        (VoicePolicy.decide(KoreanVoiceCommandMatcher.classify(sentence), prompt) as? VoiceOutcome.Choose)
            ?.let { ChoicePromptPlanner.toUserChoice(prompt, it.option).kind }

    private fun said(sentence: String, prompt: ChoicePrompt = adWithClose): Int? =
        (VoicePolicy.decide(KoreanVoiceCommandMatcher.classify(sentence), prompt) as? VoiceOutcome.Say)?.textRes

    // --- classify ---

    @Test fun refusedWordsWinOverCommands() = listOf(
        "결제해", "구매해 줘", "구독 닫아", "삭제해", "권한 허용해", "동의해 줘", "로그인 해 줘", "회원 가입", "주문해",
    ).forEach { assertEquals("'$it'", VoiceUtterance.Refused, KoreanVoiceCommandMatcher.classify(it)) }

    @Test fun alwaysAndStopAskingNeedATap() = listOf(
        "항상 닫아", "앞으로 계속 닫아", "매번 닫아 줘", "다시 묻지 마", "묻지 마", "그만 물어봐",
    ).forEach { assertEquals("'$it'", VoiceUtterance.NeedsTapConfirm, KoreanVoiceCommandMatcher.classify(it)) }

    @Test fun plainCommandsStayCommands() {
        assertEquals(VoiceUtterance.Command(VoiceCommand.CLOSE), KoreanVoiceCommandMatcher.classify("닫아 줘"))
        assertEquals(VoiceUtterance.Command(VoiceCommand.READ_CONTENT), KoreanVoiceCommandMatcher.classify("다시 읽어 줘"))
        assertEquals(VoiceUtterance.Command(VoiceCommand.KEEP_OPEN), KoreanVoiceCommandMatcher.classify("닫지 마"))
    }

    @Test fun unclearOrLongSpeechIsNotUnderstood() = listOf<String?>(
        null, "", "안녕하세요", "확인", "닫아 아니 그대로 둬", "그 영화에서 주인공이 결제를 하고 문을 닫았대",
    ).forEach { assertEquals("'$it'", VoiceUtterance.NotUnderstood, KoreanVoiceCommandMatcher.classify(it)) }

    @Test fun contractEventsCarryOnlyCommands() {
        val token = adWithClose.token
        assertEquals(VoiceEvent.Recognized(VoiceCommand.SKIP, token), VoiceUtterance.Command(VoiceCommand.SKIP).toEvent(token))
        assertEquals(VoiceEvent.Unavailable, VoiceUtterance.Unavailable.toEvent(token))
        listOf(VoiceUtterance.Refused, VoiceUtterance.NeedsTapConfirm, VoiceUtterance.NotUnderstood, VoiceUtterance.Silent)
            .forEach { assertEquals(VoiceEvent.Cancelled, it.toEvent(token)) }
    }

    // --- decide: the policy table ---

    @Test fun directRequestsPickVisibleOptions() {
        assertEquals(ChoiceKind.CLOSE_ONCE, chosenKind("닫아", adWithClose))
        assertEquals(ChoiceKind.SKIP_ONCE, chosenKind("건너뛰어", adWithClose))
        assertEquals(ChoiceKind.READ_CONTENT, chosenKind("읽어 줘", adWithClose))
        assertEquals(ChoiceKind.KEEP_OPEN, chosenKind("그대로 둬", adWithClose))
    }

    @Test fun tapOnlyAndRefusedNeverChoose() {
        assertEquals(R.string.ui_voice_pick_on_screen, said("항상 닫아"))
        assertEquals(R.string.ui_voice_pick_on_screen, said("다시 묻지 마"))
        assertEquals(R.string.ui_voice_refused, said("결제해"))
        assertEquals(R.string.ui_voice_refused, said("동의해 줘"))
    }

    @Test fun decisionPopupCannotBeClosedByVoice() {
        val decision = prompt(candidate("c", "닫기", ButtonRole.CLOSE), classification = PopupClassification.USER_DECISION)
        assertEquals(R.string.ui_choice_cannot_press, said("닫아", decision))
        assertEquals(ChoiceKind.KEEP_OPEN, chosenKind("그대로 둬", decision))
    }

    @Test fun hiddenOptionIsNeverRunByVoice() {
        val noRead = adWithClose.copy(
            options = adWithClose.options.filterNot { it.kind == PromptOptionKind.READ_CONTENT }.toImmutableList(),
        )
        assertEquals(R.string.ui_voice_not_here, said("읽어 줘", noRead))
    }

    @Test fun otherCommandsAreNotHandledHere() {
        assertEquals(R.string.ui_voice_not_here, said("뒤로 가"))
        assertEquals(R.string.ui_voice_not_here, said("무슨 그림이야"))
        // [버튼으로 이동] is off (MoveToButtonFeature), so there is no option to pick.
        assertEquals(R.string.ui_voice_not_here, said("버튼으로 이동"))
    }

    @Test fun silenceIsQuietAndUnclearIsSaid() {
        assertEquals(VoiceOutcome.Quiet, VoicePolicy.decide(VoiceUtterance.Silent, adWithClose))
        assertEquals(R.string.ui_voice_not_understood, said("안녕하세요"))
        assertEquals(
            VoiceOutcome.Say(R.string.ui_voice_unavailable),
            VoicePolicy.decide(VoiceUtterance.Unavailable, adWithClose),
        )
    }

    /** No outcome sentence may contain a command word: TalkBack reads it while the mic could be open. */
    @Test fun voiceSentencesHaveNoCommandWords() {
        val strings = java.io.File("src/main/res/values/ui_voice_strings.xml").readText()
        val voiceLines = Regex("""<string name="ui_voice_[a-z_]+">([^<]*)</string>""").findAll(strings).map { it.groupValues[1] }.toList()
        assertTrue(voiceLines.size >= 10)
        voiceLines.forEach { line ->
            assertEquals("'$line'", null, KoreanVoiceCommandMatcher.match(line))
        }
    }
}
