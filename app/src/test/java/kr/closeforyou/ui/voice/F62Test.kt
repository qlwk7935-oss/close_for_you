package kr.closeforyou.ui.voice

import kr.closeforyou.ui.voice.choice.ButtonNameDisplay
import kr.closeforyou.ui.voice.overlay.TalkBackTakeoverPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F62: private-use icon glyphs are dropped from the popup text C shows and speaks (the prompt's excerpt, its reading). */
class F62Test {
    // 준영 (LMS-like overlap, 84a935f): the excerpt ended "…깁니다. ?닫기"; the "?" was U+EA02.
    @Test fun theExcerptLosesTheIconGlyph() {
        assertEquals("공지 내용이 깁니다. 닫기", SpeakableText.of("공지 내용이 깁니다. \uEA02닫기"))
        assertEquals("공지 내용이 깁니다. 닫기", TalkBackTakeoverPlan.excerpt("공지 내용이 깁니다. \uEA02닫기", 60))
        // Stuck to the word before: the glyph turns into a space, as other decorations do.
        assertEquals("깁니다. 닫기", SpeakableText.of("깁니다.\uEA02닫기"))
        assertEquals("깁니다. 닫기", SpeakableText.inline("깁니다. \uEA02 닫기 \uF8FF"))
    }

    @Test fun onlyGlyphsLeaveNothing() {
        assertEquals("", SpeakableText.of("\uEA02"))
        assertEquals("", SpeakableText.of(" \uE000 \uF8FF "))
        assertEquals("", SpeakableText.inline("\uEA02\uE5CD"))
        assertNull(TalkBackTakeoverPlan.excerpt("\uEA02", 60))
        // A line of glyphs alone is dropped with its pause.
        assertEquals("공지", SpeakableText.of("공지\n\uEA02"))
    }

    @Test fun planesFifteenAndSixteen() {
        assertEquals("닫기", SpeakableText.of("\uDB80\uDC00닫기\uDBFF\uDFFD"))
        assertEquals("닫기", SpeakableText.of("\uDB80\uDC01 닫기"))
    }

    @Test fun ordinaryTextAndOldRulesStay() {
        assertEquals("12,900원 할인", SpeakableText.of("12,900원 할인"))
        assertEquals("서버 점검 오전 2시부터 오전 4시까지", SpeakableText.of("서버 점검 02:00~04:00"))
        assertEquals("특가 세일", SpeakableText.of("★특가★ 세일"))
        assertEquals("70퍼센트 할인 닫기", SpeakableText.of("70% 할인 \uEA02닫기"))
        // Neighbours of the ranges stay: U+F900 (a CJK compatibility ideograph), U+E0000 (plane 14, not private use).
        assertEquals("\uF900닫기", SpeakableText.of("\uF900닫기"))
        assertEquals("\uDB40\uDC00 닫기", SpeakableText.of("\uDB40\uDC00 닫기"))
    }

    // One range definition: the button name and the popup text drop the same characters.
    @Test fun theButtonNameAndTheTextShareTheRange() {
        for (cp in listOf(0xDFFF, 0xE000, 0xEA02, 0xF8FF, 0xF900, 0xEFFFF, 0xF0000, 0xFFFFD, 0xFFFFE, 0x100000, 0x10FFFD, 0x10FFFF)) {
            assertEquals(PrivateUse.isPrivateUse(cp), ButtonNameDisplay.isPrivateUse(cp))
        }
        assertTrue(PrivateUse.isPrivateUse(0xEA02))
        assertFalse(PrivateUse.isPrivateUse(0xF900))
        assertFalse(PrivateUse.isPrivateUse('닫'.code))
        assertEquals(ButtonNameDisplay.shown("\uEA02닫기"), SpeakableText.of("\uEA02닫기"))
    }
}
