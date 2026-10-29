package kr.closeforyou.ui.voice.settings

import android.content.pm.PackageManager
import android.os.Build

/**
 * Looks up the visible app name for one package. Null when the name cannot be read.
 * No QUERY_ALL_PACKAGES (A, 10/19): only packages this app can see resolve; others are shown as
 * "알 수 없는 앱" ([AppNames]). App names are never logged.
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

/**
 * Pure display rules, JVM-tested. A package name is never shown or read out (10/30): an app whose
 * name can not be read (not installed, hidden by Android 11+ package visibility, blank) is
 * [unknown] ("알 수 없는 앱").
 */
object AppNames {
    fun displayName(packageName: String, source: AppLabelSource, unknown: String): String =
        source.labelOf(packageName)?.trim()?.takeIf { it.isNotEmpty() } ?: unknown

    /**
     * Names for several packages. Two packages with the same shown name get a number ("메모 1",
     * "메모 2", in package order so it stays the same), so the person can tell the rows apart by ear.
     */
    fun displayNames(
        packageNames: Collection<String>,
        source: AppLabelSource,
        unknown: String,
        numbered: (String, Int) -> String = { name, n -> "$name $n" },
    ): Map<String, String> {
        val names = packageNames.distinct().sorted().associateWith { displayName(it, source, unknown) }
        val counters = HashMap<String, Int>()
        val duplicated = names.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return names.mapValues { (_, name) ->
            if (name in duplicated) numbered(name, counters.merge(name, 1, Int::plus)!!) else name
        }
    }
}
