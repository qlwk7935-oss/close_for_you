package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.decision.DecisionDemoRules

/** F28: why a screen is left alone right after it is read; a code for the log, never the address or the words. */
enum class SensitiveScreenReason {
    /** A visible password field ([TreeRead.passwordSeen]), in any app. */
    PASSWORD_FIELD,
    WEB_ADDRESS,
    WEB_CREDENTIAL_FORM,
    WEB_PAYMENT_PAGE,
    WEB_PERSONAL_DATA,
}

/**
 * F28 (B_DEMO_RULES.md 2절, wired by C): checked right after a read, before the video skip and the popup search.
 * A hit means nothing on that screen is judged, pressed or spoken by us. B's [BrowserSensitivity] on the nodes read,
 * plus the address bar looked up apart ([TreeRead.address]: a popup-only read does not reach the bar), plus a
 * password field anywhere on screen (the flag only). Pure; JVM tested.
 */
object SensitiveScreen {
    fun reason(nodes: List<NodeSnapshot>, screen: ScreenBounds, passwordSeen: Boolean, address: String?): SensitiveScreenReason? {
        if (passwordSeen) return SensitiveScreenReason.PASSWORD_FIELD
        if (address != null && BrowserSensitivity.sensitiveAddress(address)) return SensitiveScreenReason.WEB_ADDRESS
        return when (BrowserSensitivity.check(nodes, screen)) {
            null -> null
            BrowserSensitiveReason.SENSITIVE_ADDRESS -> SensitiveScreenReason.WEB_ADDRESS
            BrowserSensitiveReason.CREDENTIAL_FORM -> SensitiveScreenReason.WEB_CREDENTIAL_FORM
            BrowserSensitiveReason.PAYMENT_PAGE -> SensitiveScreenReason.WEB_PAYMENT_PAGE
            BrowserSensitiveReason.PERSONAL_DATA -> SensitiveScreenReason.WEB_PERSONAL_DATA
        }
    }

    fun trace(reason: SensitiveScreenReason): SimpleTrace = SimpleTrace.valueOf("SKIPPED_SCOPE_SENSITIVE_${reason.name}")
}

/**
 * F28 (B_DEMO_RULES.md 1절, wired by C): of a popup's risky buttons, how many may stop keeping it asked. Only
 * promotion purchase calls to action ([ButtonScan.promoBuyCount]), only when B's rule is on, B itself (not the
 * image-dialog hint) called the popup an ad or a coupon, and B's four conditions hold on the popup's own texts
 * ([kr.closeforyou.decision.DecisionDemoRules.isPromoPurchase]: the button is a whole call to action, promotion
 * words elsewhere, no decision words elsewhere, no checkout trace anywhere). Otherwise 0: asked as before.
 * The purchase button is never a press target either way.
 */
object PromoPurchaseGate {
    fun allowed(
        enabled: Boolean,
        byB: PopupClassification,
        hinted: Boolean,
        texts: List<ExtractedText>,
        promoBuyCount: Int,
    ): Int {
        if (!enabled || hinted || promoBuyCount <= 0) return 0
        if (byB != PopupClassification.ADVERTISEMENT && byB != PopupClassification.COUPON) return 0
        return if (DecisionDemoRules.isPromoPurchase(texts)) promoBuyCount else 0
    }
}

/**
 * F28 (LG 11/1 14:29): whether the app a prompt is about has left the screen, from the packages of the application
 * windows on screen ([SimpleTreeCollector.applicationPackages]). Our own app's window is one of them like any
 * other (its activity in front means the popup's app left); our prompt overlay is not. Null (not known): no.
 */
object PromptedApp {
    fun left(popupPackage: String, shownApps: Set<String>?): Boolean = shownApps != null && popupPackage !in shownApps
}
