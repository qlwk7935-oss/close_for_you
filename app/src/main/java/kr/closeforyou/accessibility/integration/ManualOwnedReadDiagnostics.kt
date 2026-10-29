package kr.closeforyou.accessibility.integration

/** One existing owned-window query. No handles, IDs, packages or exception objects retained. */
internal enum class ManualOwnedCount { NOT_CHECKED, UNAVAILABLE, NONE, ONE, MULTIPLE }
internal enum class ManualOwnedQuery { NOT_CHECKED, COMPLETE, EMPTY_WINDOW_LIST, WINDOW_LIMIT, READ_FAILED, RELEASE_FAILED, READ_AND_RELEASE_FAILED }
internal data class ManualOwnedReadCode(val count: ManualOwnedCount = ManualOwnedCount.NOT_CHECKED,
    val query: ManualOwnedQuery = ManualOwnedQuery.NOT_CHECKED,
    val scan: OverlayOwnedScanDetail = OverlayOwnedScanDetail.UNKNOWN,
    val native: OwnershipRelation = OwnershipRelation.NOT_CHECKED)
internal class ManualOwnedReadProbe {
    private var readFailed = false
    private var releaseFailed = false
    private var query = ManualOwnedQuery.NOT_CHECKED
    private var scan = OverlayOwnedScanDetail.NO_ELIGIBLE_OVERLAY
    private var count = ManualOwnedCount.NOT_CHECKED
    fun unavailable(value: ManualOwnedQuery) { query = value }
    fun readFailed() { readFailed = true }
    fun releaseFailed() { releaseFailed = true }
    fun observed(value: OverlayOwnedScanDetail) {
        scan = when {
            scan == OverlayOwnedScanDetail.NO_ELIGIBLE_OVERLAY || scan == value -> value
            else -> OverlayOwnedScanDetail.MIXED
        }
    }
    fun completed(ids: Set<Int>?) {
        count = when (ids?.size) { null -> ManualOwnedCount.UNAVAILABLE; 0 -> ManualOwnedCount.NONE
            1 -> ManualOwnedCount.ONE; else -> ManualOwnedCount.MULTIPLE }
        query = when {
            readFailed && releaseFailed -> ManualOwnedQuery.READ_AND_RELEASE_FAILED
            readFailed -> ManualOwnedQuery.READ_FAILED
            releaseFailed -> ManualOwnedQuery.RELEASE_FAILED
            query != ManualOwnedQuery.NOT_CHECKED -> query
            else -> ManualOwnedQuery.COMPLETE
        }
    }
    fun code() = ManualOwnedReadCode(count, query, scan)
    override fun toString() = "ManualOwnedReadProbe(redacted)"
}

/** Result describes the exact pending barrier BEFORE handling this event, not event origin or
 * removal success. The normal event handler and all native/detach/absence guards still run. */
internal enum class ManualCleanupEventResult { NO_RECEIPT, REMOVAL_RECEIPT, REJECTED }
internal data class ManualCleanupEventCode(val kind: ManualEventKind, val mask: ManualWindowMask,
    val bounds: Boolean, val added: Boolean, val removed: Boolean, val other: Boolean,
    val relation: OwnershipRelation, val transient: Boolean, val returned: Boolean, val observed: Boolean,
    val native: CleanupNativeProgress, val result: ManualCleanupEventResult = ManualCleanupEventResult.NO_RECEIPT)
