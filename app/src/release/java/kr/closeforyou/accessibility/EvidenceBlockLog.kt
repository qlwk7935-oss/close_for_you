package kr.closeforyou.accessibility

/** Release does not allocate a diagnostic or include a Logcat sink. */
@Suppress("UNUSED_PARAMETER")
internal object EvidenceBlockLog {
    fun begin(): EvidenceBlockDiagnostic? = null
    fun report(diagnostic: EvidenceBlockDiagnostic?) = Unit
}
