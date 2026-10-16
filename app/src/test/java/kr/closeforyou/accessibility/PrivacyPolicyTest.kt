package kr.closeforyou.accessibility

import kr.closeforyou.core.model.PrivacyBlockReason
import org.junit.Assert.*
import org.junit.Test

class PrivacyPolicyTest {
    private fun gate(
        target: String = "org.example.test",
        consent: Boolean = true,
        paused: Boolean = false,
        allowed: Set<String> = setOf("org.example.test"),
        keyboards: Set<String> = emptySet(),
    ) = PrivacyPolicy.packageBlock(target, "kr.closeforyou", consent, paused, allowed, keyboards)

    @Test fun `no tree analysis without explicit consent and app allowance`() {
        assertEquals(PrivacyBlockReason.NO_CONSENT, gate(consent = false))
        assertEquals(PrivacyBlockReason.APP_NOT_ALLOWED, gate(allowed = emptySet()))
        assertEquals(PrivacyBlockReason.PAUSED, gate(paused = true))
        assertNull(gate())
    }

    @Test fun `allowlist cannot override protected system keyboard or own app`() {
        assertEquals(PrivacyBlockReason.PROTECTED_APP,
            gate(target = "com.kakao.talk", allowed = setOf("com.kakao.talk")))
        assertEquals(PrivacyBlockReason.PROTECTED_APP,
            gate(target = "com.android.permissioncontroller", allowed = setOf("com.android.permissioncontroller")))
        assertEquals(PrivacyBlockReason.SYSTEM_WINDOW,
            gate(target = "kr.closeforyou", allowed = setOf("kr.closeforyou")))
        assertEquals(PrivacyBlockReason.SYSTEM_WINDOW,
            gate(keyboards = setOf("org.example.test")))
    }

    @Test fun `password variations block even when isPassword is missing`() {
        listOf(0x81, 0x91, 0xe1, 0x12).forEach { inputType ->
            assertEquals(PrivacyBlockReason.PASSWORD, PrivacyPolicy.fieldBlock(false, false, inputType))
        }
        assertEquals(PrivacyBlockReason.PASSWORD, PrivacyPolicy.fieldBlock(true, false, 0))
        assertEquals(PrivacyBlockReason.EDITABLE_FIELD, PrivacyPolicy.fieldBlock(false, true, 0))
        assertEquals(PrivacyBlockReason.EDITABLE_FIELD, PrivacyPolicy.fieldBlock(false, false, 1))
        assertNull(PrivacyPolicy.fieldBlock(false, false, 0))
    }

    @Test fun `sensitive text protection handles case and full width Unicode`() {
        assertTrue(PrivacyPolicy.hasSensitiveText("Enter PASSWORD"))
        assertTrue(PrivacyPolicy.hasSensitiveText("ＰＡＳＳＷＯＲＤ"))
        assertTrue(PrivacyPolicy.hasSensitiveText("테스트 비밀번호 입력"))
        assertTrue(PrivacyPolicy.hasSensitiveText("권한 허용을 선택하세요"))
        assertFalse(PrivacyPolicy.hasSensitiveText("시험용 일반 점검 공지"))
        assertFalse(PrivacyPolicy.hasSensitiveText(null))
    }
}
