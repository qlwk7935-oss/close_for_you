package kr.closeforyou.ui.voice.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.closeforyou.R

/**
 * F63: C's design tokens in one place ("팝콕이": white cards, dark ink lines with a thick bottom edge, orange accents).
 * Colors, shapes, spacing and type only: no wording, order, semantics or behavior lives here.
 * Contrast of every text/background pair is JVM-tested ([UiVoicePalette.textPairs], UiVoiceDesignContrastTest).
 */
data class UiVoicePalette(
    /** Lines and body text. */
    val ink: Color,
    /** Main accent: main buttons (text ≥ 18sp bold on it), switch track, progress, icons. */
    val main: Color,
    val onMain: Color,
    /** A chosen item's background: it carries small white text too, so it is darker than [main] (4.5:1). */
    val selected: Color,
    val onSelected: Color,
    /** Secondary box/button. */
    val sub: Color,
    val onSub: Color,
    /** Small labels and link text. */
    val muted: Color,
    val screen: Color,
    val card: Color,
    /** Left bar of a guide box, the icon's band. */
    val apricot: Color,
    val skin: Color,
    val disabledContainer: Color,
    val disabledContent: Color,
    val disabledLine: Color,
    val trackOff: Color,
    /** Progress bar: steps left. */
    val stepLeft: Color,
) {
    companion object {
        val Light = UiVoicePalette(
            ink = Color(0xFF2B211C),
            main = Color(0xFFE8683A),
            onMain = Color(0xFFFFFFFF),
            selected = Color(0xFFC24F22),
            onSelected = Color(0xFFFFFFFF),
            sub = Color(0xFFFFF1E8),
            onSub = Color(0xFF8A3E1F),
            // Asked #9A6B55: 4.00:1 on the screen color and 4.12:1 on [sub]; darkened to pass 4.5:1 on all three.
            muted = Color(0xFF8A5D48),
            screen = Color(0xFFF6EFE9),
            card = Color(0xFFFFFFFF),
            apricot = Color(0xFFF7B08A),
            skin = Color(0xFFFFD9C2),
            disabledContainer = Color(0xFFF3EEEA),
            disabledContent = Color(0xFFA89A92),
            disabledLine = Color(0xFFC9BDB5),
            trackOff = Color(0xFFEADFD6),
            stepLeft = Color(0xFFEADFD6),
        )

        /** The app follows the system dark setting ([kr.closeforyou.ui.voice.UiVoiceTheme]), so the same tokens exist for dark. */
        val Dark = UiVoicePalette(
            ink = Color(0xFFF3E6D6),
            main = Color(0xFFE8683A),
            onMain = Color(0xFFFFFFFF),
            selected = Color(0xFFC24F22),
            onSelected = Color(0xFFFFFFFF),
            sub = Color(0xFF4A2C1F),
            onSub = Color(0xFFFFD3BC),
            muted = Color(0xFFCDB3A3),
            screen = Color(0xFF1F1A17),
            card = Color(0xFF2B2420),
            apricot = Color(0xFFF7B08A),
            skin = Color(0xFFFFD9C2),
            disabledContainer = Color(0xFF3A322D),
            disabledContent = Color(0xFF8C7F78),
            disabledLine = Color(0xFF5A4E47),
            trackOff = Color(0xFF4A403A),
            stepLeft = Color(0xFF4A403A),
        )

        fun of(dark: Boolean) = if (dark) Dark else Light
    }

    /** Pair kind for the contrast test: body ≥ 4.5:1, large (≥ 18sp bold) ≥ 3:1, non-text ≥ 3:1; decorative (hidden from TalkBack, said in words) and disabled reported only. */
    enum class PairKind { BODY, LARGE, NON_TEXT, DECORATIVE, DISABLED }

    data class TextPair(val name: String, val fg: Color, val bg: Color, val kind: PairKind)

    /** Every text/background pair C's screens draw with these tokens. */
    fun textPairs(): List<TextPair> = listOf(
        TextPair("본문 잉크/카드", ink, card, PairKind.BODY),
        TextPair("본문 잉크/바탕", ink, screen, PairKind.BODY),
        TextPair("본문 잉크/보조 상자", ink, sub, PairKind.BODY),
        TextPair("흐린 글자/카드", muted, card, PairKind.BODY),
        TextPair("흐린 글자/바탕", muted, screen, PairKind.BODY),
        TextPair("흐린 글자/보조 상자", muted, sub, PairKind.BODY),
        TextPair("보조 글자/보조 상자", onSub, sub, PairKind.BODY),
        TextPair("테두리 버튼 글자/카드", ink, card, PairKind.BODY),
        TextPair("고른 항목 글자/고른 항목", onSelected, selected, PairKind.BODY),
        TextPair("메인 버튼 글자(18sp 굵게)/메인", onMain, main, PairKind.LARGE),
        TextPair("선/카드", ink, card, PairKind.NON_TEXT),
        TextPair("주황(스위치·막대·아이콘)/카드", main, card, PairKind.NON_TEXT),
        TextPair("진행 막대 지난 칸/남은 칸", main, stepLeft, PairKind.DECORATIVE),
        TextPair("비활성 글자/비활성 배경", disabledContent, disabledContainer, PairKind.DISABLED),
    )
}

