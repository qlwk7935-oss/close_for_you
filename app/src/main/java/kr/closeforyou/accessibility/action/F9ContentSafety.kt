package kr.closeforyou.accessibility.action

import java.text.Normalizer
import java.util.Locale
import kr.closeforyou.accessibility.PrivacyPolicy

/** Additional deny-only guard, never a substitute for F0 or B's complete decision/scam policy.
 * ASCII boundaries intentionally avoid PR #37's Android/JVM boundary mismatch next to Hangul.
 */
internal object F9ContentSafety {
    private val decisions = Regex("결제|구독|구매|송금|이체|인증|로그인|동의|약관|권한|허용|승인|삭제|가입|탈퇴|해지|체험|개인정보|" +
        "(?<![a-z0-9_])(pay|payment|purchase|buy|subscribe|subscription|consent|agree|accept|permission|allow|" +
        "delete|login|log\\s*in|sign\\s*(in|up)|account|trial|transfer|terms)(?![a-z0-9_])")
    private val scam = Regex("당첨|사기|피싱|감염|바이러스|긴급|즉시.{0,12}설치|" +
        "(?<![a-z0-9_])(winner|won|prize|virus|infected|urgent|phishing)(?![a-z0-9_])")
    fun blocked(value: CharSequence): Boolean {
        val text = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return PrivacyPolicy.hasSensitiveText(text) || PrivacyPolicy.hasUncertainText(text) ||
            decisions.containsMatchIn(text) || scam.containsMatchIn(text)
    }
    fun protectedPackage(packageName: String): Boolean =
        PrivacyPolicy.packageBlock(packageName, "kr.closeforyou", true, false, setOf(packageName), emptySet()) != null ||
            Regex("bank|banking|finance|wallet|payment|pay\\.|securities|stock|password|keychain|authenticator|bitwarden|1password|lastpass|dashlane")
                .containsMatchIn(packageName.lowercase(Locale.ROOT))
}
