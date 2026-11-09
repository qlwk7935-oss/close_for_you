package kr.closeforyou.ui.voice.choice

import kr.closeforyou.ui.voice.UiVoiceTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kr.closeforyou.ui.voice.theme.GuideBox
import kr.closeforyou.ui.voice.theme.InkButton
import kr.closeforyou.ui.voice.theme.InkGrade
import kr.closeforyou.ui.voice.theme.InkIcon
import kr.closeforyou.ui.voice.theme.PopkokHeader
import kr.closeforyou.ui.voice.theme.UiVoiceDims
import kr.closeforyou.ui.voice.theme.UiVoiceType
import kr.closeforyou.ui.voice.theme.inkShadow
import kr.closeforyou.ui.voice.theme.uiVoicePalette
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
    val palette = uiVoicePalette()
    // F63: a white card with a 2dp ink line and a 6dp bottom edge. Same Surface and semantics as before.
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                if (takeover) testTagsAsResourceId = true else this.paneTitle = paneTitle
            }
            .inkShadow(palette.ink, UiVoiceDims.cardRadius, UiVoiceDims.cardBottom - UiVoiceDims.line),
        shape = RoundedCornerShape(UiVoiceDims.cardRadius),
        color = palette.card,
        contentColor = palette.ink,
        border = BorderStroke(UiVoiceDims.line, palette.ink),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (revalidating) {
                PopkokHeader(UiVoiceDims.promptHeaderIcon) {
                    Text(
                        text = stringResource(R.string.ui_revalidate_checking),
                        fontSize = SUMMARY_SIZE,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
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
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Head: 팝콕이 and "팝업처리도우미" are hidden from TalkBack; the summary reads as before.
                    PopkokHeader(UiVoiceDims.promptHeaderIcon) {
                        Text(
                            text = stringResource(prompt.summary.textRes()),
                            fontSize = SUMMARY_SIZE,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 26.sp,
                            modifier = if (takeover && !oneVoice) Modifier.testTag(TakeoverFocusTarget.FIRST_ITEM.tag) else Modifier,
                        )
                    }
                    val excerpt = prompt.shownExcerpt
                    if (excerpt != null || oneVoice) {
                        GuideBox {
                            excerpt?.let { Text(text = it) }
                            if (oneVoice) {
                                Text(text = stringResource(ChoicePromptText.questionRes(prompt, stringResource(R.string.ui_simple_x_button))))
                            }
                        }
                    }
                }
                val countdown = prompt.countdownSeconds
                val extraLine = when {
                    countdown != null -> stringResource(R.string.ui_choice_video_ad_wait, countdown)
                    prompt.cannotPressForUser -> stringResource(R.string.ui_choice_cannot_press)
                    else -> null
                }
                val failureLine = prompt.pressFailure?.let { stringResource(PressResultSpeech.reasonRes(it)) }
                if (extraLine != null || failureLine != null) {
                    // Each line stays its own node, in the old order (the box has no semantics).
                    GuideBox {
                        extraLine?.let { Text(text = it) }
                        failureLine?.let { Text(text = it) }
                    }
                }
            }
            val enabled = !revalidating
            // Two or more press buttons keep their own names, so they are never both "'닫기' 버튼".
            // F21 (LG 11/1: after a failed close, the lone "모바일 웹으로 볼래요" read "'닫기' 버튼 눌러 주기" and
            // sounded like the button that had just failed): the failure prompt always says each button's own name.
            val pressButtonCount = prompt.options.count { it.kind == PromptOptionKind.PRESS_CANDIDATE }
                .let { if (prompt.summary == PromptSummary.AUTO_CLOSE_FAILED) maxOf(it, 2) else it }
            @Composable
            fun OptionButton(index: Int, option: PromptOption, rowModifier: Modifier) {
                val buttonModifier = rowModifier.then(
                    if (index == buttonIndex) Modifier.testTag(TakeoverFocusTarget.CHOICE_BUTTON.tag) else Modifier,
                )
                InkButton(option.label(pressButtonCount), { onOption(option) }, ChoicePromptLayout.gradeOf(option.kind), buttonModifier, enabled = enabled)
            }
            // F63: [내용 다시 읽기] right before [그대로 두기] share one line (left, then right: TalkBack and focus order unchanged).
            ChoicePromptLayout.rows(prompt.options).forEach { row ->
                if (row.size == 1) {
                    OptionButton(row[0], prompt.options[row[0]], Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        row.forEach { i -> OptionButton(i, prompt.options[i], Modifier.weight(1f)) }
                    }
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
    val palette = uiVoicePalette()
    if (controls.speakVisible) {
        val listeningState = stringResource(R.string.ui_voice_listening_state)
        val label = stringResource(if (controls.listening) R.string.ui_voice_stop_listening else R.string.ui_voice_speak)
        // F63: an orange round mic, the words, and while listening a "듣고 있어요" pill. The mic and the pill are drawn only
        // (no node): TalkBack still reads the label and "듣는 중" as before.
        InkButton(
            label,
            onSpeak,
            InkGrade.LINE,
            Modifier.fillMaxWidth().semantics {
                if (controls.listening) stateDescription = listeningState
            },
            enabled = controls.enabled,
        ) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(if (controls.enabled) palette.main else palette.disabledLine),
                contentAlignment = Alignment.Center,
            ) { InkIcon(R.drawable.ui_ic_mic, 18.dp, tint = palette.onMain) }
            Text(label, fontSize = UiVoiceType.button, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
            if (controls.listening) {
                Text(
                    stringResource(R.string.ui_voice_listening).trimEnd('.'),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.onSub,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .clearAndSetSemantics {}
                        .clip(RoundedCornerShape(UiVoiceDims.pillRadius))
                        .background(palette.sub)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
    if (controls.speechButtons) {
        // F63: small text links, side by side (left, then right: same order as before).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton(stringResource(R.string.ui_choice_repeat), onRepeat, InkGrade.LINK, Modifier.weight(1f), enabled = controls.enabled)
            InkButton(stringResource(R.string.ui_choice_stop_speaking), onStopSpeaking, InkGrade.LINK, Modifier.weight(1f), enabled = controls.enabled)
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
        Text(text = stringResource(it), style = MaterialTheme.typography.bodySmall, color = palette.muted)
    }
}

/** F63: how the prompt's options are drawn. Layout only: the options keep their order, labels and clicks. */
internal object ChoicePromptLayout {
    /** Main = the press; secondary = remembering it or moving to a button; outlined = the rest. */
    fun gradeOf(kind: PromptOptionKind): InkGrade = when (kind) {
        PromptOptionKind.PRESS_CANDIDATE -> InkGrade.MAIN
        PromptOptionKind.REMEMBER_ALWAYS, PromptOptionKind.MOVE_TO_BUTTON -> InkGrade.SUB
        PromptOptionKind.READ_CONTENT, PromptOptionKind.KEEP_OPEN, PromptOptionKind.STOP_ASKING -> InkGrade.LINE
    }

    /**
     * Option indexes grouped into lines, in order: [READ_CONTENT] directly followed by [KEEP_OPEN] share a line,
     * every other option has its own. Flattened, the result is always 0..lastIndex (reading and focus order unchanged).
     */
    fun rows(options: List<PromptOption>): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        var i = 0
        while (i < options.size) {
            if (options[i].kind == PromptOptionKind.READ_CONTENT && options.getOrNull(i + 1)?.kind == PromptOptionKind.KEEP_OPEN) {
                out += listOf(i, i + 1)
                i += 2
            } else {
                out += listOf(i)
                i += 1
            }
        }
        return out
    }
}

private val SUMMARY_SIZE = 19.sp

/** F63: the question at the end of the prompt's first item (TalkBack reads it there; no TTS speaks it). */
internal object ChoicePromptText {
    /**
     * "닫을까요?" for the brief prompt; when the press offered is a close button with no name ([xLabel], the engine's
     * "X 모양 닫기", or a label of icon glyphs only), "닫기 버튼에 이름이 없어서 …"; otherwise the usual "어떻게 할까요? …".
     */
    fun questionRes(prompt: ChoicePrompt, xLabel: String): Int = when {
        prompt.briefQuestion -> R.string.ui_choice_takeover_question_short
        prompt.options.any { it.kind == PromptOptionKind.PRESS_CANDIDATE && (it.buttonLabel == xLabel || ButtonNameDisplay.shown(it.buttonLabel) == null) } ->
            R.string.ui_choice_takeover_question_unnamed_x
        else -> R.string.ui_choice_takeover_question
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
    PromptSummary.POPUPS_OVERLAP_PRESS -> R.string.ui_choice_summary_overlap_press
    PromptSummary.POPUPS_OVERLAP_FRONT_SCAM -> R.string.ui_choice_summary_overlap_front_scam
    PromptSummary.POPUPS_OVERLAP_FRONT_SCAM_NO_MOVE -> R.string.ui_choice_summary_overlap_front_scam_no_move
}

@Composable
private fun PromptOption.label(pressButtonCount: Int): String = when (kind) {
    PromptOptionKind.PRESS_CANDIDATE -> stringResource(labelRes ?: R.string.ui_choice_press_candidate, shownName(pressButtonCount))
    PromptOptionKind.REMEMBER_ALWAYS -> stringResource(R.string.ui_choice_remember_always, shownName(pressButtonCount))
    PromptOptionKind.MOVE_TO_BUTTON -> labelRes?.let { stringResource(it) } ?: stringResource(R.string.ui_choice_move_to_button, ButtonNameDisplay.shown(buttonLabel) ?: stringResource(R.string.ui_simple_x_button))
    PromptOptionKind.READ_CONTENT -> stringResource(labelRes ?: R.string.ui_choice_read_content)
    PromptOptionKind.KEEP_OPEN -> stringResource(R.string.ui_choice_keep_open)
    PromptOptionKind.STOP_ASKING -> stringResource(R.string.ui_choice_stop_asking)
}

/** A lone long or indirect close/skip label is shown by its role ([ButtonNameDisplay]). F61: icon glyphs dropped ([ButtonNameDisplay.shown]). */
@Composable
private fun PromptOption.shownName(pressButtonCount: Int): String {
    val name = ButtonNameDisplay.shown(buttonLabel) ?: return stringResource(R.string.ui_simple_x_button)
    return ButtonNameDisplay.roleNameRes(name, candidateRole, pressButtonCount)?.let { stringResource(it) } ?: name
}

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
