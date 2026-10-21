package kr.closeforyou.accessibility

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.PrintStream
import java.lang.reflect.Modifier
import java.util.Properties
import java.util.concurrent.CancellationException
import kotlinx.collections.immutable.persistentSetOf
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Uses the same read/DTO/outline functions as SNAPSHOT_PASS. Fake getters are not Android proof. */
class CheckedNodeReadTest {
    private val textFields = listOf(CollectionField.TEXT, CollectionField.CONTENT_DESCRIPTION, CollectionField.VIEW_ID,
        CollectionField.CLASS_NAME, CollectionField.ERROR, CollectionField.HINT, CollectionField.PANE_TITLE,
        CollectionField.TOOLTIP, CollectionField.STATE_DESCRIPTION)
    private class Source : CheckedNodeRead.Source {
        var pkg: String? = "synthetic.app"
        var passwordFlag = false
        var editableFlag = false
        var input = 0
        val calls = mutableListOf<CollectionField>()
        val counts = mutableMapOf<CollectionField, Int>()
        val values = mutableMapOf<CollectionField, CharSequence?>()
        var read: ((CollectionField, Int) -> CharSequence?)? = null
        override fun packageName(): String? { calls += CollectionField.NODE_PACKAGE; return pkg }
        override fun password(): Boolean { calls += CollectionField.PASSWORD_FLAG; return passwordFlag }
        override fun editable(): Boolean { calls += CollectionField.EDITABLE_FLAG; return editableFlag }
        override fun inputType(): Int { calls += CollectionField.INPUT_TYPE; return input }
        override fun value(field: CollectionField): CharSequence? {
            calls += field
            val count = (counts[field] ?: 0) + 1
            counts[field] = count
            val callback = read
            return if (callback == null) values[field] else callback(field, count)
        }
    }
    private fun source() = Source().apply {
        values[CollectionField.TEXT] = "합성 공지"
        values[CollectionField.CONTENT_DESCRIPTION] = "합성 설명"
        values[CollectionField.VIEW_ID] = "synthetic:id/title"
        values[CollectionField.CLASS_NAME] = "android.widget.TextView"
        values[CollectionField.PANE_TITLE] = "합성 영역"
    }
    private fun checked(source: Source = source(), progress: CollectionProgress = CollectionProgress(), root: Boolean = true,
        limit: Int = 4_096) = CheckedNodeRead.read(source, "synthetic.app", root, progress, limit)
    private val bounds = ScreenBounds(1, 2, 100, 120)
    private val actions = persistentSetOf(NodeAction.CLICK, NodeAction.DISMISS)
    private fun snapshot(read: CheckedNodeRead) = read.snapshot("0/0", true, false, bounds, actions)
    private fun outline(read: CheckedNodeRead) = read.outline("synthetic-outline", bounds, 2, actions, "0/0", true, false)

