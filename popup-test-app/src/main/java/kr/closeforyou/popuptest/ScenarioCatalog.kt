package kr.closeforyou.popuptest

/**
 * D: 팝업 재현 시나리오와 사람이 정한 정답지(A03·A05).
 *
 * 정답지는 평가 기준일 뿐 실제 감지·분류·클릭 성능 결과가 아니다. 모든 문구와 금액은 합성 데이터다.
 * 닫아줄게 본 앱 코드에 의존하지 않으며 본 앱에서도 이 모듈을 참조하지 않는다.
 */
/**
 * 사람 기준 종류. [COUPON]은 받거나 가진 혜택(쿠폰·적립금·포인트 지급 등, 계약 v2 PopupClassification.COUPON과 같은 뜻).
 * [SUSPICIOUS]는 당첨·감염 같은 사기 의심 팝업(B SUSPICIOUS_CONTENT): 어떤 버튼도 앱이 누르지 않고 경고한다.
 */
enum class PopupKind { AD, COUPON, NOTICE, SUSPICIOUS, USER_DECISION, SENSITIVE, NOT_POPUP }

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

/**
 * [BOTTOM_SHEET]: 화면 아래에서 올라오는 시트. [FRONT_LAYER]: Dialog 표시 없이 같은 창에서 화면 전체를 덮는 레이어(11/2 추가).
 */
enum class PopupSurface { DIALOG, SAME_WINDOW_OVERLAY, BOTTOM_BANNER, WEBVIEW, TOAST, FULL_SCREEN, VIDEO_OVERLAY, BOTTOM_SHEET, FRONT_LAYER }

/** 팀 프리셋 3개. C `HandlingPreset`과 이름·뜻이 같다(앱 모듈 `AnswerKeyPipelineTest`가 대조). */
enum class TestPreset { CLOSE_ALL, CLOSE_ADS_AND_COUPONS, CLOSE_ADS_ONLY }

/**
 * 프리셋을 고르고 자동 닫기에 동의한 뒤의 **목표** 처리(사람 기준 종류로 정함). 허락이 아니며,
 * 실제 누르기는 B 정책·A 관문이 다시 판단한다. 지금은 자동 닫기가 꺼져 있어 모두 선택 창으로 묻는다.
 */
enum class PresetGoal { CLOSE, ANNOUNCE, ASK, BLOCK, NONE }

/** 첫 통합에서 지금 코드로 예상되는 B 분류(A 경로 포함). [BLOCKED]는 A가 분석 전에 막음, [NONE]은 팝업 아님. */
enum class Int1Kind { COUPON, ADVERTISEMENT, NOTICE, USER_DECISION, UNKNOWN, BLOCKED, NONE }

/** 그 분류에 이른 A 경로: 트리 글자 그대로, A 결정 요약(DecisionEvidence), A F0-A 차단. */
enum class Int1Path { TREE, A_SUMMARY, A_BLOCK, NONE }

