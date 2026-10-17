package kr.closeforyou.accessibility

import kr.closeforyou.accessibility.capture.EvidenceIssue
import kr.closeforyou.core.model.PrivacyBlockReason
import kr.closeforyou.core.model.WindowType

/** A-internal, opt-in, ephemeral diagnostics. No nodes, text, bounds, digests or image references. */
internal enum class CaptureStage {
    COLLECTION, POPUP_DETECTED, F0_A, F0_A_PASSED, REGION, CAPTURE_REQUEST, CAPTURE_API,
    CAPTURE_RETURNED, OCR, OCR_RETURNED, F0_B, F0_B_PASSED, PUBLISHED, CANCELLED,
}
internal enum class CaptureDetail {
    NONE, ROOT_UNAVAILABLE, ROOT_PACKAGE_UNAVAILABLE, PACKAGE_GATE, WINDOW_LIST_UNAVAILABLE,
    TARGET_MISSING, TARGET_NOT_APPLICATION, NON_APPLICATION_FOCUS, FOREIGN_APPLICATION,
    SECONDARY_DISPLAY, TREE_PROTECTION, COLLECTION_FAILED, NO_POPUP_SIGNAL,
    SCREEN_CHANGED, MAGNIFIED, REGION_REVALIDATION, BUFFER_GEOMETRY, FRAME_GEOMETRY,
    REGION_TRUNCATED, REGION_TARGET_MISSING, REGION_TARGET_NOT_APPLICATION, REGION_TARGET_INACTIVE,
    REGION_NO_BOUNDED_DIALOG, REGION_MULTIPLE_DIALOGS, REGION_OUTSIDE, REGION_OCCLUDED,
    REGION_WINDOW_BOUNDED, REGION_DIALOG_BOUNDED, PIPELINE_FAILED,
}
internal enum class CaptureApi { NONE, WINDOW, DISPLAY }
internal enum class RegionGeometry { UNKNOWN, BOUNDED, LARGE_OR_FULL, TOO_SMALL, OUTSIDE, EMPTY }
internal enum class EventWindowRelation { SAME, DIFFERENT, UNKNOWN }
internal data class CaptureAttemptDiagnostic(
    val attempt: Int,
    val stage: CaptureStage = CaptureStage.COLLECTION,
    val reached: Set<CaptureStage> = setOf(CaptureStage.COLLECTION),
    val block: PrivacyBlockReason? = null,
    val evidence: EvidenceIssue? = null,
    val detail: CaptureDetail = CaptureDetail.NONE,
    val targetType: WindowType? = null,
    val relatedType: WindowType? = null,
    val geometry: RegionGeometry = RegionGeometry.UNKNOWN,
    val api: CaptureApi = CaptureApi.NONE,
    val eventWindow: EventWindowRelation = EventWindowRelation.UNKNOWN,
)

/** One observer, installed only by the signature-gated debug probe. Release installs none.
 * A new host-root attempt resets BOTH block/evidence. Late/cancelled attempts cannot overwrite it. */
internal class CaptureDiagnosticSession(private val targetPackage: String) {
    private var generation = 0
    private var closed = false
    private var latest: CaptureAttemptDiagnostic? = null
    private var ignored = 0
    private var epoch = -1L
    private var windowId = -1
    @Synchronized fun excludedEvent(packageName: String?) {
        if (!closed && packageName != targetPackage) ignored = (ignored + 1).coerceAtMost(999)
    }
    @Synchronized fun ignoredEvents(): Int = ignored
    @Synchronized fun snapshot(): CaptureAttemptDiagnostic? = latest
    @Synchronized fun close() { closed = true; latest = null }
    @Synchronized fun matches(screenVersion: Long, rootWindowId: Int): Boolean =
        !closed && epoch == screenVersion && windowId == rootWindowId &&
            latest?.evidence == EvidenceIssue.NONE && latest?.api != CaptureApi.NONE &&
            latest?.reached?.containsAll(setOf(CaptureStage.CAPTURE_API, CaptureStage.F0_B_PASSED)) == true
    @Synchronized fun completed(screenVersion: Long, rootWindowId: Int): CaptureAttemptDiagnostic? =
        if (matches(screenVersion, rootWindowId)) latest else null
    @Synchronized fun bind(packageName: String, eventWindowId: Int, rootWindowId: Int, screenVersion: Long): Int? {
        if (closed || packageName != targetPackage) return null
        epoch = screenVersion
        windowId = rootWindowId
        generation++
        latest = CaptureAttemptDiagnostic(generation, eventWindow = when {
            eventWindowId < 0 -> EventWindowRelation.UNKNOWN
            eventWindowId == rootWindowId -> EventWindowRelation.SAME
            else -> EventWindowRelation.DIFFERENT
        })
        return generation
    }
    @Synchronized fun update(generation: Int, change: (CaptureAttemptDiagnostic) -> CaptureAttemptDiagnostic) {
        if (!closed && latest?.attempt == generation) latest = change(requireNotNull(latest))
    }
}

internal class CaptureTrace(
    private val session: CaptureDiagnosticSession?,
    private val eventWindowId: Int = -1,
    private val epoch: Long = -1,
) {
    @Volatile private var ended = false
    private var bound = false
    private var generation: Int? = null
    fun root(packageName: String, windowId: Int) {
        if (!bound) { bound = true; generation = session?.bind(packageName, eventWindowId, windowId, epoch) }
    }
    private fun update(change: (CaptureAttemptDiagnostic) -> CaptureAttemptDiagnostic) {
        if (!ended) generation?.let { session?.update(it, change) }
    }
    fun stage(stage: CaptureStage) {
        update { it.copy(stage = stage, reached = it.reached + stage) }
        if (stage == CaptureStage.CANCELLED) ended = true
    }
    fun geometry(geometry: RegionGeometry) = update { it.copy(geometry = geometry) }
    fun api(api: CaptureApi) = update { it.copy(api = api) }
    fun detail(detail: CaptureDetail, relatedType: WindowType? = null) = update {
        it.copy(detail = detail, relatedType = relatedType)
    }
    fun target(type: WindowType) = update { it.copy(targetType = type) }
    fun block(reason: PrivacyBlockReason) = update { it.copy(block = reason) }
    fun evidence(issue: EvidenceIssue) = update { it.copy(evidence = issue) }
}

internal object CaptureDiagnostics {
    @Volatile var observer: CaptureDiagnosticSession? = null
    fun trace(eventWindowId: Int = -1, epoch: Long = -1) = CaptureTrace(observer, eventWindowId, epoch)
    fun excludedEvent(packageName: String?) { observer?.excludedEvent(packageName) }
}
