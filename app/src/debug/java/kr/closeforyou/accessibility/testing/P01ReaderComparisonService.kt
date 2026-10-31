package kr.closeforyou.accessibility.testing

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kr.closeforyou.accessibility.*
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.accessibility.integration.*
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.core.model.*
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.overlay.*

/** Idle until instrumentation arms it. Separate service avoids suppressing events or changing
 * the production C lifetime. Uses the real collectors and C View; NEVER wires an executor.
 * The operator enables this service and consent in UI. No settings writes / shell permission.
 * Test-only extra region getters run on the SAME returned window handles and are recycled by
 * the original readers. No raw Android handles escape a synchronous read. */
class P01ReaderComparisonService : AccessibilityService() {
    companion object {
        internal const val HOST = "kr.closeforyou.popuptest"
        internal var connected: P01ReaderComparisonService? = null; private set
    }
    private var active = false
    private var failed = false
    private var collectingRegions = false
    private var regionReads = mutableListOf<Map<Int,List<ScreenBounds>>>()
    private var epoch = 0L
    private var armAt = 0L
    private var events = 0
    private var hardInvalidation = false
    private var preparing = false
    private val preparationChanges = Channel<Unit>(Channel.CONFLATED)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchdog: Job? = null
    private var overlay: AccessibilityChoiceOverlay? = null
    private val owner = Any()
    private var native: OverlayNativeWindowLifetime? = null
    private var source: PopupSnapshot? = null
    private var nativeBefore: Set<Int>? = null
    private var callbackSeen = false
    private var checks = ComparisonRunChecks(0)
    private var configuration: Configuration? = null
    private var cRequested = false
    private var cWasLive = false
    internal val issue get() = checks.issue
    internal val receiving get() = checks.receiving
    internal var sink: (String) -> Unit = {}
    private val collector = AndroidSnapshotCollector()
    private var readCode: F9ReadCode? = null
    private val reader by lazy { AndroidF9ScreenPort(this, onScanReport = { readCode = it }) { allowed(it) } }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS; notificationTimeout = 0 }
        connected = this
    }
    private fun consentReady(): Boolean {
        val s = CorePreferences.get(this).currentSettings()
        return s.collectionEnabled && s.allowedPackages == setOf(HOST) && !s.captureConsentGranted
    }
    private fun exclusiveService(): Boolean {
        val enabled = getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        // No second service may collect/click or add an unaccounted C window during this isolation.
        return enabled.size == 1 && enabled.single().resolveInfo.serviceInfo.let {
            it.name == javaClass.name && it.packageName == packageName
        }
    }
    private fun allowed(pkg: String) = active && !failed && !hardInvalidation && pkg == HOST && consentReady()
    internal fun arm() {
        if (active || failed || overlay != null || !consentReady() || !exclusiveService())
            throw ComparisonAbort(ComparisonIssue.SETUP_REQUIRED)
        active = true; hardInvalidation = false; callbackSeen = false; events = 0; epoch++
        armAt = SystemClock.uptimeMillis()
        checks = ComparisonRunChecks(armAt); configuration = Configuration(resources.configuration)
        cRequested = false; cWasLive = false
        watchdog = scope.launch {
            delay(8_000); checks.fail(ComparisonIssue.TIME_LIMIT); failed = true; dismissSafely(); active = false
        }
    }
    internal fun checkLive() {
        if (connected !== this) checks.fail(ComparisonIssue.SERVICE_DISCONNECTED)
        if (SystemClock.uptimeMillis() - armAt !in 0 until 8_000) checks.fail(ComparisonIssue.TIME_LIMIT)
        if (!active || failed || hardInvalidation || callbackSeen || !consentReady()) checks.fail(ComparisonIssue.INVALIDATED)
        if (issue != ComparisonIssue.NONE) throw ComparisonAbort(issue)
    }
    private fun checkBoundary() {
        checkLive()
        if (!exclusiveService()) throw ComparisonAbort(ComparisonIssue.SETUP_REQUIRED)
        if (configuration != resources.configuration) throw ComparisonAbort(ComparisonIssue.CONFIGURATION_CHANGED)
    }
    /** Connection alone is insufficient. Wait only for real synthetic-host event delivery;
     * the event may be from the background Activity, not the P01 Dialog.
     * This does not approve geometry or delay production admission. No screen read/poll here. */
    internal suspend fun awaitReception() {
        repeat(100) {
            checkBoundary()
            if (receiving) { emit("SERVICE=CONNECTED;RECEPTION=HOST_EVENT_CONFIRMED;POPUP_READY=UNCONFIRMED"); return }
            delay(10)
        }
        checks.fail(ComparisonIssue.EVENT_UNCONFIRMED)
        throw ComparisonAbort(issue)
    }
    /** Explicit opt-in test mode only; no change to production admission or normal INITIAL. */
    internal suspend fun prepareComparison(): PreparationEnd {
        checkBoundary(); preparing=true
        try {
            val result=P01ComparisonPreparation.run(SystemClock::uptimeMillis, ::checkBoundary,
                read = { attempt ->
                    preparationChanges.tryReceive() // One conflated slot; do not reuse an older trigger.
                    read(ComparisonReader.COLLECTOR_FRESH,ComparisonPhase.INITIAL,attempt,null,preparation=true)
                }, awaitChange = { remaining ->
                    if (remaining <= 0) false else withTimeoutOrNull(remaining) {
                        preparationChanges.receive(); checkBoundary(); true
                    } ?: false
                }, accept = { observed -> checks.preparedTarget(requireNotNull(observed.sample)) }, report = ::emit)
            checkBoundary()
            if (result != PreparationEnd.READY) {
                checks.fail(ComparisonIssue.PREPARATION_NOT_READY); failed=true
            }
            return result
        } finally {
            preparing=false; preparationChanges.tryReceive()
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!active || event == null) return
        // No event.source/tree getter. Event occurrence and delivery clocks are distinct samples.
        val delivered = SystemClock.uptimeMillis()
        val hostEvent = event.packageName?.toString() == HOST && event.eventType in setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        val freshHostEvent=checks.event(hostEvent,event.windowId,event.eventTime,delivered)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) checks.targetLifecycle(event.windowId,
            event.windowChanges and (AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_REMOVED) != 0)
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOW_SET"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT"
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "FOCUS"
            else -> "OTHER"
        }
        val mask = if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) "NONE" else when (event.windowChanges) {
            AccessibilityEvent.WINDOWS_CHANGE_BOUNDS -> "BOUNDS"
            AccessibilityEvent.WINDOWS_CHANGE_ADDED -> "ADDED"
            AccessibilityEvent.WINDOWS_CHANGE_REMOVED -> "REMOVED"
            else -> "OTHER_OR_MIXED"
        }
        val relation = when (event.windowId) {
            source?.token?.windowId -> "TARGET"
            native?.liveWindow(owner,native?.generation ?: -1) -> "NATIVE_C"
            else -> "UNCONFIRMED"
        }
        if (event.eventType in setOf(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
                AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START, AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED, AccessibilityEvent.TYPE_VIEW_SCROLLED)) hardInvalidation = true
        if (preparing && (freshHostEvent || hardInvalidation || issue != ComparisonIssue.NONE)) preparationChanges.trySend(Unit)
        if (events++ < 48) emit("EVENT=$kind;MASK=$mask;RELATION=$relation;" +
            "DELIVERY_MS=${delivered-armAt};OCCURRENCE_MS=${event.eventTime-armAt}")
    }
    private fun emit(codes: String) {
        try { sink("A_P01_READER;" + codes) }
        catch (_: Exception) { checks.fail(ComparisonIssue.UNEXPECTED_FAILURE); failed=true }
    }
    private fun cState(): ComparisonCState {
        val n=native
        val live=n?.liveWindow(owner,n.generation)!=null
        val result=comparisonCState(cRequested,overlay?.isShowing==true,live,cWasLive)
        if (result==ComparisonCState.NATIVE_ATTACHED) cWasLive=true
        return result
    }

    /** Additional getters are confined to this opt-in service; normal service getWindows is untouched. */
    @Suppress("DEPRECATION")
    override fun getWindows(): MutableList<AccessibilityWindowInfo> {
        val found = super.getWindows()
        if (!collectingRegions) return found
        try {
            check(found.size in 1..32)
            val map = linkedMapOf<Int,List<ScreenBounds>>()
            for (w in found) {
                check(w.displayId == 0 && !map.containsKey(w.id))
                val region = Region(); w.getRegionInScreen(region)
                val iterator = RegionIterator(region); val rect = Rect(); val parts = mutableListOf<ScreenBounds>()
                while (iterator.next(rect)) {
                    check(parts.size < 64)
                    parts += ScreenBounds(rect.left,rect.top,rect.right,rect.bottom)
                }
                map[w.id] = parts.toList()
            }
            check(regionReads.size < 4); regionReads += map
            return found
        } catch (failure: Throwable) {
            val batch = CollectionReleaseBatch()
            found.forEach { batch.attempt { it.recycle() } }
            try { batch.finish() } catch (_: Exception) { failed = true }
            throw failure
        }
    }
    internal data class Read(val sample: ReaderSample?, val ready: CollectionResult.Ready?, val detail: F9ReadDetail? = null,
        val status: ComparisonStatus = ComparisonStatus.FAILED, val block: PrivacyBlockReason? = null,
        val f0Detail: CaptureDetail = CaptureDetail.NONE, val f0Point: CaptureReadPoint = CaptureReadPoint.UNKNOWN,
        val f0Release: CaptureReadRelease = CaptureReadRelease.UNKNOWN) {
        override fun toString() = "Read(redacted)"
    }
    @Suppress("DEPRECATION")
    private fun nativeIdentity(): ManualDisplayIdentity? {
        val n = native ?: return null
        val id = n.liveWindow(owner,n.generation) ?: return null
        val before = nativeBefore ?: return null
        if (id in before) return null
        val windows = super.getWindows()
        return try {
            if (windows.size !in 1..32 || windows.any { it.displayId != 0 }) return null
            val owned = windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            if (owned.size == 1 && owned.single().id == id) ManualDisplayIdentity(id,ManualDisplayListing.LISTED)
            else null // Not enumerated is a recorded missing arm, NEVER fabricated ownership.
        } finally {
            val batch = CollectionReleaseBatch(); windows.forEach { batch.attempt { it.recycle() } }; batch.finish()
        }
    }
    internal fun read(kind: ComparisonReader, phase: ComparisonPhase, number: Int, closePath: String?, preparation: Boolean = false): Read {
        checkBoundary(); val started = SystemClock.uptimeMillis(); val eventBefore = events
        val cBefore=cState()
        // An early ownership/read failure has no after-read witness.
        var cAfter=ComparisonCState.UNCONFIRMED
        var status = ComparisonStatus.BLOCKED
        var result: Read = Read(null,null)
        readCode = null; regionReads.clear(); collectingRegions = true
        var block: PrivacyBlockReason? = null
        val f0 = CaptureReadTrace()
        try {
            val ready: CollectionResult.Ready?
            val scene: F9Scene?
            if (kind == ComparisonReader.COLLECTOR_FRESH) {
                val collected = collector.capture(this,epoch,PopupTrigger.WINDOW_CHANGED,
                    { if (allowed(it)) null else PrivacyBlockReason.APP_NOT_ALLOWED },
                    requireCaptureIsolation = true, trace = CaptureTrace(null, collectionRead = f0),
                    freshPresentation = true, checkpoint = ::checkLive)
                ready = collected as? CollectionResult.Ready; scene = null
                block = (collected as? CollectionResult.Blocked)?.reason
            } else {
                ready = null
                val identity = if (overlay != null && kind == ComparisonReader.F9_WINDOW) nativeIdentity() else null
                if (overlay != null && kind == ComparisonReader.F9_WINDOW && identity == null) {
                    status = ComparisonStatus.OWNERSHIP_UNAVAILABLE
                    return result
                }
                scene = reader.readForComparison(::checkLive,kind == ComparisonReader.F9_WINDOW,
                    if (identity != null) source else null,identity)
                if (identity != null && nativeIdentity() != identity) {
                    status = ComparisonStatus.OWNERSHIP_UNAVAILABLE
                    return result
                }
            }
            checkBoundary()
            cAfter=cState()
            val regions = regionReads.lastOrNull()?.takeIf { regionReads.all { r -> r == it } }
            val snapshot = ready?.snapshot
            val sample = if (snapshot != null) ReaderSample(snapshot.token.packageName,snapshot.token.windowId,
                snapshot.screenBounds,snapshot.windows,snapshot.nodes,snapshot,ready.nativeActions,regions,closePath)
                else scene?.takeIf { it.complete }?.let { ReaderSample(it.packageName,it.windowId,it.screen,it.windows,
                    it.nodes,it.nodes,it.nativeActions,regions,closePath) }
            if (sample != null) {
                // Preparation can observe a background Activity without binding it as the popup.
                // The protected bounded popup is anchored only by prepareComparison.accept.
                if (!preparation) {
                    val binding=checks.sample(sample)
                    if (binding!=ComparisonIssue.NONE) throw ComparisonAbort(binding)
                }
                status = ComparisonStatus.COMPLETE
            }
            if (cRequested && (cBefore!=ComparisonCState.NATIVE_ATTACHED || cAfter!=ComparisonCState.NATIVE_ATTACHED)) {
                checks.fail(if(cBefore==ComparisonCState.LOST || cAfter==ComparisonCState.LOST)
                    ComparisonIssue.C_LOST else ComparisonIssue.NATIVE_C_UNCONFIRMED)
                status=ComparisonStatus.OWNERSHIP_UNAVAILABLE
                return result
            }
            result = Read(sample,ready,readCode?.detail,status,block,f0.detail,f0.failurePoint,f0.release)
            return result
        } catch (cancelled: CancellationException) {
            status = ComparisonStatus.CANCELLED; throw cancelled
        } catch (failure: Exception) {
            checks.fail((failure as? ComparisonAbort)?.issue ?: ComparisonIssue.UNEXPECTED_FAILURE)
            status = ComparisonStatus.FAILED; failed = true
            return result
        } finally {
            collectingRegions = false; regionReads.clear()
            emit((if (preparation) "SEGMENT=PREPARATION;" else "") +
                "READ=$number;PHASE=${if(preparation) "PREPARATION" else phase};SOURCE=$kind;STATUS=$status;" +
                "ISSUE=$issue;C_BEFORE=$cBefore;C_AFTER=$cAfter;RENDER=UNOBSERVED;" +
                "START_MS=${started-armAt};END_MS=${SystemClock.uptimeMillis()-armAt};" +
                "EVENTS_DURING=${events-eventBefore};F0=${block?.name ?: "NONE"};" +
                "F9_STAGE=${readCode?.stage?.name ?: "NONE"};F9_DETAIL=${readCode?.detail?.name ?: "NONE"};" +
                "F9_RELEASE=${readCode?.release?.name ?: "NONE"};" +
                "F0_DETAIL=${f0.detail};F0_POINT=${f0.failurePoint};F0_RELEASE=${f0.release}")
        }
    }
    @Suppress("DEPRECATION")
    internal fun show(prompt: ChoicePrompt, snapshot: PopupSnapshot): PromptShowResult {
        checkBoundary(); check(overlay == null && prompt.token == snapshot.token &&
            SystemClock.uptimeMillis()-snapshot.capturedAtUptimeMillis in 0 until 5_000)
        val windows = super.getWindows()
        nativeBefore = try {
            check(windows.size in 1..32 && windows.none { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY })
            windows.map { it.id }.toSet()
        } finally { val batch=CollectionReleaseBatch(); windows.forEach { batch.attempt { it.recycle() } }; batch.finish() }
        source = snapshot
        cRequested = true
        val shownEpoch=epoch
        val c = AccessibilityChoiceOverlay(this).also { overlay = it }
        c.observeNativeWindows(object: OverlayNativeWindowObserver {
            override fun created(generation: Long) = OverlayNativeWindowLifetime(generation,owner).also { native = it }
            override fun changed(lifetime: OverlayNativeWindowLifetime) {
                if (!checks.callback(active,shownEpoch,epoch) || native !== lifetime) {
                    checks.fail(ComparisonIssue.STALE_CALLBACK); failed=true
                }
            }
        })
        return c.present(prompt) {
            if (checks.callback(active,shownEpoch,epoch)) {
                callbackSeen = true; hardInvalidation = true; dismissSafely()
            } else failed=true
        }.also { emit("PRESENT=$it;NATIVE=${cState()};RENDER=UNOBSERVED;CLICK=NOT_WIRED") }
    }
    private fun dismissSafely(): Boolean = try { overlay?.dismiss(); true }
        catch (_: Exception) { checks.fail(ComparisonIssue.CLEANUP_FAILED); failed = true; false }
    /** Separate test teardown verification; NOT a replacement for production CleanupBarrier. */
    @Suppress("DEPRECATION")
    internal suspend fun finish(): Boolean {
        watchdog?.cancel(); watchdog = null
        val c=overlay; val n=native; val id=n?.liveWindow(owner,n.generation)
        var cleanupOkay=dismissSafely()
        var absent=false
        try {
            for (attempt in 0 until 10) {
                val all=super.getWindows()
                val none=try { all.isNotEmpty() && all.none { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY || it.id == id } }
                    finally { val batch=CollectionReleaseBatch(); all.forEach { batch.attempt { it.recycle() } }; batch.finish() }
                if (none && (c==null || (id!=null && n?.removalComplete(owner,n.generation,id)==true))) {
                    absent=true; break
                }
                delay(20)
            }
        } catch (cancelled: CancellationException) { failed=true; throw cancelled }
        catch (_: Exception) { checks.fail(ComparisonIssue.CLEANUP_FAILED); failed=true; cleanupOkay=false }
        finally {
            preparing=false; preparationChanges.tryReceive()
            active=false; source=null; overlay=null; native=null; nativeBefore=null; configuration=null
            regionReads.clear(); checks.clearObservations(); sink={}
        }
        if (!absent) { checks.fail(ComparisonIssue.CLEANUP_FAILED); failed=true }
        // Report native cleanup separately from an earlier read/setup failure. The failed latch
        // and first issue survive, so successful removal cannot make the experiment successful.
        return absent && cleanupOkay
    }
    override fun onInterrupt() { hardInvalidation=true; preparationChanges.trySend(Unit); dismissSafely() }
    override fun onUnbind(intent: Intent?): Boolean {
        checks.fail(ComparisonIssue.SERVICE_DISCONNECTED); hardInvalidation=true; preparationChanges.trySend(Unit); dismissSafely(); active=false
        if (connected === this) connected=null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        hardInvalidation=true; preparationChanges.trySend(Unit); dismissSafely(); scope.cancel(); active=false
        if (connected === this) connected=null
        super.onDestroy()
    }
}
