package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonLabelRules
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.ui.voice.choice.PressableLabelPolicy

/** Why the engine will not press a button. Codes only, never the label itself. */
enum class SimpleLabelReject {
    NO_LABEL,
    /** A decision, install, coupon-claim or "today" word: never pressed, whatever else the label says. */
    FORBIDDEN,
    NOT_CLOSE,
}

/** Exactly one of [role] (CLOSE or SKIP) and [reject] is set. */
data class SimpleLabelVerdict(val role: ButtonRole?, val reject: SimpleLabelReject?) {
    init {
        require((role == null) != (reject == null))
    }
}

/**
 * Button label rules of the simple engine: the shared contract first (`ButtonLabelRules`, 14 labels),
 * then the demo-mode v2 forms B proposed in WEB_OVERLAY.md 4절, and the engine's own forbidden list
 * over everything. The contract itself is not changed (core is shared; v2 needs team agreement).
 *
 * v2 ([SimpleCloseEngineFeature.demoLabelRulesV2]):
 * - "○○ 닫기": ends with 닫기 and the front holds no forbidden word ("앱설치 안내 레이어 닫기", "배너 닫기").
 *   "설치" inside such a label describes what is closed, so it is allowed only in this form.
 * - "모바일 웹으로 볼래요/볼게요", "웹으로 계속 보기": stays on the web page = closes the install sheet.
 * - "배경을 눌러 닫기": the dim background of an app's own overlay (올리브영).
 *
 * Matching uses the contract's [ButtonLabelRules.normalize] (NFKC, lowercase, one space).
 */
object SimpleLabelRules {
    /** Never pressed. Korean forms match as substrings of the normalized label, English as whole words. */
    private val FORBIDDEN_KO = listOf(
        "구매", "결제", "주문", "동의", "허용", "권한", "설치", "구독", "삭제", "로그인", "가입", "계정",
        "쿠폰 받기", "쿠폰받기", "받기", "다운로드", "자세히", "더보기", "더 보기", "바로가기", "이동",
        "오늘 하루", "오늘하루", "하루", "보지 않기", "보지않기", "다시 보지", "일간", "취소", "저장",
        "체험", "승인", "송금", "이체", "해지", "탈퇴", "개인정보", "열기", "앱에서",
    )
    private val FORBIDDEN_EN = setOf(
        "buy", "purchase", "pay", "payment", "order", "agree", "accept", "allow", "permission", "install",
        "subscribe", "subscription", "delete", "login", "log in", "sign in", "sign up", "account", "get",
        "download", "more", "open", "today", "cancel", "save", "trial", "ok", "okay", "confirm", "continue",
    )

    /** "○○ 닫기": anything, then 닫기, optionally in brackets. */
    private val ENDS_WITH_CLOSE = Regex("""^.+\s*닫기\s*[\]\)>]?$""")
    private val WEB_STAY = listOf("모바일 웹으로 볼래요", "모바일 웹으로 볼게요", "모바일웹으로 볼래요", "모바일웹으로 볼게요",
        "웹으로 볼래요", "웹으로 볼게요", "웹으로 계속 보기", "모바일웹으로 보기", "모바일 웹으로 보기", "웹으로 보기")
    private val DIM_CLOSE = listOf("배경을 눌러 닫기", "배경을 눌러서 닫기", "배경 눌러 닫기")

    private val NON_TOKEN = Regex("[^a-z0-9']+")

    fun roleOf(label: String?, v2: Boolean = SimpleCloseEngineFeature.demoLabelRulesV2): ButtonRole? = judge(label, v2).role

    fun judge(label: String?, v2: Boolean = SimpleCloseEngineFeature.demoLabelRulesV2): SimpleLabelVerdict {
        if (label == null) return reject(SimpleLabelReject.NO_LABEL)
        val normalized = ButtonLabelRules.normalize(label)
        if (normalized.isEmpty()) return reject(SimpleLabelReject.NO_LABEL)
        // A "○○ 닫기" label may describe an install sheet ("앱설치 안내 레이어 닫기"); only that form
        // tolerates the install word, and only when nothing else forbidden is in front.
        val closeTail = v2 && ENDS_WITH_CLOSE.matches(normalized) && normalized !in DIM_CLOSE
        if (isForbidden(normalized, allowInstallWord = closeTail)) return reject(SimpleLabelReject.FORBIDDEN)
        ButtonLabelRules.roleOf(normalized)?.let { return SimpleLabelVerdict(it, null) }
        if (!v2) return reject(SimpleLabelReject.NOT_CLOSE)
        return when {
            closeTail -> SimpleLabelVerdict(ButtonRole.CLOSE, null)
            normalized in WEB_STAY -> SimpleLabelVerdict(ButtonRole.CLOSE, null)
            normalized in DIM_CLOSE -> SimpleLabelVerdict(ButtonRole.CLOSE, null)
            else -> reject(SimpleLabelReject.NOT_CLOSE)
        }
    }

    /** True when the normalized label holds a word the engine never presses. */
    fun isForbidden(normalized: String, allowInstallWord: Boolean = false): Boolean {
        val korean = if (allowInstallWord) FORBIDDEN_KO - "설치" else FORBIDDEN_KO
        if (korean.any { it in normalized }) return true
        val tokens = normalized.split(NON_TOKEN).filter { it.isNotEmpty() }
        return FORBIDDEN_EN.any { phrase ->
            val words = phrase.split(' ')
            (0..tokens.size - words.size).any { start -> words.indices.all { tokens[start + it] == words[it] } }
        }
    }

    private fun reject(reason: SimpleLabelReject) = SimpleLabelVerdict(null, reason)
}

/** C's prompt accepts a press button only when this agrees with the candidate's role. */
object SimpleLabelPolicy : PressableLabelPolicy {
    override fun roleOf(label: String?): ButtonRole? = SimpleLabelRules.roleOf(label)
}
