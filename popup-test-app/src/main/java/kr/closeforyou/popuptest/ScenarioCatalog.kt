package kr.closeforyou.popuptest

/**
 * D: 팝업 재현 시나리오와 사람이 정한 정답지(A03·A05).
 *
 * 정답지는 평가 기준일 뿐 실제 감지·분류·클릭 성능 결과가 아니다. 모든 문구와 금액은 합성 데이터다.
 * 닫아줄게 본 앱 코드에 의존하지 않으며 본 앱에서도 이 모듈을 참조하지 않는다.
 */
enum class PopupKind { AD, NOTICE, USER_DECISION, SENSITIVE, NOT_POPUP }

/** 처음 본 화면에서 기대하는 처리. 자동 닫기는 사용자 허락과 동일 팝업 재검사 이후에만 가능하다. */
enum class ExpectedHandling {
    /** 내용 안내 후 사용자 선택. 처음 본 광고도 자동 클릭하지 않는다. */
    USER_CHOICE,
    /** 결제·동의·권한·구독 등 중요한 결정: 어떤 버튼도 자동 실행 금지. */
    AUTO_FORBIDDEN,
    /** 비밀번호 등 민감 화면: 캡처·분석·저장 차단. */
    BLOCK_ANALYSIS,
    /** 팝업이 아님: 감지되면 오탐. */
    NO_ACTION,
}

/** 닫기 근거 단계(통합설계 E1~E4). */
enum class CloseEvidence { E2_LABELED_CLICK, E3_UNLABELED_CLICKABLE, E4_VISUAL_ONLY, NONE }

enum class PopupSurface { DIALOG, SAME_WINDOW_OVERLAY, BOTTOM_BANNER, WEBVIEW, TOAST, FULL_SCREEN, VIDEO_OVERLAY }

data class PopupScenario(
    val id: String,
    val title: String,
    val surface: PopupSurface,
    val kind: PopupKind,
    val expected: ExpectedHandling,
    val closeEvidence: CloseEvidence,
    /** 사람 기준 정답 닫기 버튼의 리소스 ID 이름. 노드가 없거나 정답 버튼이 없으면 null. */
    val correctTargetId: String?,
    /**
     * 앱이 사용자 대신 눌러도 되는 버튼(사용자 확인 후 1회, 또는 허락된 기억으로 반복).
     * 팀 규칙(AGENTS 6-3, A 게이트)상 라벨 없는 X(E3)·그림 X(E4)·중요 결정·민감 화면은 null.
     * null인데 앱이 무언가를 누르면 오클릭, 값이 있는데 앱이 닫기를 제안하지 않으면 "잘못 거부"로 센다.
     */
    val appAllowedTargetId: String?,
    /** 자동으로 누르면 안 되는 버튼의 리소스 ID 이름. */
    val forbiddenTargetIds: Set<String>,
    /** 사용자에게 전달되어야 하는 핵심 정보(합성). */
    val keyFacts: List<String>,
    /** 사용자 허락 후 F12 기억 대상이 될 수 있는지. 허락 자체는 아님. */
    val rememberable: Boolean,
    /** 이 시나리오에 이전 기억을 그대로 재사용해도 되는 원본 시나리오 ID. */
    val memoryReusableFrom: String?,
    /** 관련 검증 실험 ID(docs/검증 실험 계획.md). */
    val experiments: Set<String>,
    val note: String,
)

object ScenarioCatalog {
    /** 공지 변형(C05) 공통 문구. */
    const val NOTICE_BASE = "서비스 점검 안내: 10월 25일 02:00~04:00 동안 결제 기능을 쓸 수 없습니다."

    /** P16 영상 광고: 이 시간(초)이 지나야 [건너뛰기] 버튼이 생긴다. */
    const val VIDEO_SKIP_AFTER_SECONDS = 5

    /** P18 타임세일 남은 시간(초). 건너뛰기와 상관없는 카운트다운이다. */
    const val SALE_COUNTDOWN_SECONDS = 300

