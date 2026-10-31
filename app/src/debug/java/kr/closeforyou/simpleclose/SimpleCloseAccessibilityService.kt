package kr.closeforyou.simpleclose

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.core.contract.AnnouncementKind
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.model.ActionAuthorization
import kr.closeforyou.core.model.ActionDecision
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.ClickOutcome
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.DecisionSource
import kr.closeforyou.core.model.HandlingReport
import kr.closeforyou.core.model.NextStep
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.SafetyReason
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.ui.voice.AnnouncementBatcher
import kr.closeforyou.ui.voice.PressFailure
import kr.closeforyou.ui.voice.PressResultSpeech
import kr.closeforyou.ui.voice.PressSuccess
import kr.closeforyou.ui.voice.SpeechHandlingListener
import kr.closeforyou.ui.voice.TtsPopupAnnouncer
import kr.closeforyou.ui.voice.UiFormat
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.RememberChoiceRules
import kr.closeforyou.ui.voice.overlay.AccessibilityChoiceOverlay
import kr.closeforyou.ui.voice.overlay.PromptAnnouncementListener
import kr.closeforyou.ui.voice.overlay.PromptAnswer
import kr.closeforyou.ui.voice.overlay.PromptControlsSupport
import kr.closeforyou.ui.voice.overlay.PromptShowResult
import kr.closeforyou.ui.voice.overlay.PromptSpeech
import kr.closeforyou.ui.voice.settings.UiVoicePreferences

/**
 * C's simple close engine (plan B; see [SimpleCloseEngineFeature]). Debug builds only, declared in the
 * debug manifest. Turn on either this service or A's "닫아줄게", never both.
 *
 * Flow per window event of a helped app: debounce → [SimpleTreeCollector] → [PopupRootFinder] →
 * [SimplePopupAnalysis] → B's [DecisionPopupClassifier] → [SimpleCloseDecider] → one ACTION_CLICK
 * (preset) or C's prompt ([AccessibilityChoiceOverlay]) → re-read after 1.5 s → C's sentences
 * ([SpeechHandlingListener] for preset/deny, [PressResultSpeech] for the person's own press).
 *
 * Safety kept whatever the demo switches say: the click target is looked up again right before the
 * press and must still carry the same name and role inside the same popup root; one press, no retry,
 * no coordinate tap; decision and scam popups are only explained; no screen text or package in logs.
 */
class SimpleCloseAccessibilityService : AccessibilityService() {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val collector = SimpleTreeCollector(this)
    private val classifier = DecisionPopupClassifier()
    private lateinit var log: DebugSimpleLog
    private var tts: TtsPopupAnnouncer? = null
    private var overlay: AccessibilityChoiceOverlay? = null
    private var batcher: AnnouncementBatcher? = null
    private var handlingListener: SpeechHandlingListener? = null
    private var pending: Job? = null
    private var busy = false
    private var screenVersion = 0L
    private var inputMethods: Set<String> = emptySet()

    /** The popup the prompt on screen is about, and when each fingerprint was last handled. */
    private var prompted: SimplePopup? = null
    private val handledAt = LinkedHashMap<String, Long>()

