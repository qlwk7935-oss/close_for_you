package kr.closeforyou.ui.voice.choice

import kr.closeforyou.core.model.SnapshotToken

/**
 * [버튼으로 이동] (team agreement 10/18~19): moves TalkBack focus to a button so the person can find
 * and decide on it themselves. It is NOT a permission to press anything, and works for any button
 * (확인/OK, 결제 too), because the person — not C — then acts on it.
 *
 * The contract has no value for it yet (`ChoiceKind` / A's focus API), so it stays OFF. When the
 * contract lands, turn [enabled] on and hand A a [ButtonFocusMover]. (REPLACE-WITH-CONTRACT)
 */
object MoveToButtonFeature {
    /** Off until the contract value and A's focus request exist. */
    val enabled: Boolean get() = false
}

/** Which button to move to. Carries no screen text, only the token and B's candidate id. */
data class MoveToButtonRequest(val token: SnapshotToken, val candidateId: String) {
    override fun toString(): String = "MoveToButtonRequest(redacted)"
}

/**
 * C → A proposal (draft, not in core/contract): A's service moves accessibility focus to the
 * candidate's node after re-checking that the token is still current. Only on the person's request
 * (CLAUDE.md 3-2: TalkBack focus belongs to the person). Returns false when it could not move.
 */
fun interface ButtonFocusMover {
    fun moveFocusTo(request: MoveToButtonRequest): Boolean
}

/** What a tap or voice command on the prompt produces. */
sealed interface PromptSelection {
    /** Goes to B's policy and A's gate like any [kr.closeforyou.core.model.UserChoice]. */
    data class Choice(val choice: kr.closeforyou.core.model.UserChoice) : PromptSelection

    /** Goes to A's [ButtonFocusMover]; never to the action gate. */
    data class MoveToButton(val request: MoveToButtonRequest) : PromptSelection
}