    val all: List<PopupScenario> = listOf(
        PopupScenario(
            id = "P01", title = "라벨 있는 광고 대화상자", surface = PopupSurface.DIALOG,
            kind = PopupKind.AD, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close", forbiddenTargetIds = setOf("btn_ad_open"),
            keyFacts = listOf("가을 할인 쿠폰 광고"), rememberable = true, memoryReusableFrom = null,
            experiments = setOf("B01", "B03", "C01", "C03", "C04"),
            note = "기본 대화상자. 처음 본 광고는 안내 후 사용자 선택.",
        ),
        PopupScenario(
            id = "P02", title = "같은 창 덮개 광고", surface = PopupSurface.SAME_WINDOW_OVERLAY,
            kind = PopupKind.AD, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close", forbiddenTargetIds = setOf("btn_ad_open"),
            keyFacts = listOf("신규 가입 이벤트 광고"), rememberable = true, memoryReusableFrom = null,
            experiments = setOf("B01", "C01"),
            note = "별도 창 없이 같은 Activity 위에 그린 덮개.",
        ),
        PopupScenario(
            id = "P03", title = "작은 하단 배너", surface = PopupSurface.BOTTOM_BANNER,
            kind = PopupKind.AD, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close", forbiddenTargetIds = setOf("btn_ad_open"),
            keyFacts = listOf("앱 설치 유도 배너"), rememberable = true, memoryReusableFrom = null,
            experiments = setOf("C01"),
            note = "화면 일부만 가리는 작은 팝업.",
        ),
        PopupScenario(
            id = "P04", title = "라벨 없는 X 버튼", surface = PopupSurface.DIALOG,
            kind = PopupKind.AD, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E3_UNLABELED_CLICKABLE,
            correctTargetId = "btn_unlabeled_x", appAllowedTargetId = null, forbiddenTargetIds = setOf("btn_ad_open"),
            keyFacts = listOf("게임 아이템 광고"), rememberable = false, memoryReusableFrom = null,
            experiments = setOf("B03", "C03"),
            note = "클릭 가능한 노드지만 텍스트·설명이 없음. 사람 기준 정답은 X지만 앱은 누르지도 후보로 내지도 않음(AGENTS 6-3).",
        ),
        PopupScenario(
            id = "P05", title = "노드 없는 그림 X", surface = PopupSurface.DIALOG,
            kind = PopupKind.AD, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E4_VISUAL_ONLY,
            correctTargetId = null, appAllowedTargetId = null, forbiddenTargetIds = emptySet(),
            keyFacts = listOf("이미지 전면 광고"), rememberable = false, memoryReusableFrom = null,
            experiments = setOf("B03", "C03"),
            note = "X가 그림에만 있고 클릭 노드가 없음. 좌표 탭으로만 닫힘. E4 자동화 금지, 위치 안내만.",
        ),
        PopupScenario(
            id = "P06", title = "가짜 X(광고 페이지로 이동)", surface = PopupSurface.DIALOG,
            kind = PopupKind.AD, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close", forbiddenTargetIds = setOf("btn_fake_x"),
            keyFacts = listOf("X처럼 보이는 버튼이 실제로는 광고를 연다"), rememberable = true,
            memoryReusableFrom = null, experiments = setOf("A05", "C03"),
            note = "오른쪽 위 라벨 없는 X는 광고 페이지를 연다. 정답은 아래쪽 '닫기'.",
        ),
        PopupScenario(
            id = "P07", title = "글자가 그림에만 있는 공지", surface = PopupSurface.DIALOG,
            kind = PopupKind.NOTICE, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close", forbiddenTargetIds = emptySet(),
            keyFacts = listOf("점검 10월 25일 02:00~04:00", "결제 기능 중단"), rememberable = true,
            memoryReusableFrom = null, experiments = setOf("B01", "C02"),
            note = "본문이 비트맵 안에만 있고 접근성 텍스트가 없음. OCR 필요.",
        ),
        PopupScenario(
            id = "P08", title = "가짜 결제 확인", surface = PopupSurface.DIALOG,
            kind = PopupKind.USER_DECISION, expected = ExpectedHandling.AUTO_FORBIDDEN,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = null, appAllowedTargetId = null, forbiddenTargetIds = setOf("btn_pay", "btn_cancel"),
            keyFacts = listOf("시험용 상품 12,900원 결제"), rememberable = false,
            memoryReusableFrom = null, experiments = setOf("A05", "C04", "C06"),
            note = "결제·취소 어느 쪽도 자동 실행 금지.",
        ),
        PopupScenario(
            id = "P09", title = "구독 '취소' 함정", surface = PopupSurface.DIALOG,
            kind = PopupKind.USER_DECISION, expected = ExpectedHandling.AUTO_FORBIDDEN,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = null, appAllowedTargetId = null, forbiddenTargetIds = setOf("btn_cancel_sub", "btn_later"),
            keyFacts = listOf("'취소'를 누르면 구독이 해지됨"), rememberable = false,
            memoryReusableFrom = null, experiments = setOf("A05", "C04", "C06"),
            note = "'취소' 라벨이 닫기가 아니라 구독 해지를 뜻함.",
        ),
        PopupScenario(
            id = "P10", title = "가짜 권한·동의 요청", surface = PopupSurface.DIALOG,
            kind = PopupKind.USER_DECISION, expected = ExpectedHandling.AUTO_FORBIDDEN,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = null, appAllowedTargetId = null, forbiddenTargetIds = setOf("btn_allow", "btn_deny"),
            keyFacts = listOf("마케팅 정보 수신 동의"), rememberable = false,
            memoryReusableFrom = null, experiments = setOf("A05", "C06"),
            note = "실제 시스템 권한창이 아닌 앱 안의 합성 동의창.",
        ),
        PopupScenario(
            id = "P11", title = "가짜 로그인·비밀번호 입력", surface = PopupSurface.DIALOG,
            kind = PopupKind.SENSITIVE, expected = ExpectedHandling.BLOCK_ANALYSIS,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = null, appAllowedTargetId = null, forbiddenTargetIds = setOf("btn_login"),
            keyFacts = emptyList(), rememberable = false,
            memoryReusableFrom = null, experiments = setOf("B05", "C06"),
            note = "비밀번호 입력칸. 캡처·분석·저장 차단이 정답. 실제 비밀번호를 입력하지 않는다.",
        ),
        PopupScenario(
            id = "P12", title = "연속 팝업(광고→공지→동의)", surface = PopupSurface.DIALOG,
            kind = PopupKind.AD, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close", forbiddenTargetIds = setOf("btn_allow"),
            keyFacts = listOf("세 번째 창은 동의 요청"), rememberable = false,
            memoryReusableFrom = null, experiments = setOf("C08"),
            note = "광고를 닫으면 공지, 공지를 닫으면 동의창. 동의창까지 연쇄 클릭하면 실패.",
        ),
        PopupScenario(
            id = "P13", title = "WebView 모달 광고", surface = PopupSurface.WEBVIEW,
            kind = PopupKind.AD, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = null, appAllowedTargetId = null, forbiddenTargetIds = emptySet(),
            keyFacts = listOf("웹 페이지 안의 쿠폰 광고"), rememberable = false,
            memoryReusableFrom = null, experiments = setOf("B01", "C01"),
            note = "assets/popup_test_page.html#ad 를 앱 안 WebView로 표시. 웹 버튼은 리소스 ID가 없음. 노드 노출 여부는 실기기 체크리스트 P9 확인 후 정답지 갱신.",
        ),
        PopupScenario(
            id = "P14", title = "일반 화면 전환(팝업 아님)", surface = PopupSurface.FULL_SCREEN,
            kind = PopupKind.NOT_POPUP, expected = ExpectedHandling.NO_ACTION,
            closeEvidence = CloseEvidence.NONE,
            correctTargetId = null, appAllowedTargetId = null, forbiddenTargetIds = setOf("btn_next"),
            keyFacts = emptyList(), rememberable = false,
            memoryReusableFrom = null, experiments = setOf("C01"),
            note = "오탐 확인용. 전체 화면 페이지 전환.",
        ),
        PopupScenario(
            id = "P15", title = "토스트(팝업 아님)", surface = PopupSurface.TOAST,
            kind = PopupKind.NOT_POPUP, expected = ExpectedHandling.NO_ACTION,
            closeEvidence = CloseEvidence.NONE,
            correctTargetId = null, appAllowedTargetId = null, forbiddenTargetIds = emptySet(),
            keyFacts = emptyList(), rememberable = false,
            memoryReusableFrom = null, experiments = setOf("C01"),
            note = "오탐 확인용. 잠깐 나타나는 토스트.",
        ),
        PopupScenario(
            id = "P16", title = "영상 광고 건너뛰기 카운트다운", surface = PopupSurface.VIDEO_OVERLAY,
            kind = PopupKind.AD, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_skip", appAllowedTargetId = "btn_skip", forbiddenTargetIds = setOf("btn_ad_open"),
            keyFacts = listOf("5초 뒤 건너뛰기 가능"), rememberable = false, memoryReusableFrom = null,
            experiments = setOf("B03", "C07"),
            note = "전체 화면 영상 광고(합성, 실제 영상 없음). 재생 시간이 매초 바뀌고, 5초 카운트다운이 끝나야 '건너뛰기'가 생김. 그 전에는 누를 버튼이 없음. 글자가 계속 바뀌어 기억 대상 아님.",
        ),
        PopupScenario(
            id = "P17", title = "닫기·건너뛰기 함께 있는 광고", surface = PopupSurface.DIALOG,
            kind = PopupKind.AD, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close", forbiddenTargetIds = emptySet(),
            keyFacts = listOf("건너뛰기는 다음 광고, 닫기는 광고 끝"), rememberable = true, memoryReusableFrom = null,
            experiments = setOf("B03", "C09"),
            note = "라벨 있는 버튼이 둘(E2). '건너뛰기'는 두 번째 광고를 열고 '닫기'만 광고를 끝냄. 기억은 '닫기'에만 맞아야 함(F12 후보 여러 개).",
        ),
        PopupScenario(
            id = "P18", title = "건너뛰기와 무관한 시간 문구", surface = PopupSurface.DIALOG,
            kind = PopupKind.AD, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close", forbiddenTargetIds = setOf("btn_ad_open"),
            keyFacts = listOf("타임세일 남은 시간은 건너뛰기 카운트다운이 아님"), rememberable = false,
            memoryReusableFrom = null, experiments = setOf("C07"),
            note = "세일 종료 카운트다운(매초 변경)과 '5초 안에 결제' 같은 시간 문구가 있지만 건너뛰기와 무관(B #20 오인 확인). 글자가 계속 바뀌어 기억 대상 아님.",
        ),
    ) + noticeVariants()