/** Shapes, line widths and spacing. */
object UiVoiceDims {
    val line = 2.dp
    /** Total bottom edge (the line included). */
    val cardBottom = 6.dp
    val buttonBottom = 5.dp
    val rowBottom = 4.dp
    val cardRadius = 22.dp
    val buttonRadius = 14.dp
    val rowRadius = 12.dp
    val pillRadius = 999.dp
    val guideBar = 3.dp
    val headerIcon = 30.dp
    val promptHeaderIcon = 34.dp
    val stepBarHeight = 5.dp
}

/** Type sizes (sp: the system font size still applies). */
object UiVoiceType {
    val brandLabel = 11.sp
    /** Main buttons stay large text (≥ 18sp bold), so white on [UiVoicePalette.main] passes 3:1. */
    val mainButton = 18.sp
    val button = 16.sp
    val link = 15.sp
}

@Composable
fun uiVoicePalette(): UiVoicePalette = UiVoicePalette.of(isSystemInDarkTheme())

/**
 * The thick bottom edge: an [color] copy of the shape drawn [extra] lower, behind the element. The element keeps its own 2dp line,
 * so the bottom reads as line + extra. Drawing only: the element's semantics and touch area are unchanged.
 */
fun Modifier.inkShadow(color: Color, radius: Dp, extra: Dp): Modifier = this
    .drawBehind {
        val e = extra.toPx()
        val r = radius.toPx().coerceAtMost(size.minDimension / 2)
        drawRoundRect(color, topLeft = Offset(0f, e), size = Size(size.width, size.height - e), cornerRadius = CornerRadius(r, r))
    }
    .padding(bottom = extra)

/** White card: 2dp ink line, 6dp bottom, 22dp corners. */
@Composable
fun InkCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(20.dp), content: @Composable () -> Unit) {
    val p = uiVoicePalette()
    val shape = RoundedCornerShape(UiVoiceDims.cardRadius)
    Box(
        modifier
            .inkShadow(p.ink, UiVoiceDims.cardRadius, UiVoiceDims.cardBottom - UiVoiceDims.line)
            .clip(shape)
            .background(p.card, shape)
            .then(Modifier.inkOutline(p.ink, UiVoiceDims.cardRadius))
            .padding(padding),
    ) { content() }
}

/** A box for a switch line or a list line: 2dp line, 4dp bottom, 12dp corners (or a pill). */
fun Modifier.inkRow(p: UiVoicePalette, fill: Color = p.card, radius: Dp = UiVoiceDims.rowRadius, line: Color = p.ink): Modifier = this
    .inkShadow(line, radius, UiVoiceDims.rowBottom - UiVoiceDims.line)
    .clip(RoundedCornerShape(radius))
    .background(fill, RoundedCornerShape(radius))
    .inkOutline(line, radius)

/** The 2dp outline, drawn (not a layout border), so content padding stays as before. */
fun Modifier.inkOutline(color: Color, radius: Dp): Modifier = drawBehind {
    val w = UiVoiceDims.line.toPx()
    val r = (radius.toPx() - w / 2).coerceAtMost(size.minDimension / 2)
    drawRoundRect(
        color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(r, r),
        style = androidx.compose.ui.graphics.drawscope.Stroke(w),
    )
}

/** Button grades, strongest first. */
enum class InkGrade { MAIN, SUB, LINE, LINK }

/**
 * A Material [Button] (or [TextButton] for [InkGrade.LINK]) in the new look: same role, enabled state, click and label as before.
 * [content] defaults to the label; pass it to add a drawn icon (decorative) beside the words.
 */
