package kr.closeforyou.accessibility.integration

import kotlinx.collections.immutable.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Real session/admission functions; Android reader/event delivery are not simulated as verified. */
class F9PageAdmissionTest {
    private class Rig {
        val f = F9AutoCloserTest.Fixture()
        val source = f.snapshot.copy(token = f.token.copy(windowId = 10),
            nodes = persistentListOf(f.node.copy(path = "0", text = "일반 문서")),
            windows = persistentListOf(f.windows[0].copy(active = true, focused = true)))
        var scene: F9Scene? = f.before.copy(windowId = 10, pageWindowId = 10,
            nodes = source.nodes, windows = source.windows, visiblePaths = setOf("0"))
        var reads = 0
        var ready = true
        var complete = 0
        var afterRead: () -> Unit = {}
        val session = F9ServiceSession(F9CollectedSource { f.now }, object : F9ScreenPort {
            override fun read(checkpoint: () -> Unit): F9Scene? {
                reads++; checkpoint(); afterRead(); return scene
            }
            override fun press(reference: F9Reference, accept: (F9Scene) -> Boolean): F9Press = error("NO_PRESS_IN_PAGE_READ")
        }, F9Clock { f.now }, { F9SessionAccess() }, { ready }, {}, dispatcher = Dispatchers.Unconfined,
            onPageVerified = { complete++ })
        fun check() = session.observePage(source)
    }
    @Test fun completeNormalPageUsesTheActualPrecedingF0Descriptors() {
        val r = Rig(); assertEquals(F9PageObservation.VERIFIED, r.check())
        assertEquals(1, r.reads); assertEquals(1, r.complete); assertEquals(0, r.f.clicks)
    }
    @Test fun nullReadCannotCreatePageProof() {
        val r = Rig(); r.scene = null
        assertEquals(F9PageObservation.READ_FAILED, r.check()); assertEquals(0, r.complete)
    }
    @Test fun incompleteSourceAndIncompleteF9SceneAreBothRejected() {
        val r = Rig()
        assertEquals(F9PageObservation.INCOMPLETE, r.session.observePage(r.source.copy(completeness = SnapshotCompleteness.TRUNCATED)))
        r.scene = r.scene!!.copy(complete = false)
        assertEquals(F9PageObservation.INCOMPLETE, r.check()); assertEquals(0, r.complete)
    }
    @Test fun sourceTimeAndEpochAreNotRenewedByANewRead() {
        for (age in listOf(-1L, 5_000L, 5_001L)) {
            val r = Rig(); r.f.now += age
            assertEquals(F9PageObservation.STALE, r.check()); assertEquals(0, r.complete)
        }
        val r = Rig()
        assertEquals(F9PageObservation.STALE, r.session.observePage(r.source.copy(token = r.source.token.copy(captureEpoch = 99))))
    }
    @Test fun packageWindowFrameNodesAndWindowSetMismatchCannotVerifyANewScreen() {
        for (field in listOf("APP", "WINDOW", "FRAME", "NODES", "WINDOW_SET")) {
            val r = Rig(); val s = r.scene!!
            r.scene = when (field) {
                "APP" -> s.copy(packageName = "other")
                "WINDOW" -> s.copy(windowId = 11)
                "FRAME" -> s.copy(screen = ScreenBounds(0, 0, 301, 500))
                "NODES" -> s.copy(nodes = listOf(s.nodes[0].copy(text = "다른 문서")))
                else -> s.copy(windows = s.windows + s.windows[0].copy(id = 11))
            }
            assertEquals(when (field) {
                "APP" -> F9PageObservation.PACKAGE_MISMATCH
                "WINDOW" -> F9PageObservation.WINDOW_MISMATCH
                "FRAME" -> F9PageObservation.FRAME_MISMATCH
                "NODES" -> F9PageObservation.NODES_MISMATCH
                else -> F9PageObservation.WINDOW_SET_MISMATCH
            }, r.check()); assertEquals(0, r.complete)
        }
    }
    @Test fun matchingButInactiveUnfocusedOrDuplicateWindowIsStillRejected() {
        for (mode in listOf("INACTIVE", "UNFOCUSED", "DUPLICATE")) {
            val r = Rig(); val w = r.source.windows.single()
            val ws = when (mode) {
                "INACTIVE" -> persistentListOf(w.copy(active = false))
                "UNFOCUSED" -> persistentListOf(w.copy(focused = false))
                else -> persistentListOf(w, w)
            }
            r.scene = r.scene!!.copy(windows = ws)
            assertEquals(F9PageObservation.WINDOW_UNCONFIRMED, r.session.observePage(r.source.copy(windows = ws)))
        }
    }
    @Test fun insetWindowIsNotSilentlyExpandedClampedOrTreatedAsAFullPage() {
        val r = Rig(); val ws = persistentListOf(r.source.windows.single().copy(bounds = ScreenBounds(0, 20, 300, 480)))
        r.scene = r.scene!!.copy(windows = ws)
        assertEquals(F9PageObservation.NOT_FULL_PAGE, r.session.observePage(r.source.copy(windows = ws)))
        assertEquals(0, r.complete)
    }
    @Test fun popupAsItsOwnPageMissingOrBlankDigestCannotProduceAProof() {
        for (mode in listOf("BASE", "NULL", "BLANK")) {
            val r = Rig(); r.scene = when (mode) {
                "BASE" -> r.scene!!.copy(pageWindowId = 20)
                "NULL" -> r.scene!!.copy(pageDigest = null)
                else -> r.scene!!.copy(pageDigest = "")
            }
            assertEquals(F9PageObservation.BACKGROUND_UNCONFIRMED, r.check()); assertEquals(0, r.complete)
        }
    }
    @Test fun cleanupAndClosedSessionDoNotEvenStartThePageRead() {
        val r = Rig(); r.ready = false
        assertEquals(F9PageObservation.SESSION_UNAVAILABLE, r.check()); r.ready = true; r.session.close(2)
        assertEquals(F9PageObservation.SESSION_UNAVAILABLE, r.check()); assertEquals(0, r.reads)
    }
    @Test fun cancellationBeforeDuringAndAfterReadCannotCommitPageVerified() {
        for (at in 1..3) {
            val r = Rig(); var checks = 0; val cancel = CancellationException("FIXED_TEST_CANCEL")
            try {
                r.session.observePage(r.source) { if (++checks == at) throw cancel }
                fail("CANCELLATION_REQUIRED")
            } catch (actual: CancellationException) { assertSame(cancel, actual) }
            assertEquals(0, r.complete)
        }
    }
    @Test fun readFinishingAtTheOriginalFiveSecondDeadlineIsRejected() {
        val r = Rig(); r.afterRead = { r.f.now += 5_000 }
        assertEquals(F9PageObservation.STALE, r.check()); assertEquals(0, r.complete)
    }
}
