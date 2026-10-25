package kr.closeforyou.decision

import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every condition of AUTO_CLOSE.md 4절, each with a passing and a refused case. */
class DecisionAutoCloseTest {
    private val baseToken: SnapshotToken = MockPopupFixtures.notice().snapshot.token
    private val bounds = ScreenBounds(80, 400, 1000, 1450)
    private var now = 1_000_000L
    private val auto = DecisionAutoClose(nowMillis = { now })

    private fun token(popup: String = "popup-a", app: String = baseToken.packageName): SnapshotToken =
        baseToken.copy(structureDigest = popup, packageName = app)

    private fun tree(vararg texts: String) = texts.map { ExtractedText(it, bounds, TextSource.ACCESSIBILITY_TREE, null) }
    private fun ocr(vararg texts: String) = texts.map { ExtractedText(it, bounds, TextSource.OCR, null) }

    private fun button(id: String, label: String?, role: ButtonRole = ButtonRole.CLOSE, evidence: EvidenceLevel = EvidenceLevel.E2_LABELED_NODE) =
        CloseCandidate("node:$id", id, null, label, bounds, evidence, role)

    private val close = button("close", "닫기")

    private fun popup(texts: List<ExtractedText>, vararg buttons: CloseCandidate, token: SnapshotToken = token()) =
        PopupAnalysis(token, texts = texts.toPersistentList(), closeCandidates = buttons.toList().toPersistentList(),
            limitations = kotlinx.collections.immutable.persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE))

    private fun ctx(a: PopupAnalysis, interacting: Boolean = false, announce: Boolean = true, choice: UserChoice? = null) =
        DecisionContext(a.token, choice, null, interacting, announce)

    /** A's eligibility evidence for this screen, issued now (contract v2): every check. */
    private fun eligible(a: PopupAnalysis) =
        a.copy(autoEligibility = AutoEligibility(a.token, kotlinx.collections.immutable.persistentSetOf(*EligibilityCheck.entries.toTypedArray()), now))

    private fun decide(a: PopupAnalysis, preset: Set<DecisionKind> = DecisionAutoClose.PRESET_ALL, context: DecisionContext = ctx(a)) =
        auto.evaluateScoped(eligible(a), context, preset)

    private val ad = { popup(tree("광고", "신상 운동화", "자세히 보기", "닫기"), close) }
    private val coupon = { popup(tree("쿠폰이 도착했어요", "닫기"), close, token = token("coupon")) }
    private val notice = { popup(tree("공지", "서비스 점검 안내", "닫기"), close, token = token("notice")) }

    // ---- 1. kind and preset ----

    @Test fun eachPresetClosesOnlyItsKinds() {
        val presets = listOf(DecisionAutoClose.PRESET_ALL, DecisionAutoClose.PRESET_ADS_AND_COUPONS, DecisionAutoClose.PRESET_ADS_ONLY)
        val expected = mapOf( // ad, coupon, notice
            DecisionAutoClose.PRESET_ALL to listOf(true, true, true),
            DecisionAutoClose.PRESET_ADS_AND_COUPONS to listOf(true, true, false),
            DecisionAutoClose.PRESET_ADS_ONLY to listOf(true, false, false),
        )
        for (preset in presets) {
            val fresh = DecisionAutoClose(nowMillis = { now })
            val got = listOf(ad(), coupon(), notice()).map { fresh.evaluateScoped(eligible(it), ctx(it), preset) }
            assertEquals(preset.toString(), expected.getValue(preset), got.map { it.approved })
            got.filterNot { it.approved }.forEach { assertEquals(setOf(AutoCloseBlock.KIND_NOT_IN_PRESET), it.blocks) }
        }
        val ok = DecisionAutoClose(nowMillis = { now }).evaluateScoped(eligible(ad()), ctx(ad()), DecisionAutoClose.PRESET_ADS_ONLY)
        assertEquals("node:close", ok.candidateId)
        assertEquals(DecisionKind.ADVERTISEMENT, ok.kind)
    }

    @Test fun presetOffClosesNothing() {
        assertEquals(setOf(AutoCloseBlock.PRESET_OFF), decide(ad(), preset = emptySet()).blocks)
        // Decision and unknown kinds in a preset are ignored: they are never closable.
        assertEquals(setOf(AutoCloseBlock.PRESET_OFF), decide(ad(), preset = setOf(DecisionKind.USER_DECISION, DecisionKind.UNKNOWN)).blocks)
    }

    @Test fun decisionScamAndUnknownAreNeverClosedAndNeverWaited() {
        val never = listOf(
            popup(tree("광고", "지금 결제하세요", "닫기"), close),
            popup(tree("쿠폰을 받으려면 동의가 필요합니다", "닫기"), close),
            popup(tree("광고", "닫기"), close, button("buy", "구매하기", ButtonRole.USER_DECISION)),
            popup(tree("결제 선택 필요")), // A's fixed summary, nodes removed
            popup(tree("축하합니다! 경품에 당첨되었습니다", "닫기"), close),
            popup(tree("휴대폰이 바이러스에 감염되었습니다", "닫기"), close),
            popup(tree("탭하여 계속", "닫기"), close),
            popup(tree("닫기"), close),
            popup(emptyList(), close),
            popup(tree("가상 이미지 팝업: 버튼의 의미는 확인되지 않았습니다.", "닫기"), close),
            popup(ocr("광고 · 여름 대세일") + tree("닫기"), close), // promotion only in OCR
        )
        for (a in never) {
            val v = decide(a)
            assertFalse(a.texts.map { it.text }.toString(), v.approved)
            assertEquals(a.texts.map { it.text }.toString(), setOf(AutoCloseBlock.KIND_NEVER_CLOSED), v.blocks)
        }
        // A classification someone already set to a decision is respected even if B's words miss it.
        val marked = ad().copy(classification = PopupClassification.USER_DECISION)
        assertEquals(setOf(AutoCloseBlock.KIND_NEVER_CLOSED), decide(marked).blocks)
    }

    @Test fun scamWordsInOcrAlsoStopIt() {
        val a = popup(tree("광고", "닫기") + ocr("바이러스 감염"), close)
        assertEquals(DecisionKind.SUSPICIOUS, decide(a).kind)
        assertFalse(decide(a).approved)
    }

    @Test fun aNoticeReadOnlyByOcrIsToldButNotClosed() {
        val a = popup(ocr("서비스 점검 안내") + tree("닫기"), close, token = token("ocr-notice"))
        val v = decide(a)
        assertEquals(DecisionKind.NOTICE, v.kind)
        assertEquals(setOf(AutoCloseBlock.NOT_TREE_EVIDENCE), v.blocks)
    }

    // ---- 2. screen and person ----

    @Test fun staleScreenUserActionSilenceAndOtherPathsBlock() {
        val a = ad()
        assertEquals(setOf(AutoCloseBlock.STALE_SCREEN), decide(a, context = ctx(a).copy(currentToken = token("other"))).blocks)
        assertEquals(setOf(AutoCloseBlock.STALE_SCREEN), decide(a, context = ctx(a).copy(currentToken = null)).blocks)
        assertEquals(setOf(AutoCloseBlock.USER_INTERACTION), decide(a, context = ctx(a, interacting = true)).blocks)
        assertEquals(setOf(AutoCloseBlock.FEEDBACK_UNAVAILABLE), decide(a, context = ctx(a, announce = false)).blocks)
        val choice = UserChoice(a.token, ChoiceKind.KEEP_OPEN)
        assertEquals(setOf(AutoCloseBlock.OTHER_PATH), decide(a, context = ctx(a, choice = choice)).blocks)
        val rule = PopupRule("r", RuleKey("p", 1, "s", "c", "t"), ChoiceKind.STOP_ASKING, false, false, 1, null)
        assertEquals(setOf(AutoCloseBlock.OTHER_PATH), decide(a, context = ctx(a).copy(matchingRule = rule)).blocks)
        // None of these refusals left the popup waiting for a result.
        assertTrue(decide(a).approved)
    }

    // ---- 3. exactly one button ----

    @Test fun onlyOneVerifiedCloseIsPressed() {
        val two = popup(tree("광고", "닫기", "닫기"), close, button("close2", "닫기"))
        assertEquals(setOf(AutoCloseBlock.AMBIGUOUS_TARGET), decide(two).blocks)
        // A close next to a skip: the close, because the skip may open the next ad (P17).
        val both = popup(tree("광고 1/2", "건너뛰기", "닫기"), button("skip", "건너뛰기", ButtonRole.SKIP), close, token = token("p17"))
        assertEquals("node:close", decide(both).candidateId)
        // E1 dismiss with a close label counts as a close.
        val e1 = popup(tree("광고"), button("e1", "닫기", evidence = EvidenceLevel.E1_DISMISS), token = token("e1"))
        assertEquals("node:e1", decide(e1).candidateId)
    }

    @Test fun unverifiedButtonsAreNeverTargets() {
        val bad = listOf(
            button("x", "X"), button("none", null), button("blank", " "),
            button("e3", "닫기", evidence = EvidenceLevel.E3_USER_BOUND_NODE),
            button("e4", "닫기", evidence = EvidenceLevel.E4_VISUAL_ONLY),
            button("today", "오늘 하루 닫기"), button("save", "닫기 전 저장"), button("ok", "확인"),
            button("detail", "자세히 보기"), button("lie", "건너뛰기", ButtonRole.CLOSE),
            button("ack", "닫기", ButtonRole.ACKNOWLEDGE), button("nav", "닫기", ButtonRole.NAVIGATE),
        )
        for ((i, b) in bad.withIndex()) {
            val a = popup(tree("광고", "신상 운동화"), b, token = token("bad$i"))
            assertEquals(b.id, setOf(AutoCloseBlock.WAITING_FOR_TARGET), decide(a).blocks)
        }
    }

    @Test fun delayedCloseWaitsForAdsAndCouponsButANoticeIsOnlyTold() {
        assertEquals(setOf(AutoCloseBlock.WAITING_FOR_TARGET), decide(popup(tree("광고", "신상 운동화"))).blocks)
        assertEquals(setOf(AutoCloseBlock.WAITING_FOR_TARGET), decide(popup(tree("쿠폰 도착"), token = token("c"))).blocks)
        assertEquals(setOf(AutoCloseBlock.NO_TARGET), decide(popup(tree("공지", "확인"), token = token("n"))).blocks)
        // When the close button appears on a later screen, that new screen is approved.
        assertTrue(decide(popup(tree("광고", "신상 운동화", "닫기"), close, token = token("later"))).approved)
    }

    @Test fun skipOnlyOnAnAdAfterItsCountdown() {
        val skip = button("skip", "건너뛰기", ButtonRole.SKIP)
        assertEquals("node:skip", decide(popup(tree("영상 광고", "건너뛰기"), skip, token = token("s1"))).candidateId)
        // Countdown still on screen: wait (no prompt each second), not ask.
        assertEquals(setOf(AutoCloseBlock.WAITING_FOR_TARGET),
            decide(popup(tree("영상 광고", "3초 후 건너뛸 수 있어요", "건너뛰기"), skip, token = token("s2"))).blocks)
        assertEquals(setOf(AutoCloseBlock.SKIP_NOT_READY), decide(popup(tree("쿠폰 도착", "건너뛰기"), skip, token = token("s3"))).blocks)
        assertEquals(setOf(AutoCloseBlock.SKIP_NOT_READY), decide(popup(tree("공지", "건너뛰기"), skip, token = token("s4"))).blocks)
        val twoSkips = popup(tree("광고", "건너뛰기"), skip, button("skip2", "스킵", ButtonRole.SKIP), token = token("s5"))
        assertEquals(setOf(AutoCloseBlock.AMBIGUOUS_TARGET), decide(twoSkips).blocks)
    }

    // ---- 4. session history ----

    private fun result(a: PopupAnalysis, status: ActionStatus, attempted: Boolean, outcome: ClickOutcome? = null) =
        ActionResult(a.token, status, null, attempted, outcome = outcome)

    @Test fun onePopupIsSentOnceUntilItsResultComesBack() {
        val a = ad()
        assertTrue(decide(a).approved)
        assertEquals(setOf(AutoCloseBlock.AWAITING_RESULT), decide(a).blocks)
        // Same popup on a new screen (same structure): still waiting.
        assertEquals(setOf(AutoCloseBlock.AWAITING_RESULT), decide(a.copy(token = a.token.copy(screenVersion = 99, popupInstanceId = "n"))).blocks)
        // No press happened (A's gate is non-executing today): the popup may be sent again.
        for (status in listOf(ActionStatus.NOT_EXECUTED, ActionStatus.BLOCKED, ActionStatus.STALE)) {
            auto.onResult(result(a, status, attempted = false))
            assertTrue(status.name, decide(a).approved)
        }
        auto.onResult(result(a, ActionStatus.MECHANICAL_SUCCESS, attempted = true, outcome = ClickOutcome.CLOSED_CONFIRMED))
        now += 600_000
        // Closed once: if the same popup shows again this session, it is asked about, however late.
        assertEquals(setOf(AutoCloseBlock.CLOSED_BEFORE), decide(a).blocks)
    }

    @Test fun aFailedOrUnknownPressIsNeverRetried() {
        for ((i, status) in listOf(ActionStatus.ATTEMPT_FAILED, ActionStatus.OUTCOME_UNKNOWN).withIndex()) {
            val a = popup(tree("광고", "닫기"), close, token = token("f$i"))
            assertTrue(decide(a).approved)
            auto.onResult(result(a, status, attempted = true))
            assertEquals(status.name, setOf(AutoCloseBlock.FAILED_BEFORE), decide(a).blocks)
        }
        // An attempted press reported as stale is treated as a failure, not a free retry.
        val s = popup(tree("광고", "닫기"), close, token = token("f-stale", app = "stale.app"))
        assertTrue(decide(s).approved)
        auto.onResult(result(s, ActionStatus.STALE, attempted = true))
        assertEquals(setOf(AutoCloseBlock.FAILED_BEFORE), decide(s).blocks)
    }

    @Test fun aPressThatLeftTheAppStopsThatAppOnly() {
        val a = ad()
        assertTrue(decide(a).approved)
        auto.onResult(result(a, ActionStatus.MECHANICAL_SUCCESS, attempted = true, outcome = ClickOutcome.LEFT_APP))
        assertTrue(AutoCloseBlock.APP_PAUSED in decide(coupon()).blocks)
        assertTrue(AutoCloseBlock.FAILED_BEFORE in decide(a).blocks)
        val elsewhere = popup(tree("광고", "닫기"), close, token = token("x", app = "other.app"))
        assertTrue(decide(elsewhere).approved)
    }

    @Test fun resultsForPopupsNeverApprovedAreIgnoredButLeavingTheAppIsNot() {
        val a = ad()
        auto.onResult(result(a, ActionStatus.ATTEMPT_FAILED, attempted = true))
        auto.onResult(result(a, ActionStatus.MECHANICAL_SUCCESS, attempted = true))
        assertTrue(decide(a).approved)
        // Leaving the app is always believed, even after the 30 s timeout dropped the approval.
        val b = popup(tree("광고", "닫기"), close, token = token("late"))
        assertTrue(decide(b).approved)
        now += 30_000
        assertEquals(setOf(AutoCloseBlock.FAILED_BEFORE), decide(b).blocks) // timed out
        auto.onResult(result(b, ActionStatus.MECHANICAL_SUCCESS, attempted = true, outcome = ClickOutcome.LEFT_APP))
        assertTrue(AutoCloseBlock.APP_PAUSED in decide(coupon()).blocks)
    }

    @Test fun atMostThreeAutomaticClosesPerAppPerMinute() {
        repeat(3) { i ->
            val a = popup(tree("광고", "닫기"), close, token = token("r$i"))
            assertTrue(decide(a).approved)
            auto.onResult(result(a, ActionStatus.MECHANICAL_SUCCESS, attempted = true))
        }
        val fourth = popup(tree("광고", "닫기"), close, token = token("r3"))
        assertEquals(setOf(AutoCloseBlock.RATE_LIMIT), decide(fourth).blocks)
        assertTrue(decide(popup(tree("광고", "닫기"), close, token = token("r4", app = "other.app"))).approved)
        now += 60_000
        assertTrue(decide(fourth).approved)
    }

    @Test fun verdictCarriesNoScreenText() {
        val v = decide(popup(tree("광고 · 비밀 상품명", "닫기"), close))
        assertFalse(v.toString().contains("비밀"))
        assertNull(decide(popup(tree("광고 · 비밀 상품명"), token = token("t2"))).candidateId)
    }

    // ---- Independent review, 2026-10-23: every probe that was approved before the fixes ----

    private fun kindOf(vararg texts: String) = DecisionKinds.of(popup(tree(*texts), close))

    @Test fun reviewScamProbesAreSuspicious() {
        for (texts in listOf(
            listOf("축하합니다! 이벤트에 선정되셨습니다", "지금 쿠폰을 받으세요"),
            listOf("경품 당 첨! 상품권 받기"),
            listOf("경품 당​첨! 상품권 받기"),
            listOf("ＣＯＮＧＲＡＴＵＬＡＴＩＯＮＳ", "Get your coupon"),
            listOf("메모리가 부족합니다. 지금 정리하세요", "광고"),
            listOf("1등에 선정되셨습니다. 기프티콘 수령"),
        )) assertEquals(texts.toString(), DecisionKind.SUSPICIOUS, kindOf(*texts.toTypedArray(), "닫기"))
    }

    @Test fun reviewDecisionProbesAreDecisions() {
        for (texts in listOf(
            listOf("특가 상품을 주문하시겠어요?", "주문하기"),
            listOf("사전 예약하시겠습니까?", "예약하기"),
            listOf("특가 이벤트 참여 신청", "신청하기"),
            listOf("광고", "알림 수신을 허 용해 주세요"),
            listOf("AD", "Ａｌｌｏｗ notifications"),
            listOf("광고", "ＳＩＧＮ ＵＰ today"),
            listOf("특가! 7일 무료 체험 시작하기"),
            listOf("특가 프로모션: 자동 갱신으로 연장하시겠어요?"),
            listOf("광고", "Upgrade to Premium now"),
            listOf("SALE", "Proceed to checkout"),
            listOf("특가 이벤트", "구독하면 첫 달 무료, 이후 자동 갱신됩니다"),
            listOf("광고", "새 운동화 보러 가실래요?"),
        )) assertEquals(texts.toString(), DecisionKind.USER_DECISION, kindOf(*texts.toTypedArray(), "닫기"))
        // A decision word hidden by spacing next to a benefit condition still counts (R1 stays narrow).
        assertEquals(DecisionKind.USER_DECISION, kindOf("가입하면 쿠폰을 드려요. 개인 정보 제공에 동 의"))
        // ...while a plain benefit condition stays a benefit, also once spaces are removed.
        assertEquals(DecisionKind.COUPON, kindOf("광고", "지금 구매하면 쿠폰 2장 증정"))
        // A decision word hidden in a close-looking candidate label also counts.
        val hidden = popup(tree("광고"), close, button("x", "허 용", ButtonRole.UNKNOWN))
        assertEquals(DecisionKind.USER_DECISION, DecisionKinds.of(hidden))
    }

    @Test fun reviewNoticeProbesAreNotices() {
        for (texts in listOf(
            listOf("서버 점검 안내", "점검 보상으로 쿠폰을 드립니다"),
            listOf("마일리지 소멸 예정 안내"),
            listOf("환불 안내", "5,000원이 지급됩니다"),
            listOf("쿠폰 적용 오류 안내"),
            listOf("신상정보 변경 안내"),
            listOf("광고성 정보 수신 거부 처리 안내"),
            listOf("보유하신 쿠폰이 내일 만료됩니다"),
        )) {
            assertEquals(texts.toString(), DecisionKind.NOTICE, kindOf(*texts.toTypedArray(), "닫기"))
            // So presets ② and ③ only tell them.
            val a = popup(tree(*texts.toTypedArray(), "닫기"), close, token = token(texts.first()))
            assertEquals(setOf(AutoCloseBlock.KIND_NOT_IN_PRESET), decide(a, preset = DecisionAutoClose.PRESET_ADS_AND_COUPONS).blocks)
        }
        // A service-notice word seen only by OCR never lets a coupon close as a notice.
        val ocrGuard = popup(tree("쿠폰 도착", "닫기") + ocr("점검 안내"), close, token = token("og"))
        assertEquals(setOf(AutoCloseBlock.NOT_TREE_EVIDENCE), decide(ocrGuard).blocks)
    }

    @Test fun aDeadCloseIsPressedOnlyOnce() {
        // Review round 2: a "닫기" that does nothing, evaluated again every 12 s, was pressed 3 times a minute.
        val a = ad()
        repeat(5) { i ->
            val screen = a.copy(token = a.token.copy(screenVersion = i.toLong()))
            val v = auto.evaluateScoped(eligible(screen), ctx(screen), DecisionAutoClose.PRESET_ALL)
            assertEquals("evaluation $i", i == 0, v.approved)
            if (v.approved) auto.onResult(result(screen, ActionStatus.MECHANICAL_SUCCESS, attempted = true))
            now += 12_000
        }
    }

    @Test fun reviewRound2StillRefused() {
        val refused = mapOf(
            listOf("경품 당ㅤ첨 쿠폰") to DecisionKind.SUSPICIOUS,
            listOf("알림을 허ㅤ용해 주세요", "광고") to DecisionKind.USER_DECISION,
            listOf("선택하세요", "광고") to DecisionKind.USER_DECISION,
            listOf("알림 켜기", "광고") to DecisionKind.USER_DECISION,
            listOf("공유를 켜 주세요", "특가") to DecisionKind.USER_DECISION,
            listOf("약관을 수락하세요", "광고") to DecisionKind.USER_DECISION,
            listOf("지금 신청", "특가") to DecisionKind.USER_DECISION,
            listOf("7일 무료체험", "광고") to DecisionKind.USER_DECISION,
            // Mixed scripts: ASCII boundaries match on the JDK and on Android alike.
            listOf("Premium으로 upgrade하세요", "광고") to DecisionKind.USER_DECISION,
            listOf("checkout하기", "SALE") to DecisionKind.USER_DECISION,
            listOf("sign up하고 혜택", "광고") to DecisionKind.USER_DECISION,
            listOf("특가 세일 오늘 자정 종료됩니다") to DecisionKind.NOTICE,
            listOf("포인트 서비스가 종료됩니다. 남은 적립금을 확인하세요") to DecisionKind.NOTICE,
        )
        for ((texts, kind) in refused) assertEquals(texts.toString(), kind, kindOf(*texts.toTypedArray(), "닫기"))
    }

    /** Ordinary ad and coupon wording the first fixes made unclosable (review round 2): closable again. */
    @Test fun reviewRound2OrdinaryAdsAndCouponsAreClosable() {
        val closable = mapOf(
            listOf("아직도 정가로 사세요? 최대 50% 할인") to DecisionKind.ADVERTISEMENT,
            listOf("오늘 뭐 먹지? 배달 쿠폰 3,000원") to DecisionKind.COUPON,
            listOf("이벤트 참여하기", "광고") to DecisionKind.ADVERTISEMENT,
            listOf("지금 바로 참여하세요! 특가 세일") to DecisionKind.ADVERTISEMENT,
            listOf("지금 시작하기", "광고") to DecisionKind.ADVERTISEMENT,
            listOf("지금 이용해 보세요 특가") to DecisionKind.ADVERTISEMENT,
            listOf("앱 다운로드하기 쿠폰 지급") to DecisionKind.COUPON,
            listOf("생일을 축하합니다! 생일 쿠폰이 도착했어요") to DecisionKind.COUPON,
            listOf("1등급 한우 특가") to DecisionKind.ADVERTISEMENT,
            listOf("판매 1등 특가") to DecisionKind.ADVERTISEMENT,
            listOf("브랜드 대상에 선정되었습니다", "광고") to DecisionKind.ADVERTISEMENT,
            listOf("Selected items 50% off") to DecisionKind.ADVERTISEMENT,
            listOf("옷장 정리하세요! 수납용품 특가") to DecisionKind.ADVERTISEMENT,
            listOf("스마트폰 손상 걱정 끝 케이스 특가") to DecisionKind.ADVERTISEMENT,
            listOf("SALE", "Terms apply") to DecisionKind.ADVERTISEMENT,
            listOf("보 안경 특가") to DecisionKind.ADVERTISEMENT,
        )
        for ((texts, kind) in closable) assertEquals(texts.toString(), kind, kindOf(*texts.toTypedArray(), "닫기"))
        // A page tab such as "공지사항" behind a same-window coupon no longer makes it a notice.
        val page = ScreenBounds(0, 0, 1080, 2400)
        val nodes = listOf(
            NodeSnapshot("0", null, "android.widget.FrameLayout", null, null, false, true, page),
            NodeSnapshot("0/0", null, "android.widget.TextView", "공지사항", null, false, true, ScreenBounds(40, 100, 500, 200)),
            NodeSnapshot("0/1", null, "android.widget.TextView", "무엇을 찾으세요?", null, false, true, ScreenBounds(40, 200, 1040, 300)),
            NodeSnapshot("0/2", null, "android.app.Dialog", null, null, false, true, ScreenBounds(80, 900, 1000, 1500)),
            NodeSnapshot("0/2/0", null, "android.widget.TextView", "쿠폰이 도착했어요", null, false, true, ScreenBounds(100, 950, 900, 1050)),
        )
        val t = token("web-coupon")
        val snapshot = PopupSnapshot(t, 0, nodes.toPersistentList(), kotlinx.collections.immutable.persistentListOf(), page,
            PopupTrigger.CONTENT_CHANGED, SnapshotCompleteness.COMPLETE)
        val detection = PopupDetection(t, kotlinx.collections.immutable.persistentSetOf(PopupSignal.SAME_WINDOW_OVERLAY))
        val texts = nodes.mapNotNull { n -> n.text?.let { ExtractedText(it, n.bounds, TextSource.ACCESSIBILITY_TREE, null) } }
        val v = auto.evaluate(snapshot, detection, eligible(PopupAnalysis(t, texts = texts.toPersistentList())), DecisionContext(t, null, null, false, true),
            DecisionAutoClose.PRESET_ADS_AND_COUPONS)
        assertEquals(DecisionKind.COUPON, v.kind)
    }

    @Test fun anApprovalWithoutAResultEventuallyCountsAsUnknown() {
        val a = ad()
        assertTrue(decide(a).approved)
        now += 29_999
        assertEquals(setOf(AutoCloseBlock.AWAITING_RESULT), decide(a).blocks)
        now += 1
        assertEquals(setOf(AutoCloseBlock.FAILED_BEFORE), decide(a).blocks)
    }

    @Test fun mockDataIsNeverClosed() {
        val a = ad().copy(limitations = kotlinx.collections.immutable.persistentSetOf(AnalysisLimit.MOCK_ONLY))
        assertEquals(setOf(AutoCloseBlock.MOCK_DATA), decide(a).blocks)
    }

    @Test fun theEntryPointScopesFirstAndRejectsMixedTokens() {
        val page = ScreenBounds(0, 0, 1080, 2400)
        val dialog = ScreenBounds(80, 900, 1000, 1500)
        val nodes = listOf(
            NodeSnapshot("0", null, "android.widget.FrameLayout", null, null, false, true, page),
            NodeSnapshot("0/0", null, "android.widget.TextView", "쿠폰 모음", null, false, true, ScreenBounds(40, 100, 1040, 200)),
            NodeSnapshot("0/1", null, "android.app.Dialog", null, null, false, true, dialog),
            NodeSnapshot("0/1/0", null, "android.widget.TextView", "운영 시간 안내", null, false, true, ScreenBounds(100, 950, 900, 1050)),
            NodeSnapshot("0/1/1", null, "android.widget.Button", "닫기", null, true, true, ScreenBounds(100, 1350, 900, 1450),
                kotlinx.collections.immutable.persistentSetOf(NodeAction.CLICK)),
        )
        val t = token("scoped")
        val snapshot = PopupSnapshot(t, 0, nodes.toPersistentList(), kotlinx.collections.immutable.persistentListOf(), page,
            PopupTrigger.CONTENT_CHANGED, SnapshotCompleteness.COMPLETE)
        val detection = PopupDetection(t, kotlinx.collections.immutable.persistentSetOf(PopupSignal.SAME_WINDOW_OVERLAY))
        val texts = nodes.mapNotNull { n -> n.text?.let { ExtractedText(it, n.bounds, TextSource.ACCESSIBILITY_TREE, null) } }
        val analysis = PopupAnalysis(t, texts = texts.toPersistentList(), closeCandidates = DecisionCloseCandidateFinder().find(snapshot, detection))
        // Unscoped, the page's "쿠폰" would make this notice a coupon that preset ② closes.
        assertEquals(DecisionKind.COUPON, DecisionKinds.of(analysis))
        val v = auto.evaluate(snapshot, detection, eligible(analysis), ctx(analysis), DecisionAutoClose.PRESET_ADS_AND_COUPONS)
        assertEquals(DecisionKind.NOTICE, v.kind)
        assertEquals(setOf(AutoCloseBlock.KIND_NOT_IN_PRESET), v.blocks)
        val other = auto.evaluate(snapshot.copy(token = token("other")), detection, analysis, ctx(analysis), DecisionAutoClose.PRESET_ALL)
        assertEquals(setOf(AutoCloseBlock.STALE_SCREEN), other.blocks)
    }
    // ---- Contract v2: A's eligibility evidence and the observed click outcome ----

    @Test fun noCompleteCurrentEligibilityNoAutomaticClose() {
        val a = ad()
        val all = EligibilityCheck.entries.toSet()
        fun with(e: AutoEligibility?) = auto.evaluateScoped(a.copy(autoEligibility = e), ctx(a), DecisionAutoClose.PRESET_ALL).blocks
        assertEquals(setOf(AutoCloseBlock.NOT_AUTO_ELIGIBLE), with(null))
        for (missing in all) {
            val partial = AutoEligibility(a.token, kotlinx.collections.immutable.persistentSetOf(*(all - missing).toTypedArray()), now)
            assertEquals(missing.name, setOf(AutoCloseBlock.NOT_AUTO_ELIGIBLE), with(partial))
        }
        val full = kotlinx.collections.immutable.persistentSetOf(*all.toTypedArray())
        assertEquals("issued 5 s ago", setOf(AutoCloseBlock.NOT_AUTO_ELIGIBLE), with(AutoEligibility(a.token, full, now - 5_000)))
        assertEquals("another screen", setOf(AutoCloseBlock.NOT_AUTO_ELIGIBLE), with(AutoEligibility(token("other"), full, now)))
        assertTrue(auto.evaluateScoped(a.copy(autoEligibility = AutoEligibility(a.token, full, now - 4_999)), ctx(a), DecisionAutoClose.PRESET_ALL).approved)
    }

    @Test fun onlyAConfirmedCloseCountsAsClosed() {
        val cases = mapOf<ClickOutcome?, AutoCloseBlock>(
            ClickOutcome.CLOSED_CONFIRMED to AutoCloseBlock.CLOSED_BEFORE,
            ClickOutcome.STILL_PRESENT to AutoCloseBlock.FAILED_BEFORE,
            ClickOutcome.UNKNOWN to AutoCloseBlock.FAILED_BEFORE,
            // A delivered click without an observation is not a closed popup (contract v2).
            null to AutoCloseBlock.FAILED_BEFORE,
        )
        for ((i, entry) in cases.entries.withIndex()) {
            val a = popup(tree("광고", "닫기"), close, token = token("o$i", app = "app$i"))
            assertTrue(decide(a).approved)
            auto.onResult(result(a, ActionStatus.MECHANICAL_SUCCESS, attempted = true, outcome = entry.key))
            assertEquals(entry.key.toString(), setOf(entry.value), decide(a).blocks)
        }
    }

    @Test fun aPopupThatVanishedAfterAFailedPressIsAFailure() {
        // Team rule (10/25): success needs attempted + MECHANICAL_SUCCESS + CLOSED_CONFIRMED. A popup that
        // disappeared by itself after a failed or unknown press is never "closed by us".
        for ((i, status) in listOf(ActionStatus.ATTEMPT_FAILED, ActionStatus.OUTCOME_UNKNOWN).withIndex()) {
            val a = popup(tree("광고", "닫기"), close, token = token("v$i", app = "vanish$i"))
            assertTrue(decide(a).approved)
            auto.onResult(result(a, status, attempted = true, outcome = ClickOutcome.CLOSED_CONFIRMED))
            assertEquals(status.name, setOf(AutoCloseBlock.FAILED_BEFORE), decide(a).blocks)
        }
    }
}
