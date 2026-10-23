package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CancellationException
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.*
import org.junit.Test

class OverlayEventWindowClassTest {
    private class Node(val id: Int = 9, val pkg: String? = "SYNTHETIC_OTHER")
    private class Window(val id: Int = 9, val type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
        val root: Node? = Node())
    private class Driver {
        val token = MockPopupFixtures.notice().snapshot.token.copy(windowId = 1, packageName = "SYNTHETIC_TARGET")
        var current: SnapshotToken? = token
        var eventId = 9
        var windows: List<Window>? = listOf(Window())
        val reads = mutableListOf<String>()
        val releasedWindows = mutableListOf<Window>()
        val releasedRoots = mutableListOf<Node>()
        var failureAt: String? = null
        var cancellation = false
        private fun at(step: String) {
            reads += step
            if (failureAt == step) {
                if (cancellation) throw CancellationException("SYNTHETIC_SECRET")
                else throw IllegalStateException("SYNTHETIC_SECRET")
            }
        }
        fun classify() = classifyOverlayEventWindow(eventId, current,
            { at("WINDOWS"); windows }, { at("ID"); it.id }, { at("TYPE"); it.type },
            { at("ROOT"); it.root }, { at("ROOT_ID"); it.id }, { at("PACKAGE"); it.pkg },
            { releasedRoots += it; at("RELEASE_ROOT") }, { releasedWindows += it; at("RELEASE_WINDOW") })
    }

