package kr.closeforyou.servicetest

import android.app.Instrumentation
import android.app.UiAutomation
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import kr.closeforyou.capturetest.MinimalDialogActivity

/** No new Activity or service hook. Changes only the host's existing single ordinary TextView. */
internal class LayoutChangeReader(private val instrumentation: Instrumentation, private val automation: UiAutomation,
    private val activity: MinimalDialogActivity, val minimal: MinimalGeometryReader) {
    private val ordinary = PreparedDialogReader(instrumentation, automation, minimal)
    val safety get() = minimal.readSafety
    private var requested = false

    fun stimulateBaseline() = ordinary.stimulate(activity)
    fun anchor(value: MinimalGeometrySample, stimulated: Boolean) = ordinary.baseline(activity, value, stimulated)

    /** Main-thread host layout request, not an accessibility action or injected service event. */
    fun requestChange(before: MinimalGeometrySample) = MinimalGeometryReader.onMain(instrumentation) {
        safety.requireClean()
        if (requested || activity.ownedDialog != null || activity.isDestroyed || activity.isFinishing)
            throw LayoutFailure(LayoutIssue.FIXTURE_INVALID)
        val parent = activity.findViewById<FrameLayout>(android.R.id.content)
            ?: throw LayoutFailure(LayoutIssue.FIXTURE_INVALID)
        if (parent.childCount != 1) throw LayoutFailure(LayoutIssue.FIXTURE_INVALID)
        val text = parent.getChildAt(0) as? TextView ?: throw LayoutFailure(LayoutIssue.FIXTURE_INVALID)
        val bounds = before.geometry.own.nodeBounds
        if (bounds.size != 2 || bounds[0] != bounds[1] || parent.height < 2 ||
            parent.height.toLong() != bounds[0].bottom.toLong() - bounds[0].top ||
            !text.isLaidOut || text.isLayoutRequested || !text.isAttachedToWindow ||
            text.text.toString() != MinimalDialogActivity.BASE_TEXT || text.contentDescription?.toString() != PreparedDialogReader.METADATA ||
            text.isEnabled || text.isClickable || text.isLongClickable || text.isFocusable || text.isTextSelectable)
            throw LayoutFailure(LayoutIssue.FIXTURE_INVALID)
        requested = true
        text.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, parent.height / 2, Gravity.TOP)
        // Assignment requests a real Android layout. No sendAccessibilityEvent/result injection.
    }

    /** Window/frame/configuration must be unchanged; only the intended child's bounds may differ. */
    fun afterChange(before: MinimalGeometrySample): MinimalGeometrySample {
        safety.requireClean()
        if (!requested) throw LayoutFailure(LayoutIssue.LAYOUT_NOT_CHANGED)
        val sample = minimal.sample(instrumentation, automation, activity)
        val old = before.geometry
        val current = sample.geometry
        if (!current.coherent || sample.configuration != before.configuration ||
            current.copy(own = current.own.copy(nodeBounds = old.own.nodeBounds)) != old)
            throw LayoutFailure(LayoutIssue.ENVIRONMENT_CHANGED)
        if (!LayoutMutation.changed(old.own.nodeBounds, current.own.nodeBounds))
            throw GeometryDiagnosticFailure(GeometryIssue.LAYOUT_PENDING)
        ordinary.baseline(activity, sample, stimulated = true)
        return sample
    }
}
