package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ButtonLabelRules
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.ClickOutcome
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.ui.voice.PressSuccess

/** F59: why the front popup's close is not offered or not pressed. A code only, never screen text. */
enum class FrontPressMiss {
    NO_FRONT, NOT_A_CLOSE, COVER_KIND, UNREAD, LOOK_PARTIAL, COVERED, SCAM, DECISION, UNKNOWN_KIND, RISK_BUTTON, TARGET_CHANGED, LABEL_NOT_PLAIN_CLOSE, LABEL_CHANGED, FAILED_BEFORE,
}

/** F59: what happens after the press. No step presses again (AGENTS 6-6). */
enum class FrontPressStep { CLOSED, NOT_PRESSED, NOT_CLOSED, LEFT_APP }

/** F59: the front popup as read again ([kind] as [OverlapPressRules.kindOf] judges it; [suspicious] by B). */
class FrontSeen(val popup: SimplePopup, val kind: PopupClassification, val suspicious: Boolean)

/** F59: the one press an overlap prompt offers (memory only, for that prompt). [take] answers true once. */
class FrontPress(val front: FrontTarget, val popup: SimplePopup, val candidate: CloseCandidate) {
    private var taken = false

    fun take(): Boolean {
        if (taken) return false
        taken = true
        return true
    }
}

/**
 * F59 (우진 SM-F966N, TalkBack 켬, Chrome LMS 겹침 공지: a move was moved=true, the person's double tap closed it 0 of 3 with our app on,
 * 2 of 3 with TalkBack alone). The overlap prompt may press the close of the popup taken to be in front, once, when the person chose it.
 * Offered only for a plainly named close ([ButtonLabelRules], so no unnamed X, no "오늘 하루") in a popup of a known kind that is not a
 * decision or a scam, with no risky button, by the existing judgements ([FrontPressMiss] says which). The stopped popup's own buttons are
 * never a target. "In front" is still a guess from the DOM order (F57). Pure; JVM tested.
 */
object OverlapPressRules {
    /** The kind as the engine reads an ordinary popup: B's, or the image-dialog and word hints. */
    fun kindOf(popup: SimplePopup, byB: PopupClassification, suspicious: Boolean): PopupClassification {
        if (SimpleCloseEngineFeature.IMAGE_DIALOG_AD_HINT && ImageDialogAdHint.check(popup, byB, suspicious) == null) return PopupClassification.ADVERTISEMENT
        if (!SimpleCloseEngineFeature.WORD_KIND_HINT) return byB
        return WordKindHint.of(byB, suspicious, popup.analysis.texts.map { it.text }, popup.scan.riskCount).kind ?: byB
    }

    /** Null = it may be offered. [failedNames]: normalized names that were pressed on this popup and did not close it. */
    fun miss(front: FrontTarget?, seen: FrontSeen?, failedNames: Set<String> = emptySet()): FrontPressMiss? {
        if (front == null) return FrontPressMiss.NO_FRONT
        if (front.kind != FrontKind.CLOSE) return FrontPressMiss.NOT_A_CLOSE
        if (front.cover.members != null || !PopupStack.applies(front.cover)) return FrontPressMiss.COVER_KIND
        if (seen == null) return FrontPressMiss.UNREAD
        if (seen.suspicious) return FrontPressMiss.SCAM
        if (seen.kind == PopupClassification.USER_DECISION) return FrontPressMiss.DECISION
        if (seen.kind == PopupClassification.UNKNOWN) return FrontPressMiss.UNKNOWN_KIND
        // Every risky button counts here: the promotion exceptions only stop a popup being asked.
        val scan = seen.popup.scan
        if (scan.riskCount > 0 || scan.riskButtons.isNotEmpty()) return FrontPressMiss.RISK_BUTTON
        val pick = StrongCloseRules.pick(scan.candidates) ?: return FrontPressMiss.TARGET_CHANGED
        if (pick.nodePath != front.path || pick.bounds != front.bounds) return FrontPressMiss.TARGET_CHANGED
        if (ButtonLabelRules.roleOf(pick.label) != ButtonRole.CLOSE) return FrontPressMiss.LABEL_NOT_PLAIN_CLOSE
        if (pick.label?.let(ButtonLabelRules::normalize) in failedNames) return FrontPressMiss.FAILED_BEFORE
        return null
    }

    /**
     * What the look around the front popup says about [button]. A cap stopped the look: what lies over the button is not known, so no
     * press (the ordinary popup's press, which also goes on after a cap, is a separate rule, 8-46). The look runs from the end of the
     * page and goes on past the front popup, so a long page is partial even when everything after the popup was read.
     */
    fun lookMiss(button: ScreenBounds, cover: PopupRoot, look: StackLook): FrontPressMiss? = when {
        look.partial -> FrontPressMiss.LOOK_PARTIAL
        PopupStack.overFront(button, cover, look) != null -> FrontPressMiss.COVERED
        else -> null
    }

    /** The press to offer, when [miss] is null. */
    fun offer(front: FrontTarget, seen: FrontSeen): FrontPress? =
        StrongCloseRules.pick(seen.popup.scan.candidates)?.let { FrontPress(front, seen.popup, it) }

    /** Right before the click (AGENTS 6-5, 6-7): the same checks on a new read, and the same name as the prompt said. */
    fun recheck(press: FrontPress, seen: FrontSeen?): FrontPressMiss? {
        miss(press.front, seen)?.let { return it }
        return if (StrongCloseRules.pick(seen!!.popup.scan.candidates)?.label != press.candidate.label) FrontPressMiss.LABEL_CHANGED else null
    }

    fun after(result: ActionResult): FrontPressStep = when {
        PressSuccess.isConfirmed(result) -> FrontPressStep.CLOSED
        !result.attempted -> FrontPressStep.NOT_PRESSED
        result.outcome == ClickOutcome.LEFT_APP -> FrontPressStep.LEFT_APP
        else -> FrontPressStep.NOT_CLOSED
    }

    fun offeredLine(press: FrontPress): String = "OVERLAP_PRESS_OFFERED cover=${box(press.front.cover.bounds)} to=${box(press.candidate.bounds)}"

    fun notOfferedLine(miss: FrontPressMiss): String = "OVERLAP_PRESS_NOT_OFFERED reason=${miss.name}"

    /** By tap only: voice stops at [kr.closeforyou.ui.voice.command.VoicePolicy]. */
    fun chosenLine(press: FrontPress): String = "OVERLAP_PRESS_CHOSEN by=TAP to=${box(press.candidate.bounds)}"

    const val VOICE_REFUSED_LINE = "OVERLAP_PRESS_VOICE_REFUSED"

    /** [reason]: a [FrontPressMiss] or a re-check code. */
    fun recheckFailedLine(reason: String, to: ScreenBounds): String = "OVERLAP_PRESS_RECHECK_FAILED reason=$reason to=${box(to)}"

    fun resultLine(step: FrontPressStep): String = "OVERLAP_PRESS_RESULT code=${step.name}"

    private fun box(b: ScreenBounds) = "${b.left},${b.top},${b.right},${b.bottom}"
}