@Composable
fun InkButton(
    label: String,
    onClick: () -> Unit,
    grade: InkGrade,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 52.dp,
    fontSize: TextUnit = if (grade == InkGrade.MAIN) UiVoiceType.mainButton else UiVoiceType.button,
    radius: Dp = UiVoiceDims.buttonRadius,
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    val p = uiVoicePalette()
    if (grade == InkGrade.LINK) {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = p.muted, disabledContentColor = p.disabledContent),
        ) { if (content != null) content() else Text(label, fontSize = UiVoiceType.link, textAlign = TextAlign.Center) }
        return
    }
    val (fill, text) = when (grade) {
        InkGrade.MAIN -> p.main to p.onMain
        InkGrade.SUB -> p.sub to p.onSub
        else -> p.card to p.ink
    }
    val line = if (enabled) p.ink else p.disabledLine
    val shape = RoundedCornerShape(radius)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .inkShadow(line, radius, UiVoiceDims.buttonBottom - UiVoiceDims.line)
            .heightIn(min = minHeight),
        shape = shape,
        border = BorderStroke(UiVoiceDims.line, line),
        colors = ButtonDefaults.buttonColors(
            containerColor = fill,
            contentColor = text,
            disabledContainerColor = p.disabledContainer,
            disabledContentColor = p.disabledContent,
        ),
        elevation = null,
    ) {
        if (content != null) content() else Text(
            label,
            fontSize = fontSize,
            fontWeight = if (grade == InkGrade.MAIN) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/** Pill switch: 2dp ink track line, orange when on; white thumb. Material cannot outline the thumb, so it carries an ink ring inside. */
@Composable
fun inkSwitchColors(): SwitchColors {
    val p = uiVoicePalette()
    return SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = p.main,
        checkedBorderColor = p.ink,
        uncheckedThumbColor = Color.White,
        uncheckedTrackColor = p.trackOff,
        uncheckedBorderColor = p.ink,
        disabledCheckedTrackColor = p.disabledContainer,
        disabledUncheckedTrackColor = p.disabledContainer,
        disabledCheckedBorderColor = p.disabledLine,
        disabledUncheckedBorderColor = p.disabledLine,
    )
}

/** The ring drawn inside the switch thumb (decorative, Material's thumbContent). */
@Composable
fun InkThumbRing() {
    val p = uiVoicePalette()
    Box(Modifier.size(SwitchDefaults.IconSize).drawBehind {
        drawCircle(p.ink, radius = size.minDimension / 2 - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(UiVoiceDims.line.toPx()))
    })
}

/** The mascot "팝콕이" (same shapes as the launcher icon). Decorative: never read by TalkBack. */
@Composable
fun Popkok(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ui_popkok), contentDescription = null, modifier = modifier.size(size))
}

/**
 * Screen head: 팝콕이 + small "팝업처리도우미" + [title]. The picture and the small name are hidden from TalkBack
 * (clearAndSetSemantics), so only [title] is read, as before; [title] keeps whatever semantics the caller gives it.
 */
@Composable
fun PopkokHeader(iconSize: Dp = UiVoiceDims.headerIcon, title: @Composable () -> Unit) {
    val p = uiVoicePalette()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Popkok(iconSize, Modifier.clearAndSetSemantics {})
        Column(Modifier.padding(start = 10.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                stringResource(R.string.ui_app_display_name),
                fontSize = UiVoiceType.brandLabel,
                fontWeight = FontWeight.SemiBold,
                color = p.muted,
                modifier = Modifier.clearAndSetSemantics {},
            )
            title()
        }
    }
}

/** Five-step bar (decorative; the step line next to it is what TalkBack reads). */
@Composable
fun StepBar(done: Int, count: Int, modifier: Modifier = Modifier) {
    val p = uiVoicePalette()
    Row(modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(UiVoiceDims.stepBarHeight)
                    .clip(RoundedCornerShape(UiVoiceDims.pillRadius))
                    .background(if (i < done) p.main else p.stepLeft),
            )
        }
    }
}

/** A guide box: [UiVoicePalette.sub] fill with a 3dp apricot bar on the left. Layout only (no semantics). */
@Composable
fun GuideBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = uiVoicePalette()
    val shape = RoundedCornerShape(UiVoiceDims.rowRadius)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.sub, shape)
            .drawBehind { drawRect(p.apricot, size = Size(UiVoiceDims.guideBar.toPx(), size.height)) }
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() } }
}

/** Decorative orange icon (section heads, mic, check). */
@Composable
fun InkIcon(res: Int, size: Dp, tint: Color? = null, modifier: Modifier = Modifier) {
    val p = uiVoicePalette()
    androidx.compose.material3.Icon(
        painterResource(res),
        contentDescription = null,
        tint = tint ?: p.main,
        modifier = modifier.size(size),
    )
}

/** Theme text color helper for code that only has [MaterialTheme]. */
@Composable
fun inkText(): Color = MaterialTheme.colorScheme.onSurface
