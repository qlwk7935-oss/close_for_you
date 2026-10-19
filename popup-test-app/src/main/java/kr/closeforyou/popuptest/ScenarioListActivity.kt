package kr.closeforyou.popuptest

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 시험 결과 기록: 어떤 시나리오에서 어떤 합성 버튼이 눌렸는지만 남긴다.
 * 합성 화면이라 개인정보가 없으며, 비밀번호 입력값 등 입력 내용은 기록하지 않는다.
 */
object ScenarioLog {
    const val TAG = "PopupTest"
    private const val MAX = 50
    private val entries = ArrayDeque<String>()

    @Synchronized
    fun record(scenarioId: String, control: String) {
        val line = "$scenarioId:$control"
        Log.i(TAG, line)
        entries.addFirst(line)
        while (entries.size > MAX) entries.removeAt(entries.lastIndex)
    }

    @Synchronized
    fun recent(): List<String> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}

/** 시나리오 목록. adb로 바로 열 때는 PopupScenarioActivity에 `--es scenario P04`를 넘긴다. */
class ScenarioListActivity : Activity() {
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        list.addView(TextView(this).apply {
            text = getString(R.string.list_intro)
            textSize = 16f
        })
        ScenarioCatalog.all.forEach { scenario ->
            list.addView(Button(this).apply {
                text = "${scenario.id} ${scenario.title}"
                isAllCaps = false
                setOnClickListener { open(scenario.id) }
            })
        }
        list.addView(Button(this).apply {
            text = getString(R.string.clear_log)
            setOnClickListener { ScenarioLog.clear(); refreshLog() }
        })
        logView = TextView(this).apply { textSize = 14f }
        list.addView(logView)
        setContentView(ScrollView(this).apply { addView(list) })
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    private fun refreshLog() {
        val recent = ScenarioLog.recent()
        logView.text = if (recent.isEmpty()) getString(R.string.log_empty)
        else getString(R.string.log_title) + "\n" + recent.joinToString("\n")
    }

    private fun open(id: String) {
        startActivity(Intent(this, PopupScenarioActivity::class.java)
            .putExtra(PopupScenarioActivity.EXTRA_SCENARIO, id))
    }
}
