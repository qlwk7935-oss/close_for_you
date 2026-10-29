package kr.closeforyou.ui.voice.settings

import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kr.closeforyou.R
import kr.closeforyou.ui.voice.AndroidTtsEngine
import kr.closeforyou.ui.voice.SpeechEngine
import kr.closeforyou.ui.voice.SpeechReadiness
import kr.closeforyou.ui.voice.overlay.TalkBackTakeoverFeature

/** When the guide or "켜졌어요" is also spoken by our TTS (pure, JVM-tested). */
enum class GuideSpeech { NOW, AFTER_TALKBACK, NONE }

object ServiceGuidePolicy {
    /**
     * The guide dialog: TalkBack off → our TTS reads it at once. TalkBack on → TalkBack reads the
     * dialog title itself; our TTS reads the steps after it only when the person left
     * "선택 창이 뜨면 안내를 맡기기" on (same consent as the takeover, 6-0), otherwise TalkBack alone.
     */
    fun guide(touchExplorationOn: Boolean, takeoverOn: Boolean): GuideSpeech = when {
        !touchExplorationOn -> GuideSpeech.NOW
        TalkBackTakeoverFeature.enabled && takeoverOn -> GuideSpeech.AFTER_TALKBACK
        else -> GuideSpeech.NONE
    }

    /**
     * After the guide was spoken with TalkBack on ([GuideSpeech.AFTER_TALKBACK], so "안내 맡기기" is on):
     * focus goes to [설정 열기] only when the whole speech played and the person did not take over.
     */
    fun moveFocusToOpen(mode: GuideSpeech, completed: Boolean, personInteracted: Boolean): Boolean =
        mode == GuideSpeech.AFTER_TALKBACK && completed && !personInteracted

    /**
     * An accessibility event that means the person took over: a finger touch, or TalkBack focus
     * landing on something other than [selfFocusText] (what TalkBack focuses by itself, e.g. the
     * dialog title). [selfFocusText] null = every focus move counts (watching starts after TalkBack
     * settled, [AFTER_TALKBACK_MILLIS]).
     */
    fun isPersonEvent(eventType: Int, eventText: String, selfFocusText: String?): Boolean = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> true
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> selfFocusText == null || !eventText.contains(selfFocusText)
        else -> false
    }

    /**
     * Back from settings with the service on. TalkBack off → our TTS at once. TalkBack on: its live
     * region announcement is drowned by the screen's own pane title (LG 10/30: only "5단계 중 2단계" was
     * read), so with "안내 맡기기" on our TTS says it after TalkBack ([GuideSpeech.AFTER_TALKBACK]);
     * off, TalkBack alone (the state box still says 켜짐).
     */
    fun turnedOn(touchExplorationOn: Boolean, takeoverOn: Boolean): GuideSpeech = guide(touchExplorationOn, takeoverOn)

    /** After "켜졌어요": focus to [다음] only after the whole line with TalkBack, and nobody took over. */
    fun moveFocusAfterTurnedOn(mode: GuideSpeech, completed: Boolean, personInteracted: Boolean, hasTarget: Boolean): Boolean =
        hasTarget && moveFocusToOpen(mode, completed, personInteracted)

    /** Waits this long for TTS to get ready, and lets TalkBack read the dialog title first. */
    const val READY_TIMEOUT_MILLIS = 3_000L
    const val AFTER_TALKBACK_MILLIS = TalkBackTakeoverFeature.SPEECH_DELAY_MILLIS
}

/**
 * [서비스 켜기] with directions (TalkBack users found it hard to search the accessibility list):
 * 1. a dialog says where to go — swipe to the app's name under "서비스" (A's `app_name`, so it follows
 *    a rename), double-tap, turn it on, press 허용, come back — and our TTS reads it ([ServiceGuidePolicy]);
 * 2. with TalkBack and "안내 맡기기" on, once that speech finished, TalkBack's focus goes to [설정 열기]
 *    (like the prompt's move to "'닫기' 버튼 눌러 주기", 6-0). Not when it was stopped or the person
 *    touched or swiped in the dialog. Focus moves only inside this dialog and nothing is pressed;
 * 3. [설정 열기] opens system accessibility settings asking it to scroll to our service
 *    ([AndroidCoreSetupStatus.openServiceSettings]); devices that ignore that (LG, 10/30) show the list;
 * 4. back with the service on: "켜졌어요" ([ServiceGuidePolicy.turnedOn]); with TalkBack and
 *    "안내 맡기기" on, after that line TalkBack's focus goes to the node tagged [focusAfterTurnedOnTag]
 *    on this screen (the first run's [다음]); the same "person took over" rule applies.
 * Returns what the [서비스 켜기] button calls.
 */
