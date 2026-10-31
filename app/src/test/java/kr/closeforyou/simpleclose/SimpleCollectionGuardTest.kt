package kr.closeforyou.simpleclose

import kr.closeforyou.ui.voice.settings.HelpAppsMode
import kr.closeforyou.ui.voice.settings.InstalledApp
import kr.closeforyou.ui.voice.settings.UiVoiceSettings
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

/** Uses the same boundary as SimpleTreeCollector: traversal is observable and must not start
 * for a denied read. No fake Android implementation or external click executor is involved. */
class SimpleCollectionGuardTest {
    private val chrome = "com.android.chrome"
    private class Root(val pkg: String?)
    private class Rig(pkg: String?) {
        val node = Root(pkg)
        var gets = 0
        var walks = 0
        var releases = 0
        var lookup: (String) -> InstalledApp? = { InstalledApp(it, "Chrome") }
        var settings = UiVoiceSettings(helpAppsMode = HelpAppsMode.ALL)
        fun allowed(pkg: String) = SimpleAppAllowance.allows(pkg, "kr.closeforyou", settings, emptySet(), lookup = lookup)
        fun read(expected: String?) = SimpleCollectionGuard.read(expected,
            root = { gets++; node }, packageOf = { it.pkg }, allowed = ::allowed,
            collect = { root, _ -> assertSame(node, root); walks++; "COLLECTED" },
            release = { assertSame(node, it); releases++ })
    }

    @Test fun sensitiveRootIsRejectedBeforeAnyTreeOrWindowTraversal() {
        for (pkg in listOf("com.kbstar.kbbank", "com.x8bit.bitwarden")) {
            val r = Rig(pkg)
            assertNull(r.read(pkg)); assertEquals(0, r.walks); assertEquals(1, r.releases)
        }
        val namedBank = Rig("kr.example.mobile").apply { lookup = { InstalledApp(it, "은행") } }
        assertNull(namedBank.read("kr.example.mobile")); assertEquals(0, namedBank.walks)
    }

    @Test fun permittedEventDoesNotAuthorizeAnotherRootEvenWhenBothAppsAreAllowed() {
        for (pkg in listOf("com.kbstar.kbbank", "com.example.browser", "kr.closeforyou")) {
            val r = Rig(pkg)
            assertTrue(r.allowed(chrome))
            assertNull(r.read(chrome)); assertEquals(0, r.walks); assertEquals(1, r.releases)
        }
    }

    @Test fun nullOrMissingIdentityNeverStartsTraversal() {
        val r = Rig(null)
        assertNull(r.read(chrome)); assertEquals(0, r.walks); assertEquals(1, r.releases)
        val noEvent = Rig(chrome)
        assertNull(noEvent.read(null)); assertEquals(0, noEvent.gets)
        val hidden = Rig(chrome).apply { lookup = { null } }
        assertNull(hidden.read(chrome)); assertEquals(0, hidden.walks); assertEquals(1, hidden.releases)
    }

    @Test fun packageQueryFailureReturnsNoCollectedDataOrExceptionText() {
        val r = Rig(chrome).apply { lookup = { throw SecurityException("PRIVATE_FIXTURE") } }
        assertNull(r.read(chrome)); assertEquals(0, r.walks); assertEquals(1, r.releases)
    }

    @Test fun allowedChromeRetainsTraversalAndReleasesTheExactRoot() {
        val r = Rig(chrome)
        assertEquals("COLLECTED", r.read(chrome)); assertEquals(1, r.walks); assertEquals(1, r.releases)
    }

    @Test fun eventAllowanceIsNotCachedAcrossDelayedReadOrSettingsChange() {
        val r = Rig(chrome)
        assertTrue(r.allowed(chrome))
        r.lookup = { null }
        assertNull(r.read(chrome)); assertEquals(0, r.walks)
        r.lookup = { InstalledApp(it, "Chrome") }
        r.settings = r.settings.copy(helpAppsExcluded = setOf(chrome))
        assertNull(r.read(chrome)); assertEquals(0, r.walks)
    }

    @Test fun rootGetterPackageGetterAndTraversalExceptionsFailClosedWithRelease() {
        val n = Root(chrome)
        for (at in listOf("ROOT", "PACKAGE", "WALK", "RELEASE")) {
            var released = 0
            val value = SimpleCollectionGuard.read(chrome,
                root = { if (at == "ROOT") error("PRIVATE_FIXTURE"); n },
                packageOf = { if (at == "PACKAGE") error("PRIVATE_FIXTURE"); it.pkg },
                allowed = { true }, collect = { _, _ -> if (at == "WALK") error("PRIVATE_FIXTURE"); "COLLECTED" },
                release = { released++; if (at == "RELEASE") error("PRIVATE_FIXTURE") })
            assertNull(value); assertEquals(if (at == "ROOT") 0 else 1, released)
        }
    }

    @Test fun cancellationStillReleasesWithoutTraversing() {
        var released = 0
        var walked = false
        val cancel = CancellationException("SYNTHETIC")
        try {
            SimpleCollectionGuard.read(chrome, { Root(chrome) }, { it.pkg }, { throw cancel },
                { _, _ -> walked = true; "COLLECTED" }, { released++ })
            fail("CANCELLATION_NOT_PROPAGATED")
        } catch (actual: CancellationException) { assertSame(cancel, actual) }
        assertFalse(walked); assertEquals(1, released)
    }
}
