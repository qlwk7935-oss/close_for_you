package kr.closeforyou.accessibility

import kr.closeforyou.core.model.PrivacyBlockReason

/** Capture-local registration, NOT a protection-completion receipt or a freshness guarantee.
 * The collector registers the observed target package and enumerated background packages;
 * background registrations follow its existing foreign-window gate.
 * No Android handles or callbacks are retained. Excluded roots remain owned for release.
 */
internal class ProtectionRootGuard(
    initialPackage: String,
    private val expectedPackage: String,
    private val expectedWindowId: Int,
) {
    private val isProtected = initialPackage == expectedPackage

    fun inspect(
        progress: CollectionProgress,
        readPackage: () -> String?,
        readWindowId: () -> Int,
        inspectTree: () -> Unit,
    ) {
        progress.at(CollectionOperation.NODE_PACKAGE_READ, CollectionField.NODE_PACKAGE) {
            readPackage().also {
                if (isProtected) requirePackage(it, expectedPackage)
                else if (it == expectedPackage) throw CollectionGuardFailure(PrivacyBlockReason.UNREADABLE_TREE,
                    CollectionField.NODE_PACKAGE, CollectionFinding.PROTECTION_SCOPE_CHANGED)
            }
        }
        // Preserve exclusion of an initially permitted foreign background root. A read exception
        // still propagates, as before. Null/different foreign package never grants protection success.
        if (!isProtected) return
        progress.at(CollectionOperation.ROOT_WINDOW_READ) {
            if (readWindowId() != expectedWindowId) throw CollectionGuardFailure(PrivacyBlockReason.UNREADABLE_TREE,
                finding = CollectionFinding.ROOT_WINDOW_MISMATCH)
        }
        inspectTree()
    }

    override fun toString(): String = "ProtectionRootGuard(redacted)"

    companion object {
        /** Strict only for registered roots; descendant package-null behavior is unchanged. */
        fun requirePackage(actual: String?, expected: String) {
            if (actual != expected) throw CollectionGuardFailure(PrivacyBlockReason.UNREADABLE_TREE,
                CollectionField.NODE_PACKAGE, CollectionFinding.PACKAGE_MISMATCH)
        }
    }
}
