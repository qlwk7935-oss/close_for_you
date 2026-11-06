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
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView

/**
 * D: 합성 팝업 재현 화면. 실제 결제·권한·로그인·네트워크 동작은 하지 않는다.
 * 버튼에는 리소스 ID를 붙여 정답지(ScenarioCatalog)의 correctTargetId·forbiddenTargetIds와 비교한다.
 */
class PopupScenarioActivity : Activity() {
    private lateinit var scenarioId: String
    private var dialog: Dialog? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 기기는 화면을 상태 표시줄 밑까지 그린다(edge-to-edge 강제). 안 막으면 같은 창 레이어(P34 등)의
        // 위쪽 닫기 버튼이 상태 표시줄 밑에 숨어 사람도 엔진도 못 본다(11/2 에뮬레이터 API 37에서 확인). 시스템 막대만큼 띄운다.
        findViewById<View>(android.R.id.content).setOnApplyWindowInsetsListener { content, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            content.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        scenarioId = intent.getStringExtra(EXTRA_SCENARIO) ?: "UNKNOWN"
        val scenario = ScenarioCatalog.find(scenarioId)
        // 제목에는 ID만 둔다. 시나리오 이름("광고"·"공지"·"동의" 등)이 같은 창 글자로 잡혀 분류에 섞이지 않게(10/25).
        title = scenario?.id ?: getString(R.string.unknown_scenario)
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
            "P16" -> videoAd()
            "P17" -> closeOrSkipDialog()
            "P18" -> timeSaleDialog()
            "P19" -> leavesAppDialog()
            "P20" -> adSeriesDialog(0)
            "P21" -> suspiciousDialog(R.string.prize_title, R.string.prize_body, R.id.btn_prize_claim, R.string.prize_claim)
            "P22" -> suspiciousDialog(R.string.infected_title, R.string.infected_body, R.id.btn_scan_now, R.string.scan_now)
            "P23" -> delayedCloseCoupon()
            "P24" -> webViewModal("ad-risky-back")
            "P33" -> bottomSheetDialog()
            "P34" -> fullScreenLayer()
            "P35" -> openInAppSheet()
            "P36" -> hideTodayOnlyDialog()
            ScenarioCatalog.BUTTON_MOVED_NOTICE_ID -> textNotice(scenario.note, closeOnTop = true)
            else -> if (scenario.kind == PopupKind.NOTICE) textNotice(scenario.note)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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

    /** 글 공지. [closeOnTop]이면 닫기 버튼을 제목 위로 옮긴다(C09 N08: 글자는 같고 버튼 위치만 다름). */
    private fun textNotice(body: String, closeOnTop: Boolean = false) {
        val title = text(getString(R.string.notice_title), 20f)
        val content = text(body)
        val close = button(R.id.btn_close, getString(R.string.close)) { dismiss() }
        showDialog(if (closeOnTop) column(close, title, content) else column(title, content, close))
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
    /** [fragment]는 popup_test_page.html의 시나리오(#ad = P13, #ad-risky-back = P24). */
    private fun webViewModal(fragment: String = "ad") {
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/popup_test_page.html#$fragment")
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

    /** 영상 광고(P16). 재생 시간은 매초 바뀌고, [건너뛰기]는 카운트다운이 끝난 뒤에만 생긴다. */
    private fun videoAd() {
        val root = FrameLayout(this)
        root.addView(backgroundPage())
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            // 덮개 바깥 터치가 뒤 화면으로 새지 않게 막는다.
            isClickable = true
        }
        // 빈 영상 표면(재생 소스 없음). A의 영상 표면 관측 경로를 확인하려는 것일 뿐,
        // 영상 재생이나 영상 광고 판정의 증거가 아니다.
        overlay.addView(VideoView(this).apply {
            id = R.id.video_surface
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(200), Gravity.CENTER))
        val playing = text("").apply { setTextColor(Color.WHITE) }
        overlay.addView(
            column(
                text(getString(R.string.video_title), 20f).apply { setTextColor(Color.WHITE) },
                playing,
                button(R.id.btn_ad_open, getString(R.string.ad_open)) {
                    handler.removeCallbacksAndMessages(null)
                    openAdLanding()
                },
            ),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP,
            ),
        )
        val corner = FrameLayout(this)
        val countdown = text("").apply { setTextColor(Color.WHITE) }
        corner.addView(countdown)
        overlay.addView(corner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(px(16), px(16), px(16), px(16)) })
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        setContentView(root)

        var elapsed = 0
        var skipShown = false
        val tick = object : Runnable {
            override fun run() {
                playing.text = getString(R.string.video_playing, elapsed / 60, elapsed % 60)
                val left = ScenarioCatalog.VIDEO_SKIP_AFTER_SECONDS - elapsed
                if (left > 0) {
                    countdown.text = getString(R.string.video_skip_countdown, left)
                } else if (!skipShown) {
                    skipShown = true
                    corner.removeAllViews()
                    corner.addView(button(R.id.btn_skip, getString(R.string.skip)) {
                        handler.removeCallbacksAndMessages(null)
                        root.removeView(overlay)
                    })
                }
                elapsed++
                handler.postDelayed(this, 1_000)
            }
        }
        tick.run()
    }

