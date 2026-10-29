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
import kr.closeforyou.accessibility.integration.ManualDisplayContinuity
import kr.closeforyou.accessibility.integration.ManualDisplayVerdict
import kr.closeforyou.accessibility.integration.ManualDisplayProbe
import kr.closeforyou.core.model.*

/** A owns every acquired framework handle. Supports only an unambiguous separate application-window
 * popup over an unchanged page. Same-window/WebView/canvas/visual targets fail closed for now.
 * No screenshot, OCR, Back, gesture, dismiss action or parent-click fallback exists in this port.
 */
internal class AndroidF9ScreenPort(
    context: Context,
    private val activeRoot: () -> AccessibilityNodeInfo?,
    private val windowSource: () -> List<AccessibilityWindowInfo>,
    private val onRejected: (F9ReadStage) -> Unit = {},
    private val onScanReport: (F9ReadCode) -> Unit = {},
    private val packageAllowed: (String) -> Boolean,
) : F9ScreenPort {
    constructor(service: AccessibilityService, onScanReport: (F9ReadCode) -> Unit = {}, packageAllowed: (String) -> Boolean) :
        this(service, { service.rootInActiveWindow }, { service.windows }, onScanReport = onScanReport, packageAllowed = packageAllowed)
    private val geometry = AndroidScreenGeometry(context)
    private val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private var healthy = true

    override fun read(checkpoint: () -> Unit): F9Scene? = scan(checkpoint, null) { scene, _ -> scene }.takeIf { healthy }

    override fun readFor(purpose: F9ReadPurpose, checkpoint: () -> Unit): F9Scene? =
        scan(checkpoint, null, purpose = purpose) { scene, _ -> scene }.takeIf { healthy }

    private class DisplayRead(val source: PopupSnapshot, val overlayId: Int, val probe: ManualDisplayProbe?)

    /** Read-only presentation continuity. This NEVER exposes a F9Scene or token, invokes consume
     * for an action, or changes the strict overlay rejection in read()/press(). Caller confirms
     * THIS native C generation/ownership before and after the entire bounded operation. */
    fun verifyDisplayedPopup(source: PopupSnapshot, nativeOverlayId: Int, checkpoint: () -> Unit,
        probe: ManualDisplayProbe? = null): ManualDisplayVerdict {
        val verdict = scan(checkpoint, null, DisplayRead(source, nativeOverlayId, probe), F9ReadPurpose.DISPLAY_REVIEW) { scene, _ ->
            ManualDisplayContinuity.assess(source, scene, nativeOverlayId, probe)
        }
        if (!healthy || verdict == null) {
            probe?.screenUnavailable(resourceFailure = !healthy)
            return ManualDisplayVerdict.UNAVAILABLE
        }
        return verdict
    }

    override fun press(reference: F9Reference, accept: (F9Scene) -> Boolean): F9Press {
        var attempted = false
        var returned: Boolean? = null
        val once = AtomicBoolean()
        try {
            scan({}, reference.candidate.nodePath, purpose = F9ReadPurpose.PRESS) { scene, target ->
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
    private fun <T> scan(checkpoint: () -> Unit, targetPath: String?, display: DisplayRead? = null,
        purpose: F9ReadPurpose = F9ReadPurpose.UNSCOPED, consume: (F9Scene, AccessibilityNodeInfo?) -> T): T? {
        val readTrace = F9ReadObservation(purpose, onScanReport)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            readTrace.reject(F9ReadDetail.MAIN_THREAD_REQUIRED); readTrace.report()
            check(false) { "F9_MAIN_REQUIRED" }
        }
        if (!healthy) { readTrace.reject(F9ReadDetail.READER_UNHEALTHY); readTrace.report(); return null }
        val started = SystemClock.uptimeMillis()
        fun reject(detail: F9ReadDetail): T? { readTrace.reject(detail); return null }
        var completed = false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val ownedWindows = mutableListOf<AccessibilityWindowInfo>()
        val identities = IdentityHashMap<AccessibilityNodeInfo, Boolean>()
        fun tick() {
            readTrace.checkpoint(checkpoint)
            readTrace.require(SystemClock.uptimeMillis() - started < 100L, F9ReadDetail.TIME_LIMIT)
        }
        fun own(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
            readTrace.require(identities.put(node, true) == null, F9ReadDetail.NODE_ALIAS)
            nodes += node
            return node
        }
        return readTrace.run(body = {
            tick()
            readTrace.stage = F9ReadStage.GEOMETRY
            val frame = geometry.read().frame ?: return reject(F9ReadDetail.GEOMETRY_UNAVAILABLE)
            readTrace.stage = F9ReadStage.WINDOWS
            val windows = windowSource(); ownedWindows += windows
            readTrace.require(windows.size in 1..32, F9ReadDetail.WINDOW_COUNT)
            readTrace.require(windows.all { it.displayId == 0 }, F9ReadDetail.NONDEFAULT_DISPLAY)
            readTrace.stage = F9ReadStage.ACTIVE_WINDOW
            val active = if (display == null) {
                windows.singleOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    ?: return reject(F9ReadDetail.ACTIVE_APPLICATION_UNCONFIRMED)
            } else {
                val selected = ManualDisplayContinuity.target(windows.map { window(it, readTrace) }, display.source.token.windowId, display.overlayId, display.probe)
                    ?: return reject(F9ReadDetail.DISPLAY_TARGET_UNCONFIRMED)
                windows.singleOrNull { it.id == selected.id } ?: return reject(F9ReadDetail.DISPLAY_TARGET_UNCONFIRMED)
            }
            readTrace.stage = F9ReadStage.FOCUSED_WINDOW
            if (display == null && !active.isFocused) return reject(F9ReadDetail.ACTIVE_NOT_FOCUSED)
            readTrace.stage = F9ReadStage.OVERLAY_WINDOW
            var conflict = F9ReadDetail.NONE
            if (display == null && windows.any {
                    when {
                        it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> { conflict = F9ReadDetail.ACCESSIBILITY_OVERLAY_PRESENT; true }
                        it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD -> { conflict = F9ReadDetail.INPUT_METHOD_PRESENT; true }
                        it.id != active.id && (it.isActive || it.isFocused) -> { conflict = F9ReadDetail.OTHER_ACTIVE_OR_FOCUSED; true }
                        else -> false
                    }
                }) return reject(conflict)
            readTrace.stage = F9ReadStage.APP_COUNT
            val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            if (apps.size !in 1..2) return reject(F9ReadDetail.APPLICATION_COUNT)
            readTrace.stage = F9ReadStage.BACKGROUND_WINDOW
            val base = if (apps.size == 2) apps.single { it.id != active.id } else active
            if (base !== active && base.layer >= active.layer) return reject(F9ReadDetail.BACKGROUND_LAYER)
            if (base !== active && !F9AutoCloser.contains(rect(base, readTrace), rect(active, readTrace))) return reject(F9ReadDetail.BACKGROUND_BOUNDS)
            val activeBounds = rect(active, readTrace)
            readTrace.stage = F9ReadStage.WINDOW_BOUNDS
            if (!F9AutoCloser.contains(frame, activeBounds)) return reject(F9ReadDetail.WINDOW_OUTSIDE_FRAME)
            readTrace.stage = F9ReadStage.OCCLUSION
            if (windows.any { other ->
                    f9OccludingWindow({ other.id == active.id }, { other.id == display?.overlayId },
                        { windowType(other.type) }, { other.layer to active.layer }, { rect(other, readTrace) },
                        activeBounds, frame, targetPath == null,
                        { probe -> passiveSystemBar(other, frame, ::own, readTrace, probe) }, readTrace::occluding)
                }) return reject(F9ReadDetail.OCCLUDING_WINDOW)
            readTrace.stage = F9ReadStage.ROOT
            val root = own((if (display == null) activeRoot() else active.root) ?: return reject(F9ReadDetail.ROOT_UNAVAILABLE))
            val packageName = root.packageName?.toString() ?: return reject(F9ReadDetail.ROOT_PACKAGE_UNAVAILABLE)
            if (root.windowId != active.id) return reject(F9ReadDetail.ROOT_WINDOW_MISMATCH)
            if (display != null && packageName != display.source.token.packageName) return reject(F9ReadDetail.ROOT_PACKAGE_MISMATCH)
            var packageRejection = F9ReadDetail.NONE
            if (F9ContentSafety.protectedPackage(packageName).also { if (it) packageRejection = F9ReadDetail.PACKAGE_PROTECTED } ||
                (!packageAllowed(packageName)).also { if (it) packageRejection = F9ReadDetail.APP_NOT_ALLOWED }) {
                // Identity-only departure evidence. Never read protected/unconsented app text.
                tick()
                return consume(F9Scene(packageName, active.id, base.id, frame, windows.map { window(it, readTrace) },
                    emptyList(), emptySet(), complete = false), null).also { completed = true; readTrace.reject(packageRejection); readTrace.complete(identityOnly = true) }
            }
            val dtos = windows.map { window(it, readTrace) }
            var target: AccessibilityNodeInfo? = null
            var textCount = 0
            var nodeCount = 0
            fun tree(start: AccessibilityNodeInfo, captureTarget: Boolean): Pair<List<NodeSnapshot>, Set<String>> {
                val result = mutableListOf<NodeSnapshot>()
                val visible = mutableSetOf<String>()
                fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
                    tick()
                    readTrace.require(++nodeCount <= 1_200, F9ReadDetail.NODE_LIMIT)
                    readTrace.require(depth <= 60, F9ReadDetail.DEPTH_LIMIT)
                    readTrace.require(node.packageName?.toString() == packageName, F9ReadDetail.NODE_PACKAGE_MISMATCH)
                    readTrace.require(node.windowId == start.windowId, F9ReadDetail.NODE_WINDOW_MISMATCH)
                    readTrace.require(PrivacyPolicy.fieldBlock(node.isPassword, node.isEditable, node.inputType) == null, F9ReadDetail.PROTECTED_FIELD)
                    fun freeze(value: CharSequence?): String? {
                        tick()
                        if (value == null) return null
                        val size = value.length
                        readTrace.require(size <= 4_096, F9ReadDetail.TEXT_FIELD_LIMIT)
                        textCount += size; readTrace.require(textCount <= 32_768, F9ReadDetail.TEXT_TOTAL_LIMIT)
                        val frozen = value.toString()
                        readTrace.require(frozen.length == size, F9ReadDetail.TEXT_CHANGED)
                        readTrace.require(!F9ContentSafety.blocked(frozen), F9ReadDetail.PROTECTED_TEXT)
                        return frozen
                    }
                    val text = freeze(node.text); val description = freeze(node.contentDescription)
                    val viewId = freeze(node.viewIdResourceName); val className = freeze(node.className)
                    listOf(node.error, node.hintText, node.paneTitle, node.tooltipText, node.stateDescription).forEach(::freeze)
                    val actions = node.actionList
                    readTrace.require(actions.size <= 64, F9ReadDetail.ACTION_LIMIT)
                    val descriptor = NodeSnapshot(path, viewId, className, text, description, node.isClickable,
                        node.isEnabled, rect(node, readTrace), buildSet {
                            if (actions.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) add(NodeAction.CLICK)
                            if (actions.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }) add(NodeAction.DISMISS)
                        }.toPersistentSet())
                    result += descriptor
                    if (node.isVisibleToUser) visible += path
                    if (captureTarget && path == targetPath) target = node
                    val count = node.childCount
                    readTrace.require(count in 0..1_200, F9ReadDetail.CHILD_LIMIT)
                    for (i in 0 until count) {
                        val child = node.getChild(i)
                        readTrace.require(child != null, F9ReadDetail.CHILD_UNAVAILABLE)
                        visit(own(requireNotNull(child)), "$path/$i", depth + 1)
                    }
                    readTrace.require(count == node.childCount, F9ReadDetail.CHILDREN_CHANGED)
                    tick()
                }
                visit(start, "0", 0)
                return result to visible
            }
            readTrace.stage = F9ReadStage.TREE
            val (descriptors, visible) = tree(root, true)
            val baseNodes = if (base === active) descriptors else {
                val baseRoot = own(base.root ?: return reject(F9ReadDetail.BACKGROUND_ROOT_UNAVAILABLE))
                if (baseRoot.windowId != base.id) return reject(F9ReadDetail.BACKGROUND_ROOT_WINDOW_MISMATCH)
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
            readTrace.stage = F9ReadStage.ENDPOINT
            val currentWindows = windowSource(); ownedWindows += currentWindows
            if (currentWindows.map { window(it, readTrace) } != dtos) return reject(F9ReadDetail.END_WINDOWS_CHANGED)
            val endFrame = geometry.read().frame
            if (endFrame != frame) return reject(if (endFrame == null) F9ReadDetail.END_FRAME_UNAVAILABLE else F9ReadDetail.END_FRAME_CHANGED)
            val endRoot = (if (display == null) activeRoot() else active.root) ?: return reject(F9ReadDetail.END_ROOT_UNAVAILABLE)
            if (identities[endRoot] == null) own(endRoot)
            if (endRoot.windowId != active.id) return reject(F9ReadDetail.END_ROOT_WINDOW_MISMATCH)
            if (endRoot.packageName?.toString() != packageName) return reject(F9ReadDetail.END_ROOT_PACKAGE_MISMATCH)
            // refresh is supplementary, never the whole-screen safety proof.
            readTrace.stage = F9ReadStage.TARGET
            target?.let { n ->
                val expected = descriptors.single { it.path == targetPath }
                val mismatch = when {
                    !n.refresh() -> F9ReadDetail.TARGET_REFRESH_FAILED
                    n.packageName?.toString() != packageName -> F9ReadDetail.TARGET_PACKAGE_MISMATCH
                    n.windowId != active.id -> F9ReadDetail.TARGET_WINDOW_MISMATCH
                    n.text?.toString() != expected.text -> F9ReadDetail.TARGET_TEXT_CHANGED
                    n.contentDescription?.toString() != expected.contentDescription -> F9ReadDetail.TARGET_DESCRIPTION_CHANGED
                    n.viewIdResourceName != expected.viewId -> F9ReadDetail.TARGET_VIEW_ID_CHANGED
                    n.className?.toString() != expected.className -> F9ReadDetail.TARGET_CLASS_CHANGED
                    rect(n, readTrace) != expected.bounds -> F9ReadDetail.TARGET_BOUNDS_CHANGED
                    !n.isVisibleToUser -> F9ReadDetail.TARGET_NOT_VISIBLE
                    !n.isEnabled -> F9ReadDetail.TARGET_DISABLED
                    !n.isClickable -> F9ReadDetail.TARGET_NOT_CLICKABLE
                    n.isPassword || n.isEditable || n.inputType != 0 -> F9ReadDetail.TARGET_PROTECTED_FIELD
                    n.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK } -> F9ReadDetail.TARGET_CLICK_UNAVAILABLE
                    else -> null
                }
                if (mismatch != null) return reject(mismatch)
                val refreshedActions = n.actionList
                if (refreshedActions.size > 64) return reject(F9ReadDetail.ACTION_LIMIT)
                if (buildSet {
                        if (refreshedActions.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) add(NodeAction.CLICK)
                        if (refreshedActions.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }) add(NodeAction.DISMISS)
                    } != expected.actions) return reject(F9ReadDetail.TARGET_ACTIONS_CHANGED)
                if (listOf(n.error, n.hintText, n.paneTitle, n.tooltipText, n.stateDescription)
                        .filterNotNull().any { it.length > 4_096 || F9ContentSafety.blocked(it) }) return reject(F9ReadDetail.TARGET_METADATA_UNSAFE)
            }
            tick()
            readTrace.stage = F9ReadStage.COMPLETE
            return consume(F9Scene(packageName, active.id, base.id, frame, dtos, descriptors, visible, true, pageDigest), target)
                .also { completed = true; readTrace.complete() }
        }, cleanup = {
            nodes.asReversed().forEach { if (!readTrace.release(node = true) { it.recycle() }) healthy = false }
            val seen = IdentityHashMap<AccessibilityWindowInfo, Boolean>()
            ownedWindows.forEach { if (seen.put(it, true) == null && !readTrace.release(node = false) { it.recycle() }) healthy = false }
            if (!completed) try { onRejected(readTrace.stage) } catch (_: Exception) { /* Diagnostic code only. */ }
        })
    }
    private fun bounds(r: Rect, trace: F9ReadObservation): ScreenBounds {
        trace.require(r.right >= r.left && r.bottom >= r.top, F9ReadDetail.REVERSED_BOUNDS)
        return ScreenBounds(r.left, r.top, r.right, r.bottom)
    }
    private fun rect(node: AccessibilityNodeInfo, trace: F9ReadObservation) = bounds(Rect().also(node::getBoundsInScreen), trace)
    private fun rect(window: AccessibilityWindowInfo, trace: F9ReadObservation) = bounds(Rect().also(window::getBoundsInScreen), trace)
    private fun windowType(type: Int) = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> WindowType.APPLICATION
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowType.ACCESSIBILITY_OVERLAY
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowType.INPUT_METHOD
        AccessibilityWindowInfo.TYPE_SYSTEM -> WindowType.SYSTEM
        else -> WindowType.OTHER
    }
    private fun window(w: AccessibilityWindowInfo, trace: F9ReadObservation) =
        WindowSnapshot(w.id, windowType(w.type), rect(w, trace), w.isActive, w.isFocused, w.layer)
    private fun passiveSystemBar(window: AccessibilityWindowInfo, frame: ScreenBounds,
        own: (AccessibilityNodeInfo) -> AccessibilityNodeInfo, trace: F9ReadObservation, probe: F9PassiveBarProbe): Boolean =
        f9PassiveSystemBar({ window.type == AccessibilityWindowInfo.TYPE_SYSTEM }, { window.isActive }, { window.isFocused },
            { rect(window, trace) }, frame, owner = { p ->
                // Original owner-only metadata checks: never traverse or read system window text.
                val root = own(window.root ?: return@f9PassiveSystemBar p.rejected(F9PassiveBarResult.ROOT_UNAVAILABLE))
                if (root.packageName?.toString() != "com.android.systemui") return@f9PassiveSystemBar p.rejected(F9PassiveBarResult.OWNER_UNCONFIRMED)
                if (root.windowId != window.id) return@f9PassiveSystemBar p.rejected(F9PassiveBarResult.ROOT_WINDOW_MISMATCH)
                true
            }, probe = probe)
    override fun toString() = "AndroidF9ScreenPort(redacted)"
}
