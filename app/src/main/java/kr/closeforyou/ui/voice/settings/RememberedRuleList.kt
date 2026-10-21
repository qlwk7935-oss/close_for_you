package kr.closeforyou.ui.voice.settings

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kr.closeforyou.R
import kr.closeforyou.core.contract.PopupRuleRepository
import kr.closeforyou.core.model.PopupRule

/** What the settings list says about one remembered rule (D review #18-2: not just `enabled`). */
enum class RuleStatus {
    /** Still matches exact popups (B and A still re-check before anything runs). */
    ON,
    /** expiresAt ≤ now. Never matches again; [해제]·[전체 삭제] still remove it. */
    EXPIRED,
    /** A remembered press failed (D's `recordFailure`); never matches again. */
    FAILED,
    /** Cannot be used for another reason: no expiry, other schema, saved "in the future". */
    OFF,
    /** The person pressed [해제] (D keeps it disabled so the list can say so). */
    REVOKED,
}

/**
 * Pure status rules, the same conditions as D's `RuleValidity.isUsable` minus the key match.
 * Order: the person's own [해제] first, then a failed run, then the other unusable cases, then time.
 */
object RuleStatusPolicy {
    /** Same as D's `CURRENT_RULE_SCHEMA_VERSION` (D's class); `RememberedRuleListTest` keeps them equal. */
    const val SUPPORTED_SCHEMA_VERSION = 1

    fun statusOf(rule: PopupRule, nowEpochMillis: Long): RuleStatus {
        val expiresAt = rule.expiresAtEpochMillis
        return when {
            !rule.enabled -> RuleStatus.REVOKED
            rule.failureCount > 0 -> RuleStatus.FAILED
            expiresAt == null ||
                rule.schemaVersion != SUPPORTED_SCHEMA_VERSION ||
                nowEpochMillis < rule.createdAtEpochMillis -> RuleStatus.OFF
            nowEpochMillis >= expiresAt -> RuleStatus.EXPIRED
            else -> RuleStatus.ON
        }
    }

    /** Every rule not yet revoked gets [해제], expired and failed ones included. */
    fun canRevoke(status: RuleStatus): Boolean = status != RuleStatus.REVOKED
}

/**
 * One list line, read by TalkBack as a single node: app name · choice · status (with the date).
 * Only these parts go in: no screen text, button label, fingerprint or rule id. The app name falls
 * back to the package name when the app is not installed (same as the auto close list).
 */
object RuleListText {
    fun summary(
        rule: PopupRule,
        appName: String,
        nowEpochMillis: Long,
        formatDate: (epochMillis: Long) -> String,
        text: (resId: Int, args: Array<out Any>) -> String,
    ): String {
        val status = RuleStatusPolicy.statusOf(rule, nowEpochMillis)
        val statusText = when (status) {
            RuleStatus.ON -> text(R.string.ui_settings_rule_status_on, arrayOf(formatDate(rule.expiresAtEpochMillis!!)))
            RuleStatus.EXPIRED ->
                text(R.string.ui_settings_rule_status_expired, arrayOf(formatDate(rule.expiresAtEpochMillis!!)))
            RuleStatus.FAILED -> text(R.string.ui_settings_rule_status_failed, emptyArray())
            RuleStatus.OFF -> text(R.string.ui_settings_rule_status_off, emptyArray())
            RuleStatus.REVOKED -> text(R.string.ui_settings_rule_revoked, emptyArray())
        }
        return text(R.string.ui_settings_rule_summary, arrayOf(appName, text(rule.choice.ruleLabelRes(), emptyArray()), statusText))
    }
}

/**
 * Month and day in the device locale ("11월 21일"), with the year only when it is not this year.
 * [bestPattern] is `android.text.format.DateFormat::getBestDateTimePattern` on the device.
 */
object RuleDates {
    const val SKELETON_THIS_YEAR = "MMMd"
    const val SKELETON_OTHER_YEAR = "yMMMd"

    fun format(
        epochMillis: Long,
        nowEpochMillis: Long,
        zone: ZoneId,
        locale: Locale,
        bestPattern: (Locale, String) -> String,
    ): String {
        val year = Instant.ofEpochMilli(epochMillis).atZone(zone).year
        val thisYear = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).year
        val pattern = bestPattern(locale, if (year == thisYear) SKELETON_THIS_YEAR else SKELETON_OTHER_YEAR)
        return SimpleDateFormat(pattern, locale)
            .apply { timeZone = TimeZone.getTimeZone(zone) }
            .format(Date(epochMillis))
    }
}

enum class DeleteAllResult { DONE, FAILED, ALREADY_RUNNING }

/**
 * [해제] and [전체 삭제] for the settings list. [eraseAll] is what [전체 삭제] really does: the
 * repository's `deleteAll` today, D's `RuleStorage.eraseAll(context)` (rules + fingerprint key) once
 * A wires F12. A second press while one erase is running is ignored, so it runs once.
 * Failures return false without logging anything (no rule ids or keys in logs).
 */
class RememberedRuleActions(
    private val repository: PopupRuleRepository,
    private val eraseAll: suspend () -> Unit,
) {
    private val erasing = Mutex()

    suspend fun revoke(id: String): Boolean = runCatchingStorage { repository.revoke(id) }

    suspend fun deleteAll(): DeleteAllResult {
        if (!erasing.tryLock()) return DeleteAllResult.ALREADY_RUNNING
        return try {
            if (runCatchingStorage { eraseAll() }) DeleteAllResult.DONE else DeleteAllResult.FAILED
        } finally {
            erasing.unlock()
        }
    }

    private inline fun runCatchingStorage(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
