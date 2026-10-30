package kr.closeforyou.accessibility.integration

import kr.closeforyou.accessibility.NativeActionPresence
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
/** Only two enums escape the synchronous comparison. No node/path/index/native handle is kept. */
internal data class ManualInteractiveBoundsCode(val node: ManualInteractiveNode, val causes: ManualInteractiveCauses) {
    fun fields() = ";INTERACTIVE_NODE=${node.name};INTERACTIVE_CAUSES=${causes.name}"
}

internal object ManualInteractiveBoundsDiagnostics {
    fun unknown() = ManualInteractiveBoundsCode(ManualInteractiveNode.UNKNOWN, ManualInteractiveCauses.UNKNOWN)

    /** Called ONLY at checkedLayout's actual first rejection, AFTER whole-tree exact native
     * action/projection/owner checks passed. 'native' is that EXISTING row's comparison input;
     * no metadata re-read, Android access, clock, role inference from raw text, or extra scan. */
    fun describe(original: PopupSnapshot, fresh: F9Scene, candidates: List<CloseCandidate>, index: Int,
        native: NativeActionPresence): ManualInteractiveBoundsCode {
        if (original.nodes.size !in 2..1_200 || original.nodes.size != fresh.nodes.size ||
            index !in 1 until original.nodes.size || native == NativeActionPresence.UNKNOWN) return unknown()
        val before = original.nodes[index]; val after = fresh.nodes[index]
        if (before.bounds == after.bounds || before.copy(bounds = after.bounds) != after) return unknown()
        val candidate = candidates.singleOrNull() ?: return unknown()
        if (candidate.role != ButtonRole.CLOSE || candidate.evidence != EvidenceLevel.E2_LABELED_NODE) return unknown()
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
        return ManualInteractiveBoundsCode(node, causes)
    }
}
