package com.team.popup.experiment

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 실험용 선택지. 본 앱에서는 common의 UserChoice를 쓴다. */
enum class Choice(@param:StringRes val labelRes: Int) {
    CLOSE(R.string.choice_close),
    ALWAYS_CLOSE(R.string.choice_always_close),
    KEEP(R.string.choice_keep),
}

/**
 * "닫을까요?" 테스트 오버레이를 WindowManager로 띄우고 내린다.
 *
 * TYPE_ACCESSIBILITY_OVERLAY 창은 접근성 서비스 컨텍스트에서만 만들 수 있고,
 * "다른 앱 위에 표시" 권한이 필요 없다.
 */
class TestPopupOverlay(
    private val context: Context,
    private val speaker: Speaker,
    private val onChoice: (Choice) -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    fun show(mode: AnnounceMode, ttsDelayMs: Int) {
        dismiss()
        val owner = OverlayLifecycleOwner()
        val composeView = ComposeView(context).apply {
            setContent {
                MaterialTheme {
                    TestPopupContent(
                        mode = mode,
                        ttsDelayMs = ttsDelayMs,
                        speaker = speaker,
                        onChoice = { choice ->
                            dismiss()
                            onChoice(choice)
                        },
                    )
                }
            }
        }
        owner.attachTo(composeView)
        windowManager.addView(composeView, layoutParams(mode))
        view = composeView
        lifecycleOwner = owner
    }

    fun dismiss() {
        speaker.stop()
        view?.let { windowManager.removeView(it) }
        lifecycleOwner?.destroy()
        view = null
        lifecycleOwner = null
    }

    private fun layoutParams(mode: AnnounceMode) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // 입력 포커스를 가져가지 않아 사용자가 하던 앱의 키보드·포커스를 건드리지 않는다.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM
        // 창 제목이 TalkBack 안내에 쓰이는지는 실기기 확인 필요. 방식 (a)에서만 넣어 비교한다.
        if (mode == AnnounceMode.PANE_TITLE) title = context.getString(R.string.overlay_pane_title)
    }
}

@Composable
private fun TestPopupContent(
    mode: AnnounceMode,
    ttsDelayMs: Int,
    speaker: Speaker,
    onChoice: (Choice) -> Unit,
) {
    val paneTitle = stringResource(R.string.overlay_pane_title)
    val summary = stringResource(R.string.overlay_summary)

    // 라이브 영역은 "내용이 바뀔 때" 읽힌다. 그래서 빈 글자로 시작했다가 잠시 뒤 문장을 채운다.
    var summaryText by remember { mutableStateOf(if (mode == AnnounceMode.LIVE_REGION) "" else summary) }

    LaunchedEffect(mode) {
        when (mode) {
            AnnounceMode.LIVE_REGION -> {
                delay(LIVE_REGION_FILL_DELAY_MS)
                summaryText = summary
            }
            AnnounceMode.TTS -> {
                delay(ttsDelayMs.toLong())
                speaker.speak(summary)
            }
            AnnounceMode.NONE, AnnounceMode.PANE_TITLE -> Unit
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(16.dp)
            .semantics { if (mode == AnnounceMode.PANE_TITLE) this.paneTitle = paneTitle },
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        // 화면에 놓인 순서 = TalkBack 탐색 순서: 요약 문장 → 닫기 → 항상 닫기 → 그대로 두기
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = summaryText,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics {
                    if (mode == AnnounceMode.LIVE_REGION) liveRegion = LiveRegionMode.Polite
                },
            )
            Button(onClick = { onChoice(Choice.CLOSE) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Choice.CLOSE.labelRes))
            }
            OutlinedButton(onClick = { onChoice(Choice.ALWAYS_CLOSE) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Choice.ALWAYS_CLOSE.labelRes))
            }
            OutlinedButton(onClick = { onChoice(Choice.KEEP) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Choice.KEEP.labelRes))
            }
        }
    }
}

private const val LIVE_REGION_FILL_DELAY_MS = 500L
