package kr.closeforyou.decision

import kr.closeforyou.core.model.TextSource

/**
 * Held-out set for the six-kind rubric of the auto-close design (AUTO_CLOSE.md 3절), written on
 * 2026-10-23 BEFORE the kind rules were coded, with no real app data or brands. Each sample is
 * one popup: every tree text A would collect (title, body, button labels), in screen order.
 * Truth follows the human rubric, not the keyword lists; do not tune keywords to this set.
 */
internal enum class Kind6 { DECISION, SUSPICIOUS, UNKNOWN, COUPON, ADVERTISEMENT, NOTICE }

internal data class KindSample(val id: String, val truth: Kind6, val texts: List<Pair<String, TextSource>>)

internal object DecisionKindEvaluationSet {
    private fun s(id: String, truth: Kind6, vararg texts: String) =
        KindSample(id, truth, texts.map { it to TextSource.ACCESSIBILITY_TREE })
    private fun ocr(id: String, truth: Kind6, vararg texts: String) =
        KindSample(id, truth, texts.map { it to TextSource.OCR })

    val samples: List<KindSample> = listOf(
        // COUPON: a concrete benefit the person can claim or already holds (쿠폰·적립금·포인트 지급·상품권·이용권·캐시백·출석 보상).
        s("cp-01", Kind6.COUPON, "쿠폰이 도착했어요", "5천원 할인 쿠폰을 받아 가세요", "쿠폰 받기", "닫기"),
        s("cp-02", Kind6.COUPON, "첫 구매 고객 전용 3천원 쿠폰", "닫기"),
        s("cp-03", Kind6.COUPON, "적립금 2,000원이 지급되었습니다", "닫기"),
        s("cp-04", Kind6.COUPON, "출석 체크하고 포인트 받기", "오늘도 출석하면 100포인트 적립", "닫기"),
        s("cp-05", Kind6.COUPON, "룰렛을 돌려 경품 쿠폰을 받으세요", "닫기"),
        s("cp-06", Kind6.COUPON, "배달비 무료 이용권 1장 증정", "닫기"),
        s("cp-07", Kind6.COUPON, "보유하신 쿠폰이 내일 만료됩니다", "쿠폰함 보기", "닫기"),
        s("cp-08", Kind6.COUPON, "친구 초대하면 둘 다 5천 포인트", "닫기"),
        s("cp-09", Kind6.COUPON, "가을 할인 쿠폰", "지금 받으면 30퍼센트 할인. 시험용 광고입니다.", "자세히 보기", "닫기"),
        s("cp-10", Kind6.COUPON, "영화 관람권 증정 이벤트", "닫기"),
        s("cp-11", Kind6.COUPON, "스탬프 3개를 모으면 음료 교환권", "닫기"),
        s("cp-12", Kind6.COUPON, "You got a coupon!", "Close"),
        s("cp-13", Kind6.COUPON, "Earn 500 bonus points today", "Close"),
        s("cp-14", Kind6.COUPON, "10% cashback on your next order", "Close"),
        s("cp-15", Kind6.COUPON, "광고 · 신규 회원 1만원 쿠폰팩", "닫기"),
        s("cp-16", Kind6.COUPON, "마일리지 적립 2배 행사", "닫기"),
        s("cp-17", Kind6.COUPON, "상품권 5천원 드려요", "닫기"),

        // ADVERTISEMENT: promotes a product, service, app, content or sale; no claimable benefit object named.
        s("ad-01", Kind6.ADVERTISEMENT, "광고", "신상 운동화 출시", "자세히 보기", "닫기"),
        s("ad-02", Kind6.ADVERTISEMENT, "게임 아이템 특가", "자세히 보기"),
        s("ad-03", Kind6.ADVERTISEMENT, "한정 특가 광고", "닫기"),
        s("ad-04", Kind6.ADVERTISEMENT, "타임세일", "세일 종료까지 04:57", "자세히 보기", "닫기"),
        s("ad-05", Kind6.ADVERTISEMENT, "여름 대세일 최대 70%", "닫기"),
        s("ad-06", Kind6.ADVERTISEMENT, "첫 주문 3천원 할인 이벤트", "닫기"),
        s("ad-07", Kind6.ADVERTISEMENT, "스폰서 · 근처 인기 맛집", "닫기"),
        s("ad-08", Kind6.ADVERTISEMENT, "Sponsored", "New game launching today", "Close"),
        s("ad-09", Kind6.ADVERTISEMENT, "광고 1/2", "건너뛰기를 누르면 다음 광고가 나오고, 닫기를 누르면 광고가 끝납니다(시험용).", "건너뛰기", "닫기"),
        s("ad-10", Kind6.ADVERTISEMENT, "영상 광고(시험용)", "3초 후 건너뛸 수 있어요"),
        s("ad-11", Kind6.ADVERTISEMENT, "신작 게임 사전예약 오픈", "닫기"),
        s("ad-12", Kind6.ADVERTISEMENT, "프로모션 · 신규 브랜드 입점", "닫기"),
        s("ad-13", Kind6.ADVERTISEMENT, "AD", "지금 다운로드하고 보상 받기", "닫기"),
        s("ad-14", Kind6.ADVERTISEMENT, "광고를 보고 보상을 받으세요", "닫기"),
        s("ad-15", Kind6.ADVERTISEMENT, "SALE 50% OFF", "Close"),
        s("ad-16", Kind6.ADVERTISEMENT, "Limited time offer", "Ads", "Close"),
        s("ad-17", Kind6.ADVERTISEMENT, "오픈 기념 전 메뉴 할인", "닫기"),
        s("ad-18", Kind6.ADVERTISEMENT, "협찬 · 온라인 강의 할인 안내", "닫기"),
        s("ad-19", Kind6.ADVERTISEMENT, "앱 설치하고 혜택 받기(시험용)", "설치", "닫기"),

        // NOTICE: tells the person about service, schedule, policy, order or account state.
        s("no-01", Kind6.NOTICE, "공지", "서비스 점검 안내: 10월 25일 02:00~04:00", "닫기"),
        s("no-02", Kind6.NOTICE, "배송 지연 안내", "주문하신 상품이 하루 늦게 도착합니다", "닫기"),
        s("no-03", Kind6.NOTICE, "새 버전 업데이트 안내", "닫기"),
        s("no-04", Kind6.NOTICE, "이용약관이 변경되었습니다", "닫기"),
        s("no-05", Kind6.NOTICE, "과제 제출 마감이 내일입니다", "확인"),
        s("no-06", Kind6.NOTICE, "운영 시간 변경 공지", "닫기"),
        s("no-07", Kind6.NOTICE, "네트워크 연결이 불안정합니다", "닫기"),
        s("no-08", Kind6.NOTICE, "이번 업데이트의 핵심 포인트", "글자 크기 설정이 추가되었습니다", "닫기"),
        s("no-09", Kind6.NOTICE, "주문하신 음식이 출발했습니다", "닫기"),
        s("no-10", Kind6.NOTICE, "Scheduled maintenance tonight", "Close"),
        s("no-11", Kind6.NOTICE, "휴관일 안내", "매주 월요일은 쉽니다", "닫기"),
        s("no-12", Kind6.NOTICE, "할인 적용 오류 안내", "일부 상품의 가격 표시가 잘못되어 수정했습니다", "닫기"),
        s("no-13", Kind6.NOTICE, "이벤트 종료 안내", "가을 이벤트가 종료되었습니다", "닫기"),

        // DECISION: asks for an important choice, possibly next to a benefit.
        s("de-01", Kind6.DECISION, "포인트로 결제하시겠습니까?", "결제하기", "닫기"),
        s("de-02", Kind6.DECISION, "쿠폰을 받으려면 마케팅 수신 동의가 필요합니다", "동의하고 받기", "닫기"),
        s("de-03", Kind6.DECISION, "쿠폰 적용하고 바로 구매하기", "구매하기", "닫기"),
        s("de-04", Kind6.DECISION, "프리미엄 구독 첫 달 무료", "구독 시작", "닫기"),
        s("de-05", Kind6.DECISION, "알림을 허용하면 쿠폰을 드려요", "허용", "나중에"),
        s("de-06", Kind6.DECISION, "앱을 설치하시겠습니까?", "설치", "취소"),
        s("de-07", Kind6.DECISION, "쿠폰을 삭제할까요?", "삭제", "취소"),
        s("de-08", Kind6.DECISION, "무료 체험 후 월 9,900원이 결제됩니다", "체험 시작", "닫기"),
        s("de-09", Kind6.DECISION, "결제 확인", "시험용 상품 12,900원을 결제할까요? 실제 결제는 일어나지 않습니다.", "취소", "결제하기"),
        s("de-10", Kind6.DECISION, "마케팅 정보 수신 동의", "이벤트 소식을 받으시겠어요? 시험용 동의창입니다.", "거부", "동의"),
        s("de-11", Kind6.DECISION, "Subscribe to get 20% off", "Subscribe", "Close"),
        s("de-12", Kind6.DECISION, "신규 가입하고 쿠폰팩 받기", "가입하기", "닫기"),
        s("de-13", Kind6.DECISION, "위치 권한을 허용하면 근처 쿠폰을 보여 드려요", "허용", "닫기"),
        s("de-14", Kind6.DECISION, "프리미엄 구독", "구독을 해지하려면 취소를 누르세요.", "나중에", "취소"),

        // SUSPICIOUS: prize-win, infection, hacking or urgent-install claims (a scam pattern).
        s("su-01", Kind6.SUSPICIOUS, "축하합니다! 경품에 당첨되었습니다", "닫기"),
        s("su-02", Kind6.SUSPICIOUS, "휴대폰이 바이러스에 감염되었습니다", "지금 치료", "닫기"),
        s("su-03", Kind6.SUSPICIOUS, "배터리가 손상되었습니다. 즉시 정리하세요", "정리하기", "닫기"),
        s("su-04", Kind6.SUSPICIOUS, "당첨 쿠폰 수령하기", "닫기"),
        s("su-05", Kind6.SUSPICIOUS, "Congratulations! You won a free phone", "Close"),
        s("su-06", Kind6.SUSPICIOUS, "Your phone is infected with 3 viruses", "Close"),
        s("su-07", Kind6.SUSPICIOUS, "긴급 업데이트를 즉시 설치하세요", "닫기"),
        s("su-08", Kind6.SUSPICIOUS, "상금 100만원 지급 대상입니다", "닫기"),

        // UNKNOWN: the text gives no basis, or the only promotional evidence is OCR.
        s("un-01", Kind6.UNKNOWN, "닫기"),
        s("un-02", Kind6.UNKNOWN, "탭하여 계속"),
        s("un-03", Kind6.UNKNOWN, "12:30", "닫기"),
        s("un-04", Kind6.UNKNOWN, "가상 이미지 팝업: 버튼의 의미는 확인되지 않았습니다.", "닫기"),
        ocr("un-05", Kind6.UNKNOWN, "여름 대세일", "시험용 이미지 광고"),
        ocr("un-06", Kind6.UNKNOWN, "쿠폰 도착"),
        s("un-07", Kind6.UNKNOWN, "Hello", "Close"),
    )
}
