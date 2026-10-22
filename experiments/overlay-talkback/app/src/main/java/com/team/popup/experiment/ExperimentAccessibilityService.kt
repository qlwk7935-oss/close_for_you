package com.team.popup.experiment

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 실험용 임시 접근성 서비스. 화면 내용은 읽지 않고, 예약된 시간에 테스트 오버레이만 띄운다.
 * 본 앱의 서비스(A 담당)와는 별개이며 실험이 끝나면 버린다.
 */
class ExperimentAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speaker: Speaker? = null
    private var overlay: TestPopupOverlay? = null
    private var pendingPopup: Job? = null

    override fun onServiceConnected() {
        val newSpeaker = Speaker(this)
        speaker = newSpeaker
        overlay = TestPopupOverlay(this, newSpeaker, ExperimentLog::chosen)
        instance = this
        _running.value = true
    }

    /** MainActivity에서 호출. 지정한 초가 지나면 현재 설정으로 테스트 팝업을 띄운다. */
    fun schedulePopup(delaySec: Int) {
        val settings = ExperimentSettings(this)
        pendingPopup?.cancel()
        pendingPopup = scope.launch {
            delay(delaySec * 1000L)
            val mode = settings.announceMode
            overlay?.show(mode, settings.ttsDelayMs)
            ExperimentLog.shown(mode)
        }
    }

    // 실험에는 화면 이벤트가 필요 없다.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        speaker?.stop()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanUp()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanUp()
        super.onDestroy()
    }

    private fun cleanUp() {
        if (instance !== this) return
        instance = null
        _running.value = false
        pendingPopup?.cancel()
        overlay?.dismiss()
        speaker?.shutdown()
        overlay = null
        speaker = null
        scope.cancel()
    }

    companion object {
        // 실험 앱 한정의 간단한 연결 방식. 본 앱에서는 A와 정한 인터페이스로 바꾼다.
        @Volatile
        var instance: ExperimentAccessibilityService? = null
            private set

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
    }
}
