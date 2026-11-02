package kr.closeforyou.popuptest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 정답지 자체의 일관성 검사. 닫아줄게의 감지·분류 성능을 검증하는 시험이 아니다. */
class ScenarioCatalogTest {
    private val all = ScenarioCatalog.all

    @Test
    fun idsAreUniqueAndWellFormed() {
        assertEquals(all.size, all.map { it.id }.toSet().size)
        all.forEach { assertTrue(it.id, Regex("[PN]\\d\\d").matches(it.id)) }
    }

    @Test
    fun importantDecisionsAreNeverAutomatable() {
        all.filter { it.kind == PopupKind.USER_DECISION || it.kind == PopupKind.SENSITIVE }.forEach {
            assertTrue(it.id, it.expected == ExpectedHandling.AUTO_FORBIDDEN ||
                it.expected == ExpectedHandling.BLOCK_ANALYSIS)
            assertNull(it.id, it.correctTargetId)
            assertNull(it.id, it.appAllowedTargetId)
            assertFalse(it.id, it.rememberable)
        }
    }

    @Test
    fun sensitiveScreensBlockAnalysis() {
        all.filter { it.kind == PopupKind.SENSITIVE }.forEach {
            assertEquals(it.id, ExpectedHandling.BLOCK_ANALYSIS, it.expected)
            assertTrue(it.id, it.keyFacts.isEmpty())
        }
    }

    @Test
    fun nonPopupsExpectNoAction() {
        all.filter { it.kind == PopupKind.NOT_POPUP }.forEach {
            assertEquals(it.id, ExpectedHandling.NO_ACTION, it.expected)
            assertNull(it.id, it.correctTargetId)
            assertFalse(it.id, it.rememberable)
        }
    }

    @Test
    fun visualOnlyCloseHasNoHumanTarget() {
        all.filter { it.closeEvidence == CloseEvidence.E4_VISUAL_ONLY }.forEach {
            assertNull(it.id, it.correctTargetId)
        }
    }

    /** AGENTS 6-3·A 게이트: 라벨 없는 X(E3)·그림 X(E4)는 앱이 누르지도 기억하지도 않는다. */
    @Test
    fun unlabeledOrVisualXIsNeverAppClickableOrRemembered() {
        all.filter {
            it.closeEvidence == CloseEvidence.E3_UNLABELED_CLICKABLE || it.closeEvidence == CloseEvidence.E4_VISUAL_ONLY
        }.forEach {
            assertNull(it.id, it.appAllowedTargetId)
            assertFalse(it.id, it.rememberable)
        }
    }

    @Test
    fun appAllowedTargetIsTheHumanTargetAndNeverForbidden() {
        all.forEach { s ->
            s.appAllowedTargetId?.let {
                assertEquals(s.id, s.correctTargetId, it)
                assertFalse(s.id, it in s.forbiddenTargetIds)
            }
        }
    }

    @Test
    fun onlyAppClickableScenariosAreRememberable() {
        all.filter { it.rememberable }.forEach { assertNotNull(it.id, it.appAllowedTargetId) }
    }

    @Test
    fun correctTargetIsNeverForbidden() {
        all.forEach { s -> s.correctTargetId?.let { assertFalse(s.id, it in s.forbiddenTargetIds) } }
    }

    @Test
    fun firstSeenAdsAreUserChoiceNotAutomatic() {
        all.filter { it.kind == PopupKind.AD || it.kind == PopupKind.COUPON || it.kind == PopupKind.NOTICE }.forEach {
            assertEquals(it.id, ExpectedHandling.USER_CHOICE, it.expected)
        }
    }

    @Test
    fun onlyIdenticalNoticeReusesMemory() {
        val reusing = all.filter { it.memoryReusableFrom != null }
        assertEquals(listOf("N02"), reusing.map { it.id })
        val original = ScenarioCatalog.find("N01")
        assertNotNull(original)
        assertEquals(original!!.note, ScenarioCatalog.find("N02")!!.note)
        (3..7).map { "N0$it" }.forEach { id ->
            val changed = ScenarioCatalog.find(id)
            assertNotNull(id, changed)
            assertNull(id, changed!!.memoryReusableFrom)
            assertTrue(id, changed.note != original.note)
        }
    }

    /** C09 N08: 글자는 N01과 같아도 닫기 버튼 위치가 바뀌었으므로 N01 기억을 재사용하지 않는다. */
    @Test
    fun movedCloseButtonNoticeKeepsTextButMustNotReuseMemory() {
        val original = ScenarioCatalog.find("N01")!!
        val moved = ScenarioCatalog.find(ScenarioCatalog.BUTTON_MOVED_NOTICE_ID)!!
        assertEquals(original.note, moved.note)
        assertEquals(original.correctTargetId, moved.correctTargetId)
        assertEquals(original.appAllowedTargetId, moved.appAllowedTargetId)
        assertNull(moved.memoryReusableFrom)
        assertTrue(moved.rememberable)
        assertTrue("C09" in moved.experiments)
    }

