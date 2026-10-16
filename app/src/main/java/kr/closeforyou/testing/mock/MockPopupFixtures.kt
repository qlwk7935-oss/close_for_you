package kr.closeforyou.testing.mock

import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.model.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/** Synthetic data only. Expected labels are test answers, never classifier results. */
data class MockPopupFixture(
    val scenarioId: String,
    val snapshot: PopupSnapshot,
    val analysis: PopupAnalysis,
    val expectedDisplayClassification: PopupClassification,
) {
    override fun toString(): String = "MockPopupFixture(redacted)"
}

/** No real app data, nodes, screenshots, network, or Android dependencies. */
object MockPopupFixtures {
    const val PACKAGE_NAME = "kr.closeforyou.mock.host"
    private val screen = ScreenBounds(0, 0, 1080, 1920)
    private val dialog = ScreenBounds(80, 400, 1000, 1450)
    private val button = ScreenBounds(760, 440, 960, 550)

    fun advertisement(): MockPopupFixture = create(
        scenario = "advertisement",
        body = "광고 · 가상 체험 상품 안내",
        expected = PopupClassification.ADVERTISEMENT,
    )

    fun notice(): MockPopupFixture = create(
        scenario = "notice",
        body = "가상 서비스 점검: 2026년 10월 18일 02시부터 04시까지",
        expected = PopupClassification.NOTICE,
        contentKey = "notice-date-18",
    )

    /** Same structure and button as notice(), but the changed date is a new exact content key. */
    fun changedDateNotice(): MockPopupFixture = create(
        scenario = "notice-changed-date",
        body = "가상 서비스 점검: 2026년 10월 19일 02시부터 04시까지",
        expected = PopupClassification.NOTICE,
        contentKey = "notice-date-19",
    )

    fun decision(): MockPopupFixture = create(
        scenario = "important-decision",
        body = "시험용 구독 결제에 동의하시겠습니까? 실제 결제는 없습니다.",
        expected = PopupClassification.USER_DECISION,
        label = "동의",
        role = ButtonRole.USER_DECISION,
    )

    /** An unlabeled clickable node alone is not verified E3 evidence or a close candidate. */
    fun unlabeledButton(): MockPopupFixture = create(
        scenario = "unlabeled-button",
        body = "가상 이미지 팝업: 버튼의 의미는 확인되지 않았습니다.",
        expected = PopupClassification.UNKNOWN,
        label = null,
    )

    /** F5 evidence is separate from the advertisement answer; F6 has no API here. */
    fun videoWithoutAdEvidence(): MockPopupFixture = create(
        scenario = "video-without-ad-evidence",
        body = "가상 영상 창 · 광고 여부 미확인",
        expected = PopupClassification.UNKNOWN,
        video = true,
    )

    /** A protected screen exposes only the reason and generation, never a text-bearing snapshot. */
    fun privacyBlocked(screenVersion: Long = 7): PopupEvent.Blocked =
        PopupEvent.Blocked(PrivacyBlockReason.PASSWORD, screenVersion)

    fun all(): ImmutableList<MockPopupFixture> = persistentListOf(
        advertisement(), notice(), changedDateNotice(), decision(),
        unlabeledButton(), videoWithoutAdEvidence(),
    )

    private fun create(
        scenario: String,
        body: String,
        expected: PopupClassification,
        contentKey: String = scenario,
        label: String? = "닫기",
        role: ButtonRole = ButtonRole.CLOSE,
        video: Boolean = false,
    ): MockPopupFixture {
        // Explicit fixture identifiers, not production fingerprints or screen hashes.
        val token = SnapshotToken(
            screenVersion = 1,
            windowId = 10,
            packageName = PACKAGE_NAME,
            popupInstanceId = "fixture-$scenario",
            structureDigest = "fixture-dialog-structure-v1",
            contentDigest = "fixture-content-$contentKey",
            captureEpoch = 0,
        )
        val nodes = persistentListOf(
            NodeSnapshot(
                path = "0", viewId = "fixture:body", className = "android.widget.TextView",
                text = body, contentDescription = null, clickable = false, enabled = true,
                bounds = dialog,
            ),
            NodeSnapshot(
                path = "1", viewId = "fixture:button", className = "android.widget.Button",
                text = label, contentDescription = null, clickable = true, enabled = true,
                bounds = button, actions = persistentSetOf(NodeAction.CLICK),
            ),
        ).let { base ->
            if (!video) base else base.add(
                NodeSnapshot(
                    path = "2", viewId = "fixture:video", className = "android.view.SurfaceView",
                    text = null, contentDescription = null, clickable = false, enabled = true,
                    bounds = ScreenBounds(100, 600, 980, 1200),
                ),
            )
        }
        val snapshot = PopupSnapshot(
            token = token,
            capturedAtUptimeMillis = 1000,
            nodes = nodes,
            windows = persistentListOf(WindowSnapshot(10, WindowType.APPLICATION, dialog, true, true, 1)),
            screenBounds = screen,
            trigger = PopupTrigger.WINDOW_CHANGED,
            completeness = SnapshotCompleteness.COMPLETE,
        )
        val candidates = if (label == null) persistentListOf() else persistentListOf(
            CloseCandidate(
                id = "fixture-button", nodePath = "1", viewId = "fixture:button",
                label = label, bounds = button, evidence = EvidenceLevel.E2_LABELED_NODE,
                role = role,
            ),
        )
        return MockPopupFixture(
            scenarioId = scenario,
            snapshot = snapshot,
            analysis = PopupAnalysis(
                token = token,
                // Test answers above must not leak into the unimplemented B classifier.
                classification = PopupClassification.UNKNOWN,
                texts = persistentListOf(ExtractedText(body, dialog, TextSource.ACCESSIBILITY_TREE, null)),
                closeCandidates = candidates,
                limitations = persistentSetOf(AnalysisLimit.MOCK_ONLY, AnalysisLimit.OCR_NOT_IMPLEMENTED),
                videoSurfaceObserved = video,
            ),
            expectedDisplayClassification = expected,
        )
    }
}
