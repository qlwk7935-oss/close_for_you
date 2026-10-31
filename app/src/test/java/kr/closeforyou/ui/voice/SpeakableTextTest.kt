package kr.closeforyou.ui.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakableTextTest {
    private fun say(raw: String) = SpeakableText.of(raw)

    // ---- times ----

    @Test fun demoNoticeTimeRangeIsReadAsMorningHours() =
        assertEquals("서버 점검 오전 2시부터 오전 4시까지", say("서버 점검 02:00~04:00"))

    @Test fun timeRangeAcrossNoonUses24HourClock() =
        assertEquals("오전 10시부터 오후 6시 30분까지", say("10:00~18:30"))

    @Test fun ambiguousTimeRangeHasNoMeridiem() = assertEquals("2시부터 4시까지", say("2:00~4:00"))

    @Test fun dashBetweenTimesIsARange() = assertEquals("오전 9시부터 오후 6시까지", say("09:00-18:00"))

    @Test fun noonIsSaidAsNoon() = assertEquals("오전 9시부터 낮 12시 30분까지", say("09:00~12:30"))

    @Test fun midnightIsZeroHour() = assertEquals("오전 0시부터 오전 6시까지", say("00:00~06:00"))

    @Test fun twoDigitHourAloneIsAClockTime() = assertEquals("오후 2시 30분 시작", say("14:30 시작"))

    @Test fun leadingZeroHourAloneIsMorning() = assertEquals("오전 2시 점검", say("02:00 점검"))

    @Test fun writtenMeridiemIsKept() = assertEquals("오후 2시 30분 마감", say("오후 2:30 마감"))

    @Test fun englishMeridiemBecomesKorean() = assertEquals("오후 7시 오픈", say("PM 7:00 오픈"))

    @Test fun oneDigitHourWithMarkerIsATime() = assertEquals("3시부터 시작", say("3:00부터 시작"))

    @Test fun oneDigitHourAloneMayBeAVideoPosition() = assertEquals("2:15", say("2:15"))

    @Test fun videoPositionAndLengthStay() = assertEquals("0:15 / 1:30", say("0:15 / 1:30"))

    @Test fun hoursMinutesSecondsStay() = assertEquals("1:05:30", say("1:05:30"))

    @Test fun videoTalkBackTextStays() = assertEquals("0분 15초 중 0분 15초", say("0분 15초 중 0분 15초"))

    @Test fun invalidTimeStays() = assertEquals("25:00", say("25:00"))

    @Test fun existingUntilIsNotDoubled() = assertEquals("오전 2시부터 오전 4시까지", say("02:00~04:00까지"))

    // ---- dates ----

    @Test fun dateWithWeekday() = assertEquals("10월 28일 수요일", say("10/28(수)"))

    @Test fun dateRangeWithWeekdays() =
        assertEquals("기간, 10월 28일 수요일부터 11월 3일 화요일까지", say("기간: 10/28(수)~11/3(화)"))

    @Test fun dottedDateRange() = assertEquals("10월 28일부터 11월 3일까지", say("10.28~11.03"))

    @Test fun fullDate() = assertEquals("2026년 10월 28일", say("2026.10.28"))

    @Test fun isoDate() = assertEquals("2026년 10월 28일", say("2026-10-28"))

    @Test fun slashDateWithTwoDigits() = assertEquals("10월 28일 마감", say("10/28 마감"))

    @Test fun smallFractionIsNotADate() = assertEquals("1/2 가격", say("1/2 가격"))

    @Test fun decimalIsNotADate() = assertEquals("평점 4.5점", say("평점 4.5점"))

    @Test fun decimalRangeIsANumberRange() = assertEquals("1.5에서 2.5배", say("1.5~2.5배"))

    @Test fun dateAndTime() = assertEquals("10월 28일 오후 2시", say("10/28 14:00"))

    @Test fun dateTimeRangeWithTimeOnlyEnd() =
        assertEquals("10월 28일 10시부터 12시까지", say("10/28 10:00~12:00"))

    @Test fun noonWithoutEvidenceIsPlain() = assertEquals("11시부터 12시 30분까지", say("11:00~12:30"))

    @Test fun openEndedUntil() = assertEquals("10월 31일까지 할인", say("~10/31 할인"))

    @Test fun openEndedUntilKeepsWrittenUntil() = assertEquals("10월 31일까지 할인", say("~10/31까지 할인"))

    @Test fun openEndedFrom() = assertEquals("11월 1일 토요일부터 시작", say("11/1(토)~ 시작"))

    @Test fun invalidDateStays() = assertEquals("13/45", say("13/45"))

    @Test fun weekdayRange() = assertEquals("평일(월요일부터 금요일까지)", say("평일(월~금)"))

    // ---- numbers and marks ----

    @Test fun numberRange() = assertEquals("1에서 3일", say("1~3일"))

    @Test fun percent() = assertEquals("최대 70퍼센트 할인", say("최대 70% 할인"))

    @Test fun percentRange() = assertEquals("30에서 50퍼센트", say("30~50%"))

    @Test fun pricesAreLeftToTheEngine() = assertEquals("12,900원 4천원", say("12,900원 4천원"))

    @Test fun onePlusOne() = assertEquals("원 플러스 원 행사", say("1+1 행사"))

    @Test fun twoPlusOne() = assertEquals("투 플러스 원", say("2+1"))

    @Test fun bigPlus() = assertEquals("10퍼센트 플러스 5퍼센트", say("10%+5%"))

    @Test fun times() = assertEquals("2 곱하기 3", say("2×3"))

    @Test fun multiple() = assertEquals("포인트 2배 적립", say("포인트 2x 적립"))

    @Test fun resolutionIsNotAMultiple() = assertEquals("1080x1920", say("1080x1920"))

    @Test fun ampersandBetweenWords() =
        assertEquals("올리브베러 특가 그리고 4천원 할인 쿠폰", say("올리브베러 특가 & 4천원 할인 쿠폰"))

    @Test fun ampersandInsideBrand() = assertEquals("H 앤 M", say("H&M"))

    @Test fun hashtagsBecomeAList() =
        assertEquals("안심하고 즐기는 해외직구 안심직구 베스트, 디지털, 패션, 잡화", say("안심하고 즐기는 해외직구 안심직구 베스트 #디지털 #패션 #잡화"))

    @Test fun numberTagLosesTheMark() = assertEquals("1 베스트셀러", say("#1 베스트셀러"))

    @Test fun slashBetweenWords() = assertEquals("앱, 웹 공통", say("앱/웹 공통"))

    @Test fun middleDotBetweenWords() = assertEquals("광고, 쿠폰 안내", say("광고·쿠폰 안내"))

    @Test fun webAddress() = assertEquals("자세히는 웹 주소에서", say("자세히는 https://m.example.com/event?id=3에서"))

    @Test fun wwwAddress() = assertEquals("웹 주소 참고", say("www.example.co.kr 참고"))

    @Test fun emailAddress() = assertEquals("문의 이메일 주소", say("문의 help@example.com"))

    @Test fun decorationsAreDropped() = assertEquals("오늘만 특가 이벤트", say("★오늘만 특가★ ▶ 이벤트"))

    @Test fun noteMarkIsDropped() = assertEquals("유의사항 확인", say("※ 유의사항 확인"))

    @Test fun cornerBracketsAreDropped() = assertEquals("이벤트 가을 할인", say("【이벤트】 가을 할인"))

    @Test fun emojiAreDropped() = assertEquals("할인 쿠폰 도착", say("🎉 할인 쿠폰 도착 🎁"))

    @Test fun strayTildeIsDropped() = assertEquals("반가워요", say("반가워요~~"))

    @Test fun strayCloseMarkIsDropped() = assertEquals("광고", say("광고 ×"))

    @Test fun repeatedMarksCollapse() = assertEquals("놓치지 마세요!", say("놓치지 마세요!!!"))

    @Test fun plainSentenceIsUnchanged() =
        assertEquals("앱에서만 제공되는 혜택을 놓치고 계신 건 아닌가요?", say("앱에서만 제공되는 혜택을 놓치고 계신 건 아닌가요?"))

    // ---- spaces and lines ----

    @Test fun spacesCollapse() = assertEquals("가을 할인", say("  가을   할인  "))

    @Test fun linesGetAPause() = assertEquals("오늘만 특가.\n닫기", say("오늘만 특가\n닫기"))

    @Test fun lineEndingInPunctuationKeepsIt() = assertEquals("확인하세요!\n닫기", say("확인하세요!\n닫기"))

    @Test fun emptyLinesAreDropped() = assertEquals("가.\n나", say("가\n\n  \n나"))

    @Test fun onlyDecorationIsBlank() = assertEquals("", say("★ ♥ 🎉"))

    // ---- the debug reading-test fixtures (ReadingTestFixtures): what the LG check should hear ----

    @Test fun readingNoticeFixture() = assertEquals(
        "서비스 점검 안내.\n일시, 10월 28일 수요일 오전 2시부터 오전 4시까지.\n" +
            "기간, 10월 28일 수요일부터 11월 3일 화요일까지.\n내용, 주문 기능 일시 중단",
        say("서비스 점검 안내\n일시: 10/28(수) 02:00~04:00\n기간: 10/28(수)~11/3(화)\n내용: 주문 기능 일시 중단"),
    )

    @Test fun readingAdFixture() = assertEquals(
        "오늘만 특가 원 플러스 원 최대 70퍼센트 할인.\n안심직구 베스트, 디지털, 패션, 잡화.\n특가 그리고 4천원 할인 쿠폰.\n자세히, 웹 주소",
        say("★오늘만 특가★ 1+1 최대 70% 할인\n안심직구 베스트 #디지털 #패션 #잡화\n특가 & 4천원 할인 쿠폰\n자세히: https://example.com"),
    )

    @Test fun readingLongCloseNameFixture() = assertEquals(
        "앱에서만 제공되는 혜택을 놓치고 계신 건 아닌가요?\n앱으로 보기",
        say("앱에서만 제공되는 혜택을 놓치고 계신 건 아닌가요?\n앱으로 보기"),
    )

    @Test fun inlineJoinsLinesWithASpace() = assertEquals("봄 세일 70퍼센트", SpeakableText.inline("봄\n세일 70%"))

    @Test fun withPauseAddsOnce() {
        assertEquals("가.", SpeakableText.withPause("가"))
        assertEquals("가?", SpeakableText.withPause("가?"))
    }
}
