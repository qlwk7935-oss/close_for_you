package kr.closeforyou.servicetest

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.closeforyou.capturetest.DetectionScenarioActivity
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real native nodes of the unchanged fixed baseline. This is NOT a remote service/F1 pass.
 * Only code sets leave memory. Never inspect text, export bounds, write images, or sort Rects. */
@RunWith(AndroidJUnit4::class)
class SyntheticBaselineBoundsTest {
    @Suppress("DEPRECATION")
    @Test fun observesOwnNativeScrollClippingWithoutRepair() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("f1Detection") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var activity: DetectionScenarioActivity? = null
        val observations = sortedSetOf<String>()
        var sawScroller = false
        var sawOrderedControl = false
        try {
            val shown = instrumentation.startActivitySync(Intent(instrumentation.targetContext, DetectionScenarioActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as DetectionScenarioActivity
            activity = shown
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val content = shown.findViewById<ViewGroup>(android.R.id.content)
                check(content.isLaidOut && content.isAttachedToWindow) { "OWN_BASELINE_NOT_LAID_OUT" }
                fun visit(view: View, underScroller: Boolean) {
                    val node = view.createAccessibilityNodeInfo()
                    try {
                        val rect = Rect().also(node::getBoundsInScreen)
                        val shape = when {
                            rect.right < rect.left && rect.bottom < rect.top -> "REVERSED_BOTH"
                            rect.right < rect.left -> "REVERSED_X"
                            rect.bottom < rect.top -> "REVERSED_Y"
                            rect.right == rect.left && rect.bottom == rect.top -> "EMPTY_BOTH"
                            rect.right == rect.left -> "EMPTY_X"
                            rect.bottom == rect.top -> "EMPTY_Y"
                            else -> "ORDERED"
                        }
                        val visibility = if (node.isVisibleToUser) "VISIBLE" else "NOT_VISIBLE"
                        val ancestry = if (underScroller) "SCROLL_ANCESTOR" else "NO_SCROLL_ANCESTOR"
                        val layout = when {
                            !view.isAttachedToWindow -> "DETACHED"
                            !view.isLaidOut || view.isLayoutRequested -> "LAYOUT_PENDING"
                            else -> "LAYOUT_STABLE"
                        }
                        observations += "NODE_RECT=$shape;NODE_VISIBILITY=$visibility;NODE_ANCESTRY=$ancestry;LAYOUT=$layout"
                        sawScroller = sawScroller || view is ScrollView
                        sawOrderedControl = sawOrderedControl || (!underScroller && node.isVisibleToUser && shape == "ORDERED")
                    } finally { node.recycle() }
                    if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it), underScroller || view is ScrollView) }
                }
                visit(content, false)
            }
        } catch (_: Exception) {
            fail("OWN_BASELINE_BOUNDS_PROBE_FAILED") // No framework message, cause or stack dump.
        } finally {
            activity?.let { shown -> instrumentation.runOnMainSync { shown.finishAndRemoveTask() } }
        }
        observations.forEach { codes -> instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "F1_OWN_BOUNDS;SOURCE=OWN_VIEW;$codes;REPAIR=NONE;SERVICE_COLLECTION=NOT_TESTED\n")
        }) }
        assertTrue("OWN_SCROLLER_UNOBSERVED", sawScroller)
        assertTrue("OWN_ORDERED_CONTROL_UNOBSERVED", sawOrderedControl)
        // No assertion that inverted or invisible nodes are safe to ignore. Service baseline still must pass independently.
    }
}
