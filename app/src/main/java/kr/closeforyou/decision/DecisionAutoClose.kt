package kr.closeforyou.decision

import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.AnalysisLimit
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.ClickOutcome
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.PopupDetection
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.SnapshotToken
import kotlinx.collections.immutable.toPersistentList

/** Why a first-seen popup is not closed automatically. A kind code only: it never carries screen text. */
internal enum class AutoCloseBlock {
    /** The person's preset closes nothing. */
    PRESET_OFF,
    /** Suspicious, decision or unknown: always told and asked, in every preset. */
    KIND_NEVER_CLOSED,
    /** A kind the preset announces instead of closing (e.g. a coupon under preset ③). */
    KIND_NOT_IN_PRESET,
    /** The kind rests on OCR text; B closes only on accessibility-tree text. */
    NOT_TREE_EVIDENCE,
    /** The person chose, or a remembered rule matched: [DecisionActionPolicy] decides instead. */
    OTHER_PATH,
    STALE_SCREEN,
    USER_INTERACTION,
    /** The result could not be told, so nothing is closed silently. */
    FEEDBACK_UNAVAILABLE,
    /**
     * A coupon or ad with no verified close button yet (delayed close), or an ad whose skip countdown
     * is still running: A observes again, and C does not prompt on every new screen meanwhile.
     */
    WAITING_FOR_TARGET,
    /** A notice with no verified close button (often only "확인"): told, not closed. */
    NO_TARGET,
    /** More than one verified close button, or no close and more than one skip: never pick one. */
    AMBIGUOUS_TARGET,
    /** Only a skip button, and the popup is not an ad (a skip elsewhere may move on, not close). */
    SKIP_NOT_READY,
    /** This popup was already sent to A and its result has not come back. */
    AWAITING_RESULT,
    /**
     * A press on this popup failed, its outcome is unknown, no result came back in time, or it
     * left the app: no second try this session.
     */
    FAILED_BEFORE,
    /** A press in this app left the app: automatic closing stops for this app this session. */
    APP_PAUSED,
    /** Too many automatic closes in this app in a short time (an ad opening ads). */
    RATE_LIMIT,
    /** This popup was already closed automatically once this session: asked from now on. */
    CLOSED_BEFORE,
    /** The analysis is mock data (AnalysisLimit.MOCK_ONLY). */
    MOCK_DATA,
    /** A gave no complete, current AutoEligibility for this screen (contract v2). */
    NOT_AUTO_ELIGIBLE,
}

/** Exactly one of an approved [candidateId] and a non-empty [blocks] (AUTO_CLOSE.md 4절). */
internal data class AutoCloseVerdict(val kind: DecisionKind, val candidateId: String?, val blocks: Set<AutoCloseBlock>) {
    init { require((candidateId != null && blocks.isEmpty()) || (candidateId == null && blocks.isNotEmpty())) }
    val approved: Boolean get() = candidateId != null
}

/**
 * B: closes a first-seen coupon, ad or notice without asking, only when the person's preset allows
 * that kind and every condition in AUTO_CLOSE.md 4절 holds. Deny-first like [DecisionActionPolicy]:
 * any block wins, and approval names exactly one verified E1/E2 close (or skip) button. The result
 * is still only a request: A's gate rereads the screen and presses at most once.
 *
 * Keeps, in memory only and per service session, which popups were sent, failed or left the app,
 * keyed by package and A's structure digest (no screen text). Call [onResult] with A's result.
 * A popup sharing another's layout shares its key, so one failure also stops the other: the
 * cautious side.
 */
