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
            CollectionResourceSafety.run(body = { trace.readBody {
                trace.readPoint(CaptureReadPoint.ROOT)
                if (freshPresentation) checkpoint()
                val root = service.rootInActiveWindow
                    ?: return@readBody blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.ROOT_UNAVAILABLE)
                roots += root
                val packageName = root.packageName?.toString()
                    ?: return@readBody blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.ROOT_PACKAGE_UNAVAILABLE)
                trace.root(packageName, root.windowId)
                packageGate(packageName)?.let { return@readBody blocked(it, CaptureDetail.PACKAGE_GATE) }
                if (freshPresentation) {
                    trace.readPoint(CaptureReadPoint.ROOT_REFRESH)
                    refreshPresentationNode(root, capturedAtUptimeMillis, checkpoint, trace)
                    if (root.packageName?.toString() != packageName) throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace, CaptureDetail.NODE_PACKAGE_MISMATCH)
                }
                trace.readPoint(CaptureReadPoint.WINDOWS)
                androidWindows += service.windows
                if (requireCaptureIsolation && androidWindows.any { it.displayId != Display.DEFAULT_DISPLAY }) {
                    return@readBody blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.SECONDARY_DISPLAY)
                }
                if (androidWindows.isEmpty() || androidWindows.size > MAX_WINDOWS) {
                    return@readBody blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.WINDOW_LIST_UNAVAILABLE)
                }
                val windows = androidWindows.map { window ->
                    WindowSnapshot(window.id, windowType(window.type), window.bounds(),
                        window.isActive, window.isFocused, window.layer)
                }
                trace.readPoint(CaptureReadPoint.TARGET_BINDING)
                val targetWindow = windows.firstOrNull { it.id == root.windowId }
                    ?: return@readBody blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.TARGET_MISSING)
                trace.target(targetWindow.type)
                val frameworkTarget = androidWindows.single { it.id == root.windowId }
                if (frameworkTarget.displayId != Display.DEFAULT_DISPLAY) {
                    return@readBody blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.SECONDARY_DISPLAY)
                }
                trace.syntheticWindow { frameworkTarget.title }
                if (targetWindow.type != WindowType.APPLICATION) {
                    return@readBody blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.TARGET_NOT_APPLICATION)
                }
                windows.firstOrNull { (it.active || it.focused) && it.type != WindowType.APPLICATION }?.let {
                    return@readBody blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.NON_APPLICATION_FOCUS, it.type)
                }

                trace.readPoint(CaptureReadPoint.BACKGROUND_ROOT)
                for (window in androidWindows) {
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || window.id == root.windowId) continue
                    val other = window.root
                        ?: return@readBody blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.BACKGROUND_ROOT_UNAVAILABLE)
                    roots += other
                    val otherPackage = other.packageName?.toString()
                        ?: return@readBody blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.BACKGROUND_PACKAGE_UNAVAILABLE)
                    if (otherPackage != packageName && (requireCaptureIsolation || window.isActive || window.isFocused)) {
                        return@readBody blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.FOREIGN_APPLICATION, WindowType.APPLICATION)
                    }
                }

                trace.readPoint(CaptureReadPoint.FRAME)
                val geometryRead = (geometry ?: AndroidScreenGeometry(service).also { geometry = it }).read()
                trace.frameStatus(geometryRead.status)
                val screen = geometryRead.frame ?: return@readBody blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.DISPLAY_GEOMETRY_UNCERTAIN)
                trace.coordinates(targetWindow.bounds, screen, root.bounds()) {
                    try { service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.toScreenBounds() }
                    catch (_: Exception) { null }
                }
                val budget = TraversalBudget(trace)
                trace.readPoint(CaptureReadPoint.PROTECTION_PASS)
                // Inspect all trees of this application, including its obscured background window.
                for (candidateRoot in roots) {
                    if (candidateRoot.packageName?.toString() == packageName) {
                        walk(candidateRoot, budget, trace, beforeRead = { node ->
                            if (freshPresentation) {
                                refreshPresentationNode(node, capturedAtUptimeMillis, checkpoint, trace)
                                if (node.windowId != candidateRoot.windowId) throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace, CaptureDetail.NODE_WINDOW_MISMATCH)
                            }
                        }) { node, _ -> inspect(node, packageName, trace) }
                    }
                }

                val nodes = mutableListOf<NodeSnapshot>()
                val nativeRows = if (kr.closeforyou.accessibility.integration.ManualDemoBoundsFeature.enabled)
                    mutableListOf<NativeActionSummary>() else null
                val outlines = mutableListOf<DetectionOutline>()
                val structure = MessageDigest.getInstance("SHA-256").apply { update(salt) }
                val content = MessageDigest.getInstance("SHA-256").apply { update(salt) }
                var textCharacters = 0
                trace.readPoint(CaptureReadPoint.SNAPSHOT_PASS)
                walk(root, budget, trace, beforeRead = { node ->
                    if (freshPresentation) {
                        refreshPresentationNode(node, capturedAtUptimeMillis, checkpoint, trace)
                        if (node.windowId != root.windowId) throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace, CaptureDetail.NODE_WINDOW_MISMATCH)
                    }
                }) { node, path ->
                    inspect(node, packageName, trace)
                    val text = node.text?.toString()
                    val description = node.contentDescription?.toString()
                    textCharacters += (text?.length ?: 0) + (description?.length ?: 0)
                    if (textCharacters > MAX_TEXT_CHARACTERS) throw guard(PrivacyBlockReason.TRUNCATED_TREE, trace, CaptureDetail.TEXT_LIMIT)
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
                    trace.readPoint(CaptureReadPoint.END_ACTIVE_ROOT)
                    val endpoint = service.rootInActiveWindow ?: throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace, CaptureDetail.END_ROOT_UNAVAILABLE)
                    roots += endpoint
                    refreshPresentationNode(endpoint, capturedAtUptimeMillis, checkpoint, trace)
                    if (endpoint.windowId != token.windowId) throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace, CaptureDetail.END_ROOT_WINDOW_MISMATCH)
                    if (endpoint.packageName?.toString() != packageName) throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace, CaptureDetail.END_ROOT_PACKAGE_MISMATCH)
                    if (endpoint.bounds() != nodes.first().bounds) throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace, CaptureDetail.END_ROOT_BOUNDS_MISMATCH)
                    // The later DISPLAY_REVIEW uses window.root. Refuse differing root sources
                    // before C exists; do not silently rebase the active-root snapshot onto it.
                    trace.readPoint(CaptureReadPoint.END_WINDOW_ROOT)
                    val windowRoot = frameworkTarget.root ?: throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace, CaptureDetail.END_WINDOW_ROOT_UNAVAILABLE)
                    roots += windowRoot
                    refreshPresentationNode(windowRoot, capturedAtUptimeMillis, checkpoint, trace)
                    if (windowRoot.windowId != token.windowId) throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace, CaptureDetail.END_WINDOW_ROOT_ID_MISMATCH)
                    if (windowRoot.packageName?.toString() != packageName) throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace, CaptureDetail.END_WINDOW_ROOT_PACKAGE_MISMATCH)
                    if (windowRoot.bounds() != nodes.first().bounds) throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace, CaptureDetail.END_WINDOW_ROOT_BOUNDS_MISMATCH)
                    trace.readPoint(CaptureReadPoint.END_FRAME)
                    if (geometry?.read()?.frame != screen) throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace, CaptureDetail.END_FRAME_MISMATCH)
                    trace.readPoint(CaptureReadPoint.END_WINDOWS)
                    val endingWindows = service.windows
                    androidWindows += endingWindows
                    if (endingWindows.any { it.displayId != Display.DEFAULT_DISPLAY })
                        throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace, CaptureDetail.END_DISPLAY_MISMATCH)
                    if (endingWindows.map { window ->
                        WindowSnapshot(window.id, windowType(window.type), window.bounds(), window.isActive, window.isFocused, window.layer)
                    } != windows) throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace, CaptureDetail.END_WINDOWS_MISMATCH)
                    trace.readPoint(CaptureReadPoint.END_BUDGET)
                    if (SystemClock.uptimeMillis() - capturedAtUptimeMillis >= MAX_CAPTURE_MILLIS)
                        throw guard(PrivacyBlockReason.TRUNCATED_TREE, trace, CaptureDetail.TIME_LIMIT)
                    checkpoint()
                }
                trace.readPoint(CaptureReadPoint.RESULT)
                CollectionResult.Ready(snapshot, DetectionFrame(
                    digest(packageName), root.windowId,
                    windows.filter { it.type == WindowType.APPLICATION }.mapTo(mutableSetOf()) { it.id },
                    structureDigest, screen, outlines.toList(), trigger, targetWindow.bounds,
                ), nativeRows?.let { NativeNodeActions.forSnapshotSummaries(snapshot, it) })
            } }, cleanup = { trace.readRelease(CaptureReadPoint.RELEASE_BATCH) {
                val batch = CollectionReleaseBatch()
                val seen = java.util.IdentityHashMap<Any, Boolean>()
                roots.forEach { if (seen.put(it, true) == null) batch.attempt { trace.readRelease(CaptureReadPoint.ROOT_RELEASE) { it.release() } } }
                androidWindows.forEach { if (seen.put(it, true) == null) batch.attempt { trace.readRelease(CaptureReadPoint.WINDOW_RELEASE) { it.release() } } }
                batch.finish()
                trace.readReleased()
            } }, cleanupFailure = { outcome ->
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
    private fun refreshPresentationNode(node: AccessibilityNodeInfo, started: Long, checkpoint: () -> Unit, trace: CaptureTrace) {
        if (!refreshPresentationNodeRead(started, SystemClock::uptimeMillis, checkpoint,
                { node.windowId }, node::refresh, trace::readRejected)) throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace)
    }

    private fun inspect(node: AccessibilityNodeInfo, expectedPackage: String, trace: CaptureTrace) {
        val packageName = node.packageName?.toString()
        if (packageName != null && packageName != expectedPackage) {
            throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace, CaptureDetail.NODE_PACKAGE_MISMATCH)
        }
        PrivacyPolicy.fieldBlock(node.isPassword, node.isEditable, node.inputType)?.let { throw guard(it, trace) }
        fun check(value: CharSequence?) {
            if ((value?.length ?: 0) > MAX_NODE_TEXT_CHARACTERS) throw guard(PrivacyBlockReason.TRUNCATED_TREE, trace, CaptureDetail.TEXT_LIMIT)
            if (PrivacyPolicy.hasSensitiveText(value) || PrivacyPolicy.hasUncertainText(value)) throw guard(PrivacyBlockReason.SENSITIVE_CONTENT, trace)
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
        trace: CaptureTrace,
        beforeRead: (AccessibilityNodeInfo) -> Unit = {},
        visitor: (AccessibilityNodeInfo, String) -> Unit,
    ) {
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
            budget.visit(depth)
            beforeRead(node)
            visitor(node, path)
            val count = node.childCount
            if (count > MAX_NODES) throw guard(PrivacyBlockReason.TRUNCATED_TREE, trace, CaptureDetail.NODE_LIMIT)
            for (index in 0 until count) {
                val child = node.getChild(index) ?: throw guard(PrivacyBlockReason.UNREADABLE_TREE, trace, CaptureDetail.CHILD_UNAVAILABLE)
                CollectionResourceSafety.run(body = { trace.readBody { visit(child, "$path/$index", depth + 1) } },
                    cleanup = { trace.readRelease(CaptureReadPoint.CHILD_RELEASE) { child.release() } }, cleanupFailure = { throw CollectionCleanupFailure() })
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

    private inner class TraversalBudget(private val trace: CaptureTrace) {
        private var count = 0
        private val deadline = SystemClock.uptimeMillis() + MAX_CAPTURE_MILLIS
        fun visit(depth: Int) {
            if (++count > MAX_NODES * 2) throw guard(PrivacyBlockReason.TRUNCATED_TREE, trace, CaptureDetail.NODE_LIMIT)
            if (depth > MAX_DEPTH) throw guard(PrivacyBlockReason.TRUNCATED_TREE, trace, CaptureDetail.DEPTH_LIMIT)
            if (SystemClock.uptimeMillis() > deadline) throw guard(PrivacyBlockReason.TRUNCATED_TREE, trace, CaptureDetail.TIME_LIMIT)
        }
    }

    private fun guard(reason: PrivacyBlockReason, trace: CaptureTrace,
        detail: CaptureDetail = CaptureDetail.TREE_PROTECTION): GuardFailure {
        // Record at the failed branch, before child/root cleanup can fail as well.
        trace.readRejected(detail)
        return GuardFailure(reason)
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
