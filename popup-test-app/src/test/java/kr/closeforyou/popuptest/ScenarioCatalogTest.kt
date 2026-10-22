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
        all.filter { it.kind == PopupKind.AD || it.kind == PopupKind.NOTICE }.forEach {
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

    @Test
    fun everyScenarioMapsToAnExperiment() {
        all.forEach { assertTrue(it.id, it.experiments.isNotEmpty()) }
    }
}
