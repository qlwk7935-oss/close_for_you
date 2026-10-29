package kr.closeforyou.accessibility.action

import kotlinx.coroutines.CancellationException
import kr.closeforyou.accessibility.integration.ManualPromptTrace
import org.junit.Assert.*
import org.junit.Test

/** Actual scan catch/guard/checkpoint/release/report contract. No Android Binder/getter execution. */
class F9ReadDiagnosticsTest {
    @Test fun eachScanReportsOneActualGuardStageAndReleasesBeforeReporting() {
        val failures = listOf(F9ReadStage.ACTIVE_WINDOW to F9ReadDetail.ACTIVE_APPLICATION_UNCONFIRMED,
            F9ReadStage.FOCUSED_WINDOW to F9ReadDetail.ACTIVE_NOT_FOCUSED,
            F9ReadStage.OVERLAY_WINDOW to F9ReadDetail.ACCESSIBILITY_OVERLAY_PRESENT,
            F9ReadStage.ROOT to F9ReadDetail.ROOT_UNAVAILABLE,
            F9ReadStage.TREE to F9ReadDetail.CHILD_UNAVAILABLE,
            F9ReadStage.ENDPOINT to F9ReadDetail.END_WINDOWS_CHANGED)
        for ((stage, failure) in failures) {
            val order = mutableListOf<String>(); val reports = mutableListOf<F9ReadCode>()
            val r = F9ReadObservation(F9ReadPurpose.MANUAL_ELIGIBILITY) { reports += it; order += "REPORT" }
            val result = r.run(body = { r.stage = stage; r.require(false, failure); "MUST_NOT_RETURN" },
                cleanup = { r.release(true) { order += "NODE" }; r.release(false) { order += "WINDOW" } })
            assertNull(result); assertEquals(listOf("NODE", "WINDOW", "REPORT"), order)
            val c = reports.single(); assertEquals(stage, c.stage); assertEquals(failure, c.detail)
            assertEquals(F9ReadOutcome.REJECTED, c.outcome); assertFalse(c.consumed)
            r.report(); assertEquals(1, reports.size)
        }
    }
    @Test fun nonlocalScanReturnStillRunsTheSameSharedFinallyOnce() {
        val order = mutableListOf<String>(); var code: F9ReadCode? = null
        fun scan(): String? {
            val r = F9ReadObservation(F9ReadPurpose.PRE_CLICK) { code = it; order += "REPORT" }
            return r.run(body = { r.stage = F9ReadStage.COMPLETE; r.complete(); return "SCENE" },
                cleanup = { order += "RELEASE" })
        }
        assertEquals("SCENE", scan()); assertEquals(listOf("RELEASE", "REPORT"), order)
        assertEquals(F9ReadOutcome.COMPLETE, code!!.outcome); assertTrue(code!!.consumed)
    }
    @Test fun firstGuardFailureIsNotReplacedByNodeAndWindowReleaseFailures() {
        val reports = mutableListOf<F9ReadCode>(); val releases = mutableListOf<String>()
        val r = F9ReadObservation(F9ReadPurpose.DISPLAY_REVIEW, reports::add)
        assertNull(r.run(body = { r.stage = F9ReadStage.TREE; r.require(false, F9ReadDetail.PROTECTED_FIELD); "UNSAFE" }, cleanup = {
            assertFalse(r.release(true) { releases += "ROOT"; error("PRIVATE_RELEASE") })
            assertFalse(r.release(true) { releases += "CHILD"; error("PRIVATE_RELEASE") })
            assertFalse(r.release(false) { releases += "WINDOW"; error("PRIVATE_RELEASE") })
        }))
        val c = reports.single(); assertEquals(F9ReadDetail.PROTECTED_FIELD, c.detail); assertEquals(F9ReadStage.TREE, c.stage)
        assertEquals(F9ReadRelease.BOTH, c.release); assertEquals(F9ReadOutcome.CLEANUP_FAILED, c.outcome)
        assertEquals(listOf("ROOT", "CHILD", "WINDOW"), releases); assertFalse(c.toString().contains("PRIVATE"))
    }
    @Test fun successfulConsumeWithReleaseFailureIsNotMisreportedAsComplete() {
        val reports = mutableListOf<F9ReadCode>(); var healthy = true
        val r = F9ReadObservation(F9ReadPurpose.MANUAL_ELIGIBILITY, reports::add)
        val scene = r.run(body = { r.stage = F9ReadStage.COMPLETE; r.complete(); "SCENE" }, cleanup = {
            if (!r.release(true) { error("FIXED_RELEASE") }) healthy = false
        }).takeIf { healthy } // SAME read() post-finally health fence as Android
        assertNull(scene); val c = reports.single(); assertTrue(c.consumed)
        assertEquals(F9ReadDetail.NODE_RELEASE_FAILED, c.detail); assertEquals(F9ReadOutcome.CLEANUP_FAILED, c.outcome)
    }
    @Test fun cancellationPropagatesOriginalObjectAfterAllOrdinaryReleaseFailuresAndOneReport() {
        val cancellation = CancellationException("PRIVATE_CANCEL"); val rows = mutableListOf<F9ReadCode>()
        val order = mutableListOf<String>(); val r = F9ReadObservation(F9ReadPurpose.PRESS, rows::add)
        assertSame(cancellation, runCatching {
            r.run(body = { r.stage = F9ReadStage.TARGET; r.checkpoint { throw cancellation }; "UNREACHABLE" }, cleanup = {
                r.release(true) { order += "NODE"; error("SECONDARY") }
                r.release(false) { order += "WINDOW"; error("SECONDARY") }
            })
        }.exceptionOrNull())
        val c = rows.single(); assertEquals(F9ReadOutcome.CANCELLED, c.outcome); assertEquals(F9ReadDetail.CANCELLED, c.detail)
        assertEquals(F9ReadRelease.BOTH, c.release); assertEquals(listOf("NODE", "WINDOW"), order)
    }
    @Test fun frameworkExceptionHasStageButNeverRetainsItsTypeMessageOrObject() {
        val rows = mutableListOf<F9ReadCode>(); val r = F9ReadObservation(F9ReadPurpose.MANUAL_ELIGIBILITY, rows::add)
        assertNull(r.run(body = { r.stage = F9ReadStage.ROOT; error("PRIVATE_FRAMEWORK_CONTENT") }, cleanup = {}))
        assertEquals(F9ReadStage.ROOT, rows.single().stage); assertEquals(F9ReadDetail.EXCEPTION, rows.single().detail)
        assertEquals(F9ReadOutcome.EXCEPTION, rows.single().outcome); assertFalse(rows.toString().contains("PRIVATE"))
    }
    @Test fun consumeExceptionMayFollowAnActionAndIsNotEvidenceThatNoClickHappened() {
        var invocations = 0; val rows = mutableListOf<F9ReadCode>()
        val r = F9ReadObservation(F9ReadPurpose.PRESS, rows::add)
        assertNull(r.run(body = { r.stage = F9ReadStage.COMPLETE; invocations++; error("FIXED_AFTER_INVOKE") }, cleanup = {}))
        assertEquals(1, invocations); assertFalse(rows.single().consumed)
        assertEquals(F9ReadOutcome.EXCEPTION, rows.single().outcome) // CALLBACK_RETURNED=NO is NOT CLICK_ATTEMPT=NO
    }
    @Test fun checkpointFailureAndBudgetFailureAreDistinctWithoutAdditionalChecksOrClockCalls() {
        for (checkpointFails in listOf(true, false)) {
            var checks = 0; var clocks = 0; val rows = mutableListOf<F9ReadCode>()
            val r = F9ReadObservation(F9ReadPurpose.DISPLAY_REVIEW, rows::add)
            assertNull(r.run(body = {
                r.stage = F9ReadStage.TREE
                r.checkpoint { checks++; if (checkpointFails) error("FIXED_CURRENTNESS") }
                val elapsed = run { clocks++; 100L }
                r.require(elapsed < 100, F9ReadDetail.TIME_LIMIT); "UNREACHABLE"
            }, cleanup = {}))
            assertEquals(1, checks); assertEquals(if (checkpointFails) 0 else 1, clocks)
            assertEquals(if (checkpointFails) F9ReadDetail.CHECKPOINT_FAILED else F9ReadDetail.TIME_LIMIT, rows.single().detail)
        }
    }
    @Test fun identityOnlyProtectedDepartureIsNotFullCollectionOrClickEvidence() {
        val rows = mutableListOf<F9ReadCode>(); val r = F9ReadObservation(F9ReadPurpose.POST_CLICK, rows::add)
        assertEquals("IDENTITY_ONLY", r.run(body = {
            r.stage = F9ReadStage.ROOT; r.reject(F9ReadDetail.APP_NOT_ALLOWED); r.complete(identityOnly = true); "IDENTITY_ONLY"
        }, cleanup = {}))
        assertEquals(F9ReadOutcome.IDENTITY_ONLY, rows.single().outcome); assertEquals(F9ReadDetail.APP_NOT_ALLOWED, rows.single().detail)
    }
    @Test fun freshObservationCannotInheritPriorScanStageFailureOrPurpose() {
        val rows = mutableListOf<F9ReadCode>()
        val first = F9ReadObservation(F9ReadPurpose.DISPLAY_REVIEW, rows::add)
        first.run(body = { first.stage = F9ReadStage.ACTIVE_WINDOW; first.require(false, F9ReadDetail.DISPLAY_TARGET_UNCONFIRMED) }, cleanup = {})
        val next = F9ReadObservation(F9ReadPurpose.MANUAL_ELIGIBILITY, rows::add)
        next.run(body = { next.stage = F9ReadStage.COMPLETE; next.complete() }, cleanup = {})
        assertEquals(2, rows.size); assertEquals(F9ReadDetail.NONE, rows.last().detail)
        assertEquals(F9ReadPurpose.MANUAL_ELIGIBILITY, rows.last().purpose); assertEquals(F9ReadOutcome.COMPLETE, rows.last().outcome)
    }
    @Test fun diagnosticExceptionOrCancellationCannotChangeReadResultOrCleanup() {
        for (cancel in listOf(true, false)) {
            var cleaned = false
            val r = F9ReadObservation(F9ReadPurpose.PRE_CLICK) {
                if (cancel) throw CancellationException("FIXED_LOG") else error("FIXED_LOG")
            }
            assertEquals("SCENE", r.run(body = { r.stage = F9ReadStage.COMPLETE; r.complete(); "SCENE" }, cleanup = { cleaned = true }))
            assertTrue(cleaned)
        }
    }
    @Test fun existingFakePortDefaultReadForDoesExactlyOneOriginalReadAndCheckpoint() {
        var reads = 0; var checks = 0
        val port = object : F9ScreenPort {
            override fun read(checkpoint: () -> Unit): F9Scene? { reads++; checkpoint(); return null }
            override fun press(reference: F9Reference, accept: (F9Scene) -> Boolean) = error("MUST_NOT_PRESS")
        }
        assertNull(port.readFor(F9ReadPurpose.MANUAL_ELIGIBILITY) { checks++ })
        assertEquals(1, reads); assertEquals(1, checks)
    }
    @Test fun reportSinkIsBoundedToFixedCodesAndDoesNotAuthorizeOrAccumulateRawData() {
        var emitted = 0
        val trace = ManualPromptTrace(readSink = { _, _ -> emitted++ }, sink = { _, _, _ -> })
        val code = F9ReadCode(F9ReadPurpose.MANUAL_ELIGIBILITY, F9ReadStage.OVERLAY_WINDOW,
            F9ReadDetail.ACCESSIBILITY_OVERLAY_PRESENT, F9ReadOutcome.REJECTED, F9ReadRelease.NONE, false)
        repeat(100) { trace.f9Read(code) }; assertEquals(1, emitted)
        trace.begin(); trace.f9Read(code); assertEquals(2, emitted)
    }
}
