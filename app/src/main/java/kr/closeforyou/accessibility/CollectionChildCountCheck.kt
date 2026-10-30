package kr.closeforyou.accessibility

import kr.closeforyou.core.model.PrivacyBlockReason

/** Only scalar traversal consistency, never a proof that equal child counts mean equal children. */
internal object CollectionChildCountCheck {
    fun requireCount(count: Int, expected: Int? = null) {
        if (count < 0 || (expected != null && count != expected))
            throw CollectionGuardFailure(PrivacyBlockReason.UNREADABLE_TREE, finding = CollectionFinding.MISSING_CHILD)
        if (count > 1_200) throw CollectionGuardFailure(PrivacyBlockReason.TRUNCATED_TREE,
            finding = CollectionFinding.CHILD_COUNT_LIMIT)
    }
}

internal class CollectionNodeVisit(val scrollable: Boolean, val childCount: Int? = null) {
    override fun toString(): String = "CollectionNodeVisit(redacted)"
}
