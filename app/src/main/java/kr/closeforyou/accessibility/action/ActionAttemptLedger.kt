package kr.closeforyou.accessibility.action

/**
 * Bounded single-episode simulation ledger, not an Android attempt history implementation.
 * No UUID/digest/token API can create a new episode. Unknown identity fails closed.
 * Keep the ledger for its entire owner lifetime; there is no reset, eviction or retry API.
 */
internal class ActionAttemptLedger {
    enum class ReviewState { AVAILABLE, RESERVED, COMPLETED, FAILED, CANCELLED }
    private val ownedEpisode = PopupEpisode()
    private var continuityLost = false
    private var reviewState = ReviewState.AVAILABLE
    private var activeReservation: Reservation? = null

    /** Synthetic fixtures only. This is not evidence of real popup identity or appearance. */
    fun syntheticEpisode(): PopupEpisode = ownedEpisode

    class Reservation internal constructor() {
        override fun toString(): String = "ReviewReservation(redacted)"
    }
    data class ReservationResult(val reservation: Reservation?, val denial: GateDenial?)

    @Synchronized fun reserveReview(episode: PopupEpisode, continuity: GateCheck): ReservationResult {
        val denial = when {
            episode !== ownedEpisode || continuityLost || continuity != GateCheck.PASSED -> GateDenial.POPUP_IDENTITY_UNPROVEN
            reviewState == ReviewState.RESERVED -> GateDenial.REVIEW_ALREADY_RESERVED
            reviewState != ReviewState.AVAILABLE -> GateDenial.REVIEW_ALREADY_FINISHED
            else -> null
        }
        if (denial != null) return ReservationResult(null, denial)
        val reservation = Reservation()
        activeReservation = reservation
        reviewState = ReviewState.RESERVED
        return ReservationResult(reservation, null)
    }

    @Synchronized fun finishReview(reservation: Reservation, terminal: ReviewState) {
        require(terminal in setOf(ReviewState.FAILED, ReviewState.CANCELLED))
        if (activeReservation !== reservation) return
        activeReservation = null
        reviewState = terminal
    }

    /** Atomically recheck continuity after suspension; completion still records no action. */
    @Synchronized fun completeReview(reservation: Reservation): GateDenial? {
        if (continuityLost) return GateDenial.POPUP_IDENTITY_UNPROVEN
        if (activeReservation !== reservation || reviewState != ReviewState.RESERVED)
            return GateDenial.REVIEW_ALREADY_FINISHED
        activeReservation = null
        reviewState = ReviewState.COMPLETED
        return null
    }

    @Synchronized fun invalidateContinuity() { continuityLost = true }
    @Synchronized fun state(): ReviewState = reviewState
    // Reviews, reservations, failures and cancellation never become actual Android attempts.
    val attempted: Boolean get() = false
}
