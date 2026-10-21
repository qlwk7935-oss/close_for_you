package kr.closeforyou.decision

import kr.closeforyou.core.contract.ActionExecutor
import kr.closeforyou.core.contract.ActionRequest
import kr.closeforyou.core.contract.EvidenceExtractor
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.contract.SnapshotSource
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same shape as C's `ChoiceHandler` (ui/voice/integration/PopupChoiceFlow.kt), copied so B does not depend on C. */
private fun interface CsChoiceHandler {
    suspend fun handle(choice: UserChoice, analysis: PopupAnalysis): ActionResult?
}

class DecisionCandidateWiringTest {
    private val notice = MockPopupFixtures.notice()
    private val ad = MockPopupFixtures.advertisement()
    private val token = notice.snapshot.token

    /** A's source: events queued by the test, delivered on the next collection. */
    private class FakeSource(var token: SnapshotToken?) : SnapshotSource {
        val pending = mutableListOf<PopupEvent>()
        override val events: Flow<PopupEvent> = flow {
            val now = pending.toList()
            pending.clear()
            emitAll(now.asFlow())
        }
        override fun currentToken(): SnapshotToken? = token
    }

    /** A's extractor today: the fixture's analysis, marked like the real runtime, with no candidates. */
    private val aExtractor = object : EvidenceExtractor {
        override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis =
            listOf(notice, ad).first { it.snapshot.token.copy(screenVersion = 0) == snapshot.token.copy(screenVersion = 0) }.analysis.copy(
                token = snapshot.token,
                closeCandidates = persistentListOf(),
                limitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE, AnalysisLimit.IMAGE_MODEL_NOT_IMPLEMENTED),
            )
    }

    private fun candidate(fixture: kr.closeforyou.testing.mock.MockPopupFixture, signal: PopupSignal = PopupSignal.NEW_WINDOW) =
        PopupEvent.Candidate(fixture.snapshot, PopupDetection(fixture.snapshot.token, persistentSetOf(signal)))

    /** The same popup as [fixture], seen at another screen version. */
    private fun at(fixture: kr.closeforyou.testing.mock.MockPopupFixture, version: Long): PopupSnapshot =
        fixture.snapshot.copy(token = fixture.snapshot.token.copy(screenVersion = version))

    private fun candidateOf(snapshot: PopupSnapshot) =
        PopupEvent.Candidate(snapshot, PopupDetection(snapshot.token, persistentSetOf(PopupSignal.NEW_WINDOW)))

    private fun deliver(source: FakeSource, wiring: DecisionCandidateWiring, vararg events: PopupEvent) = runBlocking {
        source.pending += events
        wiring.source.events.toList()
    }

    @Test fun theAnalysisOfTheAnnouncedScreenCarriesBsCandidates() = runBlocking {
        val source = FakeSource(token)
        val wiring = DecisionCandidateWiring(source, aExtractor)
        val delivered = deliver(source, wiring, candidate(notice))
        assertEquals("events pass through unchanged", 1, delivered.size)

        val analysis = wiring.extractor.extract(notice.snapshot)
        assertEquals(listOf("node:1"), analysis.closeCandidates.map { it.id })
        assertEquals(ButtonRole.CLOSE, analysis.closeCandidates.single().role)
        assertTrue("A's limits are kept", AnalysisLimit.INCOMPLETE_EVIDENCE in analysis.limitations)
        // Extracting again for the same screen (e.g. D's fingerprint right before saving) gives the same candidates.
        assertEquals(analysis.closeCandidates, wiring.extractor.extract(notice.snapshot).closeCandidates)
    }

    @Test fun anotherScreenGetsNoCandidates() = runBlocking {
        val source = FakeSource(token)
        val wiring = DecisionCandidateWiring(source, aExtractor)
        // Nothing announced yet.
        assertTrue(wiring.extractor.extract(notice.snapshot).closeCandidates.isEmpty())

        deliver(source, wiring, candidate(notice))
        assertTrue("a snapshot of a different screen", wiring.extractor.extract(ad.snapshot).closeCandidates.isEmpty())

        // A newer popup replaces the remembered detection: the old screen no longer gets candidates.
        deliver(source, wiring, candidate(ad))
        assertTrue(wiring.extractor.extract(notice.snapshot).closeCandidates.isEmpty())
    }

    @Test fun blockedOrInvalidatedScreensForgetTheDetection() = runBlocking {
        for (end in listOf(PopupEvent.Invalidated(screenVersion = 2), PopupEvent.Blocked(PrivacyBlockReason.PASSWORD, screenVersion = 2))) {
            val source = FakeSource(token)
            val wiring = DecisionCandidateWiring(source, aExtractor)
            deliver(source, wiring, candidate(notice), end)
            assertTrue(end::class.simpleName, wiring.extractor.extract(notice.snapshot).closeCandidates.isEmpty())
        }
    }

    @Test fun anAnalysisOfADifferentTokenGetsNoCandidates() = runBlocking {
        val source = FakeSource(token)
        val staleExtractor = object : EvidenceExtractor {
            override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis = ad.analysis
        }
        val wiring = DecisionCandidateWiring(source, staleExtractor)
        deliver(source, wiring, candidate(notice))
        assertTrue(wiring.extractor.extract(notice.snapshot).closeCandidates.isEmpty())
    }

    @Test fun uncertainDetectionsAndCandidatesFromElsewhereAreNotUsed() = runBlocking {
        val source = FakeSource(token)
        val presetExtractor = object : EvidenceExtractor {
            override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis =
                notice.analysis.copy(closeCandidates = persistentListOf(notice.analysis.closeCandidates.first().copy(id = "from-elsewhere")))
        }
        val wiring = DecisionCandidateWiring(source, presetExtractor)
        deliver(source, wiring, candidate(notice, PopupSignal.INSUFFICIENT_EVIDENCE))
        assertTrue("B's finder decides, never a preset list", wiring.extractor.extract(notice.snapshot).closeCandidates.isEmpty())
    }

    @Test fun aScreenThatIsNoLongerCurrentGetsNoCandidates() = runBlocking {
        // A's token expired (TTL), was blocked or moved on, but no event reached this collector yet.
        val source = FakeSource(token)
        val wiring = DecisionCandidateWiring(source, aExtractor)
        deliver(source, wiring, candidate(notice))
        source.token = null
        assertTrue(wiring.extractor.extract(notice.snapshot).closeCandidates.isEmpty())
        source.token = ad.snapshot.token
        assertTrue(wiring.extractor.extract(notice.snapshot).closeCandidates.isEmpty())
    }

    @Test fun lateEventsFromAnotherCollectorDoNotBringOldScreensBack() = runBlocking {
        val v5 = at(notice, 5)
        val v6 = at(notice, 6)
        val source = FakeSource(v6.token)
        val wiring = DecisionCandidateWiring(source, aExtractor)

        // Invalidated(6) starts version 6 and the popup of version 6 follows: candidates.
        deliver(source, wiring, PopupEvent.Invalidated(6), candidateOf(v6))
        assertEquals(1, wiring.extractor.extract(v6).closeCandidates.size)

        // A slow collector replays version 5 afterwards: neither its Candidate nor its Invalidated wins.
        deliver(source, wiring, candidateOf(v5), PopupEvent.Invalidated(5))
        assertEquals(1, wiring.extractor.extract(v6).closeCandidates.size)

        // Once version 7 starts, version 6 is gone and a late version-6 Candidate cannot return.
        deliver(source, wiring, PopupEvent.Invalidated(7), candidateOf(v6))
        assertTrue(wiring.extractor.extract(v6).closeCandidates.isEmpty())
    }

    @Test fun aBlockAtTheSameVersionCoversThatScreen() = runBlocking {
        val v4 = at(notice, 4)
        val source = FakeSource(v4.token)
        val wiring = DecisionCandidateWiring(source, aExtractor)
        deliver(source, wiring, candidateOf(v4), PopupEvent.Blocked(PrivacyBlockReason.SENSITIVE_CONTENT, screenVersion = 4))
        assertTrue(wiring.extractor.extract(v4).closeCandidates.isEmpty())
        deliver(source, wiring, candidateOf(v4))
        assertTrue("a late Candidate of the blocked version stays out", wiring.extractor.extract(v4).closeCandidates.isEmpty())
    }

    @Test fun currentTokenComesFromA() {
        val source = FakeSource(token)
        val wiring = DecisionCandidateWiring(source, aExtractor)
        assertSame(token, wiring.source.currentToken())
        source.token = null
        assertEquals(null, wiring.source.currentToken())
    }

    @Test fun firstIntegrationPathReachesTheGateOnceAndClicksNothing() = runBlocking {
        // A's Candidate → B wiring → B classifier → person taps the named button → B handler → A gate (not executing).
        val source = FakeSource(token)
        val wiring = DecisionCandidateWiring(source, aExtractor)
        deliver(source, wiring, candidate(notice))
        val analysis = wiring.extractor.extract(notice.snapshot)
        val classified = analysis.copy(classification = DecisionPopupClassifier().classify(analysis))
        assertEquals(PopupClassification.NOTICE, classified.classification)

        val requests = mutableListOf<ActionRequest>()
        val gate = object : ActionExecutor {
            override suspend fun execute(request: ActionRequest): ActionResult {
                requests += request
                return ActionResult(request.decision.token, ActionStatus.NOT_EXECUTED, SafetyReason.NOT_IMPLEMENTED)
            }
        }
        val handler = DecisionChoiceHandler(wiring.source, gate, { false }, { true })
        // INTEGRATION.md passes it to C as ChoiceHandler(handler::handle).
        val forC = CsChoiceHandler(handler::handle)
        val choice = UserChoice(token, ChoiceKind.CLOSE_ONCE, classified.closeCandidates.single().id, buttonMeaningConfirmed = true)
        val result = forC.handle(choice, classified)
        assertEquals(ActionStatus.NOT_EXECUTED, result?.status)
        assertEquals(1, requests.size)
    }
}
