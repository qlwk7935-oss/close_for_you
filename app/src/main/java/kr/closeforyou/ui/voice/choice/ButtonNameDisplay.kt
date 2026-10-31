package kr.closeforyou.ui.voice.choice

import kr.closeforyou.R
import kr.closeforyou.core.model.ButtonLabelRules
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.PopupAnalysis

/**
 * C: the button name shown in "'닫기' 버튼 눌러 주기" and "'닫기' 버튼, 앞으로 항상 눌러 주기".
 *
 * Whether a button may be offered at all is decided elsewhere ([SharedLabelPolicy] → the shared
 * `ButtonLabelRules`, then B's policy and A's gate). This only picks the words: a short name the
 * label already is ("닫기", "광고 닫기", "skip ad") stays; a longer or indirect one, such as
 * "앱설치 안내 레이어 닫기" or "모바일 웹으로 볼래요" (B's label rules v2-a and v2-b, proposed 10/30),
 * is shown by its role, "닫기" or "건너뛰기". The real name is not lost: [내용 다시 읽기] adds it
 * ([realNames]), and the detailed announcement still reads it.
 *
 * Connection point: core v1 (14 labels) rejects both v2 examples, so today every offered label is
 * short and this changes nothing on screen. When core v2 accepts them, [SharedLabelPolicy] starts
 * offering them and this shortens them, with no change here. Shortening never widens what is offered.
 */
object ButtonNameDisplay {
    /** Longest label kept as written (after the shared normalization): "광고 건너뛰기", "close ad". */
    const val MAX_KEPT_LENGTH = 8

    /** Null = show [label] as it is; otherwise the string resource of the role name. */
    fun roleNameRes(label: String?, role: ButtonRole?): Int? {
        val roleRes = when (role) {
            ButtonRole.CLOSE -> R.string.ui_choice_role_close
            ButtonRole.SKIP -> R.string.ui_choice_role_skip
            else -> return null
        }
        val normalized = label?.let(ButtonLabelRules::normalize).orEmpty()
        if (normalized.isEmpty()) return null
        val keep = normalized.length <= MAX_KEPT_LENGTH && ROLE_WORDS.getValue(role).any { it in normalized }
        return if (keep) null else roleRes
    }

    /**
     * The close/skip labels C shows by role name and the popup's text does not already contain, in
     * candidate order, for [내용 다시 읽기] ("'닫기' 버튼의 원래 이름은 '…'이에요"). Screen text: never log.
     */
    fun realNames(analysis: PopupAnalysis, labelPolicy: PressableLabelPolicy = SharedLabelPolicy): List<RealName> {
        val texts = analysis.texts.map { ButtonLabelRules.normalize(it.text) }
        return analysis.closeCandidates.mapNotNull { c ->
            val label = c.label?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // Only labels a press button could carry; the others never appear shortened.
            if (labelPolicy.roleOf(label) != c.role) return@mapNotNull null
            val res = roleNameRes(label, c.role) ?: return@mapNotNull null
            if (ButtonLabelRules.normalize(label) in texts) null else RealName(res, label)
        }.distinctBy { it.label }
    }

    data class RealName(val roleRes: Int, val label: String) {
        override fun toString(): String = "RealName(roleRes=$roleRes, redacted)"
    }

    /**
     * A label must name its role to be kept: matching words, not speech (lowercased like
     * `ButtonLabelRules.normalize`). "웹으로 볼래요" closes a sheet but does not say so → "닫기".
     */
    private val ROLE_WORDS: Map<ButtonRole, List<String>> = mapOf(
        ButtonRole.CLOSE to listOf("닫기", "close", "dismiss"),
        ButtonRole.SKIP to listOf("건너뛰기", "스킵", "skip"),
    )
}
