package kr.closeforyou.decision

import kr.closeforyou.core.model.AnalysisLimit
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six-kind rules on the held-out [DecisionKindEvaluationSet] (AUTO_CLOSE.md 6절), plus their
 * compatibility with today's four-way classifier on the 82-sample set. Safety is enforced; the
 * other numbers are the baseline recorded in AUTO_CLOSE.md and move only with that report.
 */
class DecisionKindEvaluationTest {
    private val token = MockPopupFixtures.notice().snapshot.token
    private val limitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE, AnalysisLimit.IMAGE_MODEL_NOT_IMPLEMENTED)

    private fun analysis(texts: List<Pair<String, kr.closeforyou.core.model.TextSource>>) = PopupAnalysis(
        token,
        texts = texts.mapIndexed { i, (text, source) ->
            ExtractedText(text, ScreenBounds(80, 400 + i * 100, 1000, 490 + i * 100), source, null)
        }.toPersistentList(),
        limitations = limitations,
    )

    private fun Kind6.asKind() = when (this) {
        Kind6.DECISION -> DecisionKind.USER_DECISION
        Kind6.SUSPICIOUS -> DecisionKind.SUSPICIOUS
        Kind6.UNKNOWN -> DecisionKind.UNKNOWN
        Kind6.COUPON -> DecisionKind.COUPON
        Kind6.ADVERTISEMENT -> DecisionKind.ADVERTISEMENT
        Kind6.NOTICE -> DecisionKind.NOTICE
    }

    private val results by lazy {
        DecisionKindEvaluationSet.samples.map { it to DecisionKinds.of(analysis(it.texts)) }
    }

    @Test fun setIsUniqueAndCoversEveryKind() {
        val samples = DecisionKindEvaluationSet.samples
        assertEquals(samples.size, samples.map { it.id }.toSet().size)
        assertEquals(Kind6.entries.toSet(), samples.map { it.truth }.toSet())
    }

    /** The safety requirement: a decision or scam popup is never a kind that a preset closes. */
    @Test fun noDecisionOrScamIsEverAClosableKind() {
        val dangerous = results.filter { (sample, kind) ->
            (sample.truth == Kind6.DECISION || sample.truth == Kind6.SUSPICIOUS) && kind in DecisionAutoClose.CLOSABLE
        }.map { it.first.id }
        assertTrue("closable: $dangerous", dangerous.isEmpty())
    }

    @Test fun confusionMatrixMatchesTheRecordedBaseline() {
        val wrong = results.filter { (sample, kind) -> sample.truth.asKind() != kind }
            .map { (sample, kind) -> "${sample.id}:${sample.truth}->$kind" }
        assertEquals(RECORDED_ERRORS, wrong)
    }

    /**
     * The display classifier is the six kinds now (contract v2). Against the classifier before that
     * (main b8f7264) four of the 82 moved, all from UNKNOWN to their right class: ad-05 ("할인 이벤트"),
     * no-18 ("종료되었습니다"), no-23 ("maintenance"), de-22 (an install question); none toward closing.
     */
    @Test fun displayMappingAgainstTodaysClassifierOn82Samples() = runBlocking {
        val classifier = DecisionPopupClassifier()
        val changed = DecisionClassifierEvaluationSet.samples.mapNotNull { sample ->
            val a = analysis(if (sample.text.isEmpty()) emptyList() else listOf(sample.text to sample.source))
            val today = classifier.classify(a)
            val kind = DecisionKinds.of(a)
            if (kind.toDisplay() == today) null else "${sample.id}:${sample.truth}:$today->$kind"
        }
        assertEquals(emptyList<String>(), changed)
        // Never less careful than today: nothing today calls a decision becomes closable.
        for (sample in DecisionClassifierEvaluationSet.samples) {
            val a = analysis(if (sample.text.isEmpty()) emptyList() else listOf(sample.text to sample.source))
            if (classifier.classify(a) == PopupClassification.USER_DECISION) {
                assertEquals(sample.id, DecisionKind.USER_DECISION, DecisionKinds.of(a).let { if (it == DecisionKind.SUSPICIOUS) DecisionKind.USER_DECISION else it })
            }
        }
    }

    /** The 35-sample over-warning set (18 decisions, many next to benefit words): none becomes closable. */
    @Test fun overWarningStudyDecisionsStayNonClosable() {
        val study = DecisionOverWarningStudyTest().samples
        val kinds = study.associate { s -> s.id to DecisionKinds.of(analysis(s.texts.map { it to kr.closeforyou.core.model.TextSource.ACCESSIBILITY_TREE })) }
        val dangerous = study.filter { it.truth == DecisionOverWarningStudyTest.Truth.DECISION && kinds.getValue(it.id) in DecisionAutoClose.CLOSABLE }
        assertTrue("closable decision: ${dangerous.map { it.id }}", dangerous.isEmpty())
        val counts = study.groupBy { it.truth }.mapValues { (_, list) -> list.groupingBy { kinds.getValue(it.id) }.eachCount() }
        assertEquals(RECORDED_STUDY, counts.toString())
    }

    @Test fun emptyAnalysisIsUnknown() {
        assertEquals(DecisionKind.UNKNOWN, DecisionKinds.of(PopupAnalysis(token, texts = persistentListOf())))
    }

    private companion object {
        // 2026-10-23 after the independent review: 73/80, all on the cautious side (AUTO_CLOSE.md 6절).
        // The first run (72/80) also had de-06 as UNKNOWN and cp-07 as COUPON. 2026-10-30: 74/80 after the
        // app-install advertisement words from the real 11번가 web sheet (WEB_OVERLAY.md 3-2); ad-19 is no
        // longer an independent held-out result.
        val RECORDED_ERRORS = listOf(
            "cp-02:COUPON->USER_DECISION", // "구매 고객" is a decision word outside a benefit condition
            "cp-07:COUPON->NOTICE", // "쿠폰이 내일 만료됩니다": an expiry is a service notice (told, closed only by ①)
            "cp-08:COUPON->UNKNOWN", // "5천 포인트" with no giving verb
            "no-05:NOTICE->UNKNOWN", "no-07:NOTICE->UNKNOWN", "no-09:NOTICE->UNKNOWN", // no notice word
        )
        const val RECORDED_STUDY =
            "{AD={COUPON=5, ADVERTISEMENT=6, USER_DECISION=1}, NOTICE={USER_DECISION=5}, DECISION={USER_DECISION=18}}"
    }
}
