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
    data class Ready(val snapshot: PopupSnapshot, val frame: DetectionFrame,
        val nativeActions: NativeNodeActions? = null) : CollectionResult
    data class Blocked(val reason: PrivacyBlockReason) : CollectionResult
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
        freshPresentation: Boolean = false,
        checkpoint: () -> Unit = {},
    ): CollectionResult {
        val capturedAtUptimeMillis = SystemClock.uptimeMillis()
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val androidWindows = mutableListOf<AccessibilityWindowInfo>()
        fun blocked(reason: PrivacyBlockReason, detail: CaptureDetail, related: WindowType? = null): CollectionResult {
            trace.detail(detail, related)
            trace.block(reason)
            return CollectionResult.Blocked(reason)
        }
        return try {
            CollectionResourceSafety.run(body = {
                if (freshPresentation) checkpoint()
                val root = service.rootInActiveWindow
                    ?: return@run blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.ROOT_UNAVAILABLE)
                roots += root
                val packageName = root.packageName?.toString()
                    ?: return@run blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.ROOT_PACKAGE_UNAVAILABLE)
                trace.root(packageName, root.windowId)
                packageGate(packageName)?.let { return@run blocked(it, CaptureDetail.PACKAGE_GATE) }
                if (freshPresentation) {
                    refreshPresentationNode(root, capturedAtUptimeMillis, checkpoint)
                    if (root.packageName?.toString() != packageName) throw GuardFailure(PrivacyBlockReason.UNREADABLE_TREE)
                }
                androidWindows += service.windows
                if (requireCaptureIsolation && androidWindows.any { it.displayId != Display.DEFAULT_DISPLAY }) {
                    return@run blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.SECONDARY_DISPLAY)
                }
                if (androidWindows.isEmpty() || androidWindows.size > MAX_WINDOWS) {
                    return@run blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.WINDOW_LIST_UNAVAILABLE)
                }
                val windows = androidWindows.map { window ->
                    WindowSnapshot(window.id, windowType(window.type), window.bounds(),
                        window.isActive, window.isFocused, window.layer)
                }
                val targetWindow = windows.firstOrNull { it.id == root.windowId }
                    ?: return@run blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.TARGET_MISSING)
                trace.target(targetWindow.type)
                val frameworkTarget = androidWindows.single { it.id == root.windowId }
                if (frameworkTarget.displayId != Display.DEFAULT_DISPLAY) {
                    return@run blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.SECONDARY_DISPLAY)
                }
                trace.syntheticWindow { frameworkTarget.title }
                if (targetWindow.type != WindowType.APPLICATION) {
                    return@run blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.TARGET_NOT_APPLICATION)
                }
                windows.firstOrNull { (it.active || it.focused) && it.type != WindowType.APPLICATION }?.let {
                    return@run blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.NON_APPLICATION_FOCUS, it.type)
                }

                for (window in androidWindows) {
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || window.id == root.windowId) continue
                    val other = window.root
                        ?: return@run blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.WINDOW_LIST_UNAVAILABLE)
                    roots += other
                    val otherPackage = other.packageName?.toString()
                        ?: return@run blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.WINDOW_LIST_UNAVAILABLE)
                    if (otherPackage != packageName && (requireCaptureIsolation || window.isActive || window.isFocused)) {
                        return@run blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.FOREIGN_APPLICATION, WindowType.APPLICATION)
                    }
                }

                val geometryRead = (geometry ?: AndroidScreenGeometry(service).also { geometry = it }).read()
                trace.frameStatus(geometryRead.status)
                val screen = geometryRead.frame ?: return@run blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.DISPLAY_GEOMETRY_UNCERTAIN)
                trace.coordinates(targetWindow.bounds, screen, root.bounds()) {
                    try { service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.toScreenBounds() }
                    catch (_: Exception) { null }
                }
                val budget = TraversalBudget()
                // Inspect all trees of this application, including its obscured background window.
                for (candidateRoot in roots) {
                    if (candidateRoot.packageName?.toString() == packageName) {
                        walk(candidateRoot, budget, beforeRead = { node ->
                            if (freshPresentation) {
                                refreshPresentationNode(node, capturedAtUptimeMillis, checkpoint)
                                if (node.windowId != candidateRoot.windowId) throw GuardFailure(PrivacyBlockReason.UNREADABLE_TREE)
                            }
                        }) { node, _ -> inspect(node, packageName) }
                    }
                }

                val nodes = mutableListOf<NodeSnapshot>()
                val nativeRows = if (kr.closeforyou.accessibility.integration.ManualDemoBoundsFeature.enabled)
                    mutableListOf<NativeActionSummary>() else null
                val outlines = mutableListOf<DetectionOutline>()
                val structure = MessageDigest.getInstance("SHA-256").apply { update(salt) }
                val content = MessageDigest.getInstance("SHA-256").apply { update(salt) }
                var textCharacters = 0
                walk(root, budget, beforeRead = { node ->
                    if (freshPresentation) {
                        refreshPresentationNode(node, capturedAtUptimeMillis, checkpoint)
                        if (node.windowId != root.windowId) throw GuardFailure(PrivacyBlockReason.UNREADABLE_TREE)
                    }
                }) { node, path ->
                    inspect(node, packageName)
                    val text = node.text?.toString()
                    val description = node.contentDescription?.toString()
                    textCharacters += (text?.length ?: 0) + (description?.length ?: 0)
                    if (textCharacters > MAX_TEXT_CHARACTERS) throw GuardFailure(PrivacyBlockReason.TRUNCATED_TREE)
                    val bounds = node.bounds()
                    val actions = buildSet {
                        val actions = node.actionList
                        val readActions = if (nativeRows != null) actions.toList() else actions
                        val ids = readActions.map { it.id }
                        nativeRows?.add(if (ids.size <= 64) NativeActionSummary.fromIds(ids,
                            hasUnverifiedLabel = readActions.any { it.label != null }) else NativeActionSummary.UNKNOWN)
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
                trace.nodeClasses(nodes.any { it.className?.contains("dialog", ignoreCase = true) == true })
                val structureDigest = structure.digest().hex()
                val token = SnapshotToken(epoch, root.windowId, packageName, UUID.randomUUID().toString(),
                    structureDigest, content.digest().hex(), epoch)
                val snapshot = PopupSnapshot(token, capturedAtUptimeMillis, nodes.toPersistentList(),
                    windows.toPersistentList(), screen, trigger, SnapshotCompleteness.COMPLETE)
                if (freshPresentation) {
                    checkpoint()
                    val endpoint = service.rootInActiveWindow ?: throw GuardFailure(PrivacyBlockReason.UNREADABLE_TREE)
                    roots += endpoint
                    refreshPresentationNode(endpoint, capturedAtUptimeMillis, checkpoint)
                    if (endpoint.windowId != token.windowId || endpoint.packageName?.toString() != packageName ||
                        endpoint.bounds() != nodes.first().bounds) throw GuardFailure(PrivacyBlockReason.UNREADABLE_TREE)
                    // The later DISPLAY_REVIEW uses window.root. Refuse differing root sources
                    // before C exists; do not silently rebase the active-root snapshot onto it.
                    val windowRoot = frameworkTarget.root ?: throw GuardFailure(PrivacyBlockReason.UNREADABLE_TREE)
                    roots += windowRoot
                    refreshPresentationNode(windowRoot, capturedAtUptimeMillis, checkpoint)
                    if (windowRoot.windowId != token.windowId || windowRoot.packageName?.toString() != packageName ||
                        windowRoot.bounds() != nodes.first().bounds || geometry?.read()?.frame != screen)
                        throw GuardFailure(PrivacyBlockReason.UNREADABLE_TREE)
                    val endingWindows = service.windows
                    androidWindows += endingWindows
                    if (endingWindows.any { it.displayId != Display.DEFAULT_DISPLAY } || endingWindows.map { window ->
                        WindowSnapshot(window.id, windowType(window.type), window.bounds(), window.isActive, window.isFocused, window.layer)
                    } != windows) throw GuardFailure(PrivacyBlockReason.UNREADABLE_TREE)
                    if (SystemClock.uptimeMillis() - capturedAtUptimeMillis >= MAX_CAPTURE_MILLIS)
                        throw GuardFailure(PrivacyBlockReason.TRUNCATED_TREE)
                    checkpoint()
                }
                CollectionResult.Ready(snapshot, DetectionFrame(
                    digest(packageName), root.windowId,
                    windows.filter { it.type == WindowType.APPLICATION }.mapTo(mutableSetOf()) { it.id },
                    structureDigest, screen, outlines.toList(), trigger, targetWindow.bounds,
                ), nativeRows?.let { NativeNodeActions.forSnapshotSummaries(snapshot, it) })
            }, cleanup = {
                val batch = CollectionReleaseBatch()
                val seen = java.util.IdentityHashMap<Any, Boolean>()
                roots.forEach { if (seen.put(it, true) == null) batch.attempt { it.release() } }
                androidWindows.forEach { if (seen.put(it, true) == null) batch.attempt { it.release() } }
                batch.finish()
            }, cleanupFailure = { outcome ->
                if (outcome is CollectionResult.Blocked) outcome
                else blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.COLLECTION_FAILED)
            })
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (failure: GuardFailure) {
            blocked(failure.reason, CaptureDetail.TREE_PROTECTION)
        } catch (_: Exception) {
            // Framework exception messages can contain screen content: never log the exception.
            blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.COLLECTION_FAILED)
        } finally {
            roots.clear(); androidWindows.clear()
        }
    }

    /** Supplementary cache bypass for bounded pre-show reads only, never proof of native ABA.
     * refresh() false, identity change, cancellation or the original 100 ms bound discards all.
     */
    private fun refreshPresentationNode(node: AccessibilityNodeInfo, started: Long, checkpoint: () -> Unit) {
        if (!refreshPresentationNodeRead(started, SystemClock::uptimeMillis, checkpoint,
                { node.windowId }, node::refresh)) throw GuardFailure(PrivacyBlockReason.UNREADABLE_TREE)
    }

    private fun inspect(node: AccessibilityNodeInfo, expectedPackage: String) {
        val packageName = node.packageName?.toString()
        if (packageName != null && packageName != expectedPackage) {
            throw GuardFailure(PrivacyBlockReason.UNREADABLE_TREE)
        }
        PrivacyPolicy.fieldBlock(node.isPassword, node.isEditable, node.inputType)?.let { throw GuardFailure(it) }
        fun check(value: CharSequence?) {
            if ((value?.length ?: 0) > MAX_NODE_TEXT_CHARACTERS) throw GuardFailure(PrivacyBlockReason.TRUNCATED_TREE)
            if (PrivacyPolicy.hasSensitiveText(value) || PrivacyPolicy.hasUncertainText(value)) throw GuardFailure(PrivacyBlockReason.SENSITIVE_CONTENT)
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
        beforeRead: (AccessibilityNodeInfo) -> Unit = {},
        visitor: (AccessibilityNodeInfo, String) -> Unit,
    ) {
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
            budget.visit(depth)
            beforeRead(node)
            visitor(node, path)
            val count = node.childCount
            if (count > MAX_NODES) throw GuardFailure(PrivacyBlockReason.TRUNCATED_TREE)
            for (index in 0 until count) {
                val child = node.getChild(index) ?: throw GuardFailure(PrivacyBlockReason.UNREADABLE_TREE)
                CollectionResourceSafety.run(body = { visit(child, "$path/$index", depth + 1) },
                    cleanup = { child.release() }, cleanupFailure = { throw CollectionCleanupFailure() })
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
