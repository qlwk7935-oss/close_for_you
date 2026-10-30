package kr.closeforyou.accessibility

import android.view.accessibility.AccessibilityNodeInfo as N
import kr.closeforyou.accessibility.action.F9AutoCloserTest
import org.junit.Assert.*
import org.junit.Test

class NativeNodeActionsTest {
    @Test fun everyNativeIdIncludingLongClickScrollFocusAndUnknownIsPresentNotPassive() {
        for (id in listOf(N.ACTION_CLICK, N.ACTION_DISMISS, N.ACTION_LONG_CLICK, N.ACTION_SCROLL_FORWARD,
                N.ACTION_SCROLL_BACKWARD, N.ACTION_ACCESSIBILITY_FOCUS, N.ACTION_FOCUS, Int.MAX_VALUE)) {
            // The SAME count classifier used after the existing full native read in both readers.
            assertEquals(NativeActionPresence.PRESENT, nativeActionPresence(listOf(id).size))
        }
        assertEquals(NativeActionPresence.NONE, nativeActionPresence(emptyList<Int>().size))
    }
    @Test fun invalidOrOverLimitActionCountsCannotProveAbsence() {
        for (n in listOf(-1, 65, Int.MAX_VALUE)) assertEquals(NativeActionPresence.UNKNOWN, nativeActionPresence(n))
        assertEquals(NativeActionPresence.PRESENT, nativeActionPresence(64))
    }
    @Test fun snapshotProofCannotBindToACopyEvenWithIdenticalTokenAndNodes() {
        val s = F9AutoCloserTest.Fixture().snapshot
        val e = requireNotNull(NativeNodeActions.forSnapshot(s, listOf(NativeActionPresence.PRESENT)))
        assertEquals(NativeActionPresence.PRESENT, e.state(s, 1, 0))
        assertEquals(NativeActionPresence.UNKNOWN, e.state(s.copy(), 1, 0))
        assertEquals(NativeActionPresence.UNKNOWN, e.state(s.copy(token = s.token.copy(screenVersion = 2)), 1, 0))
    }
    @Test fun freshProofCannotBindToAnEqualButDifferentNodeList() {
        val n = F9AutoCloserTest.Fixture().snapshot.nodes
        val e = requireNotNull(NativeNodeActions.forNodes(n, listOf(NativeActionPresence.PRESENT)))
        assertEquals(NativeActionPresence.PRESENT, e.state(n, 1, 0))
        assertEquals(NativeActionPresence.UNKNOWN, e.state(n.toMutableList(), 1, 0))
        assertEquals(NativeActionPresence.UNKNOWN, e.state(n, 2, 0))
        assertEquals(NativeActionPresence.UNKNOWN, e.state(n, 1, 1))
    }
    @Test fun unknownRowsAndWrongShapeNeverBecomeNone() {
        val s = F9AutoCloserTest.Fixture().snapshot
        assertNull(NativeNodeActions.forSnapshot(s, emptyList()))
        assertNull(NativeNodeActions.forNodes(emptyList(), emptyList()))
        assertNull(NativeNodeActions.forNodes(List(1_201) { s.nodes.single() }, List(1_201) { NativeActionPresence.NONE }))
        val e = requireNotNull(NativeNodeActions.forSnapshot(s, listOf(NativeActionPresence.UNKNOWN)))
        assertEquals(NativeActionPresence.UNKNOWN, e.state(s, 1, 0))
    }
    @Test fun callerMutationCannotChangeFrozenEvidenceAndToStringIsRedacted() {
        val s = F9AutoCloserTest.Fixture().snapshot
        val rows = mutableListOf(NativeActionPresence.PRESENT)
        val e = requireNotNull(NativeNodeActions.forSnapshot(s, rows)); rows[0] = NativeActionPresence.NONE
        assertEquals(NativeActionPresence.PRESENT, e.state(s, 1, 0))
        assertEquals("NativeNodeActions(redacted)", e.toString())
    }
    @Test fun sameListReusedAtSameSizeCannotApplyOldActionsEvenToEqualDescriptorCopies() {
        val original = F9AutoCloserTest.Fixture().node
        val nodes = mutableListOf(original)
        val e = requireNotNull(NativeNodeActions.forNodes(nodes, listOf(NativeActionPresence.PRESENT)))
        nodes[0] = original.copy() // Equal values, but a different descriptor from another read.
        assertEquals(NativeActionPresence.UNKNOWN, e.state(nodes, 1, 0))
        nodes[0] = original
        assertEquals(NativeActionPresence.UNKNOWN, e.state(nodes, 1, 0)) // Observed corruption cannot recover.
    }
    @Test fun clearedAndRefilledListCannotRestoreAnObservedInvalidBinding() {
        val original = F9AutoCloserTest.Fixture().node
        val nodes = mutableListOf(original)
        val e = requireNotNull(NativeNodeActions.forNodes(nodes, listOf(NativeActionPresence.PRESENT)))
        nodes.clear()
        assertEquals(NativeActionPresence.UNKNOWN, e.state(nodes, 1, 0))
        nodes.add(original)
        assertEquals(NativeActionPresence.UNKNOWN, e.state(nodes, 1, 0))
        // A genuinely new record (after a new native read) is separate, not a recovery of e.
        val fresh = requireNotNull(NativeNodeActions.forNodes(nodes, listOf(NativeActionPresence.NONE)))
        assertEquals(NativeActionPresence.NONE, fresh.state(nodes, 1, 0))
    }
}
