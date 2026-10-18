package kr.closeforyou.servicetest

import kr.closeforyou.capturetest.PriorityFixture
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class ProtectionPriorityContractTest {
    private fun row(fixture: PriorityFixture, ordinal: Int = 1): String {
        val fields = when (fixture) {
            PriorityFixture.CONTROL -> "UNREADABLE_TREE:SNAPSHOT_PASS:TREE_PROTECTION:TARGET:NONE:MALFORMED_NODE_BOUNDS:NONE:VERIFIED:NODE_BOUNDS_VALIDATE:NONE:REVERSED_Y:NOT_VISIBLE:SCROLL_ANCESTOR:DESCENDANT"
            PriorityFixture.PASSWORD -> "PASSWORD:PROTECTION_PASS:TREE_PROTECTION:TARGET:PASSWORD_FLAG:PASSWORD_FLAG:NONE:VERIFIED:NODE_FIELD_CHECK:NONE:UNOBSERVED:UNOBSERVED:UNOBSERVED:UNOBSERVED"
            PriorityFixture.EDITABLE -> "EDITABLE_FIELD:PROTECTION_PASS:TREE_PROTECTION:TARGET:EDITABLE_FLAG:EDITABLE_FLAG:NONE:VERIFIED:NODE_FIELD_CHECK:NONE:UNOBSERVED:UNOBSERVED:UNOBSERVED:UNOBSERVED"
            PriorityFixture.SENSITIVE_TEXT -> "SENSITIVE_CONTENT:PROTECTION_PASS:TREE_PROTECTION:TARGET:TEXT:SENSITIVE_TEXT:NONE:VERIFIED:NODE_TEXT_CHECK:NONE:UNOBSERVED:UNOBSERVED:UNOBSERVED:UNOBSERVED"
        }
        return "$ordinal:BLOCKED:$fields:OLDER:CONTENT_CHANGED"
    }
    private fun field(fixture: PriorityFixture) = when (fixture) {
        PriorityFixture.CONTROL -> PriorityField.NONE
        PriorityFixture.PASSWORD -> PriorityField.PASSWORD
        PriorityFixture.EDITABLE -> PriorityField.EDITABLE
        PriorityFixture.SENSITIVE_TEXT -> PriorityField.SENSITIVE_TEXT
    }
    private fun tree(fixture: PriorityFixture) = listOf(
        PriorityProbeNode(PriorityMarker.OTHER, false, true, false, PriorityField.NONE),
        PriorityProbeNode(PriorityMarker.FIRST, true, false, true, PriorityField.NONE),
        PriorityProbeNode(PriorityMarker.LATER, true, false, true, field(fixture)))

    @Test fun fourSeparateExpectedTuplesPass() {
        PriorityFixture.entries.forEach { assertEquals(PriorityVerdict.EXPECTED_BLOCK, ProtectionPriorityContract.assess(it, listOf(row(it)), false)) }
    }
    @Test fun repeatedExpectedAttemptsNeedDistinctOrdinals() {
        assertEquals(PriorityVerdict.EXPECTED_BLOCK, ProtectionPriorityContract.assess(PriorityFixture.PASSWORD,
            listOf(row(PriorityFixture.PASSWORD), row(PriorityFixture.PASSWORD, 2)), false))
        assertEquals(PriorityVerdict.INVALID_DIAGNOSTIC, ProtectionPriorityContract.assess(PriorityFixture.PASSWORD,
            listOf(row(PriorityFixture.PASSWORD), row(PriorityFixture.PASSWORD)), false))
    }
    @Test fun differentFixturesCannotMaskEachOther() {
        for (expected in PriorityFixture.entries) for (actual in PriorityFixture.entries.filter { it != expected })
            assertEquals(PriorityVerdict.UNEXPECTED_ATTEMPT, ProtectionPriorityContract.assess(expected, listOf(row(actual)), false))
    }
    @Test fun missingObservationAndOverflowNeverPass() {
        assertEquals(PriorityVerdict.UNOBSERVED, ProtectionPriorityContract.assess(PriorityFixture.CONTROL, emptyList(), false))
        assertEquals(PriorityVerdict.OVERFLOW, ProtectionPriorityContract.assess(PriorityFixture.CONTROL, listOf(row(PriorityFixture.CONTROL)), true))
        assertEquals(PriorityVerdict.OVERFLOW, ProtectionPriorityContract.assess(PriorityFixture.CONTROL, List(257) { row(PriorityFixture.CONTROL) }, false))
    }
    @Test fun phaseMustBeFromTheSameAttemptAsProtectionReason() {
        val wrongPhase = row(PriorityFixture.PASSWORD).replace("PROTECTION_PASS", "SNAPSHOT_PASS")
        assertEquals(PriorityVerdict.UNEXPECTED_ATTEMPT, ProtectionPriorityContract.assess(PriorityFixture.PASSWORD, listOf(wrongPhase), false))
        assertEquals(PriorityVerdict.UNEXPECTED_ATTEMPT, ProtectionPriorityContract.assess(PriorityFixture.PASSWORD,
            listOf(row(PriorityFixture.CONTROL), row(PriorityFixture.PASSWORD, 2)), false))
    }
    @Test fun timeLimitAndTargetMissingRemainFailuresEvenBesideExpectedResult() {
        val time = row(PriorityFixture.CONTROL).replace("UNREADABLE_TREE", "TRUNCATED_TREE").replace("MALFORMED_NODE_BOUNDS", "TIME_LIMIT")
        val missing = row(PriorityFixture.CONTROL).replace("SNAPSHOT_PASS", "WINDOWS").replace("TREE_PROTECTION", "TARGET_MISSING")
        for (bad in listOf(time, missing)) assertEquals(PriorityVerdict.UNEXPECTED_ATTEMPT,
            ProtectionPriorityContract.assess(PriorityFixture.CONTROL, listOf(bad, row(PriorityFixture.CONTROL, 2)), false))
    }
    @Test fun assessmentsAndWindowGeometryCannotReplaceControlBlock() {
        val geometry = "1:ASSESSED:UNCERTAIN:INVALID_GEOMETRY:WINDOW_OUTSIDE_FRAME:POSITIVE:POSITIVE:WINDOW_OUTSIDE_FRAME:RIGHT:OLDER"
        assertEquals(PriorityVerdict.UNEXPECTED_ATTEMPT, ProtectionPriorityContract.assess(PriorityFixture.CONTROL, listOf(geometry), false))
    }
    @Test fun controlRequiresActualMalformedContextAndExactOperation() {
        for ((from, to) in listOf("REVERSED_Y" to "REVERSED_X", "NOT_VISIBLE" to "VISIBLE", "SCROLL_ANCESTOR" to "NO_SCROLL_ANCESTOR",
            "DESCENDANT" to "ROOT", "NODE_BOUNDS_VALIDATE" to "NODE_DTO", "VERIFIED" to "UNAVAILABLE"))
            assertEquals(PriorityVerdict.UNEXPECTED_ATTEMPT, ProtectionPriorityContract.assess(PriorityFixture.CONTROL,
                listOf(row(PriorityFixture.CONTROL).replace(from, to)), false))
    }
    @Test fun backgroundReasonAndWrongFieldNeverProveTarget() {
        for ((from, to) in listOf("TARGET" to "SAME_APP_BACKGROUND", "NODE_FIELD_CHECK" to "NODE_TEXT_CHECK", ":TARGET:PASSWORD_FLAG:" to ":TARGET:INPUT_TYPE:"))
            assertEquals(PriorityVerdict.UNEXPECTED_ATTEMPT, ProtectionPriorityContract.assess(PriorityFixture.PASSWORD,
                listOf(row(PriorityFixture.PASSWORD).replace(from, to)), false))
    }
    @Test fun arbitraryOrOversizeDiagnosticNeverEscapesVerdict() {
        val secret = "SYNTHETIC_PRIVATE_TEXT_COORDINATE_TOKEN_DIGEST"
        for (input in listOf(secret, " ".repeat(10_000) + secret, row(PriorityFixture.CONTROL) + secret)) {
            val result = ProtectionPriorityContract.assess(PriorityFixture.CONTROL, listOf(input), false)
            assertEquals(PriorityVerdict.INVALID_DIAGNOSTIC, result)
            assertFalse(result.toString().contains(secret))
        }
    }
    @Test fun actualPreorderFactsAreRequiredForAllFixtures() {
        PriorityFixture.entries.forEach { assertTrue(priorityTreeEstablished(it, tree(it))) }
    }
    @Test fun reversedOrderAndMissingOrDuplicateMarkersFail() {
        val nodes = tree(PriorityFixture.CONTROL)
        for (bad in listOf(nodes.reversed(), nodes.dropLast(1), nodes.drop(2), nodes + nodes[1], nodes + nodes[2]))
            assertFalse(priorityTreeEstablished(PriorityFixture.CONTROL, bad))
    }
    @Test fun visibilityAncestryAndReversedYAreAllRequired() {
        val nodes = tree(PriorityFixture.CONTROL)
        for (changed in listOf(nodes[1].copy(visible = true), nodes[1].copy(underScroll = false), nodes[1].copy(reversedY = false)))
            assertFalse(priorityTreeEstablished(PriorityFixture.CONTROL, listOf(nodes[0], changed, nodes[2])))
        assertFalse(priorityTreeEstablished(PriorityFixture.CONTROL, nodes.dropLast(1) + nodes.last().copy(visible = true)))
        assertFalse(priorityTreeEstablished(PriorityFixture.CONTROL, nodes.dropLast(1) + nodes.last().copy(underScroll = false)))
    }
    @Test fun earlierProtectedFieldAndUnexpectedLabelsCannotMaskLaterProbe() {
        val nodes = tree(PriorityFixture.PASSWORD)
        assertFalse(priorityTreeEstablished(PriorityFixture.PASSWORD, nodes.mapIndexed { i, n -> if (i == 0) n.copy(field = PriorityField.PASSWORD) else n }))
        assertFalse(priorityTreeEstablished(PriorityFixture.PASSWORD, nodes.mapIndexed { i, n -> if (i == 2) n.copy(field = PriorityField.UNEXPECTED) else n }))
        assertFalse(priorityTreeEstablished(PriorityFixture.EDITABLE, nodes))
    }
    @Test fun nodeCountBoundsAndRepresentationsRemainSafe() {
        val nodes = tree(PriorityFixture.CONTROL)
        assertFalse(priorityTreeEstablished(PriorityFixture.CONTROL, emptyList()))
        assertTrue(priorityTreeEstablished(PriorityFixture.CONTROL, nodes + List(253) { nodes[0] }))
        assertFalse(priorityTreeEstablished(PriorityFixture.CONTROL, nodes + List(254) { nodes[0] }))
        assertEquals("PriorityProbeNode(codes_only)", nodes[1].toString())
    }

    @Test fun initiallyExpectedBlockCannotHideLateUnexpectedAttempts() {
        val fixture = PriorityFixture.CONTROL
        val initial = listOf(row(fixture))
        assertEquals(PriorityVerdict.EXPECTED_BLOCK, ProtectionPriorityContract.assess(fixture, initial, false))
        for (late in listOf(row(PriorityFixture.PASSWORD, 2),
            row(fixture, 2).replace("UNREADABLE_TREE", "TRUNCATED_TREE").replace("MALFORMED_NODE_BOUNDS", "TIME_LIMIT"),
            row(fixture, 2).replace("SNAPSHOT_PASS", "WINDOWS").replace("TREE_PROTECTION", "TARGET_MISSING"))) {
            assertEquals(PriorityVerdict.UNEXPECTED_ATTEMPT,
                ProtectionPriorityContract.assessResponse(fixture, "OBSERVED", initial + late, false))
        }
    }

    @Test fun initiallyExpectedBlockCannotHideFinalOverflowDuplicateOrMalformedRows() {
        val fixture = PriorityFixture.PASSWORD
        val initial = listOf(row(fixture))
        assertEquals(PriorityVerdict.EXPECTED_BLOCK, ProtectionPriorityContract.assess(fixture, initial, false))
        assertEquals(PriorityVerdict.OVERFLOW, ProtectionPriorityContract.assessResponse(fixture, "OBSERVED", initial, true))
        for (final in listOf(initial + initial, initial + "SYNTHETIC_PRIVATE_DIAGNOSTIC")) {
            assertEquals(PriorityVerdict.INVALID_DIAGNOSTIC,
                ProtectionPriorityContract.assessResponse(fixture, "OBSERVED", final, false))
        }
    }

    @Test fun finalUnobservedOrNonObservedReplyCannotPass() {
        val fixture = PriorityFixture.PASSWORD
        val initial = listOf(row(fixture))
        assertEquals(PriorityVerdict.EXPECTED_BLOCK, ProtectionPriorityContract.assess(fixture, initial, false))
        assertEquals(PriorityVerdict.UNOBSERVED, ProtectionPriorityContract.assessResponse(fixture, "OBSERVED", emptyList(), false))
        for (code in listOf(null, "ENDED", "F1_SESSION_INVALIDATED", "SYNTHETIC_PRIVATE_REPLY")) {
            val verdict = ProtectionPriorityContract.assessResponse(fixture, code, initial, false)
            assertEquals(PriorityVerdict.RESPONSE_REJECTED, verdict)
            assertFalse(verdict.toString().contains("SYNTHETIC_PRIVATE_REPLY"))
        }
    }

    @Test fun finalValidSnapshotPassesIndependentlyForEachFixture() {
        for (fixture in PriorityFixture.entries) {
            assertEquals(PriorityVerdict.EXPECTED_BLOCK, ProtectionPriorityContract.assess(fixture, listOf(row(fixture)), false))
            assertEquals(PriorityVerdict.EXPECTED_BLOCK, ProtectionPriorityContract.assessResponse(fixture, "OBSERVED",
                listOf(row(fixture), row(fixture, 2)), false))
        }
    }

    @Test fun normalReleasesPreserveResultWithoutRetainedFailure() {
        val releases = PriorityReleaseScope()
        val released = mutableListOf<Int>()
        val result = releases.protect { scope ->
            try { 7 } finally { scope.releaseAll(listOf(1, 2, 3)) { released += it } }
        }
        assertEquals(7, result)
        assertEquals(listOf(1, 2, 3), released)
        assertEquals(PriorityReleaseFailure.NONE, releases.failure)
    }

    @Test fun releaseFailuresDoNotSkipRemainingObjectsOrExposeOriginalException() {
        val releases = PriorityReleaseScope()
        val released = mutableListOf<Int>()
        val failure = assertThrows(PriorityReleaseException::class.java) {
            releases.protect { scope ->
                try { Unit } finally {
                    scope.releaseAll(listOf(1, 2, 3)) {
                        released += it
                        if (it != 3) throw IllegalStateException("SYNTHETIC_PRIVATE_RELEASE_VALUE")
                    }
                }
            }
        }
        assertEquals(listOf(1, 2, 3), released)
        assertEquals(PriorityReleaseFailure.FRAMEWORK_RELEASE_FAILED, releases.failure)
        assertEquals("FRAMEWORK_RELEASE_FAILED", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.toString().contains("SYNTHETIC_PRIVATE_RELEASE_VALUE"))
        assertEquals("PriorityReleaseScope(codes_only)", releases.toString())
    }

    @Test fun duplicateWindowReferencesAreReleasedOnceButEqualDistinctObjectsAreBothReleased() {
        data class Handle(val value: Int)
        val first = Handle(1)
        val equalButDistinct = Handle(1)
        val released = mutableListOf<Handle>()
        PriorityReleaseScope().protect { scope ->
            try { Unit } finally { scope.releaseAll(listOf(first, first, equalButDistinct)) { released += it } }
        }
        assertEquals(2, released.size)
        assertSame(first, released[0])
        assertSame(equalButDistinct, released[1])
    }

    @Test fun originalInspectionFailureSurvivesAllReleaseFailures() {
        val original = IllegalArgumentException("SYNTHETIC_PRIMARY")
        val releases = PriorityReleaseScope()
        val released = mutableListOf<Int>()
        val actual = assertThrows(IllegalArgumentException::class.java) {
            releases.protect { scope ->
                try { throw original } finally {
                    scope.releaseAll(listOf(1, 2, 3)) { released += it; throw IllegalStateException("SYNTHETIC_SECONDARY") }
                }
            }
        }
        assertSame(original, actual)
        assertEquals(listOf(1, 2, 3), released)
        assertEquals(PriorityReleaseFailure.FRAMEWORK_RELEASE_FAILED, releases.failure)
        assertTrue(actual.suppressed.isEmpty())
    }

    @Test fun originalCancellationSurvivesReleaseExceptionsWithoutSkippingCleanup() {
        val original = CancellationException("SYNTHETIC_CANCEL")
        val released = mutableListOf<Int>()
        val actual = assertThrows(CancellationException::class.java) {
            PriorityReleaseScope().protect { scope ->
                try { throw original } finally {
                    scope.releaseAll(listOf(1, 2)) { released += it; throw IllegalStateException("SYNTHETIC_SECONDARY") }
                }
            }
        }
        assertSame(original, actual)
        assertEquals(listOf(1, 2), released)
        assertTrue(actual.suppressed.isEmpty())
    }

    @Test fun releaseCancellationStillAttemptsRemainingReleasesAndPropagatesFixedCancellation() {
        val releases = PriorityReleaseScope()
        val released = mutableListOf<Int>()
        val failure = assertThrows(CancellationException::class.java) {
            releases.protect { scope ->
                try { Unit } finally {
                    scope.releaseAll(listOf(1, 2)) { released += it; if (it == 1) throw CancellationException("SYNTHETIC_PRIVATE_CANCEL") }
                }
            }
        }
        assertEquals(listOf(1, 2), released)
        assertEquals(PriorityReleaseFailure.RELEASE_CANCELLED, releases.failure)
        assertEquals("PRIORITY_RELEASE_CANCELLED", failure.message)
        assertNull(failure.cause)
    }

    @Test fun childRootAndWindowCleanupAllRunWithoutReplacingPrimaryGuard() {
        val primary = IllegalArgumentException("SYNTHETIC_GUARD")
        val order = mutableListOf<String>()
        val actual = assertThrows(IllegalArgumentException::class.java) {
            PriorityReleaseScope().protect { scope ->
                try {
                    try { throw primary }
                    finally { scope.release { order += "CHILD"; throw IllegalStateException("SYNTHETIC_CHILD") } }
                } finally {
                    scope.release { order += "ROOT"; throw IllegalStateException("SYNTHETIC_ROOT") }
                    scope.releaseAll(listOf("WINDOW_A", "WINDOW_B")) { order += it; throw IllegalStateException("SYNTHETIC_WINDOW") }
                }
            }
        }
        assertSame(primary, actual)
        assertEquals(listOf("CHILD", "ROOT", "WINDOW_A", "WINDOW_B"), order)
    }

    @Test fun cleanupNeverReplacesFatalBodyFailureOrRetainsResourceAndExceptionPayloads() {
        val fatal = AssertionError("SYNTHETIC_FATAL")
        val scope = PriorityReleaseScope()
        val actual = assertThrows(AssertionError::class.java) {
            scope.protect { releases ->
                try { throw fatal } finally { releases.release { throw IllegalStateException("SYNTHETIC_RELEASE") } }
            }
        }
        assertSame(fatal, actual)
        assertTrue(PriorityReleaseScope::class.java.declaredFields.all {
            it.type == PriorityReleaseFailure::class.java || it.type == java.lang.Boolean.TYPE
        })
    }
}
