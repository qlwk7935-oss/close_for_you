package kr.closeforyou.accessibility

/** System setting observation, not proof of a running/bound service. No app labels or UI state. */
internal enum class OtherEngineState { ENABLED, DISABLED, UNKNOWN }

internal object EnabledEnginePolicy {
    const val C_PACKAGE = "kr.closeforyou"
    const val C_SERVICE = "kr.closeforyou.simpleclose.SimpleCloseAccessibilityService"
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")
    private val classPattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")

    /** null means unreadable/unset, not confirmed absence. Empty is a valid empty setting.
     * Any malformed entry makes absence unproven. A positive exact match always blocks A. */
    fun fromSecureSetting(value: String?): OtherEngineState {
        if (value == null) return OtherEngineState.UNKNOWN
        if (value.isEmpty()) return OtherEngineState.DISABLED
        var malformed = false
        for (raw in value.split(':')) {
            val entry = raw.trim()
            val parts = entry.split('/')
            if (parts.size != 2) { malformed = true; continue }
            val pkg = parts[0]
            val cls = parts[1].let { if (it.startsWith('.')) pkg + it else it }
            if (!packagePattern.matches(pkg) || !classPattern.matches(cls)) {
                malformed = true
                continue
            }
            if (pkg == C_PACKAGE && cls == C_SERVICE) return OtherEngineState.ENABLED
        }
        return if (malformed) OtherEngineState.UNKNOWN else OtherEngineState.DISABLED
    }
}
