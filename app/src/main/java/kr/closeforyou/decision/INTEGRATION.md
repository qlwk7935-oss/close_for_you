# B 연결 안내 (A 조립용)

작성: B 준영 · 2026-10-21 (자동 닫기·F9 연결 반영 2026-10-25) · 대상: 첫 통합 "실제 Dialog → A 분석 → B 분류·안전 판단 → C 안내", 그다음 F9 프리셋 자동 닫기

> JVM 단위 시험과 API 37 에뮬레이터 덤프까지만 통과했습니다. **main의 실제 서비스는 아직 이 클래스들을 만들지 않습니다**(2026-10-25 기준, A 조립 코드는 `transfer/…` 브랜치에만 있음). 실기기에서는 연결해 보지 않았습니다.

## 1. 쓸 클래스 (전부 `kr.closeforyou.decision`, Android 의존 없음)

| 클래스 | 계약 | 역할 |
|---|---|---|
| `DecisionPopupClassifier` | `PopupClassifier` | F7 표시 분류 (광고/쿠폰/공지/결정/모름, 사기 의심은 모름). 실행 허가가 아닙니다 |
| `DecisionActionPolicy` | `ActionPolicy` | F8 안전 판단. 금지 우선. 처음 본 팝업은 묻기, **단 사용자가 고른 프리셋(`autoCloseKinds`) + A 적격 근거 + B 조건을 모두 만족하면 1회 자동 닫기**(EXECUTE·PRESET). 기억 규칙 자동 반복은 꺼짐. `onActionResult`로 결과를 받음 |
| `DecisionCandidateWiring` | `SnapshotSource` + `EvidenceExtractor`를 감쌈 | A의 분석에 같은 화면(토큰)의 B 닫기 후보(E1/E2)와 팝업 제목을 붙이고, 같은 창 팝업의 글자를 팝업 범위로 좁힙니다. A 적격 근거는 토큰·수집 시각이 그 화면과 같을 때만 남깁니다 |
| `DecisionChoiceHandler` | C의 `ChoiceHandler` 모양 | 사람의 선택 → B 정책 → A 게이트(`ActionExecutor`) 1회 |

`DecisionCloseCandidateFinder`, `DecisionCandidateAttacher`는 위 클래스가 안에서 씁니다. 직접 부를 필요는 없습니다.

## 2. 조립 예시 (A 런타임)

```kotlin
// A: 실제 구현
val aSource: SnapshotSource = /* AccessibilityRuntime */
val aExtractor: EvidenceExtractor = /* CaptureOcrPipeline 등 */
val gate: ActionExecutor = /* 첫 통합: 실행하지 않는 게이트 (NOT_EXECUTED) */

// B: A의 source·extractor를 감싸서, 이후에는 감싼 것만 씁니다
val wiring = DecisionCandidateWiring(aSource, aExtractor)
val classifier = DecisionPopupClassifier()
val handler = DecisionChoiceHandler(
    source = wiring.source,
    executor = gate,
    userInteracting = { /* A: 사람이 지금 화면을 만지거나 탐색 중인지 */ },
    announcementAvailable = { tts.announcementAvailable /* C: 우리 TTS 준비됨 */ },
)

// C: PopupChoiceFlow에 감싼 source·extractor를 넘깁니다 (C 코드 변경 없음)
PopupChoiceFlow(
    source = wiring.source,
    extractor = wiring.extractor,
    classifier = classifier,
    handler = ChoiceHandler(handler::handle),
    /* presenter, announcer 등은 C 문서(docs/c-notes/presenter-usage-for-a.md) */
)
```

- **정책은 서비스 세션마다 하나만 만들고, 시계는 `SystemClock.uptimeMillis`를 넘겨 주세요:** `DecisionActionPolicy { SystemClock.uptimeMillis() }`. A의 `AutoEligibility.capturedAtUptimeMillis`와 같은 시계여야 5초 기한이 맞습니다. 인자 없는 `DecisionActionPolicy()`와 `DecisionChoiceHandler`의 기본값은 시험용 JVM 시계입니다. 위 예시에서도 `policy = 그 정책`을 `DecisionChoiceHandler`에 넘기면 사용자 선택과 프리셋이 같은 세션 기록을 씁니다(앱 이탈 기록 공유).
- **같은 `wiring` 하나를 C 흐름과 D 지문 생성기(F12 연결 때)에 함께 넘겨 주세요.** D의 `DeviceKeyedFingerprintProvider(evidenceExtractor = wiring.extractor, …)`가 저장 직전에 다시 `extract`해도 같은 화면이면 같은 후보가 붙습니다.
- `wiring.source.events`를 C가 구독할 때 B가 최신 `Candidate`의 감지 신호만 기억합니다. 스냅샷은 보관하지 않고, `Blocked`·`Invalidated`가 오면 잊습니다.
- A의 이벤트는 여러 곳에서 구독할 수 있어서, 늦게 처리된 옛 이벤트가 최신 기억을 덮어쓰지 않도록 화면 버전으로 거릅니다. `Invalidated(v)` 이후에는 v보다 오래된 화면이 돌아오지 않습니다. `Blocked(v)`는 버전 v 화면까지 막습니다.

