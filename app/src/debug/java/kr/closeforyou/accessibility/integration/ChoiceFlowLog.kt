package kr.closeforyou.accessibility.integration

import android.util.Log
import android.os.SystemClock
import kr.closeforyou.BuildConfig
import kr.closeforyou.accessibility.diagnosticWrite

/** Fixed codes only; separate from the unchanged V2 job completion diagnostic. */
internal object ChoiceFlowLog {
    fun connected() = diagnosticWrite {
        Log.i("A_ChoiceFlow", "A_CHOICE_DEBUG;STATE=ENABLED;VERSION=V4;BUILD=ISSUE29_ORIGIN_V1;SOURCE_SHA256=${BuildConfig.ISSUE29_SOURCE_SHA256}")
    }
    fun create(): ChoiceFlowDiagnostic? = ChoiceFlowDiagnostic { point, detail, cause ->
        Log.i("A_ChoiceFlow", "A_CHOICE_FLOW;POINT=${point.name};DETAIL=${detail.name};CAUSE=${cause.name}")
    }
    fun overlayOrigins(): OverlayEventOriginDiagnostic? = OverlayEventOriginDiagnostic(SystemClock::uptimeMillis) { point ->
        Log.i("A_OverlayCleanup", "A_OVERLAY_CLEANUP;VERSION=V1;POINT=${point.name}")
    }
    fun overlayEvents(origin: OverlayEventOriginDiagnostic? = null): OverlayEventDiagnostic? = OverlayEventDiagnostic(origin) {
            event, reason, detail, windowClass, change, owned, relation, time ->
        Log.i("A_OverlayEvent", "A_OVERLAY_EVENT;VERSION=V5;EVENT=${event.name};REASON=${reason.name};BINDING_DETAIL=${detail.name};WINDOW_CLASS=${windowClass.name};WINDOW_CHANGE_KIND=${change.name};OWNED_WINDOW_DETAIL=${owned.name};EVENT_ID_RELATION=${relation.name};EVENT_TIME_RELATION=${time.name}")
    }
}