    @Test fun exactCurrentTargetIdRequiresNoWindowOrNodeRead() {
        val d = Driver(); d.eventId = d.token.windowId; d.failureAt = "WINDOWS"
        assertEquals(OverlayEventWindowClass.TARGET_APP, d.classify())
        assertTrue(d.reads.isEmpty()); assertTrue(d.releasedWindows.isEmpty())
    }
    @Test fun sameTargetPackageInAnotherWindowIsUnknown() {
        val d = Driver(); d.windows = listOf(Window(root = Node(pkg = d.token.packageName)))
        assertEquals(OverlayEventWindowClass.UNKNOWN, d.classify())
        assertEquals(1, d.releasedRoots.size); assertEquals(1, d.releasedWindows.size)
    }
    @Test fun systemAndInputMethodAreDeterminedByTypeWithoutNodeAccess() {
        for ((type, expected) in listOf(AccessibilityWindowInfo.TYPE_SYSTEM to OverlayEventWindowClass.SYSTEM,
            AccessibilityWindowInfo.TYPE_INPUT_METHOD to OverlayEventWindowClass.INPUT_METHOD)) {
            val d = Driver(); d.windows = listOf(Window(type = type)); d.failureAt = "ROOT"
            assertEquals(expected, d.classify()); assertFalse(d.reads.contains("ROOT"))
            assertTrue(d.releasedRoots.isEmpty()); assertEquals(1, d.releasedWindows.size)
        }
    }
    @Test fun applicationAndAccessibilityOverlayOfAnotherPackageAreOtherApp() {
        for (type in listOf(AccessibilityWindowInfo.TYPE_APPLICATION, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)) {
            val d = Driver(); d.windows = listOf(Window(type = type))
            assertEquals(OverlayEventWindowClass.OTHER_APP, d.classify())
            assertEquals(1, d.releasedRoots.size); assertEquals(1, d.releasedWindows.size)
        }
    }
    @Test fun absentEventWindowInNormallyReadListIsNotInList() {
        val d = Driver(); d.windows = listOf(Window(id = 7), Window(id = 8)); d.failureAt = "ROOT"
        assertEquals(OverlayEventWindowClass.NOT_IN_LIST, d.classify())
        assertFalse(d.reads.contains("TYPE")); assertFalse(d.reads.contains("ROOT"))
        assertEquals(2, d.releasedWindows.size)
    }
    @Test fun missingCurrentOrInvalidIdentityDoesNotQueryWindows() {
        val cases: List<(Driver) -> Unit> = listOf(
            { it.current = null }, { it.eventId = -1 }, { it.eventId = -2 },
            { it.current = it.token.copy(windowId = -1) }, { it.current = it.token.copy(packageName = "") },
        )
        for (configure in cases) {
            val d = Driver(); configure(d)
            assertEquals(OverlayEventWindowClass.UNKNOWN, d.classify()); assertTrue(d.reads.isEmpty())
        }
    }
    @Test fun nullEmptyAndOversizedListsAreUnknownAndEveryAcquiredWindowIsReleased() {
        for (windows in listOf(null, emptyList(), (1..33).map { Window(id = it) })) {
            val d = Driver(); d.windows = windows
            assertEquals(OverlayEventWindowClass.UNKNOWN, d.classify())
            assertEquals(windows?.size ?: 0, d.releasedWindows.size)
            assertFalse(d.reads.contains("ROOT"))
        }
    }
    @Test fun missingRootPackageMismatchedRootIdAndUnknownTypeAreUnknown() {
        for (window in listOf(Window(root = null), Window(root = Node(pkg = null)),
            Window(root = Node(pkg = " ")), Window(root = Node(id = 8)), Window(type = 999))) {
            val d = Driver(); d.windows = listOf(window)
            assertEquals(OverlayEventWindowClass.UNKNOWN, d.classify())
            assertEquals(1, d.releasedWindows.size)
            assertEquals(if (window.root != null && window.type != 999) 1 else 0, d.releasedRoots.size)
        }
    }
    @Test fun duplicateMatchingWindowsAreAmbiguousAndDuplicateHandlesAreReleasedOnce() {
        val shared = Window()
        for (windows in listOf(listOf(shared, Window()), listOf(shared, shared))) {
            val d = Driver(); d.windows = windows
            assertEquals(OverlayEventWindowClass.UNKNOWN, d.classify())
            assertFalse(d.reads.contains("ROOT"))
            assertEquals(if (windows[0] === windows[1]) 1 else 2, d.releasedWindows.size)
        }
    }
    @Test fun everyReadFailureIncludingCancellationReturnsUnknownAndReleasesAcquiredHandles() {
        for (step in listOf("WINDOWS", "ID", "TYPE", "ROOT", "ROOT_ID", "PACKAGE")) for (cancel in listOf(false, true)) {
            val d = Driver(); d.windows = listOf(Window(), Window(id = 8))
            d.failureAt = step; d.cancellation = cancel
            assertEquals(OverlayEventWindowClass.UNKNOWN, d.classify())
            assertEquals(if (step == "WINDOWS") 0 else 2, d.releasedWindows.size)
            assertEquals(if (step in listOf("ROOT_ID", "PACKAGE")) 1 else 0, d.releasedRoots.size)
        }
    }
    @Test fun cleanupFailureReturnsUnknownAndStillAttemptsAllRemainingReleases() {
        for (step in listOf("RELEASE_ROOT", "RELEASE_WINDOW")) for (cancel in listOf(false, true)) {
            val d = Driver(); d.windows = listOf(Window(), Window(id = 8), Window(id = 7))
            d.failureAt = step; d.cancellation = cancel
            assertEquals(OverlayEventWindowClass.UNKNOWN, d.classify())
            assertEquals(1, d.releasedRoots.size); assertEquals(3, d.releasedWindows.size)
        }
    }
    @Test fun nonmatchingWindowsNeverHaveTheirRootsReadAndReleaseFollowsRootThenAllWindows() {
        val d = Driver(); d.windows = listOf(Window(id = 8), Window(), Window(id = 7))
        assertEquals(OverlayEventWindowClass.OTHER_APP, d.classify())
        assertEquals(1, d.reads.count { it == "ROOT" })
        assertEquals(listOf("RELEASE_ROOT", "RELEASE_WINDOW", "RELEASE_WINDOW", "RELEASE_WINDOW"), d.reads.takeLast(4))
    }
}
