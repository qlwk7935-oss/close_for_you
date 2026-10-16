package kr.closeforyou.accessibility.capture

import kr.closeforyou.core.model.*
import java.text.Normalizer
import java.util.Locale

/** Codes only. Neither diagnostics nor exceptions contain OCR text. */
enum class EvidenceIssue {
    NONE, NOT_REQUESTED, TREE_SUFFICIENT, SERVICE_DISABLED, CAPABILITY_MISSING,
    REGION_UNCERTAIN, STALE_SCREEN, PRIVACY_BLOCKED, BUSY, RATE_LIMITED, BUDGET_EXHAUSTED,
    CAPTURE_FAILED, CAPTURE_TIMEOUT, SECURE_WINDOW, INVALID_WINDOW, INVALID_DISPLAY,
    MEMORY_LIMIT, EMPTY_CAPTURE, OCR_FAILED, OCR_TIMEOUT, PRIVACY_UNCERTAIN,
}

internal class EvidenceFailure(val issue: EvidenceIssue) : RuntimeException(issue.name)

internal data class CaptureRegion(val window: WindowSnapshot, val bounds: ScreenBounds)

/** Only bounded application windows or explicit dialog nodes establish an OCR region.
 * A generic newly added container/full-screen transition is deliberately insufficient. */
internal object PopupRegionResolver {
    fun resolve(snapshot: PopupSnapshot, isolatedWindowCapture: Boolean = false): CaptureRegion? {
        if (snapshot.completeness != SnapshotCompleteness.COMPLETE) return null
        val window = snapshot.windows.singleOrNull { it.id == snapshot.token.windowId } ?: return null
        if (window.type != WindowType.APPLICATION || !window.active) return null
        val bounds = if (bounded(window.bounds, snapshot.screenBounds)) window.bounds else {
            snapshot.nodes.filter {
                it.className?.contains("dialog", ignoreCase = true) == true &&
                    bounded(it.bounds, window.bounds) && contains(snapshot.screenBounds, it.bounds)
            }.map { it.bounds }.distinct().singleOrNull() ?: return null
        }
        if (!contains(snapshot.screenBounds, bounds) || !contains(window.bounds, bounds)) return null
        if (snapshot.windows.any {
            val excludedByWindowApi = isolatedWindowCapture && it.type == WindowType.ACCESSIBILITY_OVERLAY &&
                !it.active && !it.focused
            it.id != window.id && it.layer >= window.layer && intersects(it.bounds, bounds) && !excludedByWindowApi
        }) return null
        return CaptureRegion(window, bounds)
    }

    fun needsOcr(snapshot: PopupSnapshot, region: CaptureRegion): Boolean {
        val nodes = snapshot.nodes.filter { intersects(it.bounds, region.bounds) }
        val unlabeledImage = nodes.any {
            it.className?.contains("Image", ignoreCase = true) == true &&
                it.text.isNullOrBlank() && it.contentDescription.isNullOrBlank()
        }
        val body = nodes.filterNot { it.clickable }.sumOf {
            (it.text?.trim()?.length ?: 0) + (it.contentDescription?.trim()?.length ?: 0)
        }
        return unlabeledImage || body < 4
    }

    private fun bounded(inner: ScreenBounds, outer: ScreenBounds): Boolean =
        contains(outer, inner) && inner.width >= 32 && inner.height >= 24 &&
            area(inner).toDouble() < area(outer) * 0.92

    fun contains(outer: ScreenBounds, inner: ScreenBounds): Boolean = inner.width > 0 && inner.height > 0 &&
        inner.left >= outer.left && inner.top >= outer.top && inner.right <= outer.right && inner.bottom <= outer.bottom

    fun intersects(a: ScreenBounds, b: ScreenBounds): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    fun area(bounds: ScreenBounds): Long = bounds.width.toLong() * bounds.height
}

/** Bounds are screen coordinates, confidence is optional (never fabricated). */
internal data class OcrLine(val text: String, val bounds: ScreenBounds, val confidence: Float? = null) {
    override fun toString(): String = "OcrLine(redacted)"
}

internal enum class PrivacyReview { TEXT_CHECK_PASSED, SENSITIVE, UNCERTAIN }

/** F0-B is an independent, conservative text filter; it is not proof of an image's privacy. */
internal object OcrPrivacyReview {
    private val context = Regex(
        "비밀\\s*번호|암호|인증\\s*(번호|코드)|보안\\s*(코드|키패드)|계좌|카드|금융|은행|송금|이체|잔액|청구|결제|구매|구독|" +
            "로그\\s*인|본인\\s*인증|주민|개인\\s*정보|동의|성명|이름|고객|회원|사용자|생년월일|전화|연락처|주소|" +
            "수령인|진료|건강\\s*정보|password|passcode|otp|verification|account|credit|payment|" +
            "purchase|subscription|sign\\s*in|log\\s*in|login|consent|permission|medical|patient|address|recipient|birth",
    )
    private val longNumber = Regex("(?:\\d[ \\-·.]*){10,}")
    private val uncontextualizedCode = Regex("(?<!\\d)\\d{6,8}(?!\\d)")
    private val unsupported = Regex("[^\\p{IsHangul}\\p{IsLatin}\\p{N}\\p{P}\\p{Z}\\p{S}\\s]")

    fun review(lines: List<OcrLine>, region: ScreenBounds): PrivacyReview {
        if (lines.isEmpty() || lines.size > 200 || lines.sumOf { it.text.length } > 16_384) return PrivacyReview.UNCERTAIN
        if (lines.any { !PopupRegionResolver.contains(region, it.bounds) || it.text.isBlank() ||
                (it.confidence != null && (!it.confidence.isFinite() || it.confidence < 0.5f)) }) return PrivacyReview.UNCERTAIN
        val text = Normalizer.normalize(lines.joinToString("\n") { it.text }, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        if (context.containsMatchIn(text) || context.containsMatchIn(text.replace(Regex("\\s+"), "")) ||
            '@' in text || longNumber.containsMatchIn(text)) {
            return PrivacyReview.SENSITIVE
        }
        if (uncontextualizedCode.containsMatchIn(text) || unsupported.containsMatchIn(text) ||
            text.count { it.isLetter() } < 2) return PrivacyReview.UNCERTAIN
        return PrivacyReview.TEXT_CHECK_PASSED
    }
}

/** Process-wide, no identities/content retained. One image/native OCR task at a time. */
internal class CaptureBudget(private val now: () -> Long) {
    private val recent = ArrayDeque<Long>()
    private var total = 0
    private var busy = false
    private var last = Long.MIN_VALUE
    private var blockedUntil = 0L

    @Synchronized fun denial(): EvidenceIssue? {
        val time = now()
        while (recent.isNotEmpty() && time - recent.first() >= 60_000) recent.removeFirst()
        return when {
            busy -> EvidenceIssue.BUSY
            total >= 30 -> EvidenceIssue.BUDGET_EXHAUSTED
            time < blockedUntil || (last != Long.MIN_VALUE && time - last < 2_000) || recent.size >= 6 -> EvidenceIssue.RATE_LIMITED
            else -> null
        }
    }

    @Synchronized fun acquire(): AutoCloseable {
        denial()?.let { throw EvidenceFailure(it) }
        busy = true
        total++
        last = now()
        recent.addLast(last)
        var released = false
        return AutoCloseable { synchronized(this) { if (!released) { released = true; busy = false } } }
    }

    @Synchronized fun rateLimited() { blockedUntil = now() + 6_000 }
}
