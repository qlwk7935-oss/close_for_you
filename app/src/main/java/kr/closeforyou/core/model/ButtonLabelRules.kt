package kr.closeforyou.core.model

import java.text.Normalizer

/** Why a label is not a close or skip label. A kind code only: it never carries screen text. */
enum class LabelRejectReason {
    NO_LABEL,
    SYMBOL_ONLY,
    /** Design 3-1 grade D. The button may ask for an important choice; it is not proof of a payment screen. */
    DECISION_WORD,
    /** Grade K: closes and also changes something (오늘 하루, 다시 보지 않기, 취소, 저장). */
    CONDITIONAL_WORD,
    /** Grade P: opens, installs or moves elsewhere. */
    NAVIGATION_WORD,
    /** Grade O: 확인, OK, 네. */
    AMBIGUOUS_WORD,
    NOT_IN_LIST,
    /** Node only: the text and the content description do not give the same role. */
    LABEL_CONFLICT,
}

/** Exactly one of [role] (CLOSE or SKIP) and [reason] is set. */
data class LabelVerdict(val role: ButtonRole?, val reason: LabelRejectReason?) {
    init {
        require((role == null) != (reason == null))
        require(role == null || role == ButtonRole.CLOSE || role == ButtonRole.SKIP)
    }
}

/**
 * Shared button label contract for A's gate, B's candidates and policy, and C's prompt.
 *
 * A matching label is never an action authorization: the gate still checks popup membership,
 * freshness and protection on the real screen. Pure Kotlin so every module and JVM test can use it.
 *
 * Rule: NFKC, lowercase (locale-independent), trim and collapse [Char.isWhitespace] runs to one
 * space. Invisible characters such as U+200B and U+FEFF are not whitespace and are never removed,
 * so they make the label fail to match. Then X-like symbols only → [LabelRejectReason.SYMBOL_ONLY];
 * risky words in grade order D → K → P → O; otherwise an exact match with the allowlist.
 * Korean words match as substrings, English words as whole tokens (no regex `\b`, whose word
 * boundaries differ between JDK and Android).
 */
object ButtonLabelRules {
    const val VERSION = 1

    fun roleOf(label: String?): ButtonRole? = judge(label).role

    fun judge(label: String?): LabelVerdict {
        if (label == null) return reject(LabelRejectReason.NO_LABEL)
        val normalized = normalize(label)
        if (normalized.isEmpty()) return reject(LabelRejectReason.NO_LABEL)
        if (isSymbolOnly(normalized)) return reject(LabelRejectReason.SYMBOL_ONLY)
        val tokens = normalized.split(NON_TOKEN).filter { it.isNotEmpty() }
        for ((reason, words) in RISKY_WORDS) {
            if (words.korean.any { it in normalized } || words.english.any { tokens.containsPhrase(it) }) return reject(reason)
        }
        if (normalized in EXACT_AMBIGUOUS) return reject(LabelRejectReason.AMBIGUOUS_WORD)
        return when (normalized) {
            in CLOSE_LABELS -> LabelVerdict(ButtonRole.CLOSE, null)
            in SKIP_LABELS -> LabelVerdict(ButtonRole.SKIP, null)
            else -> reject(LabelRejectReason.NOT_IN_LIST)
        }
    }

    /**
     * Every non-blank text and description must give the same role. A risky word on either side
     * wins (the more serious grade first); otherwise differing verdicts are [LabelRejectReason.LABEL_CONFLICT].
     */
    fun judgeNode(text: String?, description: String?): LabelVerdict {
        val verdicts = listOfNotNull(text, description).filter { normalize(it).isNotEmpty() }.map(::judge)
        if (verdicts.isEmpty()) return reject(LabelRejectReason.NO_LABEL)
        RISKY_WORDS.keys.firstOrNull { risky -> verdicts.any { it.reason == risky } }?.let { return reject(it) }
        // One shared role, or the same kind of rejection on both sides: report the first verdict.
        return if (verdicts.map { it.role }.toSet().size > 1) reject(LabelRejectReason.LABEL_CONFLICT) else verdicts.first()
    }

