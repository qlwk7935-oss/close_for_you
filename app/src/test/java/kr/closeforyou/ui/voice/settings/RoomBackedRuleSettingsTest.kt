package kr.closeforyou.ui.voice.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.PopupRule
import kr.closeforyou.core.model.RuleKey
import kr.closeforyou.testing.mock.InMemoryMockPopupRuleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The real `create(context)` needs Room on a device; the wiring is checked through `bind`. */
class RoomBackedRuleSettingsTest {
    private val repository = InMemoryMockPopupRuleRepository()

    @Test fun deleteAllRunsEraseAllNotTheRepositoryDelete() = runTest {
        repository.save(rule())
        var erased = 0
        val storage = RoomBackedRuleSettings.bind(repository, eraseAll = { erased++ }, cleanUp = {}, io = StandardTestDispatcher(testScheduler))
        assertSame(repository, storage.repository)
        storage.deleteAll()
        assertEquals(1, erased)
        assertEquals("rules are D's to erase (with the key)", 1, repository.observeRules().first().size)
    }

    @Test fun cleanUpRunsOnOpenAndItsCountsAreDropped() = runTest {
        var cleaned = 0
        val storage = RoomBackedRuleSettings.bind(repository, eraseAll = {}, cleanUp = { cleaned++; 3 to 1 }, io = StandardTestDispatcher(testScheduler))
        storage.onOpenCleanUp()
        assertEquals(1, cleaned)
    }

    @Test fun cleanUpFailureReachesTheRouteWhichSwallowsIt() = runTest {
        val storage = RoomBackedRuleSettings.bind(
            repository,
            eraseAll = {},
            cleanUp = { throw IllegalStateException("db") },
            io = StandardTestDispatcher(testScheduler),
        )
        try {
            storage.onOpenCleanUp()
            fail("expected the failure to reach the Route")
        } catch (e: IllegalStateException) {
            // UiVoiceSettingsRoute catches this without logging; the screen still opens.
            assertTrue(e.message == "db")
        }
    }

    private fun rule() = PopupRule(
        id = "r", key = RuleKey("kr.example.shop", 1, "s", "c", "t"),
        choice = ChoiceKind.REMEMBER_EXACT_POPUP, buttonMeaningConfirmed = true,
        repeatAutomationExplicitlyAllowed = true, createdAtEpochMillis = 0L, expiresAtEpochMillis = 1L,
    )
}
