package kr.closeforyou.servicetest

/** Fixed probe codes may contain digits (F0_A/F0_B), but never raw text or separators. */
internal object DiagnosticCode {
    fun safe(value: String?): String = value?.takeIf {
        it.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))
    } ?: "UNKNOWN"
}
