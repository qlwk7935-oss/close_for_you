package kr.closeforyou.ui.voice.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.closeforyou.R
import kr.closeforyou.ui.voice.theme.GuideBox
import kr.closeforyou.ui.voice.theme.InkButton
import kr.closeforyou.ui.voice.theme.InkGrade
import kr.closeforyou.ui.voice.theme.InkIcon
import kr.closeforyou.ui.voice.theme.InkThumbRing
import kr.closeforyou.ui.voice.theme.Popkok
import kr.closeforyou.ui.voice.theme.PopkokHeader
import kr.closeforyou.ui.voice.theme.StepBar
import kr.closeforyou.ui.voice.theme.UiVoiceDims
import kr.closeforyou.ui.voice.theme.UiVoicePalette
import kr.closeforyou.ui.voice.theme.inkRow
import kr.closeforyou.ui.voice.theme.inkSwitchColors
import kr.closeforyou.ui.voice.theme.uiVoicePalette

/**
 * Kept for older call sites and their contrast test; F63 maps it onto C's tokens ([UiVoicePalette]).
 * [accent] = text/borders that must read 4.5:1 ([UiVoicePalette.onSub]); [buttonContainer] = a background carrying
 * small white text ([UiVoicePalette.selected]); [gradientEnd] = the orange accent for drawn marks.
 */
data class UiBrandColors(val accent: Color, val buttonContainer: Color, val onButton: Color, val gradientEnd: Color) {
    companion object {
        fun of(dark: Boolean): UiBrandColors {
            val p = UiVoicePalette.of(dark)
            return UiBrandColors(accent = p.onSub, buttonContainer = p.selected, onButton = p.onSelected, gradientEnd = p.main)
        }
    }
}

@Composable
internal fun brandColors() = UiBrandColors.of(isSystemInDarkTheme())

/** Large type used by the first run and settings (sp, so the system font size still applies). */
internal object UiType {
    val title = 26.sp
    val subtitle = 21.sp
    val body = 18.sp
    val help = 16.sp
}

/** The screen head (F63): 팝콕이 + small "팝업처리도우미" (both hidden from TalkBack) + this title, read as a heading as before. */
@Composable
internal fun StepTitle(text: String) {
    val ink = uiVoicePalette().ink
    PopkokHeader {
        Text(text, fontSize = UiType.title, fontWeight = FontWeight.Bold, lineHeight = 34.sp, color = ink, modifier = Modifier.semantics { heading() })
    }
}

@Composable
internal fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = UiType.body, lineHeight = 26.sp, modifier = modifier)
}

@Composable
internal fun HelpText(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = UiType.help, lineHeight = 22.sp, color = uiVoicePalette().muted, modifier = modifier)
}

/**
 * The main button. Disabled: [disabledReason] is shown above it and read as its state, so
 * TalkBack says label, reason, "사용 중지됨" (same rule as [HelpAppsSaveButton]).
 */
@Composable
internal fun BrandButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    disabledReason: String? = null,
    modifier: Modifier = Modifier,
) {
    if (!enabled && disabledReason != null) HelpText(disabledReason)
    InkButton(
        label = label,
        onClick = onClick,
        grade = InkGrade.MAIN,
        enabled = enabled,
        minHeight = 60.dp,
        fontSize = 19.sp,
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (!enabled && disabledReason != null) stateDescription = disabledReason },
    )
}

/** "5단계 중 N단계" and a decorative five-part bar (the bar is hidden from TalkBack; the line says it). */
@Composable
internal fun StepHeader(step: OnboardingStep) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.ui_setup_step, OnboardingStep.COUNT, step.number),
            fontSize = UiType.help,
            fontWeight = FontWeight.Bold,
            color = uiVoicePalette().muted,
        )
        StepBar(done = step.number, count = OnboardingStep.COUNT)
    }
}

/** The app mark: 팝콕이. Decorative (the name is read next to it). */
@Composable
internal fun AppMark(size: Int = 112) {
    Popkok(size.dp)
}

