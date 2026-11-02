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
import kotlinx.coroutines.Job
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
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.NextStep
import kr.closeforyou.ui.voice.choice.PromptSummary
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.SafetyReason
import kr.closeforyou.R
import kr.closeforyou.decision.DecisionDemoRules
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
import kr.closeforyou.ui.voice.overlay.ChoiceOrigin
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
    /** F23: when this search's first read started (0 = none yet), and how many of its reads walked the whole tree. */
    private var searchFirstReadAt = 0L
    private var searchWholeReads = 0
    /** F23: a browser showing YouTube (seen in a whole read): its reads stay whole for a while (skip button outside popups). */
    private var videoWebPackage: String? = null
    /** F28: the browser that showed youtube.com in its address bar, and until when it counts as YouTube. */
    private var youtubeWebPackage: String? = null
    private var youtubeWebUntil = 0L
    private var videoWebUntil = 0L
    /** F24: a page whose whole read was slow (> 1 s): whole reads only on window changes for a while. */
    private var slowPackage: String? = null
    private var slowUntil = 0L
    /** F25: the page whose last whole read stopped at its cap, and when. */
    private var abortedPackage: String? = null
    private var abortedAt = 0L
    /** F24: share of time spent reading, per 10 s window (debug `READ_DUTY_PCT`). */
    private var dutyWindowStart = 0L
    /** F26: when a repaint's source was last looked up for [contentKind]. */
    private var likelyPopupCheckAt = 0L
    private var dutyBusy = 0L
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
    /** F36: the app whose video cover is counting down, until when it is re-read, and the re-read pending. */
    private var coverWaitPackage: String? = null
    private var coverWaitUntil = 0L
    private var coverWaitSeenAt = 0L
    private var coverReread: Job? = null
    /** F35: the video ad (app and window) whose wait was said, and when; one sentence per ad. */
    private var videoWaitAnnounced: Pair<String, Long>? = null
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
    /** F27 (review #1): popups closed in the last few seconds (by their words) and the button that closed them. */
    private val recentlyClosed = LinkedHashMap<String, Pair<Long, String?>>()
    /** F27 (review #2): skip buttons pressed that did not go away; never pressed again this session. */
    private val failedSkips = LinkedHashSet<String>()
    private var pressing: String? = null
    /** F20: YouTube app events seen / refused by 도와줄 앱 (debug log at most every few seconds). */
    private var videoEventLoggedAt = 0L
    private var inputMethods: Set<String> = emptySet()

    /** The popup the prompt on screen is about, when each popup key was last seen, and keys already explained. */
    private var prompted: SimplePopup? = null
    /** F32 (e): the app that last opened an activity named like a popup, and when (memory only). */
    private var popupActivityPackage: String? = null
    private var popupActivityAt = 0L
    /** F32: the last windows debug line logged ([WindowBoundsRules.line]). */
    private var lastWindowsLine: String? = null
    /** F31 (B_DEMO_RULES.md 6-2): automatic closes per app and our last press; memory only, no screen text. */
    private val chainGuard = ChainGuard(
        SimpleCloseEngineFeature.CHAIN_MAX_AUTO, SimpleCloseEngineFeature.CHAIN_WINDOW_MILLIS, SimpleCloseEngineFeature.CHAIN_NEW_POPUP_AFTER_PRESS_MILLIS,
    )
    /** F28: the "is the popup's app still on screen?" look-up in flight (the latest event's). */
    private var appLeftCheck: Job? = null
    private val seenAt = LinkedHashMap<String, Long>()
    /** F34: popups waited on for a late close ([LateCloseWait]), by window, kind and root path: since when. */
    private val lateCloseSince = LinkedHashMap<String, Long>()
    /** F34: apps where an automatic press left the app this session ([SimpleTrace.AUTO_CLOSE_PAUSED_APP_LEFT]). */
    private val leftAppPaused = LinkedHashSet<String>()
    private val denied = LinkedHashSet<String>()

    private val preferences by lazy { UiVoicePreferences.get(this) }
    private val corePreferences by lazy { CorePreferences.get(this) }
    private val installedApps by lazy { AndroidSimpleAppLookup(packageManager) }
    /** F22: popups the person told us to always close (C's own small store; hashes and numbers only). */
    private val remembered: RememberedPopupStore by lazy { SharedPrefsRememberedPopups(this) }

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
        scopeMissTrace = { pkg -> scopeTrace(SimpleAppAllowance.scopeMiss(pkg, packageName, preferences.current(), corePreferences.currentSettings().allowedPackages, inputMethods)) },
    )

    /** F22: the reason code for an app the engine leaves alone (never the package). */
    private fun scopeTrace(miss: ScopeMiss?): SimpleTrace = when (miss) {
        null -> SimpleTrace.SKIPPED_NOT_ALLOWED_APP
        else -> SimpleTrace.valueOf("SKIPPED_SCOPE_${miss.name}")
    }

    private fun endSearch() {
        firstEventAt = 0L
        searchReads = 0
        searchUnavailable = 0
        searchFirstReadAt = 0L
        searchWholeReads = 0
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
        // F28 (LG 11/1 14:29): our own app's window counts too, so this comes before the own-package return.
        if (event.eventType in APP_SWITCH_EVENTS) prompted?.let(::checkPromptedAppStillShown)
        // F32 (e): a new activity whose class is named like a popup ("…PopupActivity", "…Dialog…", "…Ads…").
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && PopupActivityName.looksLikePopup(event.className?.toString())) {
            popupActivityPackage = event.packageName?.toString()
            popupActivityAt = SystemClock.uptimeMillis()
            log.trace(SimpleTrace.POPUP_ACTIVITY_HINT)
        }
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
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> contentKind(event)
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
        // F22-2: a re-check or repaint read that the probe finds nothing in stops there (cheap), except every few reads.
        val videoWeb = expectedPackage == videoWebPackage && readStart < videoWebUntil
        val slowPage = expectedPackage == slowPackage && readStart < slowUntil
        val sinceAborted = (readStart - abortedAt).takeIf { expectedPackage == abortedPackage }
        val whole = ReadBudget.wholeRead(trigger, searchReads, slowPage, videoWeb, sinceAborted)
        val read = withContext(Dispatchers.Default) {
            collector.read(expectedPackage, ::allowsApp, probe = expectedPackage != VideoAdSkip.YOUTUBE_APP && !videoWeb, wholeIfProbeMisses = whole)
        }
        val readMillis = SystemClock.uptimeMillis() - readStart
        searchReads += 1
        // F23 (LG 11/1 옥션 웹: DETECT_LATENCY_MS 1689 once, 4058 the next time): each read of a search, as numbers:
        // when it started after the first event, what it cost, and whether it walked the whole tree.
        if (searchFirstReadAt == 0L) searchFirstReadAt = readStart
        if (whole) searchWholeReads += 1
        if (firstEventAt != 0L) log.latency("READ_AT_MS", readStart - firstEventAt)
        log.latency(if (whole) "READ_WHOLE_COST_MS" else "READ_PROBE_COST_MS", readMillis)
        // F24: a slow whole read (예스24 3.6 s) makes the next ones rarer on this page.
        if (whole && read?.mode != TreeReadMode.POPUP_SUBTREE && readMillis > ReadBudget.SLOW_WHOLE_MILLIS) {
            slowPackage = expectedPackage
            slowUntil = SystemClock.uptimeMillis() + SLOW_PAGE_MILLIS
        }
        if (read?.wholeAborted == true) {
            log.trace(SimpleTrace.READ_WHOLE_ABORTED)
            abortedPackage = expectedPackage
            abortedAt = SystemClock.uptimeMillis()
        }
        countDuty(readStart, readMillis)
        if (read == null) {
            searchUnavailable += 1
            log.trace(SimpleTrace.TREE_UNAVAILABLE)
            // F22: why (the app moved on, no tree, #65 protection, the app's name unknown), as a code.
            val why = withContext(Dispatchers.Default) {
                when {
                    collector.foregroundPackage() == null -> SimpleTrace.TREE_UNAVAILABLE_NO_ROOT
                    collector.foregroundPackage() != expectedPackage -> SimpleTrace.TREE_UNAVAILABLE_OTHER_APP
                    else -> when (SimpleAppAllowance.allowMiss(expectedPackage, packageName, preferences.current(),
                        corePreferences.currentSettings().allowedPackages, inputMethods, lookup = installedApps::find)) {
                        null -> SimpleTrace.TREE_UNAVAILABLE_NO_ROOT
                        ScopeMiss.SENSITIVE -> SimpleTrace.TREE_UNAVAILABLE_SENSITIVE
                        ScopeMiss.LABEL_UNKNOWN -> SimpleTrace.TREE_UNAVAILABLE_LABEL_UNKNOWN
                        else -> SimpleTrace.TREE_UNAVAILABLE_NOT_ALLOWED
                    }
                }
            }
            log.trace(why)
            return false
        }
        // F28 (B_DEMO_RULES.md 2절): a login, payment, bank or personal-data screen, or any password field on screen:
        // nothing on it is judged, pressed or spoken, not even a video skip. A code only, never the address or the words.
        val sensitive = withContext(Dispatchers.Default) { SensitiveScreen.reason(read.nodes, read.screen, read.passwordSeen, read.address) }
        if (sensitive != null) {
            log.trace(SensitiveScreen.trace(sensitive))
            endSearch()
            return true
        }
        // F18: a video ad's own skip button comes first (it is not a popup); a countdown waits for the next read.
        var videoAdWaiting = false
        if (read.appWindowIgnored) log.trace(SimpleTrace.APP_WINDOW_IGNORED)
        if (read.boundsFromRoot) log.trace(SimpleTrace.WINDOW_BOUNDS_FROM_ROOT)
        // F32: the windows of the read as one code line, only when it changed (not per event).
        read.windowsLine?.takeIf { it != lastWindowsLine }?.let { lastWindowsLine = it; log.line(it) }
        // F22 (LG 11/1: VIDEO_AD_MARKER_FOUND ×64 in a shopping app, a banner's "AD"): video ads only in YouTube.
        val videoContext = VideoAdSkip.isVideoContext(read.packageName, read.nodes)
        // F23: YouTube in a browser: keep its reads whole for a minute (the address bar scrolls away, the skip is no popup).
        if (videoContext && read.packageName != VideoAdSkip.YOUTUBE_APP && read.nodes.isNotEmpty()) {
            videoWebPackage = read.packageName
            videoWebUntil = SystemClock.uptimeMillis() + VIDEO_WEB_STICKY_MILLIS
        }
        // F28 (B_DEMO_RULES.md 4-1): YouTube (app, or youtube.com seen in this browser within the minute) as before.
        if (VideoAdSkip.isYouTubeWeb(read.nodes)) {
            youtubeWebPackage = read.packageName
            youtubeWebUntil = SystemClock.uptimeMillis() + VIDEO_WEB_STICKY_MILLIS
        }
        val youtube = read.packageName == VideoAdSkip.YOUTUBE_APP ||
            read.packageName == youtubeWebPackage && SystemClock.uptimeMillis() < youtubeWebUntil
        if (SimpleCloseEngineFeature.VIDEO_AD_SKIP && videoContext) {
            val video = VideoAdSkip.scan(read.nodes, read.screen, youtube)
            // F20: why or why not, as codes (only on a screen with an ad mark, a skip-like node, or in YouTube).
            if (video.diag != VideoAdDiag.NONE || read.packageName == VideoAdSkip.YOUTUBE_APP) {
                log.trace(if (video.adMarker) SimpleTrace.VIDEO_AD_MARKER_FOUND else SimpleTrace.VIDEO_AD_MARKER_MISSING)
                videoTrace(video.diag)?.let(log::trace)
            }
            when (val result = video.result) {
                is VideoAdSkipResult.Found -> {
                    // F28: outside YouTube, B's decision and scam check first; a refusal goes on to the popup search.
                    if (!youtube && withContext(Dispatchers.Default) { VideoAdSkip.deniedByB(read.nodes, read.screen) }) {
                        log.trace(SimpleTrace.VIDEO_SKIP_DENIED_BY_B)
                    } else {
                        endSearch()
                        skipVideoAd(read, result.candidate)
                        return true
                    }
                }
                VideoAdSkipResult.Waiting -> {
                    videoAdWaiting = true
                    announceVideoWait(read)
                }
                null -> Unit
            }
        }
        // F32 (e): the app just opened an activity named like a popup (the name is compared in memory, never logged).
        val popupActivity = read.packageName == popupActivityPackage && SystemClock.uptimeMillis() - popupActivityAt < POPUP_ACTIVITY_HINT_MILLIS
        // F16: an app popup window's tree has its own window for the live look-ups. F33: so has a popup that is the active
        // window itself (a Dialog window, a popup activity), [PopupRootFinder.bindWindow].
        val found = PopupRootFinder.find(read.nodes, read.screen, read.activeWindowBounded, popupActivityHint = popupActivity)
            ?.let { PopupRootFinder.bindWindow(it, read.popupWindowId, read.windowId) }
        // F34 (시험 앱 P16): outside YouTube a video ad's own cover is asked about once its skip shows; before it, waited for.
        val (cover, coverMiss) = if (found == null && !youtube && SimpleCloseEngineFeature.VIDEO_AD_SKIP) VideoAdSkip.coverCheck(read.nodes, read.screen) else null to null
        coverMiss?.let { log.trace(SimpleTrace.valueOf("VIDEO_COVER_MISS_${it.name}")) }
        when (cover) {
            VideoCover.Waiting -> {
                videoAdWaiting = true
                log.trace(SimpleTrace.VIDEO_COVER_WAITING)
                rereadCoverSoon(read.packageName)
            }
            is VideoCover.Shown -> log.trace(SimpleTrace.VIDEO_COVER_SHOWN)
            null -> Unit
        }
        // F36: a bottom row with a close that was not taken for a banner: why (codes only).
        if (found?.kind == PopupRootKind.BOTTOM_BANNER && found.members != null) log.trace(SimpleTrace.BANNER_FLAT_FOUND)
        if (found == null && cover == null) {
            (PopupRootFinder.flatBanner(read.nodes.filter { PopupRootFinder.isVisible(it, read.screen) }, read.screen) as? FlatBanner.Miss)
                ?.let { log.trace(SimpleTrace.valueOf("BANNER_MISS_${it.why.name}")) }
        }
        val root = found ?: (cover as? VideoCover.Shown)?.root?.let { PopupRootFinder.bindWindow(it, read.popupWindowId, read.windowId) }
        if (root == null) {
            log.trace(if (videoAdWaiting) SimpleTrace.VIDEO_SKIP_WAITING else SimpleTrace.NO_POPUP_ROOT)
            lastNoRootAt = SystemClock.uptimeMillis()
            return false
        }
        val popup = SimplePopupAnalysis.build(read.nodes, root, read.packageName, read.windowId, read.screen, screenVersion, SystemClock.uptimeMillis())
            .copy(foundByProbe = read.mode == TreeReadMode.POPUP_SUBTREE, depthCut = read.depthCut.any { it == root.path || it.startsWith("${root.path}/") })
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
            PopupRootKind.MODAL_CARD -> SimpleTrace.DETECTED_MODAL_CARD
            PopupRootKind.DIM_WINDOW -> SimpleTrace.DETECTED_DIM_WINDOW
            PopupRootKind.BOTTOM_BANNER -> SimpleTrace.DETECTED_BOTTOM_BANNER
            PopupRootKind.VIDEO_COVER -> SimpleTrace.DETECTED_VIDEO_COVER
        })
        // F35 (B_DEMO_RULES.md 8-1): were the card's siblings in this read (the quick subtree read may stop at the card)?
        if (root.kind == PopupRootKind.MODAL_CARD) {
            if (PopupNeighbors.siblingsRead(read.nodes, root)) log.latency("NEIGHBORS_CHECKED", popup.neighborCount.toLong())
            else log.trace(SimpleTrace.NEIGHBORS_UNREAD)
        }
        // F10: first event -> here overstates when the page itself loaded first; the gap since the last
        // read that saw no popup bounds when the popup really appeared. Which trigger caught it, too.
        log.trace(trigger.trace)
        if (firstEventAt != 0L) log.latency("DETECT_LATENCY_MS", now - firstEventAt)
        if (lastNoRootAt != 0L) log.latency("DETECT_GAP_MS", now - lastNoRootAt)
        // F13: where the time went: reads this search took, how many the guard refused, the last read's cost.
        log.latency("DETECT_READS", searchReads.toLong())
        // F23: from the first event to the first read, and how many reads walked the whole page.
        if (firstEventAt != 0L && searchFirstReadAt != 0L) log.latency("DETECT_FIRST_READ_MS", searchFirstReadAt - firstEventAt)
        log.latency("DETECT_WHOLE_READS", searchWholeReads.toLong())
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
        // F27 (review #1): the same popup back within seconds of a "confirmed" close: it did not close after all. Never
        // pressed again by itself (the button that was used is not offered), a remembered rule for it is forgotten.
        var lateFailure = false
        recentlyClosed.remove(words)?.let { (closedAt, label) ->
            if (SystemClock.uptimeMillis() - closedAt <= REAPPEAR_WINDOW_MILLIS) {
                lateFailure = true
                log.trace(SimpleTrace.CLOSE_REAPPEARED_LATE)
                autoFailed += words
                if (label != null) failedButtons.getOrPut(words) { mutableSetOf() } += label
                if (withContext(Dispatchers.Default) { remembered.get(RememberedPopup.keyOf(read.packageName, popup)) } != null) {
                    withContext(Dispatchers.Default) { remembered.remove(RememberedPopup.keyOf(read.packageName, popup)) }
                    log.trace(SimpleTrace.REMEMBERED_RULE_DROPPED)
                }
            }
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
        // F34: B's UNKNOWN named by B's own wider word lists (coupon, ad, notice); a decision word keeps it UNKNOWN.
        val wordHint = if (SimpleCloseEngineFeature.WORD_KIND_HINT && !hinted) {
            WordKindHint.of(byB, suspicious, popup.analysis.texts.map { it.text }, popup.scan.riskCount)
        } else {
            WordHint.NONE
        }
        if (wordHint != WordHint.NONE) log.trace(SimpleTrace.valueOf("WORD_HINT_${wordHint.name}"))
        val classification = if (hinted) PopupClassification.ADVERTISEMENT else wordHint.kind ?: byB
        // F28 (B_DEMO_RULES.md 1절): a "지금 구매하기" of a promotion B checked whole stops keeping it asked; never pressed.
        val promoPurchase = PromoPurchaseGate.allowed(DecisionDemoRules.PROMO_PURCHASE, byB, hinted, popup.analysis.texts, popup.scan.promoBuyCount)
        // F35 (B_DEMO_RULES.md 8-5 B안): a promotion's own "로그인하러 가기" the same way; never pressed.
        val promoLogin = PromoLoginGate.allowed(DecisionDemoRules.PROMO_LOGIN, byB, hinted, popup.analysis.texts, popup.scan.promoLoginCount)
        val promoBuy = promoPurchase + promoLogin
        if (promoBuy > 0 && promoBuy == popup.scan.riskCount) {
            if (promoPurchase > 0) log.trace(SimpleTrace.PROMO_PURCHASE_NOT_ASKED)
            if (promoLogin > 0) log.trace(SimpleTrace.PROMO_LOGIN_NOT_ASKED)
        }
        log.trace(when {
            suspicious -> SimpleTrace.CLASSIFIED_SUSPICIOUS
            classification == PopupClassification.ADVERTISEMENT -> SimpleTrace.CLASSIFIED_AD
            classification == PopupClassification.COUPON -> SimpleTrace.CLASSIFIED_COUPON
            classification == PopupClassification.NOTICE -> SimpleTrace.CLASSIFIED_NOTICE
            classification == PopupClassification.USER_DECISION -> SimpleTrace.CLASSIFIED_DECISION
            else -> SimpleTrace.CLASSIFIED_UNKNOWN
        })
        // F33 (LG 11/1 11번가 app: CLASSIFIED_UNKNOWN with a "로그인하러 가기" button on screen): what the popup gave, as numbers.
        log.latency("POPUP_TEXTS", popup.analysis.texts.size.toLong())
        log.latency("BUTTONS_RISKY", popup.scan.riskCount.toLong())
        log.latency("BUTTONS_UNLABELED", popup.scan.unlabeledCount.toLong())
        log.latency("BUTTONS_FORBIDDEN", popup.scan.forbiddenCount.toLong())
        if (popup.scan.forbiddenCount > 0) log.trace(SimpleTrace.BLOCKED_FORBIDDEN)
        if (popup.scan.riskCount > 0) log.trace(SimpleTrace.BLOCKED_RISKY)
        if (popup.scan.unlabeledCount > 0) log.trace(SimpleTrace.UNLABELED_SKIPPED)
        log.trace(if (popup.scan.candidates.isEmpty()) SimpleTrace.CANDIDATE_NONE else SimpleTrace.CANDIDATE_FOUND)

        val analysis = popup.analysis.copy(classification = classification)
        val classified = popup.copy(analysis = analysis)
        // F22 (AGENTS 6-4): the person said "앞으로 항상 닫기" for this exact popup and button: closed by itself, with the
        // usual re-check right before the press. Never a decision, scam, risky-button or already-failed popup.
        // B_DEMO_RULES.md 8-3: with the "자동 닫기" switch off nothing is pressed by itself, remembered popups included.
        val rememberedButton = if (!suspicious && classification != PopupClassification.USER_DECISION && popup.scan.riskCount == 0 && words !in autoFailed &&
            preferences.current().autoCloseKinds(live = true).isNotEmpty()) {
            withContext(Dispatchers.Default) { RememberedPopupRules.match(remembered, read.packageName, popup) }
        } else {
            null
        }
        if (rememberedButton != null) log.trace(SimpleTrace.REMEMBERED_AUTO_CLOSE)
        val decided = rememberedButton?.let { SimpleDecision.AutoClose(it) }
            ?: SimpleCloseDecider.decide(classification, suspicious, popup.scan.candidates, preferences.current(), popup.scan.forbiddenCount, popup.scan.riskCount, promoBuy)
        // F31 (B_DEMO_RULES.md 6-2): a chain of automatic closes in one app, or a popup right after our own press, is asked.
        // The popup's key is its words (a popup that moves or repaints is not "another" one).
        val chainBlock = if (decided is SimpleDecision.AutoClose && SimpleCloseEngineFeature.CHAIN_GUARD) {
            chainGuard.check(read.packageName, words, SystemClock.uptimeMillis())
        } else {
            null
        }
        // F20: an automatic close failed on this popup before: never pressed automatically again (AGENTS 6-6), asked.
        val decision = when {
            decided is SimpleDecision.AutoClose && words in autoFailed -> {
                log.trace(SimpleTrace.AUTO_CLOSE_SKIPPED_AFTER_FAIL)
                SimpleDecision.Ask
            }
            // F34 (시험 앱 P19): an automatic press here left the app before: none more in this app this session.
            decided is SimpleDecision.AutoClose && read.packageName in leftAppPaused -> {
                log.trace(SimpleTrace.AUTO_CLOSE_SKIPPED_APP_PAUSED)
                SimpleDecision.Ask
            }
            decided is SimpleDecision.AutoClose && rememberedButton == null && SimpleCloseEngineFeature.INSTALL_AD_LONE_CLOSE_ASK &&
                InstallAdLoneClose.suspected(popup, classification, decided.candidate) -> {
                log.trace(SimpleTrace.INSTALL_AD_LONE_CLOSE_ASKED)
                SimpleDecision.Ask
            }
            // F27 (review #7): a part of the popup was too deep to read: a risky button could hide there.
            decided is SimpleDecision.AutoClose && popup.depthCut -> {
                log.trace(SimpleTrace.DEPTH_CUT_ASKED)
                SimpleDecision.Ask
            }
            chainBlock != null -> {
                log.trace(when (chainBlock) {
                    ChainBlock.RATE_LIMIT -> SimpleTrace.CHAIN_GUARD_ASKED_RATE
                    ChainBlock.OPENED_BY_OUR_PRESS -> SimpleTrace.CHAIN_GUARD_ASKED_NEW_POPUP_AFTER_PRESS
                })
                SimpleDecision.Ask
            }
            else -> decided
        }
        // F34 (시험 앱 P23): nothing to press yet and the preset would close it with a close: wait for one (up to 5 s),
        // not asked meanwhile. The same popup's next read (its button appearing) is looked at again.
        val waitKey = "${read.windowId}:${root.kind}:${root.path}"
        if (decision == SimpleDecision.Ask && decided == SimpleDecision.Ask && SimpleCloseEngineFeature.LATE_CLOSE_WAIT &&
            LateCloseWait.applies(classification, suspicious, popup.scan, preferences.current().autoCloseKinds(live = true))) {
            val since = lateCloseSince.getOrPut(waitKey) { now }
            if (lateCloseSince.size > 16) lateCloseSince.remove(lateCloseSince.keys.first())
            if (now - since < LateCloseWait.MAX_WAIT_MILLIS) {
                log.trace(SimpleTrace.LATE_CLOSE_WAITING)
                seenAt.remove(key)
                if (since == now) {
                    val pkg = read.packageName
                    scope.launch {
                        delay(LateCloseWait.MAX_WAIT_MILLIS + LATE_CLOSE_LAST_LOOK_MILLIS)
                        if (lateCloseSince[waitKey] == since) inspectionEvents.onEvent(pkg, SimpleInspectionEvent.CONTENT)
                    }
                }
                return true
            }
            lateCloseSince.remove(waitKey)
            log.trace(SimpleTrace.LATE_CLOSE_WAIT_TIMEOUT)
        } else if (lateCloseSince.remove(waitKey) != null && decision is SimpleDecision.AutoClose) {
            log.trace(SimpleTrace.LATE_CLOSE_APPEARED)
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
                val started = jobs.runAction {
                  // F27: the in-flight mark is cleared however this job ends (cancelled before the press too), so a popup
                  // is never ignored for good.
                  try {
                    // F11: the person did not ask; move TalkBack's focus only if it was in the popup.
                    val inside = withContext(Dispatchers.Default) { collector.accessibilityFocusInside(classified.root.bounds) }
                    log.trace(when (inside) {
                        true -> SimpleTrace.FOCUS_WAS_IN_POPUP
                        false -> SimpleTrace.FOCUS_WAS_OUTSIDE
                        null -> SimpleTrace.FOCUS_WAS_UNKNOWN
                    })
                    val moveFocus = inside == true
                    // F22-2 D: with the result card taking the focus, no toolbar stop before the click (one sentence).
                    val preClickMove = moveFocus && (SimpleCloseEngineFeature.AUTO_PRE_CLICK_MOVE_WITH_BANNER ||
                        !SimpleCloseEngineFeature.RESULT_BANNER_TAKES_FOCUS || resultBanner?.talkBackOn() != true)
                    // F31 (B_DEMO_RULES.md 6-1): B judges the popup again right before the press (automatic presses only).
                    var stopped: PrePressStop? = null
                    val closable = when {
                        decision.strong || rememberedButton != null -> STRONG_KINDS
                        decision.unknown -> preferences.current().autoCloseKinds(live = true) + PopupClassification.UNKNOWN
                        else -> preferences.current().autoCloseKinds(live = true)
                    }
                    val prePress: (suspend () -> Boolean)? = if (SimpleCloseEngineFeature.PRE_PRESS_RECHECK) {
                        { prePressStop(classified, byB, closable).also { stopped = it } != null }
                    } else {
                        null
                    }
                    val result = try {
                        pressAndConfirm(classified, decision.candidate, preClickMove, watchFocus = moveFocus, prePress = prePress)
                    } finally {
                        if (pressing == words) pressing = null
                    }
                    if (result.attempted) chainGuard.pressed(read.packageName, words, SystemClock.uptimeMillis(), automatic = true)
                    val stop = stopped
                    if (stop != null) {
                        // Not pressed. The person is asked about the popup as it is now (a decision gets [버튼으로 이동]
                        // only; F34: a scam gets the scam prompt, [askSuspicious], as [SimpleDecision.DenySuspicious] does).
                        tts?.stop()
                        if (prompted == null && stop.fresh != null) {
                            if (stop.suspicious) askSuspicious(stop.fresh) else ask(stop.fresh, stop.kind, talkBackReadIt = false)
                        }
                    } else if (PressSuccess.isConfirmed(result)) {
                        noteClosed(words, decision.candidate)
                        // F24: the page settles after a close; no fast re-reads of it.
                        inspectionEvents.backOff()
                        speakClosedThenFocusPage(classified, moveFocus)
                    } else if (result.attempted && result.outcome != ClickOutcome.LEFT_APP) {
                        // F20: pressed and not closed (or back again): no card, no focus move, no second press; the
                        // person is asked right away, without the button that failed.
                        endReclaim(null)
                        tts?.stop()
                        rememberFailed(classified, decision.candidate)
                        // F27 (review #3): a remembered rule that failed is forgotten, also across restarts.
                        if (rememberedButton != null) {
                            withContext(Dispatchers.Default) { remembered.remove(RememberedPopup.keyOf(read.packageName, popup)) }
                            log.trace(SimpleTrace.REMEMBERED_RULE_DROPPED)
                        }
                        if (classified.root.kind == PopupRootKind.BOTTOM_BANNER) {
                            // F37 (LG 11/2 G마켓 웹: a floating "출첵" bubble's × did not close it, then a prompt): a banner
                            // hides nothing; a failed close is only recorded (never pressed again), no prompt, no sentence,
                            // and it is left alone while it stays.
                            kept += words
                            if (kept.size > 32) kept.remove(kept.first())
                            log.trace(SimpleTrace.BANNER_CLOSE_FAILED_QUIET)
                        } else {
                            log.trace(SimpleTrace.AUTO_CLOSE_FAILED_ASKED)
                            if (prompted == null) ask(classified, classification, talkBackReadIt = false, failedNow = true)
                        }
                    } else {
                        // F27 (review #4): a press that left the app (a store page) is not pressed again on return.
                        if (result.attempted && result.outcome == ClickOutcome.LEFT_APP) {
                            rememberFailed(classified, decision.candidate)
                            // F34 (시험 앱 P19: "닫기" sent the person to the home screen): this app gets no more automatic
                            // presses this session; its popups are asked.
                            leftAppPaused += read.packageName
                            if (leftAppPaused.size > 32) leftAppPaused.remove(leftAppPaused.first())
                            log.trace(SimpleTrace.AUTO_CLOSE_PAUSED_APP_LEFT)
                        }
                        tts?.stop()
                        handlingListener?.onHandled(HandlingReport(analysis.token, classification, approval, result))
                    }
                  } finally {
                    if (pressing == words) pressing = null
                  }
                }
                // Another action was running: nothing was pressed, the popup is looked at again on the next read.
                if (!started && pressing == words) pressing = null
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
                // F34 (시험 앱 P21/P22: DECIDED_DENY_SUSPICIOUS, no prompt): the warning is the prompt's first line, with
                // [닫기 버튼으로 이동], [내용 다시 읽기], [그대로 두기]; nothing pressed for the person. No prompt possible:
                // the spoken warning as before.
                if (!askSuspicious(classified)) {
                    val deny = ActionDecision(analysis.token, ActionAuthorization.DENY, persistentSetOf(SafetyReason.SUSPICIOUS_CONTENT), null, DecisionSource.PRESET, NextStep.DENY)
                    handlingListener?.onHandled(HandlingReport(analysis.token, classification, deny))
                }
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
                ask(classified, classification, talkBackReadIt = inside == true && !lateFailure, failedNow = lateFailure)
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
                    // F39: the card is watched a little longer than the page (a late focus restore after a voice choice).
                    watch.until = SystemClock.uptimeMillis() + SimpleCloseEngineFeature.FOCUS_RECLAIM_CARD_WINDOW_MILLIS
                    scope.launch {
                        delay(SimpleCloseEngineFeature.FOCUS_RECLAIM_CARD_WINDOW_MILLIS)
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
     * F34: the scam prompt. "사기일 수 있는 팝업이에요. 대신 누르지 않아요." with its close buttons (named, and the one X-shaped
     * button) as [버튼으로 이동] only, then [내용 다시 읽기] and [그대로 두기]. Nothing here is a press; the "경품 받기"-like
     * button is not offered at all. False when no prompt could be shown.
     */
    private fun askSuspicious(popup: SimplePopup): Boolean {
        val presenter = overlay ?: return false
        val planned = ChoicePromptPlanner.plan(
            popup.analysis, PopupClassification.USER_DECISION, labelPolicy = SimpleLabelPolicy,
            rememberChoicesEnabled = false, preset = preferences.current().handlingPreset,
        )
        val prompt = (if (SimpleCloseEngineFeature.READ_POPUP_TEXT_ON_PROMPT) planned else planned.copy(popupText = null))
            .copy(summary = PromptSummary.SUSPICIOUS, cannotPressForUser = false)
        val closes = popup.scan.candidates.filter { it.role == ButtonRole.CLOSE } +
            listOfNotNull(popup.scan.xButton?.copy(label = getString(R.string.ui_simple_x_button)))
        val shown = SimplePromptOptions.adjust(prompt, closes)
        prompted = popup
        focusedBeforeClose = false
        if (presenter.present(shown) { answer -> onAnswer(popup, answer) } != PromptShowResult.SHOWN) {
            prompted = null
            log.trace(SimpleTrace.PROMPT_NOT_SHOWN)
            return false
        }
        log.trace(SimpleTrace.SUSPICIOUS_ASKED)
        log.trace(SimpleTrace.ASKED)
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
        // F22: "앞으로 항상 닫기" for the X: pressed once now, remembered only after the close is confirmed (AGENTS 6-4).
        val withRemember = if (x == null || !SimpleCloseEngineFeature.REMEMBER_X_ALWAYS || !RememberedPopupRules.canRemember(popup)) withX else withX.copy(
            options = withX.options.flatMap { o ->
                if (o.kind == PromptOptionKind.PRESS_CANDIDATE && o.candidateId == x.id) listOf(o, o.copy(kind = PromptOptionKind.REMEMBER_ALWAYS)) else listOf(o)
            }.toImmutableList(),
        )
        if (withRemember !== withX) log.trace(SimpleTrace.REMEMBER_OFFERED)
        // F32 (LG 11/1 11번가 app: a decision popup, "로그인하러 가기" + an unnamed round X): where the X is not a press option
        // (a decision, or a risky button beside it), it is still a place to go: [X 모양 닫기 버튼으로 이동], never pressed by us.
        val xMove = popup.scan.xButton?.takeIf { x == null }?.copy(label = getString(R.string.ui_simple_x_button))
        if (xMove != null) log.trace(SimpleTrace.X_BUTTON_MOVE_OFFERED)
        val shown = SimplePromptOptions.adjust(withRemember, listOfNotNull(xMove) + popup.scan.riskButtons, failedButtons[keyOf(popup)].orEmpty(), failedNow)
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
            ChoiceKind.CLOSE_ONCE, ChoiceKind.SKIP_ONCE, ChoiceKind.REMEMBER_EXACT_POPUP -> {
                log.trace(SimpleTrace.USER_CHOSE_PRESS)
                log.trace(if (answer.origin == ChoiceOrigin.VOICE) SimpleTrace.USER_CHOSE_BY_VOICE else SimpleTrace.USER_CHOSE_BY_TAP)
                val remember = choice.kind == ChoiceKind.REMEMBER_EXACT_POPUP && choice.buttonMeaningConfirmed && choice.repeatAutomationExplicitlyAllowed
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
                    // F31 (B_DEMO_RULES.md 6-2): the person's press may open the next popup too; not counted as automatic.
                    if (result.attempted) chainGuard.pressed(popup.snapshot.token.packageName, keyOf(popup), SystemClock.uptimeMillis(), automatic = false)
                    when {
                        PressSuccess.isConfirmed(result) -> {
                            noteClosed(keyOf(popup), candidate)
                            inspectionEvents.backOff()
                            if (remember) {
                                RememberedPopupRules.ruleFor(popup.snapshot.token.packageName, popup, candidate)?.let { rule ->
                                    withContext(Dispatchers.Default) { remembered.put(rule) }
                                    log.trace(SimpleTrace.REMEMBERED_SAVED)
                                }
                            }
                            speakClosedThenFocusPage(popup, moveFocus = true)
                        }
                        // F21: the person's own press did not close it either: asked again at once, without that button.
                        result.attempted && result.outcome != ClickOutcome.LEFT_APP -> {
                            endReclaim(null)
                            tts?.stop()
                            rememberFailed(popup, candidate)
                            log.trace(SimpleTrace.USER_PRESS_FAILED_ASKED)
                            if (prompted == null) ask(popup, popup.analysis.classification, talkBackReadIt = false, failedNow = true)
                        }
                        else -> {
                            if (result.attempted && result.outcome == ClickOutcome.LEFT_APP) rememberFailed(popup, candidate)
                            speakOwnPress(result)
                        }
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
        val x = popup.scan.xButton?.copy(label = getString(R.string.ui_simple_x_button))
        val candidate = (popup.scan.riskButtons + popup.scan.candidates + listOfNotNull(x)).firstOrNull { it.id == candidateId } ?: return
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

    /**
     * F26: a pane appearing is a new window part (budget and re-check chain start again). A subtree change of a large
     * container that is not a list or web view ([ReadBudget.likelyPopup]) likely added a popup: its source bounds are
     * looked up (one binder call), at most every [LIKELY_POPUP_CHECK_MILLIS]. Everything else is a plain repaint.
     */
    private fun contentKind(event: AccessibilityEvent): SimpleInspectionEvent {
        val types = event.contentChangeTypes
        if (types and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED != 0) return SimpleInspectionEvent.WINDOW
        val subtree = types and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0
        val className = event.className?.toString()
        val now = SystemClock.uptimeMillis()
        if (!subtree || !ReadBudget.likelyPopup(true, className, 1.0) || now - likelyPopupCheckAt < LIKELY_POPUP_CHECK_MILLIS) {
            return SimpleInspectionEvent.CONTENT
        }
        likelyPopupCheckAt = now
        val ratio = collector.sourceAreaRatio(event) ?: return SimpleInspectionEvent.CONTENT
        return if (ReadBudget.likelyPopup(true, className, ratio)) SimpleInspectionEvent.CONTENT_LIKELY_POPUP else SimpleInspectionEvent.CONTENT
    }

    /** F24: adds one read to the current 10 s window and logs the share of time reading when the window is over. */
    private fun countDuty(start: Long, cost: Long) {
        if (dutyWindowStart == 0L) dutyWindowStart = start
        dutyBusy += cost
        val elapsed = start + cost - dutyWindowStart
        if (elapsed >= DUTY_WINDOW_MILLIS) {
            log.latency("READ_DUTY_PCT", dutyBusy * 100 / elapsed)
            dutyWindowStart = 0L
            dutyBusy = 0L
        }
    }

    /** F27 (review #1): a confirmed close, kept a few seconds to catch the popup coming back. */
    private fun noteClosed(words: String, candidate: CloseCandidate) {
        recentlyClosed[words] = SystemClock.uptimeMillis() to ButtonLabelRules.normalize(candidate.label ?: getString(R.string.ui_simple_x_button))
        if (recentlyClosed.size > 16) recentlyClosed.remove(recentlyClosed.keys.first())
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
        VideoAdDiag.AD_MARK_NOT_NEAR -> SimpleTrace.VIDEO_SKIP_AD_MARK_NOT_NEAR
    }

    /**
     * F13: the person picked a press on our window. Right now, before the window is removed, TalkBack's
     * focus goes to a place that survives the close ([PageFocusRules.plan] with beforeClick: the toolbar,
     * a heading or an item above the popup): removed under TalkBack's focus, our window let it fall back
     * into the popup, which it read again (LG 11/1). Main thread, a bounded walk; nothing is clicked.
     */
    private fun focusPageBeforeOurWindowGoes(answer: PromptAnswer) {
        val kind = (answer as? PromptAnswer.Picked)?.choice?.kind ?: return
        if (kind != ChoiceKind.CLOSE_ONCE && kind != ChoiceKind.SKIP_ONCE && kind != ChoiceKind.REMEMBER_EXACT_POPUP) return
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
            if (!ours) log.trace(if (FocusReclaimRules.intoPopup(focused?.first, watch.memory)) SimpleTrace.FOCUS_OTHER_INTO_POPUP else SimpleTrace.FOCUS_OTHER_ON_PAGE)
            val cardHoldsFocus = watch.onBanner && resultBanner?.holdsFocusTarget == true
            when (FocusReclaimRules.decide(focused?.first, ours, watch.memory, watch.count, SimpleCloseEngineFeature.FOCUS_RECLAIM_MAX, cardHoldsFocus)) {
                ReclaimVerdict.OUR_OWN, ReclaimVerdict.LEAVE -> Unit
                ReclaimVerdict.LIMIT -> endReclaim(SimpleTrace.FOCUS_RECLAIM_LIMIT)
                ReclaimVerdict.RECLAIM_TO_CARD -> {
                    lastOwnFocusAt = now
                    if (resultBanner?.refocus() == true) {
                        watch.count += 1
                        watch.lastMoveAt = now
                        log.trace(SimpleTrace.FOCUS_RECLAIM_PAGE_TO_CARD)
                    } else {
                        log.trace(SimpleTrace.FOCUS_RECLAIM_CARD_REFUSED)
                    }
                }
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
    private suspend fun pressAndConfirm(
        popup: SimplePopup,
        candidate: CloseCandidate,
        moveFocus: Boolean,
        watchFocus: Boolean,
        /** F31: true = do not press (B's verdict changed right before it, [prePressStop]). */
        prePress: (suspend () -> Boolean)? = null,
    ): ActionResult {
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
            if (prePress != null && prePress()) {
                endReclaim(null)
                return ActionResult(token, ActionStatus.BLOCKED, SafetyReason.STALE_SCREEN, attempted = false)
            }
            // F17: the watch is on before the click (the person's press armed it as our window went).
            if (watchFocus && SimpleCloseEngineFeature.FOCUS_RECLAIM_AFTER_CLOSE && reclaim == null) {
                reclaim = FocusReclaim(PopupMemory.of(popup), target, SystemClock.uptimeMillis())
            }
            // F27 (review #5): the checked node itself only, never a clickable parent.
            val clicked = clickOnce(candidate.nodePath!!, candidate.nodePath, popup.root.windowId)
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
            log.trace(SimpleTrace.valueOf("CLICKED_${SimpleLabelRules.pressKind(candidate.label, candidate.role, candidate.evidence == EvidenceLevel.E4_VISUAL_ONLY).name}"))
            focusMovesByOthers = 0
            val deadline = SystemClock.uptimeMillis() + SimpleCloseEngineFeature.CONFIRM_DELAY_MILLIS
            var outcome = ClickOutcome.STILL_PRESENT
            var notClosedWhy = SimpleTrace.NOT_CLOSED_STILL_THERE
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
                // F33: no active window for a moment (one activity closing, the next coming): look again, never a failure at once.
                if (observed == ClickOutcome.UNKNOWN) {
                    if (SystemClock.uptimeMillis() >= deadline) {
                        outcome = ClickOutcome.UNKNOWN
                        notClosedWhy = SimpleTrace.NOT_CLOSED_NO_TREE
                        break
                    }
                    continue
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
                val memory = PopupMemory.of(popup)
                // F29 (LG 11/1 11번가 웹: the sheet closed, but 220 ms later it was still sliding out): a popup found again
                // somewhere else is read again until it is gone, back in place, or still at one place ([RecheckRules]).
                var previous: Reappearance? = null
                var reads = 0
                var back: Boolean
                while (true) {
                    delay(SimpleCloseEngineFeature.CLOSE_RECHECK_MILLIS)
                    val found = withContext(Dispatchers.Default) {
                        // F27 (review #1): a popup a whole read found is re-checked with a whole read (the probe may not see it).
                        val again = collector.read(token.packageName, ::allowsApp, wholeIfProbeMisses = !popup.foundByProbe,
                            capMillis = if (popup.foundByProbe) RECHECK_CAP_MILLIS else WholeReadCap.MAX_MILLIS,
                            appWindows = popup.root.kind == PopupRootKind.APP_WINDOW, lookUpAddress = false)
                            ?: return@withContext null
                        val root = PopupRootFinder.find(again.nodes, again.screen, again.activeWindowBounded) ?: return@withContext null
                        memory.reappearance(root, again.nodes, again.screen)
                    }
                    reads += 1
                    val verdict = RecheckRules.verdict(found, previous)
                    if (verdict != RecheckVerdict.MOVING) {
                        back = verdict == RecheckVerdict.BACK
                        break
                    }
                    log.trace(SimpleTrace.CLOSE_RECHECK_MOVING)
                    if (SystemClock.uptimeMillis() - recheckStart >= SimpleCloseEngineFeature.CLOSE_RECHECK_SETTLE_MILLIS) {
                        // Still moving when the time is up: never a false "closed" (AGENTS 6-5); asked, as a failure.
                        log.trace(SimpleTrace.CLOSE_RECHECK_STILL_MOVING)
                        notClosedWhy = SimpleTrace.NOT_CLOSED_STILL_MOVING
                        back = true
                        break
                    }
                    previous = found
                }
                log.latency("CLOSE_RECHECK_MS", SystemClock.uptimeMillis() - recheckStart)
                log.latency("CLOSE_RECHECK_READS", reads.toLong())
                if (back) {
                    if (notClosedWhy != SimpleTrace.NOT_CLOSED_STILL_MOVING) notClosedWhy = SimpleTrace.NOT_CLOSED_REAPPEARED
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
            if (outcome != ClickOutcome.CLOSED_CONFIRMED && outcome != ClickOutcome.LEFT_APP) log.trace(notClosedWhy)
            return ActionResult(token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = true, outcome = outcome)
        }
    }

    /**
     * F36 (LG 11/2 P16: VIDEO_SKIP_WAITING ×3, then no read saw the skip within 12 s): while a cover counts down, it is
     * read again in [VIDEO_COVER_REREAD_MILLIS] (one pending at a time) for at most [VIDEO_COVER_WAIT_MAX_MILLIS], as a
     * window event (the repaint budget grows while nothing is found and may skip the moment the skip appears).
     */
    private fun rereadCoverSoon(packageName: String) {
        val now = SystemClock.uptimeMillis()
        // A new window only for another app, or after a pause with no countdown seen (another ad).
        if (coverWaitPackage != packageName || now - coverWaitSeenAt > VIDEO_COVER_NEW_AD_GAP_MILLIS) {
            coverWaitPackage = packageName
            coverWaitUntil = now + VIDEO_COVER_WAIT_MAX_MILLIS
        }
        coverWaitSeenAt = now
        if (coverReread?.isActive == true || now > coverWaitUntil) return
        log.trace(SimpleTrace.VIDEO_COVER_REREAD)
        coverReread = scope.launch {
            delay(VIDEO_COVER_REREAD_MILLIS)
            inspectionEvents.onEvent(packageName, SimpleInspectionEvent.WINDOW)
        }
    }

    /**
     * F35 (B_DEMO_RULES.md 8-4): while a video ad counts down to its skip, its seconds as read on screen
     * ([VideoAdSkip.waitSeconds]) are said once per ad ("광고를 5초 뒤에 건너뛸게요"), only where the skip will be pressed for
     * the person (YouTube and the preset closes ads). TalkBack on: through TalkBack's own queue (the result card, no focus
     * taken); off: our TTS. No number read: nothing said.
     */
    private fun announceVideoWait(read: TreeRead) {
        if (PopupClassification.ADVERTISEMENT !in preferences.current().autoCloseKinds(live = true)) return
        val key = "${read.packageName}:${read.windowId}"
        val now = SystemClock.uptimeMillis()
        if (videoWaitAnnounced?.let { (k, at) -> k == key && now - at < VIDEO_WAIT_ANNOUNCE_AGAIN_MILLIS } == true) return
        val seconds = VideoAdSkip.waitSeconds(read.nodes, read.screen) ?: return
        videoWaitAnnounced = key to now
        val sentence = getString(R.string.ui_video_skip_wait, seconds)
        log.trace(SimpleTrace.VIDEO_SKIP_WAIT_ANNOUNCED)
        val banner = resultBanner
        if (banner != null && banner.talkBackOn() && banner.show(sentence)) return
        val announcer = tts ?: return
        scope.launch { announcer.announce(AnnouncementRequest(null, AnnouncementKind.ACTION_RESULT, sentence)) }
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
        if (key in failedSkips) {
            log.trace(SimpleTrace.VIDEO_SKIP_FAILED_BEFORE)
            return
        }
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
                true -> {
                    log.trace(SimpleTrace.CLICKED)
                    log.trace(SimpleTrace.CLICKED_SKIP)
                }
            }
            val deadline = SystemClock.uptimeMillis() + SimpleCloseEngineFeature.CONFIRM_DELAY_MILLIS
            var gone = false
            while (!gone && SystemClock.uptimeMillis() < deadline) {
                delay(SimpleCloseEngineFeature.CONFIRM_POLL_MILLIS)
                gone = withContext(Dispatchers.Default) { !collector.targetMatches(candidate, windowId) }
            }
            if (!gone) {
                // F27 (review #2): not pressed again every 10 s; this button is done for this session.
                failedSkips += key
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

    /** F31: why an automatic press was stopped: the popup as read again ([fresh], null when unread), B's kind of it. */
    private class PrePressStop(val fresh: SimplePopup?, val kind: PopupClassification, val suspicious: Boolean)

    /**
     * F31 (B_DEMO_RULES.md 6-1): the popup's own subtree read again right before an automatic press and judged by B
     * again ([PrePressRecheck]). Null = press. The read's cost is logged (`PRE_PRESS_RECHECK_MS`), never its words.
     */
    private suspend fun prePressStop(popup: SimplePopup, decidedByB: PopupClassification, closable: Set<PopupClassification>): PrePressStop? {
        val start = SystemClock.uptimeMillis()
        val again = withContext(Dispatchers.Default) { collector.readPopupAgain(popup.root, SimpleCloseEngineFeature.PRE_PRESS_READ_CAP_MILLIS) }
        log.latency("PRE_PRESS_RECHECK_MS", SystemClock.uptimeMillis() - start)
        if (again == null) {
            log.trace(SimpleTrace.PRE_PRESS_RECHECK_STOPPED)
            log.trace(SimpleTrace.PRE_PRESS_STOPPED_UNREAD)
            val still = withContext(Dispatchers.Default) { collector.rootStillPresent(popup.root) } == true
            return PrePressStop(popup.takeIf { still }, popup.analysis.classification, suspicious = false)
        }
        val (nodes, screen) = again
        val built = withContext(Dispatchers.Default) {
            SimplePopupAnalysis.build(nodes, popup.root, popup.snapshot.token.packageName, popup.snapshot.token.windowId, screen, screenVersion, SystemClock.uptimeMillis())
        }
        val kind = classifier.classify(built.analysis)
        val promoBuy = PromoPurchaseGate.allowed(DecisionDemoRules.PROMO_PURCHASE, kind, false, built.analysis.texts, built.scan.promoBuyCount) +
            PromoLoginGate.allowed(DecisionDemoRules.PROMO_LOGIN, kind, false, built.analysis.texts, built.scan.promoLoginCount)
        val fresh = built.copy(scan = built.scan.copy(promoBuyCount = promoBuy))
        val block = PrePressRecheck.check(decidedByB, fresh, closable) ?: run {
            log.trace(SimpleTrace.PRE_PRESS_RECHECK_PASSED)
            return null
        }
        log.trace(SimpleTrace.PRE_PRESS_RECHECK_STOPPED)
        log.trace(SimpleTrace.valueOf("PRE_PRESS_STOPPED_${block.name}"))
        return PrePressStop(fresh.copy(analysis = fresh.analysis.copy(classification = kind)), kind, RememberChoiceRules.suspiciousByB(fresh.analysis))
    }

    /**
     * F28 (LG 11/1 14:29 올리브영: a DECISION_ASKED prompt stayed on screen over our own app): when no application
     * window of the popup's app is on screen any more (another app, ours included, came to the front), the prompt
     * closes and the inspection in flight is dropped (AGENTS 6-7). Our prompt is an overlay, not an application
     * window, so it never counts as "another app". TalkBack's focus is not moved. Unknown windows: nothing done.
     */
    private fun checkPromptedAppStillShown(popup: SimplePopup) {
        // The latest event wins, a burst of window events is one look-up: a leave during a look-up is not lost.
        appLeftCheck?.cancel()
        appLeftCheck = scope.launch {
            delay(APP_LEFT_SETTLE_MILLIS)
            val shown = withContext(Dispatchers.Default) { collector.applicationPackages() }
            if (prompted !== popup || !PromptedApp.left(popup.snapshot.token.packageName, shown)) return@launch
            prompted = null
            jobs.cancelInspection()
            overlay?.dismiss()
            log.trace(SimpleTrace.CHOICE_DISMISSED_APP_LEFT)
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
        pressing = null
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
        recentlyClosed.clear()
        failedSkips.clear()
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
        const val VIDEO_WEB_STICKY_MILLIS = 60_000L
        const val SLOW_PAGE_MILLIS = 60_000L
        const val DUTY_WINDOW_MILLIS = 10_000L
        const val LIKELY_POPUP_CHECK_MILLIS = 300L
        /** F27: the same popup back within this after a confirmed close is a failed close. */
        const val REAPPEAR_WINDOW_MILLIS = 5_000L
        /** F26 (LG 11/1 올리브영 앱: CLOSE_RECHECK_MS 905): the strict re-check's read has this much, probe included. */
        const val RECHECK_CAP_MILLIS = 250L
        /** F31: what 강력 모드 (and a remembered rule) closes: every kind but decisions and scams. */
        val STRONG_KINDS = setOf(PopupClassification.ADVERTISEMENT, PopupClassification.COUPON, PopupClassification.NOTICE, PopupClassification.UNKNOWN)
        /** F36: a counting-down video cover is read again this often, for at most this long. */
        const val VIDEO_COVER_REREAD_MILLIS = 1_000L
        const val VIDEO_COVER_WAIT_MAX_MILLIS = 15_000L
        const val VIDEO_COVER_NEW_AD_GAP_MILLIS = 30_000L
        /** F35: the same app and window counts as the same video ad for this long (one wait sentence per ad). */
        const val VIDEO_WAIT_ANNOUNCE_AGAIN_MILLIS = 30_000L
        /** F34: after [LateCloseWait.MAX_WAIT_MILLIS] one more look, so a popup that never got its close is asked. */
        const val LATE_CLOSE_LAST_LOOK_MILLIS = 100L
        /** F32 (e): how long an activity named like a popup counts as a hint for its app's reads. */
        const val POPUP_ACTIVITY_HINT_MILLIS = 10_000L
        /** F28: a burst of window events settles this long before the look-up. */
        const val APP_LEFT_SETTLE_MILLIS = 150L
        /** F28: events after which the popup's app may have left the screen. */
        val APP_SWITCH_EVENTS = setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED)
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

    /** F32: a line of codes and size classes only ([WindowBoundsRules.line]); never a package or screen text. */
    fun line(codes: String) {
        if (enabled) Log.d(TAG, codes)
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
