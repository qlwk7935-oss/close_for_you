package kr.closeforyou.ui.voice

import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kr.closeforyou.core.contract.AnnouncementKind
import kr.closeforyou.core.contract.AnnouncementReceipt
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.contract.AnnouncementState
import kr.closeforyou.core.contract.PopupAnnouncer
import kr.closeforyou.core.model.ActionAuthorization
import kr.closeforyou.core.model.ActionDecision
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.ClickOutcome
import kr.closeforyou.core.model.DecisionSource
import kr.closeforyou.core.model.HandlingReport
import kr.closeforyou.core.model.NextStep
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.SafetyReason
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.choice.VideoAdInfo
import kr.closeforyou.ui.voice.command.KoreanVoiceCommandMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract v3 (#45): HandlingReport → what C says. Real ui_voice_strings.xml, fake announcer. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeechHandlingListenerTest {
    private val token = MockPopupFixtures.advertisement().snapshot.token
    private val ad = PopupClassification.ADVERTISEMENT
    private val coupon = PopupClassification.COUPON
    private val notice = PopupClassification.NOTICE

    private class FakeAnnouncer : PopupAnnouncer {
        val said = mutableListOf<AnnouncementRequest>()
        override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
            said += request
            return AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
        }
        override suspend fun repeatLast() = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
        override fun stop() = Unit
    }

    private val scope = TestScope()
    private val announcer = FakeAnnouncer()
    private val batcher = AnnouncementBatcher(scope, SpeechHandlingListener.batchFlush(announcer, Strings.format))

    private fun listener(
        contentOf: suspend (HandlingReport) -> String? = { null },
        videoAdOf: (HandlingReport) -> VideoAdInfo? = { null },
        waitKeyOf: (HandlingReport) -> String? = SpeechHandlingListener::defaultWaitKey,
        titleSpeechEnabled: () -> Boolean = { HandlingTitleSpeechFeature.enabled },
    ) = SpeechHandlingListener(announcer, batcher, Strings.format, waitKeyOf, contentOf, videoAdOf, titleSpeechEnabled = titleSpeechEnabled)

    private val titlesOn = listener(titleSpeechEnabled = { true })

    private fun decision(next: NextStep, vararg reasons: SafetyReason, on: SnapshotToken = token) = ActionDecision(
        token = on,
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

    private fun result(
        outcome: ClickOutcome?,
        status: ActionStatus = ActionStatus.MECHANICAL_SUCCESS,
        attempted: Boolean = true,
        on: SnapshotToken = token,
    ) = ActionResult(on, status, reason = null, attempted = attempted, outcome = outcome)

    private fun executed(
        classification: PopupClassification,
        result: ActionResult?,
        title: String? = null,
        on: SnapshotToken = token,
    ) = HandlingReport(on, classification, decision(NextStep.EXECUTE, on = on), result, title)

    private fun closed(classification: PopupClassification, title: String? = null, on: SnapshotToken = token) =
        executed(classification, result(ClickOutcome.CLOSED_CONFIRMED, on = on), title, on)

    private fun report(next: NextStep, vararg reasons: SafetyReason, classification: PopupClassification = ad, on: SnapshotToken = token) =
        HandlingReport(on, classification, decision(next, *reasons, on = on))

    private fun handle(vararg reports: HandlingReport, l: SpeechHandlingListener = listener()) {
        scope.launch { reports.forEach { l.onHandled(it) } }
        scope.runCurrent()
    }

    /** Lets the 2 s batch window pass and returns everything said, in order. */
    private fun spoken(): List<String> {
        scope.advanceTimeBy(AnnouncementBatcher.DEFAULT_WINDOW_MILLIS)
        scope.runCurrent()
        return announcer.said.map { it.text }
    }

    private fun otherPopup(): SnapshotToken = token.copy(windowId = token.windowId + 1, structureDigest = "other")

    // --- EXECUTE ---

    @Test fun closedPerKindWithoutTitle() {
        mapOf(ad to "광고를 닫았어요.", coupon to "쿠폰을 닫았어요.", notice to "공지를 닫았어요.").forEach { (kind, line) ->
            announcer.said.clear()
            handle(closed(kind))
            assertEquals(listOf(line), spoken())
        }
    }

    /** First live integration (10/25): the title is not spoken by default, whatever A sends. */
    @Test fun titleIsOffByDefault() {
        assertFalse(HandlingTitleSpeechFeature.enabled)
        mapOf(ad to "광고를 닫았어요.", coupon to "쿠폰을 닫았어요.", notice to "공지를 닫았어요.").forEach { (kind, line) ->
            announcer.said.clear()
            handle(closed(kind, title = "가을맞이 특가 이벤트"))
            assertEquals(listOf(line), spoken())
        }
        announcer.said.clear()
        handle(closed(ad, title = "하나"), closed(coupon, title = "둘", on = otherPopup()))
        assertEquals(listOf("광고 1개, 쿠폰 1개를 닫았어요."), spoken())
        announcer.said.forEach { assertFalse(it.text, it.text.contains("하나") || it.text.contains("둘")) }
    }

    @Test fun titleOnSaysTheShortTitle() {
        handle(closed(ad, title = "가을맞이 특가 이벤트 지금 바로 확인하시고 혜택 받으세요"), l = titlesOn)
        assertEquals(listOf("'가을맞이 특가 이벤트 지금 바로 확인' 광고를 닫았어요."), spoken())
    }

    @Test fun manyClosesWithinTwoSecondsBecomeOneSentence() {
        handle(closed(ad, title = "하나"), closed(coupon), closed(ad, on = otherPopup()))
        assertEquals(listOf("광고 2개, 쿠폰 1개를 닫았어요."), spoken())
        assertEquals(AnnouncementKind.ACTION_RESULT, announcer.said.single().kind)
        assertNull(announcer.said.single().token)
    }

    @Test fun notClosedConfirmedNeverSaysClosed() {
        val notConfirmed = listOf(
            result(ClickOutcome.STILL_PRESENT),
            result(ClickOutcome.UNKNOWN),
            result(null),
            result(ClickOutcome.CLOSED_CONFIRMED, status = ActionStatus.ATTEMPT_FAILED),
            result(ClickOutcome.CLOSED_CONFIRMED, status = ActionStatus.OUTCOME_UNKNOWN),
            result(null, status = ActionStatus.ATTEMPT_FAILED),
        )
        notConfirmed.forEach { r ->
            announcer.said.clear()
            val report = executed(ad, r, title = "제목")
            assertFalse(report.closedConfirmed)
            handle(report)
            val said = spoken()
            assertEquals("$r", listOf("팝업 1개는 닫혔는지 확인하지 못했어요."), said)
        }
    }

    /** The contract's rule and C's PressSuccess are the same rule for every combination. */
    @Test fun closedConfirmedMatchesPressSuccess() {
        ActionStatus.entries.forEach { status ->
            listOf(true, false).forEach { attempted ->
                (ClickOutcome.entries + listOf(null)).forEach { outcome ->
                    if (outcome != null && !attempted) return@forEach
                    val r = result(outcome, status, attempted)
                    assertEquals("$status $attempted $outcome", PressSuccess.isConfirmed(r), executed(ad, r).closedConfirmed)
                }
            }
        }
    }

    @Test fun leftAppIsSaidAtOnce() {
        handle(executed(coupon, result(ClickOutcome.LEFT_APP)))
        scope.runCurrent()
        assertEquals(
            listOf("쿠폰을 닫으려다 다른 화면이 열렸어요. 이 앱에서는 당분간 자동으로 처리하지 않을게요."),
            announcer.said.map { it.text },
        )
        assertEquals(AnnouncementKind.ACTION_RESULT, announcer.said.single().kind)
        assertEquals(listOf("쿠폰을 닫으려다 다른 화면이 열렸어요. 이 앱에서는 당분간 자동으로 처리하지 않을게요."), spoken())
    }

    @Test fun notAttemptedOrNoResultIsSilent() {
        handle(
            executed(ad, null),
            executed(ad, result(null, ActionStatus.NOT_EXECUTED, attempted = false)),
            executed(ad, result(null, ActionStatus.BLOCKED, attempted = false)),
            executed(ad, result(null, ActionStatus.STALE, attempted = false)),
        )
        assertTrue(spoken().isEmpty())
    }

    // --- WAIT ---

    @Test fun waitIsSaidOncePerPopup() {
        val wait = report(NextStep.WAIT, SafetyReason.WAITING_FOR_TARGET)
        // A new snapshot of the same popup: new popupInstanceId and content (countdown), same structure.
        val sameLater = report(
            NextStep.WAIT, SafetyReason.WAITING_FOR_TARGET,
            on = token.copy(popupInstanceId = "next", contentDigest = "countdown-4", screenVersion = token.screenVersion + 1),
        )
        handle(wait, sameLater, wait, l = listener())
        assertEquals(listOf("광고가 끝나면 닫을게요."), spoken())
    }

    @Test fun waitBySituation() {
        val l = listener(videoAdOf = { VideoAdInfo(countdownSeconds = 5, skipCandidateIds = emptyList()) })
        handle(report(NextStep.WAIT, SafetyReason.WAITING_FOR_TARGET), l = l)
        handle(report(NextStep.WAIT, SafetyReason.WAITING_FOR_TARGET, classification = coupon, on = otherPopup()), l = l)
        val said = spoken()
        assertEquals(2, said.size)
        // Video countdown only when F5's wording flag is on (off today: plain ad wait).
        assertTrue(said[0], said[0] == "광고가 끝나면 닫을게요." || said[0].contains("5초"))
        assertEquals("쿠폰을 바로 닫을 수 없어서 잠시 기다릴게요.", said[1])
    }

    @Test fun otherWaitsAreSilent() {
        handle(
            report(NextStep.WAIT, SafetyReason.AWAITING_RESULT),
            report(NextStep.WAIT, SafetyReason.USER_INTERACTION),
            report(NextStep.WAIT, SafetyReason.STALE_SCREEN),
        )
        assertTrue(spoken().isEmpty())
    }

    @Test fun afterAPressTheWaitMaySoundAgain() {
        val wait = report(NextStep.WAIT, SafetyReason.WAITING_FOR_TARGET)
        val l = listener()
        handle(wait, executed(ad, result(ClickOutcome.STILL_PRESENT)), wait, l = l)
        assertEquals(
            listOf("광고가 끝나면 닫을게요.", "광고가 끝나면 닫을게요.", "팝업 1개는 닫혔는지 확인하지 못했어요."),
            spoken(),
        )
    }

    @Test fun unknownPopupIdentityStillSaysTheWait() {
        val l = listener(waitKeyOf = { null })
        val wait = report(NextStep.WAIT, SafetyReason.WAITING_FOR_TARGET)
        handle(wait, wait, l = l)
        assertEquals(2, spoken().size)
    }

    @Test fun nullKeyIsSaidEveryTime() {
        val l = listener(waitKeyOf = { null })
        val wait = report(NextStep.WAIT, SafetyReason.WAITING_FOR_TARGET)
        handle(wait, wait, wait, l = l)
        assertEquals(List(3) { "광고가 끝나면 닫을게요." }, spoken())
    }

    @Test fun everyResetReasonForgetsSaidWaits() {
        WaitResetReason.entries.forEach { reason ->
            announcer.said.clear()
            val l = listener()
            val wait = report(NextStep.WAIT, SafetyReason.WAITING_FOR_TARGET)
            handle(wait, wait, l = l)
            l.resetWaitMemory(reason)
            handle(wait, wait, l = l)
            assertEquals("$reason", 2, spoken().size)
        }
    }

    @Test fun clearForgetsSaidWaits() {
        val l = listener()
        val wait = report(NextStep.WAIT, SafetyReason.WAITING_FOR_TARGET)
        handle(wait, l = l)
        l.clear()
        handle(wait, l = l)
        assertEquals(2, spoken().size)
    }

    // --- ANNOUNCE / ASK / DENY ---

    @Test fun announceSaysTheKindThenTheContent() {
        handle(report(NextStep.ANNOUNCE, SafetyReason.PRESET_NOT_ALLOWED, classification = notice), l = listener(contentOf = { "점검 안내 내용" }))
        val request = announcer.said.single()
        assertEquals(AnnouncementKind.CONTENT, request.kind)
        assertEquals(token, request.token)
        assertTrue(request.text, request.text.startsWith("공지로 보이는 팝업이에요."))
        assertTrue(request.text.endsWith("점검 안내 내용"))
    }

    @Test fun announceWithoutContentSaysTheKindOnly() {
        handle(report(NextStep.ANNOUNCE, SafetyReason.PRESET_NOT_ALLOWED, classification = coupon))
        assertEquals(listOf("쿠폰으로 보이는 팝업이에요."), spoken())
        assertEquals(AnnouncementKind.POPUP_FOUND, announcer.said.single().kind)
    }

    @Test fun askIsLeftToTheChoicePrompt() {
        handle(report(NextStep.ASK, SafetyReason.FIRST_SEEN))
        assertTrue(spoken().isEmpty())
    }

    /** The person's own choice never becomes a report, so PopupChoiceFlow alone speaks its result. */
    @Test fun userChoiceCanNotReachTheListener() {
        val own = decision(NextStep.EXECUTE).copy(source = DecisionSource.USER_CHOICE)
        val built = runCatching { HandlingReport(token, ad, own, result(ClickOutcome.CLOSED_CONFIRMED)) }
        assertTrue(built.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun suspiciousSaysOnlyTheWarning() {
        handle(report(NextStep.DENY, SafetyReason.SUSPICIOUS_CONTENT))
        assertEquals(listOf("사기일 수 있는 팝업이에요. 아무것도 누르지 마세요."), spoken())
    }

    @Test fun decisionDenyAsksThePersonToCheck() {
        handle(report(NextStep.DENY, SafetyReason.IMPORTANT_DECISION, classification = PopupClassification.USER_DECISION))
        val said = spoken().single()
        assertFalse(said, said.contains("닫았어요"))
        assertFalse(said, said.contains("사기"))
    }

    // --- title and privacy ---

    @Test fun titleCutAndEmpty() {
        assertEquals("가".repeat(AutoDecisionSpeech.TITLE_MAX_CHARS), SpeechHandlingListener.spokenTitle("가".repeat(30)))
        assertNull(SpeechHandlingListener.spokenTitle(null))
        assertNull(SpeechHandlingListener.spokenTitle("   "))
        assertEquals("봄 세일", SpeechHandlingListener.spokenTitle("  봄\n세일 "))
    }

    /** A phone, card or account number is never read out as a title (the kind sentence is said). */
    @Test fun longNumbersAreNotSpokenAsATitle() {
        listOf("문의 010-1234-5678", "1588 1234 상담", "카드 1234567890123456", "계좌 123.456.789").forEach {
            assertNull(it, SpeechHandlingListener.spokenTitle(it))
        }
        assertEquals("50% 할인 쿠폰 2024", SpeechHandlingListener.spokenTitle("50% 할인 쿠폰 2024"))
        handle(closed(ad, title = "상담 010-1234-5678"), l = titlesOn)
        assertEquals(listOf("광고를 닫았어요."), spoken())
    }

    @Test fun noScreenTextInToString() {
        val report = closed(ad, title = "비밀 제목")
        assertFalse(report.toString().contains("비밀"))
        assertFalse(listener().toString().contains("비밀"))
        handle(report, l = titlesOn)
        spoken()
        announcer.said.forEach { assertFalse(it.toString().contains("비밀")) }
    }

    /** TalkBack may read these while the microphone is open: no command words in C's own sentences. */
    @Test fun spokenSentencesHaveNoCommandWords() {
        val l = listener()
        handle(
            closed(ad), closed(coupon, on = otherPopup()),
            executed(notice, result(ClickOutcome.LEFT_APP)),
            report(NextStep.WAIT, SafetyReason.WAITING_FOR_TARGET),
            report(NextStep.WAIT, SafetyReason.WAITING_FOR_TARGET, classification = notice, on = otherPopup()),
            report(NextStep.ANNOUNCE, SafetyReason.PRESET_NOT_ALLOWED, classification = notice),
            report(NextStep.DENY, SafetyReason.SUSPICIOUS_CONTENT),
            l = l,
        )
        val said = spoken()
        assertEquals(6, said.size)
        said.forEach { assertNull(it, KoreanVoiceCommandMatcher.match(it)) }
    }
}
