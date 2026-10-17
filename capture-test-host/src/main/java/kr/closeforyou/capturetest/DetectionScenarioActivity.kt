package kr.closeforyou.capturetest

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kr.closeforyou.synthetictest.DetectionScenario

/** Owned, fixed fixtures only. Not packaged in the production app; never reads user data. */
class DetectionScenarioActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var rows: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var heading: TextView
    private var dialog: Dialog? = null
    private var toast: Toast? = null
    private var editor: EditText? = null
    private var manualStep = 0
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "합성 문서 창"
        root = FrameLayout(this)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        heading = TextView(this).apply { text = "합성 일반 문서"; textSize = 22f }
        page.addView(heading)
        page.addView(Button(this).apply {
            text = "실험 실행"
            setOnClickListener {
                val chosen = DetectionScenario.entries.firstOrNull { it.name == intent.getStringExtra("scenario") }
                    ?: DetectionScenario.DIALOG
                val step = if (chosen == DetectionScenario.SEQUENCE_FIRST && manualStep++ > 0)
                    DetectionScenario.SEQUENCE_SECOND else chosen
                performScenario(step)
            }
        })
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            repeat(35) { index -> addView(TextView(this@DetectionScenarioActivity).apply {
                text = getString(R.string.synthetic_document_row, index + 1); textSize = 20f; setPadding(16, 24, 16, 24)
            }) }
        }
        scroller = ScrollView(this).apply { addView(rows) }
        page.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    /** Called by the host's own button or its own instrumentation, never by the service. */
    fun performScenario(scenario: DetectionScenario) {
        when (scenario) {
            DetectionScenario.DIALOG, DetectionScenario.SEQUENCE_FIRST, DetectionScenario.SEQUENCE_SECOND -> {
                dialog?.dismiss()
                showDialog("합성 안내", "점검 공지 안내")
            }
            DetectionScenario.PERMISSION_PROMPT -> showDialog("합성 권한 요청", "권한 허용 여부를 직접 선택하세요")
            DetectionScenario.INLINE_OVERLAY, DetectionScenario.UNMARKED_OVERLAY -> {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.WHITE)
                    elevation = 8f
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    if (scenario == DetectionScenario.INLINE_OVERLAY) accessibilityPaneTitle = "합성 안내"
                    addView(TextView(this@DetectionScenarioActivity).apply { text = "합성 점검 공지"; textSize = 22f })
                    addView(Button(this@DetectionScenarioActivity).apply { text = "그대로 두기" })
                }
                root.addView(card, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * .75).toInt(), 320, Gravity.CENTER))
            }
            DetectionScenario.SCREEN_TRANSITION -> startActivity(Intent(this, SyntheticPlainActivity::class.java))
            DetectionScenario.SCROLL -> scroller.scrollTo(0, 900)
            DetectionScenario.LAYOUT_CHANGE -> {
                heading.text = "합성 문서 배치 변경"
                rows.getChildAt(0).setPadding(16, 70, 16, 70)
            }
            DetectionScenario.TOAST -> {
                toast = Toast.makeText(this, "합성 상태 안내", Toast.LENGTH_SHORT).also { it.show() }
            }
            DetectionScenario.LOGIN, DetectionScenario.PAYMENT_INPUT, DetectionScenario.KEYBOARD -> {
                val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                content.addView(TextView(this).apply { text = when (scenario) {
                    DetectionScenario.LOGIN -> "합성 로그인"
                    DetectionScenario.PAYMENT_INPUT -> "합성 카드 입력"
                    else -> "합성 입력 시험"
                } })
                editor = EditText(this).apply {
                    hint = "합성 입력"
                    inputType = if (scenario == DetectionScenario.LOGIN)
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
                    // Empty throughout. Never type credentials, numbers, or user text into this fixture.
                    showSoftInputOnFocus = scenario == DetectionScenario.KEYBOARD
                }
                content.addView(editor)
                root.removeAllViews()
                root.addView(content, FrameLayout.LayoutParams(-1, -1))
                if (scenario == DetectionScenario.KEYBOARD) editor?.apply {
                    requestFocus()
                    postDelayed({ if (!closed) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }, 400)
                }
            }
            DetectionScenario.UNCERTAIN_CONTENT -> {
                root.removeAllViews()
                root.addView(TextView(this).apply { setText(R.string.synthetic_uncertain_content) })
            }
        }
    }
    private fun showDialog(title: String, body: String) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@DetectionScenarioActivity).apply { text = body; textSize = 22f })
            addView(Button(this@DetectionScenarioActivity).apply { text = "그대로 두기" })
        }
        dialog = Dialog(this).apply {
            setTitle(title); setContentView(content); window?.setWindowAnimations(0); show()
            window?.setLayout(minOf(resources.displayMetrics.widthPixels - 80, 1_200), 420)
        }
    }
    @Suppress("DEPRECATION")
    fun ownedTargetWindowId(): Int {
        val decor = dialog?.takeIf { it.isShowing }?.window?.decorView ?: window.decorView
        val node = decor.createAccessibilityNodeInfo()
        return try { node.windowId } finally { node.recycle() }
    }
    fun ownedImeVisible(): Boolean = window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
    override fun onDestroy() {
        closed = true
        toast?.cancel()
        editor?.let { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) }
        editor = null
        dialog?.dismiss(); dialog = null
        super.onDestroy()
    }
}

/** Actual second Activity: a normal transition, not a Dialog or custom popup class. */
class SyntheticPlainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "합성 다음 문서"
        setContentView(TextView(this).apply { text = "합성 다음 페이지의 일반 내용"; textSize = 24f })
    }
    @Suppress("DEPRECATION")
    fun ownedTargetWindowId(): Int {
        val node = window.decorView.createAccessibilityNodeInfo()
        return try { node.windowId } finally { node.recycle() }
    }
}