    /** 닫기와 건너뛰기가 함께 있는 광고(P17). 건너뛰기는 다음 광고를 열고, 닫기만 광고를 끝낸다. */
    private fun closeOrSkipDialog() {
        showDialog(column(
            text(getString(R.string.two_ads_first_title), 20f),
            text(getString(R.string.two_ads_body)),
            button(R.id.btn_skip, getString(R.string.skip)) {
                dismiss()
                showDialog(column(
                    text(getString(R.string.two_ads_second_title), 20f),
                    text(getString(R.string.two_ads_body)),
                    button(R.id.btn_close, getString(R.string.close)) { dismiss() },
                ))
            },
            button(R.id.btn_close, getString(R.string.close)) { dismiss() },
        ))
    }

    /** 건너뛰기와 상관없는 시간 문구(P18, B #20 오인 확인). 카운트다운이 끝나도 새 버튼은 생기지 않는다. */
    private fun timeSaleDialog() {
        val remaining = text("")
        showDialog(column(
            text(getString(R.string.sale_title), 20f),
            remaining,
            text(getString(R.string.sale_delivery)),
            button(R.id.btn_ad_open, getString(R.string.ad_open)) {
                handler.removeCallbacksAndMessages(null)
                openAdLanding()
            },
            button(R.id.btn_close, getString(R.string.close)) {
                handler.removeCallbacksAndMessages(null)
                dismiss()
            },
        ))
        var left = ScenarioCatalog.SALE_COUNTDOWN_SECONDS
        val tick = object : Runnable {
            override fun run() {
                remaining.text = getString(R.string.sale_remaining, left / 60, left % 60)
                if (left > 0) {
                    left--
                    handler.postDelayed(this, 1_000)
                }
            }
        }
        tick.run()
    }