/** [reason]은 사람 기준 종류와 다를 때 왜 다른지. 화면 원문이 아니라 시험 문구에 대한 설명이다. */
data class Int1Expectation(val kind: Int1Kind, val path: Int1Path, val reason: String = "")

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
    /**
     * 공지 변형(C05) 공통 문구. '결제'를 넣으면 A가 결정 요약으로 바꿔 공지로 분류되지 않으므로 '주문'을 쓴다(10/25).
     * 결제 문구 때문에 결정이 되는 한계는 P18이 맡는다.
     */
    const val NOTICE_BASE = "서비스 점검 안내: 10월 25일 02:00~04:00 동안 주문 기능을 쓸 수 없습니다."

    /** P16 영상 광고: 이 시간(초)이 지나야 [건너뛰기] 버튼이 생긴다. */
    const val VIDEO_SKIP_AFTER_SECONDS = 5

    /** P16 빈 영상 표면(VideoView)의 리소스 ID. 재생하지 않으며 영상 광고의 증거가 아니다. */
    const val VIDEO_SURFACE_ID = "video_surface"

    /**
     * C09: 글자는 N01과 같고 닫기 버튼만 본문 위로 옮긴 공지. 대상 버튼의 위치가 달라졌으므로
     * N01 기억을 재사용하면 안 된다(F12 대상 지문에 상대 위치가 들어감).
     */
    const val BUTTON_MOVED_NOTICE_ID = "N08"

    /** P18 타임세일 남은 시간(초). 건너뛰기와 상관없는 카운트다운이다. */
    const val SALE_COUNTDOWN_SECONDS = 300

    /** P20(S17): 차례로 띄우는 서로 다른 광고 수. B 자동 닫기 속도 제한(1분 3개)을 넘겨야 한다. */
    const val AD_SERIES_COUNT = 4

    /** P23(S20): '닫기' 버튼이 이 시간(초) 뒤에 생긴다. */
    const val DELAYED_CLOSE_SECONDS = 3

    val all: List<PopupScenario> = listOf(
        PopupScenario(
            id = "P01", title = "라벨 있는 광고 대화상자", surface = PopupSurface.DIALOG,
            kind = PopupKind.COUPON, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close", forbiddenTargetIds = setOf("btn_ad_open"),
            keyFacts = listOf("가을 할인 쿠폰 광고"), rememberable = true, memoryReusableFrom = null,
            experiments = setOf("B01", "B03", "C01", "C03", "C04"),
            note = "기본 대화상자. 처음 본 광고는 안내 후 사용자 선택.",
        ),
        PopupScenario(
            id = "P02", title = "같은 창 덮개 광고", surface = PopupSurface.SAME_WINDOW_OVERLAY,
            kind = PopupKind.COUPON, expected = ExpectedHandling.USER_CHOICE,
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
            keyFacts = listOf("점검 10월 25일 02:00~04:00", "주문 기능 중단"), rememberable = true,
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
            kind = PopupKind.COUPON, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close", forbiddenTargetIds = setOf("btn_allow"),
            keyFacts = listOf("세 번째 창은 동의 요청"), rememberable = false,
            memoryReusableFrom = null, experiments = setOf("C08"),
            note = "광고를 닫으면 공지, 공지를 닫으면 동의창. 동의창까지 연쇄 클릭하면 실패.",
        ),
        PopupScenario(
            id = "P13", title = "WebView 모달 광고", surface = PopupSurface.WEBVIEW,
            kind = PopupKind.COUPON, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            // 11/2 팀 결정(B_DEMO_RULES 9-3): LG에서 웹 "닫기"(data-id) 누르기·닫힘 확인이 됐다 → 프리셋 CLOSE·CLOSE·ANNOUNCE(P01과 같음).
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close", forbiddenTargetIds = setOf("btn_ad_open"),
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
            note = "전체 화면 영상 광고(합성). 빈 VideoView(video_surface, 재생 없음)가 있고, 재생 시간이 매초 바뀌며, 5초 카운트다운이 끝나야 '건너뛰기'가 생김. 그 전에는 누를 버튼이 없음. Dialog 없는 앱 자체 덮개라 현행 F1은 후보를 못 잡을 수 있음. 글자가 계속 바뀌어 기억 대상 아님.",
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
        PopupScenario(
            id = "P19", title = "닫기가 앱 밖으로 보내는 광고", surface = PopupSurface.DIALOG,
            kind = PopupKind.AD, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = null, appAllowedTargetId = null, forbiddenTargetIds = setOf("btn_fake_close"),
            keyFacts = listOf("'닫기'를 누르면 홈 화면으로 나감"), rememberable = false, memoryReusableFrom = null,
            experiments = setOf("A05", "C03"),
            note = "S16. 라벨이 '닫기'인 버튼이 팝업을 닫지 않고 앱 밖(홈 화면)으로 보낸다. 겉으로는 진짜 닫기와 구별할 수 없어 첫 1회 자동 누름은 알려진 한계이고, 그 뒤 LEFT_APP으로 이 앱 자동 닫기가 멈춰야 한다. 진짜 닫기는 뒤로 가기뿐.",
        ),
        PopupScenario(
            id = "P20", title = "1분 안에 서로 다른 광고 4개", surface = PopupSurface.DIALOG,
            kind = PopupKind.AD, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close", forbiddenTargetIds = emptySet(),
            keyFacts = listOf("광고 4개가 이어서 나옴"), rememberable = false, memoryReusableFrom = null,
            experiments = setOf("C08"),
            note = "S17. 광고를 '닫기'로 닫으면 2초 뒤 다른 광고가 나온다(모두 4개, B ChainGuard 1.5초보다 길게 11/5). 프리셋 자동 닫기는 1분에 3개까지, 4번째는 묻기(RATE_LIMIT).",
        ),
        PopupScenario(
            id = "P21", title = "사기 의심 - 1등 당첨", surface = PopupSurface.DIALOG,
            kind = PopupKind.SUSPICIOUS, expected = ExpectedHandling.AUTO_FORBIDDEN,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = null, forbiddenTargetIds = setOf("btn_prize_claim"),
            keyFacts = listOf("당첨 사기 의심"), rememberable = false, memoryReusableFrom = null,
            experiments = setOf("A05", "C06"),
            note = "S18. 어떤 프리셋에서도 앱이 누르지 않고 경고한다(B SUSPICIOUS_CONTENT). '경품 받기'는 광고 페이지를 연다. 사람은 '닫기'로 닫을 수 있다.",
        ),
        PopupScenario(
            id = "P22", title = "사기 의심 - 휴대폰 감염", surface = PopupSurface.DIALOG,
            kind = PopupKind.SUSPICIOUS, expected = ExpectedHandling.AUTO_FORBIDDEN,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = null, forbiddenTargetIds = setOf("btn_scan_now"),
            keyFacts = listOf("감염 경고 사기 의심"), rememberable = false, memoryReusableFrom = null,
            experiments = setOf("A05", "C06"),
            note = "S18. 어떤 프리셋에서도 앱이 누르지 않고 경고한다(B SUSPICIOUS_CONTENT). '지금 검사하기'는 광고 페이지를 연다. 사람은 '닫기'로 닫을 수 있다.",
        ),
        PopupScenario(
            id = "P23", title = "닫기가 늦게 나오는 쿠폰", surface = PopupSurface.DIALOG,
            kind = PopupKind.COUPON, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close", forbiddenTargetIds = emptySet(),
            keyFacts = listOf("3초 뒤 닫기 버튼이 생김"), rememberable = false, memoryReusableFrom = null,
            experiments = setOf("C07"),
            note = "S20. 처음에는 누를 버튼이 없고 3초 뒤 '닫기'가 생긴다. 버튼 전에는 기다리기(WAIT), 생긴 뒤 프리셋에 따라 닫기.",
        ),
        PopupScenario(
            id = "P24", title = "뒤 페이지에 결정 문구가 있는 웹 광고", surface = PopupSurface.WEBVIEW,
            kind = PopupKind.COUPON, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            // 11/2 팀 결정(B_DEMO_RULES 9-4): 팝업 자신의 "닫기"만 누른다 → 프리셋 CLOSE·CLOSE·ANNOUNCE. int1(A_SUMMARY)은 A 회귀 시험이라 그대로.
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close", forbiddenTargetIds = setOf("btn_ad_open"),
            keyFacts = listOf("팝업은 쿠폰이지만 뒤 페이지에 결제·동의 문구가 있음"), rememberable = false,
            memoryReusableFrom = null, experiments = setOf("A05", "B01"),
            note = "P13과 같은 쿠폰 모달인데 뒤 페이지에 '결제'·'동의' 문구가 남아 있다(A 10/25 요청 회귀 시험). 실제 앱처럼 뒤 문구가 섞이면 A가 결정으로 요약해 막아야 한다. P13의 목록 숨김으로 이 안전 차단이 사라지지 않았는지 본다.",
        ),
        PopupScenario(
            id = "P33", title = "아래에서 올라오는 시트", surface = PopupSurface.BOTTOM_SHEET,
            kind = PopupKind.COUPON, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close", forbiddenTargetIds = setOf("btn_ad_open"),
            keyFacts = listOf("화면 아래에 붙어 올라온 쿠폰 시트"), rememberable = true, memoryReusableFrom = null,
            experiments = setOf("C01"),
            note = "11/2 추가(A 요청, 실기기 재현용). Dialog 창이지만 화면 아래에 붙고 가로를 꽉 채운다(BottomSheetDialog 모양). 이름 있는 '닫기'가 있다.",
        ),
        PopupScenario(
            id = "P34", title = "화면 전체를 덮는 큰 레이어", surface = PopupSurface.FRONT_LAYER,
            kind = PopupKind.AD, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_close", appAllowedTargetId = "btn_close", forbiddenTargetIds = setOf("btn_ad_open"),
            keyFacts = listOf("Dialog 표시 없이 화면 전체를 덮는 광고"), rememberable = true, memoryReusableFrom = null,
            experiments = setOf("C01"),
            note = "11/2 추가(A 요청). Dialog·어둡게 하기 없이 같은 창에서 화면 전체를 덮는 레이어(앱 자체 전면 광고). 큰 그림(글자 없음)과 이름 있는 '닫기'가 오른쪽 위에 있다.",
        ),
        PopupScenario(
            id = "P35", title = "앱에서 열기·모바일 웹 시트", surface = PopupSurface.BOTTOM_SHEET,
            kind = PopupKind.AD, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_stay_web", appAllowedTargetId = "btn_stay_web", forbiddenTargetIds = setOf("btn_open_app"),
            keyFacts = listOf("앱 설치·열기 유도"), rememberable = true, memoryReusableFrom = null,
            experiments = setOf("C01"),
            note = "11/2 추가(A 요청). 모바일 웹에서 흔한 '앱에서 열기 / 모바일 웹으로 볼게요' 시트를 Dialog 없이 같은 창 아래에 그린다. 닫는 버튼은 '모바일 웹으로 볼게요'이고 '앱에서 열기'는 광고 페이지를 연다(앱이 누르면 실패).",
        ),
        PopupScenario(
            id = "P36", title = "오늘 하루 보지 않기만 있는 팝업", surface = PopupSurface.DIALOG,
            kind = PopupKind.AD, expected = ExpectedHandling.USER_CHOICE,
            closeEvidence = CloseEvidence.E2_LABELED_CLICK,
            correctTargetId = "btn_hide_today", appAllowedTargetId = "btn_hide_today", forbiddenTargetIds = setOf("btn_ad_open"),
            keyFacts = listOf("닫기 없이 '오늘 하루 보지 않기'만 있는 광고"), rememberable = false, memoryReusableFrom = null,
            experiments = setOf("C01"),
            note = "11/5 추가(B 준영 요청, F40 확인용). 이름 있는 '닫기'가 없고 '오늘 하루 보지 않기'만 닫는 버튼이다. '자세히 보기'는 광고 페이지를 연다(앱이 누르면 실패). 문구는 P20 두 번째 광고와 같다.",
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

    /** C05·C09: 같은 공지와 일부만 바뀐 공지. N02만 N01의 기억을 재사용할 수 있다. */
    private fun noticeVariants(): List<PopupScenario> = listOf(
        notice("N01", "공지 원본", NOTICE_BASE, null, "10월 25일 02:00~04:00 주문 중단"),
        notice("N02", "공지 동일 반복", NOTICE_BASE, "N01", "10월 25일 02:00~04:00 주문 중단"),
        notice("N03", "공지 날짜만 변경",
            "서비스 점검 안내: 10월 26일 02:00~04:00 동안 주문 기능을 쓸 수 없습니다.", null,
            "날짜가 10월 26일로 바뀜"),
        notice("N04", "공지 시간만 변경",
            "서비스 점검 안내: 10월 25일 03:00~06:00 동안 주문 기능을 쓸 수 없습니다.", null,
            "시간이 03:00~06:00으로 바뀜"),
        notice("N05", "공지 금액 추가",
            "서비스 점검 안내: 10월 25일 02:00~04:00 동안 주문 기능을 쓸 수 없습니다. 보상 3,000포인트 지급.",
            null, "보상 금액 추가"),
        notice("N06", "공지 행동 요구 추가",
            "서비스 점검 안내: 10월 25일 02:00~04:00 동안 주문 기능을 쓸 수 없습니다. 점검 전 앱을 업데이트하세요.",
            null, "업데이트 요구 추가"),
        notice("N07", "공지 본문 완전 변경",
            "이용약관이 11월 1일부터 변경됩니다.", null, "다른 공지"),
        notice(BUTTON_MOVED_NOTICE_ID, "공지 닫기 버튼 위치만 변경", NOTICE_BASE, null,
            "글자는 원본과 같고 닫기 버튼만 위로 옮김"),
    )

    /** 정답지의 건너뛰기 버튼 ID. */
    const val SKIP_TARGET_ID = "btn_skip"

    /** #46: 자동 건너뛰기는 F9 지원·실측 전까지 꺼져 있다. 켜면 P16 목표가 바뀐다. */
    val autoSkipEnabled: Boolean = false

    /**
     * 프리셋별 목표(C `HandlingPreset`: ① 광고·쿠폰·공지 닫기, ② 광고·쿠폰 닫기, ③ 광고만 닫기).
     * 닫기가 목표여도 앱 허용 버튼이 없거나 자동 건너뛰기가 꺼진 건너뛰기뿐이면 묻는다(ASK).
     */
    fun presetGoal(scenario: PopupScenario, preset: TestPreset): PresetGoal {
        val preference = when (scenario.kind) {
            PopupKind.USER_DECISION -> return PresetGoal.ASK
            PopupKind.SUSPICIOUS -> return PresetGoal.ASK
            PopupKind.SENSITIVE -> return PresetGoal.BLOCK
            PopupKind.NOT_POPUP -> return PresetGoal.NONE
            PopupKind.AD -> PresetGoal.CLOSE
            PopupKind.COUPON -> if (preset == TestPreset.CLOSE_ADS_ONLY) PresetGoal.ANNOUNCE else PresetGoal.CLOSE
            PopupKind.NOTICE -> if (preset == TestPreset.CLOSE_ALL) PresetGoal.CLOSE else PresetGoal.ANNOUNCE
        }
        val target = scenario.appAllowedTargetId
        val closable = target != null && (target != SKIP_TARGET_ID || autoSkipEnabled)
        return if (preference == PresetGoal.CLOSE && !closable) PresetGoal.ASK else preference
    }

    /**
     * 첫 통합(클릭 없음, A 결정 요약 영역 제한·A·B 영역 계약 7번 연결 전) 예상. 앱 모듈
     * `AnswerKeyPipelineTest`가 지금 A·B 코드로 같은 값이 나오는지 CI에서 확인한다. 실기기 결과가 아니다.
     */
    val int1: Map<String, Int1Expectation> = mapOf(
        "P01" to Int1Expectation(Int1Kind.COUPON, Int1Path.TREE),
        "P02" to Int1Expectation(Int1Kind.COUPON, Int1Path.TREE),
        "P03" to Int1Expectation(Int1Kind.ADVERTISEMENT, Int1Path.TREE),
        "P04" to Int1Expectation(Int1Kind.ADVERTISEMENT, Int1Path.TREE),
        "P05" to Int1Expectation(Int1Kind.UNKNOWN, Int1Path.TREE, "글자 노드 없음(그림만)"),
        "P06" to Int1Expectation(Int1Kind.ADVERTISEMENT, Int1Path.TREE),
        "P07" to Int1Expectation(Int1Kind.UNKNOWN, Int1Path.TREE, "본문이 그림에만 있음. OCR 미구현"),
        "P08" to Int1Expectation(Int1Kind.USER_DECISION, Int1Path.A_SUMMARY),
        "P09" to Int1Expectation(Int1Kind.USER_DECISION, Int1Path.A_SUMMARY),
        "P10" to Int1Expectation(Int1Kind.USER_DECISION, Int1Path.A_SUMMARY),
        "P11" to Int1Expectation(Int1Kind.BLOCKED, Int1Path.A_BLOCK),
        "P12" to Int1Expectation(Int1Kind.COUPON, Int1Path.TREE),
        "P13" to Int1Expectation(Int1Kind.COUPON, Int1Path.TREE),
        "P14" to Int1Expectation(Int1Kind.NONE, Int1Path.NONE),
        "P15" to Int1Expectation(Int1Kind.NONE, Int1Path.NONE),
        "P16" to Int1Expectation(Int1Kind.ADVERTISEMENT, Int1Path.TREE),
        "P17" to Int1Expectation(Int1Kind.ADVERTISEMENT, Int1Path.TREE),
        "P18" to Int1Expectation(Int1Kind.USER_DECISION, Int1Path.A_SUMMARY,
            "'5초 안에 결제하면'의 '결제' 때문에 A가 결정 요약으로 바꿈(영역 계약 7번 연결 전)"),
        "P19" to Int1Expectation(Int1Kind.ADVERTISEMENT, Int1Path.TREE),
        "P20" to Int1Expectation(Int1Kind.ADVERTISEMENT, Int1Path.TREE),
        "P21" to Int1Expectation(Int1Kind.UNKNOWN, Int1Path.TREE, "B는 SUSPICIOUS로 보고 화면 분류는 UNKNOWN(경고)"),
        "P22" to Int1Expectation(Int1Kind.UNKNOWN, Int1Path.TREE, "B는 SUSPICIOUS로 보고 화면 분류는 UNKNOWN(경고)"),
        "P23" to Int1Expectation(Int1Kind.COUPON, Int1Path.TREE),
        "P24" to Int1Expectation(Int1Kind.USER_DECISION, Int1Path.A_SUMMARY,
            "뒤 페이지 '결제'·'동의' 때문에 A가 결정 요약으로 막음(의도한 안전 차단 회귀)"),
        "P33" to Int1Expectation(Int1Kind.COUPON, Int1Path.TREE),
        "P34" to Int1Expectation(Int1Kind.ADVERTISEMENT, Int1Path.TREE),
        "P35" to Int1Expectation(Int1Kind.ADVERTISEMENT, Int1Path.TREE),
        "P36" to Int1Expectation(Int1Kind.ADVERTISEMENT, Int1Path.TREE),
    ) + noticeVariants().associate { it.id to Int1Expectation(Int1Kind.NOTICE, Int1Path.TREE) }

    fun find(id: String): PopupScenario? = all.firstOrNull { it.id == id }
}
