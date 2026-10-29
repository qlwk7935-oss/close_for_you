package kr.closeforyou.accessibility.integration

import android.util.Log

internal object ManualPromptLog {
    fun create(): ManualPromptTrace? = ManualPromptTrace(sink = { attempt, point, cause ->
        Log.i("A_ManualPrompt", "A_MANUAL_PROMPT;ATTEMPT=$attempt;POINT=${point.name};CAUSE=${cause.name}")
    }, actionSink = { attempt, code ->
        Log.i("A_ManualPrompt", "A_MANUAL_ACTION;ATTEMPT=$attempt;STEP=${code.step.name};STATE=${code.state.name}" +
            ";REASON=${code.reason.name};SAFETY=${code.safety?.name ?: "NONE"};F9=${code.block?.name ?: "NONE"}" +
            ";MISMATCH=${code.mismatch?.name ?: "NONE"};OUTCOME=${code.outcome?.name ?: "NONE"};NEXT=${code.next?.name ?: "NONE"}")
    }, ownershipSink = { attempt, c ->
        Log.i("A_ManualPrompt", "A_MANUAL_OWNERSHIP;ATTEMPT=$attempt;RESULT=${c.result.name};CHECK=${c.check.name}" +
            ";EVENT_WINDOW=${c.window.name};PACKAGE=${c.pkg.name};GENERATION=${c.generation.name}" +
            ";NATIVE_GENERATION=${c.nativeGeneration.name};TIME=${c.time.name};LEASE=${c.lease.name}" +
            ";DISPLAY=${c.display?.name ?: "NOT_CHECKED"};BEFORE=${c.before.name};LIVE=${c.live.name}" +
            ";NATIVE=${c.native.name};LOOKUP=${c.lookup.name}")
    }, eventSink = { attempt, c ->
        Log.i("A_ManualPrompt", "A_MANUAL_EVENT;ATTEMPT=$attempt;EVENT=${c.kind.name};RESULT=${c.result.name}" +
            ";CHECK=${c.check.name};ID_RELATION=${c.relation.name};EVENT_PACKAGE=${c.pkg.name};MASK=${c.mask.name}")
    }, speechSink = { attempt, c ->
        Log.i("A_ManualPrompt", "A_MANUAL_SPEECH;ATTEMPT=$attempt;STATE=${c.name}")
    }, retirementSink = { attempt, c ->
        Log.i("A_ManualPrompt", "A_MANUAL_RETIREMENT;ATTEMPT=$attempt;EVENT=${c.kind.name};CHECK=${c.check.name}" +
            ";RESULT=${if (c.check == ManualRetirementCheck.COMPATIBLE) "INTENT_ONLY" else "REJECTED"}")
    }, cleanupSink = { attempt, c ->
        Log.i("A_ManualPrompt", "A_MANUAL_CLEANUP;ATTEMPT=$attempt;PHASE=${c.phase.name}" +
            ";SITE=${c.current.site.name};ISSUE=${c.current.issue.name};BARRIER=${c.current.barrier?.name ?: "NONE"}" +
            ";NATIVE=${c.current.native.name};FIRST_SITE=${c.first.site.name};FIRST_ISSUE=${c.first.issue.name}" +
            ";FIRST_BARRIER=${c.first.barrier?.name ?: "NONE"};FIRST_NATIVE=${c.first.native.name}")
    })
}
