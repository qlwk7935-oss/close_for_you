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
    fun visualOnlyCloseIsNeverRemembered() {
        all.filter { it.closeEvidence == CloseEvidence.E4_VISUAL_ONLY }.forEach {
            assertNull(it.id, it.correctTargetId)
            assertFalse(it.id, it.rememberable)
        }
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

    @Test
    fun everyScenarioMapsToAnExperiment() {
        all.forEach { assertTrue(it.id, it.experiments.isNotEmpty()) }
    }
}
