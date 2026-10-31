package kr.closeforyou.accessibility.integration

import android.util.Log

/** Debug rollout only. One terminal fixed-code line per bounded pre-publication operation. */
internal object ManualPresentationStabilityFeature {
    const val enabled = true
    val contract: PresentationAssurance get() = PresentationAssurance.OBSERVATION_ONLY_DEMO
    fun report(code: PresentationStabilityEnd) {
        Log.i("A_ManualPrompt", "A_PRESENTATION_STABILITY;RESULT=${code.name}")
    }
}
