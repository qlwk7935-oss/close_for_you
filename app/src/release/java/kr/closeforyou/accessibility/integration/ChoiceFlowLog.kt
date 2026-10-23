package kr.closeforyou.accessibility.integration

/** No sink, formatter, diagnostic allocation or Logcat call in Release. */
internal object ChoiceFlowLog {
    fun connected() = Unit
    fun create(): ChoiceFlowDiagnostic? = null
}
