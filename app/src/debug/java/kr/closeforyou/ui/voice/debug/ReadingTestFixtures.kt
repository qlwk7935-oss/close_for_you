package kr.closeforyou.ui.voice.debug

import kotlinx.collections.immutable.toPersistentList
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.testing.mock.MockPopupFixture
import kr.closeforyou.testing.mock.MockPopupFixtures

/**
 * C-local synthetic fixtures (debug only) to hear `SpeakableText` on a device: times, dates, ranges,
 * 1+1, %, #, &, a web address, decorations, and a long close name. Made-up text, no real app data.
 * The words are fixture content, same category as the strings inside MockPopupFixtures.
 */
internal object ReadingTestFixtures {
    /** "공지 (낭독 시험)": several lines with a time range and a date range. */
    fun notice(): MockPopupFixture = build(
        scenarioId = "reading-notice",
        base = MockPopupFixtures.notice(),
        lines = listOf("서비스 점검 안내", "일시: 10/28(수) 02:00~04:00", "기간: 10/28(수)~11/3(화)", "내용: 주문 기능 일시 중단"),
        buttons = listOf("확인" to ButtonRole.ACKNOWLEDGE, "닫기" to ButtonRole.CLOSE),
    )

    /** "광고 (기호 시험)": decorations, 1+1, %, hashtags, &, a web address. */
    fun advertisement(): MockPopupFixture = build(
        scenarioId = "reading-advertisement",
        base = MockPopupFixtures.advertisement(),
        lines = listOf("★오늘만 특가★ 1+1 최대 70% 할인", "안심직구 베스트 #디지털 #패션 #잡화", "특가 & 4천원 할인 쿠폰", "자세히: https://example.com"),
        buttons = listOf("닫기" to ButtonRole.CLOSE, "지금 구매하기" to ButtonRole.USER_DECISION),
    )

    /**
     * "앱 설치 안내 (긴 닫기 이름)": core v1 rejects "앱설치 안내 레이어 닫기" (설치), so no press button
     * appears and the prompt says it cannot close for the person. Correct until core v2.
     */
    fun longCloseName(): MockPopupFixture = build(
        scenarioId = "reading-long-close-name",
        base = MockPopupFixtures.advertisement(),
        lines = listOf("앱에서만 제공되는 혜택을 놓치고 계신 건 아닌가요?", "앱으로 보기"),
        buttons = listOf("앱설치 안내 레이어 닫기" to ButtonRole.CLOSE),
    )

    private fun build(
        scenarioId: String,
        base: MockPopupFixture,
        lines: List<String>,
        buttons: List<Pair<String, ButtonRole>>,
    ): MockPopupFixture {
        val texts = lines.mapIndexed { i, line ->
            ExtractedText(line, ScreenBounds(80, 420 + i * 80, 1000, 490 + i * 80), TextSource.ACCESSIBILITY_TREE, null)
        }
        val candidates = buttons.mapIndexed { i, (label, role) ->
            CloseCandidate(
                id = "$scenarioId-button-$i", nodePath = "b$i", viewId = null, label = label,
                bounds = ScreenBounds(760 - i * 300, 440, 960 - i * 300, 550),
                evidence = EvidenceLevel.E2_LABELED_NODE, role = role,
            )
        }
        return base.copy(
            scenarioId = scenarioId,
            analysis = base.analysis.copy(texts = texts.toPersistentList(), closeCandidates = candidates.toPersistentList()),
        )
    }
}
