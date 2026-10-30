package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.CancellationException
import kr.closeforyou.accessibility.CollectionReleaseBatch
import kr.closeforyou.accessibility.CollectionResourceSafety
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.*
import org.junit.Test

class OwnedOverlayWindowReadTest {
    private class Node(val id: Int = 2, val owned: Boolean = true)
    private class Window(val id: Int = 2, val overlay: Boolean = true, val defaultDisplay: Boolean = true,
        val root: Node? = Node(), val titleMatches: Boolean = true)
    private class Driver {
        var windows = listOf(Window())
        var unhealthy = false
        var failAt: String? = null
        var cancel = false
        val calls = mutableListOf<String>()
        val releasedRoots = mutableListOf<Node>()
        val releasedWindows = mutableListOf<Window>()
        val details = mutableListOf<OverlayOwnedWindowDetail>()
        val diagnostic = OverlayEventDiagnostic { _, _, _, _, _, detail, _, _, _, _, _ -> details += detail }
        init { diagnostic.arm(); diagnostic.begin(OverlayEventCategory.WINDOWS_CHANGED) }
        private fun at(step: String) {
            calls += step
            if (failAt == step) {
                if (cancel) throw CancellationException("SYNTHETIC_SECRET")
                throw IllegalStateException("SYNTHETIC_SECRET")
            }
        }
        private fun readWindows(): List<Window> { at("WINDOWS"); return windows }
        private fun isOverlay(window: Window): Boolean {
            at("TYPE"); if (!window.overlay) return false
            at("DISPLAY"); return window.defaultDisplay
        }
        private fun root(window: Window): Node? { at("ROOT"); return window.root }
        private fun owned(root: Node, window: Window): Boolean {
            at("PACKAGE"); at("OWN_PACKAGE"); if (!root.owned) return false
            at("ROOT_ID"); at("WINDOW_ID"); return root.id == window.id
        }
        private fun title(window: Window): Boolean { at("TITLE"); at("OWN_TITLE"); return window.titleMatches }
        private fun id(window: Window): Int { at("FOUND_ID"); return window.id }
        private fun releaseRoot(root: Node) { releasedRoots += root; at("RELEASE_ROOT") }
        private fun releaseWindow(window: Window) { releasedWindows += window; at("RELEASE_WINDOW") }
        fun read(trace: OverlayEventDiagnostic? = diagnostic): Set<Int>? {
            if (unhealthy) { trace?.ownedQueryUncertain(OverlayOwnedWindowDetail.UNCERTAIN); return null }
            return readOwnedOverlayIds(::readWindows, ::isOverlay, ::root, ::owned, ::title, ::id,
                ::releaseRoot, ::releaseWindow, { unhealthy = true }, trace)
        }
        fun finish(result: Set<Int>?) {
            val token = MockPopupFixtures.notice().snapshot.token
            val binding = OwnedOverlayBinding(); binding.expect(emptySet(), token, 1)
            val owns = binding.matches(2, result, token, 1, diagnostic)
            if (!owns) diagnostic.bindingRefused(true)
            diagnostic.completed(owns)
        }
        // Frozen V5 reader control flow: protects getter short-circuits, cleanup and sticky failure.
        fun originalV5(): Set<Int>? {
            if (unhealthy) return null
            val roots = mutableListOf<Node>(); val acquired = mutableListOf<Window>()
            return try {
                CollectionResourceSafety.run(body = {
                    acquired += readWindows()
                    if (acquired.isEmpty() || acquired.size > 32) return@run null
                    val found = mutableSetOf<Int>()
                    for (window in acquired) {
                        if (!isOverlay(window)) continue
                        val node = root(window) ?: continue
                        if (roots.none { it === node }) roots += node
                        if (!owned(node, window)) continue
                        if (title(window)) found += id(window)
                    }
                    found
                }, cleanup = {
                    val batch = CollectionReleaseBatch()
                    roots.forEach { node -> batch.attempt { releaseRoot(node) } }
                    val seen = java.util.IdentityHashMap<Window, Boolean>()
                    acquired.forEach { window -> if (seen.put(window, true) == null) batch.attempt { releaseWindow(window) } }
                    batch.finish()
                }, cleanupFailure = { unhealthy = true; null })
            } catch (_: Exception) { unhealthy = true; null }
            finally { roots.clear(); acquired.clear() }
        }
    }

