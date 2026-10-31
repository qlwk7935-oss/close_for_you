package kr.closeforyou.simpleclose

import kr.closeforyou.ui.voice.settings.HelpAppsMode
import kr.closeforyou.ui.voice.settings.UiVoiceSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleAppAllowanceTest {
    private val own = "kr.closeforyou"
    private val chrome = "com.android.chrome"

    @Test fun allModeHelpsEveryAppButExclusionsOwnAndSystem() {
        val s = UiVoiceSettings(helpAppsMode = HelpAppsMode.ALL, helpAppsExcluded = setOf("com.kakao.talk"))
        assertTrue(SimpleAppAllowance.allows(chrome, own, s, emptySet()))
        assertFalse(SimpleAppAllowance.allows("com.kakao.talk", own, s, emptySet()))
        assertFalse(SimpleAppAllowance.allows(own, own, s, emptySet()))
        assertFalse(SimpleAppAllowance.allows("com.android.systemui", own, s, emptySet()))
        assertFalse(SimpleAppAllowance.allows("com.google.android.permissioncontroller", own, s, emptySet()))
        assertFalse(SimpleAppAllowance.allows("com.samsung.android.honeyboard", own, s, emptySet(), inputMethods = setOf("com.samsung.android.honeyboard")))
        assertFalse(SimpleAppAllowance.allows(null, own, s, emptySet()))
    }

    @Test fun chosenModeHelpsOnlyAllowedOrPendingApps() {
        val s = UiVoiceSettings(helpAppsMode = HelpAppsMode.CHOSEN, pendingHelpPackages = setOf("com.eleven.st"))
        assertTrue(SimpleAppAllowance.allows(chrome, own, s, setOf(chrome)))
        assertTrue(SimpleAppAllowance.allows("com.eleven.st", own, s, emptySet()))
        assertFalse(SimpleAppAllowance.allows("com.other", own, s, setOf(chrome)))
    }

    @Test fun noChoiceYetFollowsTheDemoSwitch() {
        val s = UiVoiceSettings(helpAppsMode = null)
        assertTrue(SimpleAppAllowance.allows(chrome, own, s, emptySet(), helpAllWhenUnset = true))
        assertFalse(SimpleAppAllowance.allows(chrome, own, s, emptySet(), helpAllWhenUnset = false))
        assertTrue(SimpleAppAllowance.allows(chrome, own, s, setOf(chrome), helpAllWhenUnset = false))
    }
}
