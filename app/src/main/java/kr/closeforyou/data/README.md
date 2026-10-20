# D — 데이터·QA

소유자: 동준/D, 브랜치: `feature/database-qa`.

F12 사용자 선택 기억(`PopupRuleRepository`·`PopupFingerprintProvider`)은 아직 메모리 Mock입니다. Room 구현은 별도 PR에서 리뷰합니다.

`SafeDiagnostics`: `CountingSafeDiagnostics`가 고정 코드·사유의 횟수만 메모리에 셉니다. 검증 지표 집계(`tools/qa_metrics.py`)와 버그·실험 이슈 양식은 [검증 지표 문서](../../../../../../../docs/QA_METRICS.md)를 참고하세요.

시험용 팝업 재현 앱(`:popup-test-app`)과 웹 페이지, 정답지는 [팝업 재현 앱 문서](../../../../../../../docs/POPUP_TEST_APP.md)를 참고하세요. 합성 데이터만 사용하며 아직 실기기·TalkBack 미검증입니다.

원문·스크린샷·노드를 DB나 일반 로그에 저장하지 않습니다. 규칙 일치는 실행 허가가 아닙니다. [연동 방법](../../../../../../../docs/TEAM_INTEGRATION.md)을 참고하세요.
