package kr.closeforyou.servicetest

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kr.closeforyou.capturetest.SyntheticPopupActivity
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real framework geometry probe for API 35/37. No screenshot, OCR, raw text or bounds output.
 * The runner targets only the host. A fixed title distinguishes two ordinary Android windows;
 * title/class names are never used as authorization in the production resolver. */
@RunWith(AndroidJUnit4::class)
class SyntheticDialogGeometryTest {
    @Suppress("DEPRECATION") // Nodes/windows owned by this test are released in finally blocks.
    @Test fun dialogHasSeparateBoundedApplicationWindow() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("syntheticHost") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val originalInfo = automation.serviceInfo
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        var activity: android.app.Activity? = null
        var code = "WINDOWS_NOT_OBSERVED"
        var dialogClass = false
        var decorElevated = "UNKNOWN"
        try {
            val shown = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SyntheticPopupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as SyntheticPopupActivity
            activity = shown
            val frame = Rect()
            var backgroundId = -1
            instrumentation.runOnMainSync {
                frame.set(shown.windowManager.maximumWindowMetrics.bounds)
                decorElevated = shown.syntheticDialogHasElevatedDecor()?.let { if (it) "YES" else "NO" } ?: "UNKNOWN"
                val ownNode = shown.window.decorView.createAccessibilityNodeInfo()
                try { backgroundId = ownNode.windowId } finally { ownNode.recycle() }
            }
            val ready = withTimeoutOrNull(10_000) {
                while (true) {
                    val windows = automation.windows
                    try {
                        val dialog = windows.singleOrNull { it.title?.toString() == "합성 이미지 창" }
                        // A modal Dialog may make its background non-interactive and absent from
                        // getWindows(). Compare against our own decor's real framework window ID.
                        val background = windows.singleOrNull { it.id == backgroundId }
                        code = when {
                            dialog == null -> "DIALOG_WINDOW_MISSING"
                            backgroundId < 0 -> "ACTIVITY_WINDOW_ID_UNAVAILABLE"
                            dialog.id == backgroundId -> "WINDOW_ID_NOT_SEPARATE"
                            dialog.type != AccessibilityWindowInfo.TYPE_APPLICATION -> "DIALOG_NOT_APPLICATION"
                            !dialog.isActive -> "DIALOG_NOT_ACTIVE"
                            background != null && dialog.layer <= background.layer -> "DIALOG_NOT_ABOVE_ACTIVITY"
                            else -> {
                                val bounds = Rect().also(dialog::getBoundsInScreen)
                                val root = dialog.root
                                try {
                                    dialogClass = root?.className?.toString()?.contains("dialog", ignoreCase = true) == true
                                    when {
                                        bounds.isEmpty || !frame.contains(bounds) -> "DIALOG_OUTSIDE_ACTIVITY_FRAME"
                                        bounds.width() < 32 || bounds.height() < 24 ||
                                            bounds.width().toLong() * bounds.height() >= frame.width().toLong() * frame.height() * 0.92 -> "DIALOG_NOT_BOUNDED"
                                        root == null || root.packageName?.toString() != "kr.closeforyou.capturetest" || root.windowId != dialog.id -> "DIALOG_ROOT_MISMATCH"
                                        !bounds.contains(Rect().also(root::getBoundsInScreen)) -> "ROOT_OUTSIDE_DIALOG"
                                        !hasImage(root, 0) -> "DIALOG_IMAGE_NODE_MISSING"
                                        else -> "READY"
                                    }
                                } finally { root?.recycle() }
                            }
                        }
                    } finally { windows.forEach { it.recycle() } }
                    if (code == "READY") break
                    delay(100)
                }
                true
            } == true
            assertTrue("SYNTHETIC_GEOMETRY:$code", ready)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "SYNTHETIC_DIALOG_GEOMETRY_READY;ROOT_CLASS_DIALOG=${if (dialogClass) "YES" else "NO"};OWN_DECOR_ELEVATED=$decorElevated\n")
            })
        } finally {
            activity?.let { shown -> instrumentation.runOnMainSync { shown.finish() } }
            automation.serviceInfo = originalInfo
        }
    }

    @Suppress("DEPRECATION")
    private fun hasImage(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 30 || node.childCount > 100) return false
        if (node.className?.toString() == "android.widget.ImageView") return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (hasImage(child, depth + 1)) return true } finally { child.recycle() }
        }
        return false
    }
}
