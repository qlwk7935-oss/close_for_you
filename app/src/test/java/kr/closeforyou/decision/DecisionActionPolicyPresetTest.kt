package kr.closeforyou.decision

import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The policy on contract v2: the preset path, who asked ([ActionDecision.source]) and what C does
 * next ([ActionDecision.next]). AUTO_CLOSE.md 4절 conditions themselves are in DecisionAutoCloseTest.
 */
class DecisionActionPolicyPresetTest {
    private val base: SnapshotToken = MockPopupFixtures.notice().snapshot.token
    private val bounds = ScreenBounds(80, 400, 1000, 1450)
    private var now = 1_000L
    private val policy = DecisionActionPolicy { now }

    private val adsOnly: ImmutableSet<PopupClassification> = persistentSetOf(PopupClassification.ADVERTISEMENT)
    private val all: ImmutableSet<PopupClassification> =
        persistentSetOf(PopupClassification.ADVERTISEMENT, PopupClassification.COUPON, PopupClassification.NOTICE)

    private fun token(popup: String = "p", app: String = base.packageName) = base.copy(structureDigest = popup, packageName = app)
    private val close = CloseCandidate("node:c", "c", null, "닫기", bounds, EvidenceLevel.E2_LABELED_NODE, ButtonRole.CLOSE)

