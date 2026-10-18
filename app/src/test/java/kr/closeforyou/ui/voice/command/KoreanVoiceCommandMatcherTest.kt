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

    @Test fun normalizeRemovesSpacesAndPunctuation() {
        assertEquals("닫아줘", KoreanVoiceCommandMatcher.normalize(" 닫아, 줘! "))
    }
}
