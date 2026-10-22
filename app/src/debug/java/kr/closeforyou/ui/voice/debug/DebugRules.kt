package kr.closeforyou.ui.voice.debug

import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.PopupRule
import kr.closeforyou.core.model.RuleKey
import kr.closeforyou.testing.mock.InMemoryMockPopupRuleRepository
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.integration.RememberRetention

/**
 * Debug-only: fake remembered rules (in-memory mock) so the settings list states can be tried on a
 * phone. Not D's Room on purpose: fake rules must not go into the real rule database.
 */
internal object DebugRules {
    val repository = InMemoryMockPopupRuleRepository()
    @Volatile private var seeded = false

    suspend fun seedOnce() {
        if (seeded) return
        seeded = true
        val now = System.currentTimeMillis()
        listOf(
            fakeRule("debug-rule-ad", "ad", ChoiceKind.REMEMBER_EXACT_POPUP, now - DAY_MILLIS),
            fakeRule("debug-rule-notice", "notice", ChoiceKind.STOP_ASKING, now),
            // Settings list states (D review #18-2): expired yesterday, and a failed remembered press.
            fakeRule("debug-rule-expired", "expired", ChoiceKind.REMEMBER_EXACT_POPUP, now - 31 * DAY_MILLIS),
            fakeRule("debug-rule-failed", "failed", ChoiceKind.REMEMBER_EXACT_POPUP, now - 2 * DAY_MILLIS)
                .copy(failureCount = 1),
        ).forEach { repository.save(it) }
    }

    private fun fakeRule(id: String, content: String, choice: ChoiceKind, createdAt: Long): PopupRule {
        val always = choice == ChoiceKind.REMEMBER_EXACT_POPUP
        return PopupRule(
            id = id,
            key = RuleKey(MockPopupFixtures.PACKAGE_NAME, 1, "fixture-structure", "fixture-$content", "fixture-target"),
            choice = choice,
            buttonMeaningConfirmed = always,
            repeatAutomationExplicitlyAllowed = always,
            createdAtEpochMillis = createdAt,
            expiresAtEpochMillis = RememberRetention.expiryFor(createdAt),
        )
    }

    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
}
