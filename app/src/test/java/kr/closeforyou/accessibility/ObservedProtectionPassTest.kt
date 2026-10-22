package kr.closeforyou.accessibility

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Modifier
import java.util.concurrent.CancellationException
import kr.closeforyou.core.model.PrivacyBlockReason
import org.junit.Assert.*
import org.junit.Test

/** Private bookkeeping via test-only reflection + real scalar guards, NOT Android walk execution.
 * There is no production test factory, fake F0 receipt API or downstream authorization path.
 */
class ObservedProtectionPassTest {
    @Test fun targetAloneRecordsOnlyAfterScopeAndTraversalReturn() {
        val h = Owner()
        h.observe(7); val target = h.register(7)
        assertNull(h.completion)
        h.freeze(); assertEquals("SCOPE_FIXED", h.state)
        h.returned(target); assertNull(h.completion)
        h.finish(); assertEquals("PASS_RECORDED", h.state); assertNotNull(h.completion)
    }
    @Test fun targetAndSameAppBackgroundBothMustReturn() {
        val h = Owner(); h.observe(7); h.observe(8)
        val target = h.register(7); val background = h.register(8, "SAME_APP_BACKGROUND")
        h.freeze(); h.returned(background); assertNull(h.completion)
        h.returned(target); h.finish(); assertNotNull(h.completion)
    }
    @Test fun excludedForeignGuardReturnsWithoutCompletingItsTicket() {
        val h = Owner(); h.observe(7); h.observe(8)
        val target = h.register(7); val foreign = h.register(8, "EXCLUDED_FOREIGN")
        h.freeze()
        inspect(h, target)
        inspect(h, foreign, ProtectionRootGuard("synthetic.foreign", OWN, 8), { "synthetic.foreign" }, { 8 }) {
            fail("EXCLUDED_TREE_MUST_NOT_RUN")
        }
        h.finish(); assertNotNull(h.completion)
        assertEquals(listOf(true, false), h.entries.map { h.field(it!!, "returned") })
    }
    @Test fun excludedTicketCannotBeCountedAsProtectionCompletion() {
        val h = Owner(); h.observe(7); h.observe(8)
        val target = h.register(7); val foreign = h.register(8, "EXCLUDED_FOREIGN")
        h.freeze(); h.returned(target); h.returned(foreign); assertDiscarded(h)
    }
    @Test fun onlyExcludedRootsCannotProducePassCompletion() {
        val h = Owner(); h.observe(8); h.register(8, "EXCLUDED_FOREIGN")
        h.freeze(); h.finish(); assertDiscarded(h)
    }
    @Test fun passCannotCompleteDuringRegistration() {
        val h = Owner(); h.observe(7); h.register(7); h.finish(); assertDiscarded(h)
    }
    @Test fun rootCannotCompleteBeforeScopeIsFixed() {
        val h = Owner(); h.observe(7); val target = h.register(7)
        h.returned(target); assertDiscarded(h)
    }
    @Test fun missingRegisteredWindowCannotBeHiddenByTargetCompletion() {
        val h = Owner(); h.observe(7); h.observe(8); val target = h.register(7)
        h.freeze(); h.returned(target); h.finish(); assertDiscarded(h)
    }
    @Test fun extraUnobservedRootCannotReplaceMissingRegistration() {
        val h = Owner(); h.observe(7); h.observe(8); h.register(7); h.register(9, "SAME_APP_BACKGROUND")
        h.freeze(); assertDiscarded(h)
    }
    @Test fun emptyWindowEnumerationCannotSealScope() {
        val h = Owner(); h.register(7); h.freeze(); assertDiscarded(h)
    }
    @Test fun duplicateObservedWindowDiscardsObservationOnly() {
        val h = Owner(); h.observe(7); h.observe(7); assertDiscarded(h)
    }
    @Test fun duplicateRootEvenWithDifferentRoleDiscards() {
        val h = Owner(); h.observe(7); h.register(7); assertNull(h.register(7, "SAME_APP_BACKGROUND"))
        assertDiscarded(h)
    }
    @Test fun multipleTargetsCannotSealScope() {
        val h = Owner(); h.observe(7); h.observe(8); h.register(7); h.register(8)
        h.freeze(); assertDiscarded(h)
    }
    @Test fun missingTargetCannotSealScope() {
        val h = Owner(); h.observe(7); h.register(7, "SAME_APP_BACKGROUND")
        h.freeze(); assertDiscarded(h)
    }
    @Test fun registrationCannotExtendFixedScope() {
        val h = fixedTarget().first; assertNull(h.register(8, "SAME_APP_BACKGROUND")); assertDiscarded(h)
    }
    @Test fun windowEnumerationCannotExtendFixedScope() {
        val h = fixedTarget().first; h.observe(8); assertDiscarded(h)
    }
    @Test fun scopeCannotBeFixedTwice() {
        val h = fixedTarget().first; h.freeze(); assertDiscarded(h)
    }
    @Test fun identicalOtherAttemptTicketCannotCompleteThisRoot() {
        val (a, _) = fixedTarget(); val (_, otherTicket) = fixedTarget()
        a.returned(otherTicket); a.finish(); assertDiscarded(a)
    }
    @Test fun unissuedTicketOfSamePrivateClassCannotCompleteRoot() {
        val (h, ticket) = fixedTarget()
        val forged = ticket!!.javaClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        h.returned(forged); h.finish(); assertDiscarded(h)
    }
    @Test fun duplicateCompletionDiscardsInsteadOfCountingTwice() {
        val (h, target) = fixedTarget(); h.returned(target); h.returned(target); h.finish(); assertDiscarded(h)
    }
    @Test fun nullTicketCannotCompleteRoot() {
        val h = fixedTarget().first; h.returned(null); h.finish(); assertDiscarded(h)
    }
    @Test fun missingBackgroundCompletionPreventsPassRecord() {
        val h = Owner(); h.observe(7); h.observe(8)
        val target = h.register(7); h.register(8, "SAME_APP_BACKGROUND")
        h.freeze(); h.returned(target); h.finish(); assertDiscarded(h)
    }
    @Test fun completedPassCannotBeIssuedAgainOrMoveBackwards() {
        val (h, target) = fixedTarget(); h.returned(target); h.finish(); assertNotNull(h.completion)
        h.finish(); assertDiscarded(h)
        h.observe(7); assertNull(h.register(7)); h.freeze(); h.returned(target); h.finish(); assertDiscarded(h)
    }
    @Test fun maximum32ObservedAndRegisteredRootsStayBounded() {
        val h = Owner()
        val tickets = (0 until 32).map { id ->
            h.observe(id); h.register(id, if (id == 0) "TARGET" else "SAME_APP_BACKGROUND")
        }
        h.freeze(); tickets.forEach(h::returned); h.finish(); assertNotNull(h.completion)
    }
    @Test fun observedWindow33DiscardsWithoutChangingCollectionPolicy() {
        val h = Owner(); (0..32).forEach(h::observe); assertDiscarded(h)
    }
    @Test fun registeredRoot33DiscardsWithoutUnboundedStorage() {
        val h = Owner(); (0..32).forEach { h.register(it, if (it == 0) "TARGET" else "EXCLUDED_FOREIGN") }
        assertDiscarded(h)
    }
    @Test fun registeredPackageAndWindowFailuresCannotRecordTraversal() {
        for (readFailure in 0..3) {
            val (h, target) = fixedTarget(); val progress = CollectionProgress()
            var walked = false
            try {
                assertThrows(Exception::class.java) {
                    inspect(h, target, readPackage = {
                        when (readFailure) { 0 -> null; 1 -> "synthetic.foreign"; 2 -> error("PRIVATE_GETTER"); else -> OWN }
                    }, readWindow = { if (readFailure == 3) 8 else 7 }, progress = progress) { walked = true }
                }
                assertFalse(walked); assertNull(h.completion)
                assertEquals(if (readFailure == 3) CollectionOperation.ROOT_WINDOW_READ else CollectionOperation.NODE_PACKAGE_READ,
                    progress.failure?.operation)
            } finally { h.discard() }
            assertDiscarded(h)
        }
    }
    @Test fun walkExceptionCannotRecordCompletionOrExposeItsMessage() {
        val (h, target) = fixedTarget(); val original = IllegalStateException("PRIVATE_BODY_TOKEN_DIGEST")
        try {
            assertSame(original, assertThrows(IllegalStateException::class.java) { inspect(h, target) { throw original } })
            assertNull(h.completion); assertFalse(h.toString().contains("PRIVATE"))
        } finally { h.discard() }
        assertDiscarded(h)
    }
    @Test fun hiddenSensitiveChildStopsBackgroundAfterTargetReturned() {
        val h = Owner(); h.observe(7); h.observe(8)
        val target = h.register(7); val background = h.register(8, "SAME_APP_BACKGROUND")
        h.freeze(); val progress = CollectionProgress()
        try {
            inspect(h, target)
            val failure = assertThrows(CollectionGuardFailure::class.java) {
                inspect(h, background, ProtectionRootGuard(OWN, OWN, 8), readWindow = { 8 }, progress = progress) {
                    // Both scripted children are inspected, regardless of visibility.
                    for ((_, text) in listOf(true to "합성 문서", false to "합성 비밀번호"))
                        progress.at(CollectionOperation.NODE_TEXT_CHECK, CollectionField.TEXT) {
                            CollectionProtectionCheck.text(text, CollectionField.TEXT, 4096)
                        }
                }
            }
            assertEquals(PrivacyBlockReason.SENSITIVE_CONTENT, failure.reason)
            assertEquals(CollectionOperation.NODE_TEXT_CHECK, progress.failure?.operation)
            assertNull(h.completion)
        } finally { h.discard() }
        assertDiscarded(h)
    }
    @Test fun missingChildAndTimeVisitLimitsNeverRecordPass() {
        for (finding in listOf(CollectionFinding.MISSING_CHILD, CollectionFinding.TIME_LIMIT, CollectionFinding.NODE_COUNT_LIMIT)) {
            val (h, target) = fixedTarget(); val progress = CollectionProgress()
            val operation = if (finding == CollectionFinding.MISSING_CHILD) CollectionOperation.CHILD_READ else CollectionOperation.TREE_BUDGET
            val reason = if (finding == CollectionFinding.MISSING_CHILD) PrivacyBlockReason.UNREADABLE_TREE else PrivacyBlockReason.TRUNCATED_TREE
            try {
                val failure = assertThrows(CollectionGuardFailure::class.java) {
                    inspect(h, target, progress = progress) {
                        progress.at(operation) { throw CollectionGuardFailure(reason, finding = finding) }
                    }
                }
                assertEquals(reason, failure.reason); assertEquals(finding, failure.finding)
                assertEquals(operation, progress.failure?.operation); assertNull(h.completion)
            } finally { h.discard() }
            assertDiscarded(h)
        }
    }
    @Test fun cancellationKeepsDiagnosticClassificationAndDiscardsSyntheticObservation() {
        val (h, target) = fixedTarget(); val progress = CollectionProgress()
        val cancelled = CancellationException("PRIVATE_CANCELLATION")
        var caught: Exception? = null
        try {
            inspect(h, target, progress = progress) {
                progress.at(CollectionOperation.NODE_FIELD_READ) { throw cancelled }
            }
            h.finish()
        } catch (exception: Exception) { caught = exception } // Synthetic classification probe, not capture's cancellation policy.
        finally { h.discard() }
        assertSame(cancelled, caught)
        assertEquals(CollectionExceptionKind.ILLEGAL_STATE, progress.failure?.exception)
        assertFalse(progress.failure.toString().contains("PRIVATE")); assertDiscarded(h)
    }
    @Test fun earlyReturnAndNormalReturnBothDiscardFinalOwnerReferences() {
        for (complete in listOf(false, true)) {
            val h = Owner()
            fun captureLike(): Int {
                try {
                    h.observe(7); val target = h.register(7)
                    if (!complete) return 1
                    h.freeze(); inspect(h, target); h.finish(); assertNotNull(h.completion)
                    return 2
                } finally { h.discard() }
            }
            assertEquals(if (complete) 2 else 1, captureLike()); assertDiscarded(h)
        }
    }
    @Test fun laterMalformedSnapshotCannotKeepEarlierProtectionRecord() {
        val (h, target) = fixedTarget()
        try {
            inspect(h, target); h.finish(); assertNotNull(h.completion)
            assertThrows(CollectionGuardFailure::class.java) {
                NodeBoundsPolicy.convert(0, 2, 1, 0) { shape -> NodeBoundsDiagnostic(shape) }
            }
        } finally { h.discard() }
        assertDiscarded(h)
    }
    @Test fun cleanupFailureKeepsLegacyMaskingButCannotRetainObservationOrOverwriteProgress() {
        val (h, target) = fixedTarget(); val progress = CollectionProgress()
        val cleanupFailure = IllegalStateException("PRIVATE_CLEANUP")
        val caught = assertThrows(IllegalStateException::class.java) {
            try {
                inspect(h, target, progress = progress) {
                    progress.at(CollectionOperation.NODE_FIELD_CHECK) { CollectionProtectionCheck.field(true, false, 0) }
                }
            } finally {
                h.discard() // Must precede any scripted framework cleanup failure.
                progress.at(CollectionOperation.CHILD_RELEASE) { throw cleanupFailure }
            }
        }
        assertSame(cleanupFailure, caught)
        assertEquals(CollectionOperation.NODE_FIELD_CHECK, progress.failure?.operation)
        assertEquals(CollectionExceptionKind.NONE, progress.failure?.exception); assertDiscarded(h)
    }
    @Test fun privateRecordAndTicketsHaveNoPayloadCopyOrPublicFactory() {
        val (h, target) = fixedTarget(); h.returned(target); h.finish()
        val completion = h.completion!!
        val ticket = requireNotNull(target)
        for (value in listOf(h.instance, ticket, completion)) {
            assertTrue(Modifier.isPrivate(value.javaClass.modifiers))
            assertFalse(value.javaClass.declaredMethods.any { it.name == "copy" || it.name.startsWith("component") })
        }
        for (value in listOf(ticket, completion))
            assertTrue(value.javaClass.declaredFields.none { !Modifier.isStatic(it.modifiers) })
    }
    @Test fun representationsAndDiagnosticsNeverPrintSensitiveSyntheticValues() {
        val output = ByteArrayOutputStream(); val stream = PrintStream(output)
        val oldOut = System.out; val oldErr = System.err
        try {
            System.setOut(stream); System.setErr(stream)
            val h = Owner(); h.observe(184763); val ticket = h.register(184763); h.freeze()
            h.returned(ticket); h.finish()
            val values = listOf(h.instance, ticket, h.completion) + h.entries
            assertTrue(values.all { it.toString().endsWith("(redacted)") })
            assertFalse(values.joinToString().contains("184763")); h.discard()
            val progress = CollectionProgress()
            val error = assertThrows(CollectionGuardFailure::class.java) {
                ProtectionRootGuard("PRIVATE_PACKAGE", "PRIVATE_PACKAGE", 184763).inspect(progress,
                    { "PRIVATE_PACKAGE" }, { 284763 }) { fail("MUST_NOT_TRAVERSE") }
            }
            val diagnostic = CollectionProtectionDiagnostic(CollectionPhase.PROTECTION_PASS, CaptureDetail.TREE_PROTECTION,
                finding = error.finding, operation = progress.failure!!.operation).encoded()
            for (secret in listOf("PRIVATE_", "184763", "284763")) assertFalse(diagnostic.contains(secret))
            assertNull(error.message); assertEquals("", output.toString())
        } finally { System.setOut(oldOut); System.setErr(oldErr); stream.close() }
    }

