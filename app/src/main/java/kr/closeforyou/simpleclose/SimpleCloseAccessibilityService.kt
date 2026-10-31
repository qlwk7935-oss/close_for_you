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
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.core.contract.AnnouncementKind
import kr.closeforyou.core.contract.AnnouncementReceipt
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.contract.PopupAnnouncer
import kr.closeforyou.core.model.ActionAuthorization
import kr.closeforyou.core.model.ActionDecision
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.ButtonLabelRules
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.ClickOutcome
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.DecisionSource
import kr.closeforyou.core.model.HandlingReport
import kr.closeforyou.core.model.NextStep
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.SafetyReason
import kr.closeforyou.R
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.ui.voice.AnnouncementBatcher
import kr.closeforyou.ui.voice.AutoDecisionSpeech
import kr.closeforyou.ui.voice.PressFailure
import kr.closeforyou.ui.voice.PressResultSpeech
import kr.closeforyou.ui.voice.PressSuccess
import kr.closeforyou.ui.voice.SpeechHandlingListener
import kr.closeforyou.ui.voice.TtsPopupAnnouncer
import kr.closeforyou.ui.voice.UiFormat
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptOption
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.choice.RememberChoiceRules
import kr.closeforyou.ui.voice.overlay.AccessibilityChoiceOverlay
import kr.closeforyou.ui.voice.overlay.PromptAnnounceMode
import kr.closeforyou.ui.voice.overlay.PromptAnnouncementListener
import kr.closeforyou.ui.voice.overlay.PromptAnswer
import kr.closeforyou.ui.voice.overlay.PromptControlsSupport
import kr.closeforyou.ui.voice.overlay.PromptShowResult
import kr.closeforyou.ui.voice.overlay.PromptSpeech
import kr.closeforyou.ui.voice.overlay.TalkBackTakeoverPlan
import kr.closeforyou.ui.voice.settings.UiVoicePreferences
import kr.closeforyou.ui.voice.settings.autoCloseKinds

/**
 * C's simple close engine (plan B; see [SimpleCloseEngineFeature]). Declared in the main manifest as
 * "팝업처리도우미" (integration/popup-helper). Turn on either this service or A's "닫아줄게", never both.
 *
 * Flow per window event of a helped app: debounce → [SimpleTreeCollector] (off the main thread) →
 * [PopupRootFinder] → [SimplePopupAnalysis] → B's [DecisionPopupClassifier] → [SimpleCloseDecider] →
 * one ACTION_CLICK (preset) or C's prompt ([AccessibilityChoiceOverlay]) → the popup root is polled
 * for up to 1.5 s → C's sentences ([SpeechHandlingListener] for preset/deny, [PressResultSpeech] for
 * the person's own press).
 *
 * Safety kept whatever the demo switches say: the click target is looked up again right before the
 * press and must still carry the same name inside the same, still-present popup root; one press, no
 * retry, no coordinate tap; decision and scam popups are only explained; no screen text or package in logs.
 */
class SimpleCloseAccessibilityService : AccessibilityService() {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val collector = SimpleTreeCollector(this)
    private val classifier = DecisionPopupClassifier()
    private lateinit var log: DebugSimpleLog
    private var tts: PopupAnnouncer? = null
    private var ttsEngine: TtsPopupAnnouncer? = null
    private var overlay: AccessibilityChoiceOverlay? = null
    private var resultBanner: TalkBackResultBanner? = null
    private var batcher: AnnouncementBatcher? = null
    private var handlingListener: SpeechHandlingListener? = null
    /** Inspection vs action jobs; an event never cancels a running press ([EngineJobs]). */
    private var jobs = EngineJobs(scope)
    private var screenVersion = 0L
    private var lastInspectAt = 0L
    /** F5: when the first helped-app event of the current search came (0 = none pending); debug latency only. */
    private var firstEventAt = 0L
    /** F10: the last read that found no popup (0 = none yet); the popup appeared after it. */
    private var lastNoRootAt = 0L
    /** F13: reads in the current search (debug numbers only): all, and those the collection guard refused. */
    private var searchReads = 0
    private var searchUnavailable = 0
    /** F13: the person's press already moved TalkBack's focus off the popup before our window went. */
    private var focusedBeforeClose = false
    /**
     * F14: TalkBack focus moves in the app during a press that we did not make (the page or Chrome
     * restoring its own focus after the popup closed); debug number only. [lastOwnFocusAt]: our own
     * last move, whose echo event is not counted.
     */
    private var focusMovesByOthers = 0
    private var lastOwnFocusAt = 0L
    /**
     * F15 (demo mode): the watch that takes TalkBack's focus back from the page after a press ([FocusReclaim]):
     * armed at CLICKED, ended [SimpleCloseEngineFeature.FOCUS_RECLAIM_WINDOW_MILLIS] after the confirmed
     * close, by a touch or gesture of the person, or at [SimpleCloseEngineFeature.FOCUS_RECLAIM_MAX].
     * [preClickTarget]: a copy of the node focused right before the press, its first target.
     */
    private var reclaim: FocusReclaim? = null
    private var preClickTarget: AccessibilityNodeInfo? = null
    /** F17: the popup the focused result card is about, so the card's removal moves the focus to the page, not back there. */
    private var lastClosedMemory: PopupMemory? = null
    /** F18: skip buttons already pressed (window + path), not pressed again for [SimpleCloseEngineFeature.SAME_POPUP_COOLDOWN_MILLIS]. */
    private val skippedAt = LinkedHashMap<String, Long>()
    /**
     * F20 (LG 11/1 11번가: a false "closed", the sheet found again and pressed again, TalkBack read it in a loop),
     * by the popup's words ([SnapshotToken.contentDigest], stable when the sheet moves): popups whose automatic
     * close failed (never pressed automatically again, asked), popups the person chose to leave (not asked or
     * pressed again while they stay), and the popup an automatic press is working on right now.
     */
    private val autoFailed = LinkedHashSet<String>()
    /** F21: per popup (same key), the normalized names of buttons pressed there that did not close it: never offered again. */
    private val failedButtons = LinkedHashMap<String, MutableSet<String>>()
    private val kept = LinkedHashSet<String>()
    private var pressing: String? = null
    /** F20: YouTube app events seen / refused by 도와줄 앱 (debug log at most every few seconds). */
    private var videoEventLoggedAt = 0L
    private var inputMethods: Set<String> = emptySet()

    /** The popup the prompt on screen is about, when each popup key was last seen, and keys already explained. */
    private var prompted: SimplePopup? = null
    private val seenAt = LinkedHashMap<String, Long>()
    private val denied = LinkedHashSet<String>()

    private val preferences by lazy { UiVoicePreferences.get(this) }
    private val corePreferences by lazy { CorePreferences.get(this) }
    private val installedApps by lazy { AndroidSimpleAppLookup(packageManager) }

    private fun allowsApp(pkg: String?): Boolean = SimpleAppAllowance.allows(
        pkg, packageName, preferences.current(), corePreferences.currentSettings().allowedPackages,
        inputMethods, lookup = installedApps::find,
    )

    private fun eventIsInScope(pkg: String?): Boolean = SimpleAppAllowance.isInScope(
        pkg, packageName, preferences.current(), corePreferences.currentSettings().allowedPackages, inputMethods,
    )

