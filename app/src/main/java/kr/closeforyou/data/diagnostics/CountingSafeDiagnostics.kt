package kr.closeforyou.data.diagnostics

import kr.closeforyou.core.contract.DiagnosticCode
import kr.closeforyou.core.contract.SafeDiagnostics
import kr.closeforyou.core.model.SafetyReason
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * D: SafeDiagnostics 구현. 고정 코드와 사유의 **횟수만** 센다.
 *
 * - 화면 글자·패키지·노드·지문·시각을 받지도 저장하지도 않는다(계약상 인자로 들어오지 않음).
 * - 프로세스 메모리에만 있고 파일·DB·로그·네트워크로 내보내지 않는다. 앱이 종료되면 사라진다.
 * - 검증 지표(오탐·차단 비율 등) 집계와 QA 화면에서 [snapshot]으로 읽는다.
 */
class CountingSafeDiagnostics : SafeDiagnostics {
    private val codeCounts = ConcurrentHashMap<DiagnosticCode, AtomicLong>()
    private val reasonCounts = ConcurrentHashMap<Pair<DiagnosticCode, SafetyReason>, AtomicLong>()

    override fun record(code: DiagnosticCode, reasons: ImmutableSet<SafetyReason>) {
        codeCounts.getOrPut(code) { AtomicLong() }.incrementAndGet()
        reasons.forEach { reason ->
            reasonCounts.getOrPut(code to reason) { AtomicLong() }.incrementAndGet()
        }
    }

    /** 지금까지의 횟수. 기록이 없는 코드는 0으로 채운다. */
    fun snapshot(): DiagnosticsSnapshot = DiagnosticsSnapshot(
        codes = DiagnosticCode.entries.associateWith { codeCounts[it]?.get() ?: 0L }.toImmutableMap(),
        reasons = reasonCounts.entries
            .associate { (key, count) -> key to count.get() }
            .toImmutableMap(),
    )

    /** QA 실험 사이에 초기화한다. */
    fun reset() {
        codeCounts.clear()
        reasonCounts.clear()
    }
}

data class DiagnosticsSnapshot(
    val codes: ImmutableMap<DiagnosticCode, Long>,
    val reasons: ImmutableMap<Pair<DiagnosticCode, SafetyReason>, Long>,
) {
    fun count(code: DiagnosticCode): Long = codes[code] ?: 0L

    fun count(code: DiagnosticCode, reason: SafetyReason): Long = reasons[code to reason] ?: 0L

    val total: Long get() = codes.values.sum()
}
