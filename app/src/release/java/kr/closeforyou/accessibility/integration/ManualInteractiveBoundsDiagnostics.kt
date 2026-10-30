package kr.closeforyou.accessibility.integration

import kr.closeforyou.accessibility.NativeActionPresence
import kr.closeforyou.accessibility.action.F9Scene
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.PopupSnapshot

/** Variant linkage only: no classification, enum vocabulary, formatter or data retention. */
internal class ManualInteractiveBoundsCode private constructor()
internal object ManualInteractiveBoundsDiagnostics {
    fun unknown(): ManualInteractiveBoundsCode? = null
    @Suppress("UNUSED_PARAMETER")
    fun describe(original: PopupSnapshot, fresh: F9Scene, candidates: List<CloseCandidate>, index: Int,
        native: NativeActionPresence): ManualInteractiveBoundsCode? = null
}