    private val inspectionEvents = SimpleInspectionEvents(
        jobs = { jobs }, promptShowing = { prompted != null }, lastInspection = { lastInspectAt },
        now = SystemClock::uptimeMillis, appInScope = ::eventIsInScope,
        onAccepted = {
            screenVersion += 1
            if (prompted == null && firstEventAt == 0L) firstEventAt = SystemClock.uptimeMillis()
        }, inspect = ::inspect,
        log = SimpleTraceSink { log.trace(it) }, onSearchExhausted = ::endSearch,
    )

    private fun endSearch() {
        firstEventAt = 0L
        searchReads = 0
        searchUnavailable = 0
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        log = DebugSimpleLog(this)
        if (!SimpleCloseEngineFeature.enabled) {
            log.trace(SimpleTrace.SERVICE_DISABLED_BY_FEATURE)
            return
        }
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        jobs = EngineJobs(scope)
        inspectionEvents.resetConnection()
        endSearch()
        lastNoRootAt = 0L
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        inputMethods = (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).inputMethodList.mapTo(mutableSetOf()) { it.packageName }
        val engine = TtsPopupAnnouncer.create(this).also { ttsEngine = it }
        val announcer: PopupAnnouncer = SpeechKindLoggingAnnouncer(engine, log).also { tts = it }
        val format = UiFormat { id, args -> getString(id, *args) }
        val batch = AnnouncementBatcher(scope, SpeechHandlingListener.batchFlush(announcer, format)).also { batcher = it }
        handlingListener = SpeechHandlingListener(announcer, batch, format)
        overlay = AccessibilityChoiceOverlay(
            this,
            // F20: with TalkBack off nothing else would say the prompt: our TTS always speaks it then.
            PromptAnnouncementListener(this, announcer, scope, mode = {
                if (resultBanner?.talkBackOn() == true) preferences.current().promptAnnounceMode else PromptAnnounceMode.PANE_TITLE_AND_TTS
            }, detail = { preferences.current().detail }),
            PromptControlsSupport.create(this, announcer),
            beforeClose = ::focusPageBeforeOurWindowGoes,
        )
        resultBanner = TalkBackResultBanner(this) {
            lastOwnFocusAt = SystemClock.uptimeMillis()
            lastClosedMemory?.let { collector.focusPageItem(it, beforeClick = true) }
        }
        log.trace(SimpleTrace.SERVICE_CONNECTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || overlay == null) return
        // F15: the person touches the screen or makes a gesture: the focus is theirs again.
        if (event.eventType in USER_TOUCH_EVENTS) {
            // F17: the double tap that chose [닫기] on our window can arrive after the watch began: not a new touch.
            val watch = reclaim
            if (watch != null && SystemClock.uptimeMillis() - watch.armedAt > SimpleCloseEngineFeature.FOCUS_RECLAIM_TOUCH_GRACE_MILLIS) {
                endReclaim(SimpleTrace.FOCUS_RECLAIM_STOPPED_BY_USER)
            }
            return
        }
        if (event.eventType !in WINDOW_EVENTS) return
        val eventPackage = event.packageName?.toString()
        // Our own prompt window and TTS: never a popup to handle (not logged: it would drown the rest).
        if (eventPackage == packageName) return
        if (eventPackage == VideoAdSkip.YOUTUBE_APP) {
            // F20 (LG 11/1: no VIDEO_* line at all): do YouTube's events reach us, and is it a helped app?
            val now = SystemClock.uptimeMillis()
            if (now - videoEventLoggedAt > VIDEO_EVENT_LOG_MILLIS) {
                videoEventLoggedAt = now
                log.trace(if (eventIsInScope(eventPackage)) SimpleTrace.VIDEO_EVENT_SEEN else SimpleTrace.VIDEO_APP_NOT_ALLOWED)
            }
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED && jobs.busy &&
            SystemClock.uptimeMillis() - lastOwnFocusAt > OWN_FOCUS_ECHO_MILLIS) {
            focusMovesByOthers += 1
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) reclaim?.let { focusMovedDuringReclaim(it, event) }
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> SimpleInspectionEvent.TALKBACK_FOCUS
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> SimpleInspectionEvent.CONTENT
            else -> SimpleInspectionEvent.WINDOW
        }
        inspectionEvents.onEvent(eventPackage, kind)
    }

    /**
     * Reads the screen once (off the main thread) and acts on what it finds. Main thread. Returns
     * false only when nothing was there to act on (no tree, no popup root), so the caller may read again.
     */
    private suspend fun inspect(expectedPackage: String, trigger: DetectTrigger): Boolean {
        lastInspectAt = SystemClock.uptimeMillis()
        val shownBefore = prompted
        if (shownBefore != null) {
            // Cheap check first: is the prompted popup still there? No full read unless it is gone.
            when (withContext(Dispatchers.Default) { collector.rootStillPresent(shownBefore.root) }) {
                true -> return true
                false -> promptGone()
                null -> return true
            }
        }
        // Full package/label authorization stays off main and is repeated for the actual root
        // before traversal. Event scope admission is not collection permission.
        val readStart = SystemClock.uptimeMillis()
        // F20: the YouTube app's skip button sits outside any popup: its tree is always read whole.
        val read = withContext(Dispatchers.Default) { collector.read(expectedPackage, ::allowsApp, probe = expectedPackage != VideoAdSkip.YOUTUBE_APP) }
        val readMillis = SystemClock.uptimeMillis() - readStart
        searchReads += 1
        if (read == null) {
            searchUnavailable += 1
            log.trace(SimpleTrace.TREE_UNAVAILABLE)
            return false
        }
        // F18: a video ad's own skip button comes first (it is not a popup); a countdown waits for the next read.
        var videoAdWaiting = false
        if (SimpleCloseEngineFeature.VIDEO_AD_SKIP) {
            val video = VideoAdSkip.scan(read.nodes, read.screen)
            // F20: why or why not, as codes (only on a screen with an ad mark, a skip-like node, or in YouTube).
            if (video.diag != VideoAdDiag.NONE || read.packageName == VideoAdSkip.YOUTUBE_APP) {
                log.trace(if (video.adMarker) SimpleTrace.VIDEO_AD_MARKER_FOUND else SimpleTrace.VIDEO_AD_MARKER_MISSING)
                videoTrace(video.diag)?.let(log::trace)
            }
            when (val result = video.result) {
                is VideoAdSkipResult.Found -> {
                    endSearch()
                    skipVideoAd(read, result.candidate)
                    return true
                }
                VideoAdSkipResult.Waiting -> videoAdWaiting = true
                null -> Unit
            }
        }
        // F16: an app popup window's tree has its own window for the live look-ups.
        val root = PopupRootFinder.find(read.nodes, read.screen, read.activeWindowBounded)?.let { found ->
            val windowId = read.popupWindowId ?: return@let found
            found.copy(windowId = windowId, kind = if (found.kind == PopupRootKind.DIALOG_WINDOW) PopupRootKind.APP_WINDOW else found.kind)
        }
        if (root == null) {
            log.trace(if (videoAdWaiting) SimpleTrace.VIDEO_SKIP_WAITING else SimpleTrace.NO_POPUP_ROOT)
            lastNoRootAt = SystemClock.uptimeMillis()
            return false
        }
        val popup = SimplePopupAnalysis.build(read.nodes, root, read.packageName, read.windowId, read.screen, screenVersion, SystemClock.uptimeMillis())
        val key = popup.coarseKey
        val now = SystemClock.uptimeMillis()
        // Seen within the cooldown (it stayed on screen, or was just handled): once is enough, quietly.
        val last = seenAt[key]
        seenAt[key] = now
        if (seenAt.size > 32) seenAt.remove(seenAt.keys.first())
        if (last != null && now - last < SimpleCloseEngineFeature.SAME_POPUP_COOLDOWN_MILLIS) {
            log.trace(SimpleTrace.SAME_POPUP_COOLDOWN)
            endSearch()
            return true
        }
        log.trace(when (root.kind) {
            PopupRootKind.ALERT_DIALOG -> SimpleTrace.DETECTED_ALERTDIALOG
            PopupRootKind.DIALOG_WINDOW -> SimpleTrace.DETECTED_DIALOG_WINDOW
            PopupRootKind.DIM_OVERLAY -> SimpleTrace.DETECTED_DIM_OVERLAY
            PopupRootKind.FRONT_LAYER -> SimpleTrace.DETECTED_FRONT_LAYER
            PopupRootKind.BOTTOM_SHEET -> SimpleTrace.DETECTED_BOTTOM_SHEET
            PopupRootKind.APP_WINDOW -> SimpleTrace.DETECTED_APP_WINDOW
        })
        // F10: first event -> here overstates when the page itself loaded first; the gap since the last
        // read that saw no popup bounds when the popup really appeared. Which trigger caught it, too.
        log.trace(trigger.trace)
        if (firstEventAt != 0L) log.latency("DETECT_LATENCY_MS", now - firstEventAt)
        if (lastNoRootAt != 0L) log.latency("DETECT_GAP_MS", now - lastNoRootAt)
        // F13: where the time went: reads this search took, how many the guard refused, the last read's cost.
        log.latency("DETECT_READS", searchReads.toLong())
        log.latency("DETECT_TREE_UNAVAILABLE", searchUnavailable.toLong())
        log.latency("TREE_READ_MS", readMillis)
        log.latency("TREE_VISITED", read.visited.toLong())
        log.latency("TREE_SKIPPED_OFFSCREEN", read.skippedSubtrees.toLong())
        log.trace(when (read.mode) {
            TreeReadMode.POPUP_SUBTREE -> SimpleTrace.READ_POPUP_SUBTREE
            TreeReadMode.FULL -> SimpleTrace.READ_FULL
            TreeReadMode.FULL_AFTER_PROBE -> SimpleTrace.READ_FULL_AFTER_PROBE
            TreeReadMode.APP_WINDOW -> SimpleTrace.READ_APP_WINDOW
        })
        log.latency("TREE_PROBE_VISITED", read.probeVisited.toLong())
        if (read.probeOutcome != ProbeOutcome.NOT_RUN) log.trace(SimpleTrace.valueOf("PROBE_${read.probeOutcome.name}"))
        endSearch()
        val words = "${read.windowId}:${popup.analysis.token.contentDigest}"
        // F20: an automatic press is still working on this very popup (it may look new after it moved): wait for it.
        if (words == pressing) {
            log.trace(SimpleTrace.SAME_POPUP_COOLDOWN)
            return true
        }
        // F20: the person chose to leave it: not asked or pressed again while it stays.
        if (words in kept) {
            log.trace(SimpleTrace.SAME_POPUP_KEPT)
            return true
        }

        val byB = classifier.classify(popup.analysis)
        val suspicious = RememberChoiceRules.suspiciousByB(popup.analysis)
        // Demo hint: an image-only dialog with a close button that B cannot judge from words (옥션).
        val miss = if (SimpleCloseEngineFeature.IMAGE_DIALOG_AD_HINT) ImageDialogAdHint.check(popup, byB, suspicious) else AdHintMiss.NOT_UNKNOWN
        val hinted = SimpleCloseEngineFeature.IMAGE_DIALOG_AD_HINT && miss == null
        if (hinted) log.trace(SimpleTrace.AD_HINT_IMAGE_DIALOG)
        // F6: why not, when B could not judge (a classified popup needs no hint, so it is not logged).
        else if (miss != null && miss != AdHintMiss.NOT_UNKNOWN) log.trace(SimpleTrace.adHintMiss(miss))
        val classification = if (hinted) PopupClassification.ADVERTISEMENT else byB
        log.trace(when {
            suspicious -> SimpleTrace.CLASSIFIED_SUSPICIOUS
            classification == PopupClassification.ADVERTISEMENT -> SimpleTrace.CLASSIFIED_AD
            classification == PopupClassification.COUPON -> SimpleTrace.CLASSIFIED_COUPON
            classification == PopupClassification.NOTICE -> SimpleTrace.CLASSIFIED_NOTICE
            classification == PopupClassification.USER_DECISION -> SimpleTrace.CLASSIFIED_DECISION
            else -> SimpleTrace.CLASSIFIED_UNKNOWN
        })
        if (popup.scan.forbiddenCount > 0) log.trace(SimpleTrace.BLOCKED_FORBIDDEN)
        if (popup.scan.riskCount > 0) log.trace(SimpleTrace.BLOCKED_RISKY)
        if (popup.scan.unlabeledCount > 0) log.trace(SimpleTrace.UNLABELED_SKIPPED)
        log.trace(if (popup.scan.candidates.isEmpty()) SimpleTrace.CANDIDATE_NONE else SimpleTrace.CANDIDATE_FOUND)

        val analysis = popup.analysis.copy(classification = classification)
        val classified = popup.copy(analysis = analysis)
        val decided = SimpleCloseDecider.decide(classification, suspicious, popup.scan.candidates, preferences.current(), popup.scan.forbiddenCount, popup.scan.riskCount)
        // F20: an automatic close failed on this popup before: never pressed automatically again (AGENTS 6-6), asked.
        val decision = if (decided is SimpleDecision.AutoClose && words in autoFailed) {
            log.trace(SimpleTrace.AUTO_CLOSE_SKIPPED_AFTER_FAIL)
            SimpleDecision.Ask
        } else {
            decided
        }
        when (decision) {
            is SimpleDecision.AutoClose -> {
                log.trace(when {
                    decision.strong -> SimpleTrace.DECIDED_AUTO_CLOSE_STRONG
                    decision.unknown -> SimpleTrace.DECIDED_AUTO_CLOSE_UNKNOWN
                    else -> SimpleTrace.DECIDED_AUTO_CLOSE
                })
                val approval = ActionDecision(analysis.token, ActionAuthorization.ALLOW_ONCE, persistentSetOf(), decision.candidate.id, DecisionSource.PRESET, NextStep.EXECUTE)
                // Its own job: the inspection this runs in is replaced by the next window event.
                pressing = words
                jobs.runAction {
                    // F11: the person did not ask; move TalkBack's focus only if it was in the popup.
                    val inside = withContext(Dispatchers.Default) { collector.accessibilityFocusInside(classified.root.bounds) }
                    log.trace(when (inside) {
                        true -> SimpleTrace.FOCUS_WAS_IN_POPUP
                        false -> SimpleTrace.FOCUS_WAS_OUTSIDE
                        null -> SimpleTrace.FOCUS_WAS_UNKNOWN
                    })
                    val moveFocus = inside == true
                    val result = try {
                        pressAndConfirm(classified, decision.candidate, moveFocus, watchFocus = moveFocus)
                    } finally {
                        if (pressing == words) pressing = null
                    }
                    if (PressSuccess.isConfirmed(result)) {
                        speakClosedThenFocusPage(classified, moveFocus)
                    } else if (result.attempted && result.outcome != ClickOutcome.LEFT_APP) {
                        // F20: pressed and not closed (or back again): no card, no focus move, no second press; the
                        // person is asked right away, without the button that failed.
                        endReclaim(null)
                        tts?.stop()
                        rememberFailed(classified, decision.candidate)
                        log.trace(SimpleTrace.AUTO_CLOSE_FAILED_ASKED)
                        if (prompted == null) ask(classified, classification, talkBackReadIt = false, failedNow = true)
                    } else {
                        tts?.stop()
                        handlingListener?.onHandled(HandlingReport(analysis.token, classification, approval, result))
                    }
                }
            }
            SimpleDecision.DenyDecision -> {
                log.trace(SimpleTrace.DECIDED_DENY_DECISION)
                if (!denyOnce(key)) return true
                // F20 ("못 닫으면 반드시 묻기", 재호 11/1): a decision popup gets the prompt too, with nothing to press
                // for the person: [버튼으로 이동] to its buttons, [내용 다시 읽기], [그대로 두기]. TalkBack off: our TTS says it.
                log.trace(SimpleTrace.DECISION_ASKED)
                ask(classified, classification, talkBackReadIt = false)
            }
            SimpleDecision.DenySuspicious -> {
                log.trace(SimpleTrace.DECIDED_DENY_SUSPICIOUS)
                if (!denyOnce(key)) return true
                val deny = ActionDecision(analysis.token, ActionAuthorization.DENY, persistentSetOf(SafetyReason.SUSPICIOUS_CONTENT), null, DecisionSource.PRESET, NextStep.DENY)
                handlingListener?.onHandled(HandlingReport(analysis.token, classification, deny))
            }
            SimpleDecision.Ask -> {
                log.trace(SimpleTrace.DECIDED_ASK)
                // F14: TalkBack is on the popup (it has been reading it): our first item stays short.
                val inside = withContext(Dispatchers.Default) { collector.accessibilityFocusInside(classified.root.bounds) }
                log.trace(when (inside) {
                    true -> SimpleTrace.FOCUS_WAS_IN_POPUP
                    false -> SimpleTrace.FOCUS_WAS_OUTSIDE
                    null -> SimpleTrace.FOCUS_WAS_UNKNOWN
                })
                ask(classified, classification, talkBackReadIt = inside == true)
            }
        }
        return true
    }

    /**
     * A confirmed close. [moveFocus] (the person's own press, or TalkBack's focus was in the popup):
     * TalkBack's focus must end outside the popup (a closed web popup can stay in the DOM and TalkBack
     * read it again, LG 10/31). F13 (LG 11/1: moved before the press and again after the close, the
     * popup read twice in between): one move per handling. The focus put on the page before the press
     * is kept while it is still outside; only when it was lost (its node went with the popup) or is in
     * the old popup is it moved once ([PageFocusRules.plan]: the top of the page, the toolbar, the
     * WebView last). Then the sentence the person expects ("광고를 닫았어요"), not waited on: with
     * TalkBack on through TalkBack's own queue ([TalkBackResultBanner], F11), else our TTS.
     */
    private suspend fun speakClosedThenFocusPage(popup: SimplePopup, moveFocus: Boolean) {
        val announcer = tts ?: return
        announcer.stop()
        var movedNow = false
        val watch = reclaim
        val kindOfPopup = AutoDecisionSpeech.kindOf(popup.analysis.classification)
        val closedSentence = getString(R.string.ui_auto_closed_one, getString(AutoDecisionSpeech.objectRes(kindOfPopup)))
        val focusBanner = resultBanner
        if (SimpleCloseEngineFeature.RESULT_BANNER_TAKES_FOCUS && moveFocus && focusBanner != null && focusBanner.talkBackOn()) {
            // F17 (LG 11/1 옥션: toolbar, title, reclaim = three places read): one move after the close, onto the
            // result card itself; TalkBack drops what it was saying and reads "쿠폰을 닫았어요". The reclaim watch
            // (if on) now puts the focus back on the card when the page takes it into the old popup.
            log.latency("FOCUS_MOVES_BY_OTHERS", focusMovesByOthers.toLong())
            lastClosedMemory = PopupMemory.of(popup)
            val shown = focusBanner.show(closedSentence, takeFocus = true) { onScreen, focused ->
                log.trace(if (onScreen) SimpleTrace.RESULT_BANNER_SHOWN else SimpleTrace.RESULT_BANNER_NOT_SHOWN)
                log.trace(if (focused) SimpleTrace.RESULT_BANNER_FOCUSED else SimpleTrace.RESULT_BANNER_FOCUS_FAILED)
                if (focused && watch != null && reclaim === watch) watch.onBanner = true
            }
            if (shown) {
                log.trace(SimpleTrace.RESULT_VIA_TALKBACK)
                if (watch != null && reclaim === watch) {
                    watch.until = SystemClock.uptimeMillis() + SimpleCloseEngineFeature.FOCUS_RECLAIM_WINDOW_MILLIS
                    scope.launch {
                        delay(SimpleCloseEngineFeature.FOCUS_RECLAIM_WINDOW_MILLIS)
                        if (reclaim === watch) endReclaim(null)
                    }
                }
                return
            }
        }
        if (SimpleCloseEngineFeature.FOCUS_PAGE_AFTER_CLOSE && moveFocus) {
            // F14 (LG 11/1: focus put on the toolbar before the press, yet back in the old popup after the
            // close): the page may restore its own focus as the popup closes, and TalkBack follows it. Let
            // that happen first, so our one move is the last one. F15: with the reclaim watch on, no wait:
            // each such move is taken back as it comes.
            if (watch == null) delay(SimpleCloseEngineFeature.FOCUS_SETTLE_MILLIS)
            else watch.until = SystemClock.uptimeMillis() + SimpleCloseEngineFeature.FOCUS_RECLAIM_WINDOW_MILLIS
            log.latency("FOCUS_MOVES_BY_OTHERS", focusMovesByOthers.toLong())
            val inside = withContext(Dispatchers.Default) { collector.accessibilityFocusInside(popup.root.bounds) }
            log.trace(when (inside) {
                false -> SimpleTrace.FOCUS_KEPT_ON_PAGE
                true -> SimpleTrace.FOCUS_AFTER_CLOSE_IN_POPUP
                null -> SimpleTrace.FOCUS_AFTER_CLOSE_LOST
            })
            if (inside != false) {
                lastOwnFocusAt = SystemClock.uptimeMillis()
                val focus = withContext(Dispatchers.Default) { collector.focusPageItemKeeping(PopupMemory.of(popup)) }
                log.trace(SimpleTrace.focusTarget(focus.kind))
                log.trace(if (focus.moved) SimpleTrace.FOCUS_MOVED_TO_PAGE else SimpleTrace.FOCUS_MOVE_FAILED)
                movedNow = focus.moved
                if (focus.node != null && watch != null && reclaim === watch) {
                    watch.target = focus.node
                    watch.lastMoveAt = SystemClock.uptimeMillis()
                }
            }
        }
        if (watch != null && reclaim === watch) {
            // F15: the result goes after the last reclaim, once the page has kept still for a moment.
            val start = SystemClock.uptimeMillis()
            while (reclaim === watch) {
                val now = SystemClock.uptimeMillis()
                if (now - maxOf(start, watch.lastMoveAt) >= SimpleCloseEngineFeature.FOCUS_RECLAIM_QUIET_MILLIS || now >= watch.until) break
                delay(SimpleCloseEngineFeature.FOCUS_RECLAIM_POLL_MILLIS)
            }
            scope.launch {
                delay((watch.until - SystemClock.uptimeMillis()).coerceAtLeast(0))
                if (reclaim === watch) endReclaim(null)
            }
            movedNow = false
        }
        val sentence = closedSentence
        val banner = resultBanner
        if (banner != null && banner.talkBackOn()) {
            // F12: after a focus move TalkBack reads the focused item first; the card comes after it.
            if (movedNow) delay(SimpleCloseEngineFeature.RESULT_AFTER_FOCUS_MILLIS)
            if (banner.show(sentence) { shown, _ -> log.trace(if (shown) SimpleTrace.RESULT_BANNER_SHOWN else SimpleTrace.RESULT_BANNER_NOT_SHOWN) }) {
                log.trace(SimpleTrace.RESULT_VIA_TALKBACK)
                return
            }
        }
        log.trace(SimpleTrace.RESULT_VIA_TTS)
        scope.launch { announcer.announce(AnnouncementRequest(null, AnnouncementKind.ACTION_RESULT, sentence)) }
    }

    /** A deny sentence ("확인이 필요한 팝업이에요", the scam warning) is said once per popup key per session (LG 10/31: it looped). */
    private fun denyOnce(key: String): Boolean {
        if (!denied.add(key)) return false
        if (denied.size > 64) denied.remove(denied.first())
        return true
    }

    /**
     * C's prompt for [popup]. F20: [failedNow] = a press just did not close it: the first line says so ("대신 닫지
     * 못했어요."). F21: buttons that failed on this popup are never offered again (AGENTS 6-6), the next one is.
     * The popup's risky buttons (결제·동의·권한…) are offered as [버튼으로 이동] only, never as a press.
     */
    private fun ask(popup: SimplePopup, classification: PopupClassification, talkBackReadIt: Boolean, failedNow: Boolean = false) {
        val presenter = overlay ?: return
        val planned = ChoicePromptPlanner.plan(
            popup.analysis, classification, labelPolicy = SimpleLabelPolicy,
            rememberChoicesEnabled = false, preset = preferences.current().handlingPreset,
        )
        // Short by default: the kind sentence, then focus on the button. The full text stays behind [내용 다시 읽기].
        // F13: a notice's first words go into the prompt's first item, read by TalkBack (one voice).
        // F14: not when TalkBack has just read the popup itself; then only "닫을까요?" (with a press button).
        val brief = talkBackReadIt && planned.options.any { it.kind == PromptOptionKind.PRESS_CANDIDATE }
        val prompt = if (SimpleCloseEngineFeature.READ_POPUP_TEXT_ON_PROMPT) planned else planned.copy(
            popupText = null,
            shownExcerpt = planned.popupText?.takeIf { classification == PopupClassification.NOTICE && !talkBackReadIt }
                ?.let { TalkBackTakeoverPlan.excerpt(it, SimpleCloseEngineFeature.PROMPT_EXCERPT_CHARS) },
            briefQuestion = brief,
        )
        // F16: nothing named to press, but one X-shaped button: offered as "X 모양 닫기", pressed only on the tap (6-3).
        val x = popup.scan.xButton?.takeIf {
            SimpleCloseEngineFeature.OFFER_X_BUTTON && popup.scan.riskCount == 0 &&
                classification != PopupClassification.USER_DECISION && prompt.options.none { o -> o.kind == PromptOptionKind.PRESS_CANDIDATE }
        }
        val withX = if (x == null) prompt else prompt.copy(
            cannotPressForUser = false,
            options = (listOf(PromptOption(PromptOptionKind.PRESS_CANDIDATE, candidateId = x.id, candidateRole = ButtonRole.CLOSE, buttonLabel = getString(R.string.ui_simple_x_button))) + prompt.options).toImmutableList(),
        )
        if (x != null) log.trace(SimpleTrace.X_BUTTON_OFFERED)
        val shown = SimplePromptOptions.adjust(withX, popup.scan.riskButtons, failedButtons[keyOf(popup)].orEmpty(), failedNow)
        prompted = popup
        focusedBeforeClose = false
        val result = presenter.present(shown) { answer -> onAnswer(popup, answer) }
        if (result != PromptShowResult.SHOWN) {
            prompted = null
            log.trace(SimpleTrace.PROMPT_NOT_SHOWN)
            return
        }
        log.trace(SimpleTrace.ASKED)
    }

    private fun onAnswer(popup: SimplePopup, answer: PromptAnswer) {
        if (answer is PromptAnswer.Move) {
            prompted = null
            moveToButton(popup, answer.request.candidateId)
            return
        }
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
                val candidate = (popup.scan.candidates + listOfNotNull(popup.scan.xButton)).firstOrNull { it.id == choice.candidateId } ?: return
                // Nothing waits for the prompt's speech: stop it and press right away.
                tts?.stop()
                // F13: the focus already left the popup before our window went ([focusPageBeforeOurWindowGoes]); not again.
                val focusedAlready = focusedBeforeClose
                focusedBeforeClose = false
                jobs.runAction {
                    // Our window goes away under TalkBack's focus: it must land on the page, not the popup's leftovers.
                    val result = pressAndConfirm(popup, candidate, moveFocus = !focusedAlready, watchFocus = true)
                    when {
                        PressSuccess.isConfirmed(result) -> speakClosedThenFocusPage(popup, moveFocus = true)
                        // F21: the person's own press did not close it either: asked again at once, without that button.
                        result.attempted && result.outcome != ClickOutcome.LEFT_APP -> {
                            endReclaim(null)
                            tts?.stop()
                            rememberFailed(popup, candidate)
                            log.trace(SimpleTrace.USER_PRESS_FAILED_ASKED)
                            if (prompted == null) ask(popup, popup.analysis.classification, talkBackReadIt = false, failedNow = true)
                        }
                        else -> speakOwnPress(result)
                    }
                }
            }
            else -> {
                log.trace(SimpleTrace.USER_CHOSE_KEEP)
                prompted = null
                // F20: left as it is: not asked or pressed again while it stays.
                if (choice.kind == ChoiceKind.KEEP_OPEN) {
                    kept += "${popup.snapshot.token.windowId}:${popup.analysis.token.contentDigest}"
                    if (kept.size > 32) kept.remove(kept.first())
                }
            }
        }
    }

    /**
     * F20 [버튼으로 이동] (a risky or other button the person wants to find; CLAUDE.local 3-1: 결제·권한은 위치
     * 안내까지): TalkBack on, its focus goes onto that button, nothing is pressed; TalkBack off, our TTS says
     * where it is on the screen. The button's name is spoken, never logged.
     */
    private fun moveToButton(popup: SimplePopup, candidateId: String) {
        log.trace(SimpleTrace.USER_CHOSE_MOVE)
        val candidate = (popup.scan.riskButtons + popup.scan.candidates).firstOrNull { it.id == candidateId } ?: return
        val path = candidate.nodePath ?: return
        if (resultBanner?.talkBackOn() == true) {
            lastOwnFocusAt = SystemClock.uptimeMillis()
            val node = collector.nodeAt(path, popup.root.windowId)
            val moved = try {
                node != null && node.isVisibleToUser && node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            } catch (e: RuntimeException) {
                false
            } finally {
                @Suppress("DEPRECATION") node?.recycle()
            }
            log.trace(if (moved) SimpleTrace.MOVED_TO_BUTTON else SimpleTrace.MOVE_TO_BUTTON_FAILED)
            if (moved) return
        }
        val announcer = tts ?: return
        val place = getString(when (SimplePromptOptions.place(candidate.bounds, popup.snapshot.screenBounds)) {
            ScreenPlace.TOP -> R.string.ui_position_top
            ScreenPlace.MIDDLE -> R.string.ui_position_middle
            ScreenPlace.BOTTOM -> R.string.ui_position_bottom
        })
        val sentence = getString(R.string.ui_move_button_position, candidate.label?.trim().orEmpty(), place)
        log.trace(SimpleTrace.MOVE_POSITION_SPOKEN)
        scope.launch { announcer.announce(AnnouncementRequest(null, AnnouncementKind.POPUP_FOUND, sentence)) }
    }

    /** F20/F21: the popup's key by its words (stable when it moves), as [inspect] uses it. */
    private fun keyOf(popup: SimplePopup): String = "${popup.snapshot.token.windowId}:${popup.analysis.token.contentDigest}"

    /** F21: this button did not close this popup: not pressed automatically again, and not offered again. */
    private fun rememberFailed(popup: SimplePopup, candidate: CloseCandidate) {
        val key = keyOf(popup)
        autoFailed += key
        if (autoFailed.size > 32) autoFailed.remove(autoFailed.first())
        val name = ButtonLabelRules.normalize(candidate.label ?: getString(R.string.ui_simple_x_button))
        failedButtons.getOrPut(key) { mutableSetOf() } += name
        if (failedButtons.size > 32) failedButtons.remove(failedButtons.keys.first())
    }

    private fun videoTrace(diag: VideoAdDiag): SimpleTrace? = when (diag) {
        VideoAdDiag.FOUND, VideoAdDiag.WAITING, VideoAdDiag.NONE -> null
        VideoAdDiag.NO_CANDIDATE -> SimpleTrace.VIDEO_SKIP_NO_CANDIDATE
        VideoAdDiag.LABEL_UNNAMED -> SimpleTrace.VIDEO_SKIP_LABEL_UNNAMED
        VideoAdDiag.NOT_CLICKABLE -> SimpleTrace.VIDEO_SKIP_NOT_CLICKABLE
        VideoAdDiag.OFFSCREEN -> SimpleTrace.VIDEO_SKIP_OFFSCREEN
        VideoAdDiag.NO_AD_MARKER -> SimpleTrace.VIDEO_SKIP_NO_AD_MARKER
        VideoAdDiag.SEVERAL -> SimpleTrace.VIDEO_SKIP_SEVERAL
    }

    /**
     * F13: the person picked a press on our window. Right now, before the window is removed, TalkBack's
     * focus goes to a place that survives the close ([PageFocusRules.plan] with beforeClick: the toolbar,
     * a heading or an item above the popup): removed under TalkBack's focus, our window let it fall back
     * into the popup, which it read again (LG 11/1). Main thread, a bounded walk; nothing is clicked.
     */
    private fun focusPageBeforeOurWindowGoes(answer: PromptAnswer) {
        val kind = (answer as? PromptAnswer.Picked)?.choice?.kind ?: return
        if (kind != ChoiceKind.CLOSE_ONCE && kind != ChoiceKind.SKIP_ONCE) return
        val popup = prompted ?: return
        if (!SimpleCloseEngineFeature.FOCUS_PAGE_BEFORE_CLICK) return
        lastOwnFocusAt = SystemClock.uptimeMillis()
        val focus = collector.focusPageItemKeeping(PopupMemory.of(popup), beforeClick = true)
        log.trace(SimpleTrace.preClickTarget(focus.kind))
        log.trace(if (focus.moved) SimpleTrace.FOCUS_PRE_CLICK_MOVED else SimpleTrace.FOCUS_PRE_CLICK_FAILED)
        focusedBeforeClose = focus.moved
        if (SimpleCloseEngineFeature.FOCUS_RECLAIM_AFTER_CLOSE) {
            // F17 (LG 11/1 11번가: two page moves between CLICKED and CLOSED_CONFIRMED): the watch starts now,
            // as our window goes, not after the click.
            endReclaim(null)
            reclaim = FocusReclaim(PopupMemory.of(popup), focus.node, SystemClock.uptimeMillis())
        } else {
            preClickTarget = focus.node
        }
    }

    /**
     * F15: a focus move while the reclaim watch is on ([FocusReclaimRules]): into the old popup or onto a
     * node that went with it, put back on our target at once (TalkBack drops what it was reading for the
     * new focus); anywhere else on the page, left alone. Main thread; one or two binder calls.
     */
    private fun focusMovedDuringReclaim(watch: FocusReclaim, event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (now > watch.until) {
            endReclaim(null)
            return
        }
        val focused = collector.focusedNode(event)
        try {
            val target = watch.target
            // Before our own move's node is known, its echo is told by time.
            val ours = if (target != null) focused?.second == target else now - lastOwnFocusAt <= OWN_FOCUS_ECHO_MILLIS
            when (FocusReclaimRules.decide(focused?.first, ours, watch.memory, watch.count, SimpleCloseEngineFeature.FOCUS_RECLAIM_MAX)) {
                ReclaimVerdict.OUR_OWN, ReclaimVerdict.LEAVE -> Unit
                ReclaimVerdict.LIMIT -> endReclaim(SimpleTrace.FOCUS_RECLAIM_LIMIT)
                ReclaimVerdict.RECLAIM -> {
                    lastOwnFocusAt = now
                    if (watch.onBanner && resultBanner?.refocus() == true) {
                        watch.count += 1
                        watch.lastMoveAt = now
                    } else if (target != null && collector.refocus(target)) {
                        watch.count += 1
                        watch.lastMoveAt = now
                    } else {
                        // F17: no node to go back to yet (or it is gone): take a page place now, not after the close.
                        val focus = collector.focusPageItemKeeping(watch.memory, beforeClick = true)
                        watch.target = focus.node
                        if (focus.moved) {
                            watch.count += 1
                            watch.lastMoveAt = now
                            log.trace(SimpleTrace.FOCUS_RECLAIM_TARGET_TAKEN)
                        } else {
                            log.trace(SimpleTrace.FOCUS_RECLAIM_NO_TARGET)
                        }
                    }
                }
            }
        } finally {
            @Suppress("DEPRECATION") focused?.second?.recycle()
        }
    }

    private fun endReclaim(reason: SimpleTrace?) {
        val watch = reclaim ?: return
        reclaim = null
        reason?.let(log::trace)
        log.latency("FOCUS_RECLAIMED", watch.count.toLong())
        watch.target = null
    }

    /** The person's own press that did not confirm a close: said at once, no follow-up press (AGENTS 6-6). */
    private suspend fun speakOwnPress(result: ActionResult) {
        val announcer = tts ?: return
        announcer.stop()
        val failure = PressResultSpeech.failureOf(result) ?: PressFailure.NOT_CONFIRMED
        announcer.announce(AnnouncementRequest(null, AnnouncementKind.ACTION_RESULT, PressResultSpeech.sentence(failure, false, ::getString)))
    }

    /**
     * Re-checks the live target right before the press (same popup root still on screen, same node
     * name), clicks it or its nearest clickable ancestor within the root exactly once, then polls the
     * root every [SimpleCloseEngineFeature.CONFIRM_POLL_MILLIS] for up to
     * [SimpleCloseEngineFeature.CONFIRM_DELAY_MILLIS]: gone = closed, another app = left app.
     */
    private suspend fun pressAndConfirm(popup: SimplePopup, candidate: CloseCandidate, moveFocus: Boolean, watchFocus: Boolean): ActionResult {
        val token = popup.analysis.token
        var target = preClickTarget
        preClickTarget = null
        run {
            if (SimpleCloseEngineFeature.FOCUS_PAGE_BEFORE_CLICK && moveFocus) {
                // Before the re-check, so nothing sits between the re-check and the click. F12/F13: straight to
                // a place that survives the close (the toolbar, above the popup), never beside it or the WebView.
                lastOwnFocusAt = SystemClock.uptimeMillis()
                val focus = withContext(Dispatchers.Default) { collector.focusPageItemKeeping(PopupMemory.of(popup), beforeClick = true) }
                log.trace(SimpleTrace.preClickTarget(focus.kind))
                log.trace(if (focus.moved) SimpleTrace.FOCUS_PRE_CLICK_MOVED else SimpleTrace.FOCUS_PRE_CLICK_FAILED)
                if (focus.node != null) {
                    target = focus.node
                    reclaim?.let { if (it.target == null) it.target = focus.node }
                }
            }
            val stillThere = withContext(Dispatchers.Default) {
                collector.foregroundPackage() == token.packageName && collector.rootStillPresent(popup.root) == true &&
                    collector.targetMatches(candidate, popup.root.windowId)
            }
            if (!stillThere) {
                endReclaim(null)
                log.trace(SimpleTrace.RECHECK_TARGET_GONE)
                return ActionResult(token, ActionStatus.STALE, SafetyReason.STALE_SCREEN, attempted = false)
            }
            // F17: the watch is on before the click (the person's press armed it as our window went).
            if (watchFocus && SimpleCloseEngineFeature.FOCUS_RECLAIM_AFTER_CLOSE && reclaim == null) {
                reclaim = FocusReclaim(PopupMemory.of(popup), target, SystemClock.uptimeMillis())
            }
            val clicked = clickOnce(candidate.nodePath!!, popup.root.path, popup.root.windowId)
            if (clicked == null) {
                endReclaim(null)
                log.trace(SimpleTrace.CLICK_TARGET_NOT_CLICKABLE)
                return ActionResult(token, ActionStatus.BLOCKED, SafetyReason.UNVERIFIED_TARGET, attempted = false)
            }
            if (!clicked) {
                endReclaim(null)
                log.trace(SimpleTrace.CLICK_REJECTED)
                return ActionResult(token, ActionStatus.ATTEMPT_FAILED, SafetyReason.UNVERIFIED_TARGET, attempted = true, outcome = ClickOutcome.UNKNOWN)
            }
            log.trace(SimpleTrace.CLICKED)
            focusMovesByOthers = 0
            val deadline = SystemClock.uptimeMillis() + SimpleCloseEngineFeature.CONFIRM_DELAY_MILLIS
            var outcome = ClickOutcome.STILL_PRESENT
            while (true) {
                delay(SimpleCloseEngineFeature.CONFIRM_POLL_MILLIS)
                val observed = withContext(Dispatchers.Default) {
                    val front = collector.foregroundPackage()
                    when {
                        front == null -> ClickOutcome.UNKNOWN
                        front != token.packageName -> ClickOutcome.LEFT_APP
                        collector.rootStillPresent(popup.root) == false -> ClickOutcome.CLOSED_CONFIRMED
                        else -> ClickOutcome.STILL_PRESENT
                    }
                }
                if (observed != ClickOutcome.STILL_PRESENT) {
                    outcome = observed
                    break
                }
                if (SystemClock.uptimeMillis() >= deadline) break
            }
            if (outcome == ClickOutcome.CLOSED_CONFIRMED && SimpleCloseEngineFeature.STRICT_CLOSE_CHECK) {
                // F20 (LG 11/1 11번가: "closed" 0.27 s after the click, the same sheet found again right after): the root
                // node going away is not enough. A moment later the screen is read again; the same popup there (same
                // place, same words: [PopupMemory.reappeared]) means it did not close.
                // F21 (LG 11/1: the result came 1.9~2.9 s after CLOSED_CONFIRMED: this re-read walked the whole page
                // when the popup was gone): the probe alone; no popup found by it = closed. Nothing is spoken meanwhile.
                log.trace(SimpleTrace.CLOSED_CONFIRMED)
                val recheckStart = SystemClock.uptimeMillis()
                delay(SimpleCloseEngineFeature.CLOSE_RECHECK_MILLIS)
                val back = withContext(Dispatchers.Default) {
                    val again = collector.read(token.packageName, ::allowsApp, wholeIfProbeMisses = false) ?: return@withContext false
                    val root = PopupRootFinder.find(again.nodes, again.screen, again.activeWindowBounded) ?: return@withContext false
                    PopupMemory.of(popup).reappeared(root, again.nodes)
                }
                log.latency("CLOSE_RECHECK_MS", SystemClock.uptimeMillis() - recheckStart)
                if (back) {
                    log.trace(SimpleTrace.CLOSE_REAPPEARED)
                    outcome = ClickOutcome.STILL_PRESENT
                }
            }
            if (outcome != ClickOutcome.CLOSED_CONFIRMED) endReclaim(null)
            log.trace(when (outcome) {
                ClickOutcome.CLOSED_CONFIRMED -> SimpleTrace.CLOSED_CONFIRMED
                ClickOutcome.LEFT_APP -> SimpleTrace.LEFT_APP
                else -> SimpleTrace.NOT_CLOSED
            })
            return ActionResult(token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = true, outcome = outcome)
        }
    }

    /**
     * F18: a video ad's skip button ([VideoAdSkip]), only in a preset that closes ads (the person's live
     * consent), re-checked right before one click on that very node (no ancestor, nothing else on the
     * player), then "광고를 건너뛰었어요" once when the button went away. Not pressed again for a while.
     */
    private fun skipVideoAd(read: TreeRead, candidate: CloseCandidate) {
        if (PopupClassification.ADVERTISEMENT !in preferences.current().autoCloseKinds(live = true)) {
            log.trace(SimpleTrace.VIDEO_SKIP_NOT_IN_PRESET)
            return
        }
        val key = "${read.windowId}:${candidate.nodePath}"
        val now = SystemClock.uptimeMillis()
        val last = skippedAt[key]
        if (last != null && now - last < SimpleCloseEngineFeature.SAME_POPUP_COOLDOWN_MILLIS) return
        skippedAt[key] = now
        if (skippedAt.size > 16) skippedAt.remove(skippedAt.keys.first())
        log.trace(SimpleTrace.VIDEO_SKIP_FOUND)
        jobs.runAction {
            val windowId = read.popupWindowId
            val path = candidate.nodePath ?: return@runAction
            val stillThere = withContext(Dispatchers.Default) {
                collector.foregroundPackage() == read.packageName && collector.targetMatches(candidate, windowId)
            }
            if (!stillThere) {
                log.trace(SimpleTrace.RECHECK_TARGET_GONE)
                return@runAction
            }
            // The skip node itself only: rootPath = its own path, so no ancestor (the player) is clicked.
            when (clickOnce(path, path, windowId)) {
                null -> { log.trace(SimpleTrace.CLICK_TARGET_NOT_CLICKABLE); return@runAction }
                false -> { log.trace(SimpleTrace.CLICK_REJECTED); return@runAction }
                true -> log.trace(SimpleTrace.CLICKED)
            }
            val deadline = SystemClock.uptimeMillis() + SimpleCloseEngineFeature.CONFIRM_DELAY_MILLIS
            var gone = false
            while (!gone && SystemClock.uptimeMillis() < deadline) {
                delay(SimpleCloseEngineFeature.CONFIRM_POLL_MILLIS)
                gone = withContext(Dispatchers.Default) { !collector.targetMatches(candidate, windowId) }
            }
            if (!gone) {
                log.trace(SimpleTrace.NOT_CLOSED)
                return@runAction
            }
            log.trace(SimpleTrace.VIDEO_SKIPPED)
            val sentence = getString(R.string.ui_video_ad_skipped)
            // The person is watching: the card speaks through TalkBack's queue, the focus stays on the player.
            val banner = resultBanner
            if (banner != null && banner.talkBackOn() && banner.show(sentence) { _, _ -> }) {
                log.trace(SimpleTrace.RESULT_VIA_TALKBACK)
            } else {
                log.trace(SimpleTrace.RESULT_VIA_TTS)
                tts?.let { announcer -> scope.launch { announcer.announce(AnnouncementRequest(null, AnnouncementKind.ACTION_RESULT, sentence)) } }
            }
        }
    }

    /** True/false = performAction result; null = no clickable node between the target and the popup root. */
    private fun clickOnce(targetPath: String, rootPath: String, windowId: Int? = null): Boolean? {
        var path = targetPath
        while (true) {
            val node = collector.nodeAt(path, windowId) ?: return null
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
        jobs.cancelAll()
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
        jobs.cancelAll()
        reclaim = null
        preClickTarget = null
        lastClosedMemory = null
        skippedAt.clear()
        autoFailed.clear()
        failedButtons.clear()
        kept.clear()
        pressing = null
        prompted = null
        resultBanner?.dismiss()
        resultBanner = null
        overlay?.dismiss()
        overlay = null
        batcher?.cancel()
        batcher = null
        handlingListener?.clear()
        handlingListener = null
        tts = null
        ttsEngine?.close()
        ttsEngine = null
        seenAt.clear()
        denied.clear()
        scope.cancel()
    }

    private companion object {
        /** Our own focus action comes back as an event within this; it is not someone else's move. */
        const val OWN_FOCUS_ECHO_MILLIS = 250L
        const val VIDEO_EVENT_LOG_MILLIS = 5_000L
        val WINDOW_EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
        )
        /** F15: the person's own touch or gesture (TalkBack swipes too); used only to end the reclaim watch. */
        val USER_TOUCH_EVENTS = setOf(
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, AccessibilityEvent.TYPE_GESTURE_DETECTION_START,
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START,
        )
    }
}

