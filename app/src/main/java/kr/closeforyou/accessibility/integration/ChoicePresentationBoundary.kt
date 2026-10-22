package kr.closeforyou.accessibility.integration

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptSelection
import kr.closeforyou.ui.voice.overlay.*

/** No reset API: C's later successful cleanup/reconnection does not restore A choice permission. */
internal class ChoicePresentationHealth {
    private val uncertain = AtomicBoolean()
    val failed: Boolean get() = uncertain.get()
    fun fail() { uncertain.set(true) }
    override fun toString() = "ChoicePresentationHealth(redacted)"
}

/** Main-thread A port shared by C's real present/answer flow and JVM fault tests.
 * C generation/origin are consistency metadata, never user-origin authentication or an F0 proof.
 */
internal class GuardedChoicePresenter(
    private val delegate: ChoicePromptPresenter,
    private val current: () -> SnapshotToken?,
    private val userInteracting: () -> Boolean,
    private val health: ChoicePresentationHealth,
    private val onFailure: () -> Unit,
) : ChoicePromptPresenter {
    private var active: Any? = null
    private var activeGeneration = 0L
    private var lastGeneration = 0L
    private var notified = false

    /** Revoke before C cleanup can reenter, even before Runtime invalidation. */
    fun revoke() { active = null; activeGeneration = 0L }
    private fun reject() {
        revoke(); health.fail()
        if (!notified) {
            notified = true
            try { onFailure() } catch (_: Exception) { /* Preserve primary failure; refusal is sticky. */ }
        }
    }
    override val windowState: OverlayWindowState
        get() = try { delegate.windowState }
        catch (cancelled: CancellationException) { reject(); throw cancelled }
        catch (_: Exception) { reject(); throw IllegalStateException("CHOICE_PRESENTATION_FAILED") }

    override val isShowing: Boolean
        get() {
            if (health.failed || active == null) return false
            val state = windowState
            if (state.status == OverlayWindowStatus.REMOVE_FAILED ||
                (state.status == OverlayWindowStatus.SHOWING && state.generation != activeGeneration)) {
                reject(); return false
            }
            return state.status == OverlayWindowStatus.SHOWING
        }

    override fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult {
        revoke()
        val token = prompt.token
        if (health.failed || current() != token || userInteracting()) return PromptShowResult.BLOCKED
        val lease = Any()
        val offered = prompt.options.mapNotNull {
            (ChoicePromptPlanner.select(prompt, it) as? PromptSelection.Choice)?.choice
        }.filter { it.kind in setOf(ChoiceKind.READ_CONTENT, ChoiceKind.KEEP_OPEN,
            ChoiceKind.CLOSE_ONCE, ChoiceKind.SKIP_ONCE) }.toSet()
        var generation = 0L
        var delivering = false
        val result = try {
            delegate.present(prompt) answerCallback@ { answer ->
                try {
                    if (active !== lease || health.failed || delivering || generation <= 0 ||
                        answer.generation != generation || current() != token || userInteracting()) return@answerCallback
                    val state = windowState
                    if (answer is PromptAnswer.Lost || answer is PromptAnswer.Move ||
                        state.status == OverlayWindowStatus.REMOVE_FAILED) {
                        reject(); return@answerCallback // No move/focus port and no retained Lost choice.
                    }
                    if (answer !is PromptAnswer.Picked || answer.choice !in offered) return@answerCallback
                    val reading = answer.choice.kind == ChoiceKind.READ_CONTENT
                    val validWindow = if (reading) state.status == OverlayWindowStatus.SHOWING && state.generation == generation
                        else state.status == OverlayWindowStatus.HIDDEN && state.generation == 0L
                    if (!validWindow) { reject(); return@answerCallback }
                    if (!reading) revoke()
                    delivering = true
                    onAnswer(answer) // Preserve origin as C metadata, not authorization.
                } catch (cancelled: CancellationException) { reject(); throw cancelled }
                catch (_: Exception) { reject() }
                finally { delivering = false }
            }
        } catch (cancelled: CancellationException) { reject(); throw cancelled }
        catch (_: Exception) { reject(); throw IllegalStateException("CHOICE_PRESENTATION_FAILED") }
        if (result != PromptShowResult.SHOWN) { reject(); return result }
        val state = windowState
        if (state.status != OverlayWindowStatus.SHOWING || state.generation <= lastGeneration) {
            reject(); return PromptShowResult.FAILED
        }
        generation = state.generation
        lastGeneration = generation
        if (health.failed || current() != token || userInteracting()) {
            dismiss(); return PromptShowResult.BLOCKED
        }
        activeGeneration = generation
        active = lease // No callback from a partial or failed present can be accepted.
        return PromptShowResult.SHOWN
    }

    override fun dismiss() {
        revoke()
        val alreadyFailed = health.failed
        try {
            delegate.dismiss()
            val state = windowState
            if (state.status != OverlayWindowStatus.HIDDEN || state.generation != 0L) {
                reject()
                if (!alreadyFailed) throw IllegalStateException("CHOICE_DISMISS_FAILED")
            }
        } catch (cancelled: CancellationException) { reject(); throw cancelled }
        catch (_: Exception) {
            reject()
            // Record a cleanup failure without replacing an already-unwinding show/callback error.
            if (!alreadyFailed) throw IllegalStateException("CHOICE_DISMISS_FAILED")
        }
    }
    override fun toString() = "GuardedChoicePresenter(redacted)"
}
