package kr.closeforyou.decision

import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.TextSource
import java.text.Normalizer

/**
 * F5 facts about a video popup, kept apart as the design requires (design F5, C07). No screen text.
 *
 * @property videoObserved A saw a video surface. On its own this says nothing about advertising.
 * @property advertisementEvidence The classifier calls the popup an advertisement: tree wording, no
 *   decision signal and no self-doubting wording. OCR wording or a video surface alone is not evidence.
 * @property countdownSeconds Seconds until skipping, only when a countdown was actually read in the
 *   tree or by OCR; null when none was read or readings disagree. Never guessed from timing.
 * @property countdownSource Where the countdown was read.
 * @property skipCandidateIds Tree skip buttons (E1/E2) whose whole label says skip, by B's rule.
 *   Listing one is not an authorization: the policy and A's gate still decide.
 */
data class VideoAdReport(
    val videoObserved: Boolean,
    val advertisementEvidence: Boolean,
    val countdownSeconds: Int?,
    val countdownSource: TextSource?,
    val skipCandidateIds: List<String>,
) {
    /** What C may say: "video ad" only with evidence, and a wait only when a countdown was read. */
    val isVideoAd: Boolean get() = videoObserved && advertisementEvidence
}

/**
 * B: reads F5 signals from an analysis for announcements. It does not classify (the classifier
 * does) and never authorizes a skip. A owns detecting the video surface itself.
 */
object DecisionVideoAd {
    fun inspect(analysis: PopupAnalysis): VideoAdReport {
        val texts = analysis.texts.filter { it.text.isNotBlank() }
        val countdowns = texts.mapNotNull { text -> countdownIn(text.text)?.let { it to text.source } }
        val seconds = countdowns.map { it.first }.distinct()
        val reading = if (seconds.size == 1) countdowns.first() else null
        return VideoAdReport(
            videoObserved = analysis.videoSurfaceObserved,
            advertisementEvidence = CLASSIFIER.displayClassification(analysis) == PopupClassification.ADVERTISEMENT,
            countdownSeconds = reading?.first,
            countdownSource = reading?.let { r -> countdowns.filter { it.first == r.first }.minOf { it.second } },
            skipCandidateIds = analysis.closeCandidates.filter(::isSkipButton).map { it.id },
        )
    }

    /** A countdown in one text, e.g. "5초 후 건너뛰기", "건너뛰기까지 5초", "Skip ad in 5s". */
    internal fun countdownIn(text: String): Int? {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        val match = KOREAN_AFTER.find(normalized) ?: KOREAN_UNTIL.find(normalized)
            ?: ENGLISH_SKIP_IN.find(normalized)?.takeIf { "skip" in normalized }
        return match?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..MAX_SECONDS }
    }

    private fun isSkipButton(candidate: CloseCandidate): Boolean =
        candidate.role == ButtonRole.SKIP &&
            (candidate.evidence == EvidenceLevel.E1_DISMISS || candidate.evidence == EvidenceLevel.E2_LABELED_NODE) &&
            candidate.label?.let(DecisionKeywords::buttonRoleOf) == ButtonRole.SKIP

    private val CLASSIFIER = DecisionPopupClassifier()
    private const val MAX_SECONDS = 300
    private val KOREAN_AFTER = Regex("""(?<!\d)(\d{1,3})\s*초\s*(?:후|뒤)""")
    private val KOREAN_UNTIL = Regex("""까지\s*(\d{1,3})\s*초""")
    private val ENGLISH_SKIP_IN = Regex("""\bin\s+(\d{1,3})\s*(?:s|sec|secs|second|seconds)?\b""")
}
