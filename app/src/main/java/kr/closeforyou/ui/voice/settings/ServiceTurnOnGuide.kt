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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    fun isPersonEvent(eventType: Int, eventText: String, selfFocusText: String?): Boolean =
        isPersonEvent(eventType, eventText, selfFocusText?.let(::listOf))

    /** Same, with several texts TalkBack may focus by itself (null = every focus move counts). */
    fun isPersonEvent(eventType: Int, eventText: String, selfFocusTexts: List<String>?): Boolean = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> true
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> selfFocusTexts == null || selfFocusTexts.none { eventText.contains(it) }
        else -> false
    }

    /**
     * 재호 10/30: whenever the screen with [다음] (first run 2/5) is shown — first entry, back from
     * system settings, back from 3/5 — and the service is on and TalkBack is on, TalkBack's focus goes
     * to [다음], whatever was spoken. Not tied to "켜졌어요" (LG: moving after that line, even
     * re-checked 5×400ms, lost to TalkBack putting focus on "5단계 중 2단계" when the window came back).
     */
    fun placeOnShow(serviceOn: Boolean, talkBackOn: Boolean, hasTarget: Boolean): Boolean = serviceOn && talkBackOn && hasTarget

    /** First move this long after the screen shows, so TalkBack's own "back to this window" focus is done. */
    const val PLACE_DELAY_MILLIS = 1_500L

    /** Then checked this often until this deadline; moved again whenever [다음] lost the focus. */
    const val PLACE_CHECK_MILLIS = 500L
    const val PLACE_DEADLINE_MILLIS = 6_000L

    /** Done once [다음] held the focus on this many checks in a row. */
    const val PLACE_STABLE_CHECKS = 2

    /** One check: the new count of checks in a row that [다음] held the focus. */
    fun nextStable(targetFocused: Boolean, stable: Int): Int = if (targetFocused) stable + 1 else 0

    /** Move (again) only when [다음] does not hold the focus and the person did not take over. */
    fun shouldMoveAgain(targetFocused: Boolean, personInteracted: Boolean): Boolean = !targetFocused && !personInteracted

    /**
     * Back from settings with the service on. TalkBack off → our TTS at once. TalkBack on: its live
     * region announcement is drowned by the screen's own pane title (LG 10/30: only "5단계 중 2단계" was
     * read), so with "안내 맡기기" on our TTS says it after TalkBack ([GuideSpeech.AFTER_TALKBACK]);
     * off, TalkBack alone (the state box still says 켜짐). With a [다음] to land on, it waits for that
     * first move ([SPEAK_AFTER_PLACED_MILLIS], at most [SPEAK_WAIT_MILLIS]) so TalkBack's "다음, 버튼"
     * is not spoken over it.
     */
    fun turnedOn(touchExplorationOn: Boolean, takeoverOn: Boolean): GuideSpeech = guide(touchExplorationOn, takeoverOn)

    const val SPEAK_AFTER_PLACED_MILLIS = 1_000L
    const val SPEAK_WAIT_MILLIS = 3_000L

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
 * 4. back with the service on: "켜졌어요" ([ServiceGuidePolicy.turnedOn]);
 * 5. separately, every time this screen shows with the service on and TalkBack on, TalkBack's focus
 *    goes to the node tagged [focusWhenServiceOnTag] (the first run's [다음], [ServiceGuidePolicy.placeOnShow]),
 *    checked and moved again for a few seconds; the same "person took over" rule stops it.
 * Returns what the [서비스 켜기] button calls.
 */
@Composable
fun rememberServiceTurnOn(
    serviceOn: Boolean,
    onOpenFailed: () -> Unit,
    /** Testtag of the button to land on whenever the screen shows with the service on (null = never, e.g. settings). */
    focusWhenServiceOnTag: String? = null,
    /**
     * What TalkBack focuses by itself on this screen when it comes back (the step line, the target's
     * label): focus landing there while we place [focusWhenServiceOnTag] is not the person.
     */
    returnSelfFocusTexts: List<String> = emptyList(),
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
    // The screen's own window, for placing [다음].
    val screenFocus = remember { OwnWindowFocus { engineHolder[0]?.stop() } }
    // Counts resumes, so placing runs again whenever the screen comes back (from settings or elsewhere).
    var shown by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        shown++
        onPauseOrDispose {}
    }
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

    LaunchedEffect(serviceOn, shown) {
        if (!serviceOn) return@LaunchedEffect
        val returned = awaitingReturn
        awaitingReturn = false
        val tag = focusWhenServiceOnTag
        val place = ServiceGuidePolicy.placeOnShow(serviceOn, talkBackOn(), tag != null)
        val speech = if (returned) {
            ServiceGuidePolicy.turnedOn(talkBackOn(), UiVoicePreferences.get(context).current().talkBackTakeover)
        } else {
            GuideSpeech.NONE
        }
        if (!place && speech == GuideSpeech.NONE) return@LaunchedEffect
        val firstPlaced = CompletableDeferred<Unit>()
        screenFocus.startWatching(selfFocusTexts = returnSelfFocusTexts)
        try {
            coroutineScope {
                if (place && tag != null) launch {
                    placeFocus(screenFocus, tag) { firstPlaced.complete(Unit) }
                    firstPlaced.complete(Unit)
                }
                if (speech != GuideSpeech.NONE) launch {
                    if (place) {
                        withTimeoutOrNull(ServiceGuidePolicy.SPEAK_WAIT_MILLIS) { firstPlaced.await() }
                        delay(ServiceGuidePolicy.SPEAK_AFTER_PLACED_MILLIS)
                    } else if (speech == GuideSpeech.AFTER_TALKBACK) {
                        delay(ServiceGuidePolicy.AFTER_TALKBACK_MILLIS)
                    }
                    if (!screenFocus.personInteracted) speakWhenReady(engine(), turnedOnLine)
                }
            }
        } finally {
            screenFocus.stopWatching()
        }
    }

    return { showGuide = true }
}

