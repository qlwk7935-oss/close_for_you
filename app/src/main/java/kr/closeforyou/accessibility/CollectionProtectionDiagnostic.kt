package kr.closeforyou.accessibility

import kr.closeforyou.core.model.PrivacyBlockReason
import kr.closeforyou.core.model.WindowType

/** A-private, code-only. No node, value, ID, coordinates or exception message. */
internal enum class CollectionPhase { ROOT, PACKAGE_GATE, WINDOWS, GEOMETRY, PROTECTION_PASS, SNAPSHOT_PASS }
internal enum class CollectionTree { NONE, TARGET, SAME_APP_BACKGROUND }
internal enum class CollectionField {
    NONE, NODE_PACKAGE, PASSWORD_FLAG, EDITABLE_FLAG, INPUT_TYPE, TEXT, CONTENT_DESCRIPTION,
    VIEW_ID, CLASS_NAME, ERROR, HINT, PANE_TITLE, TOOLTIP, STATE_DESCRIPTION,
}
internal enum class CollectionFinding {
    NONE, ROOT_GUARD, PACKAGE_GUARD, WINDOW_GUARD, GEOMETRY_GUARD, FRAMEWORK_FAILURE,
    PACKAGE_MISMATCH, PASSWORD_FLAG, PASSWORD_INPUT_TYPE, EDITABLE_FLAG, NON_ZERO_INPUT_TYPE,
    SENSITIVE_TEXT, UNCERTAIN_TEXT, NODE_TEXT_LIMIT, TOTAL_TEXT_LIMIT,
    NODE_COUNT_LIMIT, DEPTH_LIMIT, TIME_LIMIT, CHILD_COUNT_LIMIT, MISSING_CHILD,
}
internal data class CollectionProtectionDiagnostic(
    val phase: CollectionPhase,
    val detail: CaptureDetail,
    val tree: CollectionTree = CollectionTree.NONE,
    val field: CollectionField = CollectionField.NONE,
    val finding: CollectionFinding = CollectionFinding.NONE,
    val related: WindowType? = null,
    val frame: ScreenFrameStatus = ScreenFrameStatus.UNAVAILABLE,
) {
    /** One tuple per real failure: do not combine fields from different collections. */
    fun encoded(): String = listOf(phase.name, detail.name, tree.name, field.name, finding.name,
        related?.name ?: "NONE", frame.name).joinToString(":")
}

internal class CollectionGuardFailure(val reason: PrivacyBlockReason,
    val field: CollectionField = CollectionField.NONE,
    val finding: CollectionFinding = CollectionFinding.NONE) : RuntimeException()

/** Same F0-A checks and ordering as before; only labels the existing rejecting check. */
internal object CollectionProtectionCheck {
    fun field(password: Boolean, editable: Boolean, inputType: Int) {
        val reason = PrivacyPolicy.fieldBlock(password, editable, inputType) ?: return
        val source = when {
            password -> CollectionField.PASSWORD_FLAG
            reason == PrivacyBlockReason.PASSWORD -> CollectionField.INPUT_TYPE
            editable -> CollectionField.EDITABLE_FLAG
            else -> CollectionField.INPUT_TYPE
        }
        val finding = when {
            password -> CollectionFinding.PASSWORD_FLAG
            reason == PrivacyBlockReason.PASSWORD -> CollectionFinding.PASSWORD_INPUT_TYPE
            editable -> CollectionFinding.EDITABLE_FLAG
            else -> CollectionFinding.NON_ZERO_INPUT_TYPE
        }
        throw CollectionGuardFailure(reason, source, finding)
    }
    fun text(value: CharSequence?, source: CollectionField, maxCharacters: Int) {
        if ((value?.length ?: 0) > maxCharacters)
            throw CollectionGuardFailure(PrivacyBlockReason.TRUNCATED_TREE, source, CollectionFinding.NODE_TEXT_LIMIT)
        if (PrivacyPolicy.hasSensitiveText(value))
            throw CollectionGuardFailure(PrivacyBlockReason.SENSITIVE_CONTENT, source, CollectionFinding.SENSITIVE_TEXT)
        if (PrivacyPolicy.hasUncertainText(value))
            throw CollectionGuardFailure(PrivacyBlockReason.SENSITIVE_CONTENT, source, CollectionFinding.UNCERTAIN_TEXT)
    }
}
