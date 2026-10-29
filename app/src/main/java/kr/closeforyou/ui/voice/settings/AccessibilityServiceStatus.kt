package kr.closeforyou.ui.voice.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import kr.closeforyou.app.CorePreferences

/**
 * What C's first-run and settings screens read from A, read only. C never turns the service on,
 * gives A's collection consent or starts A's analysis: the person does that in system settings
 * and in A's "기본 설정". The default [NONE] is the safe side (off, no consent).
 * Android: [AndroidCoreSetupStatus]. How A wires its own source: docs/c-notes/onboarding-release.md.
 */
interface CoreSetupStatus {
    /** A's accessibility service is turned on in system settings. Re-read on every resume. */
    fun serviceEnabled(): Boolean

    /** A's "화면 정보 수집" consent; "도와줄 앱" additions wait for it ([HelpAppsSync]). */
    fun collectionConsentGranted(): Boolean

    companion object {
        val NONE = object : CoreSetupStatus {
            override fun serviceEnabled() = false
            override fun collectionConsentGranted() = false
        }
    }
}

/**
 * Two public, read-only sources (AOSP 36 source checked):
 * 1. `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)`: the services the
 *    system has enabled for this user (A's MainActivity uses the same list).
 * 2. `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` (`@Readable`): the "pkg/cls:pkg/cls" switch
 *    list, for the moment right after the switch when the list above may not have it yet.
 * Either one counts as on. Package and class names are never logged.
 */
class AndroidCoreSetupStatus(context: Context) : CoreSetupStatus {
    private val app = context.applicationContext
    private val core = CorePreferences.get(app)

    override fun serviceEnabled(): Boolean {
        val service = ServiceComponent(app.packageName, A_SERVICE_CLASS)
        val manager = app.getSystemService(AccessibilityManager::class.java)
        val listed = manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .orEmpty()
            .mapNotNull { info -> info.resolveInfo?.serviceInfo?.let { ServiceComponent(it.packageName, it.name) } }
        val secure = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return AccessibilityServiceRules.isEnabled(service, listed, secure)
    }

    override fun collectionConsentGranted(): Boolean = core.currentSettings().consentGranted

    companion object {
        /** A's service (manifest `android:name`). A string, so C does not depend on A's class. */
        const val A_SERVICE_CLASS = "kr.closeforyou.accessibility.CloseForYouAccessibilityService"

        /**
         * The system's accessibility settings, where the person turns the service on. False when no
         * screen handles it (some devices); the caller then says where to find it by hand.
         */
        fun openServiceSettings(context: Context): Boolean = try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

/** A service's package and class, without android.content.ComponentName so it is JVM-testable. */
data class ServiceComponent(val packageName: String, val className: String)

/** Pure matching rules, JVM-tested. */
object AccessibilityServiceRules {
    fun isEnabled(service: ServiceComponent, enabledList: List<ServiceComponent>, secureSetting: String?): Boolean =
        service in enabledList || inSecureSetting(service, secureSetting)

    /**
     * `ENABLED_ACCESSIBILITY_SERVICES` is "pkg/cls" entries joined by ':'; a class may be written
     * short (".cls" = pkg + ".cls"), as ComponentName.flattenToShortString does.
     */
    fun inSecureSetting(service: ServiceComponent, secureSetting: String?): Boolean =
        secureSetting.orEmpty().split(':').any { entry ->
            val slash = entry.indexOf('/')
            if (slash <= 0) return@any false
            val pkg = entry.substring(0, slash).trim()
            val cls = entry.substring(slash + 1).trim().let { if (it.startsWith('.')) pkg + it else it }
            pkg == service.packageName && cls == service.className
        }
}
