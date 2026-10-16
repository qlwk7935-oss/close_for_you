package kr.closeforyou.testing.mock

import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** TODO(B): real classification. A fixture answer is never a production inference. */
class MockPopupClassifier : PopupClassifier {
    override suspend fun classify(analysis: PopupAnalysis): PopupClassification = PopupClassification.UNKNOWN
}

/** TODO(B): policy engine. Even explicit permission cannot enable this mock. */
class DenyAllActionPolicy : ActionPolicy {
    override suspend fun decide(analysis: PopupAnalysis, context: DecisionContext): ActionDecision =
        ActionDecision(analysis.token, ActionAuthorization.DENY, persistentSetOf(SafetyReason.NOT_IMPLEMENTED))
}

/** No Android action APIs are referenced. ALLOW_ONCE supplied by a test still executes nothing. */
class NeverExecuteActionExecutor : ActionExecutor {
    override suspend fun execute(request: ActionRequest): ActionResult = ActionResult(
        token = request.decision.token,
        status = ActionStatus.NOT_EXECUTED,
        reason = SafetyReason.NOT_IMPLEMENTED,
        attempted = false,
        semanticSafetyConfirmedByUser = false,
    )
}

/** TODO(C/F10): actual TalkBack/TTS. Retains no announcement text and claims no delivery. */
class MockPopupAnnouncer : PopupAnnouncer {
    override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt = unavailable()
    override suspend fun repeatLast(): AnnouncementReceipt = unavailable()
    override fun stop() = Unit
    private fun unavailable() = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
}

/** TODO(C/F13): explicit speak-button flow. This mock never opens or requests a microphone. */
class MockVoiceCommandInput : VoiceCommandInput {
    private val mutableEvents = MutableSharedFlow<VoiceEvent>(replay = 0)
    override val events: Flow<VoiceEvent> = mutableEvents.asSharedFlow()
    override fun availability(): VoiceAvailability = VoiceAvailability.NOT_IMPLEMENTED
    override fun startFromSpeakButton(token: SnapshotToken): VoiceAvailability = availability()
    override fun cancel() = Unit
}

/** TODO(A): guarded capture, F0-B and OCR; capability is denied regardless of device API. */
class MockUnavailableCaptureCapability : CaptureCapability {
    override fun availability(token: SnapshotToken): CaptureAvailability = CaptureAvailability.NOT_IMPLEMENTED
}

/**
 * TODO(D): replace with Room and device-local keyed fingerprints. This is volatile test storage.
 * Finding a rule is not an authorization; B must recheck all policy and freshness conditions.
 */
class InMemoryMockPopupRuleRepository : PopupRuleRepository {
    private val mutex = Mutex()
    private val rules = MutableStateFlow<ImmutableList<PopupRule>>(persistentListOf())

    override suspend fun findExact(key: RuleKey, nowEpochMillis: Long): PopupRule? = mutex.withLock {
        rules.value.lastOrNull { rule ->
            rule.key == key && rule.enabled && rule.schemaVersion == 1 && rule.failureCount == 0 &&
                nowEpochMillis >= rule.createdAtEpochMillis &&
                (rule.expiresAtEpochMillis == null || nowEpochMillis < rule.expiresAtEpochMillis)
        }
    }

    override suspend fun save(rule: PopupRule) = mutex.withLock {
        // Replacing a choice must not leave an older permission able to resurface on revocation.
        rules.value = (rules.value.filterNot { it.id == rule.id || it.key == rule.key } + rule).toImmutableList()
    }

    override suspend fun revoke(id: String) = mutex.withLock {
        rules.value = rules.value.map { if (it.id == id) it.copy(enabled = false) else it }.toImmutableList()
    }

    override suspend fun deleteAll() = mutex.withLock { rules.value = persistentListOf() }

    override fun observeRules(): Flow<ImmutableList<PopupRule>> = rules.asStateFlow()
}

/**
 * Fixed lookup for these synthetic fixtures only. Does not hash or fingerprint arbitrary screens.
 * A's session digests must not become D's durable rule keys. Unknown/modified fixtures fail closed.
 */
class FixtureOnlyFingerprintProvider : PopupFingerprintProvider {
    override suspend fun createKey(
        snapshot: PopupSnapshot,
        target: CloseCandidate,
        appVersionCode: Long,
    ): RuleKey {
        require(appVersionCode > 0) { "App version must be positive" }
        val fixture = MockPopupFixtures.all().firstOrNull { it.snapshot == snapshot }
            ?: error("Only unchanged synthetic fixtures are supported")
        require(target in fixture.analysis.closeCandidates) { "Unknown fixture target" }
        return RuleKey(
            packageName = MockPopupFixtures.PACKAGE_NAME,
            appVersionCode = appVersionCode,
            structureFingerprint = "fixture-only-dialog-v1",
            exactContentFingerprint = when (fixture.scenarioId) {
                "notice" -> "fixture-only-notice-date-18"
                "notice-changed-date" -> "fixture-only-notice-date-19"
                else -> "fixture-only-${fixture.scenarioId}"
            },
            targetFingerprint = "fixture-only-${target.id}-${target.role.name}",
        )
    }
}

/** Opt-in test source; runtime AccessibilityService does not install or emit these fixtures. */
class MockSnapshotSource : SnapshotSource {
    private val mutableEvents = MutableSharedFlow<PopupEvent>(replay = 0)
    @Volatile private var token: SnapshotToken? = null
    override val events: Flow<PopupEvent> = mutableEvents.asSharedFlow()
    override fun currentToken(): SnapshotToken? = token

    suspend fun emitFixture(fixture: MockPopupFixture) {
        token = fixture.snapshot.token
        mutableEvents.emit(
            PopupEvent.Candidate(
                fixture.snapshot,
                PopupDetection(fixture.snapshot.token, persistentSetOf(PopupSignal.NEW_WINDOW)),
            ),
        )
    }

    suspend fun emitBlocked(screenVersion: Long) {
        token = null
        mutableEvents.emit(MockPopupFixtures.privacyBlocked(screenVersion))
    }

    suspend fun invalidate(screenVersion: Long) {
        token = null
        mutableEvents.emit(PopupEvent.Invalidated(screenVersion))
    }
}
