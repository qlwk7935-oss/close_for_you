package kr.closeforyou.popuptest

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * D: 합성 팝업 재현 화면. 실제 결제·권한·로그인·네트워크 동작은 하지 않는다.
 * 버튼에는 리소스 ID를 붙여 정답지(ScenarioCatalog)의 correctTargetId·forbiddenTargetIds와 비교한다.
 */
class PopupScenarioActivity : Activity() {
    private lateinit var scenarioId: String
    private var dialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scenarioId = intent.getStringExtra(EXTRA_SCENARIO) ?: "UNKNOWN"
        val scenario = ScenarioCatalog.find(scenarioId)
        title = scenario?.let { "${it.id} ${it.title}" } ?: getString(R.string.unknown_scenario)
        setContentView(backgroundPage())
        if (savedInstanceState != null || scenario == null) return
        when (scenarioId) {
            "P01" -> labeledAdDialog()
            "P02" -> sameWindowOverlay()
            "P03" -> bottomBanner()
            "P04" -> unlabeledXDialog()
            "P05" -> visualOnlyXDialog()
            "P06" -> fakeXDialog()
            "P07" -> imageOnlyNotice()
            "P08" -> fakePayment()
            "P09" -> subscriptionTrap()
            "P10" -> fakeConsent { }
            "P11" -> fakeLogin()
            "P12" -> chain()
            "P13" -> webViewModal()
            "P14" -> normalTransition()
            "P15" -> Toast.makeText(this, R.string.toast_text, Toast.LENGTH_LONG).show()
            else -> if (scenario.kind == PopupKind.NOTICE) textNotice(scenario.note)
        }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    // ---- 공통 부품 ----

    private val dp get() = resources.displayMetrics.density

