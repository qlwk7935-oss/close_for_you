package kr.closeforyou.simpleclose

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.runBlocking
import kr.closeforyou.core.model.NodeAction
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.ui.voice.choice.RememberChoiceRules
import kr.closeforyou.ui.voice.settings.HandlingPreset
import kr.closeforyou.ui.voice.settings.autoCloseKinds
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * B's offline replay (local verification only): runs every `uiautomator dump` XML in the folder named by
 * the environment variable `CFY_DUMPS` through the demo engine (video skip scan → popup root → B's
 * classifier → 강력 모드 decision) and prints one line per screen. Skipped when `CFY_DUMPS` is not set, so
 * CI never reads screen dumps. The dumps stay outside the repository.
 */
class RealDumpReplayTest {
    private val closeAll = HandlingPreset.CLOSE_ALL.autoCloseKinds()
    private val adsOnly = HandlingPreset.CLOSE_ADS_ONLY.autoCloseKinds()

    @Test fun replay() {
        val dir = System.getenv("CFY_DUMPS")?.let(::File)
        assumeTrue("CFY_DUMPS not set", dir != null && dir.isDirectory)
        val classifier = DecisionPopupClassifier()
        for (file in dir!!.listFiles { f -> f.name.endsWith(".xml") }!!.sortedBy { it.name }) {
            val nodes = parse(file.readText())
            if (nodes.isEmpty()) { println("REPLAY ${file.nameWithoutExtension}: empty"); continue }
            val screen = nodes.first().bounds
            BrowserSensitivity.check(nodes, screen)?.let { println("REPLAY ${file.nameWithoutExtension}: SENSITIVE $it (left alone)") }
            val video = VideoAdSkip.scan(nodes, screen)
            val root = PopupRootFinder.find(nodes, screen)
            if (root == null) {
                println("REPLAY ${file.nameWithoutExtension}: nodes=${nodes.size} root=none video=${video.diag}")
                continue
            }
            val popup = SimplePopupAnalysis.build(nodes, root, "com.android.chrome", 1, screen, 1, 0)
            val byB = runBlocking { classifier.classify(popup.analysis) }
            val scam = RememberChoiceRules.suspiciousByB(popup.analysis)
            val hinted = ImageDialogAdHint.applies(popup, byB, scam)
            val kind = if (hinted) PopupClassification.ADVERTISEMENT else byB
            val scan = popup.scan
            val strong = SimpleCloseDecider.decide(kind, scam, scan.candidates, closeAll, scan.forbiddenCount, riskCount = scan.riskCount)
            val ads = SimpleCloseDecider.decide(kind, scam, scan.candidates, adsOnly, scan.forbiddenCount, riskCount = scan.riskCount, strongEnabled = false)
            println("REPLAY ${file.nameWithoutExtension}: nodes=${nodes.size} root=${root.kind} kind=$byB${if (hinted) "->AD(hint)" else ""} scam=$scam " +
                "risk=${scan.riskButtons.map { it.label }} cands=${scan.candidates.map { it.label }} x=${scan.xButton != null} video=${video.diag}\n" +
                "    closeAll=${describe(strong)} adsOnly=${describe(ads)}\n" +
                "    texts=${popup.analysis.texts.map { it.text.take(40) }.take(12)}")
        }
    }

    private fun describe(d: SimpleDecision) = when (d) {
        is SimpleDecision.AutoClose -> "AUTO(${d.candidate.label})"
        else -> d::class.simpleName
    }

    /** uiautomator XML → walk-order DTOs with "0/i/j" paths. */
    private fun parse(xml: String): List<NodeSnapshot> {
        val out = mutableListOf<NodeSnapshot>()
        val stack = ArrayDeque<Int>()
        val counts = ArrayDeque<Int>().apply { addLast(0) }
        val tag = Regex("""<node ([^>]*?)(/?)>|</node>""")
        fun attr(s: String, k: String) = Regex("""(?:^|\s)$k="([^"]*)"""").find(s)?.groupValues?.get(1)?.let(::unescape)
        for (m in tag.findAll(xml)) {
            if (m.value == "</node>") { stack.removeLast(); counts.removeLast(); continue }
            val index = counts.removeLast(); counts.addLast(index + 1); stack.addLast(index)
            val a = m.groupValues[1]
            val path = stack.joinToString("/")
            val b = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""").find(attr(a, "bounds").orEmpty())?.groupValues?.drop(1)?.map(String::toInt)
            val bounds = if (b == null || b[2] < b[0] || b[3] < b[1]) ScreenBounds(0, 0, 0, 0) else ScreenBounds(b[0], b[1], b[2], b[3])
            val clickable = attr(a, "clickable") == "true"
            out += NodeSnapshot(path, attr(a, "resource-id")?.ifBlank { null }, attr(a, "class"), attr(a, "text")?.ifBlank { null },
                attr(a, "content-desc")?.ifBlank { null }, clickable, attr(a, "enabled") != "false", bounds,
                if (clickable) persistentSetOf(NodeAction.CLICK) else persistentSetOf())
            if (m.groupValues[2] == "/") stack.removeLast() else counts.addLast(0)
        }
        return out
    }

    private fun unescape(s: String) = s.replace("&#10;", "\n").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'").replace("&amp;", "&")
}