/**
 * One radio choice as a large card. TalkBack: title, body, "선택됨"/"선택 안 됨", 라디오 버튼.
 * Put several inside a `Modifier.selectableGroup()` column.
 * F63 look: chosen = the selected orange with a check at the left end (decorative), words centred, an empty slot of the
 * same width on the right; not chosen = white with an empty ring. The selectable/state modifiers are the same as before.
 */
@Composable
internal fun ChoiceCard(title: String, body: String?, selected: Boolean, onClick: () -> Unit, note: String? = null) {
    val p = uiVoicePalette()
    val state = stringResource(if (selected) R.string.ui_setup_selected else R.string.ui_setup_not_selected)
    val titleColor = if (selected) p.onSelected else p.ink
    val lineColor = if (selected) p.onSelected else p.muted
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .inkRow(p, fill = if (selected) p.selected else p.card, radius = UiVoiceDims.buttonRadius)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { stateDescription = state }
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(CHECK_SLOT), contentAlignment = Alignment.Center) {
            if (selected) InkIcon(R.drawable.ui_ic_circle_check, CHECK_SLOT, tint = p.onSelected) else EmptyRing(p.ink)
        }
        Column(
            Modifier.weight(1f).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = titleColor, textAlign = TextAlign.Center)
            if (body != null) Text(body, fontSize = UiType.help, lineHeight = 22.sp, color = lineColor, textAlign = TextAlign.Center)
            if (note != null) Text(note, fontSize = UiType.help, lineHeight = 22.sp, color = lineColor, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.size(CHECK_SLOT))
    }
}

private val CHECK_SLOT = 28.dp

@Composable
private fun EmptyRing(color: Color) {
    Canvas(Modifier.size(22.dp)) { drawCircle(color, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx())) }
}

/** One switch in a 4dp-bottom box: one TalkBack node (label, help, 켜짐/꺼짐, 스위치; disabled "사용 중지됨"). */
@Composable
internal fun SwitchCard(
    label: String,
    help: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    /** Test tag on the toggleable row (the TalkBack stop), for [returnFocusToTag]. */
    tag: String? = null,
) {
    val p = uiVoicePalette()
    Box(Modifier.fillMaxWidth().inkRow(p)) {
        Row(
            Modifier
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(16.dp)
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (help != null) HelpText(help)
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = inkSwitchColors(),
                thumbContent = { InkThumbRing() },
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** Bulleted lines in a guide box. Each line is its own TalkBack stop (bullets are drawn, not read). */
@Composable
internal fun InfoCard(lines: List<String>) {
    val p = uiVoicePalette()
    GuideBox {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            lines.forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 9.dp).size(8.dp).clip(CircleShape).background(p.main))
                    BodyText(line, Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

/** "접근성 서비스: 켜짐 ✓" status box (the check is drawn; the text says 켜짐). */
@Composable
internal fun ServiceStateBox(on: Boolean) {
    val p = uiVoicePalette()
    Row(
        Modifier.fillMaxWidth().inkRow(p, fill = if (on) p.sub else p.card).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) CheckMark(p.selected, 28)
        Text(
            stringResource(if (on) R.string.ui_setup_service_state_on else R.string.ui_setup_service_state_off),
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = if (on) 10.dp else 0.dp),
        )
    }
}

/** Drawn check (decorative). [big] = the last step's large orange circle with an ink line. */
@Composable
internal fun CheckMark(color: Color, size: Int, big: Boolean = false) {
    val p = uiVoicePalette()
    Canvas(Modifier.size(size.dp)) {
        drawCircle(if (big) p.main else color)
        if (big) drawCircle(p.ink, radius = this.size.minDimension / 2 - 1.5.dp.toPx(), style = Stroke(3.dp.toPx()))
        val stroke = (if (big) 8 else 3).dp.toPx()
        drawLine(Color.White, Offset(this.size.width * 0.27f, this.size.height * 0.52f), Offset(this.size.width * 0.44f, this.size.height * 0.68f), stroke, StrokeCap.Round)
        drawLine(Color.White, Offset(this.size.width * 0.44f, this.size.height * 0.68f), Offset(this.size.width * 0.74f, this.size.height * 0.36f), stroke, StrokeCap.Round)
    }
}

/** One summary line; TalkBack reads "이름, 값" as one stop. */
@Composable
internal fun SummaryRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        HelpText(label)
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}
