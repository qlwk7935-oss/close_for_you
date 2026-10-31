package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonLabelRules
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.LabelRejectReason
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
 * v3 ([SimpleCloseEngineFeature.demoLabelRulesV3], F16): whole-label polite dismissals ("오늘 하루 보지 않기",
 * "7일간 보지 않기", "괜찮아요", "다음에", "나중에", "그냥 볼게요", "웹으로 계속", "No thanks", "Not now").
 * Only the exact phrase counts, so nothing else can ride along ("오늘 하루 무료 체험" is still refused).
 * "확인"/"OK" stay ambiguous; "앱에서 열기", "설치", "받기" stay forbidden; a lone "×" is not a name
 * (AGENTS 6-3: it goes to the person as "X 모양 닫기", [CloseButtonFinder]).
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
    private val INSTALL_ACTION = Regex("""(설치|다운로드)\s*(하|해|후|및)""")

    /** F16 v3: whole labels only (normalized). Each one closes the popup and changes nothing else that matters. */
    private val HIDE_FOR_A_WHILE = setOf(
        "오늘 하루 보지 않기", "오늘하루 보지 않기", "오늘 하루 보지않기", "오늘하루 보지않기", "오늘 하루 그만 보기",
        "오늘 그만 보기", "오늘은 그만 보기", "오늘 보지 않기", "오늘은 보지 않기", "오늘 하루 닫기", "오늘하루 닫기",
        "다시 보지 않기", "다시 보지않기", "다시는 보지 않기", "다시 열지 않기", "그만 보기",
    )
    private val DAYS_HIDE = Regex("""^\d{1,2}\s*일\s*(간|동안|간 ?동안)?\s*(보지 ?않기|안 ?보기|그만 ?보기|닫기)$""")
    private val STAY_ON_WEB = setOf(
        "그냥 볼게요", "그냥 볼래요", "웹으로 계속", "웹으로 계속하기", "모바일 웹으로 계속", "모바일웹으로 계속",
        "웹에서 계속 보기", "웹에서 볼게요", "모바일 웹에서 볼게요",
    )
    private val POLITE_NO = setOf(
        "괜찮아요", "괜찮습니다", "아니요, 괜찮아요", "아니요 괜찮아요", "아니요, 괜찮습니다", "아니요 괜찮습니다",
        "아니오, 괜찮습니다", "아니오 괜찮습니다", "다음에", "나중에", "다음에 할게요", "나중에 할게요", "다음에 볼게요",
        "나중에 볼게요", "다음에 하기", "나중에 하기", "no thanks", "no, thanks", "no thank you", "not now", "maybe later",
    )

    private fun v3Close(normalized: String): Boolean =
        normalized in HIDE_FOR_A_WHILE || DAYS_HIDE.matches(normalized) || normalized in STAY_ON_WEB || normalized in POLITE_NO

    fun roleOf(label: String?, v2: Boolean = SimpleCloseEngineFeature.demoLabelRulesV2): ButtonRole? = judge(label, v2).role

    fun judge(label: String?, v2: Boolean = SimpleCloseEngineFeature.demoLabelRulesV2): SimpleLabelVerdict {
        if (label == null) return reject(SimpleLabelReject.NO_LABEL)
        val normalized = ButtonLabelRules.normalize(label)
        if (normalized.isEmpty()) return reject(SimpleLabelReject.NO_LABEL)
        // The exact phrase is the whole label, so the forbidden words in it ("하루", "보지 않기") carry nothing else.
        if (v2 && SimpleCloseEngineFeature.demoLabelRulesV3 && v3Close(normalized)) return SimpleLabelVerdict(ButtonRole.CLOSE, null)
        // A "○○ 닫기" label may describe an install sheet ("앱설치 안내 레이어 닫기"); only that form
        // tolerates the install word, and only when nothing else forbidden is in front.
        val closeTail = v2 && ENDS_WITH_CLOSE.matches(normalized) && normalized !in DIM_CLOSE
        if (isForbidden(normalized, allowInstallWord = closeTail)) return reject(SimpleLabelReject.FORBIDDEN)
        // F21: "설치"/"다운로드" may only name what is closed ("앱설치 안내 레이어 닫기"), never be done ("설치하고 닫기", "설치 후 닫기").
        if (closeTail && INSTALL_ACTION.containsMatchIn(normalized)) return reject(SimpleLabelReject.FORBIDDEN)
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
        // F21: in "○○ 닫기" the install/download word only names what is closed ("다운로드 안내 닫기").
        val korean = if (allowInstallWord) FORBIDDEN_KO - "설치" - "다운로드" else FORBIDDEN_KO
        if (korean.any { it in normalized }) return true
        val tokens = normalized.split(NON_TOKEN).filter { it.isNotEmpty() }
        return FORBIDDEN_EN.any { phrase ->
            val words = phrase.split(' ')
            (0..tokens.size - words.size).any { start -> words.indices.all { tokens[start + it] == words[it] } }
        }
    }

    /**
     * F16/F20: a button with a decision word (결제·구매·주문·동의·허용·권한·구독·삭제·가입·로그인…: the shared
     * contract's decision grade, plus 주문/order). Never pressed; offered to the person as [버튼으로 이동] only.
     */
    fun isDecision(label: String?): Boolean {
        if (label.isNullOrBlank()) return false
        val normalized = ButtonLabelRules.normalize(label)
        if (ButtonLabelRules.judge(normalized).reason == LabelRejectReason.DECISION_WORD) return true
        return "주문" in normalized || normalized.split(NON_TOKEN).any { it == "order" }
    }

    /**
     * F16 "결제·동의·구독·삭제·권한 버튼이 같이 있는 팝업은 묻는다" (재호 11/1), plus 허용·가입·로그인·계정·개인정보·
     * 승인·송금·이체·해지·탈퇴·체험: any one in a popup keeps it asked in every preset. F23 (D's answer key L04~L06,
     * L13: an event popup with a "구매" button is CLOSE_OK): a buy or order button alone does not keep it asked; it is
     * still never pressed ([isDecision]), and B may still call the popup a decision (L03, L17 → asked).
     */
    fun isRisky(label: String?): Boolean {
        if (!isDecision(label)) return false
        val normalized = ButtonLabelRules.normalize(label!!)
        val tokens = normalized.split(NON_TOKEN).filter { it.isNotEmpty() }
        return ASK_KO.any { it in normalized } || ASK_EN.any { phrase ->
            val words = phrase.split(' ')
            (0..tokens.size - words.size).any { start -> words.indices.all { tokens[start + it] == words[it] } }
        }
    }

    private val ASK_KO = listOf("결제", "동의", "구독", "삭제", "권한", "허용", "가입", "로그인", "계정", "개인정보", "승인", "송금", "이체", "해지", "탈퇴", "체험")
    private val ASK_EN = listOf(
        "pay", "payment", "subscribe", "subscription", "consent", "agree", "accept", "permission", "allow", "delete",
        "login", "log in", "sign in", "sign up", "account", "personal information", "personal data", "trial",
    )

    /** F16: a label made of X marks only ("×", "X", "✕"): the shape, not a name (AGENTS 6-3). */
    fun isXMark(label: String?): Boolean =
        !label.isNullOrBlank() && ButtonLabelRules.judge(label).reason == LabelRejectReason.SYMBOL_ONLY

    /**
     * F16 "전부 닫기": how plainly a close label says close, lower is plainer; the same order C's prompt
     * offers (F20), reading order on a tie. F21 (LG 11/1 06:49~53 11번가: the sheet's own "앱설치 안내 레이어
     * 닫기" was pressed and the sheet stayed; "모바일 웹으로 볼래요" closed it): an app-install sheet's own
     * "stay on the web" answer first, then a close of the popup ("닫기", "Close", "○○ 레이어 닫기"), then the
     * dim background or hiding for a while ("오늘 하루 보지 않기"), then a polite no ("괜찮아요").
     */
    fun clarity(label: String?): Int {
        val normalized = label?.let(ButtonLabelRules::normalize).orEmpty()
        return when {
            normalized in WEB_STAY || normalized in STAY_ON_WEB -> 0
            ButtonLabelRules.roleOf(normalized) == ButtonRole.CLOSE -> 1
            normalized !in DIM_CLOSE && normalized !in HIDE_FOR_A_WHILE && ENDS_WITH_CLOSE.matches(normalized) -> 1
            normalized in DIM_CLOSE || normalized in HIDE_FOR_A_WHILE || DAYS_HIDE.matches(normalized) -> 2
            else -> 3
        }
    }

    private fun reject(reason: SimpleLabelReject) = SimpleLabelVerdict(null, reason)
}

/** C's prompt accepts a press button only when this agrees with the candidate's role. */
object SimpleLabelPolicy : PressableLabelPolicy {
    override fun roleOf(label: String?): ButtonRole? = SimpleLabelRules.roleOf(label)
}
