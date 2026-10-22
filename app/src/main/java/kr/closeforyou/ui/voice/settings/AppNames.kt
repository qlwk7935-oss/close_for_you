package kr.closeforyou.ui.voice.settings

import android.content.pm.PackageManager
import android.os.Build

/**
 * Looks up the visible app name for one package. Null when the name cannot be read.
 * No QUERY_ALL_PACKAGES and no `<queries>` (A, 10/19): only packages this app can already see
 * resolve; others fall back to the package name. App names are never logged.
 */
fun interface AppLabelSource {
    fun labelOf(packageName: String): String?
}

/**
 * Android source. On API 30+ a package hidden by package visibility throws NameNotFoundException
 * like an uninstalled one, so both end as null. Which allowed apps are visible to an accessibility
 * service without `<queries>` is not verified on a device.
 */
class PackageManagerAppLabelSource(private val packageManager: PackageManager) : AppLabelSource {
    override fun labelOf(packageName: String): String? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
        packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

/** Pure display rules, JVM-tested. */
object AppNames {
    /** App name, or the package name when it is unknown or blank. */
    fun displayName(packageName: String, source: AppLabelSource): String =
        source.labelOf(packageName)?.trim()?.takeIf { it.isNotEmpty() } ?: packageName

    /**
     * Names for several packages. Two packages with the same app name get the package name added,
     * so the person can tell the switches apart by ear.
     */
    fun displayNames(packageNames: Collection<String>, source: AppLabelSource): Map<String, String> {
        val names = packageNames.distinct().associateWith { displayName(it, source) }
        val duplicated = names.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return names.mapValues { (pkg, name) -> if (name in duplicated && name != pkg) "$name ($pkg)" else name }
    }
}
