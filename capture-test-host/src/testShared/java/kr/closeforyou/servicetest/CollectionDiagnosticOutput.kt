package kr.closeforyou.servicetest

/** Accept only the eight enum-derived fields; never echo malformed IPC or arbitrary text. */
internal object CollectionDiagnosticOutput {
    private val labels = listOf("BLOCK", "STEP", "DETAIL", "TREE", "FIELD", "CAUSE", "RELATED", "FRAME")
    // Deliberately fail closed for future/unknown codes; uppercase user strings are not enums.
    private val allowed = listOf(
        setOf("NO_CONSENT", "APP_NOT_ALLOWED", "PROTECTED_APP", "PASSWORD", "EDITABLE_FIELD", "SENSITIVE_CONTENT", "SYSTEM_WINDOW", "UNREADABLE_TREE", "TRUNCATED_TREE", "PAUSED"),
        setOf("ROOT", "PACKAGE_GATE", "WINDOWS", "GEOMETRY", "PROTECTION_PASS", "SNAPSHOT_PASS"),
        setOf("NONE", "ROOT_UNAVAILABLE", "ROOT_PACKAGE_UNAVAILABLE", "PACKAGE_GATE", "WINDOW_LIST_UNAVAILABLE", "TARGET_MISSING", "TARGET_NOT_APPLICATION", "NON_APPLICATION_FOCUS", "FOREIGN_APPLICATION", "SECONDARY_DISPLAY", "TREE_PROTECTION", "COLLECTION_FAILED", "DISPLAY_GEOMETRY_UNCERTAIN"),
        setOf("NONE", "TARGET", "SAME_APP_BACKGROUND"),
        setOf("NONE", "NODE_PACKAGE", "PASSWORD_FLAG", "EDITABLE_FLAG", "INPUT_TYPE", "TEXT", "CONTENT_DESCRIPTION", "VIEW_ID", "CLASS_NAME", "ERROR", "HINT", "PANE_TITLE", "TOOLTIP", "STATE_DESCRIPTION"),
        setOf("NONE", "ROOT_GUARD", "PACKAGE_GUARD", "WINDOW_GUARD", "GEOMETRY_GUARD", "FRAMEWORK_FAILURE", "PACKAGE_MISMATCH", "PASSWORD_FLAG", "PASSWORD_INPUT_TYPE", "EDITABLE_FLAG", "NON_ZERO_INPUT_TYPE", "SENSITIVE_TEXT", "UNCERTAIN_TEXT", "NODE_TEXT_LIMIT", "TOTAL_TEXT_LIMIT", "NODE_COUNT_LIMIT", "DEPTH_LIMIT", "TIME_LIMIT", "CHILD_COUNT_LIMIT", "MISSING_CHILD"),
        setOf("NONE", "APPLICATION", "INPUT_METHOD", "ACCESSIBILITY_OVERLAY", "SYSTEM", "OTHER"),
        setOf("UNAVAILABLE", "VERIFIED", "EMPTY", "OFFSET", "SIZE_MISMATCH", "DISPLAY_CHANGED"),
    )
    fun format(encoded: String): String {
        val values = encoded.split(':')
        if (values.size != labels.size || values.withIndex().any { (index, value) -> value !in allowed[index] })
            return "COLLECTION_DIAGNOSTIC_INVALID"
        return labels.zip(values).joinToString(";") { (label, value) -> "$label=$value" }
    }
}
