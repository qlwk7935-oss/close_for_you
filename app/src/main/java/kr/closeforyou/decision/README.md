# B — 분류·안전 판단

소유자: 준영/B, 브랜치: `feature/decision-*` (기능별 브랜치 → main PR).

`PopupClassifier`와 `ActionPolicy`를 구현하고 `ActionExecutor` 인터페이스로 A에게 실행을 요청합니다. 표시 분류와 실행 허가를 분리하고 금지 조건을 먼저 적용합니다. B가 접근성 노드를 소유하거나 직접 클릭·제스처·뒤로 가기·포커스 변경을 실행하지 않습니다.

> **상태 (2026-10-25, main `4241a94`):** 아래 부품은 모두 main에 있고 JVM 단위 시험, API 37 에뮬레이터 덤프·계측 시험을 통과했습니다. 하지만 **main의 실제 서비스는 아직 B를 부르지 않습니다.** A 서비스 이벤트를 B·C로 잇는 조립 코드는 A 브랜치(`transfer/…`, #29)에만 있고, 클릭 실행(F9 #42)도 병합 전입니다. 실기기 검증은 0건입니다.

## 구현

- **`DecisionKinds` (여섯 종류, 계약 v2):** SUSPICIOUS(사기 의심) > USER_DECISION > UNKNOWN > 공지 가드 > COUPON > ADVERTISEMENT > NOTICE 순으로 먼저 맞는 것이 이깁니다. 위험 신호(결정·사기·불확실)는 모든 글자에서 넓게(띄어쓰기 없앤 형태, 전각, 보이지 않는 문자 포함), 닫을 이유(쿠폰·광고·공지)는 팝업 안 접근성 트리 글자에서만 찾습니다. 영어 단어 경계는 ASCII 기준(`[a-z0-9_]`)이라 Android에서도 "sign up하기"를 잡습니다(#37). 자세한 기준은 [자동 닫기 문서](AUTO_CLOSE.md) 3절.
- **`DecisionPopupClassifier` (F7):** 위 여섯 종류를 표시 분류로 바꿉니다(SUSPICIOUS → UNKNOWN, COUPON → COUPON). 표시 분류는 실행 허가가 아닙니다.
- **`DecisionActionPolicy` (F8):** 금지 사유가 확인 사유보다 우선합니다. 계약 v2의 `source`(USER_CHOICE·REMEMBERED_RULE·PRESET)와 `next`(EXECUTE·WAIT·ANNOUNCE·ASK·DENY)를 채웁니다.
  - **결정·사기 의심:** 누가 요청했든 DENY입니다(`IMPORTANT_DECISION`, `SUSPICIOUS_CONTENT`).
  - **사용자 선택:** 이 화면의 검증된 E1/E2 닫기/건너뛰기 버튼을 `CLOSE_ONCE`/`SKIP_ONCE`로 고르고 버튼 의미를 확인했을 때만 1회 요청합니다. 의미 확인이 없으면 같은 버튼으로 다시 묻습니다.
  - **프리셋 자동 닫기 (처음 본 팝업):** C 설정의 `autoCloseKinds`에 든 종류이고, A의 적격 근거(`AutoEligibility`, 5개 검사, 수집 후 5초)가 있고, 검증된 CLOSE 버튼이 정확히 하나일 때만 ALLOW_ONCE + EXECUTE입니다. 같은 팝업은 세션에 한 번, 실패·결과 불명·앱 이탈 뒤에는 다시 누르지 않고, 앱마다 1분에 3회까지입니다. 자동 SKIP은 기본 꺼짐입니다(#46). 18개 조건 전체는 [자동 닫기 문서](AUTO_CLOSE.md) 4절.
  - **기억 규칙(F12) 자동 반복:** 아직 꺼져 있어 항상 사용자 확인으로 갑니다. A가 모든 분석에 `INCOMPLETE_EVIDENCE`를 붙이고, 반복 허락 계약이 아직 없기 때문입니다.
  - **결과 받기:** `onActionResult`로 A의 모든 결과(누르지 않은 결과 포함)를 받아 세션 기록을 갱신합니다. 성공은 `attempted` + `MECHANICAL_SUCCESS` + `CLOSED_CONFIRMED`일 때만입니다(팀 기준 10/25).
- **`DecisionCloseCandidateFinder` (F3, E1/E2만):** 라벨 전체가 공용 계약 `ButtonLabelRules`상 닫기/건너뛰기인 `DISMISS`·클릭 가능 노드만 후보로 냅니다. 새 창은 활성 창 전체, 같은 창 덮개는 Dialog 노드가 하나일 때 그 하위 노드만 봅니다. 전체 화면 전환과 `INSUFFICIENT_EVIDENCE` 감지는 후보를 만들지 않습니다. 단독 X/×, 라벨 없는 `DISMISS`, 아이콘(E3), 픽셀(E4)은 후보가 아닙니다.
- **`DecisionPopupScope`:** 같은 창 덮개에서 팝업 밖 글자를 빼되 경고 신호는 절대 빼지 않습니다. 팝업 제목(`PopupAnalysis.title`)도 고르며, 이름+님·7자리 이상 숫자·이메일·웹 주소가 든 줄은 제목으로 쓰지 않습니다(#50). 첫 통합에서는 A가 제목을 넘기지 않습니다(팀 결정).
- **`DecisionCandidateWiring` / `DecisionChoiceHandler`:** A의 분석에 같은 화면의 후보·제목을 붙이고, 사람의 선택을 B 정책 → A 게이트 1회로 잇습니다. 조립 방법은 [연결 안내](INTEGRATION.md).
- **`DecisionVideoAd` (F5):** 영상 표면과 광고 근거를 분리합니다. A가 `videoSurfaceObserved`를 아직 채우지 않아 실제 입력은 없습니다.
- **`DecisionRulePressOutcome` (F12):** 기억 규칙으로 누른 결과가 실패이면 D의 `recordFailure`로 규칙을 끕니다(#48).
- **`DecisionKeywords`:** 버튼 등급·분류 키워드 사전. 키워드 일치는 허가가 아닙니다.

## 남은 일

- **조립:** A 서비스 → B 배선 → C 흐름 연결이 main에 들어와야 B가 실제로 쓰입니다(A 주도).
- **F9 연결:** A #42의 `F9PolicyBridge`를 B 정책으로 구현하는 어댑터가 아직 없습니다. 정책은 `DecisionActionPolicy { SystemClock.uptimeMillis() }`로 만들어야 합니다(기본 생성자는 시험용 시계). 방법은 [자동 닫기 문서](AUTO_CLOSE.md) 9절.
- **A 입력:** `AutoEligibility`, `videoSurfaceObserved`를 A가 채우기 전까지 자동 닫기와 F5는 열리지 않습니다.
- **분류 근거:** 정확도는 합성 문구 기준입니다. 실제 앱 문구 수집 뒤 다시 측정해야 합니다.
- **미구현 (팀 결정):** 라벨 없는 X(E3) 자동, F4 이미지 분석, 클릭 가능한 부모 + 라벨 자식 패턴, Dialog 노드가 없는 같은 창 덮개.
- **D 정답지:** COUPON 종류와 프리셋별 기대값이 없습니다(D와 협의).

## 팀 합의

- 사용자의 명시 선택은 `INCOMPLETE_EVIDENCE`가 있어도 검증된 E1/E2 버튼에 1회 요청할 수 있습니다(PR #8). 토큰·버튼 의미 일치, 결정·보호 차단 아님, A 게이트의 실제 화면 재검증, 최대 1회·재시도 없음이 조건입니다.
- 처음 본 팝업 자동 닫기는 사용자가 고른 프리셋 + A 적격 근거 + B 조건을 모두 만족할 때만입니다(계약 v2 #38, 재호 AGENTS 6장 개정 초안). 결정·사기 의심은 어떤 프리셋도 닫지 않습니다. **AGENTS.md 6장 2번은 아직 옛 문구라 팀 개정이 필요합니다.**
- 반복 닫기는 세션 1회가 기본이고, 반복은 F12에서 사용자가 허락했을 때만입니다(우진). `STOP_ASKING`은 닫지도 다시 묻지도 않습니다.
- O 등급 `확인`/`OK` 버튼은 누르지 않습니다. 공지 "확인"은 별도 계약으로 다룹니다.
- 닫기 라벨 기준은 A·B·C 공용 `ButtonLabelRules` v1 하나입니다(#14). 라벨 허용은 실행 허가가 아닙니다.
- 운영 자동 닫기(`PresetAutoCloseFeature.live`), 자동 SKIP, F12 자동 반복은 모두 꺼진 채로 둡니다.

B가 막는 위협과 대응 시험은 [위협 모델](THREAT_MODEL.md)에 있습니다. 분류 정확도는 [평가 보고서](CLASSIFIER_EVAL.md)(합성 82개 73개 정답)와 [자동 닫기 문서](AUTO_CLOSE.md) 6절(held-out 80개 73개 정답, 위험 0)에 있으며, 둘 다 B가 정답을 붙인 합성 문구의 JVM 평가라 실제 앱 정확도가 아닙니다. 실기기에서 확인할 전제와 시나리오는 [실기기 체크리스트](REAL_DEVICE_CHECKLIST.md)에 있습니다(실기기 검증 0건). [연동 방법](../../../../../../../docs/TEAM_INTEGRATION.md)도 참고하세요.
