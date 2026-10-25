package kr.closeforyou.accessibility.integration

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.core.contract.PopupAnnouncer
import kr.closeforyou.core.contract.SnapshotSource
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.ui.voice.settings.UiVoicePreferences

/** A-owned assembly port. Not installed into the production service: B's automatic policy and
 * C's HandlingPreset/first-seen consent are not connected. Missing either stays OFF.
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
    connected: () -> Boolean,
    userInteracting: () -> Boolean,
    cleanupReady: () -> Boolean,
    /** C must provide an agreed HandlingPreset + first-seen consent adapter; absent means false.
     * The existing C per-app F12 toggle alone is NOT first-seen consent.
     */
    presetAndFirstSeenConsent: (String) -> Boolean = { false },
    policy: F9PolicyBridge = F9PolicyBridge.UNAVAILABLE,
) {
    private val generation = generations.incrementAndGet()
    private val revision = AtomicLong()
    private val core = CorePreferences.get(service)
    private val ui = UiVoicePreferences.get(service)
    private val closer = F9AutoCloser(AndroidF9ScreenPort(service), runtime = {
        val token = source.currentToken()
        val app = token?.packageName
        val settings = core.currentSettings()
        F9RuntimeState(token, generation, revision.get(), connected(), settings.consentGranted,
            app != null && app in settings.allowedPackages,
            app != null && ui.current().autoCloseAllowed(app, settings.allowedPackages) && presetAndFirstSeenConsent(app),
            app != null && presetAndFirstSeenConsent(app), settings.paused, userInteracting(), cleanupReady())
    }, clock = F9Clock(SystemClock::uptimeMillis), policy = policy, announce = { result ->
        // No synchronous TTS callback can split the action commit. Speech belongs to service scope.
        scope.launch { F9ResultFeedback.deliver(result.toActionResult(), announcer) }
    })

    /** The snapshot is the ORIGINAL F1 evidence, never the execution reader's refreshed frame.
     * Call only after B's complete scoped-text policy has produced an approval on that evidence.
     */
    suspend fun execute(snapshot: PopupSnapshot, target: CloseCandidate, pageBeforePopup: F9PageProof? = null): F9Result =
        closer.execute(F9Reference(snapshot, target, generation, revision.get(), pageBeforePopup))

    /** Wire to consent/preset/exclusion/interaction invalidation, not to click-result screen events.
     * Direct settings reads also enforce pause/revocation immediately at the final gate.
     */
    fun invalidatePermissionOrInteraction() { revision.incrementAndGet() }
    fun close() { closer.close(); revision.incrementAndGet() }

    private companion object { val generations = AtomicLong() }
}
