package kr.closeforyou.simpleclose

import android.content.pm.PackageManager
import android.os.Build
import kr.closeforyou.ui.voice.settings.InstalledApp
import java.util.concurrent.CancellationException

/** Current package metadata only, never event text or a cached picker entry. No logging/storage.
 * Android 11+ visibility filtering can make an installed package unavailable: that is a denial,
 * not permission to fall back to the event's package name. Main already queries launcher apps. */
internal class AndroidSimpleAppLookup(private val packages: PackageManager) {
    fun find(packageName: String): InstalledApp? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packages.getApplicationInfo(packageName, 0)
        }
        if (info.packageName != packageName || !info.enabled) null
        else {
            // loadLabel/getApplicationLabel can substitute a class/package name when the label
            // is unavailable. That substitute cannot clear the sensitive-name check.
            val label = info.nonLocalizedLabel ?: if (info.labelRes != 0) {
                packages.getText(packageName, info.labelRes, info)
            } else null
            label?.let { InstalledApp(info.packageName, it.toString()) }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
