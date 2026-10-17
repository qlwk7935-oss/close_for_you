package kr.closeforyou.servicetest

/** Only bounded session ordinals and allowlisted codes. No raw epoch, window ID or arbitrary IPC. */
internal object DetectionAttemptOutput {
    private val dispositions = setOf("POPUP_CANDIDATE", "NO_POPUP", "UNCERTAIN")
    private val reasons = setOf("BOUNDED_WINDOW", "SEMANTIC_DIALOG", "PANE_WITH_UNDERLAY", "BASELINE_ONLY", "UNCHANGED",
        "ORDINARY_CONTENT_CHANGE", "UNCONFIRMED_STRUCTURE", "UNCONFIRMED_TRANSITION", "INVALID_GEOMETRY")
    private val blocks = setOf("NO_CONSENT", "APP_NOT_ALLOWED", "PROTECTED_APP", "PASSWORD", "EDITABLE_FIELD",
        "SENSITIVE_CONTENT", "SYSTEM_WINDOW", "UNREADABLE_TREE", "TRUNCATED_TREE", "PAUSED")
    fun format(encoded: String): String {
        val fields = encoded.split(':')
        val ordinal = fields.firstOrNull()?.toIntOrNull()
        if (ordinal == null || ordinal !in 1..256 || fields[0] != ordinal.toString()) return "ATTEMPT_DIAGNOSTIC_INVALID"
        if (fields.size in setOf(6, 10) && fields[1] == "ASSESSED" && fields[2] in dispositions && fields[3] in reasons &&
            fields.last() in DetectionGeometryOutput.ages) {
            val geometry = if (fields.size == 6 && fields[4] == "UNOBSERVED") "GEOMETRY=UNOBSERVED"
                else DetectionGeometryOutput.format(fields.subList(4, fields.lastIndex)) ?: return "ATTEMPT_DIAGNOSTIC_INVALID"
            return "ATTEMPT=$ordinal;RESULT=ASSESSED;DISPOSITION=${fields[2]};REASON=${fields[3]};$geometry;WINDOW_BOUNDS_AGE=${fields.last()}"
        }
        if (fields.size >= 4 && fields[1] == "BLOCKED" && fields[2] in blocks) {
            val detail = if (fields.size == 4 && fields[3] == "NONE") "BLOCK=${fields[2]};COLLECTION=NONE"
                else CollectionDiagnosticOutput.format(fields.drop(2).joinToString(":"))
            if (detail == "COLLECTION_DIAGNOSTIC_INVALID") return "ATTEMPT_DIAGNOSTIC_INVALID"
            return "ATTEMPT=$ordinal;RESULT=BLOCKED;$detail"
        }
        return "ATTEMPT_DIAGNOSTIC_INVALID"
    }
}
