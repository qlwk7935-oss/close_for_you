package kr.closeforyou.data.fingerprint

import kr.closeforyou.core.contract.EvidenceExtractor
import kr.closeforyou.core.contract.PopupFingerprintProvider
import kr.closeforyou.core.model.AnalysisLimit
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.RuleKey
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.SnapshotCompleteness
import kr.closeforyou.core.model.TextSource
import java.text.Normalizer

/** 지문을 만들 수 없는 이유. 원문·좌표 없이 고정 코드만 담는다. */
enum class FingerprintFailure {
    INVALID_APP_VERSION,
    TRUNCATED_SNAPSHOT,
    STALE_ANALYSIS,
    INCOMPLETE_EVIDENCE,
    VISUAL_ONLY_TARGET,
    UNKNOWN_TARGET,
    IMAGE_TEXT_UNVERIFIED,
    NO_CONTENT,
}

/** 이 예외가 나면 호출자는 "기억할 수 없음"으로 처리하고 사용자에게 매번 묻는다. */
class FingerprintUnavailableException(val reason: FingerprintFailure) :
    IllegalStateException("Fingerprint unavailable: $reason")

/**
 * D: F12 동일 팝업 지문. A의 세션 지문(SnapshotToken digest)을 재사용하지 않고, 현재 F0-B 완료 분석
 * (트리 + OCR 본문)을 [EvidenceExtractor]로 다시 읽어 기기 내부 키로 결박한다.
 *
 * - 본문 지문: 정규화(NFC·공백 정리)만 하고 숫자·날짜·금액은 그대로 둔다. 날짜 하나만 바뀌어도 다른 공지다.
 * - 구조 지문: 창 종류, 노드 구조, 닫기 후보들의 역할·근거·상대 위치.
 * - 대상 지문: 누를 버튼의 역할·근거·리소스 ID·라벨·노드 경로·상대 위치. 위치·아이콘이 바뀌면 달라진다.
 *
 * 다음 경우에는 지문을 만들지 않는다(fail closed): 잘린 스냅샷, 토큰이 다른 분석, 불완전 근거,
 * 노드 없는 그림 X(E4), 분석에 없는 대상, OCR 없이 확인할 수 없는 이미지 팝업, 결박할 본문이 없음.
 * 지문이 같다는 사실은 실행 허가가 아니다. B가 정책·최신성·사용자 허락을 모두 다시 확인한다.
 */
