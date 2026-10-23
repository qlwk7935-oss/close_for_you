package kr.closeforyou.ui.voice.choice

import kr.closeforyou.decision.VideoAdReport

/**
 * F5 video ad wording in the prompt. Off: not in A's first integration (videoSurfaceObserved is
 * still being wired) and not yet checked with TalkBack. While off, prompts are exactly as before.
 */
object VideoAdAnnouncementFeature {
    val enabled: Boolean get() = false
}

/**
 * C's input from B's F5 facts ([VideoAdReport] from `DecisionVideoAd.inspect`), passed in by A's
 * assembly. No screen text. Not an authorization: press buttons still come only from C's usual
 * rule (B's role = label role, E1/E2, never USER_DECISION), then B's policy and A's gate.
 *
 * @property countdownSeconds Only the value B read on screen; null when none was read or readings
 *   disagreed. C never guesses, counts down or recomputes it.
 * @property skipCandidateIds B's real skip buttons (E1/E2, whole label says skip).
 */
data class VideoAdInfo(
    val countdownSeconds: Int?,
    val skipCandidateIds: List<String>,
) {
    companion object {
        /** Null unless B says video ad (video surface + advertisement evidence). */
        fun from(report: VideoAdReport): VideoAdInfo? =
            if (report.isVideoAd) VideoAdInfo(report.countdownSeconds, report.skipCandidateIds) else null
    }
}

/**
 * What the prompt says about a video ad. [countdownSeconds] is kept only while no skip button can
 * be pressed for the person: then C says to wait and offers no press. C never waits and presses by
 * itself when the countdown ends; a new screen means a new popup event and a new prompt.
 */
data class VideoAdNotice(val countdownSeconds: Int?)
