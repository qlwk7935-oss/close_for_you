package kr.closeforyou.simpleclose

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineJobsTest {
    @Test fun autoCloseSurvivesEventsFromOtherPackagesAndSpeaksOnce() = runTest {
        val jobs = EngineJobs(this)
        val spoken = mutableListOf<String>()
        var confirmed = 0
        assertTrue(jobs.runAction {
            delay(50) // click
            delay(250) // confirmation poll
            confirmed++
            spoken += "RESULT"
        })
        // Window events from another package (or Chrome repainting) arrive while the poll runs.
        delay(100)
        jobs.scheduleInspection { spoken += "INSPECT" }
        delay(100)
        jobs.scheduleInspection { spoken += "INSPECT" }
        advanceUntilIdle()
        assertEquals(1, confirmed)
        assertEquals(listOf("RESULT"), spoken)
        assertFalse(jobs.busy)
    }

    @Test fun aNewEventReplacesTheInspectionInFlight() = runTest {
        val jobs = EngineJobs(this)
        var finished = 0
        jobs.scheduleInspection { delay(300); finished++ }
        delay(100)
        jobs.scheduleInspection { delay(300); finished++ }
        advanceUntilIdle()
        assertEquals(1, finished)
    }

    @Test fun onlyOneActionAtATimeAndItStopsThePendingInspection() = runTest {
        val jobs = EngineJobs(this)
        var inspected = 0
        var actions = 0
        jobs.scheduleInspection { delay(500); inspected++ }
        assertTrue(jobs.runAction { delay(300); actions++ })
        assertFalse(jobs.runAction { actions++ })
        advanceUntilIdle()
        assertEquals(0, inspected)
        assertEquals(1, actions)
    }

    @Test fun cancelAllStopsEverything() = runTest {
        val jobs = EngineJobs(this)
        var done = 0
        jobs.runAction { delay(300); done++ }
        jobs.cancelAll()
        advanceUntilIdle()
        assertEquals(0, done)
        assertFalse(jobs.busy)
    }
}
