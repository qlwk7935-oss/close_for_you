package kr.closeforyou.ui.voice.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private val unknown = "알 수 없는 앱"

    @Test fun knownPackageShowsAppName() {
        assertEquals("쇼핑", AppNames.displayName("kr.example.shop", source, unknown))
        assertEquals("뉴스", AppNames.displayName("kr.example.padded", source, unknown))
    }

    /** 10/30: "kr.closeforyou.mock.host" was shown in the remembered list. Never a package name. */
    @Test fun unknownOrBlankIsUnknownAppNotThePackage() {
        assertEquals(unknown, AppNames.displayName("kr.example.hidden", source, unknown))
        assertEquals(unknown, AppNames.displayName("kr.example.blank", source, unknown))
    }

    @Test fun sameAppNameGetsANumberNotThePackage() {
        val names = AppNames.displayNames(listOf("kr.example.twin.b", "kr.example.twin.a", "kr.example.shop"), source, unknown)
        assertEquals("메모 1", names["kr.example.twin.a"])
        assertEquals("메모 2", names["kr.example.twin.b"])
        assertEquals("쇼핑", names["kr.example.shop"])
        names.values.forEach { assertTrue(it, !it.contains("kr.example")) }
    }

    @Test fun failingSourceNeverBreaksTheListAndShowsNoPackage() {
        val throwing = AppLabelSource { null }
        assertEquals(
            mapOf("a.b" to "$unknown 1", "c.d" to "$unknown 2"),
            AppNames.displayNames(listOf("a.b", "c.d", "a.b"), throwing, unknown),
        )
    }
}
