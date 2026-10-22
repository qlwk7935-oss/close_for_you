package kr.closeforyou.ui.voice.overlay

import kr.closeforyou.ui.voice.UiVoiceTheme
import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
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
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.ChoicePromptContent
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptOption
import kr.closeforyou.ui.voice.choice.PromptSelection

/**
 * Shows [ChoicePromptContent] above other apps as a TYPE_ACCESSIBILITY_OVERLAY window.
 *
 * That window type needs a connected AccessibilityService context and no "draw over apps"
 * permission. FLAG_NOT_FOCUSABLE keeps the keyboard and input focus in the app the person was
 * using; TalkBack focus is never moved by code. Mock-based: not yet wired to A's real service.
 *
 * Window bookkeeping (generations, failed add/remove, exactly-once cleanup) is in
 * [OverlayWindowKeeper]; this class only supplies the Android steps.
 */
class AccessibilityChoiceOverlay(
    private val service: AccessibilityService,
    private val listener: OverlayListener = OverlayListener.NONE,
    /** [말하기]·[다시 듣기]·[그만 말하기]; [PromptControlsSupport.NONE] shows the choices only. */
    private val controlsSupport: PromptControlsSupport = PromptControlsSupport.NONE,
) : ChoicePromptPresenter {
    private class OverlayWindow(
        val prompt: ChoicePrompt,
        val view: ComposeView,
        val owner: OverlayLifecycleOwner,
        val controller: PromptControlsController,
        val scope: CoroutineScope,
    )

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val keeper = OverlayWindowKeeper(WindowOps())

    override val isShowing: Boolean get() = keeper.status == OverlayWindowStatus.SHOWING

    override val windowState: OverlayWindowState
        get() = OverlayWindowState(keeper.status, keeper.windowGeneration)

    override fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult =
        keeper.show { generation -> createWindow(prompt, generation, onAnswer) }

    override fun dismiss() = keeper.dismiss()

    private fun createWindow(prompt: ChoicePrompt, generation: Long, onAnswer: (PromptAnswer) -> Unit): OverlayWindow {
        // Taps and voice share this one path, so a voice choice is exactly a tap's UserChoice.
        // Anything from a closed, replaced or failed window stops at the generation check.
        val onOption: (PromptOption, ChoiceOrigin) -> Unit = { option, origin ->
            when (val selection = ChoicePromptPlanner.select(prompt, option)) {
                // Press and keep close the prompt first; READ_CONTENT keeps it open.
                is PromptSelection.Choice -> keeper.deliver(
                    PromptAnswer.Picked(selection.choice, origin, generation),
                    closesWindow = selection.choice.kind != ChoiceKind.READ_CONTENT,
                    onAnswer = onAnswer,
                )
                // Close first so the moved focus lands on the app, not on us.
                is PromptSelection.MoveToButton ->
                    keeper.deliver(PromptAnswer.Move(selection.request, origin, generation), closesWindow = true, onAnswer = onAnswer)
            }
        }
        val scope = MainScope()
        var owner: OverlayLifecycleOwner? = null
        try {
            val controller = PromptControlsController(prompt, scope, controlsSupport) { onOption(it, ChoiceOrigin.VOICE) }
            val composeView = ComposeView(service).apply {
                setContent {
                    val controlsState by controller.state.collectAsState()
                    UiVoiceTheme {
                        ChoicePromptContent(
                            prompt = prompt,
                            onOption = { onOption(it, ChoiceOrigin.TAP) },
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
            val lifecycleOwner = OverlayLifecycleOwner().also { owner = it }
            lifecycleOwner.attachTo(composeView)
            return OverlayWindow(prompt, composeView, lifecycleOwner, controller, scope)
        } catch (e: RuntimeException) {
            // Nothing was added yet: release what exists, then let the keeper report FAILED.
            scope.cancel()
            try {
                owner?.destroy()
            } catch (ignored: RuntimeException) {
                // The scope is already cancelled; the keeper still reports FAILED.
            }
            throw e
        }
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
        // AccessibilityWindowInfo.getTitle() falls back to it (ChoiceOverlayIdentity; device unverified).
        title = service.getString(R.string.ui_choice_pane_title)
    }

    private inner class WindowOps : OverlayWindowOps<OverlayWindow> {
        override fun add(window: OverlayWindow) = windowManager.addView(window.view, layoutParams())

        override fun announceShown(window: OverlayWindow) = listener.onShown(window.prompt, window.controller::speakPressed)

        override fun announceDismissed(window: OverlayWindow) = listener.onDismissed()

        override fun stop(window: OverlayWindow) {
            try {
                window.controller.dispose()
            } finally {
                window.scope.cancel()
            }
        }

        override fun remove(window: OverlayWindow, immediate: Boolean): RemoveOutcome = try {
            if (immediate) windowManager.removeViewImmediate(window.view) else windowManager.removeView(window.view)
            RemoveOutcome.REMOVED
        } catch (e: IllegalArgumentException) {
            // WindowManagerGlobal throws this only for a view it does not hold (AOSP 36 source).
            RemoveOutcome.NOT_ATTACHED
        }

        override fun blank(window: OverlayWindow) {
            // No buttons left to tap or read; GONE should also hide the window (device unverified).
            try {
                window.view.disposeComposition()
            } finally {
                window.view.visibility = View.GONE
            }
        }

        override fun destroy(window: OverlayWindow) = window.owner.destroy()
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