@Composable
fun rememberServiceTurnOn(
    serviceOn: Boolean,
    onOpenFailed: () -> Unit,
    /** Testtag of the button to land on after "켜졌어요" (null = do not move, e.g. settings). */
    focusAfterTurnedOnTag: String? = null,
): () -> Unit {
    val context = LocalContext.current
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var awaitingReturn by rememberSaveable { mutableStateOf(false) }
    // Created only when something is spoken; shut down with the screen.
    val engineHolder = remember { arrayOfNulls<SpeechEngine>(1) }
    fun engine(): SpeechEngine =
        engineHolder[0] ?: AndroidTtsEngine(context, SpeechRates.provider(context)).also { engineHolder[0] = it }
    DisposableEffect(Unit) { onDispose { engineHolder[0]?.shutdown() } }
    val talkBackOn = {
        context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }
    val currentOpenFailed by rememberUpdatedState(onOpenFailed)
    val focus = remember { OwnWindowFocus { engineHolder[0]?.stop() } }
    // The screen's own window, for "켜졌어요" → [다음].
    val screenFocus = remember { OwnWindowFocus { engineHolder[0]?.stop() } }
    val screenView = LocalView.current
    DisposableEffect(screenView) {
        screenFocus.attach(screenView, selfFocusText = null)
        onDispose { screenFocus.detach() }
    }

    val appName = stringResource(R.string.app_name)
    val title = stringResource(R.string.ui_setup_guide_title)
    val lines = listOf(
        stringResource(if (talkBackOn()) R.string.ui_setup_guide_find_talkback else R.string.ui_setup_guide_find, appName),
        stringResource(R.string.ui_setup_guide_turn_on),
        stringResource(R.string.ui_setup_guide_come_back),
        stringResource(R.string.ui_setup_guide_where),
    )
    val turnedOnLine = stringResource(R.string.ui_setup_service_turned_on)

    if (showGuide) {
        LaunchedEffect(Unit) {
            val mode = ServiceGuidePolicy.guide(talkBackOn(), UiVoicePreferences.get(context).current().talkBackTakeover)
            if (mode == GuideSpeech.NONE) return@LaunchedEffect
            if (mode == GuideSpeech.AFTER_TALKBACK) delay(ServiceGuidePolicy.AFTER_TALKBACK_MILLIS)
            focus.startWatching()
            val completed = speakWhenReady(engine(), lines.joinToString("\n"))
            val touched = focus.stopWatching()
            if (ServiceGuidePolicy.moveFocusToOpen(mode, completed, touched)) focus.moveTo(OwnWindowFocus.OPEN_TAG)
        }
        AlertDialog(
            onDismissRequest = {
                engineHolder[0]?.stop()
                showGuide = false
            },
            title = { Text(title, modifier = Modifier.semantics { heading() }) },
            text = {
                // The dialog's own window: watch the person touching it, find [설정 열기] in it.
                val dialogView = LocalView.current
                DisposableEffect(dialogView) {
                    focus.attach(dialogView, selfFocusText = title)
                    onDispose { focus.detach() }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    lines.forEach { Text(it, fontSize = UiType.body) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        engineHolder[0]?.stop()
                        showGuide = false
                        if (AndroidCoreSetupStatus.openServiceSettings(context)) awaitingReturn = true else currentOpenFailed()
                    },
                    modifier = Modifier.testTag(OwnWindowFocus.OPEN_TAG),
                ) { Text(stringResource(R.string.ui_setup_guide_open), fontSize = UiType.body) }
            },
            dismissButton = {
                TextButton(onClick = {
                    engineHolder[0]?.stop()
                    showGuide = false
                }) { Text(stringResource(R.string.ui_settings_cancel), fontSize = UiType.body) }
            },
        )
    }

    LaunchedEffect(serviceOn, awaitingReturn) {
        if (!awaitingReturn || !serviceOn) return@LaunchedEffect
        awaitingReturn = false
        val mode = ServiceGuidePolicy.turnedOn(talkBackOn(), UiVoicePreferences.get(context).current().talkBackTakeover)
        if (mode == GuideSpeech.NONE) return@LaunchedEffect
        // Lets TalkBack finish what it reads when the screen comes back (its pane title).
        if (mode == GuideSpeech.AFTER_TALKBACK) delay(ServiceGuidePolicy.AFTER_TALKBACK_MILLIS)
        screenFocus.startWatching()
        val completed = speakWhenReady(engine(), turnedOnLine)
        val touched = screenFocus.stopWatching()
        val tag = focusAfterTurnedOnTag
        if (ServiceGuidePolicy.moveFocusAfterTurnedOn(mode, completed, touched, tag != null) && tag != null) screenFocus.moveTo(tag)
    }

    return { showGuide = true }
}

