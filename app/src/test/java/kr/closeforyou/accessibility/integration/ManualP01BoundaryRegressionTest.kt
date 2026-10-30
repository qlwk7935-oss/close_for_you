package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kr.closeforyou.core.contract.AnnouncementReceipt
import kr.closeforyou.core.contract.AnnouncementState
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.SpeechReadiness
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.OverlayWindowStatus
import org.junit.Assert.*
import org.junit.Test

/** Actual read/release and speech-return boundaries; no Android window or audible output claim. */
class ManualP01BoundaryRegressionTest {
    private fun read(cancel: CancellationException, releaseCancel: Boolean, secondaryHealthFailure: Boolean = false): Pair<List<Int>, Throwable?> {
        val released = mutableListOf<Int>()
        val failed = runCatching {
            readOwnedOverlayIds(readWindows = { listOf(1, 2) }, isOverlayOnDefaultDisplay = { true },
                readRoot = { if (!releaseCancel && it == 2) throw cancel else it + 10 },
                isOwnedRoot = { _, _ -> true }, hasOwnedTitle = { true }, windowId = { it },
                releaseRoot = { released += it; if (releaseCancel) throw cancel else error("SYNTHETIC_SECONDARY") },
                releaseWindow = { released += it }, onUnhealthy = { if (secondaryHealthFailure) error("SYNTHETIC_HEALTH") }, diagnostic = null)
        }.exceptionOrNull()
        return released to failed
    }
    @Test fun ownershipReadCancellationPropagatesAfterAllAcquiredHandlesAreReleased() {
        val primary = CancellationException("SYNTHETIC_CANCEL")
        val (released, failure) = read(primary, false)
        assertEquals(listOf(11, 1, 2), released); assertSame(primary, failure)
    }
    @Test fun ownershipReleaseCancellationCannotBecomeOrdinaryUnavailableResult() {
        val primary = CancellationException("SYNTHETIC_RELEASE_CANCEL")
        val (released, failure) = read(primary, true)
        assertEquals(listOf(11, 12, 1, 2), released); assertSame(primary, failure)
    }
    @Test fun secondaryHealthCallbackFailureCannotReplaceOriginalCancellation() {
        val primary = CancellationException("SYNTHETIC_CANCEL")
        val (released, failure) = read(primary, false, true)
        assertEquals(listOf(11, 1, 2), released); assertSame(primary, failure)
    }
    @Test fun completedSpeechReceiptAfterDisplayRevocationIsNotCurrentCompletion() = runTest {
        val codes = mutableListOf<ManualSpeechState>(); var current = true
        val receipt = announceManualPrompt(MutableStateFlow(SpeechReadiness.READY), { current }, codes::add) {
            current = false; AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
        }
        assertEquals(AnnouncementState.NOT_IMPLEMENTED, receipt.state)
        assertTrue(ManualSpeechState.STALE in codes); assertFalse(ManualSpeechState.COMPLETED in codes)
    }
    @Test fun oldSpeechCompletionCannotClaimNewerRequest() = runTest {
        val requests = ManualSpeechRequests(); val first = requests.replace(); val codes = mutableListOf<ManualSpeechState>()
        val receipt = announceManualPrompt(MutableStateFlow(SpeechReadiness.READY), { requests.permits(first) }, codes::add) {
            requests.replace(); AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
        }
        assertEquals(AnnouncementState.NOT_IMPLEMENTED, receipt.state); assertFalse(ManualSpeechState.COMPLETED in codes)
    }
    @Test fun stillCurrentDisplayBeyondOldEvidenceTtlCanCompleteWithoutRenewingEvidence() = runTest {
        val token = MockPopupFixtures.advertisement().snapshot.token; var now = 1_000L
        val lifetime = ManualPromptLifetime { now }
        lifetime.shown(token, OverlayWindowState(OverlayWindowStatus.SHOWING, 1))
        val codes = mutableListOf<ManualSpeechState>()
        val receipt = announceManualPrompt(MutableStateFlow(SpeechReadiness.READY), { lifetime.current() == token }, codes::add) {
            now += 6_000; AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
        }
        assertEquals(AnnouncementState.TTS_COMPLETED, receipt.state); assertTrue(ManualSpeechState.COMPLETED in codes)
        assertEquals(token, lifetime.current())
    }
    @Test fun presentationFailureCannotSkipSessionOrRuntimeInvalidation() {
        val order = mutableListOf<String>(); val primary = IllegalStateException("SYNTHETIC_OWNER")
        assertSame(primary, runCatching {
            invalidateAfterOwnedCleanup({
                invalidateAfterOwnedCleanup({ order += "PRESENTATION"; throw primary }, { order += "SESSION" })
            }, { order += "RUNTIME" })
        }.exceptionOrNull())
        assertEquals(listOf("PRESENTATION", "SESSION", "RUNTIME"), order)
    }
    @Test fun originalCancellationWinsAfterAllDownstreamInvalidationsAreAttempted() {
        val order = mutableListOf<String>(); val primary = CancellationException("SYNTHETIC_PRIMARY")
        assertSame(primary, runCatching {
            invalidateAfterOwnedCleanup({
                invalidateAfterOwnedCleanup({ order += "OWNER"; throw primary }, { order += "SESSION"; error("SYNTHETIC_SECONDARY") })
            }, { order += "RUNTIME"; throw CancellationException("SYNTHETIC_SECOND_CANCEL") })
        }.exceptionOrNull())
        assertEquals(listOf("OWNER", "SESSION", "RUNTIME"), order)
    }
    @Test fun synchronousAndroidEventCancellationAbortsAndInvalidatesInsteadOfProcessingReceipt() {
        val order = mutableListOf<String>(); var processed = false
        try {
            processed = completeSynchronousAccessibilityCallback(onRejected = { issue ->
                assertEquals(ManualCleanupIssue.CANCELLED, issue)
                invalidateAfterOwnedCleanup({ order += "OWNER"; error("SYNTHETIC_CLEANUP") }, { order += "RUNTIME" })
            }) { order += "EVENT"; throw CancellationException("SYNTHETIC_FRAMEWORK") }
        } finally { order += if (processed) "PROCESSED" else "CLEANUP_FAILED" }
        assertFalse(processed); assertEquals(listOf("EVENT", "OWNER", "RUNTIME", "CLEANUP_FAILED"), order)
    }
    @Test fun normalEventAndInvalidationOrderAreUnchanged() {
        val order = mutableListOf<String>()
        assertTrue(completeSynchronousAccessibilityCallback({ error("NO_CANCEL") }) {
            invalidateAfterOwnedCleanup({ order += "OWNER" }, { order += "RUNTIME" })
        })
        assertEquals(listOf("OWNER", "RUNTIME"), order)
    }
    @Test fun ordinaryAndroidEventFailureIsFixedCodeRejectedAndNeverProcessedSuccess() {
        val failure = IllegalStateException("SYNTHETIC_FAILURE"); val issues = mutableListOf<ManualCleanupIssue>()
        assertFalse(completeSynchronousAccessibilityCallback(issues::add) { throw failure })
        assertEquals(listOf(ManualCleanupIssue.EXCEPTION), issues)
        assertFalse(issues.toString().contains("SYNTHETIC"))
    }

    @Test fun receiptCleanupFailureIsInsideAndroidBoundaryAndCannotLeaveRuntimeCurrent() {
        val order = mutableListOf<String>(); var runtimeCurrent = true; var receiptCalls = 0
        val issues = mutableListOf<ManualCleanupIssue>()
        assertFalse(completeSynchronousAccessibilityCallback(onRejected = { issue ->
            issues += issue
            invalidateAfterOwnedCleanup({ order += "INVALIDATE_OWNER"; error("SYNTHETIC_SECONDARY") },
                { order += "INVALIDATE_RUNTIME"; runtimeCurrent = false })
        }) {
            kr.closeforyou.accessibility.CollectionResourceSafety.run(body = { order += "HANDLER" },
                cleanup = { receiptCalls++; order += "RECEIPT"; error("SYNTHETIC_RECEIPT") },
                cleanupFailure = { throw kr.closeforyou.accessibility.CollectionCleanupFailure() })
        })
        assertEquals(1, receiptCalls); assertFalse(runtimeCurrent)
        assertEquals(listOf(ManualCleanupIssue.EXCEPTION), issues)
        assertEquals(listOf("HANDLER", "RECEIPT", "INVALIDATE_OWNER", "INVALIDATE_RUNTIME"), order)
    }

}
