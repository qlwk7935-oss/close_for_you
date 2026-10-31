package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ScreenBounds

/**
 * What a closed popup looked like, kept in memory only for the moment after the close: the texts and
 * bounds of its nodes. A live node with the same words or the same place is treated as the popup's
 * leftover (a web popup often stays in the DOM after closing), so TalkBack focus is not sent there.
 * Pure Kotlin; never logged or stored; dropped with the popup.
 */
class PopupMemory(private val texts: Set<String>, private val bounds: Set<ScreenBounds>) {
    fun matches(text: String?, description: String?, nodeBounds: ScreenBounds): Boolean =
        (text != null && text in texts) || (description != null && description in texts) || nodeBounds in bounds

    companion object {
        fun of(popup: SimplePopup): PopupMemory {
            val inside = popup.snapshot.nodes.filter(popup.root::contains)
            return PopupMemory(
                inside.flatMap { listOfNotNull(it.text, it.contentDescription) }.filter { it.isNotBlank() }.toSet(),
                inside.map { it.bounds }.toSet(),
            )
        }
    }
}
