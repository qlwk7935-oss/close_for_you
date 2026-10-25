package kr.closeforyou.ui.voice.integration

import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kr.closeforyou.core.contract.PopupFingerprintProvider
import kr.closeforyou.core.contract.PopupRuleRepository
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupRule
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.RuleKey
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.core.model.UserChoice

/**
 * How long a [앞으로 항상 닫기] rule lives (team decision 10/21: expiry required, 30 days).
 * Same value as D's `RuleRetention.DEFAULT_TTL_MILLIS` (data/rules), which is D's class, not a shared
 * contract; `RememberedPopupStoreTest` checks the two stay equal. Move to core if the team agrees.
 */
object RememberRetention {
    const val TTL_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

    fun expiryFor(createdAtEpochMillis: Long): Long = createdAtEpochMillis + TTL_MILLIS
}

/**
 * A rule key made before the press, while the popup and its button are still on screen.
 * Not a data class: toString must not print fingerprints.
 */
class PreparedRule(val token: SnapshotToken, val key: RuleKey)

/**
 * Where [PopupChoiceFlow] hands a [앞으로 항상 닫기] choice, in two steps (team decision 10/21):
 * 1. [prepare] before the one-time press, on the same token. The fingerprint needs the button in
 *    the current analysis; after a successful press the popup is gone and D's provider fails.
 * 2. [commit] only when [kr.closeforyou.ui.voice.PressSuccess.isConfirmed] (attempted +
 *    MECHANICAL_SUCCESS + CLOSED_CONFIRMED). Any other result drops the prepared key.
 *
 * D owns the real storage (Room, fingerprints). Saving is never an authorization: a later auto close
 * still re-checks B's policy, A's gate, the exact popup and the app's auto close setting (AGENTS 6-4).
 * Neither step may log screen text, labels or fingerprints.
 */
interface RememberedPopupStore {
    /** [choice] is the person's REMEMBER_EXACT_POPUP choice. Null when this popup cannot be remembered. */
    suspend fun prepare(choice: UserChoice, snapshot: PopupSnapshot, analysis: PopupAnalysis): PreparedRule?

    /** Returns true only when the rule was saved. */
    suspend fun commit(prepared: PreparedRule): Boolean

    companion object {
        /** Nothing connected: nothing is saved, and the person is told so. */
        val NONE = object : RememberedPopupStore {
            override suspend fun prepare(choice: UserChoice, snapshot: PopupSnapshot, analysis: PopupAnalysis) = null
            override suspend fun commit(prepared: PreparedRule) = false
        }
    }
}

/**
 * Builds the contract's [PopupRule] through D's contract interfaces. With
 * `FixtureOnlyFingerprintProvider` + `InMemoryMockPopupRuleRepository` it is Mock only; D's
 * `RuleStorage.fingerprintProvider` / `RuleStorage.repository` are the real parts (not yet wired).
 * [appVersionCode] is looked up by package name (A/D decide where it comes from).
 */
class RepositoryRememberedPopupStore(
    private val fingerprints: PopupFingerprintProvider,
    private val repository: PopupRuleRepository,
    private val appVersionCode: (packageName: String) -> Long,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : RememberedPopupStore {
    override suspend fun prepare(choice: UserChoice, snapshot: PopupSnapshot, analysis: PopupAnalysis): PreparedRule? {
        if (choice.kind != ChoiceKind.REMEMBER_EXACT_POPUP ||
            !choice.buttonMeaningConfirmed || !choice.repeatAutomationExplicitlyAllowed ||
            choice.token != snapshot.token || choice.token != analysis.token
        ) {
            return null
        }
        val target = analysis.closeCandidates.singleOrNull { it.id == choice.candidateId } ?: return null
        return try {
            PreparedRule(choice.token, fingerprints.createKey(snapshot, target, appVersionCode(snapshot.token.packageName)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // D's FingerprintUnavailableException (truncated, incomplete evidence, ...): not rememberable.
            null
        }
    }

    override suspend fun commit(prepared: PreparedRule): Boolean {
        val createdAt = nowEpochMillis()
        return try {
            repository.save(
                PopupRule(
                    id = newId(),
                    key = prepared.key,
                    choice = ChoiceKind.REMEMBER_EXACT_POPUP,
                    buttonMeaningConfirmed = true,
                    // The person's permission is stored; repeat execution stays off (D's RepeatAutomation).
                    repeatAutomationExplicitlyAllowed = true,
                    createdAtEpochMillis = createdAt,
                    expiresAtEpochMillis = RememberRetention.expiryFor(createdAt),
                ),
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // D's RuleRejectedException or a storage error: not saved, the flow says so. No details logged.
            false
        }
    }
}
