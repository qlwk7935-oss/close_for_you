package kr.closeforyou.decision

import kr.closeforyou.accessibility.PrivacyPolicy
import kr.closeforyou.accessibility.capture.DecisionEvidence
import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Auto-close on every screen of D's popup test app dumped from the API 37 emulator (P01~P18 and the
 * web page W01~W07; P15 is a toast with no tree). Each screen goes through A's real F0 text and
 * field checks and A's decision summary (as AccessibilityRuntime does) before B sees it, then B's
 * popup scope, candidates and [DecisionAutoClose] under presets ①②③. Emulator, not a real device.
 *
 * Assumed because a dump does not carry them: A's signal (Dialog window → NEW_WINDOW, activity- or
 * web-drawn → SAME_WINDOW_OVERLAY, page change → FULL_SCREEN_TRANSITION), CLICK from `clickable`,
 * editable = EditText, no OCR (A's capture is not simulated), no other window of the app, and that A
 * issues a complete AutoEligibility (contract v2) for every screen it lets through, so these rows show
 * B's own conditions.
 */
class DecisionAutoCloseScenarioTest {
    /** D's answer key (ScenarioCatalog; web rows follow c-test-checklist 2-5). Targets are view ids, or "label:" for web buttons. */
    private data class Screen(
        val name: String,
        val file: String,
        val signal: PopupSignal,
        val allowed: String?,
        val forbidden: Set<String> = emptySet(),
        val neverPress: Boolean = false,
    )

    private val screens = listOf(
        Screen("P01", "p01_dialog_ad.xml", PopupSignal.NEW_WINDOW, "btn_close", setOf("btn_ad_open")),
        Screen("P02", "p02_same_window_overlay.xml", PopupSignal.SAME_WINDOW_OVERLAY, "btn_close", setOf("btn_ad_open")),
        Screen("P03", "p03_bottom_banner.xml", PopupSignal.SAME_WINDOW_OVERLAY, "btn_close", setOf("btn_ad_open")),
        Screen("P04", "p04_unlabeled_x.xml", PopupSignal.NEW_WINDOW, null, setOf("btn_ad_open", "btn_unlabeled_x")),
        Screen("P05", "p05_visual_only_x.xml", PopupSignal.NEW_WINDOW, null),
        Screen("P06", "p06_fake_x.xml", PopupSignal.NEW_WINDOW, "btn_close", setOf("btn_fake_x")),
        Screen("P07", "p07_image_notice.xml", PopupSignal.NEW_WINDOW, "btn_close"),
        Screen("P08", "p08_fake_payment.xml", PopupSignal.NEW_WINDOW, null, setOf("btn_pay", "btn_cancel"), neverPress = true),
        Screen("P09", "p09_subscription_trap.xml", PopupSignal.NEW_WINDOW, null, setOf("btn_cancel_sub", "btn_later"), neverPress = true),
        Screen("P10", "p10_fake_consent.xml", PopupSignal.NEW_WINDOW, null, setOf("btn_allow", "btn_deny"), neverPress = true),
        Screen("P11", "p11_fake_login.xml", PopupSignal.NEW_WINDOW, null, setOf("btn_login"), neverPress = true),
        Screen("P12-1", "p12_step1_ad.xml", PopupSignal.NEW_WINDOW, "btn_close"),
        Screen("P12-2", "p12_step2_notice.xml", PopupSignal.NEW_WINDOW, "btn_close"),
        Screen("P12-3", "p12_step3_consent.xml", PopupSignal.NEW_WINDOW, null, setOf("btn_allow", "btn_deny"), neverPress = true),
        Screen("P13", "p13_webview_first_query.xml", PopupSignal.SAME_WINDOW_OVERLAY, null),
        Screen("W01", "p13_w01_web_modal.xml", PopupSignal.SAME_WINDOW_OVERLAY, "label:닫기", setOf("label:자세히 보기")),
        Screen("W02", "p13_w02_web_unlabeled_x.xml", PopupSignal.SAME_WINDOW_OVERLAY, null, setOf("label:자세히 보기")),
        Screen("W03", "p13_w03_web_fake_x.xml", PopupSignal.SAME_WINDOW_OVERLAY, "label:닫기"),
        Screen("W04", "p13_w04_web_image_x.xml", PopupSignal.SAME_WINDOW_OVERLAY, null),
        Screen("W05", "p13_w05_web_notice.xml", PopupSignal.SAME_WINDOW_OVERLAY, "label:닫기"),
        Screen("W06", "p13_w06_web_payment.xml", PopupSignal.SAME_WINDOW_OVERLAY, null, setOf("label:결제하기", "label:취소"), neverPress = true),
        Screen("W07", "p13_w07_web_consent.xml", PopupSignal.SAME_WINDOW_OVERLAY, null, setOf("label:동의", "label:거부"), neverPress = true),
        Screen("P14-1", "p14_page1.xml", PopupSignal.FULL_SCREEN_TRANSITION, null, setOf("btn_next"), neverPress = true),
        Screen("P14-2", "p14_page2.xml", PopupSignal.FULL_SCREEN_TRANSITION, null, neverPress = true),
        Screen("P16-1", "p16_countdown.xml", PopupSignal.SAME_WINDOW_OVERLAY, null, setOf("btn_ad_open")),
        Screen("P16-2", "p16_skip_shown.xml", PopupSignal.SAME_WINDOW_OVERLAY, "btn_skip", setOf("btn_ad_open")),
        Screen("P17", "p17_skip_and_close.xml", PopupSignal.NEW_WINDOW, "btn_close"),
        Screen("P18", "p18_time_sale.xml", PopupSignal.NEW_WINDOW, "btn_close", setOf("btn_ad_open")),
    )

    private val presets = listOf(
        "①" to DecisionAutoClose.PRESET_ALL, "②" to DecisionAutoClose.PRESET_ADS_AND_COUPONS, "③" to DecisionAutoClose.PRESET_ADS_ONLY,
    )

    /** One line per screen: "name: what A did | kind | ① ② ③ outcomes". */
    private fun run(screen: Screen, throughA: Boolean, keep: (DumpNode) -> Boolean = { true }): String {
        val nodes = parse(screen.file).filter(keep)
        val token = MockPopupFixtures.notice().snapshot.token.copy(popupInstanceId = screen.name, structureDigest = screen.name)
        val full = PopupSnapshot(token, 0, nodes.map { it.node }.toPersistentList(), persistentListOf(), ScreenBounds(0, 0, 1080, 2400),
            PopupTrigger.WINDOW_CHANGED, SnapshotCompleteness.COMPLETE)
        val (stage, analysis, snapshot) = if (throughA) aPipeline(full, nodes) ?: return "${screen.name}: A blocked" else
            Triple("raw", PopupAnalysis(token, texts = treeTexts(full).toPersistentList(), limitations = LIMITS), full)
        val detection = PopupDetection(token, persistentSetOf(screen.signal))
        // As B's wiring would: candidates from the finder, then the scoping entry point.
        val withCandidates = analysis.copy(closeCandidates = DecisionCloseCandidateFinder().find(snapshot, detection))
        val scoped = withCandidates.copy(texts = DecisionPopupScope.texts(snapshot, detection, analysis.texts).toPersistentList())
        val outcomes = presets.map { (name, preset) ->
            val eligible = withCandidates.copy(autoEligibility = AutoEligibility(token, persistentSetOf(*EligibilityCheck.entries.toTypedArray()), 0))
            val verdict = DecisionAutoClose(nowMillis = { 0 }).evaluate(snapshot, detection, eligible, DecisionContext(token, null, null, false, true), preset)
            val pressed = verdict.candidateId?.let { id -> targetName(nodes.single { "node:${it.node.path}" == id }.node) }
            assertSafe(screen, name, pressed)
            "$name " + (pressed ?: verdict.blocks.joinToString("+"))
        }
        return "${screen.name}: $stage | ${DecisionKinds.of(scoped)} | ${outcomes.joinToString(" ")}"
    }

    private fun assertSafe(screen: Screen, preset: String, pressed: String?) {
        if (pressed == null) return
        val where = "${screen.name} $preset pressed $pressed"
        assertTrue("$where on a screen that must never be pressed", !screen.neverPress)
        assertTrue("$where, a forbidden button", pressed !in screen.forbidden)
        assertEquals("$where, not D's allowed button", screen.allowed, pressed)
    }

    /** Through A today: the real records the app produces for B. */
    @Test fun everyScreenThroughATodayIsSafeAndMatchesTheRecord() {
        val lines = screens.map { run(it, throughA = true) }
        assertEquals(RECORDED_THROUGH_A.trimIndent(), lines.joinToString("\n"))
    }

    /**
     * B alone on the raw tree (A's summary bypassed): what B's scoping would do for web popups if A
     * kept popup-scoped texts instead of summarizing the whole page (contract item, AUTO_CLOSE.md 7절).
     */
    @Test fun everyScreenOnTheRawTreeIsSafeAndMatchesTheRecord() {
        val lines = screens.map { run(it, throughA = false) }
        assertEquals(RECORDED_RAW.trimIndent(), lines.joinToString("\n"))
    }

    /**
     * D's web page lists every scenario in a menu ("W02 이름 없는 X", "W06 가짜 결제 확인"), so A blocks
     * ("이름") or summarizes ("결제") every web screen above. With only that menu removed, the same
     * dumps show B's popup scope on a real WebView tree: the page title "P13 WebView 모달 광고" outside
     * the modal no longer counts as the popup's ad wording.
     */
    @Test fun webModalsWithoutTheScenarioMenu() {
        val menu = Regex("^W0[1-7] ")
        val keep = { n: DumpNode -> listOfNotNull(n.node.text, n.node.contentDescription).none { menu.containsMatchIn(it) } }
        val lines = screens.filter { it.name.startsWith("W") }.map { run(it, throughA = true, keep = keep) }
        assertEquals(RECORDED_WEB_WITHOUT_MENU.trimIndent(), lines.joinToString("\n"))
    }

    // ---- A's pipeline, using A's own code (CaptureOcrPipeline.analyze without capture) ----

    private fun aPipeline(snapshot: PopupSnapshot, nodes: List<DumpNode>): Triple<String, PopupAnalysis, PopupSnapshot>? {
        // AndroidSnapshotCollector.inspect: fields, then every string of every node.
        if (nodes.any { PrivacyPolicy.fieldBlock(it.password, it.editable, 0) != null }) return null
        val strings = nodes.flatMap { listOf(it.node.text, it.node.contentDescription, it.node.viewId, it.node.className) }
        if (strings.any { PrivacyPolicy.hasSensitiveText(it) || PrivacyPolicy.hasUncertainText(it) }) return null
        val tree = treeTexts(snapshot)
        val content = tree.joinToString("\n") { it.text }
        if (PrivacyPolicy.hasSensitiveText(content) || PrivacyPolicy.hasUncertainText(content)) return null
        DecisionEvidence.summarize(snapshot, tree)?.let { return Triple("A summary", it.analysis, it.snapshot) }
        return Triple("tree", PopupAnalysis(snapshot.token, texts = tree.toPersistentList(), limitations = LIMITS), snapshot)
    }

    private fun treeTexts(snapshot: PopupSnapshot): List<ExtractedText> = snapshot.nodes.flatMap { node ->
        listOfNotNull(node.text, node.contentDescription).filter { it.isNotBlank() }
            .map { ExtractedText(it, node.bounds, TextSource.ACCESSIBILITY_TREE, null) }
    }.distinctBy { it.text to it.bounds }

    private fun targetName(node: NodeSnapshot): String =
        node.viewId?.substringAfter(":id/", "")?.takeIf { it.isNotEmpty() } ?: "label:${node.text ?: node.contentDescription}"

    // ---- uiautomator XML → A's node DTOs ----

    private class DumpNode(val node: NodeSnapshot, val password: Boolean, val editable: Boolean)

    private fun parse(file: String): List<DumpNode> {
        val stream = requireNotNull(javaClass.classLoader!!.getResourceAsStream("kr/closeforyou/decision/emulator/$file")) { file }
        val document = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val out = mutableListOf<DumpNode>()
        fun visit(element: Element, path: String) {
            out += node(element, path)
            element.childElements().forEachIndexed { index, child -> visit(child, "$path/$index") }
        }
        document.documentElement.childElements().forEachIndexed { index, root -> visit(root, "$index") }
        return out
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == "node" }

    private fun node(e: Element, path: String): DumpNode {
        val (l, t, r, b) = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").find(e.getAttribute("bounds"))!!.destructured
        val clickable = e.getAttribute("clickable") == "true"
        val className = e.getAttribute("class").ifEmpty { null }
        return DumpNode(
            NodeSnapshot(
                path = path,
                viewId = e.getAttribute("resource-id").ifEmpty { null },
                className = className,
                text = e.getAttribute("text").ifEmpty { null },
                contentDescription = e.getAttribute("content-desc").ifEmpty { null },
                clickable = clickable,
                enabled = e.getAttribute("enabled") == "true",
                bounds = ScreenBounds(l.toInt(), t.toInt(), r.toInt(), b.toInt()),
                actions = if (clickable) persistentSetOf(NodeAction.CLICK) else persistentSetOf(),
            ),
            password = e.getAttribute("password") == "true",
            editable = className?.contains("EditText") == true,
        )
    }

    private companion object {
        val LIMITS = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE, AnalysisLimit.IMAGE_MODEL_NOT_IMPLEMENTED)
        // W03's fake X is never a candidate; W04's image-only modal is unknown once the page title is out of scope.
        const val RECORDED_WEB_WITHOUT_MENU = """
            W01: tree | COUPON | ① label:닫기 ② label:닫기 ③ KIND_NOT_IN_PRESET
            W02: A blocked
            W03: tree | ADVERTISEMENT | ① label:닫기 ② label:닫기 ③ label:닫기
            W04: tree | UNKNOWN | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            W05: A summary | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            W06: A summary | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            W07: A summary | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
        """

        // 2026-10-23, API 37 emulator dumps. Only P01/P12-1 (coupon), P06 and P17 (ad) are closed; every
        // other screen is asked, told or waited on. Nothing pressed differs from D's allowed button.
        const val RECORDED_THROUGH_A = """
            P01: tree | COUPON | ① btn_close ② btn_close ③ KIND_NOT_IN_PRESET
            P02: tree | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P03: tree | ADVERTISEMENT | ① WAITING_FOR_TARGET ② WAITING_FOR_TARGET ③ WAITING_FOR_TARGET
            P04: A blocked
            P05: tree | UNKNOWN | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P06: tree | ADVERTISEMENT | ① btn_close ② btn_close ③ btn_close
            P07: tree | UNKNOWN | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P08: A summary | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P09: A summary | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P10: A summary | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P11: A blocked
            P12-1: tree | COUPON | ① btn_close ② btn_close ③ KIND_NOT_IN_PRESET
            P12-2: A summary | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P12-3: A summary | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P13: tree | ADVERTISEMENT | ① WAITING_FOR_TARGET ② WAITING_FOR_TARGET ③ WAITING_FOR_TARGET
            W01: A blocked
            W02: A blocked
            W03: A blocked
            W04: A blocked
            W05: A blocked
            W06: A blocked
            W07: A blocked
            P14-1: tree | UNKNOWN | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P14-2: tree | UNKNOWN | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P16-1: tree | ADVERTISEMENT | ① WAITING_FOR_TARGET ② WAITING_FOR_TARGET ③ WAITING_FOR_TARGET
            P16-2: tree | ADVERTISEMENT | ① WAITING_FOR_TARGET ② WAITING_FOR_TARGET ③ WAITING_FOR_TARGET
            P17: tree | ADVERTISEMENT | ① btn_close ② btn_close ③ btn_close
            P18: A summary | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
        """

        // B alone: P18 becomes an ad (R1) and closes; web screens stay decisions because the menu's
        // "결제"/"동의" links are warnings that scoping never drops.
        const val RECORDED_RAW = """
            P01: raw | COUPON | ① btn_close ② btn_close ③ KIND_NOT_IN_PRESET
            P02: raw | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P03: raw | ADVERTISEMENT | ① WAITING_FOR_TARGET ② WAITING_FOR_TARGET ③ WAITING_FOR_TARGET
            P04: raw | ADVERTISEMENT | ① WAITING_FOR_TARGET ② WAITING_FOR_TARGET ③ WAITING_FOR_TARGET
            P05: raw | UNKNOWN | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P06: raw | ADVERTISEMENT | ① btn_close ② btn_close ③ btn_close
            P07: raw | UNKNOWN | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P08: raw | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P09: raw | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P10: raw | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P11: raw | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P12-1: raw | COUPON | ① btn_close ② btn_close ③ KIND_NOT_IN_PRESET
            P12-2: raw | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P12-3: raw | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P13: raw | ADVERTISEMENT | ① WAITING_FOR_TARGET ② WAITING_FOR_TARGET ③ WAITING_FOR_TARGET
            W01: raw | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            W02: raw | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            W03: raw | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            W04: raw | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            W05: raw | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            W06: raw | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            W07: raw | USER_DECISION | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P14-1: raw | UNKNOWN | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P14-2: raw | UNKNOWN | ① KIND_NEVER_CLOSED ② KIND_NEVER_CLOSED ③ KIND_NEVER_CLOSED
            P16-1: raw | ADVERTISEMENT | ① WAITING_FOR_TARGET ② WAITING_FOR_TARGET ③ WAITING_FOR_TARGET
            P16-2: raw | ADVERTISEMENT | ① WAITING_FOR_TARGET ② WAITING_FOR_TARGET ③ WAITING_FOR_TARGET
            P17: raw | ADVERTISEMENT | ① btn_close ② btn_close ③ btn_close
            P18: raw | ADVERTISEMENT | ① btn_close ② btn_close ③ btn_close
        """
    }
}
