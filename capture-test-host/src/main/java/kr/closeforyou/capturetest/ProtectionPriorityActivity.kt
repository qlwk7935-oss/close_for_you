package kr.closeforyou.capturetest

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Independent, testOnly host. Never changes the original fourteen scenarios or supplies fake bounds. */
class ProtectionPriorityActivity : Activity() {
    lateinit var first: TextView
        private set
    lateinit var later: TextView
        private set
    lateinit var scroller: ScrollView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fixture = PriorityFixture.entries.singleOrNull { it.name == intent.getStringExtra("fixture") }
        if (fixture == null) { finish(); return }
        title = PriorityFixtureText.ROW
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        page.addView(TextView(this).apply { text = PriorityFixtureText.ROW })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(35) { index ->
            val row = if (index == 31 && fixture == PriorityFixture.EDITABLE) EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                showSoftInputOnFocus = false
                hint = null
                isFocusable = false
                isFocusableInTouchMode = false
            } else TextView(this)
            row.apply {
                text = if (index == 31 && fixture == PriorityFixture.SENSITIVE_TEXT) PriorityFixtureText.SENSITIVE
                    else if (index == 31 && fixture in setOf(PriorityFixture.PASSWORD, PriorityFixture.EDITABLE)) ""
                    else PriorityFixtureText.ROW
                contentDescription = PriorityFixtureText.ROW
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                if (index == 30) { id = R.id.priority_first; first = this }
                if (index == 31) {
                    id = R.id.priority_later; later = this
                    if (fixture == PriorityFixture.PASSWORD) transformationMethod = PasswordTransformationMethod.getInstance()
                }
            }
            rows.addView(row, LinearLayout.LayoutParams(-1, (80 * resources.displayMetrics.density).toInt()))
        }
        scroller = ScrollView(this).apply { addView(rows) }
        page.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        page.requestFocus()
    }
}

enum class PriorityFixture { CONTROL, PASSWORD, EDITABLE, SENSITIVE_TEXT }

/** Fixed synthetic vocabulary; no credentials or number strings. Never printed by the test. */
object PriorityFixtureText {
    const val ROW = "합성 항목"
    const val STIMULUS = "합성 관측"
    const val SENSITIVE = "합성 비밀번호 안내"
}
