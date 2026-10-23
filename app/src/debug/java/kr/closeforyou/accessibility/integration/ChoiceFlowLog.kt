package kr.closeforyou.accessibility.integration

import android.util.Log
import kr.closeforyou.accessibility.diagnosticWrite

/** Fixed codes only; separate from the unchanged V2 job completion diagnostic. */
internal object ChoiceFlowLog {
    fun connected() = diagnosticWrite { Log.i("A_ChoiceFlow", "A_CHOICE_DEBUG;STATE=ENABLED;VERSION=V3") }
    fun create(): ChoiceFlowDiagnostic? = ChoiceFlowDiagnostic { point, detail, cause ->
        Log.i("A_ChoiceFlow", "A_CHOICE_FLOW;POINT=${point.name};DETAIL=${detail.name};CAUSE=${cause.name}")
    }
    fun overlayEvents(): OverlayEventDiagnostic? = OverlayEventDiagnostic { event, reason, detail ->
        Log.i("A_OverlayEvent", "A_OVERLAY_EVENT;VERSION=V4;EVENT=${event.name};REASON=${reason.name};BINDING_DETAIL=${detail.name}")
    }
}
