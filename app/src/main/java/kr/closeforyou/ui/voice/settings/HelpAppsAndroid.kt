package kr.closeforyou.ui.voice.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.closeforyou.app.CorePreferences

/**
 * Launcher apps (MAIN/LAUNCHER). On Android 11+ this needs the matching `<queries>` intent in the
 * merged manifest: the debug manifest has it; until A adds it to main, release builds see an
 * almost empty list and the picker says it could not load the apps. No QUERY_ALL_PACKAGES.
 * Call off the main thread. App names stay in memory only.
 */
class LauncherAppSource(private val packageManager: PackageManager) : InstalledAppSource {
    override fun launchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }
        } catch (_: SecurityException) {
            emptyList()
        }
        return activities.mapNotNull { info ->
            val app = info.activityInfo?.applicationInfo ?: return@mapNotNull null
            InstalledApp(app.packageName, packageManager.getApplicationLabel(app).toString())
        }
    }
}

/** A's list through its public functions only (A's code is not changed). */
class CoreAllowedApps(private val core: CorePreferences) : AllowedAppsTarget {
    override val consentGranted: Boolean get() = core.currentSettings().consentGranted
    override val allowed: Set<String> get() = core.currentSettings().allowedPackages
    override fun add(packageName: String): Boolean = core.addAllowedPackage(packageName)
    override fun remove(packageName: String) = core.removeAllowedPackage(packageName)
}

/**
 * Placeholder only (A's 10/24 plan): when A adds "분석 범위 모드 + 제외 목록" to `CorePreferences`,
 * it is wired in this shape. [HelpAppsSync.save]'s [HelpAppsMode.ALL] path then calls
 * `setAllApps(excluded)` once instead of `addAllowedPackage` per app, and A includes newly
 * installed apps itself. excluded = [AppCatalog.sensitive] + [UiVoiceSettings.helpAppsExcluded].
 * No implementation yet; nothing calls it.
 */
interface AnalysisScopeTarget {
    val consentGranted: Boolean
    /** Every app except [excluded]; false before consent. */
    fun setAllApps(excluded: Set<String>): Boolean
    /** Only [packages] (today's allowed list). */
    fun setChosenApps(packages: Set<String>): Boolean
}

/**
 * Saves "도와줄 앱" and keeps the additions A could not take yet. [lastOutcome] holds only counts,
 * in memory, for the settings screen's result line.
 */
object HelpAppsSync {
    private val mutableOutcome = MutableStateFlow<HelpAppsOutcome?>(null)
    val lastOutcome: StateFlow<HelpAppsOutcome?> = mutableOutcome.asStateFlow()

    /** [draft] must have a way chosen ([HelpAppsPlan.canSave]); otherwise nothing is changed. */
    fun save(draft: HelpAppsDraft, catalog: AppCatalog, preferences: UiVoicePreferences, target: AllowedAppsTarget): HelpAppsOutcome {
        val mode = draft.mode ?: return HelpAppsOutcome()
        // TODO(A 범위 API): mode == ALL → AnalysisScopeTarget.setAllApps(...) once A has it. Today: one add per app.
        val change = HelpAppsPlan.change(draft, catalog, target.allowed)
        // A new save replaces older pending additions, except those the new draft still wants.
        val stillWanted = preferences.current().pendingHelpPackages intersect HelpAppsPlan.target(draft, catalog)
        val result = HelpAppsApplier.apply(change.copy(add = change.add + stillWanted), target)
        preferences.setHelpApps(mode, draft.userExcluded intersect catalog.helpablePackages, result.stillPending)
        mutableOutcome.value = result.outcome
        return result.outcome
    }

    /** After A's consent, or when settings open. Does nothing while consent is missing. */
    fun retryPending(preferences: UiVoicePreferences, target: AllowedAppsTarget) {
        val pending = preferences.current().pendingHelpPackages
        if (pending.isEmpty() || !target.consentGranted) return
        val result = HelpAppsApplier.applyPending(pending, target)
        preferences.setPendingHelpPackages(result.stillPending)
        mutableOutcome.value = result.outcome
    }
}
