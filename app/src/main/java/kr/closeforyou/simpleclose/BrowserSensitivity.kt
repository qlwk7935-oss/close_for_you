package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds
import java.util.Locale

/** Why a browser screen is left alone (B_DEMO_RULES.md 2절). A code only: never the address or the text. */
enum class BrowserSensitiveReason {
    /** The address is a login, account, payment, bank, card, securities or government page. */
    SENSITIVE_ADDRESS,
    /** A text input next to a password, PIN, OTP or verification-code label: a login or auth form. */
    CREDENTIAL_FORM,
    /** Two or more checkout signals on screen (결제 수단, 카드 번호, 할부, 결제 금액…): a payment page. */
    PAYMENT_PAGE,
    /** A card, resident-registration or phone number, or an e-mail address, is shown on the page. */
    PERSONAL_DATA,
}

/**
 * B_DEMO_RULES.md 2절 (proposal): which browser screens the demo engine must not read further or act on.
 *
 * App-level protection ([kr.closeforyou.ui.voice.settings.SensitiveAppRules], A #65) cannot see inside a
 * browser: the same Chrome shows 11번가 and a bank. Plain words are not enough either: a shopping home page
 * has "로그인" and "개인정보처리방침" links in its footer. So a screen counts as sensitive only on a signal
 * that a login, payment, bank or personal-data page is actually open: its address, a credential form,
 * several checkout fields together, or personal data itself. Pure Kotlin: the address and texts are read
 * in memory and only the reason code leaves this function.
 */
object BrowserSensitivity {
    /** Address bars of Chrome, Samsung Internet, Whale and the Naver app. */
    val ADDRESS_BAR_IDS = listOf(":id/url_bar", ":id/location_bar_edit_text", ":id/url_field", ":id/address_bar", ":id/search_box_text")

    /** F28 (C): the collector looks the bar up by these ids when its read did not reach it. */
    fun isAddressBarId(viewId: String): Boolean = ADDRESS_BAR_IDS.any { viewId.endsWith(it) }

    private val HOST_PREFIXES = listOf("login.", "nid.", "auth.", "accounts.", "account.", "signin.", "member.", "members.", "secure.", "pay.", "payment.", "checkout.", "order.", "cert.")
    private val HOST_WORDS = listOf(
        "bank", "card", "securities", "insur", "stock", "kbstar", "shinhan", "woori", "hanabank", "nonghyup", "ibk", "kakaobank",
        "toss", "kakaopay", "naverpay", "payco", "inicis", "tosspayments", "kcp.co.kr", "nicepay", "danal", "mobilians", "kgcert",
        "gov.kr", ".go.kr", "hometax", "yessign", "crosscert",
    )
    private val PATH_WORDS = listOf("login", "signin", "sign-in", "logon", "join", "signup", "sign-up", "order", "checkout", "payment", "mypage", "myinfo", "account")

    // ASCII boundaries, not `\b` (it differs next to Hangul on Android, PR #37).
    private val CREDENTIAL_LABEL = Regex("비밀번호|패스워드|password|passcode|(?<![a-z0-9_])(pin|otp)(?![a-z0-9_])|인증\\s?번호|보안\\s?(문자|카드|코드)", RegexOption.IGNORE_CASE)

    private val CHECKOUT_SIGNALS = listOf(
        Regex("결제\\s?수단"), Regex("카드\\s?번호"), Regex("유효\\s?기간"), Regex("cvc|cvv", RegexOption.IGNORE_CASE), Regex("할부"),
        Regex("(최종|총)\\s?결제\\s?금액"), Regex("결제\\s?하기"), Regex("배송지\\s?(정보|입력|변경)"), Regex("주문자\\s?정보"), Regex("무통장|계좌\\s?이체"),
    )

    private val CARD_NUMBER = Regex("(?<!\\d)\\d{4}[- ]\\d{4}[- ]\\d{4}[- ]\\d{4}(?!\\d)")
    private val RESIDENT_NUMBER = Regex("(?<!\\d)\\d{6}-[1-4]\\d{6}(?!\\d)")
    private val PHONE_NUMBER = Regex("(?<!\\d)01[016789]-\\d{3,4}-\\d{4}(?!\\d)")
    private val EMAIL = Regex("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}", RegexOption.IGNORE_CASE)

    fun check(nodes: List<NodeSnapshot>, screen: ScreenBounds): BrowserSensitiveReason? {
        val address = nodes.firstOrNull { n -> n.viewId != null && ADDRESS_BAR_IDS.any { n.viewId.endsWith(it) } }
        address?.text?.let { if (sensitiveAddress(it)) return BrowserSensitiveReason.SENSITIVE_ADDRESS }
        // Page content only: the address bar is an input field of every browser.
        val page = nodes.filter { it !== address && PopupRootFinder.isVisible(it, screen) }
        val words = page.flatMap { listOfNotNull(it.text, it.contentDescription) }
        val hasInput = page.any { it.className?.contains("EditText", ignoreCase = true) == true }
        if (hasInput && words.any(CREDENTIAL_LABEL::containsMatchIn)) return BrowserSensitiveReason.CREDENTIAL_FORM
        if (CHECKOUT_SIGNALS.count { signal -> words.any(signal::containsMatchIn) } >= 2) return BrowserSensitiveReason.PAYMENT_PAGE
        if (words.any { CARD_NUMBER.containsMatchIn(it) || RESIDENT_NUMBER.containsMatchIn(it) || PHONE_NUMBER.containsMatchIn(it) || EMAIL.containsMatchIn(it) }) {
            return BrowserSensitiveReason.PERSONAL_DATA
        }
        return null
    }

    /** Host and path of what the address bar shows, compared in lower case; nothing is kept. */
    fun sensitiveAddress(shown: String): Boolean {
        val lower = shown.trim().lowercase(Locale.ROOT).removePrefix("https://").removePrefix("http://")
        if (lower.isEmpty() || ' ' in lower) return false // a search query typed in the bar, not an address
        val host = lower.substringBefore('/').substringBefore('?').substringBefore(':')
        val path = lower.substring(host.length)
        if (HOST_PREFIXES.any { host.startsWith(it) || host.startsWith("m.$it") }) return true
        if (HOST_WORDS.any { it in host }) return true
        return path.split('/', '?', '&', '=', '.', '_').any { it in PATH_WORDS }
    }
}