    private fun fixedTarget(): Pair<Owner, Any?> {
        val h = Owner(); h.observe(7); val target = h.register(7); h.freeze(); return h to target
    }
    private fun inspect(h: Owner, ticket: Any?, guard: ProtectionRootGuard = ProtectionRootGuard(OWN, OWN, 7),
        readPackage: () -> String? = { OWN }, readWindow: () -> Int = { 7 }, progress: CollectionProgress = CollectionProgress(),
        walk: () -> Unit = {}) {
        guard.inspect(progress, readPackage, readWindow) { walk(); h.returned(ticket) }
    }
    private fun assertDiscarded(h: Owner) {
        assertEquals("DISCARDED", h.state); assertNull(h.completion)
        assertTrue(h.entries.isEmpty()); assertTrue((h.field(h.instance, "observedWindows") as Set<*>).isEmpty())
    }

    /** Only JVM tests bypass private access. No reflection/test factory exists in production. */
    private class Owner {
        private val type = Class.forName("kr.closeforyou.accessibility.AndroidSnapshotCollector\$ObservedProtectionPass")
        val instance: Any = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        private fun call(name: String, vararg args: Any?): Any? = type.declaredMethods.single {
            it.name == name && it.parameterCount == args.size
        }.apply { isAccessible = true }.invoke(instance, *args)
        fun observe(id: Int) { call("observeWindow", id) }
        fun register(id: Int, role: String = "TARGET"): Any? {
            val parameter = type.declaredMethods.single { it.name == "registerRoot" }.parameterTypes[1]
            val constants = requireNotNull(parameter.enumConstants) { "SYNTHETIC_ROLE_ENUM_REQUIRED" }
            val value = constants.single { (it as Enum<*>).name == role }
            return call("registerRoot", id, value)
        }
        fun freeze() { call("freezeScope") }
        fun returned(ticket: Any?) { call("traversalReturned", ticket) }
        fun finish() { call("finishPass") }
        fun discard() { call("discard") }
        val state: String get() = (field(instance, "state") as Enum<*>).name
        val completion: Any? get() = field(instance, "completion")
        val entries: List<*> get() = field(instance, "entries") as List<*>
        fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
    }
    private companion object { const val OWN = "synthetic.target" }
}
