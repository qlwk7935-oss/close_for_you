package kr.closeforyou.ui.voice.choice

import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PressableLabelPolicyTest {
    @Test fun aGateCurrentEightLabelsAreAccepted() {
        listOf("닫기", "창 닫기", "팝업 닫기", "close", "dismiss").forEach {
            assertEquals(it, ButtonRole.CLOSE, AGateLabelPolicy.roleOf(it))
        }
        listOf("건너뛰기", "스킵", "skip").forEach {
            assertEquals(it, ButtonRole.SKIP, AGateLabelPolicy.roleOf(it))
        }
    }

    @Test fun caseOuterSpaceAndFullWidthFollowA() {
        listOf("Close", "CLOSE", "ＣＬＯＳＥ", " 닫기 ", "\t닫기", "닫기\n", "　닫기", " 닫기").forEach {
            assertEquals(it, ButtonRole.CLOSE, AGateLabelPolicy.roleOf(it))
        }
    }

    @Test fun xAndUnlistedLabelsAreRejected() {
        listOf(null, "", "  ", "X", "x", "×", "✕", "[X]", "확인", "OK", "오늘 하루 닫기", "동의하고 닫기",
            "[닫기]", "닫기.", "닫 기", "닫​기", "광고 닫기", "창  닫기").forEach {
            assertNull(it.toString(), AGateLabelPolicy.roleOf(it))
        }
    }

    /** B draft v1 vectors (175): the temporary policy must equal A's current gate (`a_current`). */
    @Test fun allSharedVectorsMatchAGateCurrentColumn() {
        val rows = loadVectors()
        assertEquals(175, rows.size)
        val mismatches = rows.filter { AGateLabelPolicy.roleOf(it.label) != it.aCurrent }
        assertTrue("Mismatched categories: ${mismatches.map { it.category }}", mismatches.isEmpty())
        // Rejection categories (R1~R9) must never become pressable.
        rows.filter { it.category.startsWith("R") }.forEach {
            assertNull(it.category, AGateLabelPolicy.roleOf(it.label))
        }
    }

    @Test fun plannerHidesPressButtonWhenGateWouldRejectLabel() {
        val analysis = MockPopupFixtures.notice().analysis.copy(
            closeCandidates = persistentListOf(
                candidate("x", "X"),
                candidate("times", "×"),
                candidate("ok", "확인"),
                candidate("ad", "광고 닫기"),
                candidate("dismiss", null, evidence = EvidenceLevel.E1_DISMISS),
            ),
        )
        val prompt = ChoicePromptPlanner.plan(analysis, PopupClassification.ADVERTISEMENT)
        assertTrue(prompt.options.none { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        assertTrue(prompt.cannotPressForUser)
    }

    @Test fun labelIsShownTrimmedButCheckedLikeA() {
        val analysis = MockPopupFixtures.notice().analysis.copy(
            closeCandidates = persistentListOf(
                candidate("spaced", "  닫기  "),
                candidate("dismiss", "Dismiss", evidence = EvidenceLevel.E1_DISMISS),
                candidate("skip", "SKIP", role = ButtonRole.SKIP),
            ),
        )
        val press = ChoicePromptPlanner.plan(analysis, PopupClassification.NOTICE).options
            .filter { it.kind == PromptOptionKind.PRESS_CANDIDATE }
        assertEquals(listOf("spaced", "dismiss", "skip"), press.map { it.candidateId })
        assertEquals("닫기", press.first().buttonLabel)
    }

    @Test fun promptToStringNeverContainsLabels() {
        val analysis = MockPopupFixtures.notice().analysis.copy(closeCandidates = persistentListOf(candidate("c", "닫기")))
        val prompt = ChoicePromptPlanner.plan(analysis, PopupClassification.NOTICE)
        assertTrue(!prompt.toString().contains("닫기") && prompt.options.none { it.toString().contains("닫기") })
    }

    @Test fun roleMustAgreeWithLabel() {
        // B says SKIP but the label means CLOSE: A's gate rejects this, so C must not offer it.
        val analysis = MockPopupFixtures.notice().analysis.copy(
            closeCandidates = persistentListOf(candidate("c", "닫기", ButtonRole.SKIP)),
        )
        assertTrue(ChoicePromptPlanner.plan(analysis, PopupClassification.NOTICE).cannotPressForUser)
    }

    @Test fun injectedPolicyCanWidenLikeFutureContract() {
        val analysis = MockPopupFixtures.notice().analysis.copy(
            closeCandidates = persistentListOf(candidate("ad", "광고 닫기")),
        )
        val future = PressableLabelPolicy { if (it == "광고 닫기") ButtonRole.CLOSE else null }
        val prompt = ChoicePromptPlanner.plan(analysis, PopupClassification.ADVERTISEMENT, future)
        assertEquals(listOf("ad"), prompt.options.mapNotNull { it.candidateId })
    }

    private fun candidate(
        id: String,
        label: String?,
        role: ButtonRole = ButtonRole.CLOSE,
        evidence: EvidenceLevel = EvidenceLevel.E2_LABELED_NODE,
    ) = CloseCandidate(id, "1", null, label, ScreenBounds(0, 0, 10, 10), evidence, role)

    private data class Vector(val category: String, val label: String?, val aCurrent: ButtonRole?) {
        override fun toString(): String = "Vector($category)"
    }

    private fun loadVectors(): List<Vector> {
        val stream = requireNotNull(javaClass.getResourceAsStream("label_test_vectors.csv"))
        val lines = stream.bufferedReader(Charsets.UTF_8).readLines().map { it.removePrefix("﻿") }
        val header = parseCsvLine(lines.first())
        val labelAt = header.indexOf("label_escaped")
        val aAt = header.indexOf("a_current")
        return lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val f = parseCsvLine(line)
            val raw = f[labelAt]
            Vector(
                category = f[0],
                label = if (raw == "(null)") null else raw.replace("\\n", "\n").replace("\\t", "\t"),
                aCurrent = if (f[aAt] == "NONE") null else ButtonRole.valueOf(f[aAt]),
            )
        }
    }

    /** Minimal RFC 4180 line parser: quoted fields, doubled quotes. No field spans lines here. */
    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }
}
