package kr.closeforyou.ui.voice.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.closeforyou.R

/**
 * "팝업처리도우미" brand colors (the launcher icon's indigo → teal, demo-musical scene 2).
 * [accent] is used for text and borders, so dark mode uses a lighter indigo; buttons keep
 * [buttonContainer] with white text in both modes. Contrast is JVM-tested (≥ 4.5:1).
 */
data class UiBrandColors(val accent: Color, val buttonContainer: Color, val onButton: Color, val gradientEnd: Color) {
    companion object {
        val Indigo = Color(0xFF3D5AFE)
        /** Text on light surfaces and cards (Indigo itself is 3.98:1 on surfaceVariant). */
        val IndigoDeep = Color(0xFF3047D9)
        val IndigoLight = Color(0xFFB0BCFF)
        val Teal = Color(0xFF00BFA5)

        fun of(dark: Boolean) = UiBrandColors(
            accent = if (dark) IndigoLight else IndigoDeep,
            buttonContainer = Indigo,
            onButton = Color.White,
            gradientEnd = Teal,
        )
    }
}

@Composable
internal fun brandColors() = UiBrandColors.of(isSystemInDarkTheme())

/** Large type used by the first run and settings (sp, so the system font size still applies). */
internal object UiType {
    val title = 28.sp
    val subtitle = 22.sp
    val body = 18.sp
    val help = 16.sp
}

@Composable
internal fun StepTitle(text: String) {
    Text(text, fontSize = UiType.title, fontWeight = FontWeight.Bold, lineHeight = 36.sp, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = UiType.body, lineHeight = 26.sp, modifier = modifier)
}

@Composable
internal fun HelpText(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = UiType.help, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
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
    val brand = brandColors()
    if (!enabled && disabledReason != null) HelpText(disabledReason)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .semantics { if (!enabled && disabledReason != null) stateDescription = disabledReason },
        colors = ButtonDefaults.buttonColors(containerColor = brand.buttonContainer, contentColor = brand.onButton),
        shape = RoundedCornerShape(16.dp),
    ) { Text(label, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
}

/** "5단계 중 N단계" and a decorative bar (the bar is hidden from TalkBack; the line says it). */
@Composable
internal fun StepHeader(step: OnboardingStep) {
    val brand = brandColors()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.ui_setup_step, OnboardingStep.COUNT, step.number),
            fontSize = UiType.help,
            fontWeight = FontWeight.Bold,
            color = brand.accent,
        )
        LinearProgressIndicator(
            progress = { step.number / OnboardingStep.COUNT.toFloat() },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).clearAndSetSemantics {},
            color = brand.gradientEnd,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
    }
}

/** The app mark: the launcher icon's gradient and foreground. Decorative (the name is read next to it). */
@Composable
internal fun AppMark(size: Int = 112) {
    val brand = brandColors()
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 4).dp))
            .background(Brush.linearGradient(listOf(UiBrandColors.Indigo, brand.gradientEnd))),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ui_launcher_foreground), contentDescription = null, modifier = Modifier.size((size * 1.5f).dp))
    }
}

/**
 * One radio choice as a large card. TalkBack: title, body, "선택됨"/"선택 안 됨", 라디오 버튼.
 * Put several inside a `Modifier.selectableGroup()` column.
 */
@Composable
internal fun ChoiceCard(title: String, body: String?, selected: Boolean, onClick: () -> Unit, note: String? = null) {
    val brand = brandColors()
    val state = stringResource(if (selected) R.string.ui_setup_selected else R.string.ui_setup_not_selected)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) brand.accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 2.5.dp else 1.dp, if (selected) brand.accent else MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { stateDescription = state },
    ) {
        Row(Modifier.padding(16.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = brand.accent))
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (body != null) HelpText(body)
                if (note != null) HelpText(note)
            }
        }
    }
}

/** One switch as a card: one TalkBack node (label, help, 켜짐/꺼짐, 스위치; disabled "사용 중지됨"). */
@Composable
internal fun SwitchCard(label: String, help: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val brand = brandColors()
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
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
                colors = SwitchDefaults.colors(checkedTrackColor = brand.buttonContainer),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** Bulleted lines in a tinted card. Each line is its own TalkBack stop (bullets are drawn, not read). */
@Composable
internal fun InfoCard(lines: List<String>) {
    val brand = brandColors()
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            lines.forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 9.dp).size(8.dp).clip(CircleShape).background(brand.accent))
                    BodyText(line, Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

/** "접근성 서비스: 켜짐 ✓" status box (the check is drawn; the text says 켜짐). */
@Composable
internal fun ServiceStateBox(on: Boolean) {
    val brand = brandColors()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (on) brand.gradientEnd.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (on) CheckMark(brand.gradientEnd, 28)
            Text(
                stringResource(if (on) R.string.ui_setup_service_state_on else R.string.ui_setup_service_state_off),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = if (on) 10.dp else 0.dp),
            )
        }
    }
}

/** Drawn check (decorative). [big] adds the gradient circle of the last step. */
@Composable
internal fun CheckMark(color: Color, size: Int, big: Boolean = false) {
    val end = brandColors().gradientEnd
    Canvas(Modifier.size(size.dp)) {
        if (big) drawCircle(Brush.linearGradient(listOf(UiBrandColors.Indigo, end))) else drawCircle(color)
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
