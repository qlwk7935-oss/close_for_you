package kr.closeforyou.accessibility.action

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kr.closeforyou.accessibility.AndroidScreenGeometry
import kr.closeforyou.core.model.NodeAction

/** Read-only future A adapter. No registration/state is supplied by default; no service uses it yet. */
internal class AndroidTargetProbeSource(
    private val service: AccessibilityService,
    private val reference: () -> TargetReadReference? = { null },
    private val runtime: () -> ProbeRuntimeState = { ProbeRuntimeState() },
) : TargetProbeSource {
    override fun reference() = reference.invoke()
    override fun state() = runtime()
    override fun root(): ProbeNode? = service.rootInActiveWindow?.let(::Node)
    @Suppress("DEPRECATION")
    override fun windowFrame(windowId: Int): ProbeWindowFrame {
        val windows = service.windows
        var primary: Throwable? = null
        try {
            check(windows.size in 1..32) { "PROBE_WINDOW_SCOPE" }
            val target = windows.single { it.id == windowId }
            val screen = requireNotNull(AndroidScreenGeometry(service).read().frame) { "PROBE_FRAME_UNAVAILABLE" }
            val rect = Rect().also(target::getBoundsInScreen)
            return ProbeWindowFrame(target.displayId, ProbeRect(screen.left, screen.top, screen.right, screen.bottom),
                ProbeRect(rect.left, rect.top, rect.right, rect.bottom),
                target.type == AccessibilityWindowInfo.TYPE_APPLICATION && target.isActive,
                windows.any { it.id != windowId && (it.isActive || it.isFocused || it.layer >= target.layer) })
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            var failed = false
            var cancellation: kotlinx.coroutines.CancellationException? = null
            val seen = java.util.IdentityHashMap<AccessibilityWindowInfo, Boolean>()
            windows.forEach { if (seen.put(it, true) == null) {
                try { it.recycle() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { if (cancellation == null) cancellation = cancelled }
                catch (_: Exception) { failed = true }
            } }
            if (primary !is kotlinx.coroutines.CancellationException && primary !is Error) {
                cancellation?.let { throw it }
                if (failed && primary == null) throw ProbeFailure(ProbeDenial.CLEANUP_FAILED)
            }
        }
    }
    private class Node(private val node: AccessibilityNodeInfo) : ProbeNode {
        override fun sameHandle(other: ProbeNode): Boolean = other is Node && node === other.node
        override fun packageName() = node.packageName?.toString()
        override fun windowId() = node.windowId
        override fun childCount() = node.childCount
        override fun child(index: Int) = node.getChild(index)?.let(::Node)
        override fun password() = node.isPassword
        override fun editable() = node.isEditable
        override fun inputType() = node.inputType
        override fun text(field: ProbeText): CharSequence? = when (field) {
            ProbeText.TEXT -> node.text; ProbeText.DESCRIPTION -> node.contentDescription
            ProbeText.VIEW_ID -> node.viewIdResourceName; ProbeText.CLASS_NAME -> node.className
            ProbeText.ERROR -> node.error; ProbeText.HINT -> node.hintText; ProbeText.PANE -> node.paneTitle
            ProbeText.TOOLTIP -> node.tooltipText; ProbeText.STATE -> node.stateDescription
        }
        override fun bounds() = Rect().also(node::getBoundsInScreen).let { ProbeRect(it.left, it.top, it.right, it.bottom) }
        override fun actions(): Set<NodeAction> {
            val actions = node.actionList
            check(actions.size <= 64) { "PROBE_ACTION_LIMIT" }
            return buildSet {
                if (actions.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) add(NodeAction.CLICK)
                if (actions.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }) add(NodeAction.DISMISS)
            }
        }
        override fun clickable() = node.isClickable
        override fun enabled() = node.isEnabled
        override fun visible() = node.isVisibleToUser
        @Suppress("DEPRECATION") override fun close() = node.recycle()
        override fun toString() = "ProbeAndroidNode(redacted)"
    }
    override fun toString() = "AndroidTargetProbeSource(redacted)"
}
