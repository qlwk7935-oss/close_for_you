package kr.closeforyou.accessibility

import kr.closeforyou.core.model.PrivacyBlockReason
import org.junit.Assert.*
import org.junit.Test

/** Scripted scalar getters and traversal callbacks, NOT Android collector/service instrumentation. */
class ProtectionRootGuardTest {
    private val own = "synthetic.target"
    private val foreign = "synthetic.foreign"
    private fun registered() = ProtectionRootGuard(own, own, 7)
    private fun excluded() = ProtectionRootGuard(foreign, own, 8)

    @Test fun targetOwnershipUnchangedInvokesTraversalExactlyOnce() {
        val calls = mutableListOf<String>()
        val progress = CollectionProgress()
        registered().inspect(progress, { calls += "package"; own }, { calls += "window"; 7 }) { calls += "tree" }
        assertEquals(listOf("package", "window", "tree"), calls)
        assertNull(progress.failure)
    }

    @Test fun sameAppBackgroundOwnershipUnchangedInvokesTraversal() {
        var traversed = false
        ProtectionRootGuard(own, own, 8).inspect(CollectionProgress(), { own }, { 8 }) { traversed = true }
        assertTrue(traversed)
    }

    @Test fun registeredTargetChangingPackageDenies() = packageDenies(registered(), foreign)

    @Test fun registeredBackgroundChangingPackageDenies() =
        packageDenies(ProtectionRootGuard(own, own, 8), foreign)

    @Test fun registeredTargetLosingPackageDenies() = packageDenies(registered(), null)

    @Test fun registeredBackgroundLosingPackageDenies() =
        packageDenies(ProtectionRootGuard(own, own, 8), null)

    @Test fun packageGetterFailureStopsBeforeWindowOrTraversalAndKeepsLocation() {
        val progress = CollectionProgress()
        val failure = SecurityException("SYNTHETIC_PRIVATE_MESSAGE")
        val actual = assertThrows(SecurityException::class.java) {
            registered().inspect(progress, { throw failure }, { error("must not read window") }) { fail("must not traverse") }
        }
        assertSame(failure, actual)
        assertEquals(CollectionFailureLocation(CollectionOperation.NODE_PACKAGE_READ, CollectionField.NODE_PACKAGE,
            CollectionExceptionKind.SECURITY), progress.failure)
        assertFalse(progress.failure.toString().contains("SYNTHETIC_PRIVATE"))
    }

    @Test fun registeredWindowMismatchDeniesWithoutTraversal() {
        val progress = CollectionProgress()
        val failure = assertThrows(CollectionGuardFailure::class.java) {
            registered().inspect(progress, { own }, { 8 }) { fail("must not traverse") }
        }
        assertEquals(PrivacyBlockReason.UNREADABLE_TREE, failure.reason)
        assertEquals(CollectionFinding.ROOT_WINDOW_MISMATCH, failure.finding)
        assertEquals(CollectionOperation.ROOT_WINDOW_READ, progress.failure?.operation)
        assertNull(failure.message)
    }