/** True only when the whole text was played. */
private suspend fun speakWhenReady(engine: SpeechEngine, text: String): Boolean {
    withTimeoutOrNull(ServiceGuidePolicy.READY_TIMEOUT_MILLIS) {
        engine.readiness.first { it != SpeechReadiness.INITIALIZING }
    }
    return engine.speak(text)
}

/**
 * TalkBack focus inside one of our own windows (the guide dialog, or the screen itself), so no
 * accessibility service is needed.
 * - Watching: the Compose view's container sees every accessibility event it sends
 *   (`onRequestSendAccessibilityEvent`). [ServiceGuidePolicy.isPersonEvent] (touch, or a swipe away
 *   from [selfFocusText]) means the person took over: our speech stops and focus is not moved.
 *   Event texts are only compared, never logged. Otherwise the delegate only passes events on.
 * - Moving: finds the tagged node's semantics id and performs ACTION_ACCESSIBILITY_FOCUS on the
 *   Compose view's own AccessibilityNodeProvider (its virtual view id is the semantics id).
 * Main thread only. Device unverified.
 */
private class OwnWindowFocus(private val onPersonInteracted: () -> Unit) {
    private var view: View? = null
    private var container: View? = null
    private var previousDelegate: View.AccessibilityDelegate? = null
    private var title: String? = null
    private var watching = false
    private var touched = false
    private var ownMove = false

    fun attach(dialogView: View, selfFocusText: String?) {
        detach()
        view = dialogView
        title = selfFocusText
        val parent = dialogView.parent as? View ?: return
        val previous = parent.accessibilityDelegate
        container = parent
        previousDelegate = previous
        parent.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
                if (watching && !ownMove && ServiceGuidePolicy.isPersonEvent(event.eventType, event.text.joinToString(" "), title)) {
                    watching = false
                    touched = true
                    onPersonInteracted()
                }
                return previous?.onRequestSendAccessibilityEvent(host, child, event)
                    ?: super.onRequestSendAccessibilityEvent(host, child, event)
            }
        }
    }

    fun detach() {
        container?.accessibilityDelegate = previousDelegate
        container = null
        previousDelegate = null
        view = null
        watching = false
    }

    fun startWatching() {
        touched = false
        watching = true
    }

    /** Stops watching; true when the person touched or swiped meanwhile. */
    fun stopWatching(): Boolean {
        watching = false
        return touched
    }

    fun moveTo(tag: String): Boolean {
        val dialogView = view ?: return false
        val root = (dialogView as? RootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return false
        val id = findTagged(root, tag) ?: return false
        ownMove = true
        return try {
            dialogView.accessibilityNodeProvider?.performAction(id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) == true
        } finally {
            ownMove = false
        }
    }

    private fun findTagged(node: SemanticsNode, tag: String): Int? {
        if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) return node.id
        return node.children.firstNotNullOfOrNull { findTagged(it, tag) }
    }

    companion object {
        const val OPEN_TAG = "ui_guide_open"
    }
}
