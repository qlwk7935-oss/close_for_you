package kr.closeforyou.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
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
    data class Blocked(val reason: PrivacyBlockReason) : CollectionResult
}

/**
 * Sole owner of framework nodes. Both passes finish synchronously; no node/event survives capture.
 * Pass 1 reads only for protection. Pass 2 repeats protection and builds ephemeral DTOs locally.
 * A failure anywhere discards the entire result, including any partially collected text.
 */
internal class AndroidSnapshotCollector {
    private val salt = ByteArray(32).also(SecureRandom()::nextBytes)

    fun capture(
        service: AccessibilityService,
        epoch: Long,
        trigger: PopupTrigger,
        packageGate: (String) -> PrivacyBlockReason?,
    ): CollectionResult {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val androidWindows = mutableListOf<AccessibilityWindowInfo>()
        return try {
            val root = service.rootInActiveWindow
                ?: return CollectionResult.Blocked(PrivacyBlockReason.UNREADABLE_TREE)
            roots += root
            val packageName = root.packageName?.toString()
                ?: return CollectionResult.Blocked(PrivacyBlockReason.UNREADABLE_TREE)
            packageGate(packageName)?.let { return CollectionResult.Blocked(it) }
            androidWindows += service.windows
            if (androidWindows.isEmpty() || androidWindows.size > MAX_WINDOWS) {
                return CollectionResult.Blocked(PrivacyBlockReason.UNREADABLE_TREE)
            }
            val windows = androidWindows.map { window ->
                WindowSnapshot(window.id, windowType(window.type), window.bounds(),
                    window.isActive, window.isFocused, window.layer)
            }
            val targetWindow = windows.firstOrNull { it.id == root.windowId }
                ?: return CollectionResult.Blocked(PrivacyBlockReason.UNREADABLE_TREE)
            if (targetWindow.type != WindowType.APPLICATION ||
                windows.any { (it.active || it.focused) && it.type != WindowType.APPLICATION }) {
                return CollectionResult.Blocked(PrivacyBlockReason.SYSTEM_WINDOW)
            }

            for (window in androidWindows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || window.id == root.windowId) continue
                val other = window.root
                    ?: return CollectionResult.Blocked(PrivacyBlockReason.UNREADABLE_TREE)
                roots += other
                val otherPackage = other.packageName?.toString()
                    ?: return CollectionResult.Blocked(PrivacyBlockReason.UNREADABLE_TREE)
                if (otherPackage != packageName && (window.isActive || window.isFocused)) {
                    return CollectionResult.Blocked(PrivacyBlockReason.SYSTEM_WINDOW)
                }
            }

            val budget = TraversalBudget()
            // Inspect all trees of this application, including its obscured background window.
            for (candidateRoot in roots) {
                if (candidateRoot.packageName?.toString() == packageName) {
                    walk(candidateRoot, budget) { node, _ -> inspect(node, packageName) }
                }
            }

            val nodes = mutableListOf<NodeSnapshot>()
            val outlines = mutableListOf<DetectionOutline>()
            val structure = MessageDigest.getInstance("SHA-256").apply { update(salt) }
            val content = MessageDigest.getInstance("SHA-256").apply { update(salt) }
            var textCharacters = 0
            walk(root, budget) { node, path ->
                inspect(node, packageName)
                val text = node.text?.toString()
                val description = node.contentDescription?.toString()
                textCharacters += (text?.length ?: 0) + (description?.length ?: 0)
                if (textCharacters > MAX_TEXT_CHARACTERS) throw GuardFailure(PrivacyBlockReason.TRUNCATED_TREE)
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
                    digest(*structuralFields.map { it?.toString() }.toTypedArray()), bounds, node.childCount,
                    snapshot.className?.contains("dialog", ignoreCase = true) == true ||
                        !node.paneTitle.isNullOrEmpty(),
                )
            }
            val screen = service.resources.displayMetrics.let { ScreenBounds(0, 0, it.widthPixels, it.heightPixels) }
            val structureDigest = structure.digest().hex()
            val token = SnapshotToken(epoch, root.windowId, packageName, UUID.randomUUID().toString(),
                structureDigest, content.digest().hex(), epoch)
            val snapshot = PopupSnapshot(token, SystemClock.uptimeMillis(), nodes.toPersistentList(),
                windows.toPersistentList(), screen, trigger, SnapshotCompleteness.COMPLETE)
            CollectionResult.Ready(snapshot, DetectionFrame(
                digest(packageName), root.windowId,
                windows.filter { it.type == WindowType.APPLICATION }.mapTo(mutableSetOf()) { it.id },
                structureDigest, screen, outlines.toList(), trigger,
            ))
        } catch (failure: GuardFailure) {
            CollectionResult.Blocked(failure.reason)
        } catch (_: Exception) {
            // Framework exception messages can contain screen content: never log the exception.
            CollectionResult.Blocked(PrivacyBlockReason.UNREADABLE_TREE)
        } finally {
            roots.forEach { it.release() }
            androidWindows.forEach { it.release() }
        }
    }

    private fun inspect(node: AccessibilityNodeInfo, expectedPackage: String) {
        val packageName = node.packageName?.toString()
        if (packageName != null && packageName != expectedPackage) {
            throw GuardFailure(PrivacyBlockReason.UNREADABLE_TREE)
        }
        PrivacyPolicy.fieldBlock(node.isPassword, node.isEditable, node.inputType)?.let { throw GuardFailure(it) }
        fun check(value: CharSequence?) {
            if ((value?.length ?: 0) > MAX_NODE_TEXT_CHARACTERS) throw GuardFailure(PrivacyBlockReason.TRUNCATED_TREE)
            if (PrivacyPolicy.hasSensitiveText(value)) throw GuardFailure(PrivacyBlockReason.SENSITIVE_CONTENT)
        }
        check(node.text)
        check(node.contentDescription)
        check(node.viewIdResourceName)
        check(node.className)
        check(node.error)
        check(node.hintText)
        check(node.paneTitle)
        check(node.tooltipText)
        check(node.stateDescription)
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
            if (count > MAX_NODES) throw GuardFailure(PrivacyBlockReason.TRUNCATED_TREE)
            for (index in 0 until count) {
                val child = node.getChild(index) ?: throw GuardFailure(PrivacyBlockReason.UNREADABLE_TREE)
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
            if (++count > MAX_NODES * 2 || depth > MAX_DEPTH || SystemClock.uptimeMillis() > deadline) {
                throw GuardFailure(PrivacyBlockReason.TRUNCATED_TREE)
            }
        }
    }

    private class GuardFailure(val reason: PrivacyBlockReason) : RuntimeException()

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