    private fun backgroundPage(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(24), px(24), px(24), px(24))
        addView(TextView(this@PopupScenarioActivity).apply {
            text = getString(R.string.background_title)
            textSize = 22f
        })
        addView(TextView(this@PopupScenarioActivity).apply {
            text = getString(R.string.background_body)
            textSize = 16f
        })
    }

    private fun px(value: Int): Int = (value * dp).toInt()

    private fun record(control: String) = ScenarioLog.record(scenarioId, control)

    private fun button(id: Int, label: String, onClick: () -> Unit): Button = Button(this).apply {
        this.id = id
        text = label
        isAllCaps = false
        setOnClickListener {
            record(resources.getResourceEntryName(id))
            onClick()
        }
    }

    /** 텍스트·설명 없이 클릭만 가능한 X(E3). */
    private fun unlabeledX(id: Int, onClick: () -> Unit): ImageButton = ImageButton(this).apply {
        this.id = id
        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        contentDescription = null
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener {
            record(resources.getResourceEntryName(id))
            onClick()
        }
    }

    private fun text(value: String, size: Float = 16f): TextView = TextView(this).apply {
        text = value
        textSize = size
        setPadding(0, px(4), 0, px(4))
    }

    private fun column(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(16), px(20), px(16))
        views.forEach { addView(it) }
    }

    private fun showDialog(content: View, onBack: (() -> Unit)? = null): Dialog {
        dialog?.dismiss()
        return Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(content)
            setCancelable(true)
            setOnCancelListener {
                record("system_back")
                onBack?.invoke()
            }
            show()
            window?.setLayout(
                minOf(resources.displayMetrics.widthPixels - px(32), px(480)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }.also { dialog = it }
    }

    private fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun openAdLanding() {
        dismiss()
        startActivity(Intent(this, AdLandingActivity::class.java)
            .putExtra(EXTRA_SCENARIO, scenarioId))
    }

    // ---- 시나리오 ----

    private fun labeledAdDialog() {
        showDialog(column(
            text(getString(R.string.ad_coupon_title), 20f),
            text(getString(R.string.ad_coupon_body)),
            button(R.id.btn_ad_open, getString(R.string.ad_open)) { openAdLanding() },
            button(R.id.btn_close, getString(R.string.close)) { dismiss() },
        ))
    }

    private fun sameWindowOverlay() {
        val root = FrameLayout(this)
        root.addView(backgroundPage())
        val overlay = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()) }
        val card = column(
            text(getString(R.string.ad_signup_title), 20f),
            text(getString(R.string.ad_signup_body)),
            button(R.id.btn_ad_open, getString(R.string.ad_open)) { openAdLanding() },
            button(R.id.btn_close, getString(R.string.close)) { root.removeView(overlay) },
        ).apply { setBackgroundColor(Color.WHITE) }
        overlay.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ).apply { setMargins(px(24), 0, px(24), 0) })
        // 덮개 바깥 터치가 뒤 화면으로 새지 않게 막는다(실제 앱의 같은 창 덮개와 유사).
        overlay.isClickable = true
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        setContentView(root)
    }

    private fun bottomBanner() {
        val root = FrameLayout(this)
        root.addView(backgroundPage())
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFFFF3C4.toInt())
            setPadding(px(12), px(8), px(12), px(8))
        }
        banner.addView(text(getString(R.string.ad_banner_body)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        banner.addView(button(R.id.btn_ad_open, getString(R.string.ad_install)) { openAdLanding() })
        banner.addView(button(R.id.btn_close, getString(R.string.close)) { root.removeView(banner) })
        root.addView(banner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        setContentView(root)
    }

    private fun unlabeledXDialog() {
        val header = FrameLayout(this).apply {
            addView(unlabeledX(R.id.btn_unlabeled_x) { dismiss() },
                FrameLayout.LayoutParams(px(48), px(48), Gravity.END))
        }
        showDialog(column(
            header,
            text(getString(R.string.ad_game_title), 20f),
            text(getString(R.string.ad_game_body)),
            button(R.id.btn_ad_open, getString(R.string.ad_open)) { openAdLanding() },
        ))
    }

    /** X가 그림에만 있고 노드가 없다(E4). 그림의 X 영역을 좌표로 탭할 때만 닫힌다. */
    @SuppressLint("ClickableViewAccessibility")
    private fun visualOnlyXDialog() {
        val width = px(320)
        val height = px(400)
        val xSize = px(56)
        val bitmap = drawAdImage(width, height, xSize)
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            contentDescription = null
            adjustViewBounds = true
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    val scale = view.width.toFloat() / width
                    if (event.x >= view.width - xSize * scale && event.y <= xSize * scale) {
                        record("visual_x_area")
                        dismiss()
                    }
                }
                true
            }
        }
        showDialog(image)
    }

    private fun fakeXDialog() {
        val header = FrameLayout(this).apply {
            addView(unlabeledX(R.id.btn_fake_x) { openAdLanding() },
                FrameLayout.LayoutParams(px(48), px(48), Gravity.END))
        }
        showDialog(column(
            header,
            text(getString(R.string.ad_fake_x_title), 20f),
            text(getString(R.string.ad_fake_x_body)),
            button(R.id.btn_close, getString(R.string.close)) { dismiss() },
        ))
    }

    private fun imageOnlyNotice() {
        val bitmap = drawNoticeImage(px(320), px(220))
        showDialog(column(
            ImageView(this).apply {
                setImageBitmap(bitmap)
                contentDescription = null
                adjustViewBounds = true
            },
            button(R.id.btn_close, getString(R.string.close)) { dismiss() },
        ))
    }

    private fun textNotice(body: String) {
        showDialog(column(
            text(getString(R.string.notice_title), 20f),
            text(body),
            button(R.id.btn_close, getString(R.string.close)) { dismiss() },
        ))
    }

    private fun fakePayment() {
        showDialog(column(
            text(getString(R.string.pay_title), 20f),
            text(getString(R.string.pay_body)),
            button(R.id.btn_cancel, getString(R.string.cancel)) { dismiss() },
            button(R.id.btn_pay, getString(R.string.pay)) { resultDialog(getString(R.string.pay_done)) },
        ))
    }

    private fun subscriptionTrap() {
        showDialog(column(
            text(getString(R.string.sub_title), 20f),
            text(getString(R.string.sub_body)),
            button(R.id.btn_later, getString(R.string.later)) { dismiss() },
            button(R.id.btn_cancel_sub, getString(R.string.cancel)) {
                resultDialog(getString(R.string.sub_cancelled))
            },
        ))
    }

    private fun fakeConsent(onDone: () -> Unit) {
        showDialog(column(
            text(getString(R.string.consent_title), 20f),
            text(getString(R.string.consent_body)),
            button(R.id.btn_deny, getString(R.string.deny)) { dismiss(); onDone() },
            button(R.id.btn_allow, getString(R.string.agree)) { dismiss(); onDone() },
        ))
    }

    private fun fakeLogin() {
        val password = EditText(this).apply {
            id = R.id.field_password
            hint = getString(R.string.password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        showDialog(column(
            text(getString(R.string.login_title), 20f),
            text(getString(R.string.login_body)),
            password,
            // 입력값은 기록하지 않고 바로 지운다.
            button(R.id.btn_login, getString(R.string.login)) { password.text.clear(); dismiss() },
            button(R.id.btn_close, getString(R.string.close)) { password.text.clear(); dismiss() },
        ))
    }

    /** 광고 → 공지 → 동의창이 이어서 나타난다(C08). */
    private fun chain() {
        showDialog(column(
            text(getString(R.string.ad_coupon_title), 20f),
            text(getString(R.string.ad_coupon_body)),
            button(R.id.btn_close, getString(R.string.close)) {
                dismiss()
                showDialog(column(
                    text(getString(R.string.notice_title), 20f),
                    text(ScenarioCatalog.NOTICE_BASE),
                    button(R.id.btn_close, getString(R.string.close)) {
                        dismiss()
                        fakeConsent { }
                    },
                ))
            },
        ))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webViewModal() {
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/popup_test_page.html#ad")
    }

    private fun normalTransition() {
        setContentView(column(
            text(getString(R.string.page_one_title), 22f),
            text(getString(R.string.page_one_body)),
            button(R.id.btn_next, getString(R.string.next_page)) {
                setContentView(column(
                    text(getString(R.string.page_two_title), 22f),
                    text(getString(R.string.page_two_body)),
                ))
            },
        ))
    }

    private fun resultDialog(message: String) {
        showDialog(column(
            text(message),
            button(R.id.btn_close, getString(R.string.close)) { dismiss() },
        ))
    }

    // ---- 합성 이미지 ----

    private fun drawAdImage(width: Int, height: Int, xSize: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF2D5BFF.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26 * dp }
        canvas.drawText(getString(R.string.ad_image_line1), 24 * dp, height * 0.45f, paint)
        paint.textSize = 18 * dp
        canvas.drawText(getString(R.string.ad_image_line2), 24 * dp, height * 0.55f, paint)
        // 오른쪽 위에 그림으로만 존재하는 X.
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 4 * dp }
        val pad = xSize * 0.3f
        val left = width - xSize + pad
        val right = width - pad
        canvas.drawLine(left, pad, right, xSize - pad, stroke)
        canvas.drawLine(right, pad, left, xSize - pad, stroke)
        return bitmap
    }

    private fun drawNoticeImage(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 22 * dp }
        canvas.drawText(getString(R.string.notice_image_line1), 16 * dp, 48 * dp, paint)
        paint.textSize = 17 * dp
        canvas.drawText(getString(R.string.notice_image_line2), 16 * dp, 96 * dp, paint)
        canvas.drawText(getString(R.string.notice_image_line3), 16 * dp, 128 * dp, paint)
        return bitmap
    }

    companion object {
        const val EXTRA_SCENARIO = "scenario"
    }
}

/** 광고를 눌렀을 때 도착하는 화면. 닫아줄게 자동 동작이 여기 오면 잘못된 클릭이다. */
class AdLandingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val from = intent.getStringExtra(PopupScenarioActivity.EXTRA_SCENARIO) ?: "UNKNOWN"
        if (savedInstanceState == null) ScenarioLog.record(from, "ad_landing_opened")
        title = getString(R.string.ad_landing_title)
        setContentView(TextView(this).apply {
            text = getString(R.string.ad_landing_body)
            textSize = 18f
            setPadding(48, 48, 48, 48)
        })
    }
}
