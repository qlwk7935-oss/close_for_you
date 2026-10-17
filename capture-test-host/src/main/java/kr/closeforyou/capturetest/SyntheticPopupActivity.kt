package kr.closeforyou.capturetest

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import kr.closeforyou.synthetictest.PatternRenderHint
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Standalone debug-only synthetic host. No app/test-runner dependencies or personal data. */
class SyntheticPopupActivity : Activity() {
    private var dialog: Dialog? = null
    internal var patternArmCode: String = "NOT_ARMED"
        private set
    internal var patternRendering: Set<PatternRenderHint> = emptySet()
        private set
    private var image: android.graphics.Bitmap? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "합성 배경 창"
        setContentView(TextView(this).apply { text = "합성 팝업 시험 화면" })
        val patternSession = intent.getStringExtra("patternSession")
        if (patternSession != null && intent.getBooleanExtra("syntheticPattern", false)) {
            showPatternDialog(patternSession)
            return
        }
        val pixels = syntheticNotice().also { image = it }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ImageView(this@SyntheticPopupActivity).apply {
                setImageBitmap(pixels)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 260))
            addView(Button(this@SyntheticPopupActivity).apply {
                text = "닫기"
                setOnClickListener { dialog?.dismiss() }
            })
        }
        dialog = Dialog(this).apply {
            setTitle("합성 이미지 창")
            setContentView(content)
            show()
            window?.setLayout(minOf(resources.displayMetrics.widthPixels - 80, 1200), 420)
        }
    }

    private fun showPatternDialog(session: String) {
        val pattern = SyntheticPatternView(this)
        val owned = Dialog(this).apply {
            setTitle("합성 이미지 창")
            setContentView(pattern)
            window?.setWindowAnimations(0)
            if (intent.getBooleanExtra("zeroElevation", false)) {
                // Fresh Dialog before its first show: do not reuse a surface with preserved insets.
                window?.decorView?.apply { elevation = 0f; translationZ = 0f }
            }
        }
        dialog = owned
        pattern.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            private var previousGeometry: IntArray? = null
            @Suppress("DEPRECATION")
            override fun onPreDraw(): Boolean {
                if (pattern.width < 160 || pattern.height < 100) return true
                val decor = requireNotNull(owned.window).decorView
                val origin = IntArray(2).also(decor::getLocationOnScreen)
                val content = IntArray(2).also(pattern::getLocationOnScreen)
                val node = decor.createAccessibilityNodeInfo()
                try {
                    val geometry = intArrayOf(node.windowId, origin[0], origin[1],
                        origin[0] + decor.width, origin[1] + decor.height, content[0], content[1],
                        content[0] + pattern.width, content[1] + pattern.height)
                    // Wait for two identical owned pre-draw frames, without changing screenshot timeouts.
                    if (node.windowId < 0 || previousGeometry?.contentEquals(geometry) != true) {
                        previousGeometry = geometry
                        pattern.postInvalidateOnAnimation()
                        return true
                    }
                    pattern.viewTreeObserver.removeOnPreDrawListener(this)
                    previousGeometry = null
                    patternRendering = renderingHints(pattern, decor)
                    val extras = Bundle().apply {
                        // Ephemeral private IPC only. Never attach this Bundle to status/assertions/logs.
                        putIntArray("ownedGeometry", geometry)
                        putBoolean("decorElevated", decor.z > 0f)
                    }
                    patternArmCode = try {
                        contentResolver.call(android.net.Uri.parse("content://kr.closeforyou.syntheticcapturetest"),
                            "ARM_PATTERN", session, extras)?.getString("code") ?: "ARM_UNAVAILABLE"
                    } catch (_: Exception) { "ARM_UNAVAILABLE" }
                } finally { node.recycle() }
                return true
            }
        })
        owned.show()
        owned.window?.setLayout(minOf(resources.displayMetrics.widthPixels - 80, 1200), 420)
    }

    /** Read-only, owned-view hints at ARM. These do not prove which drawing step changed a pixel. */
    private fun renderingHints(content: View, decor: View): Set<PatternRenderHint> = buildSet {
        add(if (content.isHardwareAccelerated) PatternRenderHint.HARDWARE else PatternRenderHint.SOFTWARE)
        if (content.clipToOutline) add(PatternRenderHint.CONTENT_OUTLINE)
        if (decor.clipToOutline) add(PatternRenderHint.DECOR_OUTLINE)
        if (content.foreground != null) add(PatternRenderHint.CONTENT_FOREGROUND)
        var parent = content.parent
        var depth = 0
        while (parent is View && depth++ < 30) {
            if (parent.clipToOutline) add(PatternRenderHint.ANCESTOR_OUTLINE)
            if (parent.foreground != null) add(PatternRenderHint.ANCESTOR_FOREGROUND)
            if (parent is ViewGroup) {
                if (parent.clipChildren) add(PatternRenderHint.ANCESTOR_CLIP_CHILDREN)
                if (parent.clipToPadding) add(PatternRenderHint.ANCESTOR_CLIP_PADDING)
            }
            parent = parent.parent
        }
    }

    /** Public metadata hint only; elevation does not reveal surface insets or a crop transform. */
    internal fun syntheticDialogHasElevatedDecor(): Boolean? = dialog?.window?.decorView?.let { it.z > 0f }

    override fun onDestroy() {
        dialog?.dismiss()
        image?.recycle()
        image = null
        super.onDestroy()
    }
}