    @Test fun successfulQueryKeepsTheOriginalGetterAndReleaseOrder() {
        val d = Driver(); assertEquals(setOf(2), d.read())
        assertFalse(d.unhealthy); assertEquals(listOf("WINDOWS", "TYPE", "DISPLAY", "ROOT", "PACKAGE",
            "OWN_PACKAGE", "ROOT_ID", "WINDOW_ID", "TITLE", "OWN_TITLE", "FOUND_ID", "RELEASE_ROOT", "RELEASE_WINDOW"), d.calls)
        d.finish(setOf(2)); assertTrue(d.details.isEmpty())
    }
    @Test fun queryAndCleanupFailuresAreQueryFailedAndNeverPreventOtherReleases() {
        for (step in listOf("WINDOWS", "TYPE", "DISPLAY", "ROOT", "PACKAGE", "OWN_PACKAGE", "ROOT_ID",
            "WINDOW_ID", "TITLE", "OWN_TITLE", "FOUND_ID", "RELEASE_ROOT", "RELEASE_WINDOW")) {
            for (cancel in listOf(false, true)) {
                val d = Driver(); d.windows = listOf(Window(), Window(id = 3, root = Node(id = 3)))
                d.failAt = step; d.cancel = cancel
                val attempted = runCatching { d.read() }
                if (cancel) assertTrue(attempted.exceptionOrNull() is CancellationException)
                else assertNull(attempted.exceptionOrNull())
                val result = attempted.getOrNull(); assertNull(result); assertTrue(d.unhealthy); d.finish(result)
                assertEquals(listOf(OverlayOwnedWindowDetail.QUERY_FAILED), d.details)
                assertEquals(if (step == "WINDOWS") 0 else 2, d.releasedWindows.size)
                assertEquals(when (step) {
                    "WINDOWS", "TYPE", "DISPLAY", "ROOT" -> 0
                    "RELEASE_ROOT", "RELEASE_WINDOW" -> 2
                    else -> 1
                }, d.releasedRoots.size)
            }
        }
    }
    @Test fun emptyOrOversizedListsAreUncertainWithoutInventingAQueryException() {
        for (windows in listOf(emptyList(), (1..33).map { Window(id = it) })) {
            val d = Driver(); d.windows = windows
            val result = d.read(); assertNull(result); d.finish(result)
            assertFalse(d.unhealthy); assertEquals(listOf(OverlayOwnedWindowDetail.UNCERTAIN), d.details)
            assertEquals(windows.size, d.releasedWindows.size); assertTrue(d.releasedRoots.isEmpty())
        }
    }
    @Test fun noValidatedCandidateIsNoNewWindowEvenWhenAWindowHasNoReadableRoot() {
        for (window in listOf(Window(overlay = false), Window(defaultDisplay = false), Window(root = null),
            Window(root = Node(owned = false)), Window(root = Node(id = 9)), Window(titleMatches = false))) {
            val d = Driver(); d.windows = listOf(window)
            val result = d.read(); assertEquals(emptySet<Int>(), result); d.finish(result)
            assertEquals(listOf(OverlayOwnedWindowDetail.NO_NEW_WINDOW), d.details)
            assertFalse(d.unhealthy); assertEquals(1, d.releasedWindows.size)
        }
    }
    @Test fun multipleValidatedNewWindowsAreNotChosenArbitrarily() {
        val d = Driver(); d.windows = listOf(Window(), Window(id = 3, root = Node(id = 3)))
        val result = d.read(); d.finish(result)
        assertEquals(listOf(OverlayOwnedWindowDetail.MULTIPLE_NEW_WINDOWS), d.details)
        assertEquals(2, d.releasedRoots.size); assertEquals(2, d.releasedWindows.size)
    }
    @Test fun sharedRootAndWindowHandlesAreReleasedExactlyOnce() {
        val root = Node(); val window = Window(root = root)
        val d = Driver(); d.windows = listOf(window, window, Window(root = root))
        assertEquals(setOf(2), d.read())
        assertEquals(listOf(root), d.releasedRoots); assertEquals(2, d.releasedWindows.size)
    }
    @Test fun previousUnhealthyStateMakesNoNewQueryAndCannotReuseTheEarlierFailureDetail() {
        val d = Driver(); d.failAt = "WINDOWS"
        val failed = d.read(); d.finish(failed)
        d.diagnostic.disarm(); d.diagnostic.arm(); d.diagnostic.begin(OverlayEventCategory.WINDOWS_CHANGED)
        d.failAt = null; d.calls.clear()
        val unavailable = d.read(); assertNull(unavailable); d.finish(unavailable)
        assertTrue(d.calls.isEmpty()); assertEquals(OverlayOwnedWindowDetail.UNCERTAIN, d.details.last())
    }
    @Test fun debugAndReleaseKeepV5GetterAndCleanupOrderButPropagateCancellation() {
        val configurations: List<(Driver) -> Unit> = listOf({}, { it.windows = emptyList() },
            { it.windows = (1..33).map { id -> Window(id = id) } },
            { it.windows = listOf(Window(overlay = false), Window(defaultDisplay = false), Window(root = null),
                Window(root = Node(owned = false)), Window(root = Node(id = 9)), Window(titleMatches = false), Window()) },
            { it.unhealthy = true })
        val steps = listOf(null, "WINDOWS", "TYPE", "DISPLAY", "ROOT", "PACKAGE", "OWN_PACKAGE", "ROOT_ID",
            "WINDOW_ID", "TITLE", "OWN_TITLE", "FOUND_ID", "RELEASE_ROOT", "RELEASE_WINDOW")
        for (configure in configurations) for (step in steps) for (cancel in listOf(false, true)) {
            val baseline = Driver(); val debug = Driver(); val release = Driver()
            for (d in listOf(baseline, debug, release)) { configure(d); d.failAt = step; d.cancel = cancel }
            val original = baseline.originalV5()
            val debugResult = runCatching { debug.read() }; val releaseResult = runCatching { release.read(null) }
            val cancelled = cancel && step != null && step in baseline.calls
            if (cancelled) {
                assertTrue(debugResult.exceptionOrNull() is CancellationException)
                assertTrue(releaseResult.exceptionOrNull() is CancellationException)
            } else {
                assertNull(debugResult.exceptionOrNull()); assertNull(releaseResult.exceptionOrNull())
                assertEquals(original, debugResult.getOrNull()); assertEquals(original, releaseResult.getOrNull())
            }
            for (d in listOf(debug, release)) {
                assertEquals(baseline.calls, d.calls); assertEquals(baseline.unhealthy, d.unhealthy)
                assertEquals(baseline.releasedRoots.size, d.releasedRoots.size)
                assertEquals(baseline.releasedWindows.size, d.releasedWindows.size)
            }
        }
    }
}
