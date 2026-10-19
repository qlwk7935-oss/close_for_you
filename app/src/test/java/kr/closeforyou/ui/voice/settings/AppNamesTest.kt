package kr.closeforyou.ui.voice.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNamesTest {
    private val source = AppLabelSource { pkg ->
        when (pkg) {
            "kr.example.shop" -> "쇼핑"
            "kr.example.blank" -> "   "
            "kr.example.padded" -> "  뉴스 "
            "kr.example.twin.a", "kr.example.twin.b" -> "메모"
            else -> null // not installed or hidden by package visibility
        }
    }

    @Test fun knownPackageShowsAppName() {
        assertEquals("쇼핑", AppNames.displayName("kr.example.shop", source))
        assertEquals("뉴스", AppNames.displayName("kr.example.padded", source))
    }

    @Test fun unknownOrBlankFallsBackToPackageName() {
        assertEquals("kr.example.hidden", AppNames.displayName("kr.example.hidden", source))
        assertEquals("kr.example.blank", AppNames.displayName("kr.example.blank", source))
    }

    @Test fun sameAppNameGetsPackageAdded() {
        val names = AppNames.displayNames(listOf("kr.example.twin.a", "kr.example.twin.b", "kr.example.shop"), source)
        assertEquals("메모 (kr.example.twin.a)", names["kr.example.twin.a"])
        assertEquals("메모 (kr.example.twin.b)", names["kr.example.twin.b"])
        assertEquals("쇼핑", names["kr.example.shop"])
    }

    @Test fun failingSourceNeverBreaksTheList() {
        val throwing = AppLabelSource { null }
        assertEquals(mapOf("a.b" to "a.b", "c.d" to "c.d"), AppNames.displayNames(listOf("a.b", "c.d", "a.b"), throwing))
    }
}