    private fun popup(vararg texts: String, buttons: List<CloseCandidate> = listOf(close), token: SnapshotToken = token(), eligible: Boolean = true) =
        PopupAnalysis(
            token,
            texts = texts.map { ExtractedText(it, bounds, TextSource.ACCESSIBILITY_TREE, null) }.toPersistentList(),
            closeCandidates = buttons.toPersistentList(),
            limitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE),
            autoEligibility = if (eligible) AutoEligibility(token, persistentSetOf(*EligibilityCheck.entries.toTypedArray()), now) else null,
        )

    private fun decide(a: PopupAnalysis, preset: ImmutableSet<PopupClassification> = adsOnly, choice: UserChoice? = null, rule: PopupRule? = null,
                       current: SnapshotToken? = a.token) =
        runBlocking { policy.decide(a, DecisionContext(current, choice, rule, false, true, preset)) }

    @Test fun thePresetClosesAnEligibleFirstSeenAd() {
        val d = decide(popup("광고", "신상 운동화", "닫기"))
        assertEquals(ActionAuthorization.ALLOW_ONCE, d.authorization)
        assertEquals(NextStep.EXECUTE, d.next)
        assertEquals(DecisionSource.PRESET, d.source)
        assertEquals("node:c", d.candidateId)
        assertTrue(d.reasons.isEmpty())
    }

    @Test fun withoutAsEligibilityItAsksAsToday() {
        val d = decide(popup("광고", "신상 운동화", "닫기", eligible = false))
        assertEquals(ActionAuthorization.NEEDS_CONFIRMATION, d.authorization)
        assertEquals(NextStep.ASK, d.next)
        assertEquals(DecisionSource.PRESET, d.source)
        assertEquals(setOf(SafetyReason.FIRST_SEEN, SafetyReason.NOT_AUTO_ELIGIBLE), d.reasons)
        assertEquals("the person may still choose it", "node:c", d.candidateId)
    }

    @Test fun aSkipOnlyAdIsAskedUntilAPressesSkip() {
        val skip = CloseCandidate("node:s", "s", null, "건너뛰기", bounds, EvidenceLevel.E2_LABELED_NODE, ButtonRole.SKIP)
        val ad = popup("영상 광고", "건너뛰기", buttons = listOf(skip), token = token("skip"))
        // Default (F9 is CLOSE-only): the person is asked and may choose the skip themselves.
        decide(ad).let {
            assertEquals(NextStep.ASK, it.next)
            assertEquals(ActionAuthorization.NEEDS_CONFIRMATION, it.authorization)
            assertEquals("node:s", it.candidateId)
        }
        // Once A's executor presses SKIP, A turns it on and the same ad is closed automatically.
        val skipping = DecisionActionPolicy(allowAutoSkip = true) { now }
        val d = runBlocking { skipping.decide(ad, DecisionContext(ad.token, null, null, false, true, adsOnly)) }
        assertEquals(NextStep.EXECUTE, d.next)
        assertEquals("node:s", d.candidateId)
    }

    @Test fun eachOutcomeTellsCWhatToDo() {
        // A coupon under preset ③ is only told.
        decide(popup("쿠폰이 도착했어요", "닫기", token = token("c"))).let {
            assertEquals(NextStep.ANNOUNCE, it.next)
            assertTrue(SafetyReason.PRESET_NOT_ALLOWED in it.reasons)
        }
        // A notice with only "확인" under preset ①: told, nothing verified to press.
        decide(popup("공지", "점검 안내", "확인", buttons = emptyList(), token = token("n")), preset = all).let {
            assertEquals(ActionAuthorization.DENY, it.authorization)
            assertEquals(NextStep.ANNOUNCE, it.next)
        }
        // An ad whose close button has not appeared yet: wait, do not prompt.
        decide(popup("광고", "신상 운동화", buttons = emptyList(), token = token("w"))).let {
            assertEquals(NextStep.WAIT, it.next)
            assertTrue(SafetyReason.WAITING_FOR_TARGET in it.reasons)
        }
        // Two close buttons: ask.
        decide(popup("광고", "닫기", "닫기", buttons = listOf(close, close.copy(id = "node:d", nodePath = "d")), token = token("a"))).let {
            assertEquals(NextStep.ASK, it.next)
            assertTrue(SafetyReason.UNVERIFIED_TARGET in it.reasons)
        }
        // An old screen: wait for the current one.
        decide(popup("광고", "닫기", token = token("s")), current = token("newer"))
            .let { assertEquals(NextStep.WAIT, it.next); assertEquals(ActionAuthorization.DENY, it.authorization) }
    }

    @Test fun decisionsAndScamsAreOnlyExplainedWhoeverAsks() {
        val decision = popup("광고", "특가 상품을 주문하시겠어요?", "닫기", token = token("d"))
        val scam = popup("축하합니다! 경품에 당첨되었습니다", "닫기", token = token("s"))
        val choose = { a: PopupAnalysis -> UserChoice(a.token, ChoiceKind.CLOSE_ONCE, "node:c", buttonMeaningConfirmed = true) }
        for ((a, reason) in listOf(decision to SafetyReason.IMPORTANT_DECISION, scam to SafetyReason.SUSPICIOUS_CONTENT)) {
            for (d in listOf(decide(a, preset = all), decide(a, preset = persistentSetOf()), decide(a, choice = choose(a)))) {
                assertEquals(ActionAuthorization.DENY, d.authorization)
                assertEquals(NextStep.DENY, d.next)
                assertTrue(reason in d.reasons)
                assertNull(d.candidateId)
            }
        }
    }

    @Test fun withoutAPresetEverythingIsAsBefore() {
        val ad = popup("광고", "신상 운동화", "닫기")
        for (preset in listOf(persistentSetOf(), persistentSetOf(PopupClassification.USER_DECISION, PopupClassification.UNKNOWN))) {
            val d = decide(ad, preset = preset)
            assertEquals(ActionAuthorization.NEEDS_CONFIRMATION, d.authorization)
            assertEquals(setOf(SafetyReason.FIRST_SEEN), d.reasons)
            assertEquals("node:c", d.candidateId)
            assertNull(d.source)
            assertEquals(NextStep.ASK, d.next)
        }
        val chosen = decide(ad, preset = persistentSetOf(), choice = UserChoice(ad.token, ChoiceKind.CLOSE_ONCE, "node:c", buttonMeaningConfirmed = true))
        assertEquals(ActionAuthorization.ALLOW_ONCE, chosen.authorization)
        assertEquals(DecisionSource.USER_CHOICE, chosen.source)
        val rule = PopupRule("r", RuleKey("p", 1, "s", "c", "t"), ChoiceKind.REMEMBER_EXACT_POPUP, true, true, 1, null)
        val remembered = decide(ad, rule = rule)
        assertEquals(DecisionSource.REMEMBERED_RULE, remembered.source)
        assertEquals("repeats still wait for their own agreement", ActionAuthorization.NEEDS_CONFIRMATION, remembered.authorization)
    }

    @Test fun resultsFromAShapeTheNextDecision() {
        val ad = popup("광고", "닫기")
        assertEquals(NextStep.EXECUTE, decide(ad).next)
        assertEquals("sent, result not back yet", NextStep.WAIT, decide(ad).next)
        policy.onActionResult(ActionResult(ad.token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = true, outcome = ClickOutcome.CLOSED_CONFIRMED))
        decide(ad).let {
            assertEquals("closed once: asked if it shows again", NextStep.ASK, it.next)
            assertTrue(SafetyReason.REPEAT_NOT_ALLOWED in it.reasons)
        }
        // A press that left the app stops automation for the whole app, even for the person's own press.
        val other = popup("광고", "닫기", token = token("other"))
        policy.onActionResult(ActionResult(token("chosen"), ActionStatus.MECHANICAL_SUCCESS, null, attempted = true, outcome = ClickOutcome.LEFT_APP))
        decide(other).let {
            assertEquals(NextStep.ASK, it.next)
            assertTrue(SafetyReason.LEFT_APP_BEFORE in it.reasons)
        }
    }
}
