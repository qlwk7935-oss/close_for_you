package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.CancellationException
import kr.closeforyou.accessibility.diagnosticWrite
import kr.closeforyou.core.contract.PopupClassifier
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.overlay.ChoicePromptPresenter
import kr.closeforyou.ui.voice.overlay.PromptAnswer
import kr.closeforyou.ui.voice.overlay.PromptShowResult

internal enum class ChoiceFlowPoint {
    CANDIDATE_RECEIVED, CURRENT_ACCEPTED, CURRENT_REJECTED, CLASSIFIED, PRESENT_REQUESTED,
    SHOWN, BLOCKED, FAILED, PRESENT_CANCELLED, PRESENT_EXCEPTION, INVALIDATED,
    SESSION_CANCELLED, SESSION_EXCEPTION,
}
internal enum class ChoiceFlowDetail {
    NONE, SESSION_UNAVAILABLE, TOKEN_MISMATCH, INVALID_CANDIDATE, DUPLICATE,
    SERVICE_BOUNDARY, CONSUMER_BOUNDARY,
}
internal enum class ChoiceInvalidationCause { EVENT, TTL, SERVICE, POLICY, UNKNOWN }

/** Observations only. Retains no candidate, token, exception, clock or Android object.
 * Release supplies null. A failed non-suspending sink cannot alter work or cancellation.
 */
internal class ChoiceFlowDiagnostic(
    private val write: (ChoiceFlowPoint, ChoiceFlowDetail, ChoiceInvalidationCause) -> Unit,
) {
    fun record(point: ChoiceFlowPoint, detail: ChoiceFlowDetail = ChoiceFlowDetail.NONE,
        cause: ChoiceInvalidationCause = ChoiceInvalidationCause.UNKNOWN) {
        diagnosticWrite { write(point, detail, cause) }
    }
    override fun toString() = "ChoiceFlowDiagnostic(redacted)"
}

/** A-owned wrappers around the same real B/C calls. No retries, getters or altered answers. */
internal fun diagnosedClassifier(delegate: PopupClassifier, diagnostic: ChoiceFlowDiagnostic?): PopupClassifier =
    if (diagnostic == null) delegate else object : PopupClassifier {
        override suspend fun classify(analysis: PopupAnalysis) = delegate.classify(analysis).also {
            diagnostic.record(ChoiceFlowPoint.CLASSIFIED)
        }
    }

internal fun diagnosedPresenter(delegate: ChoicePromptPresenter, diagnostic: ChoiceFlowDiagnostic?): ChoicePromptPresenter =
    if (diagnostic == null) delegate else object : ChoicePromptPresenter by delegate {
        override fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult {
            diagnostic.record(ChoiceFlowPoint.PRESENT_REQUESTED)
            val result = try { delegate.present(prompt, onAnswer) }
            catch (cancelled: CancellationException) {
                diagnostic.record(ChoiceFlowPoint.PRESENT_CANCELLED)
                throw cancelled
            } catch (failure: Exception) {
                diagnostic.record(ChoiceFlowPoint.PRESENT_EXCEPTION)
                throw failure
            }
            diagnostic.record(when (result) {
                PromptShowResult.SHOWN -> ChoiceFlowPoint.SHOWN
                PromptShowResult.BLOCKED -> ChoiceFlowPoint.BLOCKED
                PromptShowResult.FAILED -> ChoiceFlowPoint.FAILED
            })
            return result
        }
    }
