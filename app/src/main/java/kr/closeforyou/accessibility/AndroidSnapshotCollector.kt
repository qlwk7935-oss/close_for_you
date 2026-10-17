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
        val progress = CollectionProgress()
        fun blocked(reason: PrivacyBlockReason, detail: CaptureDetail, related: WindowType? = null,
            failure: CollectionGuardFailure? = null, exception: Exception? = null): CollectionResult {
            trace.detail(detail, related)
            trace.block(reason)
            val finding = failure?.finding ?: when {
                detail == CaptureDetail.COLLECTION_FAILED -> CollectionFinding.COLLECTION_EXCEPTION
                phase == CollectionPhase.ROOT -> CollectionFinding.ROOT_GUARD
                phase == CollectionPhase.PACKAGE_GATE -> CollectionFinding.PACKAGE_GUARD
                phase == CollectionPhase.WINDOWS -> CollectionFinding.WINDOW_GUARD
                phase == CollectionPhase.GEOMETRY -> CollectionFinding.GEOMETRY_GUARD
                else -> CollectionFinding.NONE
            }
            return CollectionResult.Blocked(reason, targetPackage, targetId,
                CollectionProtectionDiagnostic(phase, detail, tree, failure?.field ?: progress.failure?.field ?: CollectionField.NONE,
                    finding, related, frameStatus, progress.failure?.operation ?: if (exception == null)
                        CollectionOperation.NONE else CollectionOperation.UNCLASSIFIED,
                    progress.failure?.exception ?: exception?.let(CollectionProgress::classify) ?: CollectionExceptionKind.NONE))
        }
        return try {
            val root = progress.at(CollectionOperation.ROOT_READ) { service.rootInActiveWindow }
                ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.ROOT_UNAVAILABLE)
            roots += root
            val packageName = progress.at(CollectionOperation.ROOT_METADATA) { root.packageName?.toString() }
                ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.ROOT_PACKAGE_UNAVAILABLE)
            targetPackage = packageName
            targetId = progress.at(CollectionOperation.ROOT_METADATA) { root.windowId }
            trace.root(packageName, root.windowId)
            phase = CollectionPhase.PACKAGE_GATE
            packageGate(packageName)?.let { return blocked(it, CaptureDetail.PACKAGE_GATE) }
            phase = CollectionPhase.WINDOWS
            androidWindows += progress.at(CollectionOperation.WINDOWS_READ) { service.windows }
            if (requireCaptureIsolation && androidWindows.any { it.displayId != Display.DEFAULT_DISPLAY }) {
                return blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.SECONDARY_DISPLAY)
            }
            if (androidWindows.isEmpty() || androidWindows.size > MAX_WINDOWS) {
                return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.WINDOW_LIST_UNAVAILABLE)
            }
            val windows = androidWindows.map { window ->
                progress.at(CollectionOperation.WINDOW_METADATA) {
                    WindowSnapshot(window.id, windowType(window.type), window.bounds(),
                        window.isActive, window.isFocused, window.layer)
                }
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
                val other = progress.at(CollectionOperation.WINDOW_ROOT_READ) { window.root }
                    ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.WINDOW_LIST_UNAVAILABLE)
                roots += other
                val otherPackage = other.packageName?.toString()
                    ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.WINDOW_LIST_UNAVAILABLE)
                if (otherPackage != packageName && (requireCaptureIsolation || window.isActive || window.isFocused)) {
                    return blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.FOREIGN_APPLICATION, WindowType.APPLICATION)
                }
            }

            phase = CollectionPhase.GEOMETRY
            val geometryRead = progress.at(CollectionOperation.SCREEN_GEOMETRY) {
                (geometry ?: AndroidScreenGeometry(service).also { geometry = it }).read()
            }
            frameStatus = geometryRead.status
            trace.frameStatus(geometryRead.status)
            val screen = geometryRead.frame ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.DISPLAY_GEOMETRY_UNCERTAIN)
            progress.at(CollectionOperation.COORDINATE_CHECK) { trace.coordinates(targetWindow.bounds, screen, root.bounds()) {
                try { service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.toScreenBounds() }
                catch (_: Exception) { null }
            } }
            val budget = TraversalBudget()
            phase = CollectionPhase.PROTECTION_PASS
            // Inspect all trees of this application, including its obscured background window.
            for (candidateRoot in roots) {
                tree = if (candidateRoot === root) CollectionTree.TARGET else CollectionTree.SAME_APP_BACKGROUND
                if (progress.at(CollectionOperation.NODE_PACKAGE_READ, CollectionField.NODE_PACKAGE) { candidateRoot.packageName?.toString() } == packageName) {
                    walk(candidateRoot, budget, progress) { node, _ -> inspect(node, packageName, progress) }
                }
            }

            phase = CollectionPhase.SNAPSHOT_PASS
            tree = CollectionTree.TARGET
            val nodes = mutableListOf<NodeSnapshot>()
            val outlines = mutableListOf<DetectionOutline>()
            val structure = progress.at(CollectionOperation.DIGEST_INITIALIZE) { MessageDigest.getInstance("SHA-256").apply { update(salt) } }
            val content = progress.at(CollectionOperation.DIGEST_INITIALIZE) { MessageDigest.getInstance("SHA-256").apply { update(salt) } }
            var textCharacters = 0
            walk(root, budget, progress) { node, path ->
                inspect(node, packageName, progress)
                val text = progress.at(CollectionOperation.NODE_TEXT_READ, CollectionField.TEXT) { node.text?.toString() }
                val description = progress.at(CollectionOperation.NODE_TEXT_READ, CollectionField.CONTENT_DESCRIPTION) { node.contentDescription?.toString() }
                progress.at(CollectionOperation.TEXT_BUDGET) {
                    textCharacters += (text?.length ?: 0) + (description?.length ?: 0)
                    if (textCharacters > MAX_TEXT_CHARACTERS) throw CollectionGuardFailure(PrivacyBlockReason.TRUNCATED_TREE,
                        finding = CollectionFinding.TOTAL_TEXT_LIMIT)
                }
                val rect = progress.at(CollectionOperation.NODE_BOUNDS_READ) { Rect().also(node::getBoundsInScreen) }
                val bounds = progress.at(CollectionOperation.NODE_BOUNDS_DTO) { rect.toScreenBounds() }
                val actionIds = progress.at(CollectionOperation.NODE_ACTION_READ) { node.actionList.map { it.id } }
                val actions = progress.at(CollectionOperation.NODE_ACTIONS_DTO) {
                    buildSet {
                        if (AccessibilityNodeInfo.ACTION_CLICK in actionIds) add(NodeAction.CLICK)
                        if (AccessibilityNodeInfo.ACTION_DISMISS in actionIds) add(NodeAction.DISMISS)
                    }.toPersistentSet()
                }
                // Read Android properties before DTO constructors: a getter failure is not a DTO failure.
                val viewId = progress.at(CollectionOperation.NODE_PROPERTIES_READ, CollectionField.VIEW_ID) { node.viewIdResourceName }
                val className = progress.at(CollectionOperation.NODE_PROPERTIES_READ, CollectionField.CLASS_NAME) { node.className?.toString() }
                val clickable = progress.at(CollectionOperation.NODE_PROPERTIES_READ) { node.isClickable }
                val enabled = progress.at(CollectionOperation.NODE_PROPERTIES_READ) { node.isEnabled }
                val snapshot = progress.at(CollectionOperation.NODE_DTO) {
                    NodeSnapshot(path, viewId, className, text, description, clickable, enabled, bounds, actions).also { nodes += it }
                }
                val childCount = progress.at(CollectionOperation.CHILD_COUNT_READ) { node.childCount }
                val structuralFields = progress.at(CollectionOperation.STRUCTURE_FIELDS) {
                    listOf(path, snapshot.viewId, snapshot.className, bounds.left, bounds.top, bounds.right,
                        bounds.bottom, snapshot.clickable, snapshot.enabled, childCount, actions.joinToString(","))
                }
                progress.at(CollectionOperation.STRUCTURE_HASH) { structuralFields.forEach { structure.field(it?.toString()) } }
                progress.at(CollectionOperation.CONTENT_HASH) { content.field(path); content.field(text); content.field(description) }
                val fingerprint = progress.at(CollectionOperation.OUTLINE_HASH) { digest(path, snapshot.viewId, snapshot.className) }
                val outlineChildCount = progress.at(CollectionOperation.CHILD_COUNT_READ) { node.childCount }
                val pane = progress.at(CollectionOperation.OUTLINE_PROPERTIES_READ, CollectionField.PANE_TITLE) { !node.paneTitle.isNullOrEmpty() }
                val visible = progress.at(CollectionOperation.OUTLINE_PROPERTIES_READ) { node.isVisibleToUser }
                val scrollable = progress.at(CollectionOperation.OUTLINE_PROPERTIES_READ) { node.isScrollable }
                progress.at(CollectionOperation.OUTLINE_DTO) {
                    outlines += DetectionOutline(fingerprint, bounds, outlineChildCount,
                        snapshot.className?.contains("dialog", ignoreCase = true) == true || NodeAction.DISMISS in actions,
                        path, pane, visible, scrollable)
                }
            }
            trace.nodeClasses(nodes.any { it.className?.contains("dialog", ignoreCase = true) == true })
            val structureDigest = progress.at(CollectionOperation.DIGEST_FINALIZE) { structure.digest().hex() }
            val contentDigest = progress.at(CollectionOperation.DIGEST_FINALIZE) { content.digest().hex() }
            val windowId = progress.at(CollectionOperation.ROOT_METADATA) { root.windowId }
            val token = progress.at(CollectionOperation.TOKEN_DTO) {
                SnapshotToken(epoch, windowId, packageName, UUID.randomUUID().toString(), structureDigest, contentDigest, epoch)
            }
            val snapshot = progress.at(CollectionOperation.SNAPSHOT_DTO) {
                PopupSnapshot(token, SystemClock.uptimeMillis(), nodes.toPersistentList(),
                    windows.toPersistentList(), screen, trigger, SnapshotCompleteness.COMPLETE)
            }
            val applicationDigest = progress.at(CollectionOperation.APPLICATION_HASH) { digest(packageName) }
            val frame = progress.at(CollectionOperation.DETECTION_FRAME_DTO) {
                DetectionFrame(applicationDigest, windowId,
                    windows.filter { it.type == WindowType.APPLICATION }.mapTo(mutableSetOf()) { it.id },
                    structureDigest, screen, outlines.toList(), trigger, targetWindow.bounds)
            }
            CollectionResult.Ready(snapshot, frame)
        } catch (failure: CollectionGuardFailure) {
            blocked(failure.reason, CaptureDetail.TREE_PROTECTION, failure = failure)
        } catch (exception: Exception) {
            // This may be our code or a getter. Classify the boundary/type, never the message or stack.
            blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.COLLECTION_FAILED, exception = exception)
        } finally {
            roots.forEach { it.release() }
            androidWindows.forEach { it.release() }
        }
    }

    private fun inspect(node: AccessibilityNodeInfo, expectedPackage: String, progress: CollectionProgress) {
        progress.at(CollectionOperation.NODE_PACKAGE_READ, CollectionField.NODE_PACKAGE) {
            val packageName = node.packageName?.toString()
            if (packageName != null && packageName != expectedPackage) {
                throw CollectionGuardFailure(PrivacyBlockReason.UNREADABLE_TREE, CollectionField.NODE_PACKAGE, CollectionFinding.PACKAGE_MISMATCH)
            }
        }
        val password = progress.at(CollectionOperation.NODE_FIELD_READ, CollectionField.PASSWORD_FLAG) { node.isPassword }
        val editable = progress.at(CollectionOperation.NODE_FIELD_READ, CollectionField.EDITABLE_FLAG) { node.isEditable }
        val inputType = progress.at(CollectionOperation.NODE_FIELD_READ, CollectionField.INPUT_TYPE) { node.inputType }
        progress.at(CollectionOperation.NODE_FIELD_CHECK) { CollectionProtectionCheck.field(password, editable, inputType) }
        fun check(field: CollectionField, read: () -> CharSequence?) {
            val value = progress.at(CollectionOperation.NODE_TEXT_READ, field, read)
            progress.at(CollectionOperation.NODE_TEXT_CHECK, field) { CollectionProtectionCheck.text(value, field, MAX_NODE_TEXT_CHARACTERS) }
        }
        check(CollectionField.TEXT) { node.text }
        check(CollectionField.CONTENT_DESCRIPTION) { node.contentDescription }
        check(CollectionField.VIEW_ID) { node.viewIdResourceName }
        check(CollectionField.CLASS_NAME) { node.className }
        check(CollectionField.ERROR) { node.error }
        check(CollectionField.HINT) { node.hintText }
        check(CollectionField.PANE_TITLE) { node.paneTitle }
        check(CollectionField.TOOLTIP) { node.tooltipText }
        check(CollectionField.STATE_DESCRIPTION) { node.stateDescription }
    }

    private fun walk(
        root: AccessibilityNodeInfo,
        budget: TraversalBudget,
        progress: CollectionProgress,
        visitor: (AccessibilityNodeInfo, String) -> Unit,
    ) {
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
            progress.at(CollectionOperation.TREE_BUDGET) { budget.visit(depth) }
            visitor(node, path)
            val count = progress.at(CollectionOperation.CHILD_COUNT_READ) { node.childCount }
            if (count > MAX_NODES) throw CollectionGuardFailure(PrivacyBlockReason.TRUNCATED_TREE, finding = CollectionFinding.CHILD_COUNT_LIMIT)
            for (index in 0 until count) {
                val child = progress.at(CollectionOperation.CHILD_READ) {
                    node.getChild(index) ?: throw CollectionGuardFailure(PrivacyBlockReason.UNREADABLE_TREE,
                        finding = CollectionFinding.MISSING_CHILD)
                }
                try { visit(child, "$path/$index", depth + 1) } finally { progress.at(CollectionOperation.CHILD_RELEASE) { child.release() } }
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
