package kr.closeforyou.ui.voice.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
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

/** One step while keeping TalkBack's focus on [다음] ([ServiceGuidePolicy.placeStep]). */
enum class PlaceStep { WAIT, MOVE, STOP }

/**
 * What C's focus moves did, for debug logs only (enum names, never screen text or package names):
 * the next device run can line them up with TalkBack's own log.
 */
enum class FocusTrace {
    GUIDE_MOVE,
    PLACE_START,
    /** An event in the grace period after resume (ignored): a hover, or a focus move. */
    RETURN_HOVER,
    RETURN_FOCUS,
    PLACE_ALREADY,
    PLACE_MOVE,
    PLACE_LOST,
    /** The person took over: a finger touch, or a swipe to an item other than the step line / [다음]. */
    PLACE_PERSON_HOVER,
    PLACE_PERSON_SWIPE,
    PLACE_DONE,
    TURNED_ON_SPOKEN,
}

/** Why the person was taken to have taken over. */
enum class PersonReason { HOVER, SWIPE_FOCUS }

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
    fun isPersonEvent(eventType: Int, eventText: String, selfFocusTexts: List<String>?): Boolean =
        personReason(eventType, eventText, selfFocusTexts) != null

    /** Why an event counts as the person ([PersonReason]), or null when it does not. */
    fun personReason(eventType: Int, eventText: String, selfFocusTexts: List<String>?): PersonReason? = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> PersonReason.HOVER
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED ->
            PersonReason.SWIPE_FOCUS.takeIf { selfFocusTexts == null || selfFocusTexts.none { eventText.contains(it) } }
        else -> null
    }

    /**
     * 재호 10/30: whenever the screen with [다음] (first run 2/5) is shown — first entry, back from
     * system settings, back from 3/5 — and the service is on and TalkBack is on, TalkBack's focus goes
     * to [다음], whatever was spoken. Not tied to "켜졌어요" (LG: moving after that line, even
     * re-checked 5×400ms, lost to TalkBack putting focus on "5단계 중 2단계" when the window came back).
     */
    fun placeOnShow(serviceOn: Boolean, talkBackOn: Boolean, hasTarget: Boolean): Boolean = serviceOn && talkBackOn && hasTarget

    /**
     * First move this long after the screen shows, so TalkBack's own "back to this window" focus is done.
     * It is also a grace period: nothing in it counts as the person (LG 10/30, e71ee6f: TalkBack's
     * back gesture and its refocus right after resume were taken for the person, so nothing moved).
     * Watching starts only at the first move.
     */
    const val PLACE_DELAY_MILLIS = 1_500L

    /**
     * Then watched this often until this deadline. LG 10/30 (4a366f6): moving on a timer, with input
     * focus and accessibility focus together, made TalkBack log 3 focus actions twice and read
     * "다음" again. So: one ACTION_ACCESSIBILITY_FOCUS, and another only after TalkBack's own focus
     * event shows it went elsewhere ([PlaceStep.MOVE]), at most [PLACE_MAX_MOVES] in all.
     */
    const val PLACE_CHECK_MILLIS = 500L
    const val PLACE_DEADLINE_MILLIS = 6_000L
    const val PLACE_MAX_MOVES = 3

    /** One watch step of placing the focus on [다음]. */
    fun placeStep(personInteracted: Boolean, lostSinceMove: Boolean, moves: Int): PlaceStep = when {
        personInteracted -> PlaceStep.STOP
        !lostSinceMove -> PlaceStep.WAIT
        moves >= PLACE_MAX_MOVES -> PlaceStep.STOP
        else -> PlaceStep.MOVE
    }

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
    /** The target's visible label; TalkBack focus landing on anything else means [다음] lost it. */
    focusTargetText: String? = null,
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
    val debugLog = remember { DebugFocusLog(context) }
    // The screen's own window, for placing [다음].
    val screenFocus = remember { OwnWindowFocus(onGraceEvent = debugLog::trace) { engineHolder[0]?.stop() } }
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
            if (ServiceGuidePolicy.moveFocusToOpen(mode, completed, touched)) {
                debugLog.trace(FocusTrace.GUIDE_MOVE)
                focus.moveTo(OwnWindowFocus.OPEN_TAG)
            }
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
        // Only once the screen is resumed (shown > 0): an Activity recreated on return first composes
        // before it resumes, and that run would be cancelled half-way.
        if (!serviceOn || shown == 0) return@LaunchedEffect
        // Kept (saved state) until the line is really spoken, so a recreated screen says it once.
        val returned = awaitingReturn
        val tag = focusWhenServiceOnTag
        val place = ServiceGuidePolicy.placeOnShow(serviceOn, talkBackOn(), tag != null)
        val speech = if (returned) {
            ServiceGuidePolicy.turnedOn(talkBackOn(), UiVoicePreferences.get(context).current().talkBackTakeover)
        } else {
            GuideSpeech.NONE
        }
        if (!place && speech == GuideSpeech.NONE) return@LaunchedEffect
        val firstPlaced = CompletableDeferred<Unit>()
        // Grace period first: events now are TalkBack's return (and its back gesture), not the person.
        screenFocus.startGrace()
        try {
            coroutineScope {
                if (place && tag != null) launch {
                    debugLog.trace(FocusTrace.PLACE_START)
                    delay(ServiceGuidePolicy.PLACE_DELAY_MILLIS)
                    screenFocus.startWatching(selfFocusTexts = returnSelfFocusTexts, targetText = focusTargetText)
                    placeFocus(screenFocus, tag, debugLog::trace) { firstPlaced.complete(Unit) }
                    firstPlaced.complete(Unit)
                }
                if (speech != GuideSpeech.NONE) launch {
                    if (place) {
                        withTimeoutOrNull(ServiceGuidePolicy.SPEAK_WAIT_MILLIS) { firstPlaced.await() }
                        delay(ServiceGuidePolicy.SPEAK_AFTER_PLACED_MILLIS)
                    } else if (speech == GuideSpeech.AFTER_TALKBACK) {
                        delay(ServiceGuidePolicy.AFTER_TALKBACK_MILLIS)
                    }
                    if (!screenFocus.personInteracted) {
                        awaitingReturn = false
                        debugLog.trace(FocusTrace.TURNED_ON_SPOKEN)
                        speakWhenReady(engine(), turnedOnLine)
                    }
                }
            }
        } finally {
            screenFocus.stopWatching()
        }
    }

    return { showGuide = true }
}

