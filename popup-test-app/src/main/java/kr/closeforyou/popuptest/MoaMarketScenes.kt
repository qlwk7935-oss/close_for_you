package kr.closeforyou.popuptest

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * D: 시연 영상(가상 쇼핑몰 "모아마켓")과 같은 모양의 합성 화면·팝업(P25~P32, 10/26).
 *
 * 가상 브랜드이며 실제 상품·결제·로그인·네트워크는 없다. 팝업 문구는 영상과 같다. 버튼에는 정답지(ScenarioCatalog)와
 * 같은 리소스 ID를 붙이고, 눌리면 `시나리오:ID`만 기록한다(화면 원문·입력값은 기록하지 않음).
 */
internal class MoaMarketScenes(
    private val activity: Activity,
    private val handler: Handler,
    private val record: (String) -> Unit,
    private val openAdLanding: () -> Unit,
) {
    private var dialog: Dialog? = null
    private val animators = mutableListOf<ValueAnimator>()
    private val density = activity.resources.displayMetrics.density

    fun show(scenarioId: String) {
        when (scenarioId) {
            "P25" -> adDialog(AD_AUTUMN, labeledX = true) {}
            "P26" -> adDialog(AD_AUTUMN, labeledX = false) {}
            "P27" -> couponDialog {}
            "P28" -> noticeDialog {}
            // 광고 → 쿠폰 → 공지. 닫는 버튼(X·오늘 하루 보지 않기·닫기·확인·뒤로 가기)을 누르면 다음 팝업이 뜬다.
            "P29" -> adDialog(AD_AUTUMN, labeledX = true) {
                next { couponDialog { next { noticeDialog {} } } }
            }
            "P30" -> videoAd()
            "P31" -> adDialog(AD_WEEKEND, labeledX = true, closeAfterSeconds = ScenarioCatalog.MOA_DELAYED_CLOSE_SECONDS) {}
            "P32" -> scamDialog()
        }
    }

    fun release() {
        animators.forEach { it.cancel() }
        animators.clear()
        dialog?.dismiss()
        dialog = null
    }

    // ---- 홈 화면 ----

    fun home(): View {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(R.string.moa_brand, 24f, BRAND, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(icon(android.R.drawable.ic_popup_reminder, R.string.moa_bell))
            addView(icon(android.R.drawable.ic_menu_agenda, R.string.moa_cart))
        })
        content.addView(text(R.string.moa_search, 15f, GREY_TEXT).apply {
            background = rounded(SEARCH_BG, 24)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0)
            compoundDrawablePadding = dp(8)
        }, margins(top = 12))
        content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(R.string.moa_chip_fashion, R.string.moa_chip_appliance, R.string.moa_chip_food,
                R.string.moa_chip_living, R.string.moa_chip_camping).forEachIndexed { i, label ->
                val selected = i == 0
                addView(text(label, 14f, if (selected) Color.WHITE else DARK).apply {
                    background = rounded(if (selected) BRAND else CHIP_BG, 20)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
            }
        }, margins(top = 12))
        content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = gradient(intArrayOf(0xFF5B3FE8.toInt(), 0xFFB98AD8.toInt()), 16)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(text(R.string.moa_banner_title, 24f, Color.WHITE, bold = true))
            addView(text(R.string.moa_banner_sub, 14f, Color.WHITE))
        }, LinearLayout.LayoutParams(MATCH, dp(150)).apply { topMargin = dp(16) })
        content.addView(text(R.string.moa_today, 20f, DARK, bold = true), margins(top = 20))
        PRODUCTS.chunked(2).forEach { row ->
            content.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEachIndexed { i, p ->
                    addView(productCard(p), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                        if (i == 0) marginEnd = dp(8) else marginStart = dp(8)
                    })
                }
            }, margins(top = 12))
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        root.addView(ScrollView(activity).apply { addView(content) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(NAV_BG)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            listOf(R.string.moa_nav_home, R.string.moa_nav_category, R.string.moa_nav_search,
                R.string.moa_nav_wish, R.string.moa_nav_cart).forEachIndexed { i, label ->
                addView(text(label, 12f, DARK).apply {
                    gravity = Gravity.CENTER
                    if (i == 0) background = rounded(CHIP_BG, 16)
                    setPadding(0, dp(6), 0, dp(6))
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
        })
        return root
    }

    private fun productCard(p: Product): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(FrameLayout(activity).apply {
            background = gradient(intArrayOf(p.from, p.to), 14)
            addView(View(activity).apply {
                background = if (p.round) oval(0xE6FFFFFF.toInt()) else rounded(0xE6FFFFFF.toInt(), 12)
            }, FrameLayout.LayoutParams(dp(if (p.round) 80 else 90), dp(if (p.round) 80 else 70), Gravity.CENTER))
        }, LinearLayout.LayoutParams(MATCH, dp(140)))
        addView(text(p.name, 15f, DARK), margins(top = 8))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(text(p.discount, 15f, SALE_RED, bold = true))
            addView(text(p.price, 15f, DARK, bold = true), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        })
    }

    // ---- 광고 팝업 (P25·P26·P29·P31) ----

    private fun adDialog(spec: AdSpec, labeledX: Boolean, closeAfterSeconds: Int = 0, onClosed: () -> Unit) {
        val close = { dismiss(); onClosed() }
        val closeX = ImageButton(activity).apply {
            id = if (labeledX) R.id.btn_close else R.id.btn_unlabeled_x
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = oval(0x66000000)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            // 라벨 있는 X는 설명 "닫기"(E2), 라벨 없는 X는 설명이 없다(E3, 앱이 누르지 않음).
            contentDescription = if (labeledX) activity.getString(R.string.close) else null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { record(entry(it.id)); close() }
            visibility = if (closeAfterSeconds > 0) View.GONE else View.VISIBLE
        }
        val hero = FrameLayout(activity).apply {
            background = gradient(intArrayOf(spec.from, spec.to), 0)
            addView(FrameLayout(activity).apply {
                background = rounded(0xEEFFFFFF.toInt(), 16)
                addView(View(activity).apply { background = ring(spec.ring) },
                    FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
            }, FrameLayout.LayoutParams(dp(130), dp(90), Gravity.CENTER))
            addView(badge(R.string.moa_ad_badge, 0xFF2B2B2B.toInt(), Color.WHITE),
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(14), dp(14), 0, 0) })
            addView(closeX, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })
            addView(text(spec.percent, 18f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = oval(SALE_RED)
            }, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(14), dp(14)) })
        }
        val footer = text(R.string.moa_hide_today, 12f, Color.WHITE).apply {
            setBackgroundColor(0xFF222222.toInt())
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        if (closeAfterSeconds > 0) {
            footer.text = activity.getString(R.string.moa_close_countdown, closeAfterSeconds)
        } else {
            makeHideToday(footer, close)
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 20)
            clipToOutline = true
            addView(hero, LinearLayout.LayoutParams(MATCH, dp(190)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(18))
                addView(text(spec.title, 22f, DARK, bold = true))
                addView(text(spec.sub, 13f, GREY_TEXT), margins(top = 4))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(spec.oldPrice, 14f, GREY_TEXT).apply { paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG })
                    addView(text(spec.price, 22f, SALE_RED, bold = true), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
                }, margins(top = 6))
                addView(actionButton(R.id.btn_buy_now, R.string.moa_buy_now, SALE_RED, Color.WHITE) { openAdLanding() },
                    LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(16) })
            })
            addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        showCard(card, onBack = close)
        if (closeAfterSeconds > 0) {
            var left = closeAfterSeconds
            val tick = object : Runnable {
                override fun run() {
                    left--
                    if (left > 0) {
                        footer.text = activity.getString(R.string.moa_close_countdown, left)
                        handler.postDelayed(this, 1_000)
                    } else {
                        footer.setText(R.string.moa_hide_today)
                        makeHideToday(footer, close)
                        closeX.visibility = View.VISIBLE
                    }
                }
            }
            handler.postDelayed(tick, 1_000)
        }
    }

    private fun makeHideToday(footer: TextView, close: () -> Unit) {
        footer.id = R.id.btn_hide_today
        footer.isClickable = true
        footer.setOnClickListener { record(entry(footer.id)); close() }
    }

    // ---- 쿠폰 (P27) ----

    private fun couponDialog(onClosed: () -> Unit) {
        val close = { dismiss(); onClosed() }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(0xFFFFF8EE.toInt(), 20)
            setPadding(dp(20), dp(24), dp(20), dp(16))
            addView(text(R.string.moa_coupon_kicker, 14f, COUPON_ORANGE, bold = true))
            addView(text(R.string.moa_coupon_title, 30f, DARK, bold = true), margins(top = 4))
            addView(text(R.string.moa_coupon_condition, 13f, GREY_TEXT), margins(top = 4))
            addView(View(activity).apply {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.LINE
                    setStroke(dp(2), 0xFFF2C894.toInt(), dp(6).toFloat(), dp(5).toFloat())
                }
            }, LinearLayout.LayoutParams(MATCH, dp(16)).apply { topMargin = dp(16) })
            addView(text(R.string.moa_coupon_valid, 14f, GREY_TEXT), margins(top = 12))
            addView(actionButton(R.id.btn_coupon_get, R.string.moa_coupon_get, COUPON_ORANGE, Color.WHITE) { close() },
                LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(12) })
            addView(textButton(R.id.btn_close, R.string.close, GREY_TEXT) { close() }, margins(top = 8))
        }
        showCard(card, onBack = close)
    }

    // ---- 점검 공지 (P28) ----

    private fun noticeDialog(onClosed: () -> Unit) {
        val close = { dismiss(); onClosed() }
        val info = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFEDE8F7.toInt(), 12)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(infoRow(R.string.moa_notice_when_label, R.string.moa_notice_when))
            addView(infoRow(R.string.moa_notice_what_label, R.string.moa_notice_what), margins(top = 10))
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFF6F2FC.toInt(), 20)
            setPadding(dp(22), dp(20), dp(22), dp(20))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(R.string.moa_notice_title, 20f, DARK, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(textButton(R.id.btn_close, R.string.close, GREY_TEXT) { close() })
            })
            addView(text(R.string.moa_notice_body, 14f, DARK), margins(top = 10))
            addView(info, margins(top = 14))
            addView(actionButton(R.id.btn_confirm, R.string.moa_confirm, NOTICE_PURPLE, Color.WHITE) { close() },
                LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(18) })
        }
        showCard(card, onBack = close)
    }

    private fun infoRow(label: Int, value: Int): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(text(label, 14f, DARK, bold = true), LinearLayout.LayoutParams(dp(52), WRAP))
        addView(text(value, 14f, DARK), LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    // ---- 움직이는 영상 광고 (P30) ----

    private fun videoAd() {
        val headline = text(R.string.moa_video_title, 26f, Color.WHITE, bold = true)
        val art = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF8E2DE2.toInt(), 0xFFF24E6B.toInt()))
        val screen = FrameLayout(activity).apply {
            background = art
            addView(headline, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(24) })
            addView(FrameLayout(activity).apply {
                background = rounded(0xEEFFFFFF.toInt(), 16)
                addView(View(activity).apply { background = ring(0xFFE0336E.toInt()) },
                    FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER))
            }, FrameLayout.LayoutParams(dp(120), dp(96), Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(28) })
            addView(badge(R.string.moa_ad_badge, 0xFFFFC107.toInt(), DARK),
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })
        }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = AD_LENGTH_SECONDS
            progressTintList = ColorStateList.valueOf(0xFFFF9800.toInt())
        }
        val time = text("", 13f, Color.WHITE)
        val skipSlot = FrameLayout(activity)
        val countdown = text("", 14f, Color.WHITE).apply {
            background = rounded(0xFF333333.toInt(), 6)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        skipSlot.addView(countdown)
        val caption = text(R.string.moa_video_caption, 15f, Color.WHITE)
        val learnMore = actionButton(R.id.btn_ad_open, R.string.moa_learn_more, Color.WHITE, DARK) { openAdLanding() }
        val details = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(caption, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(learnMore, LinearLayout.LayoutParams(WRAP, dp(44)))
        }
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(screen, LinearLayout.LayoutParams(MATCH, dp(220)).apply { topMargin = dp(48) })
            addView(progress, LinearLayout.LayoutParams(MATCH, dp(6)))
            addView(time, margins(start = 16, top = 8))
            addView(details, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(16), dp(16), dp(16), 0) })
            addView(skipSlot, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END; setMargins(0, dp(12), dp(16), 0) })
        }
        showFullScreen(page)

        // 움직임: 배경색이 천천히 바뀐다(영상 재생 흉내, 실제 영상·네트워크 없음).
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2_000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                val f = it.animatedValue as Float
                art.colors = intArrayOf(blend(0xFF8E2DE2.toInt(), 0xFFFF6A3D.toInt(), f), blend(0xFFF24E6B.toInt(), 0xFF8E2DE2.toInt(), f))
                headline.scaleX = 1f + 0.08f * f
                headline.scaleY = 1f + 0.08f * f
            }
            start()
        }.also { animators += it }

        var elapsed = 0
        val tick = object : Runnable {
            override fun run() {
                if (dialog == null) return
                val left = AD_LENGTH_SECONDS - elapsed
                time.text = activity.getString(R.string.moa_video_time, left / 60, left % 60)
                progress.progress = elapsed
                if (elapsed == 3) headline.setText(R.string.moa_video_title2)
                val skipIn = ScenarioCatalog.MOA_VIDEO_SKIP_AFTER_SECONDS - elapsed
                if (skipIn > 0) {
                    countdown.text = activity.getString(R.string.moa_skip_countdown, skipIn)
                } else if (skipIn == 0) {
                    skipSlot.removeAllViews()
                    skipSlot.addView(actionButton(R.id.btn_skip, R.string.moa_skip_now, 0xFFFFC107.toInt(), DARK) {
                        handler.removeCallbacks(this)
                        mainVideo(screen, headline, time, progress, details, skipSlot)
                    })
                }
                if (left > 0) {
                    elapsed++
                    handler.postDelayed(this, 1_000)
                }
            }
        }
        tick.run()
    }

    /** 건너뛰기 뒤 본편. 광고 표시와 버튼은 사라진다. */
    private fun mainVideo(screen: FrameLayout, headline: TextView, time: TextView, progress: ProgressBar,
                          details: LinearLayout, skipSlot: FrameLayout) {
        animators.forEach { it.cancel() }
        animators.clear()
        screen.removeAllViews()
        screen.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1E6F7A.toInt(), 0xFF7BC67E.toInt()))
        screen.addView(text(R.string.moa_main_label, 22f, Color.WHITE, bold = true), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        headline.text = ""
        skipSlot.removeAllViews()
        details.removeAllViews()
        details.orientation = LinearLayout.VERTICAL
        details.addView(text(R.string.moa_main_title, 18f, Color.WHITE, bold = true))
        details.addView(text(R.string.moa_main_meta, 13f, GREY_TEXT))
        progress.max = MAIN_LENGTH_SECONDS
        var played = 0
        val tick = object : Runnable {
            override fun run() {
                if (dialog == null) return
                time.text = activity.getString(R.string.moa_main_time, played / 60, played % 60)
                progress.progress = played
                if (played < MAIN_LENGTH_SECONDS) {
                    played++
                    handler.postDelayed(this, 1_000)
                }
            }
        }
        tick.run()
    }

    // ---- 사기 의심 (P32) ----

    private fun scamDialog() {
        val close = { dismiss() }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 20)
            clipToOutline = true
            addView(FrameLayout(activity).apply {
                background = gradient(intArrayOf(0xFFFF5F2E.toInt(), 0xFFFFB12B.toInt()), 0)
                addView(text("!", 44f, SALE_RED, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = oval(Color.WHITE)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))
            }, LinearLayout.LayoutParams(MATCH, dp(170)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(18))
                addView(text(R.string.prize_title, 22f, DARK, bold = true))
                addView(text(R.string.prize_body, 14f, GREY_TEXT), margins(top = 6))
                addView(actionButton(R.id.btn_prize_claim, R.string.prize_claim, SALE_RED, Color.WHITE) { openAdLanding() },
                    LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(16) })
            })
            addView(textButton(R.id.btn_close, R.string.close, Color.WHITE) { close() }.apply {
                setBackgroundColor(0xFF222222.toInt())
                gravity = Gravity.START
                setPadding(dp(20), dp(14), dp(20), dp(14))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        showCard(card, onBack = close)
    }

    // ---- 공통 ----

    private fun next(show: () -> Unit) {
        handler.postDelayed({ if (!activity.isFinishing) show() }, CHAIN_DELAY_MILLIS)
    }

    private fun showCard(content: View, onBack: () -> Unit) {
        dismiss()
        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(content)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setCancelable(true)
            setOnCancelListener { record("system_back"); dialog = null; onBack() }
            show()
            window?.setLayout(
                minOf(activity.resources.displayMetrics.widthPixels - dp(32), dp(480)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun showFullScreen(content: View) {
        dismiss()
        dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(content)
            setCancelable(true)
            setOnCancelListener {
                record("system_back")
                animators.forEach { it.cancel() }
                animators.clear()
                dialog = null
            }
            show()
        }
    }

    private fun dismiss() {
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
    }

    private fun entry(id: Int): String = activity.resources.getResourceEntryName(id)

    private fun actionButton(viewId: Int, label: Int, bg: Int, fg: Int, onClick: () -> Unit): Button = Button(activity).apply {
        id = viewId
        setText(label)
        isAllCaps = false
        textSize = 16f
        setTextColor(fg)
        background = rounded(bg, 12)
        stateListAnimator = null
        setOnClickListener { record(entry(viewId)); onClick() }
    }

    private fun textButton(viewId: Int, label: Int, color: Int, onClick: () -> Unit): TextView = text(label, 13f, color).apply {
        id = viewId
        isClickable = true
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { record(entry(viewId)); onClick() }
    }

    private fun badge(label: Int, bg: Int, fg: Int): TextView = text(label, 11f, fg, bold = true).apply {
        background = rounded(bg, 4)
        setPadding(dp(6), dp(3), dp(6), dp(3))
    }

    private fun icon(iconRes: Int, description: Int): ImageView = ImageView(activity).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(DARK)
        contentDescription = activity.getString(description)
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun text(res: Int, size: Float, color: Int, bold: Boolean = false): TextView =
        text(activity.getString(res), size, color, bold)

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    // GradientDrawable에도 color 속성이 있어 인자 이름을 fill로 둔다(apply 안에서 가려지지 않게).
    private fun rounded(fill: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun gradient(colors: IntArray, radiusDp: Int) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = dp(radiusDp).toFloat() }

    private fun oval(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    private fun ring(strokeColor: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
        setStroke(dp(14), strokeColor)
    }

    private fun margins(start: Int = 0, top: Int = 0) = LinearLayout.LayoutParams(WRAP, WRAP).apply {
        marginStart = dp(start)
        topMargin = dp(top)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun blend(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
    )

    private class AdSpec(
        val title: Int, val sub: Int, val oldPrice: Int, val price: Int, val percent: Int,
        val from: Int, val to: Int, val ring: Int,
    )

    private class Product(val name: Int, val discount: Int, val price: Int, val from: Int, val to: Int, val round: Boolean)

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val CHAIN_DELAY_MILLIS = 700L
        const val AD_LENGTH_SECONDS = 15
        const val MAIN_LENGTH_SECONDS = 200

        val BRAND = 0xFF4F35D2.toInt()
        val BG = 0xFFFBF7FF.toInt()
        val NAV_BG = 0xFFF1ECF8.toInt()
        val SEARCH_BG = 0xFFEAE6EF.toInt()
        val CHIP_BG = 0xFFE6E1EC.toInt()
        val DARK = 0xFF1C1B1F.toInt()
        val GREY_TEXT = 0xFF6B6870.toInt()
        val SALE_RED = 0xFFF2384F.toInt()
        val COUPON_ORANGE = 0xFFFF7A00.toInt()
        val NOTICE_PURPLE = 0xFF6650A8.toInt()

        val AD_AUTUMN = AdSpec(
            R.string.moa_ad1_title, R.string.moa_ad1_sub, R.string.moa_ad1_old_price, R.string.moa_ad1_price,
            R.string.moa_ad1_percent, 0xFFFF5A36.toInt(), 0xFFE9408F.toInt(), 0xFFE04A8A.toInt(),
        )
        val AD_WEEKEND = AdSpec(
            R.string.moa_ad2_title, R.string.moa_ad2_sub, R.string.moa_ad2_old_price, R.string.moa_ad2_price,
            R.string.moa_ad2_percent, 0xFF12A87A.toInt(), 0xFF5EE38E.toInt(), 0xFF4CD486.toInt(),
        )
        val PRODUCTS = listOf(
            Product(R.string.moa_p1_name, R.string.moa_p1_discount, R.string.moa_p1_price, 0xFF7C83DB.toInt(), 0xFF8FE0E0.toInt(), true),
            Product(R.string.moa_p2_name, R.string.moa_p2_discount, R.string.moa_p2_price, 0xFFF59E0B.toInt(), 0xFFFFD000.toInt(), false),
            Product(R.string.moa_p3_name, R.string.moa_p3_discount, R.string.moa_p3_price, 0xFFFF6B6B.toInt(), 0xFFFFB86C.toInt(), true),
            Product(R.string.moa_p4_name, R.string.moa_p4_discount, R.string.moa_p4_price, 0xFF10A37F.toInt(), 0xFF34E07A.toInt(), false),
        )
    }
}