/**
 * F15: one reclaim watch after a press; [target] is a copy of a node we focused, kept only while the watch
 * is on. F17: [armedAt] (touches just after it are the press's own double tap), [onBanner] (the focused
 * result card is the place to go back to).
 */
private class FocusReclaim(val memory: PopupMemory, var target: AccessibilityNodeInfo?, val armedAt: Long) {
    var until = Long.MAX_VALUE
    var count = 0
    var lastMoveAt = 0L
    var onBanner = false
}

/** What kind of sentence our TTS spoke; logged with its length only, never the words (LG 10/31: "말한 내용 리스트"). */
private enum class SpeechKind { PROMPT_OR_DENY_NOTE, CONTENT_FULL, RESULT, PROTECTED_SCREEN, PARTIAL, REPEAT, STOP }

/**
 * Wraps C's TTS so every sentence of this engine leaves one debug line under `CfySpeech`:
 * the kind (from the contract's [AnnouncementKind]) and the character count. No text.
 */
private class SpeechKindLoggingAnnouncer(private val delegate: PopupAnnouncer, private val log: DebugSimpleLog) : PopupAnnouncer {
    override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
        val kind = when (request.kind) {
            AnnouncementKind.POPUP_FOUND -> SpeechKind.PROMPT_OR_DENY_NOTE
            AnnouncementKind.CONTENT -> SpeechKind.CONTENT_FULL
            AnnouncementKind.ACTION_RESULT -> SpeechKind.RESULT
            AnnouncementKind.PROTECTED_SCREEN -> SpeechKind.PROTECTED_SCREEN
            AnnouncementKind.PARTIAL_INFORMATION -> SpeechKind.PARTIAL
        }
        log.speech(kind, request.text.length)
        return delegate.announce(request)
    }

    override suspend fun repeatLast(): AnnouncementReceipt {
        log.speech(SpeechKind.REPEAT, 0)
        return delegate.repeatLast()
    }

    override fun stop() {
        log.speech(SpeechKind.STOP, 0)
        delegate.stop()
    }
}