    @Test fun windowGetterFailureStopsTraversalAndKeepsLocation() {
        val progress = CollectionProgress()
        val failure = IllegalStateException("SYNTHETIC_PRIVATE_WINDOW")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            registered().inspect(progress, { own }, { throw failure }) { fail("must not traverse") }
        })
        assertEquals(CollectionFailureLocation(CollectionOperation.ROOT_WINDOW_READ, CollectionField.NONE,
            CollectionExceptionKind.ILLEGAL_STATE), progress.failure)
    }

    @Test fun permittedForeignBackgroundRemainsExcludedWithoutNewWindowGetter() {
        val progress = CollectionProgress()
        excluded().inspect(progress, { foreign }, { error("excluded window getter") }) { fail("excluded tree") }
        assertNull(progress.failure)
    }

    @Test fun excludedNullOrOtherForeignPackageRetainsExistingSkipBehavior() {
        for (current in listOf(null, "synthetic.other")) {
            val progress = CollectionProgress()
            excluded().inspect(progress, { current }, { error("excluded window getter") }) { fail("excluded tree") }
            assertNull(progress.failure)
        }
    }

    @Test fun excludedRootJoiningTargetScopeDeniesRatherThanAddingOrSkippingTree() {
        val progress = CollectionProgress()
        val failure = assertThrows(CollectionGuardFailure::class.java) {
            excluded().inspect(progress, { own }, { error("must not read window") }) { fail("must not traverse") }
        }
        assertEquals(PrivacyBlockReason.UNREADABLE_TREE, failure.reason)
        assertEquals(CollectionFinding.PROTECTION_SCOPE_CHANGED, failure.finding)
        assertEquals(CollectionField.NODE_PACKAGE, failure.field)
        assertEquals(CollectionOperation.NODE_PACKAGE_READ, progress.failure?.operation)
    }

    @Test fun excludedRootGetterExceptionStillPropagates() {
        val progress = CollectionProgress()
        val failure = IllegalStateException("synthetic")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            excluded().inspect(progress, { throw failure }, { error("must not read window") }) { fail("excluded tree") }
        })
        assertEquals(CollectionOperation.NODE_PACKAGE_READ, progress.failure?.operation)
    }

    @Test fun secondRootPackageReadCannotAcceptNullAfterSuccessfulOwnershipGuard() {
        val progress = CollectionProgress()
        var afterProtection = false
        val failure = assertThrows(CollectionGuardFailure::class.java) {
            registered().inspect(progress, { own }, { 7 }) {
                progress.at(CollectionOperation.NODE_PACKAGE_READ, CollectionField.NODE_PACKAGE) {
                    ProtectionRootGuard.requirePackage(null, own)
                }
                afterProtection = true
            }
        }
        assertFalse(afterProtection)
        assertEquals(CollectionFinding.PACKAGE_MISMATCH, failure.finding)
    }

    @Test fun strictRootPackageCheckRejectsChangedPackageAndAcceptsOriginal() {
        ProtectionRootGuard.requirePackage(own, own)
        val failure = assertThrows(CollectionGuardFailure::class.java) {
            ProtectionRootGuard.requirePackage(foreign, own)
        }
        assertEquals(CollectionFinding.PACKAGE_MISMATCH, failure.finding)
    }

    @Test fun registeredBackgroundPropagatesHiddenSensitiveChildFailure() {
        val progress = CollectionProgress()
        var afterProtection = false
        val failure = assertThrows(CollectionGuardFailure::class.java) {
            ProtectionRootGuard(own, own, 8).inspect(progress, { own }, { 8 }) {
                // Synthetic traversal callback checks both records regardless of their visibility.
                val children = listOf(true to "합성 문서", false to "합성 비밀번호")
                for ((_, text) in children) progress.at(CollectionOperation.NODE_TEXT_CHECK, CollectionField.TEXT) {
                    CollectionProtectionCheck.text(text, CollectionField.TEXT, 4_096)
                }
                afterProtection = true
            }
        }
        assertFalse(afterProtection)
        assertEquals(PrivacyBlockReason.SENSITIVE_CONTENT, failure.reason)
        assertEquals(CollectionFinding.SENSITIVE_TEXT, failure.finding)
        assertEquals(CollectionOperation.NODE_TEXT_CHECK, progress.failure?.operation)
    }

    @Test fun traversalFailureRetainsFirstDiagnosticThroughOuterBoundary() {
        val progress = CollectionProgress()
        assertThrows(CollectionGuardFailure::class.java) {
            progress.at(CollectionOperation.WINDOW_ROOT_READ) {
                registered().inspect(progress, { own }, { 7 }) {
                    progress.at(CollectionOperation.NODE_FIELD_CHECK) { CollectionProtectionCheck.field(true, false, 0) }
                }
            }
        }
        assertEquals(CollectionOperation.NODE_FIELD_CHECK, progress.failure?.operation)
        assertEquals(CollectionExceptionKind.NONE, progress.failure?.exception)
    }

    @Test fun failedGuardCannotReachSubsequentSnapshotStageAndFinallyStillRuns() {
        var snapshotStage = false
        var released = false
        try {
            registered().inspect(CollectionProgress(), { foreign }, { 7 }) { fail("must not traverse") }
            snapshotStage = true
        } catch (failure: CollectionGuardFailure) {
            assertEquals(PrivacyBlockReason.UNREADABLE_TREE, failure.reason)
        } finally {
            released = true
        }
        assertFalse(snapshotStage)
        assertTrue(released)
        // Tests control flow only; actual CollectionResult/Android release/publication need a device.
    }

    @Test fun guardAndNewFailureDiagnosticsDoNotExposePackageWindowOrExceptionValues() {
        val guard = ProtectionRootGuard("PRIVATE_PACKAGE", "PRIVATE_PACKAGE", 184763)
        assertEquals("ProtectionRootGuard(redacted)", guard.toString())
        val progress = CollectionProgress()
        val failure = assertThrows(CollectionGuardFailure::class.java) {
            guard.inspect(progress, { "PRIVATE_PACKAGE" }, { 284763 }) { fail("must not traverse") }
        }
        val encoded = CollectionProtectionDiagnostic(CollectionPhase.PROTECTION_PASS, CaptureDetail.TREE_PROTECTION,
            CollectionTree.SAME_APP_BACKGROUND, finding = failure.finding,
            operation = progress.failure!!.operation).encoded()
        for (sentinel in listOf("PRIVATE_PACKAGE", "184763", "284763")) assertFalse(encoded.contains(sentinel))
        assertNull(failure.message)
    }

    private fun packageDenies(guard: ProtectionRootGuard, current: String?) {
        val progress = CollectionProgress()
        val failure = assertThrows(CollectionGuardFailure::class.java) {
            guard.inspect(progress, { current }, { error("must not read window") }) { fail("must not traverse") }
        }
        assertEquals(PrivacyBlockReason.UNREADABLE_TREE, failure.reason)
        assertEquals(CollectionFinding.PACKAGE_MISMATCH, failure.finding)
        assertEquals(CollectionField.NODE_PACKAGE, failure.field)
        assertEquals(CollectionFailureLocation(CollectionOperation.NODE_PACKAGE_READ, CollectionField.NODE_PACKAGE,
            CollectionExceptionKind.NONE), progress.failure)
        assertNull(failure.message)
    }
}