    /** P16·P18: 시간이 흐르며 글자가 바뀌는 화면은 지문이 매번 달라 기억 대상이 아니다. */
    @Test
    fun timeVaryingScreensAreNotRemembered() {
        listOf("P16", "P18").forEach { assertFalse(it, ScenarioCatalog.find(it)!!.rememberable) }
        assertTrue(ScenarioCatalog.VIDEO_SKIP_AFTER_SECONDS in 1..10)
        assertTrue(ScenarioCatalog.SALE_COUNTDOWN_SECONDS > ScenarioCatalog.VIDEO_SKIP_AFTER_SECONDS)
    }

    /** P17: 건너뛰기는 금지 버튼은 아니지만 정답도 아니다. 기억은 닫기에만 맞아야 한다. */
    @Test
    fun closeAndSkipTogetherTargetsCloseOnly() {
        val s = ScenarioCatalog.find("P17")!!
        assertEquals("btn_close", s.correctTargetId)
        assertEquals("btn_close", s.appAllowedTargetId)
        assertFalse("btn_skip" in s.forbiddenTargetIds)
        assertTrue(s.rememberable)
    }

    /** P16의 정답은 카운트다운 뒤 생기는 건너뛰기 버튼이다. */
    @Test
    fun videoAdTargetIsSkip() {
        val s = ScenarioCatalog.find("P16")!!
        assertEquals(PopupSurface.VIDEO_OVERLAY, s.surface)
        assertEquals("btn_skip", s.appAllowedTargetId)
        assertTrue("btn_ad_open" in s.forbiddenTargetIds)
    }

    /** 첫 통합 예상이 사람 기준 종류와 다른 시나리오는 이 목록에만 있고, 이유가 적혀 있어야 한다. */
    @Test
    fun int1GapsAreListedAndExplained() {
        assertEquals(all.map { it.id }.toSet(), ScenarioCatalog.int1.keys)
        val gaps = all.filter { ScenarioCatalog.int1.getValue(it.id).kind != humanInt1(it.kind) }.map { it.id }.toSet()
        assertEquals(setOf("P05", "P07", "P18", "P24"), gaps)
        gaps.forEach { assertTrue(it, ScenarioCatalog.int1.getValue(it).reason.isNotBlank()) }
    }

    private fun humanInt1(kind: PopupKind): Int1Kind = when (kind) {
        PopupKind.AD -> Int1Kind.ADVERTISEMENT
        PopupKind.COUPON -> Int1Kind.COUPON
        PopupKind.NOTICE -> Int1Kind.NOTICE
        PopupKind.SUSPICIOUS -> Int1Kind.UNKNOWN
        PopupKind.USER_DECISION -> Int1Kind.USER_DECISION
        PopupKind.SENSITIVE -> Int1Kind.BLOCKED
        PopupKind.NOT_POPUP -> Int1Kind.NONE
    }

    @Test
    fun presetGoalsFollowKindAndTarget() {
        fun goals(id: String) = TestPreset.entries.map { ScenarioCatalog.presetGoal(ScenarioCatalog.find(id)!!, it) }
        assertEquals(listOf(PresetGoal.CLOSE, PresetGoal.CLOSE, PresetGoal.ANNOUNCE), goals("P01")) // 쿠폰
        assertEquals(listOf(PresetGoal.CLOSE, PresetGoal.CLOSE, PresetGoal.CLOSE), goals("P06")) // 광고
        assertEquals(listOf(PresetGoal.CLOSE, PresetGoal.ANNOUNCE, PresetGoal.ANNOUNCE), goals("N01")) // 공지
        assertEquals(List(3) { PresetGoal.ASK }, goals("P04")) // 앱 허용 버튼 없음
        assertEquals(List(3) { PresetGoal.ASK }, goals("P16")) // 자동 건너뛰기 꺼짐(#46)
        assertEquals(List(3) { PresetGoal.ASK }, goals("P08")) // 결정
        assertEquals(List(3) { PresetGoal.BLOCK }, goals("P11")) // 민감
        assertEquals(List(3) { PresetGoal.NONE }, goals("P14")) // 팝업 아님
        // 11/2 팀 결정: P13·P24 웹 "닫기"는 누를 수 있다(LG 닫힘 확인) → P01과 같은 쿠폰 목표.
        assertEquals(listOf(PresetGoal.CLOSE, PresetGoal.CLOSE, PresetGoal.ANNOUNCE), goals("P13"))
        assertEquals(listOf(PresetGoal.CLOSE, PresetGoal.CLOSE, PresetGoal.ANNOUNCE), goals("P24"))
        assertEquals(listOf(PresetGoal.CLOSE, PresetGoal.CLOSE, PresetGoal.ANNOUNCE), goals("P33")) // 쿠폰 시트
        assertEquals(List(3) { PresetGoal.CLOSE }, goals("P34")) // 광고 레이어
        assertEquals(List(3) { PresetGoal.CLOSE }, goals("P35")) // 앱에서 열기 시트(닫는 버튼은 모바일 웹으로 보기)
    }

