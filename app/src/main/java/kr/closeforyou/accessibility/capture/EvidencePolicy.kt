package kr.closeforyou.accessibility.capture

import kr.closeforyou.core.model.*
import kr.closeforyou.accessibility.PrivacyPolicy
import kr.closeforyou.accessibility.CaptureDetail
import kr.closeforyou.accessibility.RegionGeometry

/** Codes only. Neither diagnostics nor exceptions contain OCR text. */
enum class EvidenceIssue {
    NONE, NOT_REQUESTED, TREE_SUFFICIENT, SERVICE_DISABLED, CAPABILITY_MISSING,
    REGION_UNCERTAIN, STALE_SCREEN, PRIVACY_BLOCKED, BUSY, RATE_LIMITED, BUDGET_EXHAUSTED,
    CAPTURE_FAILED, CAPTURE_TIMEOUT, SECURE_WINDOW, INVALID_WINDOW, INVALID_DISPLAY,
    MEMORY_LIMIT, EMPTY_CAPTURE, OCR_FAILED, OCR_TIMEOUT, PRIVACY_UNCERTAIN, DECISION_SUMMARY_ONLY,
}

internal class EvidenceFailure(val issue: EvidenceIssue) : RuntimeException(issue.name)

internal data class CaptureRegion(val window: WindowSnapshot, val bounds: ScreenBounds)

/** Only bounded application windows or explicit dialog nodes establish an OCR region.
 * A generic newly added container/full-screen transition is deliberately insufficient. */
internal object PopupRegionResolver {
    data class Resolution(val region: CaptureRegion?, val detail: CaptureDetail, val relatedType: WindowType? = null)
    fun resolve(snapshot: PopupSnapshot, isolatedWindowCapture: Boolean = false): CaptureRegion? =
        inspect(snapshot, isolatedWindowCapture).region

    fun inspect(snapshot: PopupSnapshot, isolatedWindowCapture: Boolean = false): Resolution {
        fun denied(detail: CaptureDetail, type: WindowType? = null) = Resolution(null, detail, type)
        if (snapshot.completeness != SnapshotCompleteness.COMPLETE) return denied(CaptureDetail.REGION_TRUNCATED)
        val window = snapshot.windows.singleOrNull { it.id == snapshot.token.windowId }
            ?: return denied(CaptureDetail.REGION_TARGET_MISSING)
        if (window.type != WindowType.APPLICATION) return denied(CaptureDetail.REGION_TARGET_NOT_APPLICATION)
        if (!window.active) return denied(CaptureDetail.REGION_TARGET_INACTIVE)
        val windowBounded = bounded(window.bounds, snapshot.screenBounds)
        val bounds = if (windowBounded) window.bounds else {
            val dialogs = snapshot.nodes.filter {
                it.className?.contains("dialog", ignoreCase = true) == true &&
                    bounded(it.bounds, window.bounds) && contains(snapshot.screenBounds, it.bounds)
            }.map { it.bounds }.distinct()
            if (dialogs.isEmpty()) return denied(CaptureDetail.REGION_NO_BOUNDED_DIALOG)
            if (dialogs.size != 1) return denied(CaptureDetail.REGION_MULTIPLE_DIALOGS)
            dialogs.single()
        }
        if (!contains(snapshot.screenBounds, bounds) || !contains(window.bounds, bounds)) return denied(CaptureDetail.REGION_OUTSIDE)
        snapshot.windows.firstOrNull {
            val excludedByWindowApi = isolatedWindowCapture && it.type == WindowType.ACCESSIBILITY_OVERLAY &&
                !it.active && !it.focused
            it.id != window.id && it.layer >= window.layer && intersects(it.bounds, bounds) && !excludedByWindowApi
        }?.let { return denied(CaptureDetail.REGION_OCCLUDED, it.type) }
        return Resolution(CaptureRegion(window, bounds), if (windowBounded) CaptureDetail.REGION_WINDOW_BOUNDED else CaptureDetail.REGION_DIALOG_BOUNDED)
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

    fun geometry(inner: ScreenBounds, outer: ScreenBounds): RegionGeometry = when {
        inner.width <= 0 || inner.height <= 0 -> RegionGeometry.EMPTY
        !contains(outer, inner) -> RegionGeometry.OUTSIDE
        inner.width < 32 || inner.height < 24 -> RegionGeometry.TOO_SMALL
        bounded(inner, outer) -> RegionGeometry.BOUNDED
        else -> RegionGeometry.LARGE_OR_FULL
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
    fun review(lines: List<OcrLine>, region: ScreenBounds): PrivacyReview {
        if (lines.isEmpty() || lines.size > 200 || lines.sumOf { it.text.length } > 16_384) return PrivacyReview.UNCERTAIN
        if (lines.any { !PopupRegionResolver.contains(region, it.bounds) || it.text.isBlank() ||
                (it.confidence != null && (!it.confidence.isFinite() || it.confidence < 0.5f)) }) return PrivacyReview.UNCERTAIN
        // Separate post-image inspection: F0-A passing does not bypass any of these checks.
        val text = lines.joinToString("\n") { it.text }
        if (PrivacyPolicy.hasSensitiveText(text)) return PrivacyReview.SENSITIVE
        if (PrivacyPolicy.hasUncertainText(text) || text.count { it.isLetter() } < 2) return PrivacyReview.UNCERTAIN
        return PrivacyReview.TEXT_CHECK_PASSED
    }
}

/** Process-wide, no identities/content retained. One image/native OCR task at a time. */
internal class CaptureBudget(private val now: () -> Long) {
    private val recent = ArrayDeque<Long>()
    private var busy = false
    private var last = Long.MIN_VALUE
    private var blockedUntil = 0L

    @Synchronized fun denial(): EvidenceIssue? {
        val time = now()
        while (recent.isNotEmpty() && time - recent.first() >= WINDOW_MILLIS) recent.removeFirst()
        return when {
            busy -> EvidenceIssue.BUSY
            recent.size >= 30 -> EvidenceIssue.BUDGET_EXHAUSTED
            time < blockedUntil || (last != Long.MIN_VALUE && time - last < 2_000) || recent.count { time - it < 60_000 } >= 6 -> EvidenceIssue.RATE_LIMITED
            else -> null
        }
    }

    @Synchronized fun acquire(): AutoCloseable {
        denial()?.let { throw EvidenceFailure(it) }
        busy = true
        last = now()
        recent.addLast(last)
        var released = false
        return AutoCloseable { synchronized(this) { if (!released) { released = true; busy = false } } }
    }

    @Synchronized fun rateLimited() { blockedUntil = now() + 6_000 }

    private companion object {
        // Rolling elapsed-time window: sustained use recovers without restarting the service.
        const val WINDOW_MILLIS = 10 * 60_000L
    }
}
