package kr.closeforyou.ui.voice

/**
 * F61/F62: Unicode private-use code points — an icon font's glyph drawn as a character (LMS's close read as U+EA02 + "닫기").
 * They have no meaning to a person and TTS reads them as noise or "?", so text shown or spoken by C drops them:
 * button names ([kr.closeforyou.ui.voice.choice.ButtonNameDisplay.shown]) and popup text ([SpeakableText]).
 * Display and speech only: judging, re-checks, remembered popups and failed names keep the text as read.
 */
object PrivateUse {
    /** U+E000–U+F8FF (BMP), planes 15 and 16 (U+F0000–U+FFFFD, U+100000–U+10FFFD). */
    val RANGES: List<IntRange> = listOf(0xE000..0xF8FF, 0xF0000..0xFFFFD, 0x100000..0x10FFFD)

    fun isPrivateUse(cp: Int): Boolean = RANGES.any { cp in it }
}
