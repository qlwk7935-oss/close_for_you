package kr.closeforyou.ui.voice.settings

import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kr.closeforyou.R

/**
 * Preset radio group + the separate "자동 닫기" switch (first-run step 1 and settings).
 * - Switch on: a dialog names what the chosen preset closes; only [켜기] calls [onConsentChange] (true).
 *   Off: at once, no dialog.
 * - While on, choosing a preset that closes more kinds asks again ("범위를 넓힐까요?"); [취소] keeps
 *   the old preset. A narrower preset applies at once. [onPresetChange]'s second value says the
 *   wider range was confirmed.
 * - [selected] null (first run, nothing chosen yet): the switch is disabled and off; its help line
 *   says to choose a way first ([AutoCloseConsentPolicy.switchEnabled]).
 * Reading order: presets → fixed decision line → switch → (not live) "지금은 닫기 전에 물어봐요".
 * The dialogs are system dialogs TalkBack enters by itself. When one closes, TalkBack's focus is put
 * back on the switch ([returnFocusToTag]; LG 10/31: it jumped to "5단계 중 3단계" at the top).
 */
@Composable
fun HandlingPresetWithConsent(
    selected: HandlingPreset?,
    consentOn: Boolean,
    onPresetChange: (HandlingPreset, Boolean) -> Unit,
    onConsentChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmTurnOn by rememberSaveable { mutableStateOf(false) }
    var widenTo by rememberSaveable { mutableStateOf<HandlingPreset?>(null) }
    // Bumped when a dialog closes; the switch takes TalkBack's focus back.
    var dialogClosed by remember { mutableIntStateOf(0) }
    val view = LocalView.current
    LaunchedEffect(dialogClosed) {
        if (dialogClosed > 0) returnFocusToTag(view, CONSENT_SWITCH_TAG)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HandlingPresetGroup(
            selected = selected,
            onSelect = { preset ->
                if (AutoCloseConsentPolicy.needsReconfirm(consentOn, selected, preset)) widenTo = preset
                else onPresetChange(preset, false)
            },
            notLiveLine = false,
        )
        ConsentSwitchRow(
            checked = consentOn,
            enabled = AutoCloseConsentPolicy.switchEnabled(selected),
            onCheckedChange = { on -> if (on) confirmTurnOn = true else onConsentChange(false) },
        )
        if (!PresetAutoCloseFeature.live) HelpText(stringResource(R.string.ui_preset_not_live))
    }

    if (confirmTurnOn && selected != null) {
        ConsentDialog(
            titleRes = R.string.ui_preset_consent_title,
            preset = selected,
            confirmRes = R.string.ui_preset_consent_turn_on,
            onConfirm = {
                confirmTurnOn = false
                onConsentChange(true)
                dialogClosed++
            },
            onDismiss = {
                confirmTurnOn = false
                dialogClosed++
            },
        )
    }
    widenTo?.let { preset ->
        ConsentDialog(
            titleRes = R.string.ui_preset_consent_widen_title,
            preset = preset,
            confirmRes = R.string.ui_preset_consent_widen,
            onConfirm = {
                widenTo = null
                onPresetChange(preset, true)
                dialogClosed++
            },
            onDismiss = {
                widenTo = null
                dialogClosed++
            },
        )
    }
}

/** One toggleable card ([SwitchCard]): label, help line, "스위치", 켜짐/꺼짐 (disabled: "사용 중지됨"). */
@Composable
private fun ConsentSwitchRow(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchCard(
        label = stringResource(R.string.ui_preset_consent_switch),
        help = stringResource(ConsentText.switchHelpRes(checked, enabled)),
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        tag = CONSENT_SWITCH_TAG,
    )
}

private const val CONSENT_SWITCH_TAG = "ui_auto_close_consent_switch"

/**
 * After one of our dialogs closes, TalkBack puts its focus on the window's first item. Puts it back
 * on the tagged node of [view] (the Compose view; its provider's virtual ids are semantics ids, as in
 * [OwnWindowFocus]): once after TalkBack's own refocus, once more if that was lost. Accessibility
 * focus only, and only while TalkBack (touch exploration) is on. Demo mode 6-0 (restores where the
 * person was). Device check needed for the timing.
 */
internal suspend fun returnFocusToTag(view: View, tag: String) {
    val manager = view.context.getSystemService(AccessibilityManager::class.java)
    if (manager?.isTouchExplorationEnabled != true) return
    delay(RETURN_FOCUS_FIRST_MILLIS)
    focusTagged(view, tag, move = true)
    delay(RETURN_FOCUS_RECHECK_MILLIS)
    if (focusTagged(view, tag, move = false) == false) focusTagged(view, tag, move = true)
}

/** move = true: performs the focus action; false: whether it holds the focus. Null = not found. */
private fun focusTagged(view: View, tag: String, move: Boolean): Boolean? {
    val root = (view as? RootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return null
    val id = findTagged(root, tag) ?: return null
    val provider = view.accessibilityNodeProvider ?: return null
    return if (move) provider.performAction(id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    else provider.createAccessibilityNodeInfo(id)?.isAccessibilityFocused == true
}

private fun findTagged(node: SemanticsNode, tag: String): Int? {
    if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) return node.id
    return node.children.firstNotNullOfOrNull { findTagged(it, tag) }
}

private const val RETURN_FOCUS_FIRST_MILLIS = 500L
private const val RETURN_FOCUS_RECHECK_MILLIS = 700L

@Composable
private fun ConsentDialog(titleRes: Int, preset: HandlingPreset, confirmRes: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes), modifier = Modifier.semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConsentText.bodyRes(preset, PresetAutoCloseFeature.live).forEach { Text(stringResource(it)) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirmRes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_settings_cancel)) } },
    )
}

/** Dialog and switch texts, separate so a JVM test checks them per preset. */
internal object ConsentText {
    fun kindsRes(preset: HandlingPreset) = when (preset) {
        HandlingPreset.CLOSE_ADS_ONLY -> R.string.ui_preset_consent_kinds_ads
        HandlingPreset.CLOSE_ADS_AND_COUPONS -> R.string.ui_preset_consent_kinds_ads_coupons
        HandlingPreset.CLOSE_ALL -> R.string.ui_preset_consent_kinds_all
    }

    /** What the preset closes, the safety line, and (not live) that the app still asks first. */
    fun bodyRes(preset: HandlingPreset, live: Boolean): List<Int> = buildList {
        add(kindsRes(preset))
        add(R.string.ui_preset_consent_safety)
        if (!live) add(R.string.ui_preset_consent_not_live)
    }

    fun switchHelpRes(on: Boolean, enabled: Boolean = true) = when {
        !enabled -> R.string.ui_preset_consent_switch_choose_first
        on -> R.string.ui_preset_consent_switch_on_help
        else -> R.string.ui_preset_consent_switch_off_help
    }
}
