package kr.closeforyou.accessibility.integration

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceRuleCleanupTest {
    @Test fun oneDeferredCallPerConnectionIncludingDuplicateCompletedStart() = runTest {
        val owner = SupervisorJob(); val scope = CoroutineScope(coroutineContext + owner)
        val cleanup = ServiceRuleCleanup(StandardTestDispatcher(testScheduler)); var calls = 0
        cleanup.start(scope) { calls++ }; cleanup.start(scope) { calls++ }
        assertEquals(0, calls); assertEquals(RuleCleanupState.RUNNING, cleanup.state)
        runCurrent()
        assertEquals(1, calls); assertEquals(RuleCleanupState.SUCCEEDED, cleanup.state)
        cleanup.start(scope) { calls++ }; runCurrent()
        assertEquals(1, calls); assertTrue(owner.isActive)
        cleanup.close(); owner.cancel()
    }
    @Test fun storageExceptionIsLocalAndDoesNotCancelTheServiceOrEnableARetry() = runTest {
        val owner = SupervisorJob(); val scope = CoroutineScope(coroutineContext + owner)
        val cleanup = ServiceRuleCleanup(StandardTestDispatcher(testScheduler)); var calls = 0; var sibling = false
        cleanup.start(scope) { calls++; error("PRIVATE_STORAGE") }; runCurrent()
        scope.launch { sibling = true }; runCurrent()
        cleanup.start(scope) { calls++ }; runCurrent()
        assertEquals(1, calls); assertTrue(owner.isActive); assertTrue(sibling)
        assertEquals(RuleCleanupState.FAILED, cleanup.state)
        assertEquals("ServiceRuleCleanup(codes_only)", cleanup.toString())
        cleanup.close(); owner.cancel()
    }
    @Test fun storageCancellationPropagatesInTheChildWithoutStoppingServiceSiblings() = runTest {
        val owner = SupervisorJob(); val scope = CoroutineScope(coroutineContext + owner)
        val cleanup = ServiceRuleCleanup(StandardTestDispatcher(testScheduler)); var calls = 0
        cleanup.start(scope) { calls++; throw CancellationException("PRIVATE_CANCEL") }; runCurrent()
        assertEquals(RuleCleanupState.CANCELLED, cleanup.state); assertTrue(owner.isActive)
        cleanup.start(scope) { calls++ }; runCurrent(); assertEquals(1, calls)
        cleanup.close(); owner.cancel()
    }
    @Test fun closingBeforeFirstDispatchPreventsWorkAndSameGenerationRestart() = runTest {
        val owner = SupervisorJob(); val scope = CoroutineScope(coroutineContext + owner)
        val cleanup = ServiceRuleCleanup(StandardTestDispatcher(testScheduler)); var calls = 0
        cleanup.start(scope) { calls++ }; cleanup.close(); runCurrent()
        cleanup.start(scope) { calls++ }; runCurrent()
        assertEquals(0, calls); assertEquals(RuleCleanupState.CLOSED, cleanup.state)
        assertTrue(owner.isActive); owner.cancel()
    }
    @Test fun closingSuspendedWorkRevokesCompletionAndCancelsOnlyThatWork() = runTest {
        val owner = SupervisorJob(); val scope = CoroutineScope(coroutineContext + owner)
        val cleanup = ServiceRuleCleanup(StandardTestDispatcher(testScheduler))
        val finish = CompletableDeferred<Unit>(); var reached = false
        cleanup.start(scope) { reached = true; finish.await() }; runCurrent()
        assertTrue(reached); cleanup.close(); runCurrent(); finish.complete(Unit); runCurrent()
        assertEquals(RuleCleanupState.CLOSED, cleanup.state); assertTrue(owner.isActive)
        owner.cancel()
    }
    @Test fun parentCancellationBeforeWorkerDispatchCannotLeaveRunningState() = runTest {
        val owner = SupervisorJob(); val scope = CoroutineScope(coroutineContext + owner)
        val cleanup = ServiceRuleCleanup(StandardTestDispatcher(testScheduler)); var calls = 0
        cleanup.start(scope) { calls++ }; owner.cancel(); runCurrent()
        assertEquals(0, calls); assertEquals(RuleCleanupState.CANCELLED, cleanup.state)
        cleanup.close()
    }
    @Test fun cancelledOrMissingConnectionJobCannotStartStorageWork() = runTest {
        val owner = SupervisorJob().also { it.cancel() }
        val cleanup = ServiceRuleCleanup(StandardTestDispatcher(testScheduler)); var calls = 0
        cleanup.start(CoroutineScope(coroutineContext + owner)) { calls++ }
        cleanup.start(object : CoroutineScope { override val coroutineContext = EmptyCoroutineContext }) { calls++ }
        runCurrent(); assertEquals(0, calls); assertEquals(RuleCleanupState.NOT_STARTED, cleanup.state)
    }
    @Test fun lateOldCompletionAndExceptionCannotOverwriteTheNewConnection() = runTest {
        for (lateFailure in listOf(false, true)) {
            val oldOwner = SupervisorJob(); val freshOwner = SupervisorJob()
            val oldScope = CoroutineScope(coroutineContext + oldOwner)
            val freshScope = CoroutineScope(coroutineContext + freshOwner)
            val cleanup = ServiceRuleCleanup(StandardTestDispatcher(testScheduler))
            val oldFinish = CompletableDeferred<Unit>(); var calls = 0
            cleanup.start(oldScope) {
                calls++
                withContext(NonCancellable) { oldFinish.await() }
                if (lateFailure) error("PRIVATE_LATE_STORAGE")
            }
            runCurrent(); cleanup.close()
            cleanup.start(freshScope) { calls++ }; runCurrent()
            assertEquals(RuleCleanupState.SUCCEEDED, cleanup.state)
            oldFinish.complete(Unit); runCurrent()
            assertEquals(2, calls); assertEquals(RuleCleanupState.SUCCEEDED, cleanup.state)
            assertTrue(freshOwner.isActive)
            cleanup.close(); oldOwner.cancel(); freshOwner.cancel()
        }
    }
    @Test fun newConnectionSupersedesOldWorkWithoutAnExplicitClose() = runTest {
        val oldOwner = SupervisorJob(); val freshOwner = SupervisorJob()
        val cleanup = ServiceRuleCleanup(StandardTestDispatcher(testScheduler))
        val finish = CompletableDeferred<Unit>()
        cleanup.start(CoroutineScope(coroutineContext + oldOwner)) { finish.await() }; runCurrent()
        cleanup.start(CoroutineScope(coroutineContext + freshOwner)) { }; runCurrent()
        finish.complete(Unit); runCurrent()
        assertEquals(RuleCleanupState.SUCCEEDED, cleanup.state)
        assertTrue(oldOwner.isActive); assertTrue(freshOwner.isActive)
        cleanup.close(); oldOwner.cancel(); freshOwner.cancel()
    }
}
