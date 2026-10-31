package kr.closeforyou.simpleclose

import java.util.concurrent.CancellationException

/** Shared production/JVM boundary. Before collect, only root acquisition and its package getter
 * are permitted; no node text, children, actions, geometry or window traversal. A root obtained
 * for an earlier event is never silently rebound to another foreground app. */
internal object SimpleCollectionGuard {
    fun <N : Any, R : Any> read(
        expectedPackage: String?,
        root: () -> N?,
        packageOf: (N) -> String?,
        allowed: (String) -> Boolean,
        collect: (N, String) -> R?,
        release: (N) -> Unit,
    ): R? {
        if (expectedPackage.isNullOrBlank()) return null
        return try {
            val node = root() ?: return null
            try {
                val actual = packageOf(node) ?: return null
                if (actual != expectedPackage || !allowed(actual)) null else collect(node, actual)
            } finally {
                release(node)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            null
        }
    }
}
