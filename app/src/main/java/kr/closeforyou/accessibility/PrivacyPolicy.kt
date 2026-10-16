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
    // These keywords block analysis, not just action. Localisation/image-only screens can evade this.
    private val sensitiveWords = listOf(
        "비밀번호", "암호", "주민등록", "주민번호", "계좌", "카드번호", "카드 번호", "보안키패드",
        "보안 키패드", "결제", "구매", "구독", "본인인증", "본인 인증", "로그인", "권한 허용",
        "접근 허용", "개인정보", "개인 정보", "동의", "삭제", "건강정보", "진료", "인증번호",
        "password", "passcode", "sign in", "log in", "login", "payment", "purchase", "subscribe",
        "subscription", "credit card", "bank account", "permission", "consent", "delete account",
        "social security", "verification code", "medical record",
    )

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
        val normalised = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return sensitiveWords.any(normalised::contains)
    }
}
