package kr.closeforyou.accessibility.integration

import kr.closeforyou.accessibility.NativeActionPresence
import kr.closeforyou.accessibility.NativeActionSummary
import kr.closeforyou.accessibility.NativeNodeActions
import android.view.accessibility.AccessibilityNodeInfo as N
import kr.closeforyou.accessibility.action.F9Scene
import kr.closeforyou.core.model.*

/** First rejected child only. These categories do NOT certify a passive widget or event origin. */
internal enum class ManualInteractiveNode { CLOSE_CANDIDATE, OTHER_CLICKABLE, NONCLICK_NATIVE_CAPABILITY, UNKNOWN }
internal enum class ManualInteractiveCauses {
    CLICKABLE, DTO_ACTION, NATIVE_ACTION, CLICKABLE_DTO_ACTION, CLICKABLE_NATIVE_ACTION,
    DTO_ACTION_NATIVE_ACTION, CLICKABLE_DTO_ACTION_NATIVE_ACTION, UNKNOWN;

    companion object {
        fun from(clickable: Boolean, dto: Boolean, native: Boolean): ManualInteractiveCauses = when {
            clickable && dto && native -> CLICKABLE_DTO_ACTION_NATIVE_ACTION
            clickable && dto -> CLICKABLE_DTO_ACTION
            clickable && native -> CLICKABLE_NATIVE_ACTION
            dto && native -> DTO_ACTION_NATIVE_ACTION
            clickable -> CLICKABLE
            dto -> DTO_ACTION
            native -> NATIVE_ACTION
            else -> UNKNOWN
        }
    }
}
internal enum class ManualRejectedRole { TEXTVIEW_LEAF, EMPTY_LAYOUT_LEAF, OTHER, UNKNOWN }
internal enum class ManualCapabilityProfile {
    NAVIGATION, SELECTION_STATE, TEXT_NAVIGATION_SELECTION,
    NAVIGATION_AND_SELECTION_STATE, NAVIGATION_AND_TEXT_NAVIGATION_SELECTION,
    SELECTION_STATE_AND_TEXT_NAVIGATION_SELECTION, NAVIGATION_AND_SELECTION_STATE_AND_TEXT_NAVIGATION_SELECTION,
    OTHER_STANDARD, UNKNOWN,
}
internal enum class ManualOperableGeometry { UNCHANGED, CHANGED, UNKNOWN }
/** Separate type: CLOSE_GEOMETRY can never report NO_TARGETS. */
internal enum class ManualOtherOperableGeometry { NO_TARGETS, UNCHANGED, CHANGED, UNKNOWN }

/** All state is fixed enums. Roles/profiles describe observations, NOT permission or passivity. */
internal data class ManualInteractiveBoundsCode(val node: ManualInteractiveNode, val causes: ManualInteractiveCauses,
    val role: ManualRejectedRole = ManualRejectedRole.UNKNOWN,
    val capabilities: ManualCapabilityProfile = ManualCapabilityProfile.UNKNOWN,
    val closeGeometry: ManualOperableGeometry = ManualOperableGeometry.UNKNOWN,
    val otherGeometry: ManualOtherOperableGeometry = ManualOtherOperableGeometry.UNKNOWN) {
    fun fields() = ";INTERACTIVE_NODE=${node.name};INTERACTIVE_CAUSES=${causes.name}" +
        ";REJECTED_ROLE=${role.name};CAPABILITY_PROFILE=${capabilities.name}" +
        ";CLOSE_GEOMETRY=${closeGeometry.name};OTHER_OPERABLE_GEOMETRY=${otherGeometry.name}"
}

internal object ManualInteractiveBoundsDiagnostics {
    private val navigation = setOf(N.ACTION_FOCUS, N.ACTION_CLEAR_FOCUS, N.ACTION_ACCESSIBILITY_FOCUS,
        N.ACTION_CLEAR_ACCESSIBILITY_FOCUS, android.R.id.accessibilityActionShowOnScreen)
    private val selection = setOf(N.ACTION_SELECT, N.ACTION_CLEAR_SELECTION)
    private val text = setOf(N.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, N.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, N.ACTION_SET_SELECTION)
    private val grouped = navigation + selection + text