## 3. 동작 규칙

- **후보:** `NEW_WINDOW`는 활성 창 전체, `SAME_WINDOW_OVERLAY`는 Dialog 노드가 하나일 때 그 하위 노드만 봅니다. `FULL_SCREEN_TRANSITION`·`INSUFFICIENT_EVIDENCE`에서는 후보를 만들지 않습니다. 라벨 없는 X·아이콘(E3)·그림(E4)은 후보가 아닙니다.
- **다른 화면:** 스냅샷·분석·감지의 토큰이 하나라도 다르거나, 그 스냅샷이 A의 `currentToken()`(TTL·차단·화면 변경 반영)과 다르면 후보 0개입니다. 이전 화면의 버튼을 새 화면에 내지 않습니다.
- **선택 처리:** 누르기 선택(`CLOSE_ONCE`/`SKIP_ONCE`)이 아니면 `null`을 반환하고 게이트를 부르지 않습니다. 정책이 `ALLOW_ONCE`가 아니면 게이트 없이 `BLOCKED`/`STALE`/`NOT_EXECUTED`를 반환합니다. `ALLOW_ONCE`면 토큰을 한 번 더 확인하고 게이트를 정확히 1번 부릅니다. 게이트 예외는 재시도 없이 `OUTCOME_UNKNOWN`(attempted=true)입니다.
- **`ALLOW_ONCE`는 요청일 뿐입니다.** 실제 화면 재검증과 실행 결정은 A 게이트가 합니다.
- **기억 규칙:** 첫 통합에서는 쓰지 않습니다(`matchingRule = null`). F12 연결 때 B가 정책 전에 `findExact` 결과를 받고, 기억으로 실행했는데 실패하면 D의 `recordFailure`를 부르는 부분을 추가합니다.
- **기억 실행 결과:** `DecisionRulePressOutcome.afterRulePress(rule, result)`가 실패를 판단합니다.
  - 성공은 팀 기준(10/25)인 `attempted` + `MECHANICAL_SUCCESS` + `CLOSED_CONFIRMED`일 때만입니다.
  - 눌렀는데(attempted=true 또는 누른 상태 값) 닫힘이 확인되지 않으면 실패이고, D의 `recordFailure`로 규칙을 끕니다. 팝업이 그대로 남은 경우, 결과 모름, 앱 이탈, 실패했는데 팝업이 사라진 경우가 모두 여기에 해당합니다.
  - 누르지 않은 결과(attempted=false인 NOT_EXECUTED·BLOCKED·STALE)는 실패로 세지 않습니다.
- **F12 주의:** 후보는 현재 화면일 때만 붙으므로, "클릭 성공 뒤 저장"을 하려면 D의 지문(`RuleKey`)은 **클릭 전에** 만들어 두고 성공이 확인된 뒤 `save`해야 합니다. 클릭 뒤에는 팝업이 닫혀 화면이 바뀌므로 후보가 0개가 되어 지문이 `UNKNOWN_TARGET`으로 실패합니다.

## 4. 프리셋 자동 닫기 연결 (F9, A #42 병합 뒤)

자동 닫기는 사람의 선택 없이 B 정책이 직접 승인하는 경로입니다. 아래가 모두 연결되기 전에는 일어나지 않습니다. 운영 스위치(`PresetAutoCloseFeature.live`)도 꺼져 있습니다.

