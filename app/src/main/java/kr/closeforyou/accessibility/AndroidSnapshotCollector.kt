package kr.closeforyou.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kr.closeforyou.core.model.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

internal sealed interface CollectionResult {
    data class Ready(val snapshot: PopupSnapshot, val frame: DetectionFrame) : CollectionResult
    data class Blocked(val reason: PrivacyBlockReason, val targetPackage: String? = null, val windowId: Int = -1,
        val diagnostic: CollectionProtectionDiagnostic? = null) : CollectionResult
}

/**
 * Sole owner of framework nodes. Both passes finish synchronously; no node/event survives capture.
 * Pass 1 reads only for protection. Pass 2 repeats protection and builds ephemeral DTOs locally.
 * A failure anywhere discards the entire result, including any partially collected text.
 */
internal class AndroidSnapshotCollector {
    private var geometry: AndroidScreenGeometry? = null
    fun resetGeometry() { geometry?.reset() }
    private val salt = ByteArray(32).also(SecureRandom()::nextBytes)

    fun capture(
        service: AccessibilityService,
        epoch: Long,
        trigger: PopupTrigger,
        packageGate: (String) -> PrivacyBlockReason?,
        requireCaptureIsolation: Boolean = false,
        trace: CaptureTrace = CaptureTrace(null),
    ): CollectionResult {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val androidWindows = mutableListOf<AccessibilityWindowInfo>()
        var targetPackage: String? = null
        var targetId = -1
        var phase = CollectionPhase.ROOT
        var tree = CollectionTree.NONE
        var frameStatus = ScreenFrameStatus.UNAVAILABLE
        fun blocked(reason: PrivacyBlockReason, detail: CaptureDetail, related: WindowType? = null,
            failure: CollectionGuardFailure? = null): CollectionResult {
            trace.detail(detail, related)
            trace.block(reason)
            val finding = failure?.finding ?: when {
                detail == CaptureDetail.COLLECTION_FAILED -> CollectionFinding.FRAMEWORK_FAILURE
                phase == CollectionPhase.ROOT -> CollectionFinding.ROOT_GUARD
                phase == CollectionPhase.PACKAGE_GATE -> CollectionFinding.PACKAGE_GUARD
                phase == CollectionPhase.WINDOWS -> CollectionFinding.WINDOW_GUARD
                phase == CollectionPhase.GEOMETRY -> CollectionFinding.GEOMETRY_GUARD
                else -> CollectionFinding.NONE
            }
            return CollectionResult.Blocked(reason, targetPackage, targetId,
                CollectionProtectionDiagnostic(phase, detail, tree, failure?.field ?: CollectionField.NONE,
                    finding, related, frameStatus))
        }
        return try {
            val root = service.rootInActiveWindow
                ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.ROOT_UNAVAILABLE)
            roots += root
            val packageName = root.packageName?.toString()
                ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.ROOT_PACKAGE_UNAVAILABLE)
            targetPackage = packageName
            targetId = root.windowId
            trace.root(packageName, root.windowId)
            phase = CollectionPhase.PACKAGE_GATE
            packageGate(packageName)?.let { return blocked(it, CaptureDetail.PACKAGE_GATE) }
            phase = CollectionPhase.WINDOWS
            androidWindows += service.windows
            if (requireCaptureIsolation && androidWindows.any { it.displayId != Display.DEFAULT_DISPLAY }) {
                return blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.SECONDARY_DISPLAY)
            }
            if (androidWindows.isEmpty() || androidWindows.size > MAX_WINDOWS) {
                return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.WINDOW_LIST_UNAVAILABLE)
            }
            val windows = androidWindows.map { window ->
                WindowSnapshot(window.id, windowType(window.type), window.bounds(),
                    window.isActive, window.isFocused, window.layer)
            }
            val targetWindow = windows.firstOrNull { it.id == root.windowId }
                ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.TARGET_MISSING)
            trace.target(targetWindow.type)
            val frameworkTarget = androidWindows.single { it.id == root.windowId }
            if (frameworkTarget.displayId != Display.DEFAULT_DISPLAY) {
                return blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.SECONDARY_DISPLAY)
            }
            trace.syntheticWindow { frameworkTarget.title }
            if (targetWindow.type != WindowType.APPLICATION) {
                return blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.TARGET_NOT_APPLICATION)
            }
            windows.firstOrNull { (it.active || it.focused) && it.type != WindowType.APPLICATION }?.let {
                return blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.NON_APPLICATION_FOCUS, it.type)
            }

            for (window in androidWindows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || window.id == root.windowId) continue
                val other = window.root
                    ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.WINDOW_LIST_UNAVAILABLE)
                roots += other
                val otherPackage = other.packageName?.toString()
                    ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.WINDOW_LIST_UNAVAILABLE)
                if (otherPackage != packageName && (requireCaptureIsolation || window.isActive || window.isFocused)) {
                    return blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.FOREIGN_APPLICATION, WindowType.APPLICATION)
                }
            }

            phase = CollectionPhase.GEOMETRY
            val geometryRead = (geometry ?: AndroidScreenGeometry(service).also { geometry = it }).read()
            frameStatus = geometryRead.status
            trace.frameStatus(geometryRead.status)
            val screen = geometryRead.frame ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.DISPLAY_GEOMETRY_UNCERTAIN)
            trace.coordinates(targetWindow.bounds, screen, root.bounds()) {
                try { service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.toScreenBounds() }
                catch (_: Exception) { null }
            }
            val budget = TraversalBudget()
            phase = CollectionPhase.PROTECTION_PASS
            // Inspect all trees of this application, including its obscured background window.
            for (candidateRoot in roots) {
                if (candidateRoot.packageName?.toString() == packageName) {
                    tree = if (candidateRoot === root) CollectionTree.TARGET else CollectionTree.SAME_APP_BACKGROUND
                    walk(candidateRoot, budget) { node, _ -> inspect(node, packageName) }
                }
            }

            val nodes = mutableListOf<NodeSnapshot>()
            val outlines = mutableListOf<DetectionOutline>()
            val structure = MessageDigest.getInstance("SHA-256").apply { update(salt) }
            val content = MessageDigest.getInstance("SHA-256").apply { update(salt) }
            var textCharacters = 0
            phase = CollectionPhase.SNAPSHOT_PASS
            tree = CollectionTree.TARGET
            walk(root, budget) { node, path ->
                inspect(node, packageName)
                val text = node.text?.toString()
                val description = node.contentDescription?.toString()
                textCharacters += (text?.length ?: 0) + (description?.length ?: 0)
                if (textCharacters > MAX_TEXT_CHARACTERS) throw CollectionGuardFailure(PrivacyBlockReason.TRUNCATED_TREE,
                    finding = CollectionFinding.TOTAL_TEXT_LIMIT)
                val bounds = node.bounds()
                val actions = buildSet {
                    val ids = node.actionList.map { it.id }
                    if (AccessibilityNodeInfo.ACTION_CLICK in ids) add(NodeAction.CLICK)
                    if (AccessibilityNodeInfo.ACTION_DISMISS in ids) add(NodeAction.DISMISS)
                }.toPersistentSet()
                val snapshot = NodeSnapshot(path, node.viewIdResourceName, node.className?.toString(),
                    text, description, node.isClickable, node.isEnabled, bounds, actions)
                nodes += snapshot
                val structuralFields = listOf(path, snapshot.viewId, snapshot.className,
                    bounds.left, bounds.top, bounds.right, bounds.bottom, snapshot.clickable,
                    snapshot.enabled, node.childCount, actions.joinToString(","))
                structuralFields.forEach { structure.field(it?.toString()) }
                content.field(path)
                content.field(text)
                content.field(description)
                outlines += DetectionOutline(
                    digest(path, snapshot.viewId, snapshot.className), bounds, node.childCount,
                    snapshot.className?.contains("dialog", ignoreCase = true) == true ||
                        NodeAction.DISMISS in actions,
                    path, !node.paneTitle.isNullOrEmpty(), node.isVisibleToUser, node.isScrollable,
                )
            }
            trace.nodeClasses(nodes.any { it.className?.contains("dialog", ignoreCase = true) == true })
            val structureDigest = structure.digest().hex()
            val token = SnapshotToken(epoch, root.windowId, packageName, UUID.randomUUID().toString(),
                structureDigest, content.digest().hex(), epoch)
            val snapshot = PopupSnapshot(token, SystemClock.uptimeMillis(), nodes.toPersistentList(),
                windows.toPersistentList(), screen, trigger, SnapshotCompleteness.COMPLETE)
            CollectionResult.Ready(snapshot, DetectionFrame(
                digest(packageName), root.windowId,
                windows.filter { it.type == WindowType.APPLICATION }.mapTo(mutableSetOf()) { it.id },
                structureDigest, screen, outlines.toList(), trigger, targetWindow.bounds,
            ))
        } catch (failure: CollectionGuardFailure) {
            blocked(failure.reason, CaptureDetail.TREE_PROTECTION, failure = failure)
        } catch (_: Exception) {
            // Framework exception messages can contain screen content: never log the exception.
            blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.COLLECTION_FAILED)
        } finally {
            roots.forEach { it.release() }
            androidWindows.forEach { it.release() }
        }
    }

    private fun inspect(node: AccessibilityNodeInfo, expectedPackage: String) {
        val packageName = node.packageName?.toString()
        if (packageName != null && packageName != expectedPackage) {
            throw CollectionGuardFailure(PrivacyBlockReason.UNREADABLE_TREE, CollectionField.NODE_PACKAGE, CollectionFinding.PACKAGE_MISMATCH)
        }
        CollectionProtectionCheck.field(node.isPassword, node.isEditable, node.inputType)
        fun check(value: CharSequence?, field: CollectionField) = CollectionProtectionCheck.text(value, field, MAX_NODE_TEXT_CHARACTERS)
        check(node.text, CollectionField.TEXT)
        check(node.contentDescription, CollectionField.CONTENT_DESCRIPTION)
        check(node.viewIdResourceName, CollectionField.VIEW_ID)
        check(node.className, CollectionField.CLASS_NAME)
        check(node.error, CollectionField.ERROR)
        check(node.hintText, CollectionField.HINT)
        check(node.paneTitle, CollectionField.PANE_TITLE)
        check(node.tooltipText, CollectionField.TOOLTIP)
        check(node.stateDescription, CollectionField.STATE_DESCRIPTION)
    }

    private fun walk(
        root: AccessibilityNodeInfo,
        budget: TraversalBudget,
        visitor: (AccessibilityNodeInfo, String) -> Unit,
    ) {
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
            budget.visit(depth)
            visitor(node, path)
            val count = node.childCount
            if (count > MAX_NODES) throw CollectionGuardFailure(PrivacyBlockReason.TRUNCATED_TREE, finding = CollectionFinding.CHILD_COUNT_LIMIT)
            for (index in 0 until count) {
                val child = node.getChild(index) ?: throw CollectionGuardFailure(PrivacyBlockReason.UNREADABLE_TREE,
                    finding = CollectionFinding.MISSING_CHILD)
                try { visit(child, "$path/$index", depth + 1) } finally { child.release() }
            }
        }
        visit(root, "0", 0)
    }

    private fun digest(vararg fields: String?): String = MessageDigest.getInstance("SHA-256").run {
        update(salt)
        fields.forEach { field(it) }
        digest().hex()
    }

    private fun MessageDigest.field(value: String?) {
        val bytes = value?.toByteArray(Charsets.UTF_8)
        val size = bytes?.size ?: -1
        update(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
        if (bytes != null) update(bytes)
    }

    private class TraversalBudget {
        private var count = 0
        private val deadline = SystemClock.uptimeMillis() + MAX_CAPTURE_MILLIS
        fun visit(depth: Int) {
            // Preserve original short-circuit order/thresholds, labeling only the first rejection.
            if (++count > MAX_NODES * 2)
                throw CollectionGuardFailure(PrivacyBlockReason.TRUNCATED_TREE, finding = CollectionFinding.NODE_COUNT_LIMIT)
            if (depth > MAX_DEPTH)
                throw CollectionGuardFailure(PrivacyBlockReason.TRUNCATED_TREE, finding = CollectionFinding.DEPTH_LIMIT)
            if (SystemClock.uptimeMillis() > deadline)
                throw CollectionGuardFailure(PrivacyBlockReason.TRUNCATED_TREE, finding = CollectionFinding.TIME_LIMIT)
        }
    }

    companion object {
        private const val MAX_NODES = 1_200
        private const val MAX_WINDOWS = 32
        private const val MAX_DEPTH = 60
        private const val MAX_NODE_TEXT_CHARACTERS = 4_096
        private const val MAX_TEXT_CHARACTERS = 32_768
        private const val MAX_CAPTURE_MILLIS = 100L
    }
}

private fun ByteArray.hex(): String {
    val alphabet = "0123456789abcdef"
    return CharArray(size * 2).also { chars ->
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = alphabet[value ushr 4]
            chars[index * 2 + 1] = alphabet[value and 0x0f]
        }
    }.concatToString()
}
private fun AccessibilityNodeInfo.bounds(): ScreenBounds = Rect().also(::getBoundsInScreen).toScreenBounds()
private fun AccessibilityWindowInfo.bounds(): ScreenBounds = Rect().also(::getBoundsInScreen).toScreenBounds()
private fun Rect.toScreenBounds(): ScreenBounds = ScreenBounds(left, top, right, bottom)
private fun windowType(type: Int): WindowType = when (type) {
    AccessibilityWindowInfo.TYPE_APPLICATION -> WindowType.APPLICATION
    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowType.INPUT_METHOD
    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowType.ACCESSIBILITY_OVERLAY
    AccessibilityWindowInfo.TYPE_SYSTEM -> WindowType.SYSTEM
    else -> WindowType.OTHER
}

@Suppress("DEPRECATION")
private fun AccessibilityNodeInfo.release() { recycle() }
@Suppress("DEPRECATION")
private fun AccessibilityWindowInfo.release() { recycle() }
