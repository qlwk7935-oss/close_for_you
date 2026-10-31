package kr.closeforyou.ui.voice

import kr.closeforyou.R
import kr.closeforyou.core.contract.AnnouncementKind
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.contract.PopupAnnouncer
import kr.closeforyou.core.contract.PopupHandlingListener
import kr.closeforyou.core.model.HandlingReport
import kr.closeforyou.core.model.NextStep
import kr.closeforyou.core.model.SafetyReason
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.ui.voice.choice.VideoAdInfo
import kr.closeforyou.ui.voice.choice.textRes

/**
 * C/F10, contract v3 (#45): speaks what happened to a popup handled without asking (a preset or a
 * remembered F12 rule). A builds one per service session and calls [onHandled] for every
 * [HandlingReport]. Mock-tested only, not yet wired to A's real service.
 *
 * **One voice for results:** when A sends HandlingReports here, this listener is the only one that
 * says the result of an automatic press. A's `F9ResultFeedback` (and any other result speech) must
 * not also speak it, or the person hears the same popup twice. The person's own taps keep
 * PopupChoiceFlow's speech; both share [batcher], so quick closes still become one sentence.
 *
 * What it says ([AutoDecisionSpeech] picks the sentence):
 * - EXECUTE: "닫았어요" is counted in [batcher] (about 2 s, "광고 2개, 쿠폰 1개를 닫았어요") only when
 *   [HandlingReport.closedConfirmed] (attempted + MECHANICAL_SUCCESS + CLOSED_CONFIRMED). LEFT_APP is
 *   said at once. Any other attempted press counts as "확인하지 못했어요". Not attempted (NOT_EXECUTED,
 *   BLOCKED, STALE) or no result yet: silent.
 * - WAIT (WAITING_FOR_TARGET): once per popup ([waitKeyOf]); repeats and other waits are silent.
 *   A resets the memory with [resetWaitMemory] on a new popup, a confirmed removal and a service
 *   reconnect, so a new wait is never dropped.
 * - ANNOUNCE: the kind sentence, then [contentOf] (the existing content reading text) when given.
 * - ASK: silent; A opens the usual choice prompt (PopupChoiceFlow).
 * - SUSPICIOUS_CONTENT: the scam warning only, with nothing to press. Other DENY: "직접 확인해 주세요".
 *
 * The person's own choice (DecisionSource.USER_CHOICE) can not reach here: [HandlingReport] rejects
 * it, so PopupChoiceFlow alone speaks those results.
 *
 * The title ([HandlingReport.title], screen text) is spoken only when [HandlingTitleSpeechFeature] is
 * on (off for the first live integration: kinds only, "광고를 닫았어요"). Then it is cut to
 * [AutoDecisionSpeech.TITLE_MAX_CHARS], dropped when it looks like a phone or card number
 * ([spokenTitle]), kept in memory for the batch window only and never logged or stored. No focus is
 * moved.
 */
