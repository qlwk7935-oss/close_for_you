package kr.closeforyou.ui.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kr.closeforyou.R
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnnouncementBatcherTest {
    private val scope = TestScope()
    private val flushed = mutableListOf<BatchSummary>()
    private val batcher = AnnouncementBatcher(scope, onFlush = { flushed += it }, windowMillis = 2_000)

    private fun advance(millis: Long) {
        scope.advanceTimeBy(millis)
        scope.runCurrent()
    }

    @Test fun threeClosesWithinWindowBecomeOneSummary() {
        batcher.add(HandledOutcome.CLOSED)
        advance(500)
        batcher.add(HandledOutcome.CLOSED)
        advance(500)
        batcher.add(HandledOutcome.CLOSED)
        advance(999)
        assertTrue(flushed.isEmpty())
        advance(1)
        assertEquals(listOf(BatchSummary(closed = 3, skipped = 0, notConfirmed = 0)), flushed)
    }

    @Test fun windowIsFixedFromFirstResultSoAStreamCannotDelayForever() {
        batcher.add(HandledOutcome.CLOSED)
        advance(1_900)
        batcher.add(HandledOutcome.SKIPPED)
        advance(100)
        assertEquals(listOf(BatchSummary(1, 1, 0)), flushed)
    }

    @Test fun resultAfterFlushStartsANewBatch() {
        batcher.add(HandledOutcome.CLOSED)
        advance(2_000)
        batcher.add(HandledOutcome.NOT_CONFIRMED)
        advance(2_000)
        assertEquals(listOf(BatchSummary(1, 0, 0), BatchSummary(0, 0, 1)), flushed)
    }

    @Test fun cancelDropsPendingResults() {
        batcher.add(HandledOutcome.CLOSED)
        batcher.cancel()
        advance(5_000)
        assertTrue(flushed.isEmpty())
        batcher.add(HandledOutcome.SKIPPED)
        advance(2_000)
        assertEquals(listOf(BatchSummary(0, 1, 0)), flushed)
    }

    @Test fun outcomeMappingNeverCountsBlockedOrStaleAsClosed() {
        val token = MockPopupFixtures.notice().snapshot.token
        fun result(status: ActionStatus) = ActionResult(token, status, reason = null)
        assertEquals(HandledOutcome.NOT_CONFIRMED, AnnouncementBatcher.outcomeOf(result(ActionStatus.MECHANICAL_SUCCESS), ChoiceKind.CLOSE_ONCE))
        assertEquals(HandledOutcome.NOT_CONFIRMED, AnnouncementBatcher.outcomeOf(result(ActionStatus.MECHANICAL_SUCCESS), ChoiceKind.SKIP_ONCE))
        assertEquals(HandledOutcome.NOT_CONFIRMED, AnnouncementBatcher.outcomeOf(result(ActionStatus.ATTEMPT_FAILED), ChoiceKind.CLOSE_ONCE))
        assertEquals(HandledOutcome.NOT_CONFIRMED, AnnouncementBatcher.outcomeOf(result(ActionStatus.OUTCOME_UNKNOWN), ChoiceKind.CLOSE_ONCE))
        listOf(ActionStatus.NOT_EXECUTED, ActionStatus.BLOCKED, ActionStatus.STALE).forEach {
            assertNull(AnnouncementBatcher.outcomeOf(result(it), ChoiceKind.CLOSE_ONCE))
        }
    }

    @Test fun sentenceListsOnlyNonZeroParts() {
        val text = { res: Int, count: Int ->
            when (res) {
                R.string.ui_announce_batch_closed -> "closed$count"
                R.string.ui_announce_batch_skipped -> "skipped$count"
                R.string.ui_announce_batch_total -> "total$count"
                else -> "unknown$count"
            }
        }
        assertEquals("closed3", BatchSentence.build(BatchSummary(3, 0, 0), text = text))
        assertEquals("closed1 skipped2 unknown1", BatchSentence.build(BatchSummary(1, 2, 1), text = text))
    }

    @Test fun detailedAddsTotalOnlyWhenKindsAreMixed() {
        val text = { res: Int, count: Int ->
            if (res == R.string.ui_announce_batch_total) "total$count" else "part$count"
        }
        assertEquals("part3", BatchSentence.build(BatchSummary(3, 0, 0), detailed = true, text = text))
        assertEquals("part1 part2 total3", BatchSentence.build(BatchSummary(1, 2, 0), detailed = true, text = text))
        assertEquals("part1 part2", BatchSentence.build(BatchSummary(1, 2, 0), detailed = false, text = text))
    }
}
