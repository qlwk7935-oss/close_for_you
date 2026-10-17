package kr.closeforyou.accessibility.testing

import kr.closeforyou.accessibility.*
import kr.closeforyou.core.model.PrivacyBlockReason

/** Bounded, transient codes only. Window IDs are private correlation keys, never returned. */
internal class DetectionTestSession(private val now: () -> Long, private val allowed: () -> Boolean) {
    private data class Record(val windowId: Int, val input: DetectionInput? = null,
        val assessment: DetectionAssessment? = null, val block: PrivacyBlockReason? = null)
    private val deadline = now() + 45_000
    private val records = mutableListOf<Record>()
    private var closed = false
    private var overflow = false
    @Synchronized fun valid(): Boolean {
        if (closed || now() >= deadline || !allowed()) { close(); return false }
        return true
    }
    @Synchronized private fun add(target: String?, record: Record) {
        if (!valid() || target != HOST) return
        if (records.size == 256) { overflow = true; return }
        records += record
    }
    fun event(target: String?, windowId: Int, input: DetectionInput) = add(target, Record(windowId, input))
    fun assessed(target: String, windowId: Int, result: DetectionAssessment) = add(target, Record(windowId, assessment = result))
    fun blocked(target: String?, windowId: Int, reason: PrivacyBlockReason) = add(target, Record(windowId, block = reason))
    @Synchronized fun report(windowId: Int): DetectionTestReport? {
        if (!valid() || windowId < 0) return null
        val scoped = records.filter { it.windowId == windowId }
        return DetectionTestReport(
            scoped.count { it.assessment?.disposition == DetectionDisposition.POPUP_CANDIDATE },
            scoped.count { it.assessment?.disposition == DetectionDisposition.NO_POPUP },
            scoped.count { it.assessment?.disposition == DetectionDisposition.UNCERTAIN },
            scoped.count { it.block != null },
            scoped.count { it.input == DetectionInput.SCROLL },
            records.count { it.input == DetectionInput.NOTIFICATION },
            scoped.mapNotNull { it.assessment?.reason?.name }.toSet(),
            scoped.mapNotNull { it.block?.name }.toSet(), overflow,
        )
    }
    @Synchronized fun close() { records.clear(); closed = true }
    @Synchronized fun reset() { records.clear(); overflow = false }
    override fun toString() = "DetectionTestSession(redacted)"
    companion object { const val HOST = "kr.closeforyou.capturetest" }
}
internal data class DetectionTestReport(val candidates: Int, val nonPopups: Int, val uncertain: Int,
    val blocks: Int, val scrolls: Int, val toasts: Int, val reasons: Set<String>, val blockReasons: Set<String>, val overflow: Boolean) {
    val actual: String get() = when {
        overflow -> "UNOBSERVED"
        candidates > 0 && blocks > 0 -> "CONFLICT" // Never hide either observation behind the other.
        candidates > 0 -> "DETECTED"
        blocks > 0 -> "BLOCKED"
        uncertain > 0 -> "UNCERTAIN"
        nonPopups > 0 || scrolls > 0 || toasts > 0 -> "NO_POPUP"
        else -> "UNOBSERVED"
    }
}
