package kr.closeforyou.ui.voice.overlay

import kr.closeforyou.ui.voice.UiVoiceTheme
import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.StringRes
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
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Window bookkeeping (generations, failed add/remove, exactly-once cleanup, REVALIDATING) is in
 * [OverlayWindowKeeper]; this class only supplies the Android steps. One window holds one prompt
 * session at a time; a same-popup confirmation swaps the session inside the same window.
 */
class AccessibilityChoiceOverlay(
    private val service: AccessibilityService,
    private val listener: OverlayListener = OverlayListener.NONE,
    /** [말하기]·[다시 듣기]·[그만 말하기]; [PromptControlsSupport.NONE] shows the choices only. */
    private val controlsSupport: PromptControlsSupport = PromptControlsSupport.NONE,
    /** [RevalidatingFeature]; false = [beginRevalidation] only dismisses, as before. */
    private val revalidatingEnabled: Boolean = RevalidatingFeature.enabled,
) : ChoicePromptPresenter {
    /** One prompt's voice controller and coroutine scope; [onTap] carries its generation. */
    private class PromptSession(
        val prompt: ChoicePrompt,
        val controller: PromptControlsController,
        val scope: CoroutineScope,
        val onTap: (PromptOption) -> Unit,
    )

    private data class WindowContent(val session: PromptSession, val revalidating: Boolean = false)

    private class OverlayWindow(
        val view: ComposeView,
        val owner: OverlayLifecycleOwner,
        val content: MutableStateFlow<WindowContent>,
    ) {
        val session: PromptSession get() = content.value.session
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val keeper = OverlayWindowKeeper(WindowOps(), MainThreadTimer(Handler(Looper.getMainLooper())))

    /** The prompt that was on screen when REVALIDATING began; for the closing sentence only. */
    private var revalidatingPrompt: ChoicePrompt? = null

    override val isShowing: Boolean get() = keeper.status == OverlayWindowStatus.SHOWING

    override val windowState: OverlayWindowState
        get() = OverlayWindowState(keeper.status, keeper.windowGeneration, keeper.windowNumber)

    override fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult {
        revalidatingPrompt = null
        return keeper.show { generation -> createWindow(prompt, generation, onAnswer) }
    }

    override fun dismiss() {
        revalidatingPrompt = null
        keeper.dismiss()
    }

    override fun beginRevalidation(cause: RevalidationCause): Boolean {
        if (!revalidatingEnabled) {
            dismiss()
            return false
        }
        val started = keeper.beginRevalidation(onTimeout = {
            val prompt = revalidatingPrompt
            revalidatingPrompt = null
            if (prompt != null) safely { listener.onRevalidationClosed(prompt, RevalidationEnd.TIMED_OUT) }
        })
        if (!started) dismiss()
        return started
    }

    override fun confirmSamePopup(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult {
        if (keeper.status != OverlayWindowStatus.REVALIDATING) return present(prompt, onAnswer)
        revalidatingPrompt = null
        val result = keeper.confirmRevalidation { window, generation ->
            // Same window, no add/remove: only the content and its generation change.
            val session = newSession(prompt, generation, onAnswer, R.string.ui_revalidate_confirmed)
            window.content.value = WindowContent(session)
        }
        if (result == PromptShowResult.SHOWN) safely { listener.onRevalidated(prompt) }
        return result
    }

    override fun endRevalidation(end: RevalidationEnd) {
        val prompt = revalidatingPrompt
        if (keeper.status != OverlayWindowStatus.REVALIDATING || prompt == null) {
            dismiss()
            return
        }
        revalidatingPrompt = null
        keeper.endRevalidation()
        safely { listener.onRevalidationClosed(prompt, end) }
    }

    private fun newSession(
        prompt: ChoicePrompt,
        generation: Long,
        onAnswer: (PromptAnswer) -> Unit,
        @StringRes initialMessageRes: Int? = null,
    ): PromptSession {
        // Taps and voice share this one path, so a voice choice is exactly a tap's UserChoice.
        // Anything from a closed, replaced, revalidating or failed window stops at the generation check.
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
        try {
            val controller = PromptControlsController(prompt, scope, controlsSupport, initialMessageRes) {
                onOption(it, ChoiceOrigin.VOICE)
            }
            return PromptSession(prompt, controller, scope) { onOption(it, ChoiceOrigin.TAP) }
        } catch (e: RuntimeException) {
            scope.cancel()
            throw e
        }
    }

    private fun createWindow(prompt: ChoicePrompt, generation: Long, onAnswer: (PromptAnswer) -> Unit): OverlayWindow {
        val session = newSession(prompt, generation, onAnswer)
        var owner: OverlayLifecycleOwner? = null
        try {
            val content = MutableStateFlow(WindowContent(session))
            val composeView = ComposeView(service).apply {
                setContent {
                    val current by content.collectAsState()
                    val shown = current.session
                    val controlsState by shown.controller.state.collectAsState()
                    UiVoiceTheme {
                        ChoicePromptContent(
                            prompt = shown.prompt,
                            onOption = shown.onTap,
                            controls = controlsState,
                            onSpeak = shown.controller::onSpeak,
                            onRepeat = shown.controller::onRepeat,
                            onStopSpeaking = shown.controller::onStopSpeaking,
                            revalidating = current.revalidating,
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
            return OverlayWindow(composeView, lifecycleOwner, content)
        } catch (e: RuntimeException) {
            // Nothing was added yet: release what exists, then let the keeper report FAILED.
            session.scope.cancel()
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

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            // A listener failure must not undo the window bookkeeping that already happened.
        }
    }

    private inner class WindowOps : OverlayWindowOps<OverlayWindow> {
        override fun add(window: OverlayWindow) = windowManager.addView(window.view, layoutParams())

        override fun announceShown(window: OverlayWindow) =
            listener.onShown(window.session.prompt, window.session.controller::speakPressed)

        override fun announceDismissed(window: OverlayWindow) = listener.onDismissed()

        override fun stop(window: OverlayWindow) = stopSession(window.session)

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

        override fun enterRevalidating(window: OverlayWindow) {
            revalidatingPrompt = window.session.prompt
            // Listening and our speech stop first, then the content changes; focus is left alone.
            stopSession(window.session)
            listener.onRevalidating()
            window.content.value = window.content.value.copy(revalidating = true)
        }

        private fun stopSession(session: PromptSession) {
            try {
                session.controller.dispose()
            } finally {
                session.scope.cancel()
            }
        }
    }

    /** Main-looper timer for the REVALIDATING timeout; cancelled by every way out of that state. */
    private class MainThreadTimer(private val handler: Handler) : RevalidationTimer {
        override fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
            val runnable = Runnable { action() }
            handler.postDelayed(runnable, delayMillis)
            return { handler.removeCallbacks(runnable) }
        }
    }
}

/** Hooks for announcing the prompt (step 2). Receives no screen text beyond the prompt itself. */
interface OverlayListener {
    /** [speakPressed]: true once [말하기] started listening on this prompt (checked when speaking). */
    fun onShown(prompt: ChoicePrompt, speakPressed: () -> Boolean = { false }) = Unit
    fun onDismissed() = Unit

    /** REVALIDATING began: stop speaking now (default: same as [onDismissed]). */
    fun onRevalidating() = onDismissed()

    /** Same popup confirmed; [prompt] is now in the same window. */
    fun onRevalidated(prompt: ChoicePrompt) = Unit

    /** The re-check closed the window ([end] = why); the window is already gone. */
    fun onRevalidationClosed(prompt: ChoicePrompt, end: RevalidationEnd) = Unit

    companion object {
        val NONE = object : OverlayListener {}
    }
}
