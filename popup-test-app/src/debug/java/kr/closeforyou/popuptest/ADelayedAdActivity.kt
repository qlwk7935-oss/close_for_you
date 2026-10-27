package kr.closeforyou.popuptest

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** A's independent Debug stimulus, not P31 or a replacement for its purchase-content answer.
 * Host adds one real Button after three seconds. No accessibility event/result is injected. */
class ADelayedAdActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var used = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "A_DELAYED_AD"
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ADelayedAdActivity).apply { setText(R.string.background_title) })
            addView(TextView(this@ADelayedAdActivity).apply { setText(R.string.background_body) })
        })
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (used || !intent.getBooleanExtra("show_delayed", false)) return
        used = true
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32)
            addView(TextView(this@ADelayedAdActivity).apply { setText(R.string.a_safe_delayed_ad_title) })
            addView(TextView(this@ADelayedAdActivity).apply { setText(R.string.a_safe_delayed_ad_body) })
        }
        dialog = Dialog(this).apply { setContentView(content); show() }
        Log.i("A_DelayedFixture", "A_DELAYED_FIXTURE;STATE=NO_BUTTON")
        handler.postDelayed({
            if (dialog?.isShowing != true || isFinishing) return@postDelayed
            content.addView(Button(this).apply {
                id = R.id.btn_close; setText(R.string.close)
                setOnClickListener {
                    Log.i("A_DelayedFixture", "A_DELAYED_FIXTURE;STATE=BUTTON_CLICKED")
                    dialog?.dismiss()
                }
            })
            Log.i("A_DelayedFixture", "A_DELAYED_FIXTURE;STATE=BUTTON_PRESENT")
        }, 3_000)
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); dialog?.dismiss(); dialog = null
        super.onDestroy()
    }
}
