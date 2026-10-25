package kr.closeforyou.ui.voice

import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kr.closeforyou.R
import kr.closeforyou.core.model.ActionAuthorization
import kr.closeforyou.core.model.ActionDecision
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.ClickOutcome
import kr.closeforyou.core.model.DecisionSource
import kr.closeforyou.core.model.NextStep
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.SafetyReason
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.choice.PromptSummary
import kr.closeforyou.ui.voice.choice.VideoAdInfo
import kr.closeforyou.ui.voice.command.KoreanVoiceCommandMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract v2 (#38) sentences, rendered from the real ui_voice_strings.xml. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoDecisionSpeechTest {
    private val token = MockPopupFixtures.notice().snapshot.token
    private val ad = PopupClassification.ADVERTISEMENT

    private fun decision(next: NextStep, vararg reasons: SafetyReason) = ActionDecision(
        token = token,
        authorization = when (next) {
            NextStep.EXECUTE -> ActionAuthorization.ALLOW_ONCE
            NextStep.DENY -> ActionAuthorization.DENY
            else -> ActionAuthorization.NEEDS_CONFIRMATION
        },
        reasons = reasons.toSet().toImmutableSet(),
        candidateId = if (next == NextStep.EXECUTE) "close" else null,
        source = DecisionSource.PRESET,
        next = next,
    )

    private val execute = decision(NextStep.EXECUTE)
    private fun pressed(outcome: ClickOutcome?, status: ActionStatus = ActionStatus.MECHANICAL_SUCCESS) =
        ActionResult(token, status, reason = null, attempted = true, outcome = outcome)

    private fun say(speech: AutoSpeech): String = AutoDecisionSpeech.render((speech as AutoSpeech.Say).lines, Strings.format)

    /**
     * Feeds [speeches] through a real batcher and returns the flushed sentence. [userTaps]: closes
     * the person chose themselves, counted without a kind as PopupChoiceFlow does today.
     */
    private fun batched(vararg speeches: AutoSpeech, userTaps: Int = 0, detailed: Boolean = false): String {
        val scope = TestScope()
        val flushed = mutableListOf<BatchSummary>()
        val batcher = AnnouncementBatcher(scope, onFlush = { flushed += it })
        speeches.map { it as AutoSpeech.Batch }.forEach { batcher.add(it.outcome, it.kind, it.title) }
        repeat(userTaps) { batcher.add(HandledOutcome.CLOSED) }
        scope.advanceTimeBy(AnnouncementBatcher.DEFAULT_WINDOW_MILLIS)
        scope.runCurrent()
        return AutoDecisionSpeech.batchSentence(flushed.single(), detailed, Strings.format)
    }

    private fun closed(classification: PopupClassification, title: String? = null) =
        AutoDecisionSpeech.speechFor(execute, classification, pressed(ClickOutcome.CLOSED_CONFIRMED), title = title)

    // --- EXECUTE ---

    @Test fun closedConfirmedWithTitle() {
        val speech = closed(ad, title = "봄맞이 할인 이벤트")
        assertEquals(AutoSpeech.Batch(HandledOutcome.CLOSED, ClosedKind.AD, "봄맞이 할인 이벤트"), speech)
        assertEquals("'봄맞이 할인 이벤트' 광고를 닫았어요.", batched(speech))
    }

    @Test fun closedConfirmedWithoutTitleSaysOnlyTheKind() {
        assertEquals("광고를 닫았어요.", batched(closed(ad)))
        assertEquals("쿠폰을 닫았어요.", batched(closed(PopupClassification.COUPON)))
        assertEquals("공지를 닫았어요.", batched(closed(PopupClassification.NOTICE)))
        assertEquals("팝업을 닫았어요.", batched(closed(PopupClassification.UNKNOWN)))
    }

    @Test fun twoSecondBatchCountsKinds() {
        assertEquals(
            "광고 2개, 쿠폰 1개를 닫았어요.",
            batched(closed(PopupClassification.COUPON, "쿠폰"), closed(ad, "a"), closed(ad, "b")),
        )
        assertEquals("광고 2개를 닫았어요.", batched(closed(ad, "a"), closed(ad)))
    }

    /** A title is said only when exactly one popup was closed in the window. */
    @Test fun titleOnlyForASingleClose() {
        assertEquals("광고 1개, 공지 1개를 닫았어요.", batched(closed(ad, "a"), closed(PopupClassification.NOTICE, "b")))
    }

    @Test fun mixedWithTheUsersOwnTapFallsBackToPopupCount() {
        assertEquals("팝업 2개를 닫았어요.", batched(closed(ad, "a"), userTaps = 1))
    }

    @Test fun closedAndNotConfirmedInOneWindow() {
        val notConfirmed = AutoDecisionSpeech.speechFor(execute, ad, pressed(ClickOutcome.STILL_PRESENT))
        assertEquals(
            "광고를 닫았어요. 팝업 1개는 닫혔는지 확인하지 못했어요. 모두 2개예요.",
            batched(closed(ad), notConfirmed, detailed = true),
        )
    }

    @Test fun leftAppIsSaidAtOnceAndNeverClosed() {
        val speech = AutoDecisionSpeech.speechFor(execute, ad, pressed(ClickOutcome.LEFT_APP))
        assertEquals("광고를 닫으려다 다른 화면이 열렸어요. 이 앱에서는 당분간 자동으로 처리하지 않을게요.", say(speech))
        assertEquals(
            "쿠폰을 닫으려다 다른 화면이 열렸어요. 이 앱에서는 당분간 자동으로 처리하지 않을게요.",
            say(AutoDecisionSpeech.speechFor(execute, PopupClassification.COUPON, pressed(ClickOutcome.LEFT_APP))),
        )
    }

    /** STILL_PRESENT, UNKNOWN and a press A did not observe are all "확인하지 못했어요", never "닫았어요". */
    @Test fun notConfirmedOutcomes() {
        val notConfirmed = listOf(
            pressed(ClickOutcome.STILL_PRESENT),
            pressed(ClickOutcome.UNKNOWN),
            pressed(null, ActionStatus.MECHANICAL_SUCCESS),
            pressed(null, ActionStatus.ATTEMPT_FAILED),
            pressed(null, ActionStatus.OUTCOME_UNKNOWN),
        )
        notConfirmed.forEach { result ->
            val speech = AutoDecisionSpeech.speechFor(execute, ad, result)
            assertEquals(AutoSpeech.Batch(HandledOutcome.NOT_CONFIRMED, ClosedKind.AD), speech)
            assertEquals("팝업 1개는 닫혔는지 확인하지 못했어요.", batched(speech))
        }
    }

    @Test fun nothingPressedOrNoResultYetIsQuiet() {
        assertEquals(AutoSpeech.Quiet, AutoDecisionSpeech.speechFor(execute, ad, result = null))
        listOf(ActionStatus.NOT_EXECUTED, ActionStatus.BLOCKED, ActionStatus.STALE).forEach {
            assertEquals(it.name, AutoSpeech.Quiet, AutoDecisionSpeech.speechFor(execute, ad, ActionResult(token, it, null)))
        }
    }

    // --- WAIT ---

    private val waitForButton = decision(NextStep.WAIT, SafetyReason.WAITING_FOR_TARGET)

    @Test fun waitForAButtonIsSaidOnce() {
        assertEquals("광고가 끝나면 닫을게요.", say(AutoDecisionSpeech.speechFor(waitForButton, ad)))
        assertEquals(AutoSpeech.Quiet, AutoDecisionSpeech.speechFor(waitForButton, ad, waitAlreadySaid = true))
    }

    @Test fun waitOnceWithTheMemory() {
        val memory = WaitSaidOnce()
        val said = (1..4).map { AutoDecisionSpeech.speechFor(waitForButton, ad, waitAlreadySaid = !memory.firstTime("popup-1")) }
        assertTrue(said.first() is AutoSpeech.Say)
        assertEquals(List(3) { AutoSpeech.Quiet }, said.drop(1))
        assertTrue("another popup is said", memory.firstTime("popup-2"))
        memory.forget("popup-1")
        assertTrue("the popup was gone", memory.firstTime("popup-1"))
        memory.clear()
        assertTrue(memory.firstTime("popup-2"))
    }

    @Test fun waitMemoryIsBounded() {
        val memory = WaitSaidOnce(capacity = 2)
        listOf("a", "b", "c").forEach { assertTrue(memory.firstTime(it)) }
        assertTrue("oldest dropped", memory.firstTime("a"))
        assertFalse(memory.firstTime("c"))
    }

    @Test fun waitingForResultOrThePersonIsSilent() {
        listOf(SafetyReason.AWAITING_RESULT, SafetyReason.USER_INTERACTION, SafetyReason.STALE_SCREEN).forEach {
            assertEquals(it.name, AutoSpeech.Quiet, AutoDecisionSpeech.speechFor(decision(NextStep.WAIT, it), ad))
        }
    }

    @Test fun waitForOtherKinds() {
        assertEquals(
            "쿠폰을 바로 닫을 수 없어서 잠시 기다릴게요.",
            say(AutoDecisionSpeech.speechFor(waitForButton, PopupClassification.COUPON)),
        )
    }

    @Test fun waitForAVideoAdUsesTheCountdownSentence() {
        val video = VideoAdInfo(countdownSeconds = 5, skipCandidateIds = emptyList())
        assertEquals(
            "영상 광고로 보이는 팝업이에요. 5초 뒤에 넘길 수 있다고 나와요. 지금은 대신 누를 버튼이 없어요.",
            say(AutoDecisionSpeech.speechFor(waitForButton, ad, videoAd = video, videoAdEnabled = true)),
        )
        assertEquals(
            "영상 광고로 보이는 팝업이에요. 광고가 끝나면 닫을게요.",
            say(AutoDecisionSpeech.speechFor(waitForButton, ad, videoAd = video.copy(countdownSeconds = null), videoAdEnabled = true)),
        )
        assertEquals(
            "video wording stays off with its feature",
            "광고가 끝나면 닫을게요.",
            say(AutoDecisionSpeech.speechFor(waitForButton, ad, videoAd = video, videoAdEnabled = false)),
        )
    }

    // --- ANNOUNCE / ASK / DENY ---

    @Test fun announceUsesTheExistingSummaryPath() {
        val announce = decision(NextStep.ANNOUNCE)
        assertEquals(AutoSpeech.Announce(PromptSummary.LIKELY_NOTICE), AutoDecisionSpeech.speechFor(announce, PopupClassification.NOTICE))
        assertEquals(AutoSpeech.Announce(PromptSummary.LIKELY_COUPON), AutoDecisionSpeech.speechFor(announce, PopupClassification.COUPON))
    }

    @Test fun askUsesTheExistingPrompt() =
        assertEquals(AutoSpeech.Ask, AutoDecisionSpeech.speechFor(decision(NextStep.ASK, SafetyReason.FIRST_SEEN), ad))

    @Test fun suspiciousIsAWarningWithNothingToPress() {
        val deny = decision(NextStep.DENY, SafetyReason.SUSPICIOUS_CONTENT)
        val speech = AutoDecisionSpeech.speechFor(deny, PopupClassification.UNKNOWN)
        assertTrue("no prompt, so no press or move option", speech is AutoSpeech.Say)
        assertEquals("사기일 수 있는 팝업이에요. 아무것도 누르지 마세요.", say(speech))
        val text = say(speech)
        listOf("눌러 주기", "이동", "닫을게요").forEach { assertFalse(it, text.contains(it)) }
    }

    /**
     * #38 review: the contract refuses a suspicious or important-decision popup with any step but
     * DENY, so C never gets one that would open the prompt with press buttons.
     */
    @Test fun contractAllowsOnlyDenyForSuspiciousOrDecision() {
        listOf(SafetyReason.SUSPICIOUS_CONTENT, SafetyReason.IMPORTANT_DECISION).forEach { reason ->
            NextStep.entries.filter { it != NextStep.DENY }.forEach { next ->
                assertThrows("$reason/$next", IllegalArgumentException::class.java) {
                    decision(next, reason, SafetyReason.WAITING_FOR_TARGET)
                }
            }
        }
    }

    /** #38 review: a WAIT always carries what it waits for; C stays quiet for all but WAITING_FOR_TARGET. */
    @Test fun contractRequiresAReasonForWait() {
        assertThrows(IllegalArgumentException::class.java) { decision(NextStep.WAIT) }
        assertThrows(IllegalArgumentException::class.java) { decision(NextStep.WAIT, SafetyReason.FIRST_SEEN) }
        ActionDecision.WAIT_REASONS.forEach { reason ->
            val speech = AutoDecisionSpeech.speechFor(decision(NextStep.WAIT, reason), ad)
            assertEquals(reason.name, reason == SafetyReason.WAITING_FOR_TARGET, speech is AutoSpeech.Say)
        }
    }

    @Test fun denyDecisionKeepsTheExistingSentence() {
        val deny = decision(NextStep.DENY, SafetyReason.IMPORTANT_DECISION)
        assertEquals("확인이 필요한 팝업이에요. 직접 확인해 주세요.", say(AutoDecisionSpeech.speechFor(deny, PopupClassification.USER_DECISION)))
        assertEquals(
            "확인이 필요한 팝업이에요. 직접 확인해 주세요. 중요한 선택을 요구할 수 있는 팝업이라 대신 누르지 않아요.",
            say(AutoDecisionSpeech.speechFor(deny, PopupClassification.USER_DECISION, reasonEnabled = true)),
        )
    }

    /** Every NextStep says something or opens a path; only WAIT may be quiet. */
    @Test fun everyNextStepIsCovered() = NextStep.entries.forEach { next ->
        val speech = AutoDecisionSpeech.speechFor(decision(next, SafetyReason.WAITING_FOR_TARGET), ad, pressed(ClickOutcome.CLOSED_CONFIRMED))
        assertTrue(next.name, speech != AutoSpeech.Quiet || next == NextStep.WAIT)
    }

    // --- wording rules ---

    /** TalkBack can read these while the microphone is open: no command word in C's own sentences. */
    @Test fun autoSentencesHaveNoCommandWords() {
        val names = Strings.names.filter { it.startsWith("ui_auto_") } + "ui_choice_summary_coupon"
        assertTrue(names.size >= 15)
        val forbidden = listOf("닫아", "닫기", "닫자", "넘겨", "넘어가", "건너뛰", "스킵", "읽어", "그대로", "놔둬", "뒤로", "돌아가", "버튼으로")
        names.forEach { name ->
            val line = Strings.raw(name)
            forbidden.forEach { assertFalse("$name: $line", KoreanVoiceCommandMatcher.normalize(line).contains(it)) }
            assertNull("$name: $line", KoreanVoiceCommandMatcher.match(line))
        }
        // Short rendered sentences too (the length limit does not hide them).
        listOf(batched(closed(ad)), batched(closed(PopupClassification.COUPON)), say(AutoDecisionSpeech.speechFor(waitForButton, ad)))
            .forEach { assertNull(it, KoreanVoiceCommandMatcher.match(it)) }
    }

    @Test fun closedIsSaidOnlyForAConfirmedClose() {
        listOf("ui_auto_wait_ad", "ui_auto_wait_other", "ui_auto_left_app", "ui_auto_suspicious", "ui_choice_summary_coupon")
            .forEach { assertFalse(it, Strings.raw(it).contains("닫았어요")) }
    }

    @Test fun couponSummaryIsHedgedLikeTheAdOne() {
        assertEquals("쿠폰으로 보이는 팝업이에요.", Strings.raw("ui_choice_summary_coupon"))
        assertEquals(Strings.raw("ui_choice_summary_ad").replace("광고로", "쿠폰으로"), Strings.raw("ui_choice_summary_coupon"))
    }

    @Test fun shortTitle() {
        assertNull(AutoDecisionSpeech.shortTitle(null))
        assertNull(AutoDecisionSpeech.shortTitle("  \n "))
        assertEquals("봄맞이 할인", AutoDecisionSpeech.shortTitle("  봄맞이\n  할인 "))
        assertEquals("여름 세일", AutoDecisionSpeech.shortTitle("'여름 \"세일\""))
        val long = "가나다라마바사아자차카타파하가나다라마바사아자"
        assertEquals(long.take(AutoDecisionSpeech.TITLE_MAX_CHARS), AutoDecisionSpeech.shortTitle(long))
        assertEquals("🎁".repeat(20), AutoDecisionSpeech.shortTitle("🎁".repeat(25)))
    }

    @Test fun titleIsNeverInToString() {
        val speech = closed(ad, title = "비밀 제목")
        assertFalse(speech.toString().contains("비밀"))
        val summary = BatchSummary(1, 0, 0, mapOf(ClosedKind.AD to 1), onlyTitle = "비밀 제목")
        assertFalse(summary.toString().contains("비밀"))
    }

    @Test fun existingBatchWithoutKindsIsUnchanged() {
        assertEquals("팝업 3개를 닫았어요.", AutoDecisionSpeech.batchSentence(BatchSummary(3, 0, 0), false, Strings.format))
    }
}

/** ui_voice_strings.xml by resource id, for rendering real sentences in JVM tests. */
internal object Strings {
    private val xml = java.io.File("src/main/res/values/ui_voice_strings.xml").readText()
    private val byName: Map<String, String> = Regex("""<string name="([a-z0-9_]+)">([^<]*)</string>""")
        .findAll(xml).associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    private val nameById: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    val names: Set<String> get() = byName.keys
    fun raw(name: String): String = requireNotNull(byName[name]) { name }
    val format = UiFormat { id, args -> String.format(raw(nameById.getValue(id)), *args) }
}
