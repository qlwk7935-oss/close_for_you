package kr.closeforyou.accessibility.testing

import kr.closeforyou.accessibility.NativeNodeActions
import kr.closeforyou.accessibility.action.F9ReadDetail
import kr.closeforyou.core.model.*

internal enum class ComparisonReader { COLLECTOR_FRESH, F9_ACTIVE, F9_WINDOW }
internal enum class ComparisonCondition { WITHOUT_C, WITH_C }
internal enum class ComparisonPhase { INITIAL, BEFORE_C, AFTER_0, AFTER_150, AFTER_300 }
internal enum class ComparisonOrder { FORWARD, REVERSE }
internal enum class ComparisonValue { SAME, CHANGED, UNKNOWN }
internal enum class ComparisonStatus { COMPLETE, BLOCKED, OWNERSHIP_UNAVAILABLE, CANCELLED, FAILED }

internal enum class ComparisonIssue {
    NONE, UIAUTOMATION_UNAVAILABLE, SERVICE_UNAVAILABLE, SETUP_REQUIRED, EVENT_UNCONFIRMED,
    TARGET_CHANGED, FRAME_CHANGED, TARGET_UNCONFIRMED, CONFIGURATION_CHANGED,
    SERVICE_DISCONNECTED, INVALIDATED, STALE_CALLBACK, NATIVE_C_UNCONFIRMED,
    C_LOST, BASELINE_UNAVAILABLE, EVIDENCE_UNAVAILABLE, POLICY_BLOCKED,
    C_NOT_SHOWN, READ_UNAVAILABLE, TIME_LIMIT, CANCELLED, CLEANUP_FAILED, UNEXPECTED_FAILURE, PREPARATION_NOT_READY
}
internal class ComparisonAbort(val issue: ComparisonIssue) : RuntimeException(issue.name)

/** Test-session anchor, NOT a native-generation witness or execution permit. */
internal class ComparisonRunChecks(private val started: Long) {
    private val receivedWindows = mutableSetOf<Int>()
    private var target: Pair<String,Int>? = null
    private var frame: ScreenBounds? = null
    private var prepared: ReaderSample? = null
    private var deliveredHostEvent = false
    private var lastPreparationTrigger = started
    var issue = ComparisonIssue.NONE; private set
    val receiving get() = deliveredHostEvent
    fun fail(value: ComparisonIssue) { if (issue == ComparisonIssue.NONE) issue = value }
    /** Keep reception semantics unchanged. Only a validated, strictly newer occurrence can
     * wake preparation. Equal-millisecond/out-of-order events conservatively do not trigger
     * another read; timestamps never establish layout readiness or native generation. */
    fun event(fromHost: Boolean, window: Int, occurred: Long, delivered: Long): Boolean {
        if (fromHost && window >= 0 && occurred in started..delivered && receivedWindows.size < 32) {
            receivedWindows += window
            deliveredHostEvent = true
            if (occurred > lastPreparationTrigger) {
                lastPreparationTrigger = occurred
                return true
            }
        }
        return false
    }
    /** Keep only fixed-code completion facts once teardown ends. */
    fun clearObservations() { receivedWindows.clear(); target=null; frame=null; prepared=null }
    fun preparedTarget(value: ReaderSample) {
        val result=sample(value)
        if (result != ComparisonIssue.NONE) throw ComparisonAbort(result)
        prepared=value
    }
    fun targetLifecycle(window: Int, addedOrRemoved: Boolean) {
        if (prepared?.window == window && addedOrRemoved) fail(ComparisonIssue.TARGET_CHANGED)
    }
    fun sample(value: ReaderSample): ComparisonIssue {
        val window = value.windows.singleOrNull { it.id == value.window }
        val current = value.packageName to value.window
        val result = when {
            window?.type != WindowType.APPLICATION -> ComparisonIssue.TARGET_UNCONFIRMED
            target != null && target != current -> ComparisonIssue.TARGET_CHANGED
            frame != null && frame != value.frame -> ComparisonIssue.FRAME_CHANGED
            value.window !in receivedWindows -> ComparisonIssue.EVENT_UNCONFIRMED
            prepared?.let { !preparedTargetMatches(it,value) } == true -> ComparisonIssue.TARGET_CHANGED
            else -> ComparisonIssue.NONE
        }
        if (result == ComparisonIssue.NONE && target == null) { target = current; frame = value.frame }
        fail(result)
        return result
    }
    fun callback(active: Boolean, expectedEpoch: Long, currentEpoch: Long): Boolean {
        if (active && expectedEpoch == currentEpoch) return true
        fail(ComparisonIssue.STALE_CALLBACK)
        return false
    }
}

/** A SHOWN return is not a native attachment, and attachment is not a rendered-frame proof. */
internal enum class ComparisonCState { NOT_CREATED, NATIVE_ATTACHED, UNCONFIRMED, LOST }
internal fun comparisonCState(requested: Boolean, showing: Boolean, live: Boolean,
    previouslyLive: Boolean): ComparisonCState = when {
    !requested -> ComparisonCState.NOT_CREATED
    showing && live -> ComparisonCState.NATIVE_ATTACHED
    previouslyLive || !showing -> ComparisonCState.LOST
    else -> ComparisonCState.UNCONFIRMED
}

