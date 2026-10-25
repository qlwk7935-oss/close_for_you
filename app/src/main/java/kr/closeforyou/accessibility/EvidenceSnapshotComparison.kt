package kr.closeforyou.accessibility

import kr.closeforyou.core.model.PopupSnapshot

internal enum class EvidenceMismatch { NONE, PACKAGE, WINDOW, STRUCTURE, CONTENT, FRAME, WINDOW_SET }


/** The original short-circuit comparison, on existing DTOs only. No new Android reads. */
internal object EvidenceSnapshotComparison {
    fun firstMismatch(original: PopupSnapshot, fresh: PopupSnapshot): EvidenceMismatch {
        val a = original.token
        val b = fresh.token
        return when {
            a.packageName != b.packageName -> EvidenceMismatch.PACKAGE
            a.windowId != b.windowId -> EvidenceMismatch.WINDOW
            a.structureDigest != b.structureDigest -> EvidenceMismatch.STRUCTURE
            a.contentDigest != b.contentDigest -> EvidenceMismatch.CONTENT
            original.screenBounds != fresh.screenBounds -> EvidenceMismatch.FRAME
            original.windows.toSet() != fresh.windows.toSet() -> EvidenceMismatch.WINDOW_SET
            else -> EvidenceMismatch.NONE
        }
    }
}

