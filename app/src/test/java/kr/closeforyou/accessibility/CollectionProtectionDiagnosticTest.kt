package kr.closeforyou.accessibility

import kr.closeforyou.core.model.PrivacyBlockReason
import org.junit.Assert.*
import org.junit.Test

class CollectionProtectionDiagnosticTest {
    @Test fun `diagnostic wrapper preserves every tested field block decision`() {
        for (password in listOf(false, true)) for (editable in listOf(false, true))
            for (input in listOf(0, 1, 0x81, 0x91, 0xe1, 0x12, 0x20001)) {
                val expected = PrivacyPolicy.fieldBlock(password, editable, input)
                val failure = try { CollectionProtectionCheck.field(password, editable, input); null }
                    catch (blocked: CollectionGuardFailure) { blocked }
                assertEquals(expected, failure?.reason)
            }
    }
    @Test fun `non editable but nonzero input type remains blocked and identified`() {
        val failure = assertThrows(CollectionGuardFailure::class.java) { CollectionProtectionCheck.field(false, false, 1) }
        assertEquals(PrivacyBlockReason.EDITABLE_FIELD, failure.reason)
        assertEquals(CollectionField.INPUT_TYPE, failure.field)
        assertEquals(CollectionFinding.NON_ZERO_INPUT_TYPE, failure.finding)
    }
    @Test fun `password metadata variations and editable flag remain distinguished`() {
        val password = assertThrows(CollectionGuardFailure::class.java) { CollectionProtectionCheck.field(true, true, 1) }
        val variation = assertThrows(CollectionGuardFailure::class.java) { CollectionProtectionCheck.field(false, false, 0x81) }
        val editor = assertThrows(CollectionGuardFailure::class.java) { CollectionProtectionCheck.field(false, true, 0) }
        assertEquals(CollectionFinding.PASSWORD_FLAG, password.finding)
        assertEquals(CollectionFinding.PASSWORD_INPUT_TYPE, variation.finding)
        assertEquals(CollectionFinding.EDITABLE_FLAG, editor.finding)
    }
    @Test fun `sensitive uncertain and oversized text retain their existing protection and field source`() {
        for (source in listOf(CollectionField.TEXT, CollectionField.VIEW_ID, CollectionField.CLASS_NAME,
            CollectionField.HINT, CollectionField.STATE_DESCRIPTION)) {
            val sensitive = assertThrows(CollectionGuardFailure::class.java) { CollectionProtectionCheck.text("합성 비밀번호", source, 4_096) }
            val uncertain = assertThrows(CollectionGuardFailure::class.java) { CollectionProtectionCheck.text("합성 \u0627", source, 4_096) }
            val limit = assertThrows(CollectionGuardFailure::class.java) { CollectionProtectionCheck.text("a".repeat(4_097), source, 4_096) }
            assertEquals(CollectionFinding.SENSITIVE_TEXT, sensitive.finding)
            assertEquals(CollectionFinding.UNCERTAIN_TEXT, uncertain.finding)
            assertEquals(CollectionFinding.NODE_TEXT_LIMIT, limit.finding)
            assertEquals(PrivacyBlockReason.TRUNCATED_TREE, limit.reason)
            assertEquals(source, sensitive.field)
            assertNull(sensitive.message)
        }
    }
    @Test fun `known baseline literals alone do not reproduce the device block`() {
        val literals = listOf("합성 문서 창", "합성 일반 문서", "실험 실행", "android.widget.FrameLayout",
            "android.widget.LinearLayout", "android.widget.Button", "android.widget.TextView", "android.widget.ScrollView") +
            (1..35).map { "합성 문단 $it : 고정 안내 문서" }
        literals.forEach { CollectionProtectionCheck.text(it, CollectionField.TEXT, 4_096) }
        // Framework input flags, generated metadata, windows and geometry are NOT established here.
    }
}
