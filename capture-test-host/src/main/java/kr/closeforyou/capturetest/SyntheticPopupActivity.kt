package kr.closeforyou.capturetest

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Standalone debug-only synthetic host. No app/test-runner dependencies or personal data. */
class SyntheticPopupActivity : Activity() {
    private var dialog: Dialog? = null
    private var image: android.graphics.Bitmap? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "합성 팝업 시험 화면" })
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
            setContentView(content)
            show()
            window?.setLayout(minOf(resources.displayMetrics.widthPixels - 80, 1200), 420)
        }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        image?.recycle()
        image = null
        super.onDestroy()
    }
}
