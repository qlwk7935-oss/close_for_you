package kr.closeforyou.ui.voice.overlay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.closeforyou.R
import kr.closeforyou.core.contract.AnnouncementKind
import kr.closeforyou.core.contract.AnnouncementReceipt
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.contract.AnnouncementState
import kr.closeforyou.core.contract.PopupAnnouncer
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.UserChoice
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.command.KoreanVoiceCommandMatcher
import kr.closeforyou.ui.voice.settings.AnnouncementDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #29 REVALIDATING: state changes, dropped answers, same window, timeout, speech. */
@OptIn(ExperimentalCoroutinesApi::class)
class OverlayRevalidationTest {
    private class FakeOps : OverlayWindowOps<String> {
        val log = mutableListOf<String>()
        var revalidateThrows = false
        val removeResults = ArrayDeque<RemoveOutcome>()

        override fun add(window: String) {
            log += "add $window"
        }
        override fun announceShown(window: String) {
            log += "shown $window"
        }
        override fun announceDismissed(window: String) {
            log += "dismissed $window"
        }
        override fun stop(window: String) {
            log += "stop $window"
        }
        override fun remove(window: String, immediate: Boolean): RemoveOutcome {
            log += if (immediate) "removeNow $window" else "remove $window"
            return removeResults.removeFirstOrNull() ?: RemoveOutcome.REMOVED
        }
        override fun blank(window: String) {
            log += "blank $window"
        }
        override fun destroy(window: String) {
            log += "destroy $window"
        }
        override fun enterRevalidating(window: String) {
            log += "revalidate $window"
            if (revalidateThrows) throw IllegalStateException("compose")
        }

        fun count(prefix: String) = log.count { it.startsWith(prefix) }
    }

    /** Holds scheduled timeouts; [fire] runs them as the main looper would. */
    private class FakeTimer : RevalidationTimer {
        class Scheduled(val delayMillis: Long, val action: () -> Unit) {
            var cancelled = false
        }
        val scheduled = mutableListOf<Scheduled>()

        override fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
            val s = Scheduled(delayMillis, action)
            scheduled += s
            return { s.cancelled = true }
        }

