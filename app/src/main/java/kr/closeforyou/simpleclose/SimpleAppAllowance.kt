package kr.closeforyou.simpleclose

import kr.closeforyou.ui.voice.settings.HelpAppsMode
import kr.closeforyou.ui.voice.settings.UiVoiceSettings

/**
 * Which apps the engine helps, from C's "도와줄 앱" setting: ALL = every app but our own, the system
 * packages below and the person's exclusions; CHOSEN = the ticked apps (A's allowed list plus the
 * ones still pending there). No choice yet: all apps in demo mode ([SimpleCloseEngineFeature.helpAllAppsWhenUnset]).
 * System UI, settings and permission dialogs are never handled: a permission prompt is a decision.
 */
object SimpleAppAllowance {
    private val NEVER = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.samsung.android.permissioncontroller", "com.lge.permissioncontroller",
    )

    fun allows(
        packageName: String?,
        ownPackage: String,
        settings: UiVoiceSettings,
        coreAllowed: Set<String>,
        inputMethods: Set<String> = emptySet(),
        helpAllWhenUnset: Boolean = SimpleCloseEngineFeature.helpAllAppsWhenUnset,
    ): Boolean {
        if (packageName.isNullOrEmpty() || packageName == ownPackage || packageName in NEVER || packageName in inputMethods) return false
        return when (settings.helpAppsMode) {
            HelpAppsMode.ALL -> packageName !in settings.helpAppsExcluded
            HelpAppsMode.CHOSEN -> packageName in coreAllowed || packageName in settings.pendingHelpPackages
            null -> helpAllWhenUnset || packageName in coreAllowed
        }
    }
}