class DeviceKeyedFingerprintProvider(
    private val evidenceExtractor: EvidenceExtractor,
    private val hasher: KeyedHasher,
) : PopupFingerprintProvider {

    override suspend fun createKey(
        snapshot: PopupSnapshot,
        target: CloseCandidate,
        appVersionCode: Long,
    ): RuleKey {
        if (appVersionCode <= 0) fail(FingerprintFailure.INVALID_APP_VERSION)
        if (snapshot.completeness != SnapshotCompleteness.COMPLETE) fail(FingerprintFailure.TRUNCATED_SNAPSHOT)
        if (target.evidence == EvidenceLevel.E4_VISUAL_ONLY || target.nodePath == null) {
            fail(FingerprintFailure.VISUAL_ONLY_TARGET)
        }

        val analysis = evidenceExtractor.extract(snapshot)
        if (analysis.token != snapshot.token) fail(FingerprintFailure.STALE_ANALYSIS)
        if (AnalysisLimit.INCOMPLETE_EVIDENCE in analysis.limitations ||
            AnalysisLimit.MOCK_ONLY in analysis.limitations
        ) {
            fail(FingerprintFailure.INCOMPLETE_EVIDENCE)
        }
        if (analysis.closeCandidates.none { it == target }) fail(FingerprintFailure.UNKNOWN_TARGET)
        if (imageTextUnverified(snapshot, analysis)) fail(FingerprintFailure.IMAGE_TEXT_UNVERIFIED)

        val reference = referenceBounds(snapshot, target.bounds)
        val content = contentBytes(snapshot, analysis) ?: fail(FingerprintFailure.NO_CONTENT)

        return RuleKey(
            packageName = snapshot.token.packageName,
            appVersionCode = appVersionCode,
            structureFingerprint = digest(structureBytes(snapshot, analysis, reference)),
            exactContentFingerprint = digest(content),
            targetFingerprint = digest(targetBytes(snapshot, target, reference)),
        )
    }

    // ---- 입력 직렬화 ----

    private fun contentBytes(snapshot: PopupSnapshot, analysis: PopupAnalysis): ByteArray? {
        val writer = CanonicalWriter(DOMAIN_CONTENT)
        var count = 0
        snapshot.nodes.sortedBy { it.path }.forEach { node ->
            val text = normalize(node.text)
            val description = normalize(node.contentDescription)
            if (text != null || description != null) {
                writer.text("node").text(node.path).text(text).text(description)
                count++
            }
        }
        analysis.texts
            .mapNotNull { item -> normalize(item.text)?.let { item to it } }
            .sortedWith(
                compareBy<Pair<ExtractedText, String>>(
                    { it.first.source }, { it.first.bounds.top }, { it.first.bounds.left }, { it.second },
                ),
            )
            .forEach { (item, text) ->
                writer.text(item.source.name).text(text)
                count++
            }
        return if (count == 0) null else writer.toByteArray()
    }

    private fun structureBytes(
        snapshot: PopupSnapshot,
        analysis: PopupAnalysis,
        reference: ScreenBounds,
    ): ByteArray {
        val writer = CanonicalWriter(DOMAIN_STRUCTURE)
        snapshot.windows.map { it.type.name }.sorted().forEach { writer.text("window").text(it) }
        snapshot.nodes.sortedBy { it.path }.forEach { node ->
            writer.text("node").text(node.path).text(node.className).text(node.viewId)
                .flag(node.clickable).flag(node.enabled)
                .text(node.actions.map { it.name }.sorted().joinToString(","))
        }
        analysis.closeCandidates
            .map { candidate ->
                listOf(
                    candidate.role.name, candidate.evidence.name, candidate.viewId.orEmpty(),
                    candidate.nodePath.orEmpty(), relativeCell(candidate.bounds, reference),
                ).joinToString("|")
            }
            .sorted()
            .forEach { writer.text("candidate").text(it) }
        writer.text("textSources").text(analysis.texts.map { it.source.name }.sorted().joinToString(","))
        return writer.toByteArray()
    }

    private fun targetBytes(
        snapshot: PopupSnapshot,
        target: CloseCandidate,
        reference: ScreenBounds,
    ): ByteArray {
        val node: NodeSnapshot? = snapshot.nodes.firstOrNull { it.path == target.nodePath }
        return CanonicalWriter(DOMAIN_TARGET)
            .text(target.role.name)
            .text(target.evidence.name)
            .text(target.viewId)
            .text(normalize(target.label))
            .text(target.nodePath)
            .text(node?.className)
            .text(normalize(node?.contentDescription))
            .text(relativeCell(target.bounds, reference))
            .toByteArray()
    }

    // ---- 보조 ----

    /** 이미지처럼 보이는데 글자 정보가 없고 OCR도 없으면 본문을 결박할 수 없다. */
    private fun imageTextUnverified(snapshot: PopupSnapshot, analysis: PopupAnalysis): Boolean {
        val hasOcr = analysis.texts.any { it.source == TextSource.OCR && normalize(it.text) != null }
        if (hasOcr) return false
        // 클릭 가능한 이미지 버튼(라벨 없는 X 등)은 본문이 아니라 대상 후보이므로 제외한다.
        val unlabeledImage = snapshot.nodes.any { node ->
            !node.clickable && node.className?.contains("Image", ignoreCase = true) == true &&
                normalize(node.text) == null && normalize(node.contentDescription) == null
        }
        val ocrMissing = AnalysisLimit.OCR_NOT_IMPLEMENTED in analysis.limitations
        val treeTextMissing = analysis.texts.none { it.source == TextSource.ACCESSIBILITY_TREE } &&
            snapshot.nodes.none { normalize(it.text) != null || normalize(it.contentDescription) != null }
        return unlabeledImage || (ocrMissing && treeTextMissing)
    }

    /** 대상이 들어 있는 가장 작은 창을 기준으로 삼는다. 없으면 화면 전체. */
    private fun referenceBounds(snapshot: PopupSnapshot, target: ScreenBounds): ScreenBounds {
        val cx = (target.left + target.right) / 2
        val cy = (target.top + target.bottom) / 2
        return snapshot.windows
            .map { it.bounds }
            .filter { cx in it.left..it.right && cy in it.top..it.bottom && it.width > 0 && it.height > 0 }
            .minByOrNull { it.width.toLong() * it.height }
            ?: snapshot.screenBounds
    }

    /** 기준 영역을 20x20 칸으로 나눈 위치·크기. 미세한 픽셀 차이는 흡수하고 위치 이동은 구분한다. */
    private fun relativeCell(bounds: ScreenBounds, reference: ScreenBounds): String {
        if (reference.width <= 0 || reference.height <= 0) return "no-reference"
        fun cell(value: Int, origin: Int, size: Int): Int =
            (((value - origin).toLong() * GRID) / size).toInt().coerceIn(-GRID, GRID * 2)
        val left = cell(bounds.left, reference.left, reference.width)
        val top = cell(bounds.top, reference.top, reference.height)
        val right = cell(bounds.right, reference.left, reference.width)
        val bottom = cell(bounds.bottom, reference.top, reference.height)
        return "$left,$top,$right,$bottom"
    }

    private fun digest(bytes: ByteArray): String =
        hasher.mac(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun fail(reason: FingerprintFailure): Nothing = throw FingerprintUnavailableException(reason)

    companion object {
        private const val GRID = 20
        private const val DOMAIN_CONTENT = "closeforyou.rule.content.v1"
        private const val DOMAIN_STRUCTURE = "closeforyou.rule.structure.v1"
        private const val DOMAIN_TARGET = "closeforyou.rule.target.v1"

        /** NFC 정규화와 공백 정리만 한다. 숫자·기호·대소문자는 바꾸지 않는다. */
        internal fun normalize(value: String?): String? {
            if (value == null) return null
            val text = Normalizer.normalize(value, Normalizer.Form.NFC)
                .replace(WHITESPACE, " ")
                .trim()
            return text.ifEmpty { null }
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
