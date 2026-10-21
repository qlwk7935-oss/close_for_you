package kr.closeforyou.decision

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static guard for B's production sources (threat model T15, AGENTS 6-9, TEAM_INTEGRATION B-4):
 * B code must not log, print, persist or send anything, hold Android framework objects, or act on
 * the screen itself. Comments are ignored; string literals are skipped only while looking for
 * comments, so text such as "//" inside a string cannot hide code after it.
 */
class DecisionPrivacyGuardTest {
    @Test fun decisionSourcesHaveNoLoggingStorageNetworkAndroidOrActionCalls() {
        val files = decisionSourceDir().listFiles { file -> file.extension == "kt" }.orEmpty().sortedBy { it.name }
        // Guard against a vacuous pass if the directory moves or the glob stops matching.
        assertTrue("expected B sources, found ${files.map { it.name }}", files.size >= 4)
        val found = files.flatMap { file -> PrivacyGuard.violations(file.readText()).map { "${file.name}:$it" } }
        assertTrue("forbidden in B sources:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test fun guardCatchesEachForbiddenKind() {
        val samples = mapOf(
            "import android.util.Log" to "android",
            "import androidx.core.content.ContextCompat" to "android",
            "Log.d(\"tag\", label)" to "logging",
            "println(text)" to "logging",
            "System.err.write(1)" to "logging",
            "error.printStackTrace()" to "logging",
            "val f = File(path)" to "storage",
            "import java.nio.file.Files" to "storage",
            "prefs: SharedPreferences" to "storage",
            "import java.net.URL" to "network",
            "val c = HttpURLConnection()" to "network",
            "node.performAction(16)" to "action",
            "service.dispatchGesture(g, null, null)" to "action",
            "val n: AccessibilityNodeInfo? = null" to "android-object",
            "val b: Bitmap? = null" to "android-object",
        )
        for ((code, kind) in samples) {
            val found = PrivacyGuard.violations(code)
            assertTrue("not caught: $code -> $found", found.any { it.contains("[$kind]") })
        }
    }

    @Test fun commentsAreIgnoredButStringsDoNotHideCode() {
        assertEquals(emptyList<String>(), PrivacyGuard.violations("// Log.d(\"tag\", x)\nval a = 1"))
        assertEquals(emptyList<String>(), PrivacyGuard.violations("/* println(x)\n performAction(1) */ val a = 1"))
        assertEquals(emptyList<String>(), PrivacyGuard.violations("/** KDoc mentions Bitmap. */ class A"))
        // "//" inside a string is not a comment start, so the call after it is still found.
        assertTrue(PrivacyGuard.violations("val s = \"a // b\"; println(s)").any { it.contains("[logging]") })
        assertTrue(PrivacyGuard.violations("val s = \"\"\"x // y\"\"\"\nLog.i(\"t\", s)").any { it.contains("[logging]") })
        // Names that only contain a forbidden word are not calls to it.
        assertEquals(emptyList<String>(), PrivacyGuard.violations("class DecisionContext; val catalog = 1; val profile = 2"))
    }

    private fun decisionSourceDir(): File = listOf(
        File("src/main/java/kr/closeforyou/decision"), // Gradle runs unit tests from the module directory.
        File("app/src/main/java/kr/closeforyou/decision"), // IDE runs from the project root.
    ).firstOrNull { it.isDirectory } ?: error("decision sources not found from ${File(".").absolutePath}")
}

/** Line-numbered findings like "12 [logging] println(". */
internal object PrivacyGuard {
    private val rules = listOf(
        "android" to Regex("""^\s*import\s+androidx?\.""", RegexOption.MULTILINE),
        "logging" to Regex("""\bLog\.[a-z]+\s*\(|\bTimber\b|\bprintln\s*\(|\bprint\s*\(|\bSystem\.(out|err)\b|\bprintStackTrace\s*\("""),
        "storage" to Regex("""\bjava\.io\.|\bjava\.nio\.|\bFile\s*\(|\bFileOutputStream\b|\bFileWriter\b|\bSharedPreferences\b|\bandroidx\.room\b|\bSQLite\w*"""),
        "network" to Regex("""\bjava\.net\.|\bokhttp3?\b|\bHttpURLConnection\b|\bURL\s*\(|\bRetrofit\b|\bSocket\s*\("""),
        "action" to Regex("""\bperformAction\b|\bdispatchGesture\b|\bperformGlobalAction\b|\bGestureDescription\b"""),
        "android-object" to Regex("""\bAccessibility(NodeInfo|Event|Service|WindowInfo)\b|\bBitmap\b|\bHardwareBuffer\b"""),
    )

    fun violations(source: String): List<String> {
        val code = stripComments(source)
        return rules.flatMap { (kind, regex) ->
            regex.findAll(code).map { match ->
                val line = code.substring(0, match.range.first).count { it == '\n' } + 1
                "$line [$kind] ${match.value.trim()}"
            }
        }
    }

    /** Replaces comments with spaces (keeping line breaks) and leaves string literals intact. */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 }
                    out.append(source, i, end); i = end
                }
                source[i] == '"' -> {
                    var j = i + 1
                    while (j < source.length && source[j] != '"' && source[j] != '\n') j += if (source[j] == '\\') 2 else 1
                    val end = minOf(j + 1, source.length)
                    out.append(source, i, end); i = end
                }
                source.startsWith("//", i) -> {
                    val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                    repeat(end - i) { out.append(' ') }; i = end
                }
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                    for (k in i until end) out.append(if (source[k] == '\n') '\n' else ' ')
                    i = end
                }
                else -> { out.append(source[i]); i++ }
            }
        }
        return out.toString()
    }
}
