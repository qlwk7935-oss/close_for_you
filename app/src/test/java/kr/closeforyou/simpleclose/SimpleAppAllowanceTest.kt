package kr.closeforyou.simpleclose

import kr.closeforyou.ui.voice.settings.HelpAppsMode
import kr.closeforyou.ui.voice.settings.InstalledApp
import kr.closeforyou.ui.voice.settings.UiVoiceSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleAppAllowanceTest {
    private val own = "kr.closeforyou"
    private val chrome = "com.android.chrome"

    private fun allows(pkg: String?, owner: String, settings: UiVoiceSettings, core: Set<String>,
        inputMethods: Set<String> = emptySet(), lookup: (String) -> InstalledApp? = { InstalledApp(it, "Example App") }) =
        SimpleAppAllowance.allows(pkg, owner, settings, core, inputMethods, lookup)

    @Test fun sensitivePackageIsDeniedEvenWhenExplicitlyChosen() {
        val bank = "com.kbstar.kbbank"
        val s = UiVoiceSettings(helpAppsMode = HelpAppsMode.CHOSEN, pendingHelpPackages = setOf(bank))
        assertFalse(allows(bank, own, s, setOf(bank)))
    }

    @Test fun sensitivePackageIsDeniedInAllMode() {
        assertFalse(allows("com.x8bit.bitwarden", own,
            UiVoiceSettings(helpAppsMode = HelpAppsMode.ALL), emptySet()))
    }

    @Test fun noChoiceDoesNotGrantImplicitAllApps() {
        assertFalse(allows(chrome, own, UiVoiceSettings(), emptySet()))
    }

    @Test fun allModeHelpsEveryAppButExclusionsOwnAndSystem() {
        val s = UiVoiceSettings(helpAppsMode = HelpAppsMode.ALL, helpAppsExcluded = setOf("com.kakao.talk"))
        assertTrue(allows(chrome, own, s, emptySet()))
        assertFalse(allows("com.kakao.talk", own, s, emptySet()))
        assertFalse(allows(own, own, s, emptySet()))
        assertFalse(allows("com.android.systemui", own, s, emptySet()))
        assertFalse(allows("com.google.android.permissioncontroller", own, s, emptySet()))
        assertFalse(allows("com.samsung.android.honeyboard", own, s, emptySet(), inputMethods = setOf("com.samsung.android.honeyboard")))
        assertFalse(allows(null, own, s, emptySet()))
    }

    @Test fun chosenModeHelpsOnlyAllowedOrPendingApps() {
        val s = UiVoiceSettings(helpAppsMode = HelpAppsMode.CHOSEN, pendingHelpPackages = setOf("com.eleven.st"))
        assertTrue(allows(chrome, own, s, setOf(chrome)))
        assertTrue(allows("com.eleven.st", own, s, emptySet()))
        assertFalse(allows("com.other", own, s, setOf(chrome)))
    }

    @Test fun noChoiceKeepsOnlyExistingExplicitCoreAllowance() {
        val s = UiVoiceSettings(helpAppsMode = null)
        assertFalse(allows(chrome, own, s, emptySet()))
        assertTrue(allows(chrome, own, s, setOf(chrome)))
        assertFalse(allows(chrome, own, s.copy(pendingHelpPackages = setOf(chrome)), emptySet()))
    }

    @Test fun knownSensitivePackagesAreDeniedInEveryScopeDespiteSelection() {
        for (pkg in listOf("com.kbstar.kbbank", "com.samsung.android.spay", "com.kiwoom.heromts",
                "com.x8bit.bitwarden", "com.google.android.apps.authenticator2", "com.sktelecom.tauth")) {
            for (mode in listOf(HelpAppsMode.ALL, HelpAppsMode.CHOSEN, null)) {
                var queried = 0
                assertFalse(allows(pkg, own, UiVoiceSettings(helpAppsMode = mode, pendingHelpPackages = setOf(pkg)),
                    setOf(pkg), lookup = { queried++; InstalledApp(it, "Example App") }))
                assertEquals(0, queried)
            }
        }
    }

    @Test fun sensitiveResolvedNameIsDeniedEvenWithAnUnremarkablePackage() {
        val pkg = "kr.example.mobile"
        for (label in listOf("우리 은행", "모바일 증권", "간편 결제", "비밀번호 관리자", "OTP 인증")) {
            for (mode in listOf(HelpAppsMode.ALL, HelpAppsMode.CHOSEN, null)) {
                assertFalse(allows(pkg, own, UiVoiceSettings(helpAppsMode = mode), setOf(pkg),
                    lookup = { InstalledApp(it, label) }))
            }
        }
    }

    @Test fun metadataUnavailableInvalidOrHiddenByPackageVisibilityIsDenied() {
        val s = UiVoiceSettings(helpAppsMode = HelpAppsMode.ALL)
        assertFalse(SimpleAppAllowance.allows(chrome, own, s, emptySet()))
        for (info in listOf(null, InstalledApp("com.other", "Chrome"), InstalledApp(chrome, " "), InstalledApp(chrome, chrome))) {
            assertFalse(allows(chrome, own, s, emptySet(), lookup = { info }))
        }
        assertFalse(allows(chrome, own, s, emptySet(), lookup = { throw SecurityException("PRIVATE_FIXTURE") }))
        assertFalse(allows(chrome, own, s, emptySet(), lookup = { throw IllegalStateException("PRIVATE_FIXTURE") }))
    }

    @Test fun unknownPackageCannotBeRepairedWithAChosenEntry() {
        for (pkg in listOf("", " ", "com.android.chrome ", "com..chrome")) {
            assertFalse(allows(pkg, own, UiVoiceSettings(helpAppsMode = HelpAppsMode.CHOSEN), setOf(pkg)))
        }
    }

    @Test fun chromeIsAllowedWithRealMetadataInAllAndExplicitScopes() {
        for (mode in listOf(HelpAppsMode.ALL, HelpAppsMode.CHOSEN, null)) {
            assertTrue(allows(chrome, own, UiVoiceSettings(helpAppsMode = mode), setOf(chrome),
                lookup = { InstalledApp(it, "Chrome") }))
        }
    }

    @Test fun userExclusionAlwaysWinsOverAllowedAndPendingLists() {
        for (mode in listOf(HelpAppsMode.ALL, HelpAppsMode.CHOSEN, null)) {
            assertFalse(allows(chrome, own, UiVoiceSettings(helpAppsMode = mode,
                pendingHelpPackages = setOf(chrome), helpAppsExcluded = setOf(chrome)), setOf(chrome)))
        }
    }
}
