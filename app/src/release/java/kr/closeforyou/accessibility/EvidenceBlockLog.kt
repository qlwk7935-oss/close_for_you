package kr.closeforyou.accessibility

/** Release does not allocate a diagnostic or include a Logcat sink. */
@Suppress("UNUSED_PARAMETER")
internal object EvidenceBlockLog {
    fun connected() = Unit
    fun beginWork(kind: ServiceWorkKind): ServiceWorkDiagnostic? = null
}
