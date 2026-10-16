# A — Android 접근성 코어

소유자: 우진/A, 1차 `feature/core` 병합, 2차 브랜치: `feature/core-capture-ocr`.

서비스·실제 접근성 노드·캡처 버퍼·한국어 OCR·보호 검사·팝업 후보 감지·토큰 무효화와 미래 행동 게이트를 소유합니다. Android 노드와 이벤트는 동기 처리 안에서만 읽고 불변 `core.model` DTO로 복사합니다. Bitmap/HardwareBuffer는 `capture` 패키지 안에서 수명을 관리합니다. B·C·D에 이 객체를 전달하거나 일반 로그에 원문을 쓰지 않습니다.

현재 팝업 후보와 OCR은 광고 판정·클릭 허가가 아닙니다. 캡처·OCR은 별도 동의·앱별 허용·영역·F0-A/F0-B를 통과한 경로에 연결하며 실제 행동은 TODO입니다. [연동 방법](../../../../../../../docs/TEAM_INTEGRATION.md), [캡처/OCR](../../../../../../../docs/CAPTURE_OCR.md), [상태](../../../../../../../docs/DEVELOPMENT_STATUS.md)를 따릅니다.
