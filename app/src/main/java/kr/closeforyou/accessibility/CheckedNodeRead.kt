package kr.closeforyou.accessibility

import kotlinx.collections.immutable.ImmutableSet
import kr.closeforyou.core.model.NodeAction
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds

/** Only the values checked during ONE snapshot-node read, not an F0 receipt or DTO permission.
 * The collector still owns scope, both traversals, geometry, budgets, tokens and all publication.
 * No Source, Android object or mutable CharSequence escapes read(). No logging/persistence.
 */
internal class CheckedNodeRead private constructor(
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val paneTitle: String?,
) {
    /** Synchronous adapter; the only production implementation is collector-owned. */
    interface Source {
        fun packageName(): String?
        fun password(): Boolean
        fun editable(): Boolean
        fun inputType(): Int
        fun value(field: CollectionField): CharSequence?
    }

    // Value construction only. Neither method grants completeness, freshness or authorization.
    fun snapshot(path: String, clickable: Boolean, enabled: Boolean, bounds: ScreenBounds,
        actions: ImmutableSet<NodeAction>) = NodeSnapshot(path, viewId, className, text,
        contentDescription, clickable, enabled, bounds, actions)

    fun outline(fingerprint: String, bounds: ScreenBounds, childCount: Int, actions: ImmutableSet<NodeAction>,
        path: String, visible: Boolean, scrollable: Boolean) = DetectionOutline(fingerprint, bounds, childCount,
        className?.contains("dialog", ignoreCase = true) == true || NodeAction.DISMISS in actions,
        path, !paneTitle.isNullOrEmpty(), visible, scrollable)

    override fun toString(): String = "CheckedNodeRead(redacted)"

    companion object {
        fun read(source: Source, expectedPackage: String, isRoot: Boolean,
            progress: CollectionProgress, maxCharacters: Int): CheckedNodeRead {
            progress.at(CollectionOperation.NODE_PACKAGE_READ, CollectionField.NODE_PACKAGE) {
                val actual = source.packageName()
                if (isRoot) ProtectionRootGuard.requirePackage(actual, expectedPackage)
                else if (actual != null && actual != expectedPackage)
                    throw CollectionGuardFailure(kr.closeforyou.core.model.PrivacyBlockReason.UNREADABLE_TREE,
                        CollectionField.NODE_PACKAGE, CollectionFinding.PACKAGE_MISMATCH)
            }
            // Preserve the original getter/check order, including reading all three flags first.
            val password = progress.at(CollectionOperation.NODE_FIELD_READ, CollectionField.PASSWORD_FLAG) { source.password() }
            val editable = progress.at(CollectionOperation.NODE_FIELD_READ, CollectionField.EDITABLE_FLAG) { source.editable() }
            val inputType = progress.at(CollectionOperation.NODE_FIELD_READ, CollectionField.INPUT_TYPE) { source.inputType() }
            progress.at(CollectionOperation.NODE_FIELD_CHECK) { CollectionProtectionCheck.field(password, editable, inputType) }
            fun checked(field: CollectionField): String? {
                val value = progress.at(CollectionOperation.NODE_TEXT_READ, field) { source.value(field) }
                return progress.at(CollectionOperation.NODE_TEXT_CHECK, field) {
                    val frozen = freeze(value, field, maxCharacters)
                    CollectionProtectionCheck.text(frozen, field, maxCharacters)
                    frozen
                }
            }
            val text = checked(CollectionField.TEXT)
            val description = checked(CollectionField.CONTENT_DESCRIPTION)
            val viewId = checked(CollectionField.VIEW_ID)
            val className = checked(CollectionField.CLASS_NAME)
            checked(CollectionField.ERROR)
            checked(CollectionField.HINT)
            val paneTitle = checked(CollectionField.PANE_TITLE)
            checked(CollectionField.TOOLTIP)
            checked(CollectionField.STATE_DESCRIPTION)
            return CheckedNodeRead(text, description, viewId, className, paneTitle)
        }

        /** No truncation/subSequence/toString on a mutable source. Bound allocation before copying,
         * reject observed size changes, then run the existing protection on the immutable copy.
         * Same-length races/ABA can still occur in the source; only the checked COPY is consumed.
         */
        private fun freeze(value: CharSequence?, field: CollectionField, maxCharacters: Int): String? {
            if (value == null) return null
            val length = value.length
            if (length < 0) throw IllegalStateException("CHECKED_NODE_LENGTH_INVALID")
            if (length > maxCharacters)
                throw CollectionGuardFailure(kr.closeforyou.core.model.PrivacyBlockReason.TRUNCATED_TREE,
                    field, CollectionFinding.NODE_TEXT_LIMIT)
            if (value is String) return value // Already immutable; preserve code units/null/empty exactly.
            val chars = CharArray(length)
            for (index in chars.indices) chars[index] = value[index]
            val afterLength = value.length
            if (afterLength > maxCharacters)
                throw CollectionGuardFailure(kr.closeforyou.core.model.PrivacyBlockReason.TRUNCATED_TREE,
                    field, CollectionFinding.NODE_TEXT_LIMIT)
            if (afterLength != length) throw IllegalStateException("CHECKED_NODE_LENGTH_CHANGED")
            return chars.concatToString()
        }
    }
}