    /** Exact known IDs stay private. Mixed OTHER_STANDARD is UNKNOWN instead of losing a group.
     * Empty known lists have no requested category; UNKNOWN here does not imply an unread list. */
    fun profile(summary: NativeActionSummary): ManualCapabilityProfile {
        if (!summary.hasExactKnownSet || summary.presence != NativeActionPresence.PRESENT) return ManualCapabilityProfile.UNKNOWN
        if (!summary.containsAnyOf(grouped)) return ManualCapabilityProfile.OTHER_STANDARD
        if (!summary.containsOnly(grouped)) return ManualCapabilityProfile.UNKNOWN
        val n = summary.containsAnyOf(navigation); val s = summary.containsAnyOf(selection); val t = summary.containsAnyOf(text)
        return when {
            n && s && t -> ManualCapabilityProfile.NAVIGATION_AND_SELECTION_STATE_AND_TEXT_NAVIGATION_SELECTION
            n && s -> ManualCapabilityProfile.NAVIGATION_AND_SELECTION_STATE
            n && t -> ManualCapabilityProfile.NAVIGATION_AND_TEXT_NAVIGATION_SELECTION
            s && t -> ManualCapabilityProfile.SELECTION_STATE_AND_TEXT_NAVIGATION_SELECTION
            n -> ManualCapabilityProfile.NAVIGATION
            s -> ManualCapabilityProfile.SELECTION_STATE
            t -> ManualCapabilityProfile.TEXT_NAVIGATION_SELECTION
            else -> ManualCapabilityProfile.UNKNOWN
        }
    }
    fun unknown() = ManualInteractiveBoundsCode(ManualInteractiveNode.UNKNOWN, ManualInteractiveCauses.UNKNOWN)