    private val preferences by lazy { UiVoicePreferences.get(this) }
    private val corePreferences by lazy { CorePreferences.get(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        log = DebugSimpleLog(this)
        if (!SimpleCloseEngineFeature.enabled) {
            log.trace(SimpleTrace.SERVICE_DISABLED_BY_FEATURE)
            return
        }
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        inputMethods = (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).inputMethodList.mapTo(mutableSetOf()) { it.packageName }
        val announcer = TtsPopupAnnouncer.create(this).also { tts = it }
        val format = UiFormat { id, args -> getString(id, *args) }
        val batch = AnnouncementBatcher(scope, SpeechHandlingListener.batchFlush(announcer, format)).also { batcher = it }
        handlingListener = SpeechHandlingListener(announcer, batch, format)
        overlay = AccessibilityChoiceOverlay(
            this,
            PromptAnnouncementListener(this, announcer, scope, mode = { preferences.current().promptAnnounceMode }, detail = { preferences.current().detail }),
            PromptControlsSupport.create(this, announcer),
        )
        log.trace(SimpleTrace.SERVICE_CONNECTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || overlay == null || event.eventType !in WINDOW_EVENTS) return
        val eventPackage = event.packageName?.toString()
        if (eventPackage == packageName) {
            // Our own prompt window and TTS: never a popup to handle.
            log.trace(SimpleTrace.EVENT_OWN_WINDOW_IGNORED)
            return
        }
        if (!SimpleAppAllowance.allows(eventPackage, packageName, preferences.current(), corePreferences.currentSettings().allowedPackages, inputMethods)) {
            log.trace(SimpleTrace.SKIPPED_NOT_ALLOWED_APP)
            return
        }
        screenVersion += 1
        pending?.cancel()
        pending = scope.launch {
            delay(SimpleCloseEngineFeature.DEBOUNCE_MILLIS)
            if (!busy) inspect()
        }
    }

    /** Reads the screen once and acts on what it finds. Main thread. */
    private suspend fun inspect() {
        val read = collector.read()
        if (read == null) {
            log.trace(SimpleTrace.TREE_UNAVAILABLE)
            return
        }
        val root = PopupRootFinder.find(read.nodes, read.screen, read.activeWindowBounded)
        if (root == null) {
            log.trace(SimpleTrace.NO_POPUP_ROOT)
            promptGone()
            return
        }
        log.trace(when (root.kind) {
            PopupRootKind.ALERT_DIALOG -> SimpleTrace.DETECTED_ALERTDIALOG
            PopupRootKind.DIALOG_WINDOW -> SimpleTrace.DETECTED_DIALOG_WINDOW
            PopupRootKind.DIM_OVERLAY -> SimpleTrace.DETECTED_DIM_OVERLAY
        })
        val popup = SimplePopupAnalysis.build(read.nodes, root, read.packageName, read.windowId, read.screen, screenVersion, SystemClock.uptimeMillis())
        val shown = prompted
        if (shown != null && shown.fingerprint == popup.fingerprint && overlay?.isShowing == true) return
        if (shown != null && shown.fingerprint != popup.fingerprint) promptGone()
        val now = SystemClock.uptimeMillis()
        val last = handledAt[popup.fingerprint]
        if (last != null && now - last < SimpleCloseEngineFeature.SAME_POPUP_COOLDOWN_MILLIS) {
            log.trace(SimpleTrace.SAME_POPUP_COOLDOWN)
            return
        }
        handledAt[popup.fingerprint] = now
        if (handledAt.size > 32) handledAt.remove(handledAt.keys.first())

        val classification = classifier.classify(popup.analysis)
        val suspicious = RememberChoiceRules.suspiciousByB(popup.analysis)
        log.trace(when {
            suspicious -> SimpleTrace.CLASSIFIED_SUSPICIOUS
            classification == PopupClassification.ADVERTISEMENT -> SimpleTrace.CLASSIFIED_AD
            classification == PopupClassification.COUPON -> SimpleTrace.CLASSIFIED_COUPON
            classification == PopupClassification.NOTICE -> SimpleTrace.CLASSIFIED_NOTICE
            classification == PopupClassification.USER_DECISION -> SimpleTrace.CLASSIFIED_DECISION
            else -> SimpleTrace.CLASSIFIED_UNKNOWN
        })
        if (popup.scan.forbiddenCount > 0) log.trace(SimpleTrace.BLOCKED_FORBIDDEN)
        if (popup.scan.unlabeledCount > 0) log.trace(SimpleTrace.UNLABELED_SKIPPED)
        log.trace(if (popup.scan.candidates.isEmpty()) SimpleTrace.CANDIDATE_NONE else SimpleTrace.CANDIDATE_FOUND)

        val analysis = popup.analysis.copy(classification = classification)
        val classified = popup.copy(analysis = analysis)
        when (val decision = SimpleCloseDecider.decide(classification, suspicious, popup.scan.candidates, preferences.current())) {
            is SimpleDecision.AutoClose -> {
                log.trace(SimpleTrace.DECIDED_AUTO_CLOSE)
                val approval = ActionDecision(analysis.token, ActionAuthorization.ALLOW_ONCE, persistentSetOf(), decision.candidate.id, DecisionSource.PRESET, NextStep.EXECUTE)
                val result = pressAndConfirm(classified, decision.candidate)
                handlingListener?.onHandled(HandlingReport(analysis.token, classification, approval, result))
            }
            SimpleDecision.DenyDecision -> {
                log.trace(SimpleTrace.DECIDED_DENY_DECISION)
                val deny = ActionDecision(analysis.token, ActionAuthorization.DENY, persistentSetOf(SafetyReason.IMPORTANT_DECISION), null, DecisionSource.PRESET, NextStep.DENY)
                handlingListener?.onHandled(HandlingReport(analysis.token, classification, deny))
            }
            SimpleDecision.DenySuspicious -> {
                log.trace(SimpleTrace.DECIDED_DENY_SUSPICIOUS)
                val deny = ActionDecision(analysis.token, ActionAuthorization.DENY, persistentSetOf(SafetyReason.SUSPICIOUS_CONTENT), null, DecisionSource.PRESET, NextStep.DENY)
                handlingListener?.onHandled(HandlingReport(analysis.token, classification, deny))
            }
            SimpleDecision.Ask -> {
                log.trace(SimpleTrace.DECIDED_ASK)
                ask(classified, classification)
            }
        }
    }

    private fun ask(popup: SimplePopup, classification: PopupClassification) {
        val presenter = overlay ?: return
        val prompt = ChoicePromptPlanner.plan(
            popup.analysis, classification, labelPolicy = SimpleLabelPolicy,
            rememberChoicesEnabled = false, preset = preferences.current().handlingPreset,
        )
        prompted = popup
        val result = presenter.present(prompt) { answer -> onAnswer(popup, answer) }
        if (result != PromptShowResult.SHOWN) {
            prompted = null
            log.trace(SimpleTrace.PROMPT_NOT_SHOWN)
            return
        }
        log.trace(SimpleTrace.ASKED)
    }

    private fun onAnswer(popup: SimplePopup, answer: PromptAnswer) {
        if (answer !is PromptAnswer.Picked) return
        val choice = answer.choice
        when (choice.kind) {
            ChoiceKind.READ_CONTENT -> {
                log.trace(SimpleTrace.USER_CHOSE_READ)
                val announcer = tts ?: return
                scope.launch { announcer.announce(AnnouncementRequest(choice.token, AnnouncementKind.CONTENT, PromptSpeech.content(this@SimpleCloseAccessibilityService, popup.analysis))) }
            }
            ChoiceKind.CLOSE_ONCE, ChoiceKind.SKIP_ONCE -> {
                log.trace(SimpleTrace.USER_CHOSE_PRESS)
                prompted = null
                val candidate = popup.scan.candidates.firstOrNull { it.id == choice.candidateId } ?: return
                scope.launch {
                    busy = true
                    try {
                        val result = pressAndConfirm(popup, candidate)
                        speakOwnPress(result, choice.kind)
                    } finally {
                        busy = false
                    }
                }
            }
            else -> {
                log.trace(SimpleTrace.USER_CHOSE_KEEP)
                prompted = null
            }
        }
    }

    /** The person's own press: "닫았어요" is batched; anything else is said at once, no follow-up press. */
    private suspend fun speakOwnPress(result: ActionResult, kind: ChoiceKind) {
        val announcer = tts ?: return
        if (PressSuccess.isConfirmed(result)) {
            AnnouncementBatcher.outcomeOf(result, kind)?.let { batcher?.add(it) }
            return
        }
        val failure = PressResultSpeech.failureOf(result) ?: PressFailure.NOT_CONFIRMED
        announcer.announce(AnnouncementRequest(null, AnnouncementKind.ACTION_RESULT, PressResultSpeech.sentence(failure, false, ::getString)))
    }

    /**
     * Re-reads the target right before the press (same path, same name, same role, inside the same
     * popup root), clicks it or its nearest clickable ancestor within the root exactly once, then
     * reads the screen again after [SimpleCloseEngineFeature.CONFIRM_DELAY_MILLIS].
     */
    private suspend fun pressAndConfirm(popup: SimplePopup, candidate: CloseCandidate): ActionResult {
        val token = popup.analysis.token
        busy = true
        try {
            val fresh = collector.read()
            val freshRoot = fresh?.let { PopupRootFinder.find(it.nodes, it.screen, it.activeWindowBounded) }
            val same = fresh != null && freshRoot != null && fresh.packageName == token.packageName &&
                SimplePopupAnalysis.build(fresh.nodes, freshRoot, fresh.packageName, fresh.windowId, fresh.screen, screenVersion, SystemClock.uptimeMillis()).fingerprint == popup.fingerprint
            val target = fresh?.nodes?.firstOrNull { it.path == candidate.nodePath }
            val sameName = target != null && CloseButtonFinder.nameOf(target, fresh.nodes) == candidate.label && SimpleLabelRules.roleOf(candidate.label) == candidate.role
            if (!same || target == null || !sameName) {
                log.trace(SimpleTrace.RECHECK_TARGET_GONE)
                return ActionResult(token, ActionStatus.STALE, SafetyReason.STALE_SCREEN, attempted = false)
            }
            val clicked = clickOnce(candidate.nodePath!!, popup.root.path)
            if (clicked == null) {
                log.trace(SimpleTrace.CLICK_TARGET_NOT_CLICKABLE)
                return ActionResult(token, ActionStatus.BLOCKED, SafetyReason.UNVERIFIED_TARGET, attempted = false)
            }
            if (!clicked) {
                log.trace(SimpleTrace.CLICK_REJECTED)
                return ActionResult(token, ActionStatus.ATTEMPT_FAILED, SafetyReason.UNVERIFIED_TARGET, attempted = true, outcome = ClickOutcome.UNKNOWN)
            }
            log.trace(SimpleTrace.CLICKED)
            delay(SimpleCloseEngineFeature.CONFIRM_DELAY_MILLIS)
            val after = collector.read()
            val outcome = when {
                after == null -> ClickOutcome.UNKNOWN
                after.packageName != token.packageName -> ClickOutcome.LEFT_APP
                else -> {
                    val root = PopupRootFinder.find(after.nodes, after.screen, after.activeWindowBounded)
                    val still = root != null && SimplePopupAnalysis.build(after.nodes, root, after.packageName, after.windowId, after.screen, screenVersion, SystemClock.uptimeMillis()).fingerprint == popup.fingerprint
                    if (still) ClickOutcome.STILL_PRESENT else ClickOutcome.CLOSED_CONFIRMED
                }
            }
            log.trace(when (outcome) {
                ClickOutcome.CLOSED_CONFIRMED -> SimpleTrace.CLOSED_CONFIRMED
                ClickOutcome.LEFT_APP -> SimpleTrace.LEFT_APP
                else -> SimpleTrace.NOT_CLOSED
            })
            return ActionResult(token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = true, outcome = outcome)
        } finally {
            busy = false
        }
    }

    /** True/false = performAction result; null = no clickable node between the target and the popup root. */
    private fun clickOnce(targetPath: String, rootPath: String): Boolean? {
        var path = targetPath
        while (true) {
            val node = collector.nodeAt(path) ?: return null
            try {
                if (node.isClickable && node.isEnabled) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } finally {
                @Suppress("DEPRECATION") node.recycle()
            }
            if (path == rootPath || '/' !in path) return null
            path = path.substringBeforeLast('/')
        }
    }

    private fun promptGone() {
        if (prompted == null) return
        prompted = null
        overlay?.dismiss()
        log.trace(SimpleTrace.POPUP_GONE_PROMPT_DISMISSED)
    }

    override fun onInterrupt() {
        pending?.cancel()
        promptGone()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        pending?.cancel()
        pending = null
        prompted = null
        overlay?.dismiss()
        overlay = null
        batcher?.cancel()
        batcher = null
        handlingListener?.clear()
        handlingListener = null
        tts?.close()
        tts = null
        handledAt.clear()
        scope.cancel()
    }

    private companion object {
        val WINDOW_EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        )
    }
}

/** Debug builds only: one enum name per step under `CfySimple` (no text, no ids, no packages). */
private class DebugSimpleLog(service: AccessibilityService) : SimpleTraceSink {
    private val enabled = service.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override fun trace(code: SimpleTrace) {
        if (enabled) Log.d(TAG, code.name)
    }

    private companion object {
        const val TAG = "CfySimple"
    }
}
