package kr.closeforyou.simpleclose

import kr.closeforyou.ui.voice.settings.HelpAppsMode
import kr.closeforyou.ui.voice.settings.InstalledApp
import kr.closeforyou.ui.voice.settings.SensitiveAppRules
import kr.closeforyou.ui.voice.settings.UiVoiceSettings
import java.util.concurrent.CancellationException

/**
 * App scope never overrides sensitive-app protection or an unavailable package identity.
 * ALL excludes protected apps and the person's exclusions; CHOSEN uses the saved/pending list.
 * No mode chosen: only an existing explicit A allowance, never implicit all-app collection.
 * System UI, settings and permission dialogs are never handled: a permission prompt is a decision.
 */
object SimpleAppAllowance {
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
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
        lookup: (String) -> InstalledApp? = { null },
    ): Boolean {
        if (packageName.isNullOrBlank() || !PACKAGE.matches(packageName) || packageName == ownPackage ||
            packageName in NEVER || packageName in inputMethods || packageName in settings.helpAppsExcluded) return false
        if (SensitiveAppRules.looksSensitive(InstalledApp(packageName, ""))) return false
        val selected = when (settings.helpAppsMode) {
            HelpAppsMode.ALL -> true
            HelpAppsMode.CHOSEN -> packageName in coreAllowed || packageName in settings.pendingHelpPackages
            null -> packageName in coreAllowed
        }
        if (!selected) return false
        val app = try { lookup(packageName) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } ?: return false
        // PackageManager may fall back to the package string when no label could be loaded.
        // Neither that fallback nor a picker placeholder is evidence that the name is safe.
        if (app.packageName != packageName || app.label.isBlank() || app.label.trim() == packageName) return false
        return !SensitiveAppRules.looksSensitive(app)
    }
}
