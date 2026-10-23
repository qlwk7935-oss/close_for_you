package kr.closeforyou.accessibility.action

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kr.closeforyou.accessibility.PrivacyPolicy
import kr.closeforyou.core.model.*

/** Negative/comparison evidence only. These codes cannot satisfy ActionGateContext or authorize execution. */
internal enum class ProbeDenial {
    EXECUTION_DISABLED, WHOLE_F0_UNPROVEN, POPUP_IDENTITY_UNPROVEN, USER_ORIGIN_UNPROVEN,
    NO_REFERENCE, ALREADY_READ, SESSION_CHANGED, STALE_TOKEN, TTL_EXPIRED, POLICY_BLOCKED,
    INVALID_REFERENCE, OWNER_MISMATCH, TARGET_MISSING, TARGET_CHANGED, PROTECTED_TARGET,
    GEOMETRY_CHANGED, LIMIT, TIME_LIMIT, READ_FAILED, CLEANUP_FAILED,
}
internal class ProbeOutcome(val denials: Set<ProbeDenial>) {
    val productionReady get() = false
    override fun toString() = "ProbeOutcome(codes=${denials.joinToString { it.name }})"
}
internal data class TargetReadReference(val snapshot: PopupSnapshot, val candidate: CloseCandidate,
    val serviceGeneration: Long) {
    override fun toString() = "TargetReadReference(redacted)"
}
internal data class ProbeRuntimeState(val serviceGeneration: Long = -1, val token: SnapshotToken? = null,
    val connected: Boolean = false, val consent: Boolean = false, val paused: Boolean = true,
    val appAllowed: Boolean = false, val userInteracting: Boolean = true) {
    override fun toString() = "ProbeRuntimeState(redacted)"
}
internal class ProbeRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun matches(bounds: ScreenBounds) = right.toLong() > left && bottom.toLong() > top &&
        left == bounds.left && top == bounds.top && right == bounds.right && bottom == bounds.bottom
    override fun toString() = "ProbeRect(redacted)"
}
internal class ProbeWindowFrame(val displayId: Int, val frame: ProbeRect, val window: ProbeRect,
    val activeApplication: Boolean, val occluded: Boolean) {
    override fun toString() = "ProbeWindowFrame(redacted)"
}
internal enum class ProbeText { TEXT, DESCRIPTION, VIEW_ID, CLASS_NAME, ERROR, HINT, PANE, TOOLTIP, STATE }
internal interface ProbeNode : AutoCloseable {
    /** Adapter wrappers can alias the same acquired framework handle. Never release it twice. */
    fun sameHandle(other: ProbeNode): Boolean = this === other
    fun packageName(): String?
    fun windowId(): Int
    fun childCount(): Int
    fun child(index: Int): ProbeNode?
    fun password(): Boolean
    fun editable(): Boolean
    fun inputType(): Int
    fun text(field: ProbeText): CharSequence?
    fun bounds(): ProbeRect
    fun actions(): Set<NodeAction>
    fun clickable(): Boolean
    fun enabled(): Boolean
    fun visible(): Boolean
}
/** A-owned future bridge, never passed ActionRequest. Default Android bridge has no registration. */
internal interface TargetProbeSource {
    fun reference(): TargetReadReference?
    fun state(): ProbeRuntimeState
    fun root(): ProbeNode?
    fun windowFrame(windowId: Int): ProbeWindowFrame
}
internal class ProbeFailure(val code: ProbeDenial) : IllegalStateException(code.name)

/** One owner, one read. Not an episode ledger, F0 receipt, fresh snapshot, or executing gate. */
internal class ReadOnlyTargetProbe(private val source: TargetProbeSource, private val clock: GateClock) {
    private var used = false
    @Synchronized private fun consume(): Boolean = if (used) false else { used = true; true }

