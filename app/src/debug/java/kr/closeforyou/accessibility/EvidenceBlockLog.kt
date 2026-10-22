package kr.closeforyou.accessibility

import android.util.Log

/** Debug-only sink. No provider, session, screen data or action capability. */
internal object EvidenceBlockLog {
    fun connected() = diagnosticWrite { Log.i("A_EvidenceBlock", "A_FLOW_DEBUG;STATE=ENABLED;VERSION=V2") }
    fun beginWork(kind: ServiceWorkKind): ServiceWorkDiagnostic? =
        ServiceWorkDiagnostic(kind) { code -> Log.i("A_EvidenceBlock", code) }
}
