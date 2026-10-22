package kr.closeforyou.capturetest

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView

/** Independent testOnly host: ordinary text first, one real Android Dialog on an explicit host call. */
class MinimalDialogActivity : Activity() {
    var ownedDialog: Dialog? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = BASE_TEXT
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(TextView(this).apply {
            text = BASE_TEXT
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        })
    }

    /** Called on main by this host's instrumentation, never by AccessibilityService. No click API. */
    fun showOwnedDialog() {
        check(ownedDialog == null && window.decorView.isLaidOut && window.decorView.width > 0) { "DIALOG_FIXTURE_NOT_READY" }
        val content = TextView(this).apply {
            text = DIALOG_TEXT
            textSize = 20f
            gravity = Gravity.CENTER
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        ownedDialog = Dialog(this).apply {
            setTitle(DIALOG_TEXT)
            setContentView(content)
            setCancelable(false)
            window?.setWindowAnimations(0)
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        ownedDialog?.show()
        // Control only our own fixture layout. Never alter any observed accessibility bounds.
        ownedDialog?.window?.setLayout(window.decorView.width * 2 / 3, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun closeOwnedDialog() {
        val owned = ownedDialog
        ownedDialog = null
        owned?.dismiss()
    }

    override fun onDestroy() {
        try { closeOwnedDialog() } finally { super.onDestroy() }
    }

    companion object {
        const val BASE_TEXT = "합성 일반 문서"
        const val DIALOG_TEXT = "합성 점검 공지"
    }
}
