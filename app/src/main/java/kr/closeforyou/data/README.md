# D — 데이터·QA

소유자: 동준/D, 브랜치: `feature/database-qa`.

`PopupRuleRepository`, `PopupFingerprintProvider`, `SafeDiagnostics`를 구현합니다. 현재 저장소는 메모리 Mock이며 Room이 아닙니다. 필수 F12 사용자 선택 기억은 후속 구현해야 합니다.

시험용 팝업 재현 앱(`:popup-test-app`)과 웹 페이지, 정답지는 [팝업 재현 앱 문서](../../../../../../../docs/POPUP_TEST_APP.md)를 참고하세요. 합성 데이터만 사용하며 아직 실기기·TalkBack 미검증입니다.

TODO: 기기 내부 키 지문·정확한 본문/구조/대상/앱 버전 일치, 명시 허락·만료·철회·삭제·마이그레이션, 기기별 TalkBack QA. 원문·스크린샷·노드를 DB나 일반 로그에 저장하지 않습니다. [연동 방법](../../../../../../../docs/TEAM_INTEGRATION.md)을 참고하세요.