        val pending: List<Scheduled> get() = scheduled.filterNot { it.cancelled }
        fun fire(s: Scheduled = scheduled.last()) = s.action()
    }

    private val ops = FakeOps()
    private val timer = FakeTimer()
    private val keeper = OverlayWindowKeeper(ops, timer)
    private var timeouts = 0
    private fun show() = keeper.show { "w$it" }
    private fun begin() = keeper.beginRevalidation { timeouts++ }

    private val choice = UserChoice(MockPopupFixtures.advertisement().snapshot.token, ChoiceKind.CLOSE_ONCE, "b")
    private val answers = mutableListOf<PromptAnswer>()
    private fun tap(generation: Long) =
        keeper.deliver(PromptAnswer.Picked(choice, ChoiceOrigin.TAP, generation), closesWindow = true) { answers += it }

    // --- transitions ---

    @Test fun showingToRevalidatingKeepsTheWindowAndTakesANewGeneration() {
        show()
        assertTrue(begin())
        assertEquals(OverlayWindowStatus.REVALIDATING, keeper.status)
        assertEquals(OverlayWindowKeeper.NO_GENERATION, keeper.currentGeneration)
        assertEquals(2L, keeper.windowGeneration)
        assertEquals("same added window", 1L, keeper.windowNumber)
        assertEquals("no remove, no stop of the window", listOf("add w1", "shown w1", "revalidate w1"), ops.log)
        assertEquals(RevalidatingFeature.TIMEOUT_MILLIS, timer.pending.single().delayMillis)
    }

    @Test fun tapsAndVoiceOfTheOldPromptAreDroppedWhileRevalidating() {
        show()
        begin()
        tap(1)
        keeper.deliver(PromptAnswer.Picked(choice, ChoiceOrigin.VOICE, 1), closesWindow = false) { answers += it }
        tap(keeper.windowGeneration) // even the revalidating generation accepts nothing
        assertTrue(answers.isEmpty())
        assertEquals(OverlayWindowStatus.REVALIDATING, keeper.status)
    }

    @Test fun confirmRefillsTheSameWindowWithoutAddOrRemove() {
        show()
        begin()
        val refilled = mutableListOf<Pair<String, Long>>()
        assertEquals(PromptShowResult.SHOWN, keeper.confirmRevalidation { w, g -> refilled += w to g })
        assertEquals(listOf("w1" to 3L), refilled)
        assertEquals(OverlayWindowStatus.SHOWING, keeper.status)
        assertTrue(keeper.isCurrent(3))
        assertFalse(keeper.isCurrent(1))
        assertEquals(1L, keeper.windowNumber)
        assertEquals(1, ops.count("add"))
        assertEquals(0, ops.count("remove"))
        assertTrue("timeout cancelled", timer.pending.isEmpty())

        tap(1)
        assertTrue(answers.isEmpty())
        tap(3)
        assertEquals(3L, answers.single().generation)
        assertEquals("the refilled window closes normally", OverlayWindowStatus.HIDDEN, keeper.status)
        assertEquals(1, ops.count("destroy w1"))
    }

    @Test fun endClosesTheWindowOnceAndCancelsTheTimeout() {
        show()
        begin()
        assertTrue(keeper.endRevalidation())
        assertEquals(OverlayWindowStatus.HIDDEN, keeper.status)
        assertEquals(1, ops.count("stop w1"))
        assertEquals(1, ops.count("destroy w1"))
        assertTrue(timer.pending.isEmpty())
        assertFalse("second end does nothing", keeper.endRevalidation())
    }

    @Test fun timeoutClosesTheWindow() {
        show()
        begin()
        timer.fire()
        assertEquals(OverlayWindowStatus.HIDDEN, keeper.status)
        assertEquals(1, timeouts)
        assertEquals(1, ops.count("remove w1"))
    }

    @Test fun lateTimeoutAfterConfirmOrEndDoesNothing() {
        show()
        begin()
        val first = timer.scheduled.last()
        keeper.confirmRevalidation { _, _ -> }
        timer.fire(first) // cancelled on a real Handler; even if it ran, the generation check stops it
        assertEquals(OverlayWindowStatus.SHOWING, keeper.status)
        assertEquals(0, timeouts)

        begin()
        val second = timer.scheduled.last()
        keeper.endRevalidation()
        show()
        timer.fire(second)
        assertEquals(OverlayWindowStatus.SHOWING, keeper.status)
        assertEquals(0, timeouts)
    }

    @Test fun dismissAndShowCloseARevalidatingWindowAndCancelTheTimeout() {
        show()
        begin()
        keeper.dismiss()
        assertEquals(OverlayWindowStatus.HIDDEN, keeper.status)
        assertTrue(timer.pending.isEmpty())

        show()
        begin()
        assertEquals(PromptShowResult.SHOWN, show())
        assertTrue(timer.pending.isEmpty())
        assertEquals("the revalidating w3 window was removed before w5 was added", 1, ops.count("destroy w3"))
        assertTrue(keeper.isCurrent(5))
    }

    @Test fun secondBeginKeepsTheFirstTimeout() {
        show()
        begin()
        assertTrue(begin())
        assertEquals(1, timer.scheduled.size)
        assertEquals(2L, keeper.windowGeneration)
    }

    @Test fun beginWithNothingShowingIsRefused() {
        assertFalse(begin())
        assertEquals(OverlayWindowStatus.HIDDEN, keeper.status)
        assertEquals(PromptShowResult.FAILED, keeper.confirmRevalidation { _, _ -> error("not called") })
        assertTrue(timer.scheduled.isEmpty())
    }

    @Test fun failedClearingClosesTheWindow() {
        show()
        ops.revalidateThrows = true
        assertFalse(begin())
        assertEquals(OverlayWindowStatus.HIDDEN, keeper.status)
        assertTrue(timer.scheduled.isEmpty())
    }

    @Test fun failedRefillClosesTheWindow() {
        show()
        begin()
        assertEquals(PromptShowResult.FAILED, keeper.confirmRevalidation { _, _ -> throw IllegalStateException("compose") })
        assertEquals(OverlayWindowStatus.HIDDEN, keeper.status)
        assertEquals(1, ops.count("destroy w1"))
    }

    @Test fun removalFailureWhileRevalidatingFollowsTheRemoveFailedRules() {
        show()
        begin()
        ops.removeResults += RemoveOutcome.FAILED
        keeper.endRevalidation()
        assertEquals(OverlayWindowStatus.REMOVE_FAILED, keeper.status)
        assertEquals(1, ops.count("blank w1"))
        assertFalse(begin())
        ops.removeResults += RemoveOutcome.FAILED
        assertEquals(PromptShowResult.BLOCKED, show())
        keeper.dismiss() // retry succeeds
        assertEquals(OverlayWindowStatus.HIDDEN, keeper.status)
    }

    // --- tracker: the same window stays C's through revalidation ---

    @Test fun trackerStillKnowsTheWindowAfterRevalidationAndRefill() {
        val tracker = OwnOverlayWindowTracker("kr.closeforyou", "팝업 안내")
        fun state() = OverlayWindowState(keeper.status, keeper.windowGeneration, keeper.windowNumber)
        show()
        tracker.update(
            listOf(WindowFacts(42, android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, "팝업 안내", "kr.closeforyou")),
            state(),
        )
        begin()
        assertTrue(tracker.isOwnOverlayWindowEvent(42, state()))
        keeper.confirmRevalidation { _, _ -> }
        assertTrue(tracker.isOwnOverlayWindowEvent(42, state()))
        show() // a new window needs update again
        assertFalse(tracker.isOwnOverlayWindowEvent(42, state()))
    }

    // --- speech ---

    private class CountingAnnouncer : PopupAnnouncer {
        val said = mutableListOf<AnnouncementRequest>()
        var stops = 0
        override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
            said += request
            return AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
        }
        override suspend fun repeatLast() = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
        override fun stop() {
            stops++
        }
    }

    private val prompt = ChoicePromptPlanner.plan(
        MockPopupFixtures.advertisement().analysis,
        MockPopupFixtures.advertisement().expectedDisplayClassification,
    )

    @Test fun revalidatingCancelsThePendingFirstAnnouncementAndConfirmSaysNothing() = runTest {
        val announcer = CountingAnnouncer()
        val listener = PromptAnnouncementListener(
            announcer, backgroundScope, { PromptAnnounceMode.PANE_TITLE_AND_TTS }, 700L,
            { AnnouncementDetail.SHORT }, { _, _ -> "summary" }, { "res$it" },
        )
        listener.onShown(prompt)
        advanceTimeBy(300)
        listener.onRevalidating()
        assertEquals(1, announcer.stops)
        listener.onRevalidated(prompt)
        advanceTimeBy(5_000)
        assertTrue("no TTS: the window's own status line says it once", announcer.said.isEmpty())
    }

    @Test fun closingSentenceIsSaidOnceAndBlockedIsSilent() = runTest {
        val announcer = CountingAnnouncer()
        val listener = PromptAnnouncementListener(
            announcer, backgroundScope, { PromptAnnounceMode.PANE_TITLE_ONLY }, 700L,
            { AnnouncementDetail.SHORT }, { _, _ -> "summary" }, { "res$it" },
        )
        listener.onRevalidationClosed(prompt, RevalidationEnd.DIFFERENT_POPUP)
        runCurrent()
        assertEquals(listOf("res${R.string.ui_revalidate_closed_changed}"), announcer.said.map { it.text })
        assertEquals(AnnouncementKind.ACTION_RESULT, announcer.said.single().kind)

        listener.onRevalidationClosed(prompt, RevalidationEnd.BLOCKED)
        runCurrent()
        assertEquals(1, announcer.said.size)

        listener.onRevalidationClosed(prompt, RevalidationEnd.TIMED_OUT)
        runCurrent()
        assertEquals("res${R.string.ui_revalidate_closed_timeout}", announcer.said.last().text)
    }

    @Test fun reasonSentences() {
        assertEquals(R.string.ui_revalidate_closed_changed, RevalidationSpeech.closedRes(RevalidationEnd.SCREEN_CHANGED))
        assertEquals(R.string.ui_revalidate_closed_changed, RevalidationSpeech.closedRes(RevalidationEnd.DIFFERENT_POPUP))
        assertEquals(R.string.ui_revalidate_closed_timeout, RevalidationSpeech.closedRes(RevalidationEnd.TIMED_OUT))
        assertNull(RevalidationSpeech.closedRes(RevalidationEnd.BLOCKED))
    }

    /** TalkBack may read these while the microphone could open next: no command words. */
    @Test fun revalidationSentencesHaveNoCommandWords() {
        val strings = java.io.File("src/main/res/values/ui_voice_strings.xml").readText()
        listOf(
            "ui_revalidate_checking", "ui_revalidate_confirmed", "ui_revalidate_closed_changed", "ui_revalidate_closed_timeout",
        ).forEach { name ->
            val line = Regex("""<string name="$name">([^<]*)</string>""").find(strings)!!.groupValues[1]
            assertNull("'$line'", KoreanVoiceCommandMatcher.match(line))
        }
    }

    @Test fun featureIsOffByDefaultWithAThreeSecondTimeout() {
        assertFalse(RevalidatingFeature.enabled)
        assertEquals(3_000L, RevalidatingFeature.TIMEOUT_MILLIS)
    }
}
