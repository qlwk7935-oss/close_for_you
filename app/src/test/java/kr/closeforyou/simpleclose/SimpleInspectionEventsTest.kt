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
        val triggers = mutableListOf<DetectTrigger>()
        var exhausted = 0
        var readDelay = 0L
        val worker = StandardTestDispatcher(scope.testScheduler)
        fun now() = 10_000L + scope.testScheduler.currentTime
        val events = SimpleInspectionEvents({ jobs }, { prompt }, { lastInspection }, ::now,
            { SimpleAppAllowance.isInScope(it, "kr.closeforyou", settings, emptySet()) },
            { accepted++ }, { expected, trigger ->
                triggers += trigger
                delay(readDelay)
                lastInspection = now()
                reads += scope.testScheduler.currentTime to expected
                val result = withContext(worker) {
                    SimpleCollectionGuard.read(expected, { root }, { it.pkg },
                        { SimpleAppAllowance.allows(it, "kr.closeforyou", settings, emptySet(), lookup = { pkg ->
                            queries++; lookup(pkg)
                        }) }, { _, _ -> walks++; "COLLECTED" }, { releases++ })
                }
                result != null && popupFound
            }, SimpleTraceSink { traces += it }, { exhausted++ })
        fun focus() = events.onEvent(chrome, SimpleInspectionEvent.TALKBACK_FOCUS)
        fun event(content: Boolean = false, pkg: String = chrome) = events.onEvent(pkg, if (content) SimpleInspectionEvent.CONTENT else SimpleInspectionEvent.WINDOW)
    }

    @Test fun busyEventNeverQueriesPackageMetadata() = runTest {
        val jobs = EngineJobs(this)
        jobs.runAction { delay(500) }
        var queries = 0
        val events = SimpleInspectionEvents({ jobs }, { false }, { 0L }, { 10_000L },
            { queries++; true }, {}, { _, _ -> error("UNEXPECTED_READ") }, SimpleTraceSink.NONE)
        events.onEvent("com.android.chrome", SimpleInspectionEvent.WINDOW)
        assertEquals(0, queries)
    }

    @Test fun promptThrottleNeverQueriesPackageMetadata() = runTest {
        val jobs = EngineJobs(this)
        var queries = 0
        val events = SimpleInspectionEvents({ jobs }, { true }, { 10_000L }, { 10_100L },
            { queries++; true }, {}, { _, _ -> error("UNEXPECTED_READ") }, SimpleTraceSink.NONE)
        events.onEvent("com.android.chrome", SimpleInspectionEvent.WINDOW)
        assertEquals(0, queries)
    }

    @Test fun contentThrottleNeverQueriesPackageMetadata() = runTest {
        val jobs = EngineJobs(this)
        var queries = 0
        val events = SimpleInspectionEvents({ jobs }, { false }, { 10_000L }, { 10_100L },
            { queries++; true }, {}, { _, _ -> error("UNEXPECTED_READ") }, SimpleTraceSink.NONE)
        events.onEvent("com.android.chrome", SimpleInspectionEvent.CONTENT)
        assertEquals(0, queries)
    }

    @Test fun chromeWindowChainKeepsItsDelaysAndExactEventPackage() = runTest {
        val r = Rig(this).apply { popupFound = false }
        r.event()
        assertEquals(0, r.queries)
        advanceUntilIdle()
        assertEquals(listOf(200L, 400L, 900L, 1900L, 3900L, 6900L), r.reads.map { it.first })
        assertTrue(r.reads.all { it.second == r.chrome })
        assertEquals(6, r.queries)
        assertEquals(6, r.walks)
        assertEquals(5, r.traces.count { it == SimpleTrace.RECHECK_SCHEDULED })
    }

    @Test fun contentEventKeepsSingleDebouncedReadEvenWhenNoPopupFound() = runTest {
        val r = Rig(this).apply { popupFound = false }
        r.event(content = true)
        assertEquals(0, r.queries)
        advanceUntilIdle()
        assertEquals(listOf(200L to r.chrome), r.reads)
        assertEquals(1, r.queries)
    }

    @Test fun throttledContentAndKnownSensitiveEventDoNotReplaceChromeChain() = runTest {
        val r = Rig(this).apply { popupFound = false }
        r.event()
        advanceTimeBy(400)
        r.event(content = true)
        r.event(pkg = "com.kbstar.kbbank")
        r.popupFound = true
        advanceUntilIdle()
        assertEquals(listOf(200L, 400L), r.reads.map { it.first })
        assertEquals(2, r.queries)
    }

    @Test fun aNewWindowEventStillReplacesThePendingInspection() = runTest {
        val r = Rig(this)
        r.event()
        advanceTimeBy(100)
        r.event()
        advanceUntilIdle()
        assertEquals(listOf(300L to r.chrome), r.reads)
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
        repeat(10) { r.event(content = it % 2 == 0); r.focus() }
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
        assertEquals(listOf(300L to replacement), r.reads)
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
        advanceTimeBy(400)
        r.settings = r.settings.copy(helpAppsExcluded = setOf(r.chrome))
        advanceUntilIdle()
        assertEquals(6, r.reads.size)
        assertEquals(1, r.queries)
        assertEquals(1, r.walks)
        assertEquals(6, r.releases)
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
                {}, { expected, _ ->
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
            events.onEvent("com.android.chrome", SimpleInspectionEvent.WINDOW)
            assertEquals(0, queries)
            advanceTimeBy(SimpleCloseEngineFeature.DEBOUNCE_MILLIS)
            runCurrent()
            finished.await()
            assertEquals(1, queries)
        } finally { worker.close() }
    }

    @Test fun talkBackFocusReadsImmediatelyWithoutDebounceAndUsesFocusTrigger() = runTest {
        val r = Rig(this)
        r.focus()
        assertEquals(0, r.queries)
        runCurrent()
        assertEquals(listOf(0L to r.chrome), r.reads)
        assertEquals(listOf(DetectTrigger.TALKBACK_FOCUS), r.triggers)
        assertEquals(1, r.walks)
    }

    @Test fun talkBackFocusNeverReplacesPendingWindowOrRecheckChain() = runTest {
        val r = Rig(this).apply { popupFound = false }
        r.event()
        advanceTimeBy(100)
        r.focus()
        advanceTimeBy(150)
        r.focus()
        advanceUntilIdle()
        assertEquals(listOf(200L, 400L, 900L, 1900L, 3900L, 6900L), r.reads.map { it.first })
        assertEquals(listOf(DetectTrigger.WINDOW_EVENT) + List(5) { DetectTrigger.RECHECK }, r.triggers)
        assertEquals(1, r.accepted)
        assertEquals(1, r.exhausted)
    }

    @Test fun focusDuringSuspendedWorkerReadDoesNotCancelIt() = runTest {
        val r = Rig(this).apply { readDelay = 500 }
        r.focus()
        runCurrent()
        advanceTimeBy(100)
        r.focus()
        advanceUntilIdle()
        assertEquals(listOf(500L to r.chrome), r.reads)
        assertEquals(1, r.walks)
        assertEquals(1, r.triggers.size)
    }

    @Test fun focusThrottleIs300msAndDoesNotUseContentThrottleOrRecheck() = runTest {
        val r = Rig(this).apply { popupFound = false }
        r.focus()
        runCurrent()
        advanceTimeBy(299)
        r.focus()
        runCurrent()
        assertEquals(1, r.reads.size)
        advanceTimeBy(1)
        r.focus()
        runCurrent()
        assertEquals(listOf(0L, 300L), r.reads.map { it.first })
        assertTrue(r.triggers.all { it == DetectTrigger.TALKBACK_FOCUS })
        assertEquals(2, r.exhausted)
        assertTrue(r.traces.none { it == SimpleTrace.RECHECK_SCHEDULED })
    }

    @Test fun focusDoesNotReadWhilePromptIsUpEvenAfterPromptThrottle() = runTest {
        val r = Rig(this).apply { prompt = true }
        r.focus()
        advanceUntilIdle()
        assertTrue(r.reads.isEmpty())
        assertEquals(0, r.accepted)
        assertEquals(0, r.queries)
    }

    @Test fun focusQueuedBeforeActionCannotInterruptClickConfirmationOrSpeech() = runTest {
        val r = Rig(this)
        var completed = false
        r.focus()
        r.jobs.runAction { delay(1500); completed = true }
        r.focus()
        advanceUntilIdle()
        assertTrue(completed)
        assertEquals(0, r.queries)
        assertTrue(r.reads.isEmpty())
    }

    @Test fun focusReadStillRechecksLatestSettingsPackageAndMetadataBeforeWalk() = runTest {
        for (failure in 0..3) {
            val r = Rig(this)
            r.focus()
            when (failure) {
                0 -> r.settings = r.settings.copy(helpAppsMode = null)
                1 -> r.root = Root("com.example.other")
                2 -> r.lookup = { null }
                3 -> r.lookup = { InstalledApp(it, "모바일 은행") }
            }
            runCurrent()
            assertEquals(listOf(DetectTrigger.TALKBACK_FOCUS), r.triggers)
            assertEquals(0, r.walks)
            assertEquals(1, r.releases)
            assertEquals(1, r.exhausted)
        }
    }

    @Test fun contentAndWindowTriggerAndExhaustionKeepDiagnosticMeaning() = runTest {
        val r = Rig(this).apply { popupFound = false }
        r.event(content = true)
        advanceUntilIdle()
        assertEquals(listOf(DetectTrigger.CONTENT_EVENT), r.triggers)
        assertEquals(1, r.exhausted)
        r.event()
        r.popupFound = true
        advanceUntilIdle()
        assertEquals(DetectTrigger.WINDOW_EVENT, r.triggers.last())
        assertEquals(1, r.exhausted)
        assertEquals(SimpleTrace.DETECTED_BY_TALKBACK_FOCUS, DetectTrigger.TALKBACK_FOCUS.trace)
        assertEquals(SimpleTrace.DETECTED_BY_RECHECK, DetectTrigger.RECHECK.trace)
    }

    @Test fun reconnectDiscardsPendingFocusAndUsesNewJobsWithoutOldThrottle() = runTest {
        val r = Rig(this)
        r.focus()
        r.jobs.cancelAll()
        r.jobs = EngineJobs(this)
        r.events.resetConnection()
        r.focus()
        runCurrent()
        assertEquals(listOf(0L to r.chrome), r.reads)
        assertEquals(1, r.walks)
    }

    // ---- F13 (LG 11/1 11번가: 6.4 s with no read, Chrome repainting all the time) ----

    @Test fun repaintsNeverPushBackAWaitingFirstRead() = runTest {
        val r = Rig(this).apply { popupFound = false }
        r.event(content = true)
        repeat(30) {
            advanceTimeBy(100)
            r.event(content = true)
        }
        advanceUntilIdle()
        assertEquals(SimpleCloseEngineFeature.DEBOUNCE_MILLIS, r.reads.first().first)
        assertTrue(r.traces.contains(SimpleTrace.EVENT_COALESCED))
    }

    @Test fun windowEventsReArmTheFirstReadOnlyUpToTheCap() = runTest {
        val r = Rig(this).apply { popupFound = true }
        r.event()
        repeat(10) {
            advanceTimeBy(100)
            r.event()
        }
        advanceUntilIdle()
        assertEquals(SimpleCloseEngineFeature.FIRST_READ_MAX_WAIT_MILLIS to r.chrome, r.reads.first())
    }

    @Test fun aRepaintNeverReplacesAWaitingWindowChain() = runTest {
        val r = Rig(this).apply { popupFound = false }
        r.event()
        advanceTimeBy(50)
        r.event(content = true)
        advanceUntilIdle()
        assertEquals(listOf(DetectTrigger.WINDOW_EVENT) + List(5) { DetectTrigger.RECHECK }, r.triggers)
    }

    @Test fun anotherAppsEventIsNotFoldedIntoTheWaitingRead() = runTest {
        val other = "com.example.browser"
        val r = Rig(this).apply { popupFound = true }
        r.event(content = true)
        advanceTimeBy(100)
        r.root = Root(other)
        r.event(content = true, pkg = other)
        advanceUntilIdle()
        assertEquals(listOf(100L + SimpleCloseEngineFeature.DEBOUNCE_MILLIS to other), r.reads)
    }
}
