# B — 분류·안전 판단

소유자: 준영/B, 브랜치: `feature/decision`.

`PopupClassifier`와 `ActionPolicy`를 구현하고 `ActionExecutor` 인터페이스로 A에게 실행을 요청합니다. 표시 분류와 실행 허가를 분리하고 금지 조건을 먼저 적용합니다. B가 접근성 노드를 소유하거나 직접 클릭·제스처·뒤로 가기·포커스 변경을 실행하지 않습니다.

TODO: F3 닫기 후보 근거, F5 영상 광고·스킵, F7 분류, F8 안전 판단, A와 F9 결과 검증 통합. F6 영상 내용 설명은 제외합니다. [연동 방법](../../../../../../../docs/TEAM_INTEGRATION.md)을 참고하세요.
