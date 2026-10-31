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
/**
 * F22: why an app is not helped (debug log `SKIPPED_SCOPE_*`, `TREE_UNAVAILABLE_*`): a reason code only, never
 * the package. [SENSITIVE] is #65's protection (bank, pay, card… by package or name).
 */
enum class ScopeMiss { NO_PACKAGE, OWN_APP, SYSTEM, INPUT_METHOD, USER_EXCLUDED, SENSITIVE, NOT_CHOSEN, NO_MODE_NOT_ALLOWED, LABEL_UNKNOWN }

object SimpleAppAllowance {
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val NEVER = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.samsung.android.permissioncontroller", "com.lge.permissioncontroller",
    )

    /** Cheap event scheduling filter only. Passing this never authorizes node collection.
     * No PackageManager/label lookup, saved metadata or allowed-result cache. */
    internal fun isInScope(
        packageName: String?,
        ownPackage: String,
        settings: UiVoiceSettings,
        coreAllowed: Set<String>,
        inputMethods: Set<String> = emptySet(),
    ): Boolean {
        if (packageName.isNullOrBlank() || !PACKAGE.matches(packageName) || packageName == ownPackage ||
            packageName in NEVER || packageName in inputMethods || packageName in settings.helpAppsExcluded) return false
        if (SensitiveAppRules.looksSensitive(InstalledApp(packageName, ""))) return false
        return when (settings.helpAppsMode) {
            HelpAppsMode.ALL -> true
            HelpAppsMode.CHOSEN -> packageName in coreAllowed || packageName in settings.pendingHelpPackages
            null -> packageName in coreAllowed
        }
    }

    /** F22: the reason [isInScope] says no, or null when it says yes. Same order as [isInScope]. */
    internal fun scopeMiss(
        packageName: String?,
        ownPackage: String,
        settings: UiVoiceSettings,
        coreAllowed: Set<String>,
        inputMethods: Set<String> = emptySet(),
    ): ScopeMiss? = when {
        packageName.isNullOrBlank() || !PACKAGE.matches(packageName) -> ScopeMiss.NO_PACKAGE
        packageName == ownPackage -> ScopeMiss.OWN_APP
        packageName in NEVER -> ScopeMiss.SYSTEM
        packageName in inputMethods -> ScopeMiss.INPUT_METHOD
        packageName in settings.helpAppsExcluded -> ScopeMiss.USER_EXCLUDED
        SensitiveAppRules.looksSensitive(InstalledApp(packageName, "")) -> ScopeMiss.SENSITIVE
        settings.helpAppsMode == HelpAppsMode.ALL -> null
        settings.helpAppsMode == HelpAppsMode.CHOSEN -> if (packageName in coreAllowed || packageName in settings.pendingHelpPackages) null else ScopeMiss.NOT_CHOSEN
        else -> if (packageName in coreAllowed) null else ScopeMiss.NO_MODE_NOT_ALLOWED
    }

    /** F22: the reason [allows] says no (after the scope: the app's name is unknown or sensitive), or null. */
    fun allowMiss(
        packageName: String?,
        ownPackage: String,
        settings: UiVoiceSettings,
        coreAllowed: Set<String>,
        inputMethods: Set<String> = emptySet(),
        lookup: (String) -> InstalledApp? = { null },
    ): ScopeMiss? {
        scopeMiss(packageName, ownPackage, settings, coreAllowed, inputMethods)?.let { return it }
        val app = try { lookup(packageName!!) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } ?: return ScopeMiss.LABEL_UNKNOWN
        if (app.packageName != packageName || app.label.isBlank() || app.label.trim() == packageName) return ScopeMiss.LABEL_UNKNOWN
        return if (SensitiveAppRules.looksSensitive(app)) ScopeMiss.SENSITIVE else null
    }

    fun allows(
        packageName: String?,
        ownPackage: String,
        settings: UiVoiceSettings,
        coreAllowed: Set<String>,
        inputMethods: Set<String> = emptySet(),
        lookup: (String) -> InstalledApp? = { null },
    ): Boolean {
        if (packageName == null || !isInScope(packageName, ownPackage, settings, coreAllowed, inputMethods)) return false
        val app = try { lookup(packageName) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } ?: return false
        // PackageManager may fall back to the package string when no label could be loaded.
        // Neither that fallback nor a picker placeholder is evidence that the name is safe.
        if (app.packageName != packageName || app.label.isBlank() || app.label.trim() == packageName) return false
        return !SensitiveAppRules.looksSensitive(app)
    }
}