class SpeechHandlingListener(
    private val announcer: PopupAnnouncer,
    /** The same batcher PopupChoiceFlow uses; build its flush with [batchFlush]. */
    private val batcher: AnnouncementBatcher,
    private val format: UiFormat,
    /**
     * "The same popup" for WAIT-once. The default ([defaultWaitKey]) is a Mock-only stand-in, not
     * the live rule: A passes its own same-popup key here. null = not sure it is the same popup,
     * then the WAIT sentence is said every time (never silently dropped).
     */
    private val waitKeyOf: (HandlingReport) -> String? = ::defaultWaitKey,
    /** ANNOUNCE: the existing content reading text for this screen (A has the analysis), or null. */
    private val contentOf: suspend (HandlingReport) -> String? = { null },
    /** B's F5 facts for this screen; wording only (see [AutoDecisionSpeech.speechFor]). */
    private val videoAdOf: (HandlingReport) -> VideoAdInfo? = { null },
    private val waitSaid: WaitSaidOnce = WaitSaidOnce(),
    /**
     * The one switch for title speech, read on every report. Default off ([HandlingTitleSpeechFeature]);
     * later a C setting is passed here (no settings UI yet).
     */
    private val titleSpeechEnabled: () -> Boolean = { HandlingTitleSpeechFeature.enabled },
) : PopupHandlingListener {

    override suspend fun onHandled(report: HandlingReport) {
        val key = waitKeyOf(report)
        val decision = report.decision
        val alreadySaid = decision.next == NextStep.WAIT &&
            SafetyReason.WAITING_FOR_TARGET in decision.reasons &&
            key != null && !waitSaid.firstTime(key)
        // After an attempted press the popup may be gone: a later WAIT for it may be said again.
        if (report.result?.attempted == true && key != null) waitSaid.forget(key)

        val speech = AutoDecisionSpeech.speechFor(
            decision = decision,
            classification = report.classification,
            result = report.result,
            title = if (titleSpeechEnabled()) spokenTitle(report.title) else null,
            waitAlreadySaid = alreadySaid,
            videoAd = videoAdOf(report),
        )
        when (speech) {
            AutoSpeech.Quiet, AutoSpeech.Ask -> Unit
            is AutoSpeech.Batch -> {
                // The contract's single success rule decides "닫았어요", whatever the speech said.
                val outcome = if (speech.outcome == HandledOutcome.CLOSED && !report.closedConfirmed) {
                    HandledOutcome.NOT_CONFIRMED
                } else {
                    speech.outcome
                }
                batcher.add(outcome, speech.kind, speech.title.takeIf { outcome == HandledOutcome.CLOSED })
            }
            is AutoSpeech.Say -> say(
                tokenFor(report),
                if (decision.next == NextStep.EXECUTE) AnnouncementKind.ACTION_RESULT else AnnouncementKind.POPUP_FOUND,
                AutoDecisionSpeech.render(speech.lines, format),
            )
            is AutoSpeech.Announce -> {
                val kindLine = format.get(speech.summary.textRes())
                val content = contentOf(report)?.let(SpeakableText::of)?.takeIf { it.isNotBlank() }
                if (content == null) {
                    say(report.token, AnnouncementKind.POPUP_FOUND, kindLine)
                } else {
                    say(report.token, AnnouncementKind.CONTENT, "$kindLine $content")
                }
            }
        }
    }

    /**
     * Forgets every WAIT already said, so the next WAIT is spoken. A calls it when it sees a
     * different popup ([WaitResetReason.NEW_POPUP], not on every snapshot of the same one), when it
     * confirms the popup is gone, when the service reconnects, and when it stops. [reason] only
     * documents the call site; every reason forgets everything.
     */
    fun resetWaitMemory(@Suppress("UNUSED_PARAMETER") reason: WaitResetReason) = waitSaid.clear()

    /** The service stopped (the batcher is cancelled by its owner). */
    fun clear() = resetWaitMemory(WaitResetReason.SERVICE_STOPPED)

    private suspend fun say(token: SnapshotToken?, kind: AnnouncementKind, text: String) {
        announcer.announce(AnnouncementRequest(token, kind, text))
    }

    /** After a press the popup's token is no longer current, so results carry none (as A's F9 did). */
    private fun tokenFor(report: HandlingReport): SnapshotToken? =
        if (report.result?.attempted == true) null else report.token

    override fun toString(): String = "SpeechHandlingListener(redacted)"

    companion object {
        /**
         * Mock-only stand-in, not the live same-popup rule: same window and same node structure.
         * Opaque, memory only, never logged. A replaces it through `waitKeyOf`.
         */
        fun defaultWaitKey(report: HandlingReport): String =
            "${report.token.windowId}:${report.token.structureDigest}"

        /** Digits (with spaces, dots or dashes between) this long look like a phone or card number. */
        private val LONG_NUMBER = Regex("""\d(?:[\s.\-]?\d){6,}""")

        /**
         * The title as it may be spoken: [AutoDecisionSpeech.shortTitle], or null when it holds a
         * long number (phone, card, account), so personal numbers are never read out by C.
         */
        fun spokenTitle(raw: String?): String? {
            val short = AutoDecisionSpeech.shortTitle(raw?.let(SpeakableText::inline)) ?: return null
            if (LONG_NUMBER.containsMatchIn(raw.orEmpty())) return null
            return short
        }

        /**
         * The [AnnouncementBatcher] flush for a batcher shared with this listener: kinds when every
         * close in the window has one ("광고 2개, 쿠폰 1개를 닫았어요"), otherwise the old sentence.
         */
        fun batchFlush(
            announcer: PopupAnnouncer,
            format: UiFormat,
            detailed: () -> Boolean = { false },
        ): suspend (BatchSummary) -> Unit = { summary ->
            announcer.announce(
                AnnouncementRequest(null, AnnouncementKind.ACTION_RESULT, AutoDecisionSpeech.batchSentence(summary, detailed(), format)),
            )
        }
    }
}

/**
 * Whether [SpeechHandlingListener] speaks a popup's title ("'제목' 광고를 닫았어요"). Off for the first
 * live integration (Woojin, 10/25): kinds and counts only, and A passes `title = null` anyway. B keeps
 * filling the title; once its privacy check is done, a C setting turns this on through the listener's
 * `titleSpeechEnabled`, the only place it is read. When on, the old rule applies (first 20 characters,
 * nothing with a long number).
 */
object HandlingTitleSpeechFeature {
    val enabled: Boolean get() = false
}

/** Why A called [SpeechHandlingListener.resetWaitMemory]. Same effect for each. */
enum class WaitResetReason {
    /** A judged that a different popup appeared (not a new snapshot of the same one). */
    NEW_POPUP,

    /** A confirmed the popup is gone (closed or replaced). */
    POPUP_REMOVED,

    /** The accessibility service connected again. */
    SERVICE_RECONNECTED,

    /** The service stopped. */
    SERVICE_STOPPED,
}