    suspend fun inspect(): ProbeOutcome {
        val context = currentCoroutineContext()
        context.ensureActive()
        if (!consume()) return ProbeOutcome(UNPROVEN + ProbeDenial.ALREADY_READ)
        val owned = mutableListOf<ProbeNode>()
        var denial: ProbeDenial? = null
        var cancellation: CancellationException? = null
        var finalCheck: (() -> Unit)? = null
        try {
            val reference = source.reference() ?: throw ProbeFailure(ProbeDenial.NO_REFERENCE)
            val snapshot = reference.snapshot; val token = snapshot.token; val candidate = reference.candidate
            val captured = snapshot.capturedAtUptimeMillis
            val started = clock.nowMillis()
            fun checkpoint() {
                context.ensureActive()
                val state = source.state()
                context.ensureActive()
                if (state.serviceGeneration != reference.serviceGeneration) throw ProbeFailure(ProbeDenial.SESSION_CHANGED)
                if (!state.connected || !state.consent || state.paused || !state.appAllowed || state.userInteracting)
                    throw ProbeFailure(ProbeDenial.POLICY_BLOCKED)
                if (state.token != token) throw ProbeFailure(ProbeDenial.STALE_TOKEN)
                val now = clock.nowMillis(); context.ensureActive()
                if (captured < 0 || now < captured || now - captured >= 5_000) throw ProbeFailure(ProbeDenial.TTL_EXPIRED)
                if (started < 0 || now < started || now - started > 100) throw ProbeFailure(ProbeDenial.TIME_LIMIT)
            }
            finalCheck = ::checkpoint
            if (snapshot.completeness != SnapshotCompleteness.COMPLETE || snapshot.nodes.size !in 1..1_200 ||
                snapshot.windows.size !in 1..32 || token.captureEpoch != token.screenVersion ||
                token.packageName.length !in 1..256 || listOf(token.popupInstanceId, token.structureDigest, token.contentDigest).any { it.length !in 1..128 })
                throw ProbeFailure(ProbeDenial.INVALID_REFERENCE)
            checkpoint()
            val path = candidate.nodePath ?: throw ProbeFailure(ProbeDenial.INVALID_REFERENCE)
            if (path.length > 301) throw ProbeFailure(ProbeDenial.LIMIT)
            val parts = path.split('/')
            if (parts.firstOrNull() != "0" || parts.size > 61 || parts.drop(1).any {
                    it.isEmpty() || it.length > 4 || it.any { c -> c !in '0'..'9' } ||
                        (it.length > 1 && it[0] == '0') || (it.toIntOrNull() ?: 1_200) !in 0..1_199 })
                throw ProbeFailure(ProbeDenial.INVALID_REFERENCE)
            val original = snapshot.nodes.singleOrNull { it.path == path } ?: throw ProbeFailure(ProbeDenial.INVALID_REFERENCE)
            if (listOf(original.text, original.contentDescription, original.viewId, original.className, candidate.label)
                    .any { (it?.length ?: 0) > 4_096 }) throw ProbeFailure(ProbeDenial.LIMIT)
            if (candidate.viewId != original.viewId || candidate.bounds != original.bounds ||
                original.className.isNullOrBlank() ||
                candidate.evidence !in setOf(EvidenceLevel.E1_DISMISS, EvidenceLevel.E2_LABELED_NODE) ||
                ButtonLabelRules.roleOf(candidate.label) != candidate.role || candidate.role !in setOf(ButtonRole.CLOSE, ButtonRole.SKIP))
                throw ProbeFailure(ProbeDenial.INVALID_REFERENCE)
            val window = snapshot.windows.singleOrNull { it.id == token.windowId } ?: throw ProbeFailure(ProbeDenial.INVALID_REFERENCE)
            fun contains(outer: ScreenBounds, inner: ScreenBounds) = outer.right.toLong() > outer.left &&
                outer.bottom.toLong() > outer.top && inner.right.toLong() > inner.left && inner.bottom.toLong() > inner.top &&
                inner.left >= outer.left && inner.top >= outer.top && inner.right <= outer.right && inner.bottom <= outer.bottom
            if (!contains(snapshot.screenBounds, window.bounds) || !contains(window.bounds, original.bounds))
                throw ProbeFailure(ProbeDenial.INVALID_REFERENCE)
            fun own(node: ProbeNode) {
                checkpoint()
                if (node.packageName() != token.packageName || node.windowId() != token.windowId)
                    throw ProbeFailure(ProbeDenial.OWNER_MISMATCH)
                checkpoint()
            }
            var node = source.root()?.also { owned += it } ?: throw ProbeFailure(ProbeDenial.TARGET_MISSING)
            own(node)
            fun checkFrame() {
                val frame = source.windowFrame(token.windowId); checkpoint()
                if (window.type != WindowType.APPLICATION || !window.active || frame.displayId != 0 ||
                    !frame.activeApplication || frame.occluded || !frame.frame.matches(snapshot.screenBounds) ||
                    !frame.window.matches(window.bounds)) throw ProbeFailure(ProbeDenial.GEOMETRY_CHANGED)
            }
            checkFrame()
            for (index in parts.drop(1).map(String::toInt)) {
                val count = node.childCount(); checkpoint()
                if (count !in 0..1_200 || index >= count) throw ProbeFailure(ProbeDenial.TARGET_MISSING)
                node = node.child(index)?.also { child ->
                    if (owned.any { it.sameHandle(child) || child.sameHandle(it) }) throw ProbeFailure(ProbeDenial.TARGET_CHANGED)
                    owned += child
                } ?: throw ProbeFailure(ProbeDenial.TARGET_MISSING)
                own(node)
            }
            // Ownership precedes every target data getter. This is NOT a full-tree protection pass.
            val password = node.password(); checkpoint()
            val editable = node.editable(); checkpoint()
            val inputType = node.inputType(); checkpoint()
            if (PrivacyPolicy.fieldBlock(password, editable, inputType) != null) throw ProbeFailure(ProbeDenial.PROTECTED_TARGET)
            fun frozen(field: ProbeText): String? {
                val raw = node.text(field); checkpoint()
                if (raw == null) return null
                val size = raw.length
                if (size !in 0..4_096) throw ProbeFailure(ProbeDenial.LIMIT)
                val copy = if (raw is String) raw else CharArray(size) { raw[it] }.concatToString()
                if (raw.length != size) throw ProbeFailure(ProbeDenial.TARGET_CHANGED)
                checkpoint()
                if (PrivacyPolicy.hasSensitiveText(copy) || PrivacyPolicy.hasUncertainText(copy)) throw ProbeFailure(ProbeDenial.PROTECTED_TARGET)
                return copy
            }
            val text = frozen(ProbeText.TEXT); val description = frozen(ProbeText.DESCRIPTION)
            val viewId = frozen(ProbeText.VIEW_ID); val className = frozen(ProbeText.CLASS_NAME)
            listOf(ProbeText.ERROR, ProbeText.HINT, ProbeText.PANE, ProbeText.TOOLTIP, ProbeText.STATE).forEach(::frozen)
            val bounds = node.bounds(); checkpoint()
            val actions = node.actions().toSet(); checkpoint()
            val clickable = node.clickable(); checkpoint()
            val enabled = node.enabled(); checkpoint()
            val visible = node.visible(); checkpoint()
            if (text != original.text || description != original.contentDescription || viewId != original.viewId ||
                className != original.className || !bounds.matches(original.bounds) ||
                actions != original.actions || clickable != original.clickable ||
                enabled != original.enabled || !enabled || !visible) throw ProbeFailure(ProbeDenial.TARGET_CHANGED)
            val labels = listOfNotNull(text, description).filter { it.isNotBlank() }
            if (labels.isEmpty() || candidate.label !in labels ||
                ButtonLabelRules.judgeNode(text, description).role != candidate.role)
                throw ProbeFailure(ProbeDenial.TARGET_CHANGED)
            val requiredAction = if (candidate.evidence == EvidenceLevel.E1_DISMISS) NodeAction.DISMISS else NodeAction.CLICK
            if (requiredAction !in original.actions || (requiredAction == NodeAction.CLICK && !original.clickable))
                throw ProbeFailure(ProbeDenial.INVALID_REFERENCE)
            owned.forEach(::own)
            checkFrame()
            checkpoint()
        } catch (cancelled: CancellationException) { cancellation = cancelled }
        catch (failure: ProbeFailure) { denial = failure.code }
        catch (_: Exception) { denial = ProbeDenial.READ_FAILED }
        finally {
            for (node in owned.asReversed()) {
                try { node.close() }
                catch (cancelled: CancellationException) { if (cancellation == null) cancellation = cancelled }
                catch (_: Exception) { if (denial == null) denial = ProbeDenial.CLEANUP_FAILED }
            }
            owned.clear()
        }
        cancellation?.let { throw it }
        context.ensureActive()
        try { finalCheck?.invoke() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ProbeFailure) { if (denial == null) denial = failure.code }
        catch (_: Exception) { if (denial == null) denial = ProbeDenial.READ_FAILED }
        return ProbeOutcome(if (denial == null) UNPROVEN else UNPROVEN + requireNotNull(denial))
    }
    override fun toString() = "ReadOnlyTargetProbe(redacted)"
    companion object {
        val UNPROVEN = setOf(ProbeDenial.EXECUTION_DISABLED, ProbeDenial.WHOLE_F0_UNPROVEN,
            ProbeDenial.POPUP_IDENTITY_UNPROVEN, ProbeDenial.USER_ORIGIN_UNPROVEN)
    }
}
