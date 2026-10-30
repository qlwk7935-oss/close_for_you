package kr.closeforyou.ui.voice.choice

import kotlinx.collections.immutable.toPersistentList
import kr.closeforyou.R
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonNameDisplayTest {
    private val close = ButtonRole.CLOSE
    private val skip = ButtonRole.SKIP

    @Test fun shortCloseLabelsStayAsWritten() {
        for (label in listOf("닫기", "창 닫기", "팝업 닫기", "광고 닫기", "공지 닫기", "닫기 버튼", "Close", "close ad", "Dismiss")) {
            assertNull(label, ButtonNameDisplay.roleNameRes(label, close))
        }
    }

    @Test fun shortSkipLabelsStayAsWritten() {
        for (label in listOf("건너뛰기", "스킵", "Skip", "skip ad", "광고 건너뛰기")) {
            assertNull(label, ButtonNameDisplay.roleNameRes(label, skip))
        }
    }

    @Test fun everyV1AllowlistLabelStays() {
        // Today nothing on screen changes: every label core v1 accepts is kept.
        for ((label, role) in kr.closeforyou.core.model.ButtonLabelRules.allowlist) {
            assertNull(label, ButtonNameDisplay.roleNameRes(label, role))
        }
    }

    @Test fun longCloseLabelIsShownAsClose() =
        assertEquals(R.string.ui_choice_role_close, ButtonNameDisplay.roleNameRes("앱설치 안내 레이어 닫기", close))

    @Test fun indirectCloseLabelIsShownAsClose() {
        assertEquals(R.string.ui_choice_role_close, ButtonNameDisplay.roleNameRes("모바일 웹으로 볼래요", close))
        assertEquals(R.string.ui_choice_role_close, ButtonNameDisplay.roleNameRes("웹으로 볼게요", close))
    }

    @Test fun longSkipLabelIsShownAsSkip() =
        assertEquals(R.string.ui_choice_role_skip, ButtonNameDisplay.roleNameRes("5초 후 광고 건너뛰기", skip))

    @Test fun otherRolesAndBlankLabelsAreNotTouched() {
        assertNull(ButtonNameDisplay.roleNameRes("앱설치 안내 레이어 닫기", ButtonRole.UNKNOWN))
        assertNull(ButtonNameDisplay.roleNameRes("앱설치 안내 레이어 닫기", null))
        assertNull(ButtonNameDisplay.roleNameRes(" ", close))
        assertNull(ButtonNameDisplay.roleNameRes(null, close))
    }

    // ---- real names for [내용 다시 읽기] ----

    private val acceptAll = PressableLabelPolicy { label -> if (label.isNullOrBlank()) null else ButtonRole.CLOSE }

    private fun analysis(texts: List<String>, labels: List<String?>): PopupAnalysis =
        MockPopupFixtures.notice().analysis.copy(
            texts = texts.map { ExtractedText(it, BOUNDS, TextSource.ACCESSIBILITY_TREE, null) }.toPersistentList(),
            closeCandidates = labels.mapIndexed { i, l ->
                CloseCandidate("c$i", "$i", null, l, BOUNDS, EvidenceLevel.E2_LABELED_NODE, ButtonRole.CLOSE)
            }.toPersistentList(),
        )

    @Test fun realNameIsGivenForAShortenedLabel() {
        val names = ButtonNameDisplay.realNames(analysis(listOf("앱에서 보면 더 좋아요"), listOf("앱설치 안내 레이어 닫기")), acceptAll)
        assertEquals(listOf(ButtonNameDisplay.RealName(R.string.ui_choice_role_close, "앱설치 안내 레이어 닫기")), names)
    }

    @Test fun realNameIsSkippedWhenThePopupTextHasIt() {
        val names = ButtonNameDisplay.realNames(analysis(listOf("앱에서 보면 더 좋아요", "모바일 웹으로 볼래요"), listOf("모바일 웹으로 볼래요")), acceptAll)
        assertTrue(names.isEmpty())
    }

    @Test fun keptLabelsHaveNoRealNameLine() =
        assertTrue(ButtonNameDisplay.realNames(analysis(listOf("특가"), listOf("닫기", null)), acceptAll).isEmpty())

    @Test fun labelsTheSharedRulesRejectAreNotListed() {
        // Core v1 rejects "앱설치 …" (설치): no press button shows it, so no line explains it.
        assertTrue(ButtonNameDisplay.realNames(analysis(listOf("특가"), listOf("앱설치 안내 레이어 닫기"))).isEmpty())
    }

    @Test fun toStringHidesTheLabel() =
        assertFalse(ButtonNameDisplay.RealName(R.string.ui_choice_role_close, "비밀 이름").toString().contains("비밀"))

    private companion object {
        val BOUNDS = ScreenBounds(0, 0, 10, 10)
    }
}
