package kr.closeforyou.accessibility

import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kr.closeforyou.accessibility.capture.EvidenceIssue
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.*
import org.junit.Test

class EvidenceBlockDiagnosticTest {
    private val original = MockPopupFixtures.notice().snapshot
    private fun line(d: EvidenceBlockDiagnostic): String {
        var result: String? = null
        d.report { result = it }
        return requireNotNull(result)
    }
    private fun blocked(d: EvidenceBlockDiagnostic) = d.apply {
        blocked(PrivacyBlockReason.UNREADABLE_TREE, EvidenceIssue.PRIVACY_BLOCKED)
    }

    @Test fun unchangedDtosMatch() {
        assertEquals(EvidenceMismatch.NONE, EvidenceSnapshotComparison.firstMismatch(original, original.copy()))
    }

    @Test fun eachOriginalComparisonRejectsWithItsOwnCode() {
        val changes = listOf(
            EvidenceMismatch.PACKAGE to original.copy(token = original.token.copy(packageName = "synthetic.other")),
            EvidenceMismatch.WINDOW to original.copy(token = original.token.copy(windowId = original.token.windowId + 1)),
            EvidenceMismatch.STRUCTURE to original.copy(token = original.token.copy(structureDigest = "synthetic.structure")),
            EvidenceMismatch.CONTENT to original.copy(token = original.token.copy(contentDigest = "synthetic.content")),
            EvidenceMismatch.FRAME to original.copy(screenBounds = original.screenBounds.copy(right = original.screenBounds.right + 1)),
            EvidenceMismatch.WINDOW_SET to original.copy(windows = original.windows.map {
                it.copy(focused = !it.focused)
            }.toPersistentList()),
        )
        for ((code, fresh) in changes) {
            assertEquals(code, EvidenceSnapshotComparison.firstMismatch(original, fresh))
            val a = original.token; val b = fresh.token
            val oldRejected = a.packageName != b.packageName || a.windowId != b.windowId ||
                a.structureDigest != b.structureDigest || a.contentDigest != b.contentDigest ||
                original.screenBounds != fresh.screenBounds || original.windows.toSet() != fresh.windows.toSet()
            assertEquals(oldRejected, EvidenceSnapshotComparison.firstMismatch(original, fresh) != EvidenceMismatch.NONE)
        }
    }

    @Test fun firstMismatchKeepsOriginalShortCircuitOrder() {
        val changed = original.copy(token = original.token.copy(packageName = "synthetic.other",
            windowId = original.token.windowId + 1, structureDigest = "other", contentDigest = "other"),
            screenBounds = original.screenBounds.copy(right = original.screenBounds.right + 1), windows = kotlinx.collections.immutable.persistentListOf())
        assertEquals(EvidenceMismatch.PACKAGE, EvidenceSnapshotComparison.firstMismatch(original, changed))
        val samePackage = changed.copy(token = changed.token.copy(packageName = original.token.packageName))
        assertEquals(EvidenceMismatch.WINDOW, EvidenceSnapshotComparison.firstMismatch(original, samePackage))
    }

    @Test fun windowOrderingAndDuplicatesHaveTheSameExistingSetSemantics() {
        val fresh = original.copy(windows = (original.windows.reversed() + original.windows).toPersistentList())
        assertEquals(EvidenceMismatch.NONE, EvidenceSnapshotComparison.firstMismatch(original, fresh))
    }

    @Test fun newlyGeneratedPopupIdentityIsNotComparedOrLogged() {
        val fresh = original.copy(token = original.token.copy(popupInstanceId = "synthetic-secret-token"))
        assertEquals(EvidenceMismatch.NONE, EvidenceSnapshotComparison.firstMismatch(original, fresh))
        val d = blocked(EvidenceBlockDiagnostic())
        assertFalse(line(d).contains("synthetic-secret-token"))
    }