/**
 * Puts TalkBack's focus on [tag] and keeps it there for a few seconds ([ServiceGuidePolicy]):
 * waits for TalkBack's own focus on the returning window, then checks every [PLACE_CHECK_MILLIS]
 * and moves again while the node does not hold it, until it held it twice in a row, the deadline,
 * or the person took over. [onFirstMove] runs after the first move.
 */
private suspend fun placeFocus(focus: OwnWindowFocus, tag: String, onFirstMove: () -> Unit) {
    delay(ServiceGuidePolicy.PLACE_DELAY_MILLIS)
    var stable = 0
    var moved = false
    var waited = 0L
    while (waited <= ServiceGuidePolicy.PLACE_DEADLINE_MILLIS) {
        if (focus.personInteracted) return
        val focused = focus.isFocused(tag)
        stable = ServiceGuidePolicy.nextStable(focused, stable)
        if (stable >= ServiceGuidePolicy.PLACE_STABLE_CHECKS) return
        if (ServiceGuidePolicy.shouldMoveAgain(focused, focus.personInteracted)) {
            focus.moveTo(tag)
            if (!moved) {
                moved = true
                onFirstMove()
            }
        }
        delay(ServiceGuidePolicy.PLACE_CHECK_MILLIS)
        waited += ServiceGuidePolicy.PLACE_CHECK_MILLIS
    }
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
internal class OwnWindowFocus(private val onPersonInteracted: () -> Unit) {
    private var view: View? = null
    private var container: View? = null
    private var previousDelegate: View.AccessibilityDelegate? = null
    private var title: String? = null
    private var watchTexts: List<String>? = null
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
                if (watching && !ownMove && ServiceGuidePolicy.isPersonEvent(event.eventType, event.text.joinToString(" "), watchTexts)) {
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

    /** [selfFocusTexts] null = the text given to [attach] (or every focus move when that was null). */
    fun startWatching(selfFocusTexts: List<String>? = null) {
        watchTexts = selfFocusTexts ?: title?.let(::listOf)
        touched = false
        watching = true
    }

    /** The person touched or swiped since [startWatching]. */
    val personInteracted: Boolean get() = touched

    /** The tagged node holds the accessibility focus (Compose answers from what TalkBack last focused). */
    fun isFocused(tag: String): Boolean {
        val dialogView = view ?: return false
        val root = (dialogView as? RootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return false
        val id = findTagged(root, tag) ?: return false
        return dialogView.accessibilityNodeProvider?.createAccessibilityNodeInfo(id)?.isAccessibilityFocused == true
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
        val provider = dialogView.accessibilityNodeProvider ?: return false
        ownMove = true
        return try {
            // Input focus too (no effect in touch mode on some devices); TalkBack may follow it.
            provider.performAction(id, AccessibilityNodeInfo.ACTION_FOCUS, null)
            provider.performAction(id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
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
