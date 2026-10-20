package kr.closeforyou.ui.voice.overlay

import kr.closeforyou.ui.voice.UiVoiceTheme
import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kr.closeforyou.R
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.UserChoice
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.ChoicePromptContent
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.MoveToButtonRequest
import kr.closeforyou.ui.voice.choice.PromptOption
import kr.closeforyou.ui.voice.choice.PromptSelection

/**
 * Shows [ChoicePromptContent] above other apps as a TYPE_ACCESSIBILITY_OVERLAY window.
 *
 * That window type needs a connected AccessibilityService context and no "draw over apps"
 * permission. FLAG_NOT_FOCUSABLE keeps the keyboard and input focus in the app the person was
 * using; TalkBack focus is never moved by code. Mock-based: not yet wired to A's real service.
 */
class AccessibilityChoiceOverlay(
    private val service: AccessibilityService,
    private val listener: OverlayListener = OverlayListener.NONE,
    /** [말하기]·[다시 듣기]·[그만 말하기]; [PromptControlsSupport.NONE] shows the choices only. */
    private val controlsSupport: PromptControlsSupport = PromptControlsSupport.NONE,
) : ChoicePromptPresenter {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var controls: PromptControlsController? = null
    private var controlsScope: CoroutineScope? = null

    override val isShowing: Boolean get() = view != null

    override fun show(
        prompt: ChoicePrompt,
        onMoveToButton: (MoveToButtonRequest) -> Unit,
        onChoice: (UserChoice) -> Unit,
    ) {
        dismiss()
        val lifecycleOwner = OverlayLifecycleOwner()
        // Taps and voice share this one path, so a voice choice is exactly a tap's UserChoice.
        val onOption: (PromptOption) -> Unit = { option ->
            when (val selection = ChoicePromptPlanner.select(prompt, option)) {
                is PromptSelection.Choice -> {
                    if (selection.choice.kind != ChoiceKind.READ_CONTENT) dismiss()
                    onChoice(selection.choice)
                }
                is PromptSelection.MoveToButton -> {
                    // Close first so the moved focus lands on the app, not on us.
                    dismiss()
                    onMoveToButton(selection.request)
                }
            }
        }
        val scope = MainScope()
        val controller = PromptControlsController(prompt, scope, controlsSupport, onOption)
        val composeView = ComposeView(service).apply {
            setContent {
                val controlsState by controller.state.collectAsState()
                UiVoiceTheme {
                    ChoicePromptContent(
                        prompt = prompt,
                        onOption = onOption,
                        controls = controlsState,
                        onSpeak = controller::onSpeak,
                        onRepeat = controller::onRepeat,
                        onStopSpeaking = controller::onStopSpeaking,
                        // Scrolls when large fonts make the prompt taller than the screen
                        // (the window is WRAP_CONTENT, so its height is capped by the display).
                        // statusBarsPadding: a full-height prompt stays below the status bar
                        // (inset behaviour of overlay windows is not yet checked on a device).
                        modifier = Modifier
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
        lifecycleOwner.attachTo(composeView)
        windowManager.addView(composeView, layoutParams())
        view = composeView
        owner = lifecycleOwner
        controls = controller
        controlsScope = scope
        listener.onShown(prompt, controller::speakPressed)
    }

    override fun dismiss() {
        val current = view ?: return
        view = null
        controls?.dispose()
        controls = null
        controlsScope?.cancel()
        controlsScope = null
        listener.onDismissed()
        windowManager.removeView(current)
        owner?.destroy()
        owner = null
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM
        // Fixed, text-free title: the same words as the Compose paneTitle, never popup content.
        title = service.getString(R.string.ui_choice_pane_title)
    }
}

/** Hooks for announcing the prompt (step 2). Receives no screen text beyond the prompt itself. */
interface OverlayListener {
    /** [speakPressed]: true once [말하기] started listening on this prompt (checked when speaking). */
    fun onShown(prompt: ChoicePrompt, speakPressed: () -> Boolean = { false }) = Unit
    fun onDismissed() = Unit

    companion object {
        val NONE = object : OverlayListener {}
    }
}
