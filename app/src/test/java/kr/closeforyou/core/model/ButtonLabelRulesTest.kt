package kr.closeforyou.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Shared label contract v1 against the team's vector file (draft v3, 176 rows) and the golden
 * normalization table. The same files run on Android in ButtonLabelRulesInstrumentedTest.
 * Failures name the vector id and code points only, never the label itself.
 */
class ButtonLabelRulesTest {
    private val vectors = LabelVectorFile.read(resource(LabelVectorFile.VECTORS))
    private val golden = LabelVectorFile.read(resource(LabelVectorFile.NORMALIZATION))

    @Test fun vectorFileIsWellFormed() {
        assertEquals(176, vectors.size)
        assertEquals("ids are unique", vectors.size, vectors.map { it["id"] }.toSet().size)
        for (row in vectors) {
            val label = LabelVectorFile.label(row["label_escaped"]!!)
            // Surrogate pairs (e.g. U+1F5D9) must come back as one code point.
            assertEquals(row["id"], row["codepoints"], LabelVectorFile.codePoints(label))
            assertTrue("${row["id"]} label column is paste-safe", row["label_escaped"]!!.all { it.code in 0x20..0x7E || it in '가'..'힣' })
        }
        val allowed = vectors.count { it["expected_role"] != "NONE" }
        assertEquals(38, allowed)
        assertEquals(138, vectors.size - allowed)
    }

    @Test fun everyVectorGetsItsExpectedRoleAndReason() {
        val wrong = vectors.filter { row ->
            val verdict = ButtonLabelRules.judge(LabelVectorFile.label(row["label_escaped"]!!))
            verdict.role?.name ?: "NONE" != row["expected_role"] || verdict.reason?.name.orEmpty() != row["reject_reason"]
        }
        assertTrue("wrong: " + wrong.joinToString { "${it["id"]} [${it["codepoints"]}]" }, wrong.isEmpty())
    }

    @Test fun noRejectedVectorIsAllowed() {
        val rejectCategories = vectors.filter { it["category"]!!.startsWith("R") || it["category"]!!.startsWith("Q2") || it["category"]!!.startsWith("P ") }
        assertEquals(138, rejectCategories.size)
        assertTrue(rejectCategories.all { ButtonLabelRules.roleOf(LabelVectorFile.label(it["label_escaped"]!!)) == null })
    }

    @Test fun normalizationMatchesTheGoldenTable() {
        assertEquals(20, golden.size)
        val wrong = golden.filter { row ->
            val input = LabelVectorFile.label(row["input_escaped"]!!)!!
            val verdict = ButtonLabelRules.judge(input)
            ButtonLabelRules.normalize(input) != LabelVectorFile.label(row["normalized_escaped"]!!) ||
                verdict.role?.name ?: "NONE" != row["role"] || verdict.reason?.name.orEmpty() != row["reject_reason"]
        }
        assertTrue("wrong: " + wrong.joinToString { it["id"]!! }, wrong.isEmpty())
    }

    @Test fun allowlistInvariants() {
        val allowlist = ButtonLabelRules.allowlist
        assertEquals(14, allowlist.size)
        assertEquals(9, allowlist.values.count { it == ButtonRole.CLOSE })
        for ((label, role) in allowlist) {
            assertEquals("already normalized", label, ButtonLabelRules.normalize(label))
            assertEquals("no risky word inside", role, ButtonLabelRules.roleOf(label))
        }
        // A's gate allows these today; the contract must keep allowing them.
        for (label in listOf("닫기", "창 닫기", "팝업 닫기", "close", "dismiss")) assertEquals(ButtonRole.CLOSE, ButtonLabelRules.roleOf(label))
        for (label in listOf("건너뛰기", "스킵", "skip")) assertEquals(ButtonRole.SKIP, ButtonLabelRules.roleOf(label))
        // Held (Q2): joined forms are not added by deleting spaces.
        for (label in listOf("창닫기", "팝업닫기", "광고닫기", "공지닫기", "닫기버튼", "광고건너뛰기")) {
            assertEquals(LabelRejectReason.NOT_IN_LIST, ButtonLabelRules.judge(label).reason)
        }
    }

    @Test fun normalizationIsIdempotentAndEveryVerdictHasExactlyOneSide() {
        for (row in vectors + golden) {
            val label = LabelVectorFile.label(row["label_escaped"] ?: row["input_escaped"]!!) ?: continue
            val once = ButtonLabelRules.normalize(label)
            assertEquals(row["id"], once, ButtonLabelRules.normalize(once))
            val verdict = ButtonLabelRules.judge(label)
            assertTrue(row["id"], (verdict.role == null) != (verdict.reason == null))
        }
    }

    @Test fun riskyGradesAreReportedMostSeriousFirst() {
        assertEquals(LabelRejectReason.DECISION_WORD, ButtonLabelRules.judge("동의하고 오늘 하루 닫기").reason)
        assertEquals(LabelRejectReason.CONDITIONAL_WORD, ButtonLabelRules.judge("오늘 하루 설치 안내 닫기").reason)
        assertEquals(LabelRejectReason.NAVIGATION_WORD, ButtonLabelRules.judge("Install and OK").reason)
        // English words are whole tokens: "playlist" is not "pay", "reopen" is not "open".
        assertEquals(LabelRejectReason.NOT_IN_LIST, ButtonLabelRules.judge("playlist").reason)
        assertEquals(LabelRejectReason.NOT_IN_LIST, ButtonLabelRules.judge("reopen").reason)
        assertEquals(LabelRejectReason.DECISION_WORD, ButtonLabelRules.judge("Log in").reason)
    }

