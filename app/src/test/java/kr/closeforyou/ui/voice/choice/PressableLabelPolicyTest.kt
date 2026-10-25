package kr.closeforyou.ui.voice.choice

import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.LabelVectorFile
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C behaviour only: when the prompt shows a press button. The label contract itself is tested in
 * core (`ButtonLabelRulesTest`); here its vector file is reused, not copied.
 */
class PressableLabelPolicyTest {
    @Test fun pressButtonShownExactlyWhenContractRoleEqualsCandidateRole() {
        val rows = LabelVectorFile.read(resource(LabelVectorFile.VECTORS))
        assertEquals(176, rows.size)
        val wrong = rows.flatMap { row ->
            val label = LabelVectorFile.label(row["label_escaped"]!!)
            listOf(ButtonRole.CLOSE, ButtonRole.SKIP).mapNotNull { role ->
                val expected = row["expected_role"] == role.name
                val shown = pressIds(PopupClassification.ADVERTISEMENT, candidate("c", label, role)) == listOf("c")
                if (shown == expected) null else "${row["id"]}/$role"
            }
        }
        // Ids only: labels are screen-like text and stay out of failure messages.
        assertTrue("wrong: $wrong", wrong.isEmpty())
    }

    @Test fun sixLabelsAddedByContractV1NowGetAPressButton() {
        val close = listOf("광고 닫기", "공지 닫기", "닫기 버튼", "close ad")
        val skip = listOf("skip ad", "광고 건너뛰기")
        val candidates = close.mapIndexed { i, it -> candidate("c$i", it) } +
            skip.mapIndexed { i, it -> candidate("s$i", it, ButtonRole.SKIP) }
        assertEquals(candidates.map { it.id }, pressIds(PopupClassification.ADVERTISEMENT, *candidates.toTypedArray()))
    }

    @Test fun xAndRiskyLabelsGetNoPressButton() {
        val analysis = MockPopupFixtures.notice().analysis.copy(
            closeCandidates = persistentListOf(
                candidate("x", "X"),
                candidate("times", "×"),
                candidate("ok", "확인"),
                candidate("today", "오늘 하루 닫기"),
                candidate("agree", "동의하고 닫기"),
                candidate("dismiss", null, evidence = EvidenceLevel.E1_DISMISS),
            ),
        )
        val prompt = ChoicePromptPlanner.plan(analysis, PopupClassification.ADVERTISEMENT)
        assertTrue(prompt.options.none { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        assertTrue(prompt.cannotPressForUser)
    }

    @Test fun labelIsShownTrimmedButCheckedByTheContract() {
        val press = pressOptions(
            PopupClassification.NOTICE,
            candidate("spaced", "  닫기  "),
            candidate("dismiss", "Dismiss", evidence = EvidenceLevel.E1_DISMISS),
            candidate("skip", "SKIP", role = ButtonRole.SKIP),
        )
        assertEquals(listOf("spaced", "dismiss", "skip"), press.map { it.candidateId })
        assertEquals("닫기", press.first().buttonLabel)
    }

    @Test fun promptToStringNeverContainsLabels() {
        val analysis = MockPopupFixtures.notice().analysis.copy(closeCandidates = persistentListOf(candidate("c", "닫기")))
        val prompt = ChoicePromptPlanner.plan(analysis, PopupClassification.NOTICE)
        assertTrue(!prompt.toString().contains("닫기") && prompt.options.none { it.toString().contains("닫기") })
    }

    @Test fun roleMustAgreeWithLabel() {
        // B says SKIP but the label means CLOSE (and the reverse): A's gate rejects this, so C must not offer it.
        val analysis = MockPopupFixtures.notice().analysis.copy(
            closeCandidates = persistentListOf(candidate("c", "닫기", ButtonRole.SKIP), candidate("s", "광고 건너뛰기", ButtonRole.CLOSE)),
        )
        assertTrue(ChoicePromptPlanner.plan(analysis, PopupClassification.NOTICE).cannotPressForUser)
    }

    @Test fun decisionPopupNeverGetsAPressButtonEvenForAllowedLabel() {
        assertTrue(pressIds(PopupClassification.USER_DECISION, candidate("c", "닫기")).isEmpty())
    }

    @Test fun narrowerPolicyCanBeInjected() {
        // E.g. while A's gate still has its own 8 labels, an integration test can pass that list.
        val gateOnly = PressableLabelPolicy { if (it == "닫기") ButtonRole.CLOSE else null }
        val analysis = MockPopupFixtures.notice().analysis.copy(
            closeCandidates = persistentListOf(candidate("ad", "광고 닫기"), candidate("c", "닫기")),
        )
        val prompt = ChoicePromptPlanner.plan(analysis, PopupClassification.ADVERTISEMENT, gateOnly)
        assertEquals(listOf("c"), prompt.options.filter { it.kind == PromptOptionKind.PRESS_CANDIDATE }.map { it.candidateId })
    }

    private fun pressOptions(classification: PopupClassification, vararg candidates: CloseCandidate) =
        ChoicePromptPlanner.plan(
            MockPopupFixtures.notice().analysis.copy(closeCandidates = persistentListOf(*candidates)),
            classification,
        ).options.filter { it.kind == PromptOptionKind.PRESS_CANDIDATE }

    private fun pressIds(classification: PopupClassification, vararg candidates: CloseCandidate) =
        pressOptions(classification, *candidates).map { it.candidateId }

    private fun candidate(
        id: String,
        label: String?,
        role: ButtonRole = ButtonRole.CLOSE,
        evidence: EvidenceLevel = EvidenceLevel.E2_LABELED_NODE,
    ) = CloseCandidate(id, "1", null, label, ScreenBounds(0, 0, 10, 10), evidence, role)

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(name)) { name }.use { it.readBytes().toString(Charsets.UTF_8) }
}
