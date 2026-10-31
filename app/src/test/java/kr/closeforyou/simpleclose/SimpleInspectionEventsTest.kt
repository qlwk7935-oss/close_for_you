package kr.closeforyou.simpleclose

import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kr.closeforyou.ui.voice.settings.HelpAppsMode
import kr.closeforyou.ui.voice.settings.InstalledApp
import kr.closeforyou.ui.voice.settings.UiVoiceSettings
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class SimpleInspectionEventsTest {
    private class Root(val pkg: String)
    private class Rig(val scope: TestScope) {
        val chrome = "com.android.chrome"
        var jobs = EngineJobs(scope)
        var prompt = false
        var lastInspection = 0L
        var settings = UiVoiceSettings(helpAppsMode = HelpAppsMode.ALL)
        var root = Root(chrome)
        var lookup: (String) -> InstalledApp? = { InstalledApp(it, "Chrome") }
        var queries = 0
        var walks = 0
        var releases = 0
        var accepted = 0
        var popupFound = true
        val reads = mutableListOf<Pair<Long, String>>()
        val traces = mutableListOf<SimpleTrace>()
        val worker = StandardTestDispatcher(scope.testScheduler)
        fun now() = 10_000L + scope.testScheduler.currentTime
        val events = SimpleInspectionEvents({ jobs }, { prompt }, { lastInspection }, ::now,
            { SimpleAppAllowance.isInScope(it, "kr.closeforyou", settings, emptySet()) },
            { accepted++ }, { expected ->
                lastInspection = now()
                reads += scope.testScheduler.currentTime to expected
                val result = withContext(worker) {
                    SimpleCollectionGuard.read(expected, { root }, { it.pkg },
                        { SimpleAppAllowance.allows(it, "kr.closeforyou", settings, emptySet(), lookup = { pkg ->
                            queries++; lookup(pkg)
                        }) }, { _, _ -> walks++; "COLLECTED" }, { releases++ })
                }
                result != null && popupFound
            }, SimpleTraceSink { traces += it })
        fun event(content: Boolean = false, pkg: String = chrome) = events.onEvent(pkg, content)
    }

    @Test fun busyEventNeverQueriesPackageMetadata() = runTest {
        val jobs = EngineJobs(this)
        jobs.runAction { delay(500) }
        var queries = 0
        val events = SimpleInspectionEvents({ jobs }, { false }, { 0L }, { 10_000L },
            { queries++; true }, {}, { error("UNEXPECTED_READ") }, SimpleTraceSink.NONE)
        events.onEvent("com.android.chrome", false)
        assertEquals(0, queries)
    }

    @Test fun promptThrottleNeverQueriesPackageMetadata() = runTest {
        val jobs = EngineJobs(this)
        var queries = 0
        val events = SimpleInspectionEvents({ jobs }, { true }, { 10_000L }, { 10_100L },
            { queries++; true }, {}, { error("UNEXPECTED_READ") }, SimpleTraceSink.NONE)
        events.onEvent("com.android.chrome", false)
        assertEquals(0, queries)
    }

    @Test fun contentThrottleNeverQueriesPackageMetadata() = runTest {
        val jobs = EngineJobs(this)
        var queries = 0
        val events = SimpleInspectionEvents({ jobs }, { false }, { 10_000L }, { 10_100L },
            { queries++; true }, {}, { error("UNEXPECTED_READ") }, SimpleTraceSink.NONE)
        events.onEvent("com.android.chrome", true)
        assertEquals(0, queries)
    }

    @Test fun chromeWindowChainKeepsItsDelaysAndExactEventPackage() = runTest {
        val r = Rig(this).apply { popupFound = false }
        r.event()
        assertEquals(0, r.queries)
        advanceUntilIdle()
        assertEquals(CHAIN, r.reads.map { it.first })
        assertTrue(r.reads.all { it.second == r.chrome })
        assertEquals(CHAIN.size, r.queries)
        assertEquals(CHAIN.size, r.walks)
        assertEquals(DELAYS.size, r.traces.count { it == SimpleTrace.RECHECK_SCHEDULED })
    }

    @Test fun contentEventKeepsSingleDebouncedReadEvenWhenNoPopupFound() = runTest {
        val r = Rig(this).apply { popupFound = false }
        r.event(content = true)
        assertEquals(0, r.queries)
        advanceUntilIdle()
        assertEquals(listOf(DEBOUNCE to r.chrome), r.reads)
        assertEquals(1, r.queries)
    }

    @Test fun throttledContentAndKnownSensitiveEventDoNotReplaceChromeChain() = runTest {
        val r = Rig(this).apply { popupFound = false }
        r.event()
        advanceTimeBy(DEBOUNCE + 1)
        r.event(content = true)
        r.event(pkg = "com.kbstar.kbbank")
        r.popupFound = true
        advanceUntilIdle()
        assertEquals(CHAIN.take(2), r.reads.map { it.first })
        assertEquals(2, r.queries)
    }

    @Test fun aNewWindowEventStillReplacesThePendingInspection() = runTest {
        val r = Rig(this)
        r.event()
        advanceTimeBy(100)
        r.event()
        advanceUntilIdle()
        assertEquals(listOf(100L + DEBOUNCE to r.chrome), r.reads)
        assertEquals(1, r.queries)
    }

    @Test fun promptAppearingDuringDebouncePreventsReadAndLookup() = runTest {
        val r = Rig(this)
        r.event()
        advanceTimeBy(100)
        r.prompt = true
        advanceUntilIdle()
        assertTrue(r.reads.isEmpty())
        assertEquals(0, r.queries)
    }

    @Test fun runningActionRetainsConfirmationAndSpeechWhenEventsArrive() = runTest {
        val r = Rig(this)
        var confirmed = 0
        var spoken = 0
        r.event()
        r.jobs.runAction { delay(500); confirmed++; spoken++ }
        repeat(10) { r.event(content = it % 2 == 0) }
        advanceUntilIdle()
        assertEquals(1, confirmed)
        assertEquals(1, spoken)
        assertTrue(r.reads.isEmpty())
        assertEquals(0, r.queries)
    }

    @Test fun cancelAndReplacementConnectionDoNotResumeOldInspection() = runTest {
        val r = Rig(this)
        r.event()
        advanceTimeBy(100)
        r.jobs.cancelAll()
        r.jobs = EngineJobs(this)
        val replacement = "com.example.browser"
        r.root = Root(replacement)
        r.event(pkg = replacement)
        advanceUntilIdle()
        assertEquals(listOf(100L + DEBOUNCE to replacement), r.reads)
        assertEquals(1, r.queries)
    }

    @Test fun activeRootReplacementDuringDebounceNeverTraversesOrQueriesAnotherApp() = runTest {
        val r = Rig(this)
        r.event(content = true)
        r.root = Root("com.kbstar.kbbank")
        advanceUntilIdle()
        assertEquals(0, r.queries)
        assertEquals(0, r.walks)
        assertEquals(1, r.releases)
    }

    @Test fun settingsChangedAfterEventAreCheckedBeforeEveryCollection() = runTest {
        val r = Rig(this).apply { popupFound = false }
        r.event()
        advanceTimeBy(DEBOUNCE + 1)
        r.settings = r.settings.copy(helpAppsExcluded = setOf(r.chrome))
        advanceUntilIdle()
        assertEquals(CHAIN.size, r.reads.size)
        assertEquals(1, r.queries)
        assertEquals(1, r.walks)
        assertEquals(CHAIN.size, r.releases)
    }

    @Test fun changedSelectionOrMissingMetadataNeverUsesEventAdmissionAsPermission() = runTest {
        for (mode in listOf<HelpAppsMode?>(HelpAppsMode.CHOSEN, null)) {
            val r = Rig(this)
            r.event(content = true)
            r.settings = r.settings.copy(helpAppsMode = mode)
            advanceUntilIdle()
            assertEquals(0, r.queries)
            assertEquals(0, r.walks)
        }
        for (info in listOf(null, InstalledApp("com.android.chrome", "모바일 은행"))) {
            val r = Rig(this)
            r.event(content = true)
            r.lookup = { info }
            advanceUntilIdle()
            assertEquals(1, r.queries)
            assertEquals(0, r.walks)
        }
    }

    @Test fun cheapScopeNeverClaimsThatResolvedAppIsSafe() = runTest {
        val r = Rig(this)
        assertTrue(SimpleAppAllowance.isInScope(r.chrome, "kr.closeforyou", r.settings, emptySet()))
        r.lookup = { throw SecurityException("PRIVATE_FIXTURE") }
        r.event(content = true)
        assertEquals(0, r.queries)
        advanceUntilIdle()
        assertEquals(1, r.queries)
        assertEquals(0, r.walks)
    }

    @Test fun eventSchedulesWorkerCollectionWithoutCallingMetadataOnEventThread() = runTest {
        val eventThread = Thread.currentThread()
        val finished = CompletableDeferred<Unit>()
        val jobs = EngineJobs(this)
        var queries = 0
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val events = SimpleInspectionEvents({ jobs }, { false }, { 0L }, { 10_000L },
                { SimpleAppAllowance.isInScope(it, "kr.closeforyou", UiVoiceSettings(helpAppsMode = HelpAppsMode.ALL), emptySet()) },
                {}, { expected ->
                    withContext(worker) {
                        SimpleCollectionGuard.read(expected, { Root(expected) }, { it.pkg },
                            { SimpleAppAllowance.allows(it, "kr.closeforyou", UiVoiceSettings(helpAppsMode = HelpAppsMode.ALL), emptySet(), lookup = { pkg ->
                                assertFalse(eventThread === Thread.currentThread())
                                queries++; InstalledApp(pkg, "Chrome")
                            }) }, { _, _ -> "COLLECTED" }, {})
                    }
                    finished.complete(Unit)
                    true
                }, SimpleTraceSink.NONE)
            events.onEvent("com.android.chrome", false)
            assertEquals(0, queries)
            advanceTimeBy(SimpleCloseEngineFeature.DEBOUNCE_MILLIS)
            runCurrent()
            finished.await()
            assertEquals(1, queries)
        } finally { worker.close() }
    }

    // ---- C integration (F10): TalkBack focus reads at once, same scope check ----

    @Test fun talkBackFocusReadsAtOnceWithoutDebounce() = runTest {
        val r = Rig(this)
        r.events.onTalkBackFocus(r.chrome)
        advanceUntilIdle()
        assertEquals(listOf(0L to r.chrome), r.reads)
    }

    @Test fun talkBackFocusIsThrottledAndNeverCutsAChain() = runTest {
        val r = Rig(this).apply { popupFound = false }
        r.events.onTalkBackFocus(r.chrome)
        advanceUntilIdle()
        r.events.onTalkBackFocus(r.chrome)
        advanceUntilIdle()
        assertEquals(1, r.reads.size)
        // A window chain in flight is not replaced by a focus event.
        advanceTimeBy(SimpleCloseEngineFeature.FOCUS_EVENT_READ_MILLIS)
        r.event()
        advanceTimeBy(DEBOUNCE + 1)
        r.events.onTalkBackFocus(r.chrome)
        advanceUntilIdle()
        assertEquals(1 + CHAIN.size, r.reads.size)
    }

    @Test fun talkBackFocusInASensitiveOrUnchosenAppIsSkipped() = runTest {
        val r = Rig(this)
        r.events.onTalkBackFocus("com.kbstar.kbbank")
        r.prompt = true
        r.events.onTalkBackFocus(r.chrome)
        advanceUntilIdle()
        assertTrue(r.reads.isEmpty())
        assertEquals(0, r.queries)
    }

    private companion object {
        val DEBOUNCE = SimpleCloseEngineFeature.DEBOUNCE_MILLIS
        val DELAYS = SimpleCloseEngineFeature.RECHECK_DELAYS_MILLIS
        /** Read times of a full window-event chain: debounce, then each re-check delay in turn. */
        val CHAIN: List<Long> = DELAYS.runningFold(DEBOUNCE) { at, wait -> at + wait }
    }
}