    @Test fun nodeTextAndDescriptionMustAgree() {
        fun node(text: String?, description: String?) = ButtonLabelRules.judgeNode(text, description)
        assertEquals(ButtonRole.CLOSE, node("닫기", "닫기 버튼").role)
        assertEquals(ButtonRole.CLOSE, node(" 닫기 ", null).role)
        assertEquals(ButtonRole.SKIP, node(null, "Skip Ad").role)
        assertEquals(LabelRejectReason.LABEL_CONFLICT, node("X", "닫기").reason)
        assertEquals(LabelRejectReason.LABEL_CONFLICT, node("건너뛰기", "닫기").reason)
        assertEquals(LabelRejectReason.LABEL_CONFLICT, node("닫기", "[닫기]").reason)
        assertEquals(LabelRejectReason.NAVIGATION_WORD, node("닫기", "앱 설치").reason)
        assertEquals(LabelRejectReason.DECISION_WORD, node("동의", "닫기").reason)
        assertEquals(LabelRejectReason.DECISION_WORD, node("다시 보지 않기", "구독").reason)
        assertEquals(LabelRejectReason.AMBIGUOUS_WORD, node("확인", null).reason)
        assertEquals(LabelRejectReason.NO_LABEL, node(null, null).reason)
        assertEquals(LabelRejectReason.NO_LABEL, node("", "  \n").reason)
        // A blank side is ignored, not a conflict.
        assertEquals(ButtonRole.CLOSE, node("닫기", "　").role)
        // Both sides rejected for the same kind of reason: that reason, not a conflict.
        assertEquals(LabelRejectReason.SYMBOL_ONLY, node("X", "×").reason)
    }

    @Test fun verdictAcceptsOnlyCloseOrSkipAndNeverBoth() {
        assertNotNull(runCatching { LabelVerdict(ButtonRole.CLOSE, null) }.getOrNull())
        assertNull(runCatching { LabelVerdict(ButtonRole.CLOSE, LabelRejectReason.NOT_IN_LIST) }.getOrNull())
        assertNull(runCatching { LabelVerdict(null, null) }.getOrNull())
        assertNull(runCatching { LabelVerdict(ButtonRole.USER_DECISION, null) }.getOrNull())
        assertEquals("verdicts carry no screen text", "LabelVerdict(role=null, reason=DECISION_WORD)", ButtonLabelRules.judge("결제하기").toString())
    }

    @Test fun androidTestCopiesAreIdenticalToTheseFiles() {
        for (name in listOf(LabelVectorFile.VECTORS, LabelVectorFile.NORMALIZATION)) {
            val copy = listOf("src/androidTest/resources", "app/src/androidTest/resources").map { File(it, name) }.firstOrNull { it.isFile }
            assertNotNull("missing androidTest copy of $name", copy)
            assertEquals(name, resource(name), copy!!.readText(Charsets.UTF_8))
        }
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(name)) { name }.use { it.readBytes().toString(Charsets.UTF_8) }
}

/** Reads the team's quoted CSV files. Kept in step with the copy in the androidTest source set. */
internal object LabelVectorFile {
    const val VECTORS = "kr/closeforyou/core/model/button_label_vectors.csv"
    const val NORMALIZATION = "kr/closeforyou/core/model/button_label_normalization.csv"

    fun read(text: String): List<Map<String, String>> {
        val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
        val header = lines.first().split(',')
        return lines.drop(1).map { line -> header.zip(fields(line)).toMap().also { require(it.size == header.size) { "bad row" } } }
    }

    private fun fields(line: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < line.length) {
            require(line[i] == '"') { "unquoted field" }
            val value = StringBuilder()
            i++
            while (true) {
                if (line[i] == '"' && i + 1 < line.length && line[i + 1] == '"') { value.append('"'); i += 2; continue }
                if (line[i] == '"') { i++; break }
                value.append(line[i++])
            }
            out += value.toString()
            if (i < line.length) { require(line[i] == ','); i++ }
        }
        return out
    }

    /** `(null)` is a missing label; `\\`, `\n`, `\t` and `\uXXXX` (UTF-16 units) are escapes. */
    fun label(escaped: String): String? {
        if (escaped == "(null)") return null
        val out = StringBuilder()
        var i = 0
        while (i < escaped.length) {
            val c = escaped[i]
            if (c != '\\') { out.append(c); i++; continue }
            when (escaped[i + 1]) {
                '\\' -> { out.append('\\'); i += 2 }
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'u' -> { out.append(escaped.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                else -> error("bad escape")
            }
        }
        return out.toString()
    }

    fun codePoints(label: String?): String =
        label?.codePoints()?.toArray()?.joinToString(" ") { "U+" + it.toString(16).uppercase().padStart(4, '0') }.orEmpty()
}
