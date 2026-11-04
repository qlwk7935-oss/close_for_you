package kr.closeforyou.ui.voice.choice

import kr.closeforyou.ui.voice.UiVoiceTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kr.closeforyou.R
import kr.closeforyou.testing.mock.MockPopupFixture
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.PressResultSpeech
import kr.closeforyou.ui.voice.command.SpeakButtonState
import kr.closeforyou.ui.voice.overlay.PromptControlsState
import kr.closeforyou.ui.voice.overlay.TakeoverFocusTarget
import kr.closeforyou.ui.voice.overlay.TalkBackTakeoverPlan

/**
 * "닫을까요?" prompt body, reusable inside the future accessibility overlay.
 * Reading order = layout order: summary, choices, then [말하기] → [다시 듣기] → [그만 말하기] and the
 * status line. It never requests focus itself ([takeover] only marks where A's service may put it).
 * With the default [controls] only the choices are shown.
 */
@Composable
fun ChoicePromptContent(
    prompt: ChoicePrompt,
    onOption: (PromptOption) -> Unit,
    modifier: Modifier = Modifier,
    controls: PromptControlsState = PromptControlsState(),
    onSpeak: () -> Unit = {},
    onRepeat: () -> Unit = {},
    onStopSpeaking: () -> Unit = {},
    /**
     * REVALIDATING: the summary and its extra lines give way to one "다시 확인하고 있어요." line
     * (liveRegion, said once as it appears) and every button is disabled. The buttons stay in place
     * so a TalkBack focus on one of them is not dropped (device unverified). paneTitle is unchanged.
     */
    revalidating: Boolean = false,
    /**
     * TalkBack takeover ([TalkBackTakeoverFeature]): no paneTitle (TalkBack would announce the window
     * over our reading), and the summary and the landing button carry ids so A's service can put
     * TalkBack's focus on them. Fixed for the window's life: nothing here changes while we speak.
     */
    takeover: Boolean = false,
) {
    val paneTitle = stringResource(R.string.ui_choice_pane_title)
    val buttonIndex = if (takeover) TalkBackTakeoverPlan.buttonIndex(prompt) else null
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                if (takeover) testTagsAsResourceId = true else this.paneTitle = paneTitle
            },
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (revalidating) {
                Text(
                    text = stringResource(R.string.ui_revalidate_checking),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                // F13 (one voice): with the takeover and no popup text for our TTS, the first item is the
                // summary, a notice's first words and the question, merged into one node TalkBack reads.
                val oneVoice = takeover && TalkBackTakeoverPlan.oneVoice(prompt)
                Column(
                    modifier = if (oneVoice) {
                        Modifier.semantics(mergeDescendants = true) {}.testTag(TakeoverFocusTarget.FIRST_ITEM.tag)
                    } else {
                        Modifier
                    },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(prompt.summary.textRes()),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = if (takeover && !oneVoice) Modifier.testTag(TakeoverFocusTarget.FIRST_ITEM.tag) else Modifier,
                    )
                    prompt.shownExcerpt?.let { Text(text = it) }
                    if (oneVoice) {
                        Text(text = stringResource(if (prompt.briefQuestion) R.string.ui_choice_takeover_question_short else R.string.ui_choice_takeover_question))
                    }
                }
                val countdown = prompt.countdownSeconds
                if (countdown != null) {
                    Text(text = stringResource(R.string.ui_choice_video_ad_wait, countdown))
                } else if (prompt.cannotPressForUser) {
                    Text(text = stringResource(R.string.ui_choice_cannot_press))
                }
                prompt.pressFailure?.let { failure ->
                    Text(text = stringResource(PressResultSpeech.reasonRes(failure)))
                }
            }
            val enabled = !revalidating
            // Two or more press buttons keep their own names, so they are never both "'닫기' 버튼".
            // F21 (LG 11/1: after a failed close, the lone "모바일 웹으로 볼래요" read "'닫기' 버튼 눌러 주기" and
            // sounded like the button that had just failed): the failure prompt always says each button's own name.
            val pressButtonCount = prompt.options.count { it.kind == PromptOptionKind.PRESS_CANDIDATE }
                .let { if (prompt.summary == PromptSummary.AUTO_CLOSE_FAILED) maxOf(it, 2) else it }
            prompt.options.forEachIndexed { index, option ->
                val label = option.label(pressButtonCount)
                val buttonModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).then(
                    if (index == buttonIndex) Modifier.testTag(TakeoverFocusTarget.CHOICE_BUTTON.tag) else Modifier,
                )
                if (option.kind == PromptOptionKind.PRESS_CANDIDATE) {
                    Button(onClick = { onOption(option) }, modifier = buttonModifier, enabled = enabled) { Text(label) }
                } else {
                    OutlinedButton(onClick = { onOption(option) }, modifier = buttonModifier, enabled = enabled) { Text(label) }
                }
            }
            PromptControls(if (revalidating) controls.copy(enabled = false, messageRes = null) else controls, onSpeak, onRepeat, onStopSpeaking)
        }
    }
}

/**
 * The visible words are the TalkBack name (no separate contentDescription), so what TalkBack reads
 * matches the screen. No command words ("닫아" …) in any label or status: TalkBack speaking them
 * while the microphone is open could be heard as a command.
 */
