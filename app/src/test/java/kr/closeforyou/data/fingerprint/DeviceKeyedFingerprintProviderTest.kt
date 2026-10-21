package kr.closeforyou.data.fingerprint

import kr.closeforyou.core.contract.EvidenceExtractor
import kr.closeforyou.core.model.AnalysisLimit
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.PopupTrigger
import kr.closeforyou.core.model.RuleKey
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.SnapshotCompleteness
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.core.model.WindowSnapshot
import kr.closeforyou.core.model.WindowType
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 합성 화면만 사용한다. 실제 앱·개인정보 없음. */
class DeviceKeyedFingerprintProviderTest {

    private val notice = "서비스 점검 안내: 10월 25일 02:00~04:00 동안 결제 기능을 쓸 수 없습니다."
    private val popupBounds = ScreenBounds(100, 600, 980, 1500)
    private val closeBounds = ScreenBounds(700, 1380, 960, 1480)

    @Test
    fun sameScreenInANewSessionGivesTheSameKey() = runBlocking {
        val first = key(screen(session = 1))
        val second = key(screen(session = 2))
        assertEquals(first, second)
    }

    @Test
    fun whitespaceOnlyDifferencesAreIgnored() = runBlocking {
        val original = key(screen())
        val spaced = key(screen(body = "  서비스 점검 안내:\n10월 25일   02:00~04:00 동안 결제 기능을 쓸 수 없습니다. "))
        assertEquals(original.exactContentFingerprint, spaced.exactContentFingerprint)
    }

    @Test
    fun changedDateTimeOrAmountIsADifferentNotice() = runBlocking {
        val original = key(screen())
        listOf(
            notice.replace("25일", "26일"),
            notice.replace("02:00~04:00", "03:00~06:00"),
            "$notice 보상 3,000포인트 지급.",
        ).forEach { changedBody ->
            val changed = key(screen(body = changedBody))
            assertNotEquals(original.exactContentFingerprint, changed.exactContentFingerprint)
            assertEquals(original.structureFingerprint, changed.structureFingerprint)
            assertEquals(original.targetFingerprint, changed.targetFingerprint)
        }
    }

    @Test
    fun movedButtonChangesTheTargetFingerprint() = runBlocking {
        val original = key(screen())
        val moved = key(screen(closeAt = ScreenBounds(120, 620, 260, 720)))
        assertNotEquals(original.targetFingerprint, moved.targetFingerprint)
        assertEquals(original.exactContentFingerprint, moved.exactContentFingerprint)
    }

    @Test
    fun changedButtonLabelChangesTheTargetFingerprint() = runBlocking {
        assertNotEquals(key(screen()).targetFingerprint, key(screen(closeLabel = "확인")).targetFingerprint)
    }

    @Test
    fun appVersionIsPartOfTheKey() = runBlocking {
        val screen = screen()
        currentAnalysis = screen.analysis
        val v1 = provider().createKey(screen.snapshot, screen.target, 1)
        val v2 = provider().createKey(screen.snapshot, screen.target, 2)
        assertNotEquals(v1, v2)
        assertEquals(v1.copy(appVersionCode = 2), v2)
    }

    @Test
    fun anotherDeviceKeyCannotReproduceFingerprints() = runBlocking {
        val screen = screen()
        currentAnalysis = screen.analysis
        val a = provider(deviceKey = 1).createKey(screen.snapshot, screen.target, 1)
        val b = provider(deviceKey = 2).createKey(screen.snapshot, screen.target, 1)
        assertNotEquals(a.structureFingerprint, b.structureFingerprint)
        assertNotEquals(a.exactContentFingerprint, b.exactContentFingerprint)
        assertNotEquals(a.targetFingerprint, b.targetFingerprint)
    }

    @Test
    fun fingerprintsAreOpaqueHashesWithoutScreenText() = runBlocking {
        val key = key(screen())
        listOf(key.structureFingerprint, key.exactContentFingerprint, key.targetFingerprint).forEach {
            assertTrue(Regex("[0-9a-f]{64}").matches(it))
        }
        assertTrue("redacted" in key.toString())
    }

