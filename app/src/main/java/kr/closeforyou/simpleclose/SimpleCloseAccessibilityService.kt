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
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.choice.RememberChoiceRules
import kr.closeforyou.ui.voice.overlay.AccessibilityChoiceOverlay
import kr.closeforyou.ui.voice.overlay.PromptAnnouncementListener
import kr.closeforyou.ui.voice.overlay.PromptAnswer
import kr.closeforyou.ui.voice.overlay.PromptControlsSupport
import kr.closeforyou.ui.voice.overlay.PromptShowResult
import kr.closeforyou.ui.voice.overlay.PromptSpeech
import kr.closeforyou.ui.voice.overlay.TalkBackTakeoverPlan
import kr.closeforyou.ui.voice.settings.UiVoicePreferences

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
            PromptAnnouncementListener(this, announcer, scope, mode = { preferences.current().promptAnnounceMode }, detail = { preferences.current().detail }),
            PromptControlsSupport.create(this, announcer),
            beforeClose = ::focusPageBeforeOurWindowGoes,
        )
        resultBanner = TalkBackResultBanner(this)
        log.trace(SimpleTrace.SERVICE_CONNECTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || overlay == null) return
        // F15: the person touches the screen or makes a gesture: the focus is theirs again.
        if (event.eventType in USER_TOUCH_EVENTS) {
            if (reclaim != null) endReclaim(SimpleTrace.FOCUS_RECLAIM_STOPPED_BY_USER)
            return
        }
        if (event.eventType !in WINDOW_EVENTS) return
        val eventPackage = event.packageName?.toString()
        // Our own prompt window and TTS: never a popup to handle (not logged: it would drown the rest).
        if (eventPackage == packageName) return
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
        val read = withContext(Dispatchers.Default) { collector.read(expectedPackage, ::allowsApp) }
        val readMillis = SystemClock.uptimeMillis() - readStart
        searchReads += 1
        if (read == null) {
            searchUnavailable += 1
            log.trace(SimpleTrace.TREE_UNAVAILABLE)
            return false
        }
        val root = PopupRootFinder.find(read.nodes, read.screen, read.activeWindowBounded)
        if (root == null) {
            log.trace(SimpleTrace.NO_POPUP_ROOT)
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
        })
        log.latency("TREE_PROBE_VISITED", read.probeVisited.toLong())
        endSearch()

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
        if (popup.scan.unlabeledCount > 0) log.trace(SimpleTrace.UNLABELED_SKIPPED)
        log.trace(if (popup.scan.candidates.isEmpty()) SimpleTrace.CANDIDATE_NONE else SimpleTrace.CANDIDATE_FOUND)

        val analysis = popup.analysis.copy(classification = classification)
        val classified = popup.copy(analysis = analysis)
        when (val decision = SimpleCloseDecider.decide(classification, suspicious, popup.scan.candidates, preferences.current(), popup.scan.forbiddenCount)) {
            is SimpleDecision.AutoClose -> {
                log.trace(if (decision.unknown) SimpleTrace.DECIDED_AUTO_CLOSE_UNKNOWN else SimpleTrace.DECIDED_AUTO_CLOSE)
                val approval = ActionDecision(analysis.token, ActionAuthorization.ALLOW_ONCE, persistentSetOf(), decision.candidate.id, DecisionSource.PRESET, NextStep.EXECUTE)
                // Its own job: the inspection this runs in is replaced by the next window event.
                jobs.runAction {
                    // F11: the person did not ask; move TalkBack's focus only if it was in the popup.
                    val inside = withContext(Dispatchers.Default) { collector.accessibilityFocusInside(classified.root.bounds) }
                    log.trace(when (inside) {
                        true -> SimpleTrace.FOCUS_WAS_IN_POPUP
                        false -> SimpleTrace.FOCUS_WAS_OUTSIDE
                        null -> SimpleTrace.FOCUS_WAS_UNKNOWN
                    })
                    val moveFocus = inside == true
                    val result = pressAndConfirm(classified, decision.candidate, moveFocus, watchFocus = moveFocus)
                    if (PressSuccess.isConfirmed(result)) {
                        speakClosedThenFocusPage(classified, moveFocus)
                    } else {
                        tts?.stop()
                        handlingListener?.onHandled(HandlingReport(analysis.token, classification, approval, result))
                    }
                }
            }
            SimpleDecision.DenyDecision -> {
                log.trace(SimpleTrace.DECIDED_DENY_DECISION)
                if (!denyOnce(key)) return true
                val deny = ActionDecision(analysis.token, ActionAuthorization.DENY, persistentSetOf(SafetyReason.IMPORTANT_DECISION), null, DecisionSource.PRESET, NextStep.DENY)
                handlingListener?.onHandled(HandlingReport(analysis.token, classification, deny))
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
        val kind = AutoDecisionSpeech.kindOf(popup.analysis.classification)
        val sentence = getString(R.string.ui_auto_closed_one, getString(AutoDecisionSpeech.objectRes(kind)))
        val banner = resultBanner
        if (banner != null && banner.talkBackOn()) {
            // F12: after a focus move TalkBack reads the focused item first; the card comes after it.
            if (movedNow) delay(SimpleCloseEngineFeature.RESULT_AFTER_FOCUS_MILLIS)
            if (banner.show(sentence) { shown -> log.trace(if (shown) SimpleTrace.RESULT_BANNER_SHOWN else SimpleTrace.RESULT_BANNER_NOT_SHOWN) }) {
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

    private fun ask(popup: SimplePopup, classification: PopupClassification, talkBackReadIt: Boolean) {
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
        prompted = popup
        focusedBeforeClose = false
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
                // Nothing waits for the prompt's speech: stop it and press right away.
                tts?.stop()
                // F13: the focus already left the popup before our window went ([focusPageBeforeOurWindowGoes]); not again.
                val focusedAlready = focusedBeforeClose
                focusedBeforeClose = false
                jobs.runAction {
                    // Our window goes away under TalkBack's focus: it must land on the page, not the popup's leftovers.
                    val result = pressAndConfirm(popup, candidate, moveFocus = !focusedAlready, watchFocus = true)
                    if (PressSuccess.isConfirmed(result)) speakClosedThenFocusPage(popup, moveFocus = true) else speakOwnPress(result)
                }
            }
            else -> {
                log.trace(SimpleTrace.USER_CHOSE_KEEP)
                prompted = null
            }
        }
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
        preClickTarget = focus.node
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
                    if (target != null && collector.refocus(target)) {
                        watch.count += 1
                        watch.lastMoveAt = now
                    } else {
                        // No node to go back to yet (or it is gone): the move after the close sets one.
                        watch.target = null
                        log.trace(SimpleTrace.FOCUS_RECLAIM_NO_TARGET)
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
                if (focus.node != null) target = focus.node
            }
            val stillThere = withContext(Dispatchers.Default) {
                collector.foregroundPackage() == token.packageName && collector.rootStillPresent(popup.root) == true && collector.targetMatches(candidate)
            }
            if (!stillThere) {
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
            focusMovesByOthers = 0
            if (watchFocus && SimpleCloseEngineFeature.FOCUS_RECLAIM_AFTER_CLOSE) {
                endReclaim(null)
                reclaim = FocusReclaim(PopupMemory.of(popup), target)
            }
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
            if (outcome != ClickOutcome.CLOSED_CONFIRMED) endReclaim(null)
            log.trace(when (outcome) {
                ClickOutcome.CLOSED_CONFIRMED -> SimpleTrace.CLOSED_CONFIRMED
                ClickOutcome.LEFT_APP -> SimpleTrace.LEFT_APP
                else -> SimpleTrace.NOT_CLOSED
            })
            return ActionResult(token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = true, outcome = outcome)
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

/** F15: one reclaim watch after a press; [target] is a copy of a node we focused, kept only while the watch is on. */
private class FocusReclaim(val memory: PopupMemory, var target: AccessibilityNodeInfo?) {
    var until = Long.MAX_VALUE
    var count = 0
    var lastMoveAt = 0L
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