@Composable
private fun PromptControls(
    controls: PromptControlsState,
    onSpeak: () -> Unit,
    onRepeat: () -> Unit,
    onStopSpeaking: () -> Unit,
) {
    val buttonModifier = Modifier.heightIn(min = 48.dp)
    if (controls.speakVisible) {
        val listeningState = stringResource(R.string.ui_voice_listening_state)
        OutlinedButton(
            onClick = onSpeak,
            enabled = controls.enabled,
            modifier = buttonModifier.fillMaxWidth().semantics {
                if (controls.listening) stateDescription = listeningState
            },
        ) {
            Text(stringResource(if (controls.listening) R.string.ui_voice_stop_listening else R.string.ui_voice_speak))
        }
    }
    if (controls.speechButtons) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRepeat, modifier = buttonModifier.weight(1f), enabled = controls.enabled) {
                Text(stringResource(R.string.ui_choice_repeat))
            }
            OutlinedButton(onClick = onStopSpeaking, modifier = buttonModifier.weight(1f), enabled = controls.enabled) {
                Text(stringResource(R.string.ui_choice_stop_speaking))
            }
        }
    }
    // Status line and hint exist only while they have words: an empty Text would still be a node
    // TalkBack can land on. The Row above has no semantics, so it is not a node either (LG check 10/23).
    // F38: a sentence our TTS already said keeps no liveRegion, so TalkBack does not read it twice.
    controls.messageRes?.let {
        Text(
            text = stringResource(it),
            modifier = if (controls.messageSpoken) Modifier else Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    controls.hintRes?.let {
        Text(text = stringResource(it), style = MaterialTheme.typography.bodySmall)
    }
}

internal fun PromptSummary.textRes(): Int = when (this) {
    PromptSummary.LIKELY_AD -> R.string.ui_choice_summary_ad
    PromptSummary.LIKELY_VIDEO_AD -> R.string.ui_choice_summary_video_ad
    PromptSummary.LIKELY_NOTICE -> R.string.ui_choice_summary_notice
    PromptSummary.LIKELY_COUPON -> R.string.ui_choice_summary_coupon
    PromptSummary.NEEDS_REVIEW -> R.string.ui_choice_summary_review
    PromptSummary.UNKNOWN -> R.string.ui_choice_summary_unknown
    PromptSummary.AUTO_CLOSE_FAILED -> R.string.ui_choice_summary_auto_failed
    PromptSummary.SUSPICIOUS -> R.string.ui_choice_summary_suspicious
    PromptSummary.POPUPS_OVERLAP -> R.string.ui_choice_summary_overlap
    PromptSummary.POPUPS_OVERLAP_NO_MOVE -> R.string.ui_choice_summary_overlap_no_move
}

@Composable
private fun PromptOption.label(pressButtonCount: Int): String = when (kind) {
    PromptOptionKind.PRESS_CANDIDATE -> stringResource(R.string.ui_choice_press_candidate, shownName(pressButtonCount))
    PromptOptionKind.REMEMBER_ALWAYS -> stringResource(R.string.ui_choice_remember_always, shownName(pressButtonCount))
    PromptOptionKind.MOVE_TO_BUTTON -> labelRes?.let { stringResource(it) } ?: stringResource(R.string.ui_choice_move_to_button, buttonLabel.orEmpty())
    PromptOptionKind.READ_CONTENT -> stringResource(R.string.ui_choice_read_content)
    PromptOptionKind.KEEP_OPEN -> stringResource(R.string.ui_choice_keep_open)
    PromptOptionKind.STOP_ASKING -> stringResource(R.string.ui_choice_stop_asking)
}

/** A lone long or indirect close/skip label is shown by its role ([ButtonNameDisplay]). */
@Composable
private fun PromptOption.shownName(pressButtonCount: Int): String =
    ButtonNameDisplay.roleNameRes(buttonLabel, candidateRole, pressButtonCount)?.let { stringResource(it) }
        ?: buttonLabel.orEmpty()

/** Previews use the fixture's expected answer as a stand-in for B's classifier. */
@Composable
private fun FixturePreview(fixture: MockPopupFixture, controls: PromptControlsState = PREVIEW_CONTROLS) {
    UiVoiceTheme {
        ChoicePromptContent(
            prompt = ChoicePromptPlanner.plan(fixture.analysis, fixture.expectedDisplayClassification),
            onOption = {},
            modifier = Modifier.padding(16.dp),
            controls = controls,
        )
    }
}

private val PREVIEW_CONTROLS = PromptControlsState(
    speakButton = SpeakButtonState.SHOWN,
    speechButtons = true,
)

@Preview(name = "광고 · 듣는 중", showBackground = true)
@Composable
private fun ListeningPreview() = FixturePreview(
    MockPopupFixtures.advertisement(),
    PREVIEW_CONTROLS.copy(listening = true, messageRes = R.string.ui_voice_listening),
)

@Preview(name = "광고 · 마이크 허용 전", showBackground = true)
@Composable
private fun PermissionNeededPreview() = FixturePreview(
    MockPopupFixtures.advertisement(),
    PREVIEW_CONTROLS.copy(speakButton = SpeakButtonState.PERMISSION_NEEDED),
)

@Preview(name = "광고", showBackground = true)
@Composable
private fun AdvertisementPreview() = FixturePreview(MockPopupFixtures.advertisement())

@Preview(name = "공지", showBackground = true)
@Composable
private fun NoticePreview() = FixturePreview(MockPopupFixtures.notice())

@Preview(name = "중요 결정", showBackground = true)
@Composable
private fun DecisionPreview() = FixturePreview(MockPopupFixtures.decision())

@Preview(name = "라벨 없는 버튼", showBackground = true)
@Composable
private fun UnlabeledPreview() = FixturePreview(MockPopupFixtures.unlabeledButton())
