package kr.closeforyou.accessibility.action

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CancellationException
import kr.closeforyou.accessibility.AndroidScreenGeometry
import kr.closeforyou.accessibility.PrivacyPolicy
import kr.closeforyou.core.model.*

internal enum class F9ReadStage { MAIN, GEOMETRY, WINDOWS, ACTIVE_WINDOW, FOCUSED_WINDOW, OVERLAY_WINDOW,
    APP_COUNT, BACKGROUND_WINDOW, WINDOW_BOUNDS, OCCLUSION, ROOT, TREE, ENDPOINT, TARGET, TIME_LIMIT, CLEANUP, COMPLETE }

/** A owns every acquired framework handle. Supports only an unambiguous separate application-window
 * popup over an unchanged page. Same-window/WebView/canvas/visual targets fail closed for now.
 * No screenshot, OCR, Back, gesture, dismiss action or parent-click fallback exists in this port.
 */
internal class AndroidF9ScreenPort(
    context: Context,
    private val activeRoot: () -> AccessibilityNodeInfo?,
    private val windowSource: () -> List<AccessibilityWindowInfo>,
    private val onRejected: (F9ReadStage) -> Unit = {},
    private val packageAllowed: (String) -> Boolean,
) : F9ScreenPort {
    constructor(service: AccessibilityService, onRejected: (F9ReadStage) -> Unit = {}, packageAllowed: (String) -> Boolean) :
        this(service, { service.rootInActiveWindow }, { service.windows }, onRejected, packageAllowed)
    private val geometry = AndroidScreenGeometry(context)
    private val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private var healthy = true

    override fun read(checkpoint: () -> Unit): F9Scene? = scan(checkpoint, null) { scene, _ -> scene }.takeIf { healthy }

    override fun press(reference: F9Reference, accept: (F9Scene) -> Boolean): F9Press {
        var attempted = false
        var returned: Boolean? = null
        val once = AtomicBoolean()
        try {
            scan({}, reference.candidate.nodePath) { scene, target ->
                if (target != null && accept(scene) && once.compareAndSet(false, true)) {
                    // accept's cancellation/currentness/TTL checks are synchronous on this main thread.
                    attempted = true // Set BEFORE the framework call: even a throwing call may act.
                    returned = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
        } catch (cancelled: CancellationException) {
            if (!attempted) throw cancelled
        } catch (_: Exception) { /* An invoked action is never retried or reported as unattempted. */ }
        return F9Press(attempted, returned)
    }

    @Suppress("DEPRECATION")
    private fun <T> scan(checkpoint: () -> Unit, targetPath: String?, consume: (F9Scene, AccessibilityNodeInfo?) -> T): T? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "F9_MAIN_REQUIRED" }
        if (!healthy) return null
        val started = SystemClock.uptimeMillis()
        var stage = F9ReadStage.MAIN
        var completed = false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val ownedWindows = mutableListOf<AccessibilityWindowInfo>()
        val identities = IdentityHashMap<AccessibilityNodeInfo, Boolean>()
        fun tick() {
            checkpoint()
            if (SystemClock.uptimeMillis() - started >= 100L) {
                stage = F9ReadStage.TIME_LIMIT
                error("F9_F0_TIME_LIMIT")
            }
        }
        fun own(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
            check(identities.put(node, true) == null) { "F9_NODE_ALIAS" }
            nodes += node
            return node
        }
        try {
            tick()
            stage = F9ReadStage.GEOMETRY
            val frame = geometry.read().frame ?: return null
            stage = F9ReadStage.WINDOWS
            val windows = windowSource(); ownedWindows += windows
            check(windows.size in 1..32 && windows.all { it.displayId == 0 }) { "F9_WINDOW_SCOPE" }
            stage = F9ReadStage.ACTIVE_WINDOW
            val active = windows.singleOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return null
            stage = F9ReadStage.FOCUSED_WINDOW
            if (!active.isFocused) return null
            stage = F9ReadStage.OVERLAY_WINDOW
            if (windows.any { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                    it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD || (it.id != active.id && (it.isActive || it.isFocused)) }) return null
            stage = F9ReadStage.APP_COUNT
            val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            if (apps.size !in 1..2) return null
            stage = F9ReadStage.BACKGROUND_WINDOW
            val base = if (apps.size == 2) apps.single { it.id != active.id } else active
            if (base !== active && (base.layer >= active.layer || !F9AutoCloser.contains(rect(base), rect(active)))) return null
            val activeBounds = rect(active)
            stage = F9ReadStage.WINDOW_BOUNDS
            if (!F9AutoCloser.contains(frame, activeBounds)) return null
            stage = F9ReadStage.OCCLUSION
            if (windows.any { other ->
                    other.id != active.id && other.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                        other.layer >= active.layer && overlaps(rect(other), activeBounds) &&
                        !(targetPath == null && activeBounds == frame && passiveSystemBar(other, frame, ::own))
                }) return null
            stage = F9ReadStage.ROOT
            val root = own(activeRoot() ?: return null)
            val packageName = root.packageName?.toString() ?: return null
            if (root.windowId != active.id) return null
            if (F9ContentSafety.protectedPackage(packageName) || !packageAllowed(packageName)) {
                // Identity-only departure evidence. Never read protected/unconsented app text.
                tick()
                return consume(F9Scene(packageName, active.id, base.id, frame, windows.map(::window),
                    emptyList(), emptySet(), complete = false), null).also { completed = true }
            }
            val dtos = windows.map(::window)
            var target: AccessibilityNodeInfo? = null
            var textCount = 0
            var nodeCount = 0
            fun tree(start: AccessibilityNodeInfo, captureTarget: Boolean): Pair<List<NodeSnapshot>, Set<String>> {
                val result = mutableListOf<NodeSnapshot>()
                val visible = mutableSetOf<String>()
                fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
                    tick()
                    check(++nodeCount <= 1_200 && depth <= 60 && node.packageName?.toString() == packageName &&
                        node.windowId == start.windowId) { "F9_TREE_SCOPE" }
                    check(PrivacyPolicy.fieldBlock(node.isPassword, node.isEditable, node.inputType) == null) { "F9_PROTECTED_FIELD" }
                    fun freeze(value: CharSequence?): String? {
                        tick()
                        if (value == null) return null
                        val size = value.length
                        check(size <= 4_096) { "F9_TEXT_LIMIT" }
                        textCount += size; check(textCount <= 32_768) { "F9_TEXT_LIMIT" }
                        val frozen = value.toString()
                        check(frozen.length == size && !F9ContentSafety.blocked(frozen)) { "F9_PROTECTED_TEXT" }
                        return frozen
                    }
                    val text = freeze(node.text); val description = freeze(node.contentDescription)
                    val viewId = freeze(node.viewIdResourceName); val className = freeze(node.className)
                    listOf(node.error, node.hintText, node.paneTitle, node.tooltipText, node.stateDescription).forEach(::freeze)
                    val actions = node.actionList
                    check(actions.size <= 64) { "F9_ACTION_LIMIT" }
                    val descriptor = NodeSnapshot(path, viewId, className, text, description, node.isClickable,
                        node.isEnabled, rect(node), buildSet {
                            if (actions.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) add(NodeAction.CLICK)
                            if (actions.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }) add(NodeAction.DISMISS)
                        }.toPersistentSet())
                    result += descriptor
                    if (node.isVisibleToUser) visible += path
                    if (captureTarget && path == targetPath) target = node
                    val count = node.childCount
                    check(count in 0..1_200) { "F9_CHILD_LIMIT" }
                    for (i in 0 until count) visit(own(node.getChild(i) ?: error("F9_MISSING_CHILD")), "$path/$i", depth + 1)
                    check(count == node.childCount) { "F9_CHANGED_CHILDREN" }
                    tick()
                }
                visit(start, "0", 0)
                return result to visible
            }
            stage = F9ReadStage.TREE
            val (descriptors, visible) = tree(root, true)
            val baseNodes = if (base === active) descriptors else {
                val baseRoot = own(base.root ?: return null)
                if (baseRoot.windowId != base.id) return null
                tree(baseRoot, false).first
            }
            val digest = MessageDigest.getInstance("SHA-256").apply { update(salt) }
            baseNodes.forEach { n ->
                listOf(n.path, n.viewId, n.className, n.text, n.contentDescription, n.bounds.toString(),
                    n.clickable.toString(), n.enabled.toString(), n.actions.toString()).forEach { field ->
                    val bytes = field?.toByteArray(Charsets.UTF_8)
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
                    if (bytes != null) digest.update(bytes)
                }
            }
            val pageDigest = digest.digest().joinToString("") { "%02x".format(it) }
            stage = F9ReadStage.ENDPOINT
            val currentWindows = windowSource(); ownedWindows += currentWindows
            if (currentWindows.map(::window) != dtos || geometry.read().frame != frame) return null
            val endRoot = activeRoot() ?: return null
            if (identities[endRoot] == null) own(endRoot)
            if (endRoot.windowId != active.id || endRoot.packageName?.toString() != packageName) return null
            // refresh is supplementary, never the whole-screen safety proof.
            stage = F9ReadStage.TARGET
            target?.let { n ->
                val expected = descriptors.single { it.path == targetPath }
                if (!n.refresh() || n.packageName?.toString() != packageName || n.windowId != active.id ||
                    n.text?.toString() != expected.text || n.contentDescription?.toString() != expected.contentDescription ||
                    n.viewIdResourceName != expected.viewId || n.className?.toString() != expected.className ||
                    rect(n) != expected.bounds || !n.isVisibleToUser || !n.isEnabled || !n.isClickable ||
                    n.isPassword || n.isEditable || n.inputType != 0 ||
                    n.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return null
                val refreshedActions = n.actionList
                if (refreshedActions.size > 64 || buildSet {
                        if (refreshedActions.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) add(NodeAction.CLICK)
                        if (refreshedActions.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }) add(NodeAction.DISMISS)
                    } != expected.actions || listOf(n.error, n.hintText, n.paneTitle, n.tooltipText, n.stateDescription)
                        .filterNotNull().any { it.length > 4_096 || F9ContentSafety.blocked(it) }) return null
            }
            tick()
            stage = F9ReadStage.COMPLETE
            return consume(F9Scene(packageName, active.id, base.id, frame, dtos, descriptors, visible, true, pageDigest), target)
                .also { completed = true }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
        finally {
            nodes.asReversed().forEach { try { it.recycle() } catch (_: Exception) { healthy = false } }
            val seen = IdentityHashMap<AccessibilityWindowInfo, Boolean>()
            ownedWindows.forEach { if (seen.put(it, true) == null) try { it.recycle() } catch (_: Exception) { healthy = false } }
            if (!completed || !healthy) try { onRejected(if (!healthy) F9ReadStage.CLEANUP else stage) } catch (_: Exception) { /* Diagnostic code only. */ }
        }
    }
    private fun rect(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen).let { ScreenBounds(it.left, it.top, it.right, it.bottom) }
    private fun rect(window: AccessibilityWindowInfo) = Rect().also(window::getBoundsInScreen).let { ScreenBounds(it.left, it.top, it.right, it.bottom) }
    private fun window(w: AccessibilityWindowInfo) = WindowSnapshot(w.id, when (w.type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> WindowType.APPLICATION
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowType.ACCESSIBILITY_OVERLAY
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowType.INPUT_METHOD
        AccessibilityWindowInfo.TYPE_SYSTEM -> WindowType.SYSTEM
        else -> WindowType.OTHER
    }, rect(w), w.isActive, w.isFocused, w.layer)
    private fun overlaps(a: ScreenBounds, b: ScreenBounds) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
    private fun passiveSystemBar(window: AccessibilityWindowInfo, frame: ScreenBounds,
        own: (AccessibilityNodeInfo) -> AccessibilityNodeInfo): Boolean {
        if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM || window.isActive || window.isFocused) return false
        val bar = rect(window)
        if (bar.left != frame.left || bar.right != frame.right || bar.height !in 1..(frame.height / 8) ||
            (bar.top != frame.top && bar.bottom != frame.bottom)) return false
        // API 37 exposes null titles for SystemUI bars. Verify owner metadata and narrow edge
        // geometry instead of trusting a label. Never traverse or read SystemUI notification text.
        val root = own(window.root ?: return false)
        return root.packageName?.toString() == "com.android.systemui" && root.windowId == window.id
    }
    override fun toString() = "AndroidF9ScreenPort(redacted)"
}