    @Test
    fun ocrTextIsBoundForImageNotices() = runBlocking {
        val original = key(imageScreen(ocr = "점검 10월 25일 02:00~04:00"))
        val changed = key(imageScreen(ocr = "점검 10월 26일 02:00~04:00"))
        assertNotEquals(original.exactContentFingerprint, changed.exactContentFingerprint)
    }

    @Test
    fun imagePopupWithoutOcrCannotBeRemembered() {
        assertFailure(FingerprintFailure.IMAGE_TEXT_UNVERIFIED) { key(imageScreen(ocr = null)) }
    }

    @Test
    fun visualOnlyTargetCannotBeRemembered() {
        val screen = screen(evidence = EvidenceLevel.E4_VISUAL_ONLY)
        assertFailure(FingerprintFailure.VISUAL_ONLY_TARGET) { key(screen) }
        val noNode = screen().let { it.copy(target = it.target.copy(nodePath = null)) }
        assertFailure(FingerprintFailure.VISUAL_ONLY_TARGET) { key(noNode) }
    }

    @Test
    fun analysisForAnotherScreenIsRejected() {
        val current = screen(session = 1)
        val stale = screen(session = 2)
        assertFailure(FingerprintFailure.STALE_ANALYSIS) {
            DeviceKeyedFingerprintProvider(FixedExtractor(stale.analysis), hasher(1))
                .createKey(current.snapshot, current.target, 1)
        }
    }

    @Test
    fun truncatedSnapshotIsRejected() {
        val screen = screen().let { it.copy(snapshot = it.snapshot.copy(completeness = SnapshotCompleteness.TRUNCATED)) }
        assertFailure(FingerprintFailure.TRUNCATED_SNAPSHOT) { key(screen) }
    }

    @Test
    fun incompleteEvidenceIsRejected() {
        listOf(AnalysisLimit.INCOMPLETE_EVIDENCE, AnalysisLimit.MOCK_ONLY).forEach { limit ->
            assertFailure(FingerprintFailure.INCOMPLETE_EVIDENCE) { key(screen(limits = setOf(limit))) }
        }
    }

    @Test
    fun targetMustComeFromTheCurrentAnalysis() {
        val screen = screen()
        val foreign = screen.target.copy(id = "other", label = "다른 버튼")
        assertFailure(FingerprintFailure.UNKNOWN_TARGET) { key(screen.copy(target = foreign)) }
    }

    @Test
    fun invalidAppVersionIsRejected() {
        val screen = screen()
        currentAnalysis = screen.analysis
        assertFailure(FingerprintFailure.INVALID_APP_VERSION) {
            provider().createKey(screen.snapshot, screen.target, 0)
        }
    }

    @Test
    fun screenWithoutAnyTextIsRejected() {
        assertFailure(FingerprintFailure.NO_CONTENT) { key(screen(body = "   ", closeLabel = null)) }
    }

    // ---- 합성 화면 ----

    private data class Screen(val snapshot: PopupSnapshot, val analysis: PopupAnalysis, val target: CloseCandidate)

    private fun screen(
        session: Long = 1,
        body: String = notice,
        closeLabel: String? = "닫기",
        closeAt: ScreenBounds = closeBounds,
        evidence: EvidenceLevel = EvidenceLevel.E2_LABELED_NODE,
        limits: Set<AnalysisLimit> = emptySet(),
    ): Screen {
        val token = token(session)
        val nodes = persistentListOf(
            node("0", "android.widget.FrameLayout", null, false, popupBounds),
            node("0/0", "android.widget.TextView", body, false, ScreenBounds(140, 640, 940, 1300)),
            node("0/1", "android.widget.Button", closeLabel, true, closeAt, viewId = "com.example.test:id/close"),
        )
        val target = CloseCandidate(
            id = "candidate-$session",
            nodePath = "0/1",
            viewId = "com.example.test:id/close",
            label = closeLabel,
            bounds = closeAt,
            evidence = evidence,
            role = ButtonRole.CLOSE,
        )
        val snapshot = snapshot(token, nodes)
        val analysis = PopupAnalysis(
            token = token,
            texts = persistentListOf(),
            closeCandidates = persistentListOf(target),
            limitations = limits.toPersistentSet(),
        )
        return Screen(snapshot, analysis, target)
    }