    @Test fun recollectionFailureCopiesOnlyExistingEnumCodes() {
        val d = EvidenceBlockDiagnostic()
        d.rejected(EvidenceBlockSource.RECOLLECTION_FAILED, collection = CollectionProtectionDiagnostic(
            CollectionPhase.SNAPSHOT_PASS, CaptureDetail.NONE, finding = CollectionFinding.TIME_LIMIT,
            operation = CollectionOperation.TREE_BUDGET))
        blocked(d)
        val code = line(d)
        assertTrue(code.contains("SOURCE=RECOLLECTION_FAILED;CHECK=NONE;REASON=UNREADABLE_TREE;ISSUE=PRIVACY_BLOCKED"))
        assertTrue(code.endsWith("COLLECTION_PHASE=SNAPSHOT_PASS;OPERATION=TREE_BUDGET;FINDING=TIME_LIMIT"))
    }

    @Test fun mismatchLineIsBoundedAndHasOnlyFixedCodes() {
        val d = blocked(EvidenceBlockDiagnostic().apply {
            rejected(EvidenceBlockSource.SNAPSHOT_MISMATCH, EvidenceMismatch.CONTENT)
        })
        val code = line(d)
        assertEquals("A_EVIDENCE_BLOCK;SOURCE=SNAPSHOT_MISMATCH;CHECK=CONTENT;REASON=UNREADABLE_TREE;ISSUE=PRIVACY_BLOCKED;COLLECTION_PHASE=NONE;OPERATION=NONE;FINDING=NONE", code)
        assertTrue(code.matches(Regex("[A-Z_;=]+")))
        assertTrue(code.length < 512)
        assertEquals("EvidenceBlockDiagnostic(redacted)", d.toString())
    }

    @Test fun firstCauseAndFirstBlockedResultAreNotOverwritten() {
        val d = blocked(EvidenceBlockDiagnostic().apply {
            rejected(EvidenceBlockSource.SNAPSHOT_MISMATCH, EvidenceMismatch.FRAME)
        })
        d.rejected(EvidenceBlockSource.TEXT_PROTECTION)
        d.blocked(PrivacyBlockReason.PASSWORD, EvidenceIssue.PRIVACY_UNCERTAIN)
        val code = line(d)
        assertTrue(code.contains("SOURCE=SNAPSHOT_MISMATCH;CHECK=FRAME;REASON=UNREADABLE_TREE;ISSUE=PRIVACY_BLOCKED"))
    }

    @Test fun noLineUntilActualBlockedOutcomeAndAtMostOneAfterwards() {
        val d = EvidenceBlockDiagnostic()
        d.rejected(EvidenceBlockSource.TEXT_PROTECTION)
        var writes = 0
        d.report { writes++ }
        assertEquals(0, writes)
        blocked(d)
        repeat(3) { d.report { writes++ } }
        assertEquals(1, writes)
    }

    @Test fun separateEvidenceOperationsDoNotShareDiagnostics() {
        val first = blocked(EvidenceBlockDiagnostic().apply { rejected(EvidenceBlockSource.TEXT_PROTECTION) })
        val second = blocked(EvidenceBlockDiagnostic())
        assertTrue(line(first).contains("SOURCE=TEXT_PROTECTION"))
        assertTrue(line(second).contains("SOURCE=UNSPECIFIED"))
    }

    @Test fun loggingFailureIsNotRetriedOrExposed() {
        val d = blocked(EvidenceBlockDiagnostic())
        d.report { throw IllegalStateException("synthetic-private-exception") }
        d.report { fail("UNEXPECTED_RETRY") }
        assertEquals("EvidenceBlockDiagnostic(redacted)", d.toString())
    }

    @Test fun loggingCancellationPropagatesWithoutWrappingOrRetainingIt() {
        val d = blocked(EvidenceBlockDiagnostic())
        val cancelled = CancellationException("synthetic-private-exception")
        try {
            d.report { throw cancelled }
            fail("CANCELLATION_LOST")
        } catch (caught: CancellationException) {
            assertSame(cancelled, caught)
        }
        assertTrue(cancelled.suppressed.isEmpty())
        assertEquals("EvidenceBlockDiagnostic(redacted)", d.toString())
    }
}
