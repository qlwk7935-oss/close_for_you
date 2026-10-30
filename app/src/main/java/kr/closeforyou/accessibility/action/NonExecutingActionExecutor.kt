package kr.closeforyou.accessibility.action

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kr.closeforyou.core.contract.ActionExecutor
import kr.closeforyou.core.contract.ActionRequest
import kr.closeforyou.core.model.*

/** Not wired into the service. No Android action port/API exists, even through injection. */
internal class NonExecutingActionExecutor(
    private val source: ActionGateContextSource = ActionGateContextSource { null },
    private val clock: GateClock = GateClock { System.nanoTime() / 1_000_000L },
    private val ledger: ActionAttemptLedger = ActionAttemptLedger(),
    private val validator: ActionGateValidator = ActionGateValidator(),
) : ActionExecutor {
    override suspend fun execute(request: ActionRequest): ActionResult {
        inspect(request)
        return ActionResult(request.decision.token, ActionStatus.NOT_EXECUTED,
            SafetyReason.NOT_IMPLEMENTED, attempted = false, semanticSafetyConfirmedByUser = false)
    }

    /** Fixed codes only. Cancellation propagates; exceptions expose no message or screen data. */
    suspend fun inspect(request: ActionRequest): GateEvaluation {
        var reservation: ActionAttemptLedger.Reservation? = null
        var terminal = ActionAttemptLedger.ReviewState.FAILED
        val unavailable = setOf(GateDenial.ANDROID_REVALIDATION_NOT_IMPLEMENTED)
        try {
            currentCoroutineContext().ensureActive()
            val first = source.read()
            currentCoroutineContext().ensureActive()
            val initial = validator.evaluate(request, first, clock.nowMillis())
            if (!initial.syntheticChecksSatisfied || first == null) return initial
            val reserved = ledger.reserveReview(first.registration.episode, first.popupContinuity)
            reservation = reserved.reservation
            if (reservation == null) return GateEvaluation(unavailable + requireNotNull(reserved.denial))
            // A suspension may invalidate consent, session, token, proof, target or original TTL.
            val latest = source.read()
            currentCoroutineContext().ensureActive()
            val final = validator.evaluate(request, latest, clock.nowMillis())
            if (latest != first) return GateEvaluation(final.denials + GateDenial.INVALIDATED_DURING_CHECK)
            if (!final.syntheticChecksSatisfied) return final
            // Do not pass the validator's earlier timestamp. Re-observe cancellation and time
            // inside the ledger's completion monitor, using the original registration deadline.
            ledger.completeReview(reservation, first.registration.issuedAtMillis,
                first.registration.expiresAtMillis, clock, currentCoroutineContext())
                ?.let { return GateEvaluation(unavailable + it) }
            return final // Still ANDROID_REVALIDATION_NOT_IMPLEMENTED, never ALLOW.
        } catch (cancelled: CancellationException) {
            terminal = ActionAttemptLedger.ReviewState.CANCELLED
            throw cancelled
        } catch (_: Exception) {
            return GateEvaluation(unavailable + GateDenial.INSPECTION_FAILED)
        } finally {
            reservation?.let { ledger.finishReview(it, terminal) }
        }
    }
}
