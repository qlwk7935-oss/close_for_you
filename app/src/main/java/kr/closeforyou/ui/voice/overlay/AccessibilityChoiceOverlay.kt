package kr.closeforyou.ui.voice.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import kr.closeforyou.R
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.UserChoice
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.ChoicePromptContent
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner

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
) : ChoicePromptPresenter {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null

    override val isShowing: Boolean get() = view != null

    override fun show(prompt: ChoicePrompt, onChoice: (UserChoice) -> Unit) {
        dismiss()
        val lifecycleOwner = OverlayLifecycleOwner()
        val composeView = ComposeView(service).apply {
            setContent {
                MaterialTheme {
                    ChoicePromptContent(
                        prompt = prompt,
                        onOption = { option ->
                            val choice = ChoicePromptPlanner.toUserChoice(prompt, option)
                            if (choice.kind != ChoiceKind.READ_CONTENT) dismiss()
                            onChoice(choice)
                        },
                        modifier = Modifier.navigationBarsPadding().padding(16.dp),
                    )
                }
            }
        }
        lifecycleOwner.attachTo(composeView)
        windowManager.addView(composeView, layoutParams())
        view = composeView
        owner = lifecycleOwner
        listener.onShown(prompt)
    }

    override fun dismiss() {
        val current = view ?: return
        view = null
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
    fun onShown(prompt: ChoicePrompt) = Unit
    fun onDismissed() = Unit

    companion object {
        val NONE = object : OverlayListener {}
    }
}
