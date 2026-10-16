package kr.closeforyou.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local A bootstrap settings; not D's popup-rule database or F12 implementation. */
data class CoreSettings(
    val consentGranted: Boolean = false,
    val paused: Boolean = true,
    val allowedPackages: Set<String> = emptySet(),
) {
    val collectionEnabled: Boolean get() = consentGranted && !paused
}

/** Application singleton so setting changes immediately reach both UI and service. */
class CorePreferences private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "core_privacy_settings", Context.MODE_PRIVATE,
    )
    private val mutableState = MutableStateFlow(readSettings())
    val state: StateFlow<CoreSettings> = mutableState.asStateFlow()

    // Keep a strong reference: SharedPreferences holds its listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mutableState.value = readSettings()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun currentSettings(): CoreSettings = mutableState.value

    fun updateConsent(granted: Boolean) {
        preferences.edit {
            putBoolean(KEY_CONSENT, granted)
            // Revocation also clears app permissions and requires a new deliberate start.
            if (!granted) putBoolean(KEY_PAUSED, true).remove(KEY_ALLOWED)
        }
        mutableState.value = readSettings()
    }

    fun setPaused(paused: Boolean) {
        preferences.edit { putBoolean(KEY_PAUSED, paused || !currentSettings().consentGranted) }
        mutableState.value = readSettings()
    }

    fun addAllowedPackage(packageName: String): Boolean {
        val normalized = packageName.trim()
        if (!currentSettings().consentGranted || !PACKAGE_NAME.matches(normalized)) return false
        preferences.edit { putStringSet(KEY_ALLOWED, currentSettings().allowedPackages + normalized) }
        mutableState.value = readSettings()
        return true
    }

    fun removeAllowedPackage(packageName: String) {
        preferences.edit { putStringSet(KEY_ALLOWED, currentSettings().allowedPackages - packageName) }
        mutableState.value = readSettings()
    }

    private fun readSettings() = CoreSettings(
        consentGranted = preferences.getBoolean(KEY_CONSENT, false),
        paused = preferences.getBoolean(KEY_PAUSED, true),
        allowedPackages = preferences.getStringSet(KEY_ALLOWED, emptySet()).orEmpty().toSet(),
    )

    companion object {
        private const val KEY_CONSENT = "disclosure_consent_v1"
        private const val KEY_PAUSED = "collection_paused"
        private const val KEY_ALLOWED = "allowed_packages"
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        @Volatile private var instance: CorePreferences? = null

        fun get(context: Context): CorePreferences = instance ?: synchronized(this) {
            instance ?: CorePreferences(context).also { instance = it }
        }
    }
}
