package kr.closeforyou.ui.voice.choice

import kr.closeforyou.core.model.ButtonLabelRules
import kr.closeforyou.core.model.ButtonRole

/**
 * C: label → pressable role (CLOSE / SKIP / null). The prompt offers a press button only when
 * this role equals the candidate's role (`roleOf(label) == candidate.role`).
 * A match is never an authorization: B's ActionPolicy and A's gate re-check everything.
 */
fun interface PressableLabelPolicy {
    /** Null means "not a label C may offer to press for the user". */
    fun roleOf(label: String?): ButtonRole?
}

/**
 * The shared label contract (`core/model/ButtonLabelRules`, PR #14, allowlist v1 = 14 labels).
 * C only receives [kr.closeforyou.core.model.CloseCandidate.label] (one string, not the node's
 * text and description), so it uses [ButtonLabelRules.roleOf], not `judgeNode`.
 * The contract's [ButtonRole.CLOSE] / [ButtonRole.SKIP] are the same values as B's candidate role.
 */
object SharedLabelPolicy : PressableLabelPolicy {
    override fun roleOf(label: String?): ButtonRole? = ButtonLabelRules.roleOf(label)
}