    private fun imageScreen(ocr: String?): Screen {
        val token = token(1)
        val nodes = persistentListOf(
            node("0", "android.widget.FrameLayout", null, false, popupBounds),
            node("0/0", "android.widget.ImageView", null, false, ScreenBounds(140, 640, 940, 1300)),
            node("0/1", "android.widget.Button", "닫기", true, closeBounds, viewId = "com.example.test:id/close"),
        )
        val target = CloseCandidate("c", "0/1", "com.example.test:id/close", "닫기", closeBounds,
            EvidenceLevel.E2_LABELED_NODE, ButtonRole.CLOSE)
        val texts = listOfNotNull(ocr?.let {
            ExtractedText(it, ScreenBounds(150, 700, 900, 760), TextSource.OCR, null)
        }).toPersistentList()
        val analysis = PopupAnalysis(token = token, texts = texts, closeCandidates = persistentListOf(target))
        return Screen(snapshot(token, nodes), analysis, target)
    }

    /** 세션마다 A의 토큰 값(버전·인스턴스·세션 지문)이 달라진다. */
    private fun token(session: Long) = SnapshotToken(
        screenVersion = 10 + session,
        windowId = 40 + session.toInt(),
        packageName = "com.example.test",
        popupInstanceId = "instance-$session",
        structureDigest = "session-structure-$session",
        contentDigest = "session-content-$session",
        captureEpoch = session,
    )

    private fun snapshot(token: SnapshotToken, nodes: List<NodeSnapshot>) = PopupSnapshot(
        token = token,
        capturedAtUptimeMillis = 1_000,
        nodes = nodes.toPersistentList(),
        windows = persistentListOf(
            WindowSnapshot(1, WindowType.APPLICATION, ScreenBounds(0, 0, 1080, 2400), false, false, 0),
            WindowSnapshot(2, WindowType.APPLICATION, popupBounds, true, true, 1),
        ),
        screenBounds = ScreenBounds(0, 0, 1080, 2400),
        trigger = PopupTrigger.WINDOW_CHANGED,
        completeness = SnapshotCompleteness.COMPLETE,
    )

    private fun node(
        path: String,
        className: String,
        text: String?,
        clickable: Boolean,
        bounds: ScreenBounds,
        viewId: String? = null,
    ) = NodeSnapshot(path, viewId, className, text, null, clickable, true, bounds, persistentSetOf())

    private class FixedExtractor(private val analysis: PopupAnalysis) : EvidenceExtractor {
        override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis = analysis
    }

    private fun hasher(deviceKey: Int) = KeyedHasher { data ->
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(ByteArray(32) { deviceKey.toByte() }, "HmacSHA256"))
        }.doFinal(data)
    }

    private fun provider(deviceKey: Int = 1): DeviceKeyedFingerprintProvider =
        DeviceKeyedFingerprintProvider(
            object : EvidenceExtractor {
                override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis =
                    currentAnalysis ?: error("no analysis")
            },
            hasher(deviceKey),
        )

    private var currentAnalysis: PopupAnalysis? = null

    private suspend fun key(screen: Screen): RuleKey {
        currentAnalysis = screen.analysis
        return provider().createKey(screen.snapshot, screen.target, 1)
    }

    private fun assertFailure(expected: FingerprintFailure, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("Expected $expected")
        } catch (e: FingerprintUnavailableException) {
            assertEquals(expected, e.reason)
        }
    }
}