internal fun comparisonComplete(problem: ComparisonIssue, released: Boolean, unavailable: Int,
    reception: Boolean, completedPhases: Int): Boolean = problem == ComparisonIssue.NONE &&
    released && unavailable == 0 && reception && completedPhases == ComparisonPhase.entries.size

/** Retained only inside one bounded synthetic test. Never print fields/DTOs or export them. */
internal class ReaderSample(val packageName: String, val window: Int, val frame: ScreenBounds,
    val windows: List<WindowSnapshot>, val nodes: List<NodeSnapshot>, val actionOwner: Any,
    val actions: NativeNodeActions?, val regions: Map<Int, List<ScreenBounds>>?,
    val closePath: String?) {
    override fun toString() = "ReaderSample(redacted)"
}

internal data class ReaderDifference(val binding: ComparisonValue, val frame: ComparisonValue,
    val windows: ComparisonValue, val windowBounds: ComparisonValue, val region: ComparisonValue,
    val root: ComparisonValue, val close: ComparisonValue, val nodes: ComparisonValue,
    val actions: ComparisonValue) {
    fun fixedCodes() = "BINDING=$binding;FRAME=$frame;WINDOW_SET=$windows;WINDOW_BOUNDS=$windowBounds;" +
        "WINDOW_REGION=$region;ROOT_BOUNDS=$root;CLOSE_BOUNDS=$close;NODES=$nodes;NATIVE_ACTIONS=$actions"
}

/** Only same-target, already protected/released reads can be compared. Unknown is never equality.
 * Tokens/digests are not compared across producers, and no descriptor/token is manufactured. */
internal object ReaderComparison {
    fun order(value: ComparisonOrder): List<ComparisonReader> = listOf(
        ComparisonReader.COLLECTOR_FRESH, ComparisonReader.COLLECTOR_FRESH,
        ComparisonReader.F9_ACTIVE, ComparisonReader.F9_ACTIVE,
        ComparisonReader.F9_WINDOW, ComparisonReader.F9_WINDOW,
    ).let { if (value == ComparisonOrder.REVERSE) it.reversed() else it }

    fun compare(a: ReaderSample?, b: ReaderSample?): ReaderDifference {
        val u = ComparisonValue.UNKNOWN
        if (a == null || b == null) return ReaderDifference(u,u,u,u,u,u,u,u,u)
        fun relation(x: Any?, y: Any?): ComparisonValue = when {
            x == null || y == null -> u
            x == y -> ComparisonValue.SAME
            else -> ComparisonValue.CHANGED
        }
        val bound = a.packageName == b.packageName && a.window == b.window
        if (!bound) return ReaderDifference(ComparisonValue.CHANGED,u,u,u,u,u,u,u,u)
        val aw = a.windows.singleOrNull { it.id == a.window }
        val bw = b.windows.singleOrNull { it.id == b.window }
        val paths = a.nodes.map { it.path }
        val paired = paths.isNotEmpty() && paths.toSet().size == paths.size && paths == b.nodes.map { it.path }
        val summariesA = a.nodes.indices.map { a.actions?.observedSummary(a.actionOwner, a.nodes.size, it) }
        val summariesB = b.nodes.indices.map { b.actions?.observedSummary(b.actionOwner, b.nodes.size, it) }
        val actionResult = if (!paired || (summariesA + summariesB).any { it?.hasExactKnownSet != true } ||
            summariesA.indices.any { summariesA[it]?.matchesProjection(a.nodes[it].actions) != true } ||
            summariesB.indices.any { summariesB[it]?.matchesProjection(b.nodes[it].actions) != true }) u
            else relation(summariesA, summariesB)
        val closeA = a.nodes.singleOrNull { it.path == a.closePath }
        val closeB = b.nodes.singleOrNull { it.path == b.closePath }
        // A moved button can be identified only if its non-geometry evidence still matches.
        val sameClose = closeA != null && closeB != null && a.closePath == b.closePath &&
            closeA.copy(bounds = closeB.bounds) == closeB
        return ReaderDifference(ComparisonValue.SAME, relation(a.frame,b.frame), relation(a.windows,b.windows),
            relation(aw?.bounds,bw?.bounds), relation(a.regions?.get(a.window),b.regions?.get(b.window)),
            relation(a.nodes.singleOrNull { it.path == "0" }?.bounds,b.nodes.singleOrNull { it.path == "0" }?.bounds),
            if (sameClose) relation(closeA?.bounds,closeB?.bounds) else u,
            relation(a.nodes,b.nodes), actionResult)
    }
}

/** Expected negative control, not a sample or ownership proof. DISPLAY/WINDOW reads still
 * have to complete; no other rejection becomes a successful comparison. */
internal fun expectedComparisonGuard(condition: ComparisonCondition, phase: ComparisonPhase,
    reader: ComparisonReader, detail: F9ReadDetail?): Boolean =
    condition == ComparisonCondition.WITH_C && phase in setOf(ComparisonPhase.AFTER_0,
        ComparisonPhase.AFTER_150, ComparisonPhase.AFTER_300) &&
        reader == ComparisonReader.F9_ACTIVE && detail == F9ReadDetail.ACCESSIBILITY_OVERLAY_PRESENT
