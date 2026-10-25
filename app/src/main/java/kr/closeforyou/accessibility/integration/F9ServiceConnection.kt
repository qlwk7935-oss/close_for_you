package kr.closeforyou.accessibility.integration

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.core.contract.PopupAnnouncer
import kr.closeforyou.core.contract.SnapshotSource
import kr.closeforyou.core.contract.ActionPolicy
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.decision.DecisionActionPolicy
import kr.closeforyou.ui.voice.settings.UiVoicePreferences
import kr.closeforyou.ui.voice.AnnouncementBatcher
import kr.closeforyou.ui.voice.UiFormat

/** A-owned assembly port, not installed into the production service. B's result port and C's
 * live-gated consent source are connected; automatic evidence and the additional consent adapter
 * remain absent. Production stays OFF.
 *
 * Once the policy and consent adapters are supplied, the service assembly may own this beside its overlay,
 * supply its real cleanup.ready/native-window state, and close it BEFORE service scope cancellation.
 * Never turn cleanupReady into a constant or reinterpret a display classification as a B permit.
 */
internal class F9ServiceConnection(
    service: AccessibilityService,
    scope: CoroutineScope,
    source: SnapshotSource,
    announcer: PopupAnnouncer,
    /** Required shared instance: pass the SAME one to PopupChoiceFlow; never make a private batcher. */
    batcher: AnnouncementBatcher,
    format: UiFormat,
    connected: () -> Boolean,
    userInteracting: () -> Boolean,
    cleanupReady: () -> Boolean,
    /** C must provide an agreed HandlingPreset + first-seen consent adapter; absent means false.
     * The existing C per-app F12 toggle alone is NOT first-seen consent.
     */
    presetAndFirstSeenConsent: (String) -> Boolean = { false },
    // One session policy, injectable when the service also owns a user-choice policy path.
    actionPolicy: ActionPolicy = DecisionActionPolicy(allowAutoSkip = false, nowUptimeMillis = SystemClock::uptimeMillis),
    policyEvidence: suspend (F9Reference, F9Scene) -> F9PolicyInput? = { _, _ -> null },
) {
    private val generation = generations.incrementAndGet()
    private val revision = AtomicLong()
    private val core = CorePreferences.get(service)
    private val ui = UiVoicePreferences.get(service)
    private val feedback = F9FeedbackRouter(announcer, batcher, format)
    private val policy = F9ActionPolicyBridge(actionPolicy, evidence = policyEvidence, onReport = { report ->
        // Capture this exact report now, not a callback that might read a newer decision later.
        scope.launch {
            // WAIT/ASK/ANNOUNCE is still about the current screen. Attempted results deliberately
            // describe the completed past attempt, after its popup token may already be gone.
            if (report.result?.attempted != true && source.currentToken() != report.token) return@launch
            try { feedback.deliver(report) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* C alone owns automatic speech. No retry or legacy fallback. */ }
        }
    })
    private val closer = F9AutoCloser(AndroidF9ScreenPort(service), runtime = {
        val token = source.currentToken()
        val app = token?.packageName
        val settings = core.currentSettings()
        // #34 owns consent and the live=false feature gate. Re-read it at every A gate check;
        // the older exact-rule per-app toggle or the additional A adapter cannot replace it.
        val consent = ui.autoCloseConsentSource.current()
        val allowsAdvertisement = consent.granted && PopupClassification.ADVERTISEMENT in consent.autoCloseKinds
        F9RuntimeState(token, generation, revision.get(), connected(), settings.consentGranted,
            app != null && app in settings.allowedPackages,
            app != null && consent.granted && ui.current().autoCloseAllowed(app, settings.allowedPackages) && presetAndFirstSeenConsent(app),
            app != null && allowsAdvertisement && presetAndFirstSeenConsent(app), settings.paused, userInteracting(), cleanupReady())
    }, clock = F9Clock(SystemClock::uptimeMillis), policy = policy)

    /** The snapshot is the ORIGINAL F1 evidence, never the execution reader's refreshed frame.
     * The supplied bridge owns B's complete scoped-text authorization inside this request. Do not
     * pre-approve with that policy or deliver the returned result to it a second time.
     */
    suspend fun execute(snapshot: PopupSnapshot, target: CloseCandidate, pageBeforePopup: F9PageProof? = null): F9Result =
        closer.execute(F9Reference(snapshot, target, generation, revision.get(), pageBeforePopup))

    /** Wire to consent/preset/exclusion/interaction invalidation, not to click-result screen events.
     * Direct settings reads also enforce pause/revocation immediately at the final gate.
     */
    fun invalidatePermissionOrInteraction() { revision.incrementAndGet() }
    /** WAIT lifecycle ports: connect to confirmed popup identity/removal, not every new token. */
    fun onNewPopup() { feedback.onNewPopup() }
    fun onPopupRemoved() { feedback.onPopupRemoved() }
    fun onServiceReconnected() { revision.incrementAndGet(); feedback.onServiceReconnected() }
    /** Call before scope cancellation; the composition root cancels its shared batcher once. */
    fun close() { closer.close(); revision.incrementAndGet(); feedback.close() }

    private companion object { val generations = AtomicLong() }
}
