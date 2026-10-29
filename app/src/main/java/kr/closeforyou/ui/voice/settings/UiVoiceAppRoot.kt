package kr.closeforyou.ui.voice.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kr.closeforyou.ui.voice.UiVoiceTheme

/**
 * The whole first screen for A's MainActivity, in one call (integration: C, see
 * docs/c-notes/onboarding-release.md):
 * 1. first run not finished → the five-step setup ([UiVoiceOnboardingGate]);
 * 2. then C's settings as the home screen ("팝업처리도우미 설정", D's real Room rules);
 * 3. [기본 설정 열기] there opens [coreSettings] (A's screen: collection consent, start/pause, OCR),
 *    and system back returns to the home screen.
 * C's screens use [UiVoiceTheme] (follows the system dark mode). [coreSettings] keeps A's own theme
 * and Scaffold. Focus is never moved.
 */
@Composable
fun UiVoiceAppRoot(
    modifier: Modifier = Modifier,
    coreSettings: @Composable () -> Unit,
) {
    var showCore by rememberSaveable { mutableStateOf(false) }
    UiVoiceOnboardingGate(modifier) {
        if (showCore) {
            BackHandler { showCore = false }
            coreSettings()
        } else {
            UiVoiceTheme {
                Scaffold(modifier) { insets ->
                    RoomBackedUiVoiceSettingsRoute(
                        modifier = Modifier.padding(insets),
                        onOpenCoreSettings = { showCore = true },
                    )
                }
            }
        }
    }
}
