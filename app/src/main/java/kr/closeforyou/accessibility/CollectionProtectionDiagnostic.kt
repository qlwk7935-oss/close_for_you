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
    NONE, ROOT_GUARD, PACKAGE_GUARD, WINDOW_GUARD, GEOMETRY_GUARD, COLLECTION_EXCEPTION,
    PACKAGE_MISMATCH, PASSWORD_FLAG, PASSWORD_INPUT_TYPE, EDITABLE_FLAG, NON_ZERO_INPUT_TYPE,
    SENSITIVE_TEXT, UNCERTAIN_TEXT, NODE_TEXT_LIMIT, TOTAL_TEXT_LIMIT,
    NODE_COUNT_LIMIT, DEPTH_LIMIT, TIME_LIMIT, CHILD_COUNT_LIMIT, MISSING_CHILD, MALFORMED_NODE_BOUNDS,
    PROTECTION_SCOPE_CHANGED, ROOT_WINDOW_MISMATCH,
}
/** Actual synchronous code boundaries, not a claim about the origin of an exception. */
internal enum class CollectionOperation {
    NONE, UNCLASSIFIED, ROOT_READ, ROOT_METADATA, WINDOWS_READ, WINDOW_METADATA, WINDOW_ROOT_READ,
    SCREEN_GEOMETRY, COORDINATE_CHECK, TREE_BUDGET, CHILD_COUNT_READ, CHILD_READ, CHILD_RELEASE,
    NODE_PACKAGE_READ, NODE_FIELD_READ, NODE_FIELD_CHECK, NODE_TEXT_READ, NODE_TEXT_CHECK, NODE_BOUNDS_READ, NODE_BOUNDS_DTO, NODE_BOUNDS_VALIDATE,
    NODE_ACTION_READ, NODE_ACTIONS_DTO, NODE_PROPERTIES_READ, NODE_DTO, TEXT_BUDGET,
    DIGEST_INITIALIZE, STRUCTURE_FIELDS, STRUCTURE_HASH, CONTENT_HASH, APPLICATION_HASH, OUTLINE_HASH,
    OUTLINE_PROPERTIES_READ, OUTLINE_DTO, DIGEST_FINALIZE, TOKEN_DTO, SNAPSHOT_DTO, DETECTION_FRAME_DTO,
    ROOT_WINDOW_READ,
}
internal enum class CollectionExceptionKind {
    NONE, SECURITY, ILLEGAL_ARGUMENT, ILLEGAL_STATE, INDEX_OUT_OF_BOUNDS,
    UNSUPPORTED_OPERATION, NULL_POINTER, CLASS_CAST, DIGEST_UNAVAILABLE, OTHER_EXCEPTION,
}
internal data class CollectionFailureLocation(val operation: CollectionOperation, val field: CollectionField,
    val exception: CollectionExceptionKind)

/** Saves only enums at the innermost failing boundary. Never retains a Throwable or its message. */
internal class CollectionProgress {
    var failure: CollectionFailureLocation? = null
        private set
    // Inline keeps boundary instrumentation light on the hot traversal path; clock allowance is unchanged.
    inline fun <T> at(operation: CollectionOperation, field: CollectionField = CollectionField.NONE, block: () -> T): T =
        try { block() } catch (exception: Exception) {
            failed(operation, field, exception)
            throw exception
        }
    @PublishedApi internal fun failed(operation: CollectionOperation, field: CollectionField, exception: Exception) {
        if (failure == null) failure = CollectionFailureLocation(operation, field, classify(exception))
    }
    companion object {
        fun classify(exception: Exception): CollectionExceptionKind = when (exception) {
            is CollectionGuardFailure -> CollectionExceptionKind.NONE // Existing protection, not a runtime failure.
            is SecurityException -> CollectionExceptionKind.SECURITY
            is IndexOutOfBoundsException -> CollectionExceptionKind.INDEX_OUT_OF_BOUNDS
            is IllegalArgumentException -> CollectionExceptionKind.ILLEGAL_ARGUMENT
            is IllegalStateException -> CollectionExceptionKind.ILLEGAL_STATE
            is UnsupportedOperationException -> CollectionExceptionKind.UNSUPPORTED_OPERATION
            is NullPointerException -> CollectionExceptionKind.NULL_POINTER
            is ClassCastException -> CollectionExceptionKind.CLASS_CAST
            is java.security.NoSuchAlgorithmException -> CollectionExceptionKind.DIGEST_UNAVAILABLE
            else -> CollectionExceptionKind.OTHER_EXCEPTION
        }
    }
}
internal data class CollectionProtectionDiagnostic(
    val phase: CollectionPhase,
    val detail: CaptureDetail,
    val tree: CollectionTree = CollectionTree.NONE,
    val field: CollectionField = CollectionField.NONE,
    val finding: CollectionFinding = CollectionFinding.NONE,
    val related: WindowType? = null,
    val frame: ScreenFrameStatus = ScreenFrameStatus.UNAVAILABLE,
    val operation: CollectionOperation = CollectionOperation.NONE,
    val exception: CollectionExceptionKind = CollectionExceptionKind.NONE,
    val nodeBounds: NodeBoundsDiagnostic = NodeBoundsDiagnostic(),
    val windowAge: WindowBoundsAge = WindowBoundsAge.UNOBSERVED,
    val trigger: kr.closeforyou.core.model.PopupTrigger? = null,
) {
    /** One tuple per real failure: do not combine fields from different collections. */
    fun encoded(): String = listOf(phase.name, detail.name, tree.name, field.name, finding.name,
        related?.name ?: "NONE", frame.name, operation.name, exception.name, nodeBounds.encoded(), windowAge.name, trigger?.name ?: "UNOBSERVED").joinToString(":")
}

internal class CollectionGuardFailure(val reason: PrivacyBlockReason,
    val field: CollectionField = CollectionField.NONE,
    val finding: CollectionFinding = CollectionFinding.NONE,
    val nodeBounds: NodeBoundsDiagnostic? = null) : RuntimeException()

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