| 누가 | 무엇을 | 없으면 |
|---|---|---|
| A | `PopupAnalysis.autoEligibility`: 5개 검사 전부, 같은 토큰, 원본 화면 `capturedAtUptimeMillis` | B가 `NOT_AUTO_ELIGIBLE`로 묻기 |
| C | `DecisionContext.autoCloseKinds` = `consent.current().autoCloseKinds` (`consent = UiVoicePreferences.get(service).autoCloseConsentSource`, 판단할 때마다 `current()`로 새로 읽기, 캐시 금지). 첫 실행 완료·자동 닫기 스위치·`PresetAutoCloseFeature.live`를 모두 거친 값이고, F9의 `presetAndFirstSeenConsent`(`granted`)와 같은 객체에서 읽음. **`HandlingPreset.autoCloseKinds()`(프리셋 값 그대로)는 스위치를 건너뛰므로 쓰지 않음** (재호 #52 리뷰) | 빈 집합 → 지금처럼 묻기 |
| A (B 리뷰) | `F9PolicyBridge` 어댑터 (아직 없음. A가 연결, 우진 10/25 우선순위 2) | F9가 `POLICY_UNAVAILABLE`로 전부 막음 |
| A | 누르든 안 누르든 모든 결과를 `policy.onActionResult`로 | B가 30초 뒤 "결과 모름"으로 보고 그 팝업을 세션 내내 자동 금지 |
| A → C | `PopupHandlingListener.onHandled(HandlingReport)` (PRESET·REMEMBERED_RULE만, 결과는 EXECUTE일 때만). C 구현은 `SpeechHandlingListener`(#51) | 자동으로 닫은 사실을 사용자가 모름 |

**어댑터 동작 (B 기준):**

```kotlin
// 서비스 세션마다 하나. 사용자 선택 경로의 DecisionChoiceHandler와 같은 인스턴스를 씁니다.
val policy = DecisionActionPolicy { SystemClock.uptimeMillis() }
// C의 자동 닫기 동의. 객체는 한 번 받아 두고, 값은 판단할 때마다 current()로 읽습니다.
val consent = UiVoicePreferences.get(service).autoCloseConsentSource

// authorize: B 배선 extractor로 받은 분석으로 판단
val analysis = wiring.extractor.extract(snapshot) // wiring.source 이벤트로 받은 같은 화면
val decision = policy.decide(analysis, DecisionContext(
    currentToken = wiring.source.currentToken(),
    userChoice = null, matchingRule = null,
    userInteracting = /* A */, announcementAvailable = /* C */,
    autoCloseKinds = consent.current().autoCloseKinds, // C: 매번 새로 읽기 (위 표)
))
// 승인은 이것뿐: next == EXECUTE && source == PRESET (곧 ALLOW_ONCE, 사유 없음)
// → F9Approval(decision, analysis.autoEligibility, CONTRACT_V2), 대상은 decision.candidateId

// onActionResult: F9 결과를 ActionResult로 바꿔 그대로 넘김 (attempted=false 포함)
policy.onActionResult(result)
```

- B 승인은 요청일 뿐입니다. F9가 누르기 직전에 화면·대상·설정을 다시 확인합니다.
- `next`가 WAIT이면 A가 다음 화면에서 다시 판단하고 C는 묻지 않습니다. ANNOUNCE는 안내만, ASK는 지금의 선택 창 흐름, DENY는 안내만(사기 의심은 경고) 합니다.

## 5. 브랜치·PR

| 내용 | 위치 |
|---|---|
| 분류기·정책·후보 탐색기 | main (#8·#11·#12) |
| 연결 부품 (`DecisionCandidateWiring`, `DecisionChoiceHandler`) | main (#19) |
| F5 영상 광고, F12 기억 결과 | main (#20, #23·#48) |
| 공용 라벨 계약 `ButtonLabelRules` | main (#14) |
| 영어 경계 수정, 계약 v2, 정책 연결, 계약 v3(HandlingReport), 자동 SKIP 꺼짐, 제목 개인정보 제외 | main (#37, #38, #44, #45, #46, #50) |
| 서비스 조립 (A) | A 브랜치 `transfer/…` (#29 진행 중, main 미병합) |
| F9 클릭 실행 (A) | #42 (Draft) |

## 6. 시험

- `DecisionCandidateWiringTest` 12개: 같은 화면에만 후보 부착, 다른 화면·새 팝업·`Blocked`/`Invalidated`·다른 토큰 분석·불확실 감지·A 현재 화면이 아님에서는 0개, 늦게 온 옛 이벤트가 최신 기억을 덮지 못함, 같은 버전 차단, 미리 붙은 후보는 쓰지 않음, A 이벤트는 그대로 전달, 같은 창 팝업은 팝업 글자로 분류, 적격 근거는 그 화면의 수집 시각일 때만 유지, 첫 통합 경로(분류 → 선택 → C의 `ChoiceHandler` 모양 → 게이트 1회, 클릭 없음)
- `DecisionChoiceHandlerTest` 9개
- 자동 닫기: `DecisionAutoCloseTest` 29개, `DecisionActionPolicyPresetTest` 7개, `DecisionAutoCloseScenarioTest` 3개(에뮬레이터 덤프 28화면 × 프리셋 3, A의 실제 필터 통과, **A 적격 근거는 있다고 가정**)
- 토큰 확인·현재 화면 확인·버전 확인을 하나씩 일부러 빼면 시험이 실패하는 것을 확인했습니다
