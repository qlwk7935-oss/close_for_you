package kr.closeforyou.accessibility.integration

import android.util.Log

internal object ManualPromptLog {
    fun create(): ManualPromptTrace? = ManualPromptTrace { attempt, point, cause ->
        Log.i("A_ManualPrompt", "A_MANUAL_PROMPT;ATTEMPT=$attempt;POINT=${point.name};CAUSE=${cause.name}")
    }
}