    /** S16: 라벨은 '닫기'지만 누르면 앱 밖(홈 화면)으로 나간다. 네트워크·다른 앱 화면 조작은 하지 않는다. */
    private fun leavesAppDialog() {
        showDialog(column(
            text(getString(R.string.leave_title), 20f),
            text(getString(R.string.leave_body)),
            button(R.id.btn_fake_close, getString(R.string.close)) {
                dismiss()
                record("left_app")
                startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        ))
    }

    private val adSeries = listOf(
        R.string.ad_series_1_title to R.string.ad_series_1_body,
        R.string.ad_series_2_title to R.string.ad_series_2_body,
        R.string.ad_series_3_title to R.string.ad_series_3_body,
        R.string.ad_series_4_title to R.string.ad_series_4_body,
    )

    /** S17: 광고를 '닫기'로 닫으면 1초 뒤 다른 광고가 나온다. 뒤로 가기로 닫으면 거기서 멈춘다. */
    private fun adSeriesDialog(index: Int) {
        val (title, body) = adSeries[index]
        showDialog(column(
            text(getString(title), 20f),
            text(getString(body)),
            button(R.id.btn_close, getString(R.string.close)) {
                dismiss()
                if (index + 1 < adSeries.size) handler.postDelayed({ adSeriesDialog(index + 1) }, 2_000)
            },
        ))
    }

    /** S18: 사기 의심 팝업. [actionId] 버튼은 광고 페이지를 연다(앱이 누르면 실패). */
    private fun suspiciousDialog(titleRes: Int, bodyRes: Int, actionId: Int, actionLabelRes: Int) {
        showDialog(column(
            text(getString(titleRes), 20f),
            text(getString(bodyRes)),
            button(actionId, getString(actionLabelRes)) { openAdLanding() },
            button(R.id.btn_close, getString(R.string.close)) { dismiss() },
        ))
    }

    /** S20: 처음에는 누를 버튼이 없고 [ScenarioCatalog.DELAYED_CLOSE_SECONDS]초 뒤 '닫기'가 생긴다. */
    private fun delayedCloseCoupon() {
        val content = column(
            text(getString(R.string.delayed_title), 20f),
            text(getString(R.string.delayed_body)),
        )
        showDialog(content)
        handler.postDelayed({
            content.addView(button(R.id.btn_close, getString(R.string.close)) { dismiss() })
        }, ScenarioCatalog.DELAYED_CLOSE_SECONDS * 1_000L)
    }

    /** P36: 이름 있는 '닫기' 없이 '오늘 하루 보지 않기'만 닫는 버튼인 광고(F40). '자세히 보기'는 광고 페이지를 연다. */
    private fun hideTodayOnlyDialog() {
        showDialog(column(
            text(getString(R.string.ad_series_2_title), 20f),
            text(getString(R.string.ad_series_2_body)),
            button(R.id.btn_ad_open, getString(R.string.ad_open)) { openAdLanding() },
            button(R.id.btn_hide_today, getString(R.string.hide_today)) { dismiss() },
        ))
    }

    /** P33: 아래에서 올라오는 시트. Dialog 창을 화면 아래에 붙이고 가로를 꽉 채운다(BottomSheetDialog 모양). */
    private fun bottomSheetDialog() {
        val sheet = column(
            text(getString(R.string.ad_coupon_title), 20f),
            text(getString(R.string.ad_coupon_body)),
            button(R.id.btn_ad_open, getString(R.string.ad_open)) { openAdLanding() },
            button(R.id.btn_close, getString(R.string.close)) { dismiss() },
        )
        sheet.setBackgroundColor(Color.WHITE)
        val shown = showDialog(sheet)
        shown.window?.setGravity(Gravity.BOTTOM)
        shown.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /**
     * P34: Dialog·어둡게 하기 없이 같은 창에서 화면 전체를 덮는 큰 레이어(앱 자체 전면 광고).
     * 이름 있는 '닫기'가 오른쪽 위, 글자 없는 큰 그림, 광고 문구, '자세히 보기' 순서다.
     */
    private fun fullScreenLayer() {
        val root = FrameLayout(this)
        root.addView(backgroundPage())
        val layer = LinearLayout(this)
        layer.orientation = LinearLayout.VERTICAL
        layer.setBackgroundColor(0xFFFFF8E1.toInt())
        layer.setPadding(px(24), px(24), px(24), px(24))
        // 레이어 바깥 터치가 뒤 화면으로 새지 않게 막는다.
        layer.isClickable = true
        val closeParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        closeParams.gravity = Gravity.END
        layer.addView(button(R.id.btn_close, getString(R.string.close)) { root.removeView(layer) }, closeParams)
        val picture = ImageView(this)
        picture.setImageBitmap(plainImage(px(320), px(240), 0xFF2D5BFF.toInt()))
        picture.contentDescription = null
        picture.adjustViewBounds = true
        layer.addView(picture)
        layer.addView(text(getString(R.string.ad_series_1_title), 22f))
        layer.addView(text(getString(R.string.ad_series_1_body)))
        layer.addView(button(R.id.btn_ad_open, getString(R.string.ad_open)) { openAdLanding() })
        root.addView(layer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        setContentView(root)
    }

    /**
     * P35: 모바일 웹에서 흔한 "앱에서 열기 / 모바일 웹으로 볼게요" 시트. Dialog 없이 같은 창 아래에 그린다.
     * '모바일 웹으로 볼게요'가 닫는 버튼이고 '앱에서 열기'는 광고 페이지를 연다(앱이 누르면 실패).
     */
    private fun openInAppSheet() {
        val root = FrameLayout(this)
        root.addView(backgroundPage())
        val dim = FrameLayout(this)
        dim.setBackgroundColor(0x99000000.toInt())
        // 덮개 바깥 터치가 뒤 화면으로 새지 않게 막는다.
        dim.isClickable = true
        val sheet = column(
            text(getString(R.string.open_app_title), 20f),
            text(getString(R.string.open_app_body)),
            button(R.id.btn_open_app, getString(R.string.open_app)) { openAdLanding() },
            button(R.id.btn_stay_web, getString(R.string.stay_web)) { root.removeView(dim) },
        )
        sheet.setBackgroundColor(Color.WHITE)
        dim.addView(sheet, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
        ))
        root.addView(dim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        setContentView(root)
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

    /** 글자 없는 한 색 그림(P34 전면 광고 그림). */
    private fun plainImage(width: Int, height: Int, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(color)
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
