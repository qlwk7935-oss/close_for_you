package kr.closeforyou.simpleclose

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kr.closeforyou.core.model.AnalysisLimit
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.PopupTrigger
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.SnapshotCompleteness
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.core.model.TextSource
import java.security.MessageDigest

/** One popup as the engine saw it: the DTOs B's classifier and C's prompt already understand. */
data class SimplePopup(
    val snapshot: PopupSnapshot,
    val analysis: PopupAnalysis,
    val root: PopupRoot,
    val scan: ButtonScan,
    /**
     * F27 (review #1): the read that found it was the probe's subtree read. Otherwise (a whole read found it) the
     * strict close re-check reads the whole tree too, or a moved popup the probe cannot see would count as closed.
     */
    val foundByProbe: Boolean = true,
    /** F27 (review #7): part of the popup lies deeper than the walk goes; a risky button there would be unseen. */
    val depthCut: Boolean = false,
    /** F35 (B_DEMO_RULES.md 8-1): nodes beside a card-shaped root counted for the safety checks ([PopupNeighbors]). */
    val neighborCount: Int = 0,
) {
    /** Same popup later: same window, same root kind, same structure under the root. */
    val fingerprint: String get() = "${snapshot.token.windowId}:${root.kind}:${snapshot.token.structureDigest}"

    /** Same popup while it stays on screen: same window and [PopupRoot.key], whatever repaints inside. */
    val coarseKey: String get() = "${snapshot.token.windowId}:${root.key}"
}

/**
 * Adapter from the engine's node list to the shared `PopupSnapshot` / `PopupAnalysis` (contract v3),
 * so `DecisionPopupClassifier`, `RememberChoiceRules.suspiciousByB` and `ChoicePromptPlanner` run
 * unchanged. Texts are the visible texts under the popup root only (tree source: no OCR); invisible
 * page text such as a zero-height footer never reaches B. Screen text stays in these objects,
 * which redact their toString, and is never logged.
 */
object SimplePopupAnalysis {
    fun build(
        nodes: List<NodeSnapshot>,
        root: PopupRoot,
        packageName: String,
        windowId: Int,
        screen: ScreenBounds,
        screenVersion: Long,
        nowUptimeMillis: Long,
    ): SimplePopup {
        val visible = nodes.filter { PopupRootFinder.isVisible(it, screen) }
        val inside = visible.filter(root::contains)
        // B_DEMO_RULES.md 8-1: what sits right beside a card-shaped root counts for the safety checks, never as a target.
        val neighbors = PopupNeighbors.of(visible, root, screen)
        val scan = PopupNeighbors.withNeighbors(CloseButtonFinder.scan(visible, root), neighbors)
        val texts = (inside + neighbors).flatMap { node ->
            listOfNotNull(node.text, node.contentDescription).filter { it.isNotBlank() }
                .map { ExtractedText(it, node.bounds, TextSource.ACCESSIBILITY_TREE, confidence = null) }
        }
        val structure = sha256(inside.map { listOf(it.path, it.className, it.viewId, it.clickable.toString(), it.bounds.toString()).joinToString("|") })
        val content = sha256(texts.map { it.text })
        val token = SnapshotToken(
            screenVersion = screenVersion,
            windowId = windowId,
            packageName = packageName,
            popupInstanceId = "$screenVersion:${root.path}",
            structureDigest = structure,
            contentDigest = content,
            captureEpoch = screenVersion,
        )
        val snapshot = PopupSnapshot(
            token, nowUptimeMillis, visible.toPersistentList(), persistentListOf(), screen,
            PopupTrigger.WINDOW_CHANGED, SnapshotCompleteness.COMPLETE,
        )
        val analysis = PopupAnalysis(
            token = token,
            texts = texts.toPersistentList(),
            closeCandidates = scan.candidates,
            // Honest: no OCR, no image model, no A eligibility; nothing here is an authorization.
            limitations = persistentSetOf(AnalysisLimit.OCR_NOT_IMPLEMENTED, AnalysisLimit.IMAGE_MODEL_NOT_IMPLEMENTED, AnalysisLimit.INCOMPLETE_EVIDENCE),
        )
        return SimplePopup(snapshot, analysis, root, scan, neighborCount = neighbors.size)
    }

    private fun sha256(fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (field in fields) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(byteArrayOf((bytes.size ushr 8).toByte(), bytes.size.toByte()))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
