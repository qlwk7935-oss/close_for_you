package kr.closeforyou.ui.voice.settings

import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kr.closeforyou.R
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.PopupRule
import kr.closeforyou.core.model.RuleKey
import kr.closeforyou.data.rules.CURRENT_RULE_SCHEMA_VERSION
import kr.closeforyou.testing.mock.InMemoryMockPopupRuleRepository
import kr.closeforyou.ui.voice.integration.RememberRetention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RememberedRuleListTest {
    private val created = 1_792_000_000_000L // 2026-10-14 UTC
    private val expires = RememberRetention.expiryFor(created)
    private val seoul = ZoneId.of("Asia/Seoul")

    private fun rule(
        id: String = "rule-id-secret",
        failureCount: Int = 0,
        enabled: Boolean = true,
        schemaVersion: Int = 1,
        expiresAt: Long? = expires,
    ) = PopupRule(
        id = id,
        key = RuleKey("kr.example.shop", 7, "structure-fp", "content-fp", "target-fp"),
        choice = ChoiceKind.REMEMBER_EXACT_POPUP,
        buttonMeaningConfirmed = true,
        repeatAutomationExplicitlyAllowed = true,
        createdAtEpochMillis = created,
        expiresAtEpochMillis = expiresAt,
        schemaVersion = schemaVersion,
        failureCount = failureCount,
        enabled = enabled,
    )

    private fun status(rule: PopupRule, now: Long) = RuleStatusPolicy.statusOf(rule, now)

    // --- status ---

    @Test fun activeRuleIsOnUntilJustBeforeExpiry() {
        assertEquals(RuleStatus.ON, status(rule(), created))
        assertEquals(RuleStatus.ON, status(rule(), expires - 1))
    }

    /** Same boundary as D's `RuleValidity`: usable only while now < expiresAt. */
    @Test fun ruleIsExpiredExactlyAtExpiry() {
        assertEquals(RuleStatus.EXPIRED, status(rule(), expires))
        assertEquals(RuleStatus.EXPIRED, status(rule(), expires + 1))
    }

    @Test fun failedRuleIsShownAsFailedEvenBeforeExpiry() {
        assertEquals(RuleStatus.FAILED, status(rule(failureCount = 1), created + 1))
        assertEquals(RuleStatus.FAILED, status(rule(failureCount = 3), expires + 1))
    }

    @Test fun unusableRulesAreOff() {
        assertEquals(RuleStatus.OFF, status(rule(expiresAt = null), created + 1))
        assertEquals(RuleStatus.OFF, status(rule(schemaVersion = 2), created + 1))
        // Clock moved back before the rule was saved: D does not match it either.
        assertEquals(RuleStatus.OFF, status(rule(), created - 1))
    }

    @Test fun revokedWinsOverEveryOtherState() {
        assertEquals(RuleStatus.REVOKED, status(rule(enabled = false), created + 1))
        assertEquals(RuleStatus.REVOKED, status(rule(enabled = false, failureCount = 1), expires + 1))
    }

    @Test fun everyStateButRevokedCanStillBeRemoved() {
        RuleStatus.entries.forEach { assertEquals(it.name, it != RuleStatus.REVOKED, RuleStatusPolicy.canRevoke(it)) }
    }

    @Test fun schemaVersionMatchesD() =
        assertEquals(CURRENT_RULE_SCHEMA_VERSION, RuleStatusPolicy.SUPPORTED_SCHEMA_VERSION)

    // --- list sentence ---

    private val templates = mapOf(
        R.string.ui_settings_rule_summary to "%1\$s · %2\$s · %3\$s",
        R.string.ui_settings_rule_always_close to "앞으로 항상 닫기",
        R.string.ui_settings_rule_status_on to "켜짐, %1\$s까지",
        R.string.ui_settings_rule_status_expired to "만료됨, %1\$s에 끝남",
        R.string.ui_settings_rule_status_failed to "꺼짐, 닫기에 실패해서 다시 쓰지 않음",
        R.string.ui_settings_rule_status_off to "꺼짐, 쓸 수 없는 기억",
        R.string.ui_settings_rule_revoked to "해제됨",
    )
    private val text: (Int, Array<out Any>) -> String = { res, args -> templates.getValue(res).format(*args) }
    private val date: (Long) -> String = { millis -> if (millis == expires) "11월 13일" else "다른 날" }

    private fun summary(rule: PopupRule, now: Long) = RuleListText.summary(rule, "예시 쇼핑", now, date, text)

    @Test fun summaryHasAppChoiceStatusAndDateOnOneLine() {
        assertEquals("예시 쇼핑 · 앞으로 항상 닫기 · 켜짐, 11월 13일까지", summary(rule(), created + 1))
        assertEquals("예시 쇼핑 · 앞으로 항상 닫기 · 만료됨, 11월 13일에 끝남", summary(rule(), expires))
        assertEquals("예시 쇼핑 · 앞으로 항상 닫기 · 꺼짐, 닫기에 실패해서 다시 쓰지 않음", summary(rule(failureCount = 1), created + 1))
        assertEquals("예시 쇼핑 · 앞으로 항상 닫기 · 해제됨", summary(rule(enabled = false), created + 1))
        assertFalse(summary(rule(), created + 1).contains('\n'))
    }

    /** No fingerprint, rule id or package name when an app name is known (screen text is never in a rule). */
    @Test fun summaryLeaksNothingFromTheRule() {
        listOf(rule(), rule(failureCount = 1), rule(enabled = false), rule(expiresAt = null)).forEach { r ->
            listOf(created + 1, expires).forEach { now ->
                val line = summary(r, now)
                listOf("structure-fp", "content-fp", "target-fp", "rule-id-secret", "kr.example.shop", "7").forEach {
                    assertFalse("$it in $line", line.contains(it))
                }
            }
        }
    }

    // --- dates ---

    private val koreanPattern: (Locale, String) -> String = { _, skeleton ->
        if (skeleton == RuleDates.SKELETON_THIS_YEAR) "M월 d일" else "y년 M월 d일"
    }

    @Test fun dateIsMonthAndDayThisYear() {
        // 2026-11-13 17:46 UTC = 2026-11-14 02:46 in Seoul.
        assertEquals("11월 14일", RuleDates.format(expires, created, seoul, Locale.KOREA, koreanPattern))
        assertEquals("11월 13일", RuleDates.format(expires, created, ZoneId.of("UTC"), Locale.KOREA, koreanPattern))
    }

    @Test fun dateHasYearWhenNotThisYear() {
        val nextYear = created + 120L * 24 * 60 * 60 * 1000
        assertEquals("2026년 11월 14일", RuleDates.format(expires, nextYear, seoul, Locale.KOREA, koreanPattern))
    }

    @Test fun dateAsksForTheLocaleSkeleton() {
        val asked = mutableListOf<Pair<Locale, String>>()
        RuleDates.format(expires, created, seoul, Locale.US) { locale, skeleton ->
            asked += locale to skeleton
            "MMM d"
        }
        assertEquals(listOf(Locale.US to RuleDates.SKELETON_THIS_YEAR), asked)
    }

    // --- actions ---

    @Test fun deleteAllCallsTheGivenEraseOnceNotTheRepository() = runTest {
        val repository = InMemoryMockPopupRuleRepository().apply { save(rule()) }
        var erases = 0
        val actions = RememberedRuleActions(repository) { erases++ }
        assertEquals(DeleteAllResult.DONE, actions.deleteAll())
        assertEquals(1, erases)
        // Proves the default repository.deleteAll was not used instead of the passed function.
        assertEquals(1, repository.observeRules().first().size)
    }

    @Test fun secondDeleteAllWhileOneIsRunningIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        var erases = 0
        val actions = RememberedRuleActions(InMemoryMockPopupRuleRepository()) {
            erases++
            gate.await()
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { actions.deleteAll() }
        assertEquals(DeleteAllResult.ALREADY_RUNNING, actions.deleteAll())
        gate.complete(Unit)
        assertEquals(DeleteAllResult.DONE, first.await())
        assertEquals(1, erases)
        // Unlocked afterwards: a later press works again.
        assertEquals(DeleteAllResult.DONE, actions.deleteAll())
    }

    @Test fun failedEraseIsReportedNotThrown() = runTest {
        val actions = RememberedRuleActions(InMemoryMockPopupRuleRepository()) { error("keystore") }
        assertEquals(DeleteAllResult.FAILED, actions.deleteAll())
    }

    @Test fun revokeDisablesOnlyThatRule() = runTest {
        val repository = InMemoryMockPopupRuleRepository().apply { save(rule()) }
        val actions = RememberedRuleActions(repository) {}
        assertTrue(actions.revoke("rule-id-secret"))
        assertEquals(RuleStatus.REVOKED, status(repository.observeRules().first().single(), created + 1))
    }
}
