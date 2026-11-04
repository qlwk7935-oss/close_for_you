package kr.closeforyou.ui.voice.command

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer

/**
 * [말하기] on the "닫을까요?" prompt. On by default; [kr.closeforyou.ui.voice.settings.UiVoiceSettings]
 * lets the person hide it. Still hidden whenever [SpeakButtonPolicy] says the microphone cannot be used.
 */
object VoiceButtonFeature {
    val enabled: Boolean get() = true
}

/** What C needs to know about the microphone, behind an interface so the policy is JVM tested. */
interface MicrophoneAccess {
    /** The person allowed RECORD_AUDIO. */
    fun granted(): Boolean

    /** API 31+ and the device has an on-device recognizer (never a remote one). */
    fun onDeviceRecognizerAvailable(): Boolean
}

class AndroidMicrophoneAccess(context: Context) : MicrophoneAccess {
    private val appContext = context.applicationContext

    override fun granted(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onDeviceRecognizerAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
}

/** Why [말하기] is or is not on the prompt. Also drives the settings screen's microphone line. */
enum class SpeakButtonState {
    SHOWN,

    /** The feature flag or the person's voice command setting is off: hidden, nothing said. */
    TURNED_OFF,

    /** Not allowed: hidden on the prompt with one short hint; allowed from settings. */
    PERMISSION_NEEDED,

    /** No on-device recognizer (for example the LG test phone): hidden, we never fall back to a server. */
    DEVICE_UNSUPPORTED,
}

object SpeakButtonPolicy {
    fun state(featureEnabled: Boolean, settingEnabled: Boolean, microphone: MicrophoneAccess): SpeakButtonState = when {
        !featureEnabled || !settingEnabled -> SpeakButtonState.TURNED_OFF
        !microphone.onDeviceRecognizerAvailable() -> SpeakButtonState.DEVICE_UNSUPPORTED
        !microphone.granted() -> SpeakButtonState.PERMISSION_NEEDED
        else -> SpeakButtonState.SHOWN
    }
}
