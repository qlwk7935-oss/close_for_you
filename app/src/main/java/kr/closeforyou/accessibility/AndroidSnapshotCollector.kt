package kr.closeforyou.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.os.Build
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
import java.util.concurrent.CancellationException

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
        checkActive: () -> Unit = {},
    ): CollectionResult {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        // Separate release ownership from the protection scope fixed during enumeration.
        val rootGuards = mutableListOf<Triple<AccessibilityNodeInfo, ProtectionRootGuard, ProtectionRootTicket?>>()
        val protectionObservation = ObservedProtectionPass()
        val androidWindows = mutableListOf<AccessibilityWindowInfo>()
        var targetPackage: String? = null
        var targetId = -1
        var phase = CollectionPhase.ROOT
        var tree = CollectionTree.NONE
        var frameStatus = ScreenFrameStatus.UNAVAILABLE
        var windowAge = WindowBoundsAge.UNOBSERVED
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
                    progress.failure?.exception ?: exception?.let(CollectionProgress::classify) ?: CollectionExceptionKind.NONE,
                    failure?.nodeBounds ?: NodeBoundsDiagnostic(), windowAge, trigger))
        }
        fun collect(): CollectionResult {
            return try {
                checkActive()
                val root = progress.at(CollectionOperation.ROOT_READ) { service.rootInActiveWindow }
                    ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.ROOT_UNAVAILABLE)
                roots += root
                val packageName = progress.at(CollectionOperation.ROOT_METADATA) { root.packageName?.toString() }
                    ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.ROOT_PACKAGE_UNAVAILABLE)
                targetPackage = packageName
                targetId = progress.at(CollectionOperation.ROOT_METADATA) { root.windowId }
                rootGuards += Triple(root, ProtectionRootGuard(packageName, packageName, targetId),
                    protectionObservation.registerRoot(targetId, ProtectionRootRole.TARGET))
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
                // Only previously read DTO scalars; no additional framework getters or tree traversal.
                windows.forEach { if (it.type == WindowType.APPLICATION) protectionObservation.observeWindow(it.id) }
                val targetWindow = windows.firstOrNull { it.id == targetId }
                    ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.TARGET_MISSING)
                trace.target(targetWindow.type)
                val frameworkTarget = androidWindows.single { it.id == targetId }
                val targetDisplayId = frameworkTarget.displayId
                if (targetDisplayId != Display.DEFAULT_DISPLAY) {
                    return blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.SECONDARY_DISPLAY)
                }
                windowAge = if (Build.VERSION.SDK_INT >= 34) {
                    try { WindowBoundsTiming.classify(frameworkTarget.transitionTimeMillis, SystemClock.uptimeMillis()) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { WindowBoundsAge.READ_FAILED } // Observation only; no retry or acceptance rule.
                } else WindowBoundsAge.UNSUPPORTED
                trace.syntheticWindow { frameworkTarget.title }
                if (targetWindow.type != WindowType.APPLICATION) {
                    return blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.TARGET_NOT_APPLICATION)
                }
                windows.firstOrNull { (it.active || it.focused) && it.type != WindowType.APPLICATION }?.let {
                    return blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.NON_APPLICATION_FOCUS, it.type)
                }

                for (window in androidWindows) {
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || window.id == targetId) continue
                    val other = progress.at(CollectionOperation.WINDOW_ROOT_READ) { window.root }
                        ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.WINDOW_LIST_UNAVAILABLE)
                    roots += other
                    val otherPackage = other.packageName?.toString()
                        ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.WINDOW_LIST_UNAVAILABLE)
                    if (otherPackage != packageName && (requireCaptureIsolation || window.isActive || window.isFocused)) {
                        return blocked(PrivacyBlockReason.SYSTEM_WINDOW, CaptureDetail.FOREIGN_APPLICATION, WindowType.APPLICATION)
                    }
                    val registeredWindowId = window.id // Same getter, at the original registration point.
                    rootGuards += Triple(other, ProtectionRootGuard(otherPackage, packageName, registeredWindowId),
                        protectionObservation.registerRoot(registeredWindowId, if (otherPackage == packageName)
                            ProtectionRootRole.SAME_APP_BACKGROUND else ProtectionRootRole.EXCLUDED_FOREIGN))
                }
                protectionObservation.freezeScope()

                phase = CollectionPhase.GEOMETRY
                val geometryRead = progress.at(CollectionOperation.SCREEN_GEOMETRY) {
                    (geometry ?: AndroidScreenGeometry(service).also { geometry = it }).read()
                }
                frameStatus = geometryRead.status
                trace.frameStatus(geometryRead.status)
                val screen = geometryRead.frame ?: return blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.DISPLAY_GEOMETRY_UNCERTAIN)
                progress.at(CollectionOperation.COORDINATE_CHECK) { trace.coordinates(targetWindow.bounds, screen, root.bounds()) {
                    try { service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.toScreenBounds() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                } }
                val budget = TraversalBudget {
                    checkActive()
                    packageGate(packageName)?.let { throw CollectionGuardFailure(it, finding = CollectionFinding.PACKAGE_GUARD) }
                }
                val consistency = progress.at(CollectionOperation.COORDINATE_CHECK) {
                    CollectionWindowFrameCheck.begin(CollectionWindowFrameSample(packageName, targetId,
                        targetDisplayId, screen, geometryRead.status, geometryRead.rotation, windows))
                }
                phase = CollectionPhase.PROTECTION_PASS
                // Inspect all trees of this application, including its obscured background window.
                for ((candidateRoot, guard, ticket) in rootGuards) {
                    tree = if (candidateRoot === root) CollectionTree.TARGET else CollectionTree.SAME_APP_BACKGROUND
                    guard.inspect(progress, { candidateRoot.packageName?.toString() }, { candidateRoot.windowId }) {
                        walk(candidateRoot, budget, progress) { node, path, _ ->
                            inspect(node, packageName, progress, isRoot = path == "0"); CollectionNodeVisit(false)
                        }
                        // Only a real protection walk returning normally records this root.
                        // An excluded foreign guard never invokes this callback.
                        protectionObservation.traversalReturned(ticket)
                    }
                }
                protectionObservation.finishPass()

                phase = CollectionPhase.SNAPSHOT_PASS
                tree = CollectionTree.TARGET
                val nodes = mutableListOf<NodeSnapshot>()
                val outlines = mutableListOf<DetectionOutline>()
                val structure = progress.at(CollectionOperation.DIGEST_INITIALIZE) { MessageDigest.getInstance("SHA-256").apply { update(salt) } }
                val content = progress.at(CollectionOperation.DIGEST_INITIALIZE) { MessageDigest.getInstance("SHA-256").apply { update(salt) } }
                var textCharacters = 0
                walk(root, budget, progress) { node, path, underScrollAncestor ->
                    val checked = CheckedNodeRead.read(AndroidCheckedNodeSource(node), packageName,
                        isRoot = path == "0", progress, MAX_NODE_TEXT_CHARACTERS)
                    val text = checked.text
                    val description = checked.contentDescription
                    progress.at(CollectionOperation.TEXT_BUDGET) {
                        textCharacters += (text?.length ?: 0) + (description?.length ?: 0)
                        if (textCharacters > MAX_TEXT_CHARACTERS) throw CollectionGuardFailure(PrivacyBlockReason.TRUNCATED_TREE,
                            finding = CollectionFinding.TOTAL_TEXT_LIMIT)
                    }
                    val rect = progress.at(CollectionOperation.NODE_BOUNDS_READ) { Rect().also(node::getBoundsInScreen) }
                    val bounds = progress.at(CollectionOperation.NODE_BOUNDS_VALIDATE) {
                        NodeBoundsPolicy.convert(rect.left, rect.top, rect.right, rect.bottom) { shape ->
                            val visibility = try { if (node.isVisibleToUser) NodeVisibility.VISIBLE else NodeVisibility.NOT_VISIBLE }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { NodeVisibility.READ_FAILED }
                            NodeBoundsDiagnostic(shape, visibility,
                                if (underScrollAncestor) NodeAncestry.SCROLL_ANCESTOR else NodeAncestry.NO_SCROLL_ANCESTOR,
                                if (path == "0") NodeRole.ROOT else NodeRole.DESCENDANT)
                        }
                    }
                    val actionIds = progress.at(CollectionOperation.NODE_ACTION_READ) { node.actionList.map { it.id } }
                    val actions = progress.at(CollectionOperation.NODE_ACTIONS_DTO) {
                        buildSet {
                            if (AccessibilityNodeInfo.ACTION_CLICK in actionIds) add(NodeAction.CLICK)
                            if (AccessibilityNodeInfo.ACTION_DISMISS in actionIds) add(NodeAction.DISMISS)
                        }.toPersistentSet()
                    }
                    // Read Android properties before DTO constructors: a getter failure is not a DTO failure.
                    val clickable = progress.at(CollectionOperation.NODE_PROPERTIES_READ) { node.isClickable }
                    val enabled = progress.at(CollectionOperation.NODE_PROPERTIES_READ) { node.isEnabled }
                    val snapshot = progress.at(CollectionOperation.NODE_DTO) {
                        checked.snapshot(path, clickable, enabled, bounds, actions).also { nodes += it }
                    }
                    val childCount = progress.at(CollectionOperation.CHILD_COUNT_READ) { node.childCount }
                    val structuralFields = progress.at(CollectionOperation.STRUCTURE_FIELDS) {
                        listOf(path, snapshot.viewId, snapshot.className, bounds.left, bounds.top, bounds.right,
                            bounds.bottom, snapshot.clickable, snapshot.enabled, childCount, actions.joinToString(","))
                    }
                    progress.at(CollectionOperation.STRUCTURE_HASH) { structuralFields.forEach { structure.field(it?.toString()) } }
                    progress.at(CollectionOperation.CONTENT_HASH) { content.field(path); content.field(text); content.field(description) }
                    val fingerprint = progress.at(CollectionOperation.OUTLINE_HASH) { digest(path, snapshot.viewId, snapshot.className) }
                    val outlineChildCount = progress.at(CollectionOperation.CHILD_COUNT_READ) {
                        node.childCount.also { CollectionChildCountCheck.requireCount(it, childCount) }
                    }
                    val visible = progress.at(CollectionOperation.OUTLINE_PROPERTIES_READ) { node.isVisibleToUser }
                    val scrollable = progress.at(CollectionOperation.OUTLINE_PROPERTIES_READ) { node.isScrollable }
                    progress.at(CollectionOperation.OUTLINE_DTO) {
                        outlines += checked.outline(fingerprint, bounds, outlineChildCount, actions, path, visible, scrollable)
                    }
                    CollectionNodeVisit(scrollable, childCount) // Context only; protection inspects every node.
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
                        structureDigest, screen, outlines.toList(), trigger, targetWindow.bounds, windowAge)
                }
                // One bounded endpoint observation only. Never replace the first DTOs/frame,
                // retry capture, or traverse/protect a different root as a fallback.
                progress.at(CollectionOperation.COORDINATE_CHECK) {
                    consistency.finish(windowId, checkBudget = {
                        progress.at(CollectionOperation.TREE_BUDGET) { budget.checkTime() }
                    }, read = {
                        phase = CollectionPhase.WINDOWS
                        val completedRoot = progress.at(CollectionOperation.ROOT_READ) { service.rootInActiveWindow }
                            ?: throw CollectionGuardFailure(PrivacyBlockReason.UNREADABLE_TREE,
                                finding = CollectionFinding.ROOT_GUARD)
                        // Framework may return the same object instance: own/release it once.
                        if (roots.none { it === completedRoot }) roots += completedRoot
                        val completedPackage = progress.at(CollectionOperation.ROOT_METADATA) {
                            completedRoot.packageName?.toString()
                        }
                        ProtectionRootGuard.requirePackage(completedPackage, packageName)
                        val completedId = progress.at(CollectionOperation.ROOT_METADATA) { completedRoot.windowId }
                        if (completedId != targetId) throw CollectionGuardFailure(PrivacyBlockReason.UNREADABLE_TREE,
                            finding = CollectionFinding.ROOT_WINDOW_MISMATCH)
                        packageGate(packageName)?.let { throw CollectionGuardFailure(it,
                            finding = CollectionFinding.PACKAGE_GUARD) }
                        val completedWindows = progress.at(CollectionOperation.WINDOWS_READ) { service.windows }
                        completedWindows.forEach { window ->
                            if (androidWindows.none { it === window }) androidWindows += window
                        }
                        if (completedWindows.isEmpty() || completedWindows.size > MAX_WINDOWS)
                            throw CollectionGuardFailure(PrivacyBlockReason.UNREADABLE_TREE,
                                finding = CollectionFinding.WINDOW_GUARD)
                        CollectionWindowFrameCheck.requireCaptureDisplayIsolation(requireCaptureIsolation) {
                            completedWindows.any { it.displayId != Display.DEFAULT_DISPLAY }
                        }
                        val completedTarget = completedWindows.singleOrNull { it.id == targetId }
                            ?: throw CollectionGuardFailure(PrivacyBlockReason.UNREADABLE_TREE,
                                finding = CollectionFinding.WINDOW_GUARD)
                        val completedDisplay = progress.at(CollectionOperation.WINDOW_METADATA) { completedTarget.displayId }
                        val completedDtos = completedWindows.map { window ->
                            progress.at(CollectionOperation.WINDOW_METADATA) {
                                WindowSnapshot(window.id, windowType(window.type), window.bounds(),
                                    window.isActive, window.isFocused, window.layer)
                            }
                        }
                        phase = CollectionPhase.GEOMETRY
                        val completedGeometry = progress.at(CollectionOperation.SCREEN_GEOMETRY) {
                            requireNotNull(geometry).read()
                        }
                        frameStatus = completedGeometry.status
                        CollectionWindowFrameSample(completedPackage, completedId, completedDisplay,
                            completedGeometry.frame, completedGeometry.status, completedGeometry.rotation, completedDtos)
                    })
                }
                progress.at(CollectionOperation.TREE_BUDGET) { budget.checkTime() }
                checkActive()
                CollectionResult.Ready(snapshot, frame)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CollectionGuardFailure) {
                blocked(failure.reason, CaptureDetail.TREE_PROTECTION, failure = failure)
            } catch (exception: Exception) {
                // This may be our code or a getter. Classify the boundary/type, never the message or stack.
                blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.COLLECTION_FAILED, exception = exception)
            }
        }
        val result = CollectionResourceSafety.run(::collect, cleanup = {
            // Discard before root/window release; attempt every ordinary cleanup independently.
            val releases = CollectionReleaseBatch()
            try {
                releases.attempt { protectionObservation.discard() }
                roots.forEach { node -> releases.attempt { node.release() } }
                androidWindows.forEach { window -> releases.attempt { window.release() } }
                releases.finish()
            } finally {
                rootGuards.clear()
                roots.clear()
                androidWindows.clear()
            }
        }, cleanupFailure = { result ->
            // Preserve the original Blocked reason/location. A failed cleanup cannot publish Ready.
            if (result is CollectionResult.Blocked) result
            else blocked(PrivacyBlockReason.UNREADABLE_TREE, CaptureDetail.COLLECTION_FAILED,
                exception = CollectionCleanupFailure())
        })
        checkActive()
        return result
    }

    private enum class ProtectionRootRole { TARGET, SAME_APP_BACKGROUND, EXCLUDED_FOREIGN }

    /** Empty, identity-only tickets. Never carry a node/window, text, coordinates or token. */
    private class ProtectionRootTicket {
        override fun toString(): String = "ProtectionRootTicket(redacted)"
    }
    private class ObservedF0Completion {
        override fun toString(): String = "ObservedF0Completion(redacted)"
    }

    /** Capture-local bookkeeping of OBSERVED traversal returns only, NOT whole-screen F0 proof.
     * No external factory, synthetic input, timer, callback or Android object is accepted/retained.
     * Recording rejection discards THIS observation only; it cannot change Ready/Blocked or gates.
     * Sole-thread owner: created/used/discarded synchronously inside capture, never published.
     */
    private class ObservedProtectionPass {
        private enum class State { REGISTERING, SCOPE_FIXED, PASS_RECORDED, DISCARDED }
        private class Entry(val windowId: Int, val role: ProtectionRootRole,
            val ticket: ProtectionRootTicket, var returned: Boolean = false) {
            override fun toString(): String = "ProtectionEntry(redacted)"
        }
        private var state = State.REGISTERING
        private val observedWindows = mutableSetOf<Int>()
        private val entries = mutableListOf<Entry>()
        private var completion: ObservedF0Completion? = null

        fun observeWindow(windowId: Int) {
            if (state != State.REGISTERING || observedWindows.size >= MAX_WINDOWS ||
                !observedWindows.add(windowId)) discard()
        }

        fun registerRoot(windowId: Int, role: ProtectionRootRole): ProtectionRootTicket? {
            if (state != State.REGISTERING || entries.size >= MAX_WINDOWS ||
                entries.any { it.windowId == windowId }) {
                discard(); return null
            }
            return ProtectionRootTicket().also { entries += Entry(windowId, role, it) }
        }

        fun freezeScope() {
            if (state != State.REGISTERING || observedWindows.isEmpty() ||
                entries.count { it.role == ProtectionRootRole.TARGET } != 1 ||
                entries.mapTo(mutableSetOf()) { it.windowId } != observedWindows) {
                discard(); return
            }
            state = State.SCOPE_FIXED
        }

        fun traversalReturned(ticket: ProtectionRootTicket?) {
            if (state != State.SCOPE_FIXED) { discard(); return }
            val entry = entries.singleOrNull { it.ticket === ticket }
            if (entry == null || entry.role == ProtectionRootRole.EXCLUDED_FOREIGN || entry.returned) {
                discard(); return
            }
            entry.returned = true
        }

        fun finishPass() {
            if (state != State.SCOPE_FIXED ||
                entries.any { it.role != ProtectionRootRole.EXCLUDED_FOREIGN && !it.returned }) {
                discard(); return
            }
            completion = ObservedF0Completion()
            state = State.PASS_RECORDED
        }

        fun discard() {
            completion = null
            entries.clear()
            observedWindows.clear()
            state = State.DISCARDED
        }

        override fun toString(): String = "ObservedProtectionPass(redacted)"
    }

    private fun inspect(node: AccessibilityNodeInfo, expectedPackage: String, progress: CollectionProgress, isRoot: Boolean) {
        CheckedNodeRead.read(AndroidCheckedNodeSource(node), expectedPackage, isRoot, progress, MAX_NODE_TEXT_CHARACTERS)
    }

    /** One visitor call only; never stored in CheckedNodeRead, a DTO, or any completion record. */
    private class AndroidCheckedNodeSource(private val node: AccessibilityNodeInfo) : CheckedNodeRead.Source {
        override fun packageName(): String? = node.packageName?.toString()
        override fun password(): Boolean = node.isPassword
        override fun editable(): Boolean = node.isEditable
        override fun inputType(): Int = node.inputType
        override fun value(field: CollectionField): CharSequence? = when (field) {
            CollectionField.TEXT -> node.text
            CollectionField.CONTENT_DESCRIPTION -> node.contentDescription
            CollectionField.VIEW_ID -> node.viewIdResourceName
            CollectionField.CLASS_NAME -> node.className
            CollectionField.ERROR -> node.error
            CollectionField.HINT -> node.hintText
            CollectionField.PANE_TITLE -> node.paneTitle
            CollectionField.TOOLTIP -> node.tooltipText
            CollectionField.STATE_DESCRIPTION -> node.stateDescription
            else -> throw IllegalArgumentException("CHECKED_NODE_FIELD_INVALID")
        }
        override fun toString(): String = "AndroidCheckedNodeSource(redacted)"
    }

    private fun walk(
        root: AccessibilityNodeInfo,
        budget: TraversalBudget,
        progress: CollectionProgress,
        visitor: (AccessibilityNodeInfo, String, Boolean) -> CollectionNodeVisit,
    ) {
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int, underScrollAncestor: Boolean) {
            progress.at(CollectionOperation.TREE_BUDGET) { budget.visit(depth) }
            val record = visitor(node, path, underScrollAncestor)
            val scrollable = record.scrollable
            val count = progress.at(CollectionOperation.CHILD_COUNT_READ) {
                node.childCount.also { CollectionChildCountCheck.requireCount(it, record.childCount) }
            }
            for (index in 0 until count) {
                val child = progress.at(CollectionOperation.CHILD_READ) {
                    node.getChild(index) ?: throw CollectionGuardFailure(PrivacyBlockReason.UNREADABLE_TREE,
                        finding = CollectionFinding.MISSING_CHILD)
                }
                CollectionResourceSafety.visitChild(progress,
                    visit = { visit(child, "$path/$index", depth + 1, underScrollAncestor || scrollable) },
                    release = { child.release() },
                )
            }
            progress.at(CollectionOperation.TREE_BUDGET) { budget.checkTime() }
            progress.at(CollectionOperation.CHILD_COUNT_READ) {
                CollectionChildCountCheck.requireCount(node.childCount, count)
            }
        }
        visit(root, "0", 0, false)
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

    private class TraversalBudget(private val checkActive: () -> Unit) {
        private var count = 0
        private val deadline = SystemClock.uptimeMillis() + MAX_CAPTURE_MILLIS
        fun visit(depth: Int) {
            // Preserve original short-circuit order/thresholds, labeling only the first rejection.
            if (++count > MAX_NODES * 2)
                throw CollectionGuardFailure(PrivacyBlockReason.TRUNCATED_TREE, finding = CollectionFinding.NODE_COUNT_LIMIT)
            if (depth > MAX_DEPTH)
                throw CollectionGuardFailure(PrivacyBlockReason.TRUNCATED_TREE, finding = CollectionFinding.DEPTH_LIMIT)
            checkTime()
        }
        fun checkTime() {
            checkActive()
            CollectionWindowFrameCheck.checkDeadline(SystemClock.uptimeMillis(), deadline)
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