    @Test fun sameOrderedProtectionFieldsReadExactlyOnceBeforeReturningValues() {
        val s = source(); checked(s)
        assertEquals(listOf(CollectionField.NODE_PACKAGE, CollectionField.PASSWORD_FLAG, CollectionField.EDITABLE_FLAG,
            CollectionField.INPUT_TYPE) + textFields, s.calls)
        assertTrue(s.counts.values.all { it == 1 })
    }
    @Test fun secondGetterWouldBeSensitiveButDtoOutlineAndHashNeverCallIt() {
        val s = source()
        val original = s.values.toMap()
        s.read = { field, count -> if (count > 1) "합성 비밀번호" else original[field] }
        val read = checked(s); val node = snapshot(read); val shape = outline(read)
        val codec = SnapshotFingerprintCodec(ByteArray(32) { 7 })
        codec.structure(listOf(SnapshotFingerprintNode(node, 2)))
        codec.content(listOf(SnapshotFingerprintNode(node, 2))); codec.outline(node)
        assertEquals("합성 공지", node.text); assertEquals("합성 설명", node.contentDescription)
        assertEquals("synthetic:id/title", node.viewId); assertEquals("android.widget.TextView", node.className)
        assertTrue(shape.pane); assertTrue(s.counts.values.all { it == 1 })
    }
    @Test fun earlierMutableTextCannotChangeWhenLaterProtectionFieldsAreRead() {
        val s = source(); val value = StringBuilder("합성 공지")
        s.values[CollectionField.TEXT] = value
        s.read = { field, _ ->
            if (field == CollectionField.STATE_DESCRIPTION) value.replace(0, value.length, "합성 비밀번호")
            s.values[field]
        }
        val read = checked(s)
        assertEquals("합성 공지", snapshot(read).text)
        assertEquals("합성 비밀번호", value.toString())
    }
    @Test fun allFiveRetainedFieldsAreImmutableAfterMutableSourcesChange() {
        val s = source()
        val retained = listOf(CollectionField.TEXT, CollectionField.CONTENT_DESCRIPTION, CollectionField.VIEW_ID,
            CollectionField.CLASS_NAME, CollectionField.PANE_TITLE)
        val originals = s.values.toMap()
        retained.forEach { s.values[it] = StringBuilder(originals.getValue(it).toString()) }
        val read = checked(s)
        retained.forEach { (s.values.getValue(it) as StringBuilder).replace(0, s.values.getValue(it)!!.length, "합성 비밀번호") }
        assertEquals(originals[CollectionField.TEXT], read.text)
        assertEquals(originals[CollectionField.CONTENT_DESCRIPTION], read.contentDescription)
        assertEquals(originals[CollectionField.VIEW_ID], read.viewId)
        assertEquals(originals[CollectionField.CLASS_NAME], read.className)
        assertEquals(originals[CollectionField.PANE_TITLE], read.paneTitle)
    }
    @Test fun paneUsesCheckedValueEvenIfSourceIsLaterEmpty() {
        val s = source(); val pane = StringBuilder("합성 영역")
        s.values[CollectionField.PANE_TITLE] = pane
        val read = checked(s); pane.clear()
        assertTrue(outline(read).pane)
    }
    @Test fun nullAndEmptyFieldsAreNotNormalizedOrInterchanged() {
        val s = Source().apply { values[CollectionField.CONTENT_DESCRIPTION] = ""; values[CollectionField.CLASS_NAME] = "" }
        val read = checked(s)
        assertNull(read.text); assertEquals("", read.contentDescription); assertNull(read.viewId)
        assertEquals("", read.className); assertNull(read.paneTitle); assertFalse(outline(read).pane)
    }
    @Test fun unicodeCodeUnitsSurrogatesAndWhitespaceArePreserved() {
        val value = "  합성 공지🙂\n한글\t"
        val s = source().apply { values[CollectionField.TEXT] = StringBuilder(value) }
        assertEquals(value, checked(s).text)
    }
    @Test fun mutableCopyNeverUsesSourceToStringOrSubSequence() {
        val value = object : CharSequence {
            override val length = 4
            override fun get(index: Int) = "합성문구"[index]
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw AssertionError("NO_SUBSEQUENCE")
            override fun toString(): String = throw AssertionError("NO_SOURCE_TOSTRING")
        }
        assertEquals("합성문구", checked(source().apply { values[CollectionField.TEXT] = value }).text)
    }
    @Test fun immutableCopyIsInspectedInsteadOfMisleadingSourceToString() {
        val value = object : CharSequence {
            override val length = 4
            override fun get(index: Int) = "비밀번호"[index]
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw AssertionError("NO_SUBSEQUENCE")
            override fun toString() = "합성문구"
        }
        val failure = assertThrows(CollectionGuardFailure::class.java) {
            checked(source().apply { values[CollectionField.TEXT] = value })
        }
        assertEquals(CollectionFinding.SENSITIVE_TEXT, failure.finding)
    }
    @Test fun sameLengthMutationCreatingSensitiveCopyIsBlocked() {
        var data = "합성문구"
        val value = object : CharSequence {
            override val length get() = data.length
            override fun get(index: Int): Char { data = "비밀번호"; return data[index] }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw AssertionError("NO_SUBSEQUENCE")
        }
        assertEquals(PrivacyBlockReason.SENSITIVE_CONTENT, assertThrows(CollectionGuardFailure::class.java) {
            checked(source().apply { values[CollectionField.TEXT] = value })
        }.reason)
    }
    @Test fun allNineTextFieldsKeepSensitiveUncertainAndLengthProtection() {
        for (field in textFields) for ((value, expected) in listOf(
            "합성 비밀번호" to CollectionFinding.SENSITIVE_TEXT,
            "합성 \u0627" to CollectionFinding.UNCERTAIN_TEXT,
            "가".repeat(4_097) to CollectionFinding.NODE_TEXT_LIMIT)) {
            val failure = assertThrows(CollectionGuardFailure::class.java) {
                checked(source().apply { values[field] = StringBuilder(value) })
            }
            assertEquals(field, failure.field); assertEquals(expected, failure.finding)
        }
    }
    @Test fun lengthLimitIsCheckedBeforeAllocatingOrReadingCharacters() {
        var charsRead = 0
        val value = object : CharSequence {
            override val length = Int.MAX_VALUE
            override fun get(index: Int): Char { charsRead++; throw AssertionError("NO_CHAR_READ") }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw AssertionError("NO_SUBSEQUENCE")
        }
        assertEquals(CollectionFinding.NODE_TEXT_LIMIT, assertThrows(CollectionGuardFailure::class.java) {
            checked(source().apply { values[CollectionField.TEXT] = value })
        }.finding)
        assertEquals(0, charsRead)
    }
    @Test fun exactFieldLimitRemainsAcceptedForStringAndMutableInput() {
        val value = "가".repeat(4_096)
        for (text in listOf<CharSequence>(value, StringBuilder(value)))
            assertEquals(value, checked(source().apply { values[CollectionField.TEXT] = text }).text)
    }
    @Test fun growthDuringCopyOverLimitRejectsWithoutReadingUnboundedSuffix() {
        var reads = 0; var size = 4
        val value = object : CharSequence {
            override val length get() = size
            override fun get(index: Int): Char { reads++; size = 4_097; return '가' }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw AssertionError("NO_SUBSEQUENCE")
        }
        assertEquals(CollectionFinding.NODE_TEXT_LIMIT, assertThrows(CollectionGuardFailure::class.java) {
            checked(source().apply { values[CollectionField.TEXT] = value })
        }.finding)
        assertEquals(4, reads)
    }
    @Test fun observedLengthChangeWithinLimitRejectsInsteadOfAcceptingPrefix() {
        var size = 4
        val value = object : CharSequence {
            override val length get() = size
            override fun get(index: Int): Char { size = 5; return '가' }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw AssertionError("NO_SUBSEQUENCE")
        }
        val p = CollectionProgress()
        val failure = assertThrows(IllegalStateException::class.java) { checked(source().apply { values[CollectionField.TEXT] = value }, p) }
        assertEquals("CHECKED_NODE_LENGTH_CHANGED", failure.message)
        assertEquals(CollectionOperation.NODE_TEXT_CHECK, p.failure?.operation)
        assertEquals(CollectionField.TEXT, p.failure?.field)
    }
    @Test fun shrinkingDuringIndexReadDoesNotReturnPartialData() {
        val value = object : CharSequence {
            override val length = 4
            override fun get(index: Int): Char = throw IndexOutOfBoundsException("SYNTHETIC_PRIVATE")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw AssertionError("NO_SUBSEQUENCE")
        }
        val p = CollectionProgress()
        assertThrows(IndexOutOfBoundsException::class.java) { checked(source().apply { values[CollectionField.TEXT] = value }, p) }
        assertEquals(CollectionExceptionKind.INDEX_OUT_OF_BOUNDS, p.failure?.exception)
    }
    @Test fun invalidNegativeLengthFailsBeforeAllocation() {
        val value = object : CharSequence {
            override val length = -1
            override fun get(index: Int): Char = throw AssertionError("NO_CHAR_READ")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw AssertionError("NO_SUBSEQUENCE")
        }
        assertEquals("CHECKED_NODE_LENGTH_INVALID", assertThrows(IllegalStateException::class.java) {
            checked(source().apply { values[CollectionField.TEXT] = value })
        }.message)
    }
    @Test fun packageGuardPrecedesEveryFieldRead() {
        for (pkg in listOf(null, "synthetic.other")) {
            val s = source().apply { this.pkg = pkg }
            assertEquals(CollectionFinding.PACKAGE_MISMATCH, assertThrows(CollectionGuardFailure::class.java) { checked(s) }.finding)
            assertEquals(listOf(CollectionField.NODE_PACKAGE), s.calls)
        }
    }
    @Test fun descendantNullPackageKeepsExistingPolicyButForeignPackageBlocks() {
        checked(source().apply { pkg = null }, root = false)
        assertEquals(CollectionFinding.PACKAGE_MISMATCH, assertThrows(CollectionGuardFailure::class.java) {
            checked(source().apply { pkg = "synthetic.other" }, root = false)
        }.finding)
    }
    @Test fun allInputFlagCombinationsKeepExistingPolicyAndGetterOrder() {
        for (password in listOf(false, true)) for (editable in listOf(false, true)) for (input in listOf(0, 1, 0x81, 0x12)) {
            val s = source().apply { passwordFlag = password; editableFlag = editable; this.input = input }
            val reason = PrivacyPolicy.fieldBlock(password, editable, input)
            if (reason == null) checked(s) else {
                assertEquals(reason, assertThrows(CollectionGuardFailure::class.java) { checked(s) }.reason)
                assertEquals(listOf(CollectionField.NODE_PACKAGE, CollectionField.PASSWORD_FLAG,
                    CollectionField.EDITABLE_FLAG, CollectionField.INPUT_TYPE), s.calls)
            }
        }
    }
    @Test fun getterExceptionIsRecordedAtReadBoundaryWithoutFurtherReads() {
        val p = CollectionProgress(); val s = source()
        val original = SecurityException("SYNTHETIC_PRIVATE")
        s.read = { field, _ -> if (field == CollectionField.CLASS_NAME) throw original else s.values[field] }
        assertSame(original, assertThrows(SecurityException::class.java) { checked(s, p) })
        assertEquals(CollectionOperation.NODE_TEXT_READ, p.failure?.operation)
        assertEquals(CollectionField.CLASS_NAME, p.failure?.field)
        assertEquals(CollectionExceptionKind.SECURITY, p.failure?.exception)
        assertFalse(s.calls.contains(CollectionField.ERROR))
    }
    @Test fun inputFlagGetterFailureRetainsBoundaryAndStopsBeforeTextOrLaterFlags() {
        val delegate = source(); val p = CollectionProgress()
        val original = SecurityException("SYNTHETIC_PRIVATE")
        val s = object : CheckedNodeRead.Source by delegate {
            override fun editable(): Boolean = throw original
        }
        assertSame(original, assertThrows(SecurityException::class.java) {
            CheckedNodeRead.read(s, "synthetic.app", true, p, 4_096)
        })
        assertEquals(CollectionField.EDITABLE_FLAG, p.failure?.field)
        assertEquals(CollectionOperation.NODE_FIELD_READ, p.failure?.operation)
        assertFalse(delegate.calls.contains(CollectionField.INPUT_TYPE))
        assertTrue(delegate.counts.isEmpty())
    }
    @Test fun laterFailureCannotReplaceFirstRecordedBoundary() {
        val p = CollectionProgress(); val s = source()
        s.read = { _, _ -> throw IllegalArgumentException("SYNTHETIC_PRIVATE") }
        assertThrows(IllegalArgumentException::class.java) { checked(s, p) }
        val first = p.failure
        assertThrows(CollectionGuardFailure::class.java) { checked(source().apply { pkg = null }, p) }
        assertSame(first, p.failure)
    }
    @Test fun cancellationFromGetterStillPropagatesToCollectorBoundary() {
        val p = CollectionProgress(); val s = source(); val original = CancellationException("SYNTHETIC_PRIVATE")
        s.read = { _, _ -> throw original }
        assertSame(original, assertThrows(CancellationException::class.java) { checked(s, p) })
        // Diagnostic classification is unchanged; capture now rethrows cancellation. Not Job-cancellation proof.
        assertEquals(CollectionExceptionKind.ILLEGAL_STATE, p.failure?.exception)
    }
    @Test fun cancellationDuringCopyNeverReturnsCheckedFields() {
        val original = CancellationException("SYNTHETIC_PRIVATE")
        val value = object : CharSequence {
            override val length = 4
            override fun get(index: Int): Char = throw original
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw AssertionError("NO_SUBSEQUENCE")
        }
        val p = CollectionProgress()
        assertSame(original, assertThrows(CancellationException::class.java) { checked(source().apply { values[CollectionField.TEXT] = value }, p) })
        assertEquals(CollectionOperation.NODE_TEXT_CHECK, p.failure?.operation)
    }
    @Test fun malformedBoundsStillStopBeforeNodeAndOutlineConstruction() {
        for (rect in listOf(intArrayOf(2, 0, 1, 1), intArrayOf(0, 2, 1, 1), intArrayOf(2, 2, 1, 1))) {
            val read = checked(); var dtoCreated = false; val p = CollectionProgress()
            val failure = assertThrows(CollectionGuardFailure::class.java) {
                val b = p.at(CollectionOperation.NODE_BOUNDS_VALIDATE) { NodeBoundsPolicy.convert(rect[0], rect[1], rect[2], rect[3]) {
                    NodeBoundsDiagnostic(it, NodeVisibility.NOT_VISIBLE, NodeAncestry.SCROLL_ANCESTOR, NodeRole.DESCENDANT)
                } }
                read.snapshot("0/0", true, true, b, actions); dtoCreated = true
            }
            assertFalse(dtoCreated); assertEquals(CollectionFinding.MALFORMED_NODE_BOUNDS, failure.finding)
            assertEquals(CollectionOperation.NODE_BOUNDS_VALIDATE, p.failure?.operation)
        }
    }
    @Test fun plainDtoAndOutlineFieldsRetainCallerTopologyActionsAndGeometry() {
        val read = checked(); val node = snapshot(read); val shape = outline(read)
        assertEquals(NodeSnapshot("0/0", "synthetic:id/title", "android.widget.TextView", "합성 공지", "합성 설명", true, false, bounds, actions), node)
        assertEquals(DetectionOutline("synthetic-outline", bounds, 2, true, "0/0", true, true, false), shape)
    }
    @Test fun nullPaneAndSemanticClassOrDismissKeepOriginalOutlineMeaning() {
        val read = checked(Source().apply { values[CollectionField.CLASS_NAME] = "SyntheticDialog" })
        val semantic = read.outline("synthetic", bounds, 0, persistentSetOf(), "0", false, true)
        assertTrue(semantic.semanticOverlay); assertFalse(semantic.pane); assertFalse(semantic.visible); assertTrue(semantic.scrollable)
    }
    @Test fun onlyImmutableStringsRemainAndWrapperIsRedacted() {
        val read = checked()
        assertEquals("CheckedNodeRead(redacted)", read.toString())
        assertTrue(CheckedNodeRead::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.all { it.type == String::class.java })
    }
    @Test fun successfulAndBlockedReadsDoNotWriteSourceValuesOrExceptions() {
        val oldOut = System.out; val oldErr = System.err; val bytes = ByteArrayOutputStream(); val output = PrintStream(bytes)
        try {
            System.setOut(output); System.setErr(output)
            checked(); assertThrows(CollectionGuardFailure::class.java) { checked(source().apply { values[CollectionField.ERROR] = "합성 비밀번호" }) }
            val p = CollectionProgress()
            assertThrows(IllegalStateException::class.java) { checked(source().apply {
                read = { _, _ -> throw IllegalStateException("SYNTHETIC_PRIVATE") }
            }, p) }
            assertFalse(p.failure.toString().contains("SYNTHETIC_PRIVATE"))
            assertEquals("", bytes.toString())
        } finally { System.setOut(oldOut); System.setErr(oldErr); output.close() }
    }

