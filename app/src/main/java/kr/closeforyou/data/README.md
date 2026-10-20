# D — 데이터·QA

소유자: 동준/D, 브랜치: `feature/database-qa`.

`PopupRuleRepository`, `PopupFingerprintProvider`, `SafeDiagnostics`를 구현합니다. 현재 저장소는 메모리 Mock이며 Room이 아닙니다. 필수 F12 사용자 선택 기억은 후속 구현해야 합니다.

TODO: 기기 내부 키 지문·정확한 본문/구조/대상/앱 버전 일치, 명시 허락·만료·철회·삭제·마이그레이션, 시험용 팝업 앱·기기별 TalkBack QA. 원문·스크린샷·노드를 DB나 일반 로그에 저장하지 않습니다. [연동 방법](../../../../../../../docs/TEAM_INTEGRATION.md)을 참고하세요.
