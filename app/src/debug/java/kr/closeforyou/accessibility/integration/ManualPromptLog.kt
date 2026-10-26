package kr.closeforyou.accessibility.integration

import android.util.Log

internal object ManualPromptLog {
    fun create(): ManualPromptTrace? = ManualPromptTrace(sink = { attempt, point, cause ->
        Log.i("A_ManualPrompt", "A_MANUAL_PROMPT;ATTEMPT=$attempt;POINT=${point.name};CAUSE=${cause.name}")
    }, actionSink = { attempt, code ->
        Log.i("A_ManualPrompt", "A_MANUAL_ACTION;ATTEMPT=$attempt;STEP=${code.step.name};STATE=${code.state.name}" +
            ";REASON=${code.reason.name};SAFETY=${code.safety?.name ?: "NONE"};F9=${code.block?.name ?: "NONE"}" +
            ";MISMATCH=${code.mismatch?.name ?: "NONE"};OUTCOME=${code.outcome?.name ?: "NONE"};NEXT=${code.next?.name ?: "NONE"}")
    })
}
