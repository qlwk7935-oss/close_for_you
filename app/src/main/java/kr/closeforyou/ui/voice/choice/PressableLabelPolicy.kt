package kr.closeforyou.ui.voice.choice

import java.text.Normalizer
import java.util.Locale
import kr.closeforyou.core.model.ButtonRole

/**
 * C: label → pressable role (CLOSE / SKIP / null). The prompt offers a press button only when
 * this role equals the candidate's role, so C never shows a button that A's gate would reject.
 *
 * Shape matches B's shared-label draft v1 (`ButtonLabelRules.roleOf(label)`, 10/19). When that
 * contract function lands in `core`, replace [AGateLabelPolicy] with it. (REPLACE-WITH-CONTRACT)
 * A match is never an authorization: B's ActionPolicy and A's gate re-check everything.
 */
fun interface PressableLabelPolicy {
    /** Null means "not a label C may offer to press for the user". */
    fun roleOf(label: String?): ButtonRole?
}

/**
 * TEMPORARY C copy of A's action gate `labelRole` (`ActionGateValidator`, Draft PR #10,
 * `feature/a-action-gate` 5729ec1): NFKC → lowercase(ROOT) → trim, then exact match on 8 labels.
 * No X/×, no punctuation stripping, no inner-space collapsing — same as A, on purpose.
 * Remove once the shared contract function exists. (REPLACE-WITH-CONTRACT)
 */
object AGateLabelPolicy : PressableLabelPolicy {
    private val closeLabels = setOf("닫기", "창 닫기", "팝업 닫기", "close", "dismiss")
    private val skipLabels = setOf("건너뛰기", "스킵", "skip")

    override fun roleOf(label: String?): ButtonRole? {
        val normalized = label?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
            ?.lowercase(Locale.ROOT)?.trim() ?: return null
        return when (normalized) {
            in closeLabels -> ButtonRole.CLOSE
            in skipLabels -> ButtonRole.SKIP
            else -> null
        }
    }
}
