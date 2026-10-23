package kr.closeforyou.ui.voice.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kr.closeforyou.R
import kr.closeforyou.ui.voice.UiVoiceTheme

/** Which screen the app's first screen shows. Pure, so the gate is tested on the JVM. */
object OnboardingGatePolicy {
    fun showOnboarding(settings: UiVoiceSettings): Boolean = !settings.onboardingDone
}

/**
 * For A's first screen: the first-run setup until [시작하기] is pressed once, then [content].
 * e.g. `UiVoiceOnboardingGate { CoreSetupScreen(...) }`. Nothing is shown over another app.
 */
@Composable
fun UiVoiceOnboardingGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { UiVoicePreferences.get(context) }
    val settings by preferences.state.collectAsState()
    if (OnboardingGatePolicy.showOnboarding(settings)) {
        UiVoiceOnboardingScreen(
            initialPreset = settings.handlingPreset,
            onStart = preferences::completeOnboarding,
            modifier = modifier,
        )
    } else {
        content()
    }
}

/**
 * First-run setup: short explanation → the preset radio group → fixed lines → [시작하기].
 * The choice is saved only on [시작하기]; before that, rotation keeps it ([rememberSaveable]).
 * Reading order = layout order and focus is never moved, so TalkBack starts wherever it is.
 */
@Composable
fun UiVoiceOnboardingScreen(
    initialPreset: HandlingPreset,
    onStart: (HandlingPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var chosen by rememberSaveable { mutableStateOf(initialPreset) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.ui_onboarding_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.ui_onboarding_intro))
        HorizontalDivider()
        Text(
            stringResource(R.string.ui_preset_section),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        HandlingPresetGroup(selected = chosen, onSelect = { chosen = it })
        Text(stringResource(R.string.ui_onboarding_later), style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { onStart(chosen) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.ui_onboarding_start)) }
    }
}

@Preview(name = "첫 실행", showBackground = true, heightDp = 900)
@Composable
private fun OnboardingPreview() {
    UiVoiceTheme { UiVoiceOnboardingScreen(initialPreset = HandlingPreset.DEFAULT, onStart = {}) }
}