    fun normalize(label: String): String {
        val folded = Normalizer.normalize(label, Normalizer.Form.NFKC).lowercase()
        val out = StringBuilder(folded.length)
        var pendingSpace = false
        for (c in folded) {
            if (c.isWhitespace()) {
                pendingSpace = true
                continue
            }
            if (pendingSpace && out.isNotEmpty()) out.append(' ')
            pendingSpace = false
            out.append(c)
        }
        return out.toString()
    }

    private fun reject(reason: LabelRejectReason) = LabelVerdict(null, reason)

    private fun isSymbolOnly(normalized: String): Boolean {
        var mark = false
        for (cp in normalized.codePoints().toArray()) {
            when (cp) {
                in X_MARKS -> mark = true
                in BRACKETS -> Unit
                else -> return false
            }
        }
        return mark
    }

    private fun List<String>.containsPhrase(phrase: String): Boolean {
        val words = phrase.split(' ')
        return (0..size - words.size).any { start -> words.indices.all { this[start + it] == words[it] } }
    }

    private class Words(val korean: List<String>, val english: List<String>)

    private val NON_TOKEN = Regex("[^a-z0-9']+")

    // Ordered by severity: a label hitting several grades reports the first.
    private val RISKY_WORDS: Map<LabelRejectReason, Words> = linkedMapOf(
        LabelRejectReason.DECISION_WORD to Words(
            listOf("결제", "구매", "구독", "동의", "권한", "허용", "삭제", "가입", "로그인", "계정", "개인정보", "승인", "송금", "이체", "해지", "탈퇴", "체험"),
            listOf(
                "pay", "payment", "purchase", "buy", "subscribe", "subscription", "consent", "agree", "accept", "permission",
                "allow", "delete", "login", "log in", "sign in", "sign up", "account", "personal information", "personal data", "trial",
            ),
        ),
        LabelRejectReason.CONDITIONAL_WORD to Words(
            listOf("취소", "거절", "나중에", "다음에", "저장", "보지 않기", "다시 보지", "그만", "오늘", "하루", "일간"),
            listOf("cancel", "decline", "later", "not now", "today", "don't show", "do not show"),
        ),
        LabelRejectReason.NAVIGATION_WORD to Words(
            listOf("설치", "자세히", "더보기", "받기", "열기", "바로가기", "이동", "다운로드"),
            listOf("install", "open", "more", "get", "download"),
        ),
        LabelRejectReason.AMBIGUOUS_WORD to Words(listOf("확인", "알겠"), listOf("ok", "okay", "confirm", "continue", "got it")),
    )

    // Whole label only: "네" and "예" also occur inside other words (네트워크, 예약).
    private val EXACT_AMBIGUOUS = setOf("네", "예", "아니요", "아니오", "yes", "no")

    // Allowlist v1 (14): A's gate list plus the PR #12 additions. Joined forms (창닫기) and other
    // B-only wording stay out until real apps show them (team decision, 2026-10-19).
    private val CLOSE_LABELS = setOf("닫기", "창 닫기", "팝업 닫기", "close", "dismiss", "광고 닫기", "공지 닫기", "닫기 버튼", "close ad")
    private val SKIP_LABELS = setOf("건너뛰기", "스킵", "skip", "skip ad", "광고 건너뛰기")

    // X-like marks after normalization (full-width Ｘ and ⓧ become "x"), plus the emoji selector.
    private val X_MARKS = setOf(0x78, 0xD7, 0x2715, 0x2716, 0x274C, 0x2A09, 0x2573, 0x1F5D9, 0xFE0F)
    private val BRACKETS = setOf('['.code, ']'.code, '('.code, ')'.code, '<'.code, '>'.code, ' '.code)

    internal val allowlist: Map<String, ButtonRole> get() =
        CLOSE_LABELS.associateWith { ButtonRole.CLOSE } + SKIP_LABELS.associateWith { ButtonRole.SKIP }
}
