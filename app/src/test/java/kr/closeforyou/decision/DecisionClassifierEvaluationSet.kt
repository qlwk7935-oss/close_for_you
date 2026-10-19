package kr.closeforyou.decision

import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.PopupClassification.ADVERTISEMENT
import kr.closeforyou.core.model.PopupClassification.NOTICE
import kr.closeforyou.core.model.PopupClassification.UNKNOWN
import kr.closeforyou.core.model.PopupClassification.USER_DECISION
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.core.model.TextSource.ACCESSIBILITY_TREE
import kr.closeforyou.core.model.TextSource.OCR

/**
 * Held-out evaluation set for the B classifier (verification plan C04). Synthetic popup texts,
 * no real app data or brands. Labels follow the human rubric in CLASSIFIER_EVAL.md and were
 * written before running the classifier; keywords must not be tuned to this set.
 */
internal data class EvaluationSample(
    val id: String,
    val app: String,
    val text: String,
    val source: TextSource,
    val truth: PopupClassification,
)

internal object DecisionClassifierEvaluationSet {
    private fun s(id: String, app: String, truth: PopupClassification, text: String, source: TextSource = ACCESSIBILITY_TREE) =
        EvaluationSample(id, app, text, source, truth)

    val samples: List<EvaluationSample> = listOf(
        // Advertisements: promote a product, service, app or event without asking for a decision.
        s("ad-01", "쇼핑", ADVERTISEMENT, "광고 · 오늘만 특가! 인기 상품 최대 70% 할인"),
        s("ad-02", "쇼핑", ADVERTISEMENT, "AD 새로 나온 운동화를 만나보세요"),
        s("ad-03", "쇼핑", ADVERTISEMENT, "지금 구매하면 무료 배송 혜택"),
        s("ad-04", "쇼핑", ADVERTISEMENT, "쿠폰 도착! 앱 전용 5천원 할인 쿠폰"),
        s("ad-05", "배달", ADVERTISEMENT, "첫 주문 3천원 할인 이벤트"),
        s("ad-06", "배달", ADVERTISEMENT, "스폰서 · 근처 인기 맛집 추천"),
        s("ad-07", "영상", ADVERTISEMENT, "광고 · 5초 후 건너뛸 수 있습니다"),
        s("ad-08", "영상", ADVERTISEMENT, "Sponsored · New game launching today"),
        s("ad-09", "영상", ADVERTISEMENT, "광고 · 신작 게임 사전예약", OCR),
        s("ad-10", "게임", ADVERTISEMENT, "AD 지금 다운로드하고 보상 받기"),
        s("ad-11", "게임", ADVERTISEMENT, "광고를 보고 보상을 받으세요"),
        s("ad-12", "학교", ADVERTISEMENT, "광고 · 방학 특강 수강생 모집"),
        s("ad-13", "LMS", ADVERTISEMENT, "협찬 · 온라인 강의 할인 안내"),
        s("ad-14", "쇼핑", ADVERTISEMENT, "SALE 50% OFF", OCR),
        s("ad-15", "쇼핑", ADVERTISEMENT, "Ads · Limited time offer"),
        s("ad-16", "쇼핑", ADVERTISEMENT, "회원가입하면 1만원 쿠폰 증정"),
        s("ad-17", "앱", ADVERTISEMENT, "광고 · 새 버전 앱을 설치하고 혜택을 받으세요"),
        s("ad-18", "배달", ADVERTISEMENT, "오늘의 특가 메뉴"),
        s("ad-19", "영상", ADVERTISEMENT, "광고 이후 영상이 재생됩니다"),
        s("ad-20", "뉴스", ADVERTISEMENT, "AD 이 콘텐츠는 광고주의 지원을 받았습니다"),
        s("ad-21", "쇼핑", ADVERTISEMENT, "Buy one get one free"),
        s("ad-22", "게임", ADVERTISEMENT, "", OCR),
        s("ad-23", "쇼핑", ADVERTISEMENT, "프로모션 · 신규 브랜드 입점 기념"),
        s("ad-24", "뉴스", ADVERTISEMENT, "광고 · 프리미엄 멤버십 혜택 알아보기"),

        // Notices: inform about service state, schedules, policy or updates.
        s("no-01", "LMS", NOTICE, "시스템 점검 안내: 10월 20일 02:00~04:00"),
        s("no-02", "LMS", NOTICE, "과제 제출 마감이 내일입니다"),
        s("no-03", "학교", NOTICE, "학사 공지: 중간고사 일정이 변경되었습니다"),
        s("no-04", "학교", NOTICE, "이번 주 급식 메뉴 안내"),
        s("no-05", "쇼핑", NOTICE, "배송 지연 안내"),
        s("no-06", "앱", NOTICE, "새로운 버전이 출시되었습니다. 업데이트 안내"),
        s("no-07", "영상", NOTICE, "서비스 점검으로 일부 기능이 제한됩니다"),
        s("no-08", "뉴스", NOTICE, "속보 알림"),
        s("no-09", "LMS", NOTICE, "개인정보 처리방침 변경 안내"),
        s("no-10", "쇼핑", NOTICE, "이용약관이 변경되었습니다"),
        s("no-11", "학교", NOTICE, "내일 휴교 안내", OCR),
        s("no-12", "LMS", NOTICE, "현재 점검 중입니다", OCR),
        s("no-13", "배달", NOTICE, "주문하신 음식이 출발했습니다"),
        s("no-14", "앱", NOTICE, "네트워크 연결이 불안정합니다"),
        s("no-15", "학교", NOTICE, "새 공지사항이 등록되었습니다"),
        s("no-16", "LMS", NOTICE, "수강 신청 기간 안내"),
        s("no-17", "앱", NOTICE, "알림 설정이 변경되었습니다"),
        s("no-18", "영상", NOTICE, "방송이 종료되었습니다"),
        s("no-19", "앱", NOTICE, "임시 점검 완료 안내"),
        s("no-20", "학교", NOTICE, "학생증 발급 안내"),
        s("no-21", "쇼핑", NOTICE, "고객센터 운영 시간 변경 안내"),
        s("no-22", "LMS", NOTICE, "출석 인정 기준 변경 공지"),
        s("no-23", "앱", NOTICE, "Scheduled maintenance tonight"),
        s("no-24", "뉴스", NOTICE, "오늘의 주요 뉴스 알림", OCR),

        // User decisions: ask the person to pay, consent, grant, log in, subscribe, delete or install.
        s("de-01", "쇼핑", USER_DECISION, "결제 선택 필요"),
        s("de-02", "쇼핑", USER_DECISION, "구매 선택 필요", OCR),
        s("de-03", "영상", USER_DECISION, "구독 선택 필요"),
        s("de-04", "LMS", USER_DECISION, "동의 선택 필요"),
        s("de-05", "앱", USER_DECISION, "권한 허용 선택 필요"),
        s("de-06", "앱", USER_DECISION, "삭제 선택 필요", OCR),
        s("de-07", "배달", USER_DECISION, "위치 권한을 허용하시겠습니까?"),
        s("de-08", "앱", USER_DECISION, "알림을 허용하시겠습니까?"),
        s("de-09", "LMS", USER_DECISION, "개인정보 수집 및 이용에 동의합니다"),
        s("de-10", "영상", USER_DECISION, "구독을 해지하시겠습니까?"),
        s("de-11", "앱", USER_DECISION, "계정을 삭제하면 복구할 수 없습니다"),
        s("de-12", "쇼핑", USER_DECISION, "회원 탈퇴하시겠습니까?"),
        s("de-13", "학교", USER_DECISION, "로그인이 필요합니다"),
        s("de-14", "영상", USER_DECISION, "무료 체험 후 월 9,900원이 자동 결제됩니다"),
        s("de-15", "쇼핑", USER_DECISION, "광고 · 구독 결제를 진행하시겠습니까?"),
        s("de-16", "앱", USER_DECISION, "Allow notifications?"),
        s("de-17", "LMS", USER_DECISION, "Sign in to continue"),
        s("de-18", "뉴스", USER_DECISION, "Accept cookies to keep reading"),
        s("de-19", "쇼핑", USER_DECISION, "송금하시겠습니까?"),
        s("de-20", "쇼핑", USER_DECISION, "자동이체를 신청하시겠습니까?"),
        s("de-21", "앱", USER_DECISION, "카메라 접근을 승인하시겠습니까?"),
        s("de-22", "게임", USER_DECISION, "앱을 설치하시겠습니까?"),
        s("de-23", "쇼핑", USER_DECISION, "결제 수단을 등록하시겠습니까?"),
        s("de-24", "앱", USER_DECISION, "Delete this file?"),

        // Unknown: the text itself gives no basis for any class.
        s("un-01", "앱", UNKNOWN, ""),
        s("un-02", "앱", UNKNOWN, "   "),
        s("un-03", "게임", UNKNOWN, "!!!"),
        s("un-04", "앱", UNKNOWN, "..."),
        s("un-05", "영상", UNKNOWN, "12:30"),
        s("un-06", "앱", UNKNOWN, "가상 이미지 팝업: 버튼의 의미는 확인되지 않았습니다."),
        s("un-07", "게임", UNKNOWN, "탭하여 계속"),
        s("un-08", "앱", UNKNOWN, "😀"),
        s("un-09", "쇼핑", UNKNOWN, "내용을 알 수 없습니다", OCR),
        s("un-10", "앱", UNKNOWN, "Hello"),
    )
}