/**
 * Puts TalkBack's focus on [tag] once (the caller waited [ServiceGuidePolicy.PLACE_DELAY_MILLIS] and
 * started watching); skipped when it is already there. Moves again only after
 * TalkBack's focus event shows it went elsewhere ([ServiceGuidePolicy.placeStep]), until the
 * deadline or the person took over. [onFirstMove] runs after the first move (or the skip).
 */
private suspend fun placeFocus(focus: OwnWindowFocus, tag: String, trace: (FocusTrace) -> Unit, onFirstMove: () -> Unit) {
    var moves = 0
    if (focus.isFocused(tag)) {
        trace(FocusTrace.PLACE_ALREADY)
    } else {
        focus.moveTo(tag)
        moves++
        trace(FocusTrace.PLACE_MOVE)
    }
    onFirstMove()
    var waited = 0L
    while (waited < ServiceGuidePolicy.PLACE_DEADLINE_MILLIS) {
        delay(ServiceGuidePolicy.PLACE_CHECK_MILLIS)
        waited += ServiceGuidePolicy.PLACE_CHECK_MILLIS
        when (ServiceGuidePolicy.placeStep(focus.personInteracted, focus.lostSinceMove, moves)) {
            PlaceStep.WAIT -> Unit
            PlaceStep.STOP -> return trace(
                when (focus.personReason) {
                    PersonReason.HOVER -> FocusTrace.PLACE_PERSON_HOVER
                    PersonReason.SWIPE_FOCUS -> FocusTrace.PLACE_PERSON_SWIPE
                    null -> FocusTrace.PLACE_DONE
                },
            )
            PlaceStep.MOVE -> {
                trace(FocusTrace.PLACE_LOST)
                focus.moveTo(tag)
                moves++
                trace(FocusTrace.PLACE_MOVE)
            }
        }
    }
    trace(FocusTrace.PLACE_DONE)
}

/** Debug builds only: one enum name per focus step (no text, no ids, no packages). */
private class DebugFocusLog(context: Context) {
    private val enabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun trace(step: FocusTrace) {
        if (enabled) Log.d(TAG, step.name)
    }

    private companion object {
        const val TAG = "CfyFocus"
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
internal class OwnWindowFocus(
    /** Debug trace of events ignored in the grace period ([startGrace]). */
    private val onGraceEvent: (FocusTrace) -> Unit = {},
    private val onPersonInteracted: () -> Unit,
) {
    private var view: View? = null
    private var container: View? = null
    private var previousDelegate: View.AccessibilityDelegate? = null
    private var title: String? = null
    private var watchTexts: List<String>? = null
    private var targetText: String? = null
    private var lost = false
    private var grace = false
    private var reason: PersonReason? = null
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
                val text = event.text.joinToString(" ")
                if (watching && !ownMove && event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                    val target = targetText
                    if (target == null || !text.contains(target)) lost = true
                }
                if (grace && !watching && !ownMove) {
                    when (event.eventType) {
                        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> onGraceEvent(FocusTrace.RETURN_HOVER)
                        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> onGraceEvent(FocusTrace.RETURN_FOCUS)
                        else -> Unit
                    }
                }
                val why = if (watching && !ownMove) ServiceGuidePolicy.personReason(event.eventType, text, watchTexts) else null
                if (why != null) {
                    watching = false
                    touched = true
                    reason = why
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
    fun startWatching(selfFocusTexts: List<String>? = null, targetText: String? = null) {
        watchTexts = selfFocusTexts ?: title?.let(::listOf)
        this.targetText = targetText
        touched = false
        reason = null
        lost = false
        grace = false
        watching = true
    }

    /** Not watching yet: forget an earlier "person took over", only trace what comes in. */
    fun startGrace() {
        watching = false
        touched = false
        reason = null
        grace = true
    }

    /** Why [personInteracted] became true (null = it did not). */
    val personReason: PersonReason? get() = reason

    /** TalkBack's focus went somewhere other than the target since the last [moveTo]. */
    val lostSinceMove: Boolean get() = lost

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
        grace = false
        return touched
    }

    fun moveTo(tag: String): Boolean {
        val dialogView = view ?: return false
        val root = (dialogView as? RootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return false
        val id = findTagged(root, tag) ?: return false
        val provider = dialogView.accessibilityNodeProvider ?: return false
        lost = false
        ownMove = true
        return try {
            // Accessibility focus only: adding input focus made TalkBack record extra focus actions (LG 10/30).
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
