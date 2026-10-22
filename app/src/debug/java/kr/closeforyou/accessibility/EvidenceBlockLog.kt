package kr.closeforyou.accessibility

import android.util.Log

/** Debug-only sink. No provider, session, screen data or action capability. */
internal object EvidenceBlockLog {
    fun begin(): EvidenceBlockDiagnostic? = EvidenceBlockDiagnostic()
    fun report(diagnostic: EvidenceBlockDiagnostic?) {
        diagnostic?.report { code -> Log.i("A_EvidenceBlock", code) }
    }
}
