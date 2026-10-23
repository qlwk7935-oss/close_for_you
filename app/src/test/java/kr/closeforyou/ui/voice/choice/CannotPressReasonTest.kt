package kr.closeforyou.ui.voice.choice

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kr.closeforyou.R
import kr.closeforyou.core.model.LabelRejectReason
import kr.closeforyou.core.model.SafetyReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class CannotPressReasonTest {
    /** Reads C's string file so wording rules are checked on the real sentences. */
    private val strings: Map<String, String> by lazy {
        val file = listOf("src/main/res/values/ui_voice_strings.xml", "app/src/main/res/values/ui_voice_strings.xml")
            .map(::File).first { it.exists() }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to e.textContent
        }
    }

    private fun text(resId: Int): String {
        val name = R.string::class.java.fields.first { it.getInt(null) == resId }.name
        return requireNotNull(strings[name]) { "missing string $name" }
    }

    private val allLabelSentences get() = LabelRejectReason.entries.map { text(CannotPressReasonSpeech.labelRes(it)) }

    /** A new contract reason must get its own C sentence (the `when` in labelRes also stops the build). */
    @Test fun contractStillHasTheEightReasonsCMaps() {
        assertEquals(
            listOf("NO_LABEL", "SYMBOL_ONLY", "DECISION_WORD", "CONDITIONAL_WORD", "NAVIGATION_WORD",
                "AMBIGUOUS_WORD", "NOT_IN_LIST", "LABEL_CONFLICT"),
            LabelRejectReason.entries.map { it.name },
        )
    }

    @Test fun everyLabelReasonHasItsOwnNonEmptySentence() {
        val ids = LabelRejectReason.entries.map(CannotPressReasonSpeech::labelRes)
        assertEquals(8, ids.toSet().size)
        allLabelSentences.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test fun decisionSentencesDoNotAssertPayment() {
        val decision = listOf(
            text(CannotPressReasonSpeech.labelRes(LabelRejectReason.DECISION_WORD)),
            text(CannotPressReasonSpeech.policyRes(SafetyReason.IMPORTANT_DECISION)),
        )
        val asserted = listOf("결제", "구매", "구독", "동의", "삭제", "권한", "로그인")
        decision.forEach { sentence -> asserted.forEach { assertFalse("$it in $sentence", sentence.contains(it)) } }
        decision.forEach { assertTrue(it.contains("수 있는")) }
    }

    @Test fun sentencesHaveNoLabelPlaceholderAndNeverSayPressable() {
        val all = allLabelSentences + listOf(
            SafetyReason.IMPORTANT_DECISION, SafetyReason.PRIVACY_BLOCKED,
            SafetyReason.UNVERIFIED_TARGET, SafetyReason.INCOMPLETE_EVIDENCE,
        ).map { text(CannotPressReasonSpeech.policyRes(it)) }
        all.forEach {
            assertFalse("placeholder in $it", it.contains("%"))
            assertFalse(it.contains("누를 수 있어요"))
            assertTrue(it.endsWith("대신 누르지 않아요."))
        }
    }

    @Test fun reasonSentencesAreNotExecutionResultSentences() {
        val results = setOf(R.string.ui_result_not_pressed, R.string.ui_result_screen_changed,
            R.string.ui_result_press_failed, R.string.ui_result_not_confirmed)
        LabelRejectReason.entries.forEach { assertFalse(CannotPressReasonSpeech.labelRes(it) in results) }
    }

    @Test fun policyReasonComesBeforeLabelReason() {
        val explanation = CannotPressReasonSpeech.explain(
            setOf(SafetyReason.FIRST_SEEN, SafetyReason.IMPORTANT_DECISION),
            listOf(LabelRejectReason.SYMBOL_ONLY),
            enabled = true,
        )
        assertEquals(CannotPressExplanation.Policy(SafetyReason.IMPORTANT_DECISION), explanation)
    }

    @Test fun nonRefusalPolicyReasonFallsThroughToLabel() {
        val explanation = CannotPressReasonSpeech.explain(
            setOf(SafetyReason.FIRST_SEEN, SafetyReason.EXPLICIT_PERMISSION_REQUIRED),
            listOf(LabelRejectReason.NO_LABEL, LabelRejectReason.DECISION_WORD),
            enabled = true,
        )
        assertEquals(CannotPressExplanation.Label(LabelRejectReason.DECISION_WORD), explanation)
    }

    @Test fun nothingToExplainOrFeatureOffKeepsGenericSentence() {
        assertNull(CannotPressReasonSpeech.explain(emptySet(), emptyList(), enabled = true))
        assertNull(
            CannotPressReasonSpeech.explain(setOf(SafetyReason.IMPORTANT_DECISION), listOf(LabelRejectReason.NO_LABEL)),
        )
        assertFalse(CannotPressReasonFeature.enabled)
    }

    @Test fun explanationToStringCarriesOnlyCodes() {
        val label = CannotPressExplanation.Label(LabelRejectReason.NOT_IN_LIST)
        assertEquals("Label(reason=NOT_IN_LIST)", label.toString())
    }
}
