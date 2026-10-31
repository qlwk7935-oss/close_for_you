package kr.closeforyou.accessibility.integration

/** Release keeps original collection/admission policy. No formatter or Logcat call. */
internal object ManualPresentationStabilityFeature {
    const val enabled = false
    val contract: PresentationAssurance get() = PresentationAssurance.STRICT_GENERATION_REQUIRED
    @Suppress("UNUSED_PARAMETER") fun report(code: PresentationStabilityEnd) = Unit
}
