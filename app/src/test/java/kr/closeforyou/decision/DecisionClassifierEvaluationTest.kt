package kr.closeforyou.decision

import kr.closeforyou.core.model.AnalysisLimit
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.PopupClassification.ADVERTISEMENT
import kr.closeforyou.core.model.PopupClassification.NOTICE
import kr.closeforyou.core.model.PopupClassification.USER_DECISION
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification plan C04 on a held-out synthetic set: the safety requirement is enforced, the
 * remaining numbers are a regression baseline recorded in CLASSIFIER_EVAL.md. Changing the
 * classifier may move the baseline, but only together with an updated report.
 */
class DecisionClassifierEvaluationTest {
    private val classifier = DecisionPopupClassifier()
    private val bounds = ScreenBounds(80, 400, 1000, 1450)
    // A's runtime marks every analysis like this.
    private val limitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE, AnalysisLimit.IMAGE_MODEL_NOT_IMPLEMENTED)

    private val results: List<Pair<EvaluationSample, PopupClassification>> by lazy {
        runBlocking {
            DecisionClassifierEvaluationSet.samples.map { sample ->
                val texts = if (sample.text.isEmpty()) persistentListOf() else
                    persistentListOf(ExtractedText(sample.text, bounds, sample.source, null))
                sample to classifier.classify(
                    PopupAnalysis(MockPopupFixtures.notice().snapshot.token, texts = texts, limitations = limitations),
                )
            }
        }
    }

    @Test fun setIsBalancedAndUnique() {
        val samples = DecisionClassifierEvaluationSet.samples
        assertEquals(samples.size, samples.map { it.id }.toSet().size)
        assertEquals(82, samples.size)
        assertEquals(mapOf(ADVERTISEMENT to 24, NOTICE to 24, USER_DECISION to 24, PopupClassification.UNKNOWN to 10),
            samples.groupingBy { it.truth }.eachCount())
    }

    /** C04 safety: a decision screen must never be announced as an advertisement or a notice. */
    @Test fun noDecisionIsShownAsAnAdvertisementOrANotice() {
        val dangerous = results.filter { (sample, predicted) ->
            // COUPON (contract v2) is closable too, so it counts as dangerous for a decision.
            sample.truth == USER_DECISION && (predicted == ADVERTISEMENT || predicted == NOTICE || predicted == PopupClassification.COUPON)
        }.map { it.first.id }
        assertTrue("decision shown as ad/notice: $dangerous", dangerous.isEmpty())
    }

    @Test fun confusionMatrixMatchesTheRecordedBaseline() {
        val matrix = PopupClassification.entries.associateWith { truth ->
            PopupClassification.entries.associateWith { predicted ->
                results.count { it.first.truth == truth && it.second == predicted }
            }
        }
        println(report(matrix))
        assertEquals(BASELINE_CORRECT, results.count { it.first.truth == legacy(it.second) })
    }

    private fun report(matrix: Map<PopupClassification, Map<PopupClassification, Int>>): String = buildString {
        val classes = PopupClassification.entries
        appendLine("CLASSIFIER_EVAL rows=truth cols=predicted " + classes.joinToString(" "))
        for (truth in classes) appendLine("CLASSIFIER_EVAL ${truth.name} " + classes.joinToString(" ") { matrix.getValue(truth).getValue(it).toString() })
        appendLine("CLASSIFIER_EVAL correct=${results.count { it.first.truth == legacy(it.second) }} total=${results.size}")
        for ((sample, predicted) in results) if (sample.truth != legacy(predicted)) {
            appendLine("CLASSIFIER_EVAL miss ${sample.id} truth=${sample.truth} predicted=$predicted source=${sample.source}")
        }
    }

    /** This set's rubric (2026-10-19) has no coupon class: a coupon is the promotion it labelled ADVERTISEMENT. */
    private fun legacy(predicted: PopupClassification) =
        if (predicted == PopupClassification.COUPON) ADVERTISEMENT else predicted

    private companion object {
        /**
         * 68 on 2026-10-19 (main 845214a); 69 after R1 on 2026-10-22; 73 with the six kinds (contract v2,
         * 2026-10-24: ad-05, no-18, no-23, de-22 from UNKNOWN to their class). CLASSIFIER_EVAL.md 5, 11, 12.
         */
        const val BASELINE_CORRECT = 73
    }
}