internal class DecisionAutoClose(
    /**
     * Must be the clock A stamps AutoEligibility with (SystemClock.uptimeMillis): the wiring passes
     * it. The default is the JVM monotonic clock, for tests (same base as uptime on Android: device check pending).
     */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxPerApp: Int = 3,
    private val windowMillis: Long = 60_000,
    /** An approval with no result this long may have pressed: treated as an unknown outcome. */
    private val resultTimeoutMillis: Long = 30_000,
    private val remembered: Int = 256,
) {
    private val pending = LinkedHashMap<String, Long>()
    private val closed = LinkedHashSet<String>()
    private val failed = LinkedHashSet<String>()
    private val pausedApps = LinkedHashSet<String>()
    // Per app: (popup key, approval time). An approval A never pressed is taken back in [onResult].
    private val approvedAt = HashMap<String, ArrayDeque<Pair<String, Long>>>()

    /**
     * The entry point: scopes [analysis]'s texts to the popup first ([DecisionPopupScope]), so page
     * text behind a same-window popup can never decide its kind. All three must share one token.
     */
    fun evaluate(
        snapshot: PopupSnapshot,
        detection: PopupDetection,
        analysis: PopupAnalysis,
        context: DecisionContext,
        allowed: Set<DecisionKind>,
    ): AutoCloseVerdict {
        if (snapshot.token != analysis.token || detection.token != analysis.token) {
            return AutoCloseVerdict(DecisionKinds.of(analysis), null, setOf(AutoCloseBlock.STALE_SCREEN))
        }
        val scoped = analysis.copy(texts = DecisionPopupScope.texts(snapshot, detection, analysis.texts).toPersistentList())
        return evaluateScoped(scoped, context, allowed)
    }

    /** [evaluate] for an analysis whose texts are already the popup's own (a new window, or tests). */
    @Synchronized
    fun evaluateScoped(analysis: PopupAnalysis, context: DecisionContext, allowed: Set<DecisionKind>): AutoCloseVerdict {
        val kind = DecisionKinds.of(analysis)
        val blocks = linkedSetOf<AutoCloseBlock>()
        val closable = allowed.intersect(CLOSABLE)

        // 1. What the popup is, and whether this preset closes it.
        when {
            closable.isEmpty() -> blocks += AutoCloseBlock.PRESET_OFF
            kind !in CLOSABLE || analysis.classification == PopupClassification.USER_DECISION ->
                blocks += AutoCloseBlock.KIND_NEVER_CLOSED
            kind !in closable -> blocks += AutoCloseBlock.KIND_NOT_IN_PRESET
            !DecisionKinds.isFromTree(analysis, kind) -> blocks += AutoCloseBlock.NOT_TREE_EVIDENCE
        }

        // 2. The screen and the person, as in the policy.
        if (context.userChoice != null || context.matchingRule != null) blocks += AutoCloseBlock.OTHER_PATH
        if (context.currentToken != analysis.token) blocks += AutoCloseBlock.STALE_SCREEN
        if (context.userInteracting) blocks += AutoCloseBlock.USER_INTERACTION
        if (!context.announcementAvailable) blocks += AutoCloseBlock.FEEDBACK_UNAVAILABLE
        // Mock data never moves a real screen.
        if (AnalysisLimit.MOCK_ONLY in analysis.limitations) blocks += AutoCloseBlock.MOCK_DATA
        // A's evidence that this exact screen is eligible: every check, this token, within 5 s.
        val now = nowMillis()
        if (analysis.autoEligibility?.validFor(analysis.token, now) != true) blocks += AutoCloseBlock.NOT_AUTO_ELIGIBLE

        // 3. Exactly one button to press. Only for a kind this preset closes, so that "waiting for a
        // close button" can never stand in for asking about a decision or an unknown popup.
        val kindBlocked = blocks.any { it in KIND_BLOCKS }
        val target = if (kindBlocked) null else target(analysis, kind, blocks)

        // 4. This session's history for this popup and app.
        val key = key(analysis.token)
        val app = analysis.token.packageName
        pending[key]?.let { sentAt -> if (now - sentAt >= resultTimeoutMillis) { pending.remove(key); remember(failed, key) } }
        if (app in pausedApps) blocks += AutoCloseBlock.APP_PAUSED
        if (key in failed) blocks += AutoCloseBlock.FAILED_BEFORE
        if (key in closed) blocks += AutoCloseBlock.CLOSED_BEFORE
        if (key in pending) blocks += AutoCloseBlock.AWAITING_RESULT
        val recent = approvedAt.getOrPut(app) { ArrayDeque() }.apply { while (isNotEmpty() && now - first().second >= windowMillis) removeFirst() }
        if (recent.size >= maxPerApp) blocks += AutoCloseBlock.RATE_LIMIT

        if (blocks.isNotEmpty() || target == null) {
            if (blocks.isEmpty()) blocks += AutoCloseBlock.AMBIGUOUS_TARGET // defensive: never approve without a target
            return AutoCloseVerdict(kind, null, blocks)
        }
        pending[key] = now
        trim(pending)
        recent.addLast(key to now)
        return AutoCloseVerdict(kind, target, emptySet())
    }

    /**
     * A's result for any press, read from its observed [ActionResult.outcome] (contract v2):
     * - LEFT_APP (a fake close): that popup and the whole app stop for this session, even when the
     *   result comes after the timeout or the press was the person's own choice.
     * - For a popup B approved: no press (not executed, blocked, stale) frees it for a later screen.
     *   A success is the team rule (10/25): attempted + MECHANICAL_SUCCESS + CLOSED_CONFIRMED; it marks
     *   the popup closed once (asked if it shows again: repeating is the person's F12 choice).
     *   Anything else after a press is a failure, never retried: STILL_PRESENT, UNKNOWN, no
     *   observation, and a popup that vanished although the press failed or its outcome is unknown.
     */
    @Synchronized
    fun onResult(result: ActionResult) {
        val key = key(result.token)
        if (result.outcome == ClickOutcome.LEFT_APP) {
            remember(pausedApps, result.token.packageName)
            remember(failed, key)
        }
        if (pending.remove(key) == null) return
        val pressed = result.attempted || result.status == ActionStatus.MECHANICAL_SUCCESS ||
            result.status == ActionStatus.ATTEMPT_FAILED || result.status == ActionStatus.OUTCOME_UNKNOWN
        if (!pressed) {
            // Only presses count toward the per-app limit (A's gate does not press at all today).
            approvedAt[result.token.packageName]?.let { list -> list.lastOrNull { it.first == key }?.let(list::remove) }
            return
        }
        when {
            result.outcome == ClickOutcome.LEFT_APP -> Unit // already stopped above
            isConfirmedClose(result) -> remember(closed, key)
            else -> remember(failed, key)
        }
    }

    /** The team's single success rule (A #42, C PressSuccess, 10/25): all three, never one alone. */
    private fun isConfirmedClose(result: ActionResult): Boolean =
        result.attempted && result.status == ActionStatus.MECHANICAL_SUCCESS && result.outcome == ClickOutcome.CLOSED_CONFIRMED

    private fun target(analysis: PopupAnalysis, kind: DecisionKind, blocks: MutableSet<AutoCloseBlock>): String? {
        val verified = analysis.closeCandidates.filter { it.isVerifiedTarget() }
        val closes = verified.filter { it.role == ButtonRole.CLOSE }
        val skips = verified.filter { it.role == ButtonRole.SKIP }
        return when {
            // A close and a skip together: close, because a skip may open the next ad (P17).
            closes.size == 1 -> closes.single().id
            closes.size > 1 -> null.also { blocks += AutoCloseBlock.AMBIGUOUS_TARGET }
            skips.size > 1 -> null.also { blocks += AutoCloseBlock.AMBIGUOUS_TARGET }
            skips.size == 1 -> when {
                kind != DecisionKind.ADVERTISEMENT -> null.also { blocks += AutoCloseBlock.SKIP_NOT_READY }
                // A skip countdown still running: wait for a later screen instead of asking every tick.
                DecisionVideoAd.inspect(analysis).countdownSeconds != null -> null.also { blocks += AutoCloseBlock.WAITING_FOR_TARGET }
                else -> skips.single().id
            }
            kind == DecisionKind.NOTICE -> null.also { blocks += AutoCloseBlock.NO_TARGET }
            else -> null.also { blocks += AutoCloseBlock.WAITING_FOR_TARGET }
        }
    }

    private fun key(token: SnapshotToken) = token.packageName + "\u0000" + token.structureDigest

    private fun trim(map: LinkedHashMap<String, Long>) {
        while (map.size > remembered) map.remove(map.keys.first())
    }

    private fun remember(set: LinkedHashSet<String>, value: String) {
        set += value
        while (set.size > remembered) set.remove(set.first())
    }

    companion object {
        val CLOSABLE: Set<DecisionKind> = setOf(DecisionKind.COUPON, DecisionKind.ADVERTISEMENT, DecisionKind.NOTICE)
        private val KIND_BLOCKS = setOf(
            AutoCloseBlock.PRESET_OFF, AutoCloseBlock.KIND_NEVER_CLOSED, AutoCloseBlock.KIND_NOT_IN_PRESET, AutoCloseBlock.NOT_TREE_EVIDENCE,
        )

        /** Preset ① all, ② ads and coupons, ③ ads only (재호's draft); decisions and unknowns never. */
        val PRESET_ALL = CLOSABLE
        val PRESET_ADS_AND_COUPONS: Set<DecisionKind> = setOf(DecisionKind.COUPON, DecisionKind.ADVERTISEMENT)
        val PRESET_ADS_ONLY: Set<DecisionKind> = setOf(DecisionKind.ADVERTISEMENT)
    }
}