    /** Called ONLY at checkedLayout's actual first rejection, AFTER whole-tree exact native
     * action/projection/owner checks passed. The one bounded DTO/metadata scan never reads Android
     * or changes a metadata latch. It continues after CHANGED so later uncertainty wins. */
    fun describe(original: PopupSnapshot, fresh: F9Scene, candidates: List<CloseCandidate>, index: Int,
        native: NativeActionPresence, originalActions: NativeNodeActions? = null): ManualInteractiveBoundsCode {
        if (original.nodes.size !in 2..1_200 || original.nodes.size != fresh.nodes.size ||
            index !in 1 until original.nodes.size || native == NativeActionPresence.UNKNOWN ||
            original.completeness != SnapshotCompleteness.COMPLETE || !fresh.complete ||
            original.token.packageName != fresh.packageName || original.token.windowId != fresh.windowId ||
            original.screenBounds != fresh.screen) return unknown()
        val before = original.nodes[index]; val after = fresh.nodes[index]
        if (before.bounds == after.bounds || before.copy(bounds = after.bounds) != after) return unknown()
        val candidate = candidates.singleOrNull() ?: return unknown()
        if (candidate.role != ButtonRole.CLOSE || candidate.evidence != EvidenceLevel.E2_LABELED_NODE) return unknown()
        val closePath = candidate.nodePath ?: return unknown()
        val prefix = before.path + "/"
        val paths = HashSet<String>()
        var leaf = true; var closeIndex = -1; var hasOther = false; var otherChanged = false
        var rejectedSummary: NativeActionSummary? = null
        for (i in original.nodes.indices) {
            val a = original.nodes[i]; val b = fresh.nodes[i]
            if (a.path != b.path || a.viewId != b.viewId || a.className != b.className || a.text != b.text ||
                a.contentDescription != b.contentDescription || a.clickable != b.clickable || a.enabled != b.enabled ||
                a.actions != b.actions || !paths.add(a.path)) return unknown()
            if (i == 0) { if (a.path != "0") return unknown() }
            else {
                val split = a.path.lastIndexOf('/')
                if (split < 1 || a.path.length > 301 || a.path.substring(0, split) !in paths) return unknown()
                val segment = a.path.substring(split + 1)
                if (segment.isEmpty() || segment.length > 4 || segment.any { it !in '0'..'9' } ||
                    (segment.length > 1 && segment[0] == '0') || segment.toInt() >= 1_200) return unknown()
            }
            fun positive(bounds: ScreenBounds) = bounds.right.toLong() > bounds.left && bounds.bottom.toLong() > bounds.top
            if (!positive(a.bounds) || !positive(b.bounds)) return unknown()
            val old = originalActions?.observedSummary(original, original.nodes.size, i) ?: return unknown()
            val new = fresh.nativeActions?.observedSummary(fresh.nodes, fresh.nodes.size, i) ?: return unknown()
            if (!old.hasExactKnownSet || !new.hasExactKnownSet || old != new ||
                !old.matchesProjection(a.actions) || !new.matchesProjection(b.actions)) return unknown()
            if (i == index) { if (old.presence != native) return unknown(); rejectedSummary = old }
            if (a.path.startsWith(prefix)) leaf = false
            if (a.path == closePath) closeIndex = i
            // Validate ALL rows above, including excluded ones. Only the geometry population
            // excludes ROOT, the bound close candidate and this exact first rejected row.
            val operable = a.clickable || b.clickable || a.actions.isNotEmpty() || b.actions.isNotEmpty() ||
                old.presence == NativeActionPresence.PRESENT || new.presence == NativeActionPresence.PRESENT
            if (i != 0 && i != index && a.path != closePath && operable) {
                hasOther = true
                if (a.bounds != b.bounds) otherChanged = true
            }
        }
        if (closeIndex < 0) return unknown()
        val close = original.nodes[closeIndex]
        if (candidate.viewId != close.viewId || candidate.bounds != close.bounds || !close.clickable || !close.enabled ||
            NodeAction.CLICK !in close.actions) return unknown()
        val dto = before.actions.isNotEmpty()
        val hasNative = native == NativeActionPresence.PRESENT
        if (dto && !hasNative) return unknown() // Impossible after the real native projection guard.
        val causes = ManualInteractiveCauses.from(before.clickable, dto, hasNative)
        if (causes == ManualInteractiveCauses.UNKNOWN) return unknown()
        val node = when {
            before.path == candidate.nodePath -> {
                if (candidate.viewId != before.viewId || candidate.bounds != before.bounds ||
                    !before.clickable || kr.closeforyou.core.model.NodeAction.CLICK !in before.actions) return unknown()
                ManualInteractiveNode.CLOSE_CANDIDATE
            }
            before.clickable -> ManualInteractiveNode.OTHER_CLICKABLE
            hasNative -> ManualInteractiveNode.NONCLICK_NATIVE_CAPABILITY
            else -> ManualInteractiveNode.UNKNOWN
        }
        val role = when {
            before.className.isNullOrBlank() -> ManualRejectedRole.UNKNOWN
            !leaf -> ManualRejectedRole.OTHER
            before.className == "android.widget.TextView" -> ManualRejectedRole.TEXTVIEW_LEAF
            before.className in setOf("android.widget.FrameLayout", "android.widget.LinearLayout") &&
                before.text.isNullOrBlank() && before.contentDescription.isNullOrBlank() -> ManualRejectedRole.EMPTY_LAYOUT_LEAF
            else -> ManualRejectedRole.OTHER
        }
        return ManualInteractiveBoundsCode(node, causes, role, profile(requireNotNull(rejectedSummary)),
            if (close.bounds == fresh.nodes[closeIndex].bounds) ManualOperableGeometry.UNCHANGED else ManualOperableGeometry.CHANGED,
            when {
                !hasOther -> ManualOtherOperableGeometry.NO_TARGETS
                otherChanged -> ManualOtherOperableGeometry.CHANGED
                else -> ManualOtherOperableGeometry.UNCHANGED
            })
    }
}