    @Test
    fun couponScenariosAreTheClaimableBenefits() {
        assertEquals(setOf("P01", "P02", "P12", "P13", "P23", "P24", "P33"), all.filter { it.kind == PopupKind.COUPON }.map { it.id }.toSet())
    }

    /** S18: 사기 의심은 어떤 프리셋에서도 앱이 누르지 않는다. */
    @Test
    fun suspiciousPopupsAreNeverAutomatable() {
        val suspicious = all.filter { it.kind == PopupKind.SUSPICIOUS }
        assertEquals(setOf("P21", "P22"), suspicious.map { it.id }.toSet())
        suspicious.forEach { s ->
            assertEquals(s.id, ExpectedHandling.AUTO_FORBIDDEN, s.expected)
            assertNull(s.id, s.appAllowedTargetId)
            assertFalse(s.id, s.rememberable)
            assertTrue(s.id, s.forbiddenTargetIds.isNotEmpty())
            TestPreset.entries.forEach { assertEquals(s.id, PresetGoal.ASK, ScenarioCatalog.presetGoal(s, it)) }
        }
    }

    /** S16: 라벨이 '닫기'여도 앱 밖으로 보내는 버튼은 앱 허용 버튼이 아니다. */
    @Test
    fun fakeCloseThatLeavesTheAppIsNotAppAllowed() {
        val s = ScenarioCatalog.find("P19")!!
        assertNull(s.appAllowedTargetId)
        assertTrue("btn_fake_close" in s.forbiddenTargetIds)
    }

    /** S17은 B 속도 제한(1분 3개)을 넘겨야 4번째 묻기를 볼 수 있다. S20은 몇 초 안에 버튼이 생겨야 한다. */
    @Test
    fun autoCloseScenarioTimingFitsTheChecklist() {
        assertTrue(ScenarioCatalog.AD_SERIES_COUNT > 3)
        assertTrue(ScenarioCatalog.DELAYED_CLOSE_SECONDS in 1..10)
    }

    /** A 10/25: 실제 앱처럼 뒤 페이지에 위험 문구가 있으면 팝업이 쿠폰이어도 A 결정 요약으로 막혀야 한다. */
    @Test
    fun riskyBackPageStaysBlockedAfterHidingTheList() {
        val p24 = ScenarioCatalog.int1.getValue("P24")
        assertEquals(Int1Kind.USER_DECISION, p24.kind)
        assertEquals(Int1Path.A_SUMMARY, p24.path)
        assertEquals(Int1Kind.COUPON, ScenarioCatalog.int1.getValue("P13").kind)
        // 11/2 팀 결정: 시연 엔진은 팝업 자신의 "닫기"만 누른다(프리셋 CLOSE). A 결정 요약 회귀(int1)는 그대로 지킨다.
        assertEquals("btn_close", ScenarioCatalog.find("P24")!!.appAllowedTargetId)
    }

    /** 11/2 추가 유형: Dialog 표시가 없거나 화면 아래에 붙은 팝업. 닫는 버튼은 이름이 있고, 여는 버튼은 금지다. */
    @Test
    fun newLayoutTypesCloseWithANamedButton() {
        val sheet = ScenarioCatalog.find("P33")!!
        val layer = ScenarioCatalog.find("P34")!!
        val openInApp = ScenarioCatalog.find("P35")!!
        assertEquals(PopupSurface.BOTTOM_SHEET, sheet.surface)
        assertEquals(PopupSurface.FRONT_LAYER, layer.surface)
        assertEquals(PopupSurface.BOTTOM_SHEET, openInApp.surface)
        listOf(sheet, layer, openInApp).forEach {
            assertEquals(it.id, CloseEvidence.E2_LABELED_CLICK, it.closeEvidence)
            assertNotNull(it.id, it.appAllowedTargetId)
        }
        assertEquals("btn_stay_web", openInApp.appAllowedTargetId)
        assertTrue("btn_open_app" in openInApp.forbiddenTargetIds)
    }

    @Test
    fun everyScenarioMapsToAnExperiment() {
        all.forEach { assertTrue(it.id, it.experiments.isNotEmpty()) }
    }
}
