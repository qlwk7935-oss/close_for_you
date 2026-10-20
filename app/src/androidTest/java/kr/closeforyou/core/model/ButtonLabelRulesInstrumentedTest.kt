package kr.closeforyou.core.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The JVM contract test on Android's own NFKC, lowercase and whitespace implementations (ICU),
 * with the same vector and golden files (the JVM test checks both copies are identical).
 * Failures name the vector id only.
 */
@RunWith(AndroidJUnit4::class)
class ButtonLabelRulesInstrumentedTest {
    @Test fun everyVectorGetsTheSameVerdictOnAndroid() {
        val rows = read("kr/closeforyou/core/model/button_label_vectors.csv")
        assertEquals(176, rows.size)
        val wrong = rows.filter { row ->
            val verdict = ButtonLabelRules.judge(label(row.getValue("label_escaped")))
            (verdict.role?.name ?: "NONE") != row["expected_role"] || verdict.reason?.name.orEmpty() != row["reject_reason"]
        }
        assertTrue("wrong: " + wrong.joinToString { it.getValue("id") }, wrong.isEmpty())
    }

    @Test fun normalizationMatchesTheJvmGoldenTable() {
        val rows = read("kr/closeforyou/core/model/button_label_normalization.csv")
        assertEquals(20, rows.size)
        val wrong = rows.filter { row ->
            val input = label(row.getValue("input_escaped"))!!
            ButtonLabelRules.normalize(input) != label(row.getValue("normalized_escaped")) ||
                (ButtonLabelRules.judge(input).role?.name ?: "NONE") != row["role"]
        }
        assertTrue("wrong: " + wrong.joinToString { it.getValue("id") }, wrong.isEmpty())
    }

    private fun read(name: String): List<Map<String, String>> {
        val text = requireNotNull(javaClass.classLoader!!.getResourceAsStream(name)) { name }.use { it.readBytes().toString(Charsets.UTF_8) }
        val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
        val header = lines.first().split(',')
        return lines.drop(1).map { line -> header.zip(fields(line)).toMap() }
    }

    // Same quoted-CSV reading and escapes as LabelVectorFile in the JVM test.
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

    private fun label(escaped: String): String? {
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
}
