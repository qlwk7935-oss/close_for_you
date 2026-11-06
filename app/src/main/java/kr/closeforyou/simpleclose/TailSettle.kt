package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot

/**
 * F60 (준영 SM-M446K, TalkBack 끔, 로컬 실험, 예스24 웹): a popup only [TailRead] found has no second read: the whole walk that found it
 * may not run again for [WholeReadCap.ABORTED_RETRY_MILLIS], the probe does not see it, so [SettleRules]'s read again came back with no
 * nodes. Its stillness is still checked (F50: a popup coming in may not have its close or its 결제 button yet): the read again walks that
 * popup's own subtree only ([TailRead.SUBTREE_MAX_MILLIS]) into the first read's nodes, and [SettleRules] judges as ever. Not read whole,
 * or gone ([merged] null): it did not stand still, and the popup is not judged from this read. Pure; JVM tested.
 */
object TailSettle {
    /** A popup found by the tail read waiting for its read again. Memory only, dropped by the next read. */
    class Pending(val read: TreeRead, val root: PopupRoot, val at: Long)

    /** The same app, soon enough to be the same sighting ([SettleRules.FORGET_MILLIS]). */
    fun usable(pendingPackage: String, at: Long, packageName: String, now: Long): Boolean =
        pendingPackage == packageName && now >= at && now - at <= SettleRules.FORGET_MILLIS

    /**
     * [nodes] with [paths]' subtrees as they are now. Null when one of them is gone or was cut ([readPath] null), or nothing of them is
     * visible: no partial popup goes on to be judged (F25 safety: a 결제·동의 button must not be missing from it).
     */
    fun merged(nodes: List<NodeSnapshot>, paths: Set<String>, readPath: (String) -> List<NodeSnapshot>?): List<NodeSnapshot>? {
        val again = paths.flatMap { readPath(it) ?: return null }
        if (again.isEmpty()) return null
        val kept = nodes.filterNot { node -> paths.any { node.path == it || node.path.startsWith("$it/") } }
        return (kept + again).distinctBy { it.path }.sortedWith(TailRead.WALK_ORDER)
    }
}
