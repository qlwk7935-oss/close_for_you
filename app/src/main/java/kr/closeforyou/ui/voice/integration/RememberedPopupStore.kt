package kr.closeforyou.ui.voice.integration

import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kr.closeforyou.core.contract.PopupFingerprintProvider
import kr.closeforyou.core.contract.PopupRuleRepository
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupRule
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.UserChoice

/**
 * Where [PopupChoiceFlow] hands a [앞으로 항상 닫기] choice after this popup's one-time press
 * succeeded. D owns the real storage (Room, fingerprints); today this is backed by Mock parts.
 * Saving is never an authorization: a later auto close still re-checks B's policy, A's gate, the
 * exact popup and the app's auto close setting (AGENTS 6-4).
 */
fun interface RememberedPopupStore {
    /**
     * [choice] is the person's REMEMBER_EXACT_POPUP choice. Returns true only when the rule was
     * saved. Must not log screen text, labels or fingerprints.
     */
    suspend fun save(choice: UserChoice, snapshot: PopupSnapshot, analysis: PopupAnalysis): Boolean

    companion object {
        /** Nothing connected: nothing is saved, and the person is told so. */
        val NONE = RememberedPopupStore { _, _, _ -> false }
    }
}

/**
 * Builds the contract's [PopupRule] through D's contract interfaces. With
 * `FixtureOnlyFingerprintProvider` + `InMemoryMockPopupRuleRepository` it is Mock only.
 * [appVersionCode] is looked up by package name (A/D decide where it comes from).
 */
class RepositoryRememberedPopupStore(
    private val fingerprints: PopupFingerprintProvider,
    private val repository: PopupRuleRepository,
    private val appVersionCode: (packageName: String) -> Long,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : RememberedPopupStore {
    override suspend fun save(choice: UserChoice, snapshot: PopupSnapshot, analysis: PopupAnalysis): Boolean {
        if (choice.kind != ChoiceKind.REMEMBER_EXACT_POPUP ||
            !choice.buttonMeaningConfirmed || !choice.repeatAutomationExplicitlyAllowed ||
            choice.token != snapshot.token || choice.token != analysis.token
        ) {
            return false
        }
        val target = analysis.closeCandidates.singleOrNull { it.id == choice.candidateId } ?: return false
        return try {
            val key = fingerprints.createKey(snapshot, target, appVersionCode(snapshot.token.packageName))
            repository.save(
                PopupRule(
                    id = newId(),
                    key = key,
                    choice = ChoiceKind.REMEMBER_EXACT_POPUP,
                    buttonMeaningConfirmed = true,
                    repeatAutomationExplicitlyAllowed = true,
                    createdAtEpochMillis = nowEpochMillis(),
                    // [팀 확정 필요] D decides expiry; none here, revocable from C's settings.
                    expiresAtEpochMillis = null,
                ),
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Unknown popup for the fingerprint provider, storage error: not saved. No details logged.
            false
        }
    }
}
