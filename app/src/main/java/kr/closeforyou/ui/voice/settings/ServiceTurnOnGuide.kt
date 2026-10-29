package kr.closeforyou.ui.voice.settings

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kr.closeforyou.R
import kr.closeforyou.ui.voice.AndroidTtsEngine
import kr.closeforyou.ui.voice.SpeechEngine
import kr.closeforyou.ui.voice.SpeechReadiness
import kr.closeforyou.ui.voice.overlay.TalkBackTakeoverFeature

/** When the guide or "켜졌어요" is also spoken by our TTS (pure, JVM-tested). */
enum class GuideSpeech { NOW, AFTER_TALKBACK, NONE }

object ServiceGuidePolicy {
    /**
     * The guide dialog: TalkBack off → our TTS reads it at once. TalkBack on → TalkBack reads the
     * dialog title itself; our TTS reads the steps after it only when the person left
     * "선택 창이 뜨면 안내를 맡기기" on (same consent as the takeover, 6-0), otherwise TalkBack alone.
     */
    fun guide(touchExplorationOn: Boolean, takeoverOn: Boolean): GuideSpeech = when {
        !touchExplorationOn -> GuideSpeech.NOW
        TalkBackTakeoverFeature.enabled && takeoverOn -> GuideSpeech.AFTER_TALKBACK
        else -> GuideSpeech.NONE
    }

    /** Back from settings with the service on: TalkBack reads the state box (live region) itself. */
    fun turnedOn(touchExplorationOn: Boolean): GuideSpeech = if (touchExplorationOn) GuideSpeech.NONE else GuideSpeech.NOW

    /** Waits this long for TTS to get ready, and lets TalkBack read the dialog title first. */
    const val READY_TIMEOUT_MILLIS = 3_000L
    const val AFTER_TALKBACK_MILLIS = TalkBackTakeoverFeature.SPEECH_DELAY_MILLIS
}

/**
 * [서비스 켜기] with directions (TalkBack users found it hard to search the accessibility list):
 * 1. a dialog says where to go — find the app's name (A's `app_name`, so it follows a rename),
 *    double-tap it, turn it on, press 허용, come back — and our TTS reads it ([ServiceGuidePolicy]);
 * 2. [설정 열기] opens system accessibility settings asking it to scroll to our service
 *    ([AndroidCoreSetupStatus.openServiceSettings]); devices that ignore that show the list;
 * 3. back with the service on: "켜졌어요" (TTS when TalkBack is off; TalkBack reads the live state box).
 * Returns what the [서비스 켜기] button calls. Focus is never moved; the dialog is a system dialog.
 */
@Composable
fun rememberServiceTurnOn(serviceOn: Boolean, onOpenFailed: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var awaitingReturn by rememberSaveable { mutableStateOf(false) }
    // Created only when something is spoken; shut down with the screen.
    val engineHolder = remember { arrayOfNulls<SpeechEngine>(1) }
    fun engine(): SpeechEngine =
        engineHolder[0] ?: AndroidTtsEngine(context, SpeechRates.provider(context)).also { engineHolder[0] = it }
    DisposableEffect(Unit) { onDispose { engineHolder[0]?.shutdown() } }
    val talkBackOn = {
        context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }
    val currentOpenFailed by rememberUpdatedState(onOpenFailed)

    val appName = stringResource(R.string.app_name)
    val lines = listOf(
        stringResource(R.string.ui_setup_guide_find, appName),
        stringResource(R.string.ui_setup_guide_turn_on),
        stringResource(R.string.ui_setup_guide_come_back),
        stringResource(R.string.ui_setup_guide_where),
    )
    val turnedOnLine = stringResource(R.string.ui_setup_service_turned_on)

    if (showGuide) {
        LaunchedEffect(Unit) {
            val mode = ServiceGuidePolicy.guide(talkBackOn(), UiVoicePreferences.get(context).current().talkBackTakeover)
            if (mode == GuideSpeech.NONE) return@LaunchedEffect
            if (mode == GuideSpeech.AFTER_TALKBACK) delay(ServiceGuidePolicy.AFTER_TALKBACK_MILLIS)
            speakWhenReady(engine(), lines.joinToString("\n"))
        }
        AlertDialog(
            onDismissRequest = {
                engineHolder[0]?.stop()
                showGuide = false
            },
            title = { Text(stringResource(R.string.ui_setup_guide_title), modifier = Modifier.semantics { heading() }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    lines.forEach { Text(it, fontSize = UiType.body) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    engineHolder[0]?.stop()
                    showGuide = false
                    if (AndroidCoreSetupStatus.openServiceSettings(context)) awaitingReturn = true else currentOpenFailed()
                }) { Text(stringResource(R.string.ui_setup_guide_open), fontSize = UiType.body) }
            },
            dismissButton = {
                TextButton(onClick = {
                    engineHolder[0]?.stop()
                    showGuide = false
                }) { Text(stringResource(R.string.ui_settings_cancel), fontSize = UiType.body) }
            },
        )
    }

    LaunchedEffect(serviceOn, awaitingReturn) {
        if (!awaitingReturn || !serviceOn) return@LaunchedEffect
        awaitingReturn = false
        if (ServiceGuidePolicy.turnedOn(talkBackOn()) == GuideSpeech.NOW) speakWhenReady(engine(), turnedOnLine)
    }

    return { showGuide = true }
}

private suspend fun speakWhenReady(engine: SpeechEngine, text: String) {
    withTimeoutOrNull(ServiceGuidePolicy.READY_TIMEOUT_MILLIS) {
        engine.readiness.first { it != SpeechReadiness.INITIALIZING }
    }
    engine.speak(text)
}
