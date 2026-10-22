package kr.closeforyou.ui.voice.command

import kr.closeforyou.core.contract.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KoreanVoiceCommandMatcherTest {
    private fun assertMatches(expected: VoiceCommand, vararg sentences: String) = sentences.forEach {
        assertEquals("'$it'", expected, KoreanVoiceCommandMatcher.match(it))
    }

    private fun assertIgnored(vararg sentences: String?) = sentences.forEach {
        assertNull("'$it'", KoreanVoiceCommandMatcher.match(it))
    }

    @Test fun close() = assertMatches(VoiceCommand.CLOSE, "닫아", "닫아줘", "닫아 주세요", "이거 닫아줘.", "닫기")

    @Test fun skip() = assertMatches(VoiceCommand.SKIP, "건너뛰어", "건너 뛰어 줘", "건너뛰기", "넘겨", "스킵")

    @Test fun readContent() = assertMatches(VoiceCommand.READ_CONTENT, "다시 읽어줘", "읽어줘", "내용 읽어 줘", "뭐라고 써 있어?")

    @Test fun keepOpen() = assertMatches(VoiceCommand.KEEP_OPEN, "그대로 둬", "그냥 둬", "놔둬", "닫지 마", "닫지 말고 그대로 둬")

    @Test fun goBack() = assertMatches(VoiceCommand.GO_BACK, "돌아가", "뒤로 가", "뒤로")

    @Test fun moveToButton() = assertMatches(VoiceCommand.MOVE_TO_BUTTON, "버튼으로 이동", "버튼으로 가 줘", "버튼 찾아줘")

    @Test fun describeImage() = assertMatches(VoiceCommand.DESCRIBE_IMAGE, "무슨 그림이야", "무슨 사진이야?", "그림 설명해 줘")

    @Test fun emptyOrNullIsIgnored() = assertIgnored(null, "", "   ", "?!")

    @Test fun unrelatedSpeechIsIgnored() = assertIgnored("안녕하세요", "오늘 날씨 어때", "광고", "확인")

    @Test fun negatedCommandsAreIgnored() = assertIgnored("건너뛰지 마", "읽지 마", "넘기지 마", "돌아가지 마")

    @Test fun twoCommandsInOneSentenceAreIgnored() = assertIgnored("닫아 아니 그대로 둬", "닫기 버튼으로 이동", "읽어줘 그리고 닫아")

    @Test fun longBackgroundSpeechIsIgnored() =
        assertIgnored("그 영화에서 주인공이 문을 닫아 버리고 떠났다고 하더라", "오늘 뉴스에서는 다시 읽어 볼 만한 기사가 많았습니다")

    @Test fun consentOrPaymentWordsAreNeverCommands() = assertIgnored("동의", "동의해줘", "결제해", "구독해 줘", "확인 눌러", "로그인")

    @Test fun honorificAndCasualVariants() {
        assertMatches(VoiceCommand.CLOSE, "닫으세요", "닫을래", "닫어", "닫아라")
        assertMatches(VoiceCommand.SKIP, "건너뛰세요", "건너뛸게요", "넘어가", "넘겨 주세요")
        assertMatches(VoiceCommand.READ_CONTENT, "읽어 주라", "읽어 줄래요?")
        assertMatches(VoiceCommand.KEEP_OPEN, "그냥 두세요", "그대로 두세요", "놔두세요", "냅둬", "내비 둬")
    }

    /** Sound-alike or everyday sentences that must not become commands. */
    @Test fun nearMissesAreIgnored() = assertIgnored(
        "단어", "다다음 주", "문 닫는 시간", "넘어졌어", "읽는 중", "건너편", "스키 타러 가",
        "닫으면 안 돼", "넘어가지 마", "냅다 뛰어",
    )

    /** Length limit counts the normalized text: 16 is still a command, 17 is not. */
    @Test fun lengthLimitBoundary() {
        val filler = "가나다라마바사아자차카타파하거너더러"
        val sixteen = filler.take(KoreanVoiceCommandMatcher.MAX_COMMAND_LENGTH - 2) + "닫아"
        val seventeen = filler.take(KoreanVoiceCommandMatcher.MAX_COMMAND_LENGTH - 1) + "닫아"
        assertEquals(VoiceCommand.CLOSE, KoreanVoiceCommandMatcher.match(sixteen))
        assertNull(KoreanVoiceCommandMatcher.match(seventeen))
        // Spaces and punctuation do not count toward the limit.
        assertEquals(VoiceCommand.CLOSE, KoreanVoiceCommandMatcher.match("  ${sixteen.chunked(2).joinToString(" ")}!  "))
    }

    @Test fun normalizeRemovesSpacesAndPunctuation() {
        assertEquals("닫아줘", KoreanVoiceCommandMatcher.normalize(" 닫아, 줘! "))
    }
}
