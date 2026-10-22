package kr.closeforyou.decision

import kr.closeforyou.core.contract.ActionExecutor
import kr.closeforyou.core.contract.ActionPolicy
import kr.closeforyou.core.contract.ActionRequest
import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.core.contract.SnapshotSource
import kr.closeforyou.core.model.ActionAuthorization
import kr.closeforyou.core.model.ActionDecision
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.SafetyReason
import kr.closeforyou.core.model.UserChoice
import kotlinx.coroutines.CancellationException

/**
 * B: the "choice → B policy → A gate" path for a person's choice on C's prompt. Same shape as C's
 * `ChoiceHandler.handle`, so it can be passed as `ChoiceHandler(handler::handle)` without B
 * depending on C's code.
 *
 * - Choices that press nothing return null and never reach the gate.
 * - Anything but ALLOW_ONCE returns a not-executed result; the gate is not called.
 * - On ALLOW_ONCE the screen token is checked once more, then the gate is called exactly once.
 *   The gate still rereads the real screen and decides; ALLOW_ONCE is only a request.
 * - Nothing is retried (AGENTS 6-6). A gate failure becomes OUTCOME_UNKNOWN, never a retry, and
 *   its message is not kept (it may contain screen text).
 *
 * Remembered rules are not consulted: the person's current choice wins over a rule, and automatic
 * repeats stay off until the contract carries the per-app setting and attempt history.
 */
class DecisionChoiceHandler(
    private val source: SnapshotSource,
    private val executor: ActionExecutor,
    /** From A: whether the person is touching or exploring the screen right now. */
    private val userInteracting: () -> Boolean,
    /** From C: whether our own TTS can tell the person what happened. */
    private val announcementAvailable: () -> Boolean,
    private val policy: ActionPolicy = DecisionActionPolicy(),
) {
    suspend fun handle(choice: UserChoice, analysis: PopupAnalysis): ActionResult? {
        if (choice.kind != ChoiceKind.CLOSE_ONCE && choice.kind != ChoiceKind.SKIP_ONCE) return null

        val decision = policy.decide(
            analysis,
            DecisionContext(
                currentToken = source.currentToken(),
                userChoice = choice,
                matchingRule = null,
                userInteracting = userInteracting(),
                announcementAvailable = announcementAvailable(),
            ),
        )
        if (decision.authorization != ActionAuthorization.ALLOW_ONCE) return notExecuted(analysis, decision)

        val candidate = analysis.closeCandidates.singleOrNull { it.id == decision.candidateId }
        if (candidate == null || decision.candidateId != choice.candidateId || decision.token != analysis.token) {
            return result(analysis, ActionStatus.BLOCKED, SafetyReason.UNVERIFIED_TARGET)
        }
        // The policy may have suspended; the screen must still be the analysed one.
        if (source.currentToken() != analysis.token) return result(analysis, ActionStatus.STALE, SafetyReason.STALE_SCREEN)

        return try {
            executor.execute(ActionRequest(decision, candidate))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The gate may or may not have acted: report it as possibly attempted so nobody retries.
            ActionResult(analysis.token, ActionStatus.OUTCOME_UNKNOWN, reason = null, attempted = true)
        }
    }

    private fun notExecuted(analysis: PopupAnalysis, decision: ActionDecision): ActionResult {
        val reason = REASON_PRIORITY.firstOrNull { it in decision.reasons } ?: decision.reasons.firstOrNull()
        val status = when {
            reason == SafetyReason.STALE_SCREEN -> ActionStatus.STALE
            decision.authorization == ActionAuthorization.DENY -> ActionStatus.BLOCKED
            else -> ActionStatus.NOT_EXECUTED
        }
        return result(analysis, status, reason)
    }

    private fun result(analysis: PopupAnalysis, status: ActionStatus, reason: SafetyReason?) =
        ActionResult(analysis.token, status, reason, attempted = false, semanticSafetyConfirmedByUser = false)

    private companion object {
        /** The reason C should explain first when several apply: freshness, then safety, then asking. */
        val REASON_PRIORITY = listOf(
            SafetyReason.STALE_SCREEN, SafetyReason.USER_INTERACTION, SafetyReason.IMPORTANT_DECISION,
            SafetyReason.PRIVACY_BLOCKED, SafetyReason.UNVERIFIED_TARGET, SafetyReason.EXPLICIT_PERMISSION_REQUIRED,
            SafetyReason.FIRST_SEEN, SafetyReason.RULE_MISMATCH, SafetyReason.ALREADY_ATTEMPTED,
            SafetyReason.INCOMPLETE_EVIDENCE, SafetyReason.FEEDBACK_UNAVAILABLE, SafetyReason.NOT_IMPLEMENTED,
        )
    }
}
