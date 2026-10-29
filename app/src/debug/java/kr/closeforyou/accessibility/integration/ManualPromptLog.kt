package kr.closeforyou.accessibility.integration

import android.util.Log

internal object ManualPromptLog {
    fun create(): ManualPromptTrace? = ManualPromptTrace(sink = { attempt, point, cause ->
        Log.i("A_ManualPrompt", "A_MANUAL_PROMPT;ATTEMPT=$attempt;POINT=${point.name};CAUSE=${cause.name}")
    }, actionSink = { attempt, code ->
        Log.i("A_ManualPrompt", "A_MANUAL_ACTION;ATTEMPT=$attempt;STEP=${code.step.name};STATE=${code.state.name}" +
            ";REASON=${code.reason.name};SAFETY=${code.safety?.name ?: "NONE"};F9=${code.block?.name ?: "NONE"}" +
            ";MISMATCH=${code.mismatch?.name ?: "NONE"};OUTCOME=${code.outcome?.name ?: "NONE"};NEXT=${code.next?.name ?: "NONE"}" +
            (code.eligibility?.let { ";MODE=${it.mode.name};CHECK=${it.check.name};DETAIL=${it.detail.name}" } ?: ""))
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
        Log.i("A_ManualPrompt", "A_MANUAL_RETIREMENT;ATTEMPT=$attempt;EVENT=${c.kind.name};CHECK=${c.check.name};MASK=${c.mask.name}" +
            ";DISPLAY=${c.display.name};PACKAGE_CHECK=${c.pkg.name};NATIVE_CHECK=${c.native.name}" +
            ";RESULT=${when (c.check) {
                ManualRetirementCheck.COMPATIBLE -> "INTENT_ONLY"
                ManualRetirementCheck.CLEANUP_WAIT -> "WAIT_ONLY"
                else -> "REJECTED"
            }}")
    }, displayReviewSink = { attempt, c ->
        Log.i("A_ManualPrompt", "A_MANUAL_DISPLAY_REVIEW;ATTEMPT=$attempt;EVENT=${c.kind.name};MASK=${c.mask.name};RESULT=${c.verdict.name}" +
            ";POINT=${c.point.name};DETAIL=${c.detail.name}" +
            ";LIVE_COUNT=${c.owned.count.name};LIVE_QUERY=${c.owned.query.name};LIVE_SCAN=${c.owned.scan.name};NATIVE_RELATION=${c.owned.native.name}")
    }, cleanupEventSink = { attempt, c ->
        Log.i("A_ManualPrompt", "A_MANUAL_CLEANUP_EVENT;ATTEMPT=$attempt;EVENT=${c.kind.name};MASK=${c.mask.name}" +
            ";BOUNDS=${if (c.bounds) "YES" else "NO"};ADDED=${if (c.added) "YES" else "NO"};REMOVED=${if (c.removed) "YES" else "NO"}" +
            ";OTHER_BITS=${if (c.other) "YES" else "NO"};RETIRED_RELATION=${c.relation.name}" +
            ";WAIT_CONFIRMED=${if (c.transient) "YES" else "NO"};REMOVE_RETURNED=${if (c.returned) "YES" else "NO"}" +
            ";REMOVAL_OBSERVED=${if (c.observed) "YES" else "NO"};NATIVE=${c.native.name};RESULT=${c.result.name}")
    }, reviewSink = { attempt, c ->
        Log.i("A_ManualPrompt", "A_MANUAL_REVIEW;ATTEMPT=$attempt;POINT=${c.point.name};ADMISSION=${c.admission?.name ?: "NOT_CHECKED"}" +
            ";IDENTITY_CHANGE=${c.change.name};SUMMARY=${c.summary?.name ?: "NOT_CHECKED"}" +
            ";PRESS_OFFERED=${when (c.pressOffered) { true -> "YES"; false -> "NO"; null -> "NOT_CHECKED" }}")
    }, readSink = { attempt, c ->
        Log.i("A_ManualPrompt", "A_F9_READ;ATTEMPT=$attempt;PURPOSE=${c.purpose.name};STAGE=${c.stage.name};DETAIL=${c.detail.name}" +
            ";OUTCOME=${c.outcome.name};RELEASE=${c.release.name};CALLBACK_RETURNED=${if (c.consumed) "YES" else "NO"}" +
            (c.occlusion?.let { ";OCCLUDER_TYPE=${it.type.name};LAYER=${it.layer.name};OVERLAP=${it.overlap.name};PASSIVE_BAR=${it.passive.name}" } ?: ""))
    }, cleanupSink = { attempt, c ->
        Log.i("A_ManualPrompt", "A_MANUAL_CLEANUP;ATTEMPT=$attempt;PHASE=${c.phase.name}" +
            ";SITE=${c.current.site.name};ISSUE=${c.current.issue.name};BARRIER=${c.current.barrier?.name ?: "NONE"}" +
            ";NATIVE=${c.current.native.name};FIRST_SITE=${c.first.site.name};FIRST_ISSUE=${c.first.issue.name}" +
            ";FIRST_BARRIER=${c.first.barrier?.name ?: "NONE"};FIRST_NATIVE=${c.first.native.name}")
    })
}