    private fun notice(id: String, title: String, body: String, reuse: String?, fact: String) =
        PopupScenario(
            id = id, title = title, surface = PopupSurface.DIALOG,
            kind = PopupKind.NOTICE, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close",
            forbiddenTargetIds = emptySet(),
            keyFacts = listOf(fact), rememberable = true, memoryReusableFrom = reuse,
            experiments = setOf("C05", "C09"), note = body,
        )

    /** C05: 같은 공지와 일부만 바뀐 공지. N02만 N01의 기억을 재사용할 수 있다. */
    private fun noticeVariants(): List<PopupScenario> = listOf(
        notice("N01", "공지 원본", NOTICE_BASE, null, "10월 25일 02:00~04:00 결제 중단"),
        notice("N02", "공지 동일 반복", NOTICE_BASE, "N01", "10월 25일 02:00~04:00 결제 중단"),
        notice("N03", "공지 날짜만 변경",
            "서비스 점검 안내: 10월 26일 02:00~04:00 동안 결제 기능을 쓸 수 없습니다.", null,
            "날짜가 10월 26일로 바뀜"),
        notice("N04", "공지 시간만 변경",
            "서비스 점검 안내: 10월 25일 03:00~06:00 동안 결제 기능을 쓸 수 없습니다.", null,
            "시간이 03:00~06:00으로 바뀜"),
        notice("N05", "공지 금액 추가",
            "서비스 점검 안내: 10월 25일 02:00~04:00 동안 결제 기능을 쓸 수 없습니다. 보상 3,000포인트 지급.",
            null, "보상 금액 추가"),
        notice("N06", "공지 행동 요구 추가",
            "서비스 점검 안내: 10월 25일 02:00~04:00 동안 결제 기능을 쓸 수 없습니다. 점검 전 앱을 업데이트하세요.",
            null, "업데이트 요구 추가"),
        notice("N07", "공지 본문 완전 변경",
            "개인정보 처리방침이 11월 1일부터 변경됩니다.", null, "다른 공지"),
    )

    fun find(id: String): PopupScenario? = all.firstOrNull { it.id == id }
}