    private val goldens = Properties().apply {
        checkNotNull(CheckedNodeReadTest::class.java.getResourceAsStream("/snapshot_fingerprint_v1_golden.properties")).use(::load)
    }
    private fun decode(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private val codec = SnapshotFingerprintCodec(decode(goldens.getProperty("salt")))
    private fun goldenTree(): List<SnapshotFingerprintNode> {
        val originals = listOf(
            SnapshotFingerprintNode(NodeSnapshot("0", null, "android.widget.FrameLayout", null, "", false, true, ScreenBounds(0, 0, 1080, 1920)), 2),
            SnapshotFingerprintNode(NodeSnapshot("0/0", "synthetic:id/공지", "android.widget.TextView", "합성 점검 공지🙂", null, false, true, ScreenBounds(24, 60, 1056, 600)), 0),
            SnapshotFingerprintNode(NodeSnapshot("0/1", "", null, "닫기", "합성 설명\n확인", true, false, ScreenBounds(-7, 640, 1001, 880), actions), 0))
        return originals.map { record ->
            val original = record.snapshot
            val s = Source().apply {
                values[CollectionField.TEXT] = original.text?.let(::StringBuilder)
                values[CollectionField.CONTENT_DESCRIPTION] = original.contentDescription?.let(::StringBuilder)
                values[CollectionField.VIEW_ID] = original.viewId?.let(::StringBuilder)
                values[CollectionField.CLASS_NAME] = original.className?.let(::StringBuilder)
            }
            val node = checked(s).snapshot(original.path, original.clickable, original.enabled, original.bounds, original.actions)
            assertEquals(original, node)
            SnapshotFingerprintNode(node, record.childCount)
        }
    }
    @Test fun checkedMutableFieldsMatchIndependentStructureGoldenBytes() {
        assertArrayEquals(decode(goldens.getProperty("structure.tree.sha256")), codec.structure(goldenTree()).bytes())
    }
    @Test fun checkedMutableFieldsMatchIndependentContentGoldenBytes() {
        assertArrayEquals(decode(goldens.getProperty("content.tree.sha256")), codec.content(goldenTree()).bytes())
    }
    @Test fun checkedMutableFieldsMatchEachIndependentOutlineGolden() {
        goldenTree().forEachIndexed { i, node ->
            assertArrayEquals(decode(goldens.getProperty("outline.node$i.sha256")), codec.outline(node.snapshot).bytes())
        }
    }
    @Test fun unchangedCollectorStructuralFieldListMatchesGoldenEncodingDirectly() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            for (record in goldenTree()) {
                val n = record.snapshot
                val fields = listOf(n.path, n.viewId, n.className, n.bounds.left, n.bounds.top, n.bounds.right, n.bounds.bottom,
                    n.clickable, n.enabled, record.childCount, n.actions.joinToString(","))
                for (field in fields) {
                    val utf8 = field?.toString()?.toByteArray(Charsets.UTF_8)
                    output.writeInt(utf8?.size ?: -1); if (utf8 != null) output.write(utf8)
                }
            }
        }
        assertArrayEquals(decode(goldens.getProperty("structure.tree.stream")), bytes.toByteArray())
    }
}
