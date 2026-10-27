package kr.closeforyou.accessibility.testing

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kr.closeforyou.R
import kr.closeforyou.accessibility.AccessibilityRuntime
import kr.closeforyou.accessibility.integration.DebugDelayedAutoClose
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.ui.voice.settings.UiVoicePreferences
import kr.closeforyou.ui.voice.settings.autoCloseConsentNow

/** Debug-only developer UI. Intent extras cannot arm execution. This activity never changes
 * collection consent, allowed apps, accessibility settings, presets or the auto-close switch. */
class DelayedAutoCloseActivity : Activity() {
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.a_delayed_auto_title)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        column.addView(TextView(this).apply {
            setText(R.string.a_delayed_auto_explanation)
        })
        status = TextView(this).apply { id = R.id.a_delayed_auto_state }
        column.addView(status)
        column.addView(Button(this).apply {
            id = R.id.a_enable_delayed_auto; setText(R.string.a_delayed_auto_enable)
            setOnClickListener {
                val refusal = prerequisite()
                if (refusal != null) { status.text = getString(R.string.a_delayed_auto_status, refusal); return@setOnClickListener }
                AlertDialog.Builder(this@DelayedAutoCloseActivity)
                    .setTitle(R.string.a_delayed_auto_confirm_title)
                    .setMessage(R.string.a_delayed_auto_confirm_body)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.a_delayed_auto_confirm) { _, _ ->
                        val rejected = prerequisite()
                        if (rejected != null) status.text = getString(R.string.a_delayed_auto_status, rejected)
                        else { DebugDelayedAutoClose.arm(); finish() }
                    }.show()
            }
        })
        column.addView(Button(this).apply {
            id = R.id.a_disable_delayed_auto; setText(R.string.a_delayed_auto_disable)
            setOnClickListener { DebugDelayedAutoClose.userOff(); status.text = getString(R.string.a_delayed_auto_status, "OFF") }
        })
        setContentView(column)
    }
    override fun onResume() { super.onResume(); status.text = getString(R.string.a_delayed_auto_status,
        prerequisite() ?: if (DebugDelayedAutoClose.ticket() != null) "ARMED" else "OFF") }
    private fun prerequisite(): String? {
        val core = CorePreferences.get(this).currentSettings()
        val ui = UiVoicePreferences.get(this).current()
        val consent = ui.autoCloseConsentNow(live = true)
        return when {
            !AccessibilityRuntime.status.value.connected -> "SERVICE_NOT_CONNECTED"
            !core.consentGranted -> "COLLECTION_CONSENT_REQUIRED"
            core.paused -> "ANALYSIS_PAUSED"
            "kr.closeforyou.popuptest" !in core.allowedPackages || "kr.closeforyou.popuptest" in ui.helpAppsExcluded -> "SYNTHETIC_APP_NOT_ALLOWED"
            !consent.granted || consent.autoCloseKinds.none { it == PopupClassification.COUPON || it == PopupClassification.ADVERTISEMENT } -> "AD_COUPON_CONSENT_REQUIRED"
            else -> null
        }
    }
}
