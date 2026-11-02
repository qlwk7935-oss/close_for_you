package kr.closeforyou.simpleclose

/**
 * F41: what [방금 닫은 내용 듣기] on the result card says: the closed popup's words, as [내용 다시 읽기] on the
 * prompt says them. Memory only (never stored, never logged); kept only while that card is up, forgotten
 * when the card goes, when the next popup is handled and when the service ends. A popup without words
 * keeps nothing, and its card has no button. Main thread.
 */
class LastClosedContent {
    private var sentence: String? = null

    /** The words of the popup just closed; a blank sentence keeps nothing. */
    fun keep(sentence: String) {
        this.sentence = sentence.takeIf { it.isNotBlank() }
    }

    /** What to say on a press; null once forgotten. Not cleared by reading: the button may be pressed again. */
    fun current(): String? = sentence

    fun forget() {
        sentence = null
    }

    override fun toString(): String = "LastClosedContent(kept=${sentence != null}, redacted)"
}
