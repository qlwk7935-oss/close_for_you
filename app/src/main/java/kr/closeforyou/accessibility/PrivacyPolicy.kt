package kr.closeforyou.accessibility

import kr.closeforyou.core.model.PrivacyBlockReason
import java.text.Normalizer
import java.util.Locale

/** F0-A is a conservative filter, never proof that a screen contains no private information. */
internal object PrivacyPolicy {
    private val protectedPackages = setOf(
        "com.android.settings", "com.android.systemui", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.contacts", "com.google.android.contacts",
        "com.android.mms", "com.google.android.apps.messaging", "com.google.android.gm",
        "com.microsoft.office.outlook", "com.kakao.talk", "org.telegram.messenger",
        "com.whatsapp", "com.samsung.android.messaging", "com.samsung.android.email.provider",
        "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.sec.android.app.shealth",
        "com.google.android.apps.walletnfcrel", "com.samsung.android.spay", "com.kakaobank.channel",
        "com.kbstar.kbbank", "com.shinhan.sbanking", "com.wooribank.smart.npib",
        "com.nh.smartbanking", "com.hanabank.ebk.channel.android.hananbank", "viva.republica.toss",
    )
    // Actual private/credential/financial context takes priority over decision summaries.
    // Generic decision verbs are handled separately; they never authorize an action.
    private val sensitiveContext = Regex(
        "비밀번호|암호|인증(번호|코드)|보안(코드|키패드)|주민|계좌|카드|금융|은행|송금|이체|잔액|청구|" +
            "로그인|본인인증|성명|이름|고객|회원|사용자|생년월일|전화|연락처|주소|수령인|진료|건강정보|" +
            "password|passcode|otp|verification|account|credit|signin|login|socialsecurity|" +
            "medical|patient|address|recipient|birth",
    )
    private val longNumber = Regex("(?:\\d[\\s\\-·.]*){10,}")
    private val possibleCode = Regex("(?<!\\d)\\d{6,8}(?!\\d)")
    private val unsupported = Regex("[^\\p{IsHangul}\\p{IsLatin}\\p{N}\\p{P}\\p{Z}\\p{S}\\s]")
    private val genericDataConsent = Regex("개인정보(수집(및이용)?|처리|이용)?에?동의")
    private val decisions = listOf(
        Regex("결제|payment|pay(?![a-z])") to "결제 선택 필요",
        Regex("구매|purchase|buy(?![a-z])") to "구매 선택 필요",
        Regex("구독|subscribe|subscription") to "구독 선택 필요",
        Regex("동의|consent|agree") to "동의 선택 필요",
        Regex("권한|접근허용|permission") to "권한 허용 선택 필요",
        Regex("삭제|delete") to "삭제 선택 필요",
    )

    private fun normalized(value: CharSequence): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private fun compact(value: CharSequence): String = normalized(value).replace(Regex("\\s+"), "")

    /** Fixed vocabulary only. No captured string, identifier, amount or button handle is returned. */
    fun decisionSummaries(value: CharSequence): List<String> {
        val text = compact(value)
        return decisions.filter { it.first.containsMatchIn(text) }.map { it.second }
    }

    fun hasUncertainText(value: CharSequence?): Boolean {
        if (value.isNullOrEmpty()) return false
        val text = normalized(value)
        return possibleCode.containsMatchIn(text) || unsupported.containsMatchIn(text)
    }

    fun packageBlock(
        packageName: String,
        ownPackage: String,
        consent: Boolean,
        paused: Boolean,
        allowedPackages: Set<String>,
        inputMethodPackages: Set<String>,
    ): PrivacyBlockReason? = when {
        packageName == ownPackage || packageName == "android" ||
            packageName in inputMethodPackages -> PrivacyBlockReason.SYSTEM_WINDOW
        packageName in protectedPackages -> PrivacyBlockReason.PROTECTED_APP
        !consent -> PrivacyBlockReason.NO_CONSENT
        paused -> PrivacyBlockReason.PAUSED
        packageName !in allowedPackages -> PrivacyBlockReason.APP_NOT_ALLOWED
        else -> null
    }

    fun fieldBlock(password: Boolean, editable: Boolean, inputType: Int): PrivacyBlockReason? {
        // InputType values are kept platform-independent so these protections run in JVM tests.
        val inputClass = inputType and 0x0f
        val variation = inputType and 0x0ff0
        val passwordVariation = (inputClass == 1 && variation in setOf(0x80, 0x90, 0xe0)) ||
            (inputClass == 2 && variation == 0x10)
        return when {
            password || passwordVariation -> PrivacyBlockReason.PASSWORD
            editable || inputType != 0 -> PrivacyBlockReason.EDITABLE_FIELD
            else -> null
        }
    }

    fun hasSensitiveText(value: CharSequence?): Boolean {
        if (value.isNullOrEmpty()) return false
        val text = normalized(value)
        val condensed = compact(value)
        // Permit the generic data-consent label only; surrounding private fields still block.
        val privateContext = genericDataConsent.replace(condensed, "")
        return sensitiveContext.containsMatchIn(condensed) || '@' in text || longNumber.containsMatchIn(text) ||
            "개인정보" in privateContext || "personaldata" in condensed || "personalinformation" in condensed
    }
}
