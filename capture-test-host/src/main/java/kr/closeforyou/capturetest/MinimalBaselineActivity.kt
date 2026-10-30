package kr.closeforyou.capturetest

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Isolation control only. Never substituted into the fourteen F1 scenarios. */
class MinimalBaselineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "합성 최소 기준 창"
        setContentView(TextView(this).apply {
            setText(R.string.minimal_baseline)
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        })
    }
}