/**
 * Debug builds only: one enum name per step under `CfySimple` (no text, no ids, no packages). The
 * same code in a row is written once (Chrome sends dozens of own-window and not-allowed events).
 * Speech goes under `CfySpeech` as kind + length, every time.
 */
private class DebugSimpleLog(service: AccessibilityService) : SimpleTraceSink {
    private val enabled = service.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var last: SimpleTrace? = null

    override fun trace(code: SimpleTrace) {
        if (!enabled || code == last) return
        last = code
        Log.d(TAG, code.name)
    }

    fun speech(kind: SpeechKind, length: Int) {
        if (enabled) Log.d(SPEECH_TAG, "${kind.name} len=$length")
    }

    /** F5: first helped-app event of this search → DETECTED, in ms (a number only). */
    fun latency(name: String, millis: Long) {
        if (enabled) Log.d(TAG, "$name=$millis")
    }

    private companion object {
        const val TAG = "CfySimple"
        const val SPEECH_TAG = "CfySpeech"
    }
}

/** What made the read that found the popup (F10, debug log `DETECTED_BY_*`). */
enum class DetectTrigger(val trace: SimpleTrace) {
    WINDOW_EVENT(SimpleTrace.DETECTED_BY_WINDOW_EVENT),
    CONTENT_EVENT(SimpleTrace.DETECTED_BY_CONTENT_EVENT),
    RECHECK(SimpleTrace.DETECTED_BY_RECHECK),
    TALKBACK_FOCUS(SimpleTrace.DETECTED_BY_TALKBACK_FOCUS),
}
