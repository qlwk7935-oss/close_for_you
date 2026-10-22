package kr.closeforyou.ui.voice

import kr.closeforyou.core.contract.AnnouncementRequest

/**
 * Recent announcements for "다시 듣기", held in process memory only.
 * Never persisted or logged; the owner clears it when the service ends.
 */
class AnnouncementHistory(private val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0)
    }

    private val entries = ArrayDeque<AnnouncementRequest>()

    @Synchronized
    fun record(request: AnnouncementRequest) {
        entries.addLast(request)
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun last(): AnnouncementRequest? = entries.lastOrNull()

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() = entries.clear()

    override fun toString(): String = "AnnouncementHistory(size=${size()}, redacted)"

    companion object {
        const val DEFAULT_CAPACITY = 10
    }
}
