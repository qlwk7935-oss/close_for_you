package kr.closeforyou.ui.voice.command

import kr.closeforyou.core.contract.VoiceCommand

/**
 * F13: turns one recognized Korean sentence into a [VoiceCommand], or null when unsure.
 *
 * Rule based on purpose: video sound, nearby speech or TalkBack itself can reach the microphone,
 * so long sentences, sentences matching two commands, and anything else unclear are ignored.
 * The recognized sentence is never logged or stored.
 */
object KoreanVoiceCommandMatcher {
    /** Longer utterances are more likely background speech than a command (normalized length). */
    const val MAX_COMMAND_LENGTH = 16

    // Phrases are compared after removing spaces and punctuation. Variants are listed whole words
    // (honorific "-세요", casual "-어/-라", 10/19) rather than bare stems like "닫", so ordinary
    // sentences ("문 닫는 시간") stay unmatched.
    private val phrases: Map<VoiceCommand, List<String>> = mapOf(
        VoiceCommand.CLOSE to listOf("닫아", "닫기", "닫자", "닫어", "닫으세요", "닫을래"),
        VoiceCommand.SKIP to listOf("건너뛰어", "건너뛰기", "건너뛸래", "건너뛰세요", "건너뛸게", "넘겨", "넘어가", "스킵"),
        VoiceCommand.READ_CONTENT to listOf(
            "다시읽어", "읽어줘", "읽어봐", "읽어주세요", "읽어주라", "읽어줄래", "내용읽", "뭐라고써",
        ),
        VoiceCommand.KEEP_OPEN to listOf(
            "그대로둬", "그대로놔", "그대로두세요", "그냥둬", "그냥두세요", "그냥놔둬", "놔둬", "놔두세요", "냅둬", "내비둬",
            "닫지마", "닫지말",
        ),
        VoiceCommand.GO_BACK to listOf("돌아가", "뒤로가", "뒤로"),
        VoiceCommand.MOVE_TO_BUTTON to listOf("버튼으로이동", "버튼으로가", "버튼찾아", "버튼위치"),
        VoiceCommand.DESCRIBE_IMAGE to listOf("무슨그림", "그림설명", "무슨사진", "사진설명", "이미지설명", "무슨이미지"),
    )

    /** Negated or prohibitive endings ("건너뛰지 마", "읽지 말고") never trigger an action. */
    private val negations = listOf("지마", "지말", "안돼", "하지마")

    fun match(recognized: String?): VoiceCommand? {
        val text = normalize(recognized ?: return null)
        if (text.isEmpty() || text.length > MAX_COMMAND_LENGTH) return null
        val matched = phrases.filterValues { list -> list.any { it in text } }.keys
        if (matched.size != 1) return null
        val command = matched.single()
        // KEEP_OPEN phrases are themselves negations ("닫지 마"); any other command plus a negation is unclear.
        if (command != VoiceCommand.KEEP_OPEN && negations.any { it in text }) return null
        return command
    }

    internal fun normalize(text: String): String =
        text.filterNot { it.isWhitespace() || it in PUNCTUATION }

    private const val PUNCTUATION = ".,!?~…·'\"()[]"
}
