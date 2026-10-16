package kr.closeforyou.testing.mock

import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.*
import kotlinx.coroutines.flow.collectLatest

/** Compile-checked usage examples. Nothing invokes these functions at application startup. */
object IntegrationExample {
    /** B receives immutable values; this returns DENY until B's actual engine is provided. */
    suspend fun runBExample(fixture: MockPopupFixture = MockPopupFixtures.notice()): ActionDecision {
        val classifier: PopupClassifier = MockPopupClassifier()
        val policy: ActionPolicy = DenyAllActionPolicy()
        val analysis = fixture.analysis.copy(classification = classifier.classify(fixture.analysis))
        return policy.decide(
            analysis,
            DecisionContext(
                currentToken = fixture.snapshot.token,
                userChoice = null,
                matchingRule = null,
                userInteracting = false,
                announcementAvailable = false,
            ),
        )
    }

    /** C must implement F10 before this can claim UI/audio output. */
    suspend fun runCExample(): AnnouncementReceipt {
        val announcer: PopupAnnouncer = MockPopupAnnouncer()
        return announcer.announce(
            AnnouncementRequest(
                token = MockPopupFixtures.notice().snapshot.token,
                kind = AnnouncementKind.POPUP_FOUND,
                text = "시험용 팝업 후보입니다. 자동으로 닫지 않았습니다.",
            ),
        )
    }

    /**
     * D maps separately collected user confirmations to a rule, rather than deriving consent
     * from a popup disappearance, STOP_ASKING, a voice command, or successful speech playback.
     */
    suspend fun saveExplicitChoiceExample(
        fixture: MockPopupFixture,
        choice: UserChoice,
        repository: PopupRuleRepository,
        nowEpochMillis: Long,
        appVersionCode: Long = 1,
    ): PopupRule {
        require(choice.token == fixture.snapshot.token) { "Choice must match the current fixture" }
        require(choice.kind == ChoiceKind.REMEMBER_EXACT_POPUP || choice.kind == ChoiceKind.STOP_ASKING) {
            "Only explicit remembered choices belong in this example"
        }
        val target = fixture.analysis.closeCandidates.singleOrNull { it.id == choice.candidateId }
            ?: error("An exact fixture target is required")
        val key = FixtureOnlyFingerprintProvider().createKey(fixture.snapshot, target, appVersionCode)
        return PopupRule(
            id = "fixture-rule-${fixture.scenarioId}",
            key = key,
            choice = choice.kind,
            buttonMeaningConfirmed = choice.buttonMeaningConfirmed,
            repeatAutomationExplicitlyAllowed = choice.repeatAutomationExplicitlyAllowed,
            createdAtEpochMillis = nowEpochMillis,
            expiresAtEpochMillis = nowEpochMillis + 60_000,
        ).also { repository.save(it) }
    }

    /**
     * Start in a lifecycle-owned coroutine; cancel that job when its screen/service stops.
     * collectLatest cancels slow analysis on Invalidated/Blocked/new candidates. Also recheck
     * currentToken after suspension; A's future action gate must still reread the actual screen.
     * This example exposes only a decision callback and never invokes an action executor.
     */
    suspend fun collectBDecisionsExample(
        source: SnapshotSource,
        extractor: EvidenceExtractor,
        classifier: PopupClassifier,
        policy: ActionPolicy,
        onDecision: suspend (ActionDecision) -> Unit,
        onDiscard: () -> Unit,
    ) {
        source.events.collectLatest { event ->
            when (event) {
                is PopupEvent.Candidate -> {
                    if (source.currentToken() != event.snapshot.token) return@collectLatest
                    val evidence = extractor.extract(event.snapshot)
                    val analysis = evidence.copy(classification = classifier.classify(evidence))
                    if (source.currentToken() != event.snapshot.token) return@collectLatest
                    val decision = policy.decide(
                        analysis,
                        DecisionContext(source.currentToken(), null, null, false, false),
                    )
                    if (source.currentToken() == decision.token) onDecision(decision)
                }
                is PopupEvent.Blocked, is PopupEvent.Invalidated -> onDiscard()
            }
        }
    }
}
