# B 연결 안내 (A 조립용)

작성: B 준영 · 2026-10-21 · 대상: 첫 통합 "실제 Dialog → A 분석 → B 분류·안전 판단 → C 안내" (클릭·자동 닫기·기억 저장 없음)

> JVM 단위 시험만 통과했습니다. 실제 서비스·실기기에서는 아직 연결해 보지 않았습니다.

## 1. 쓸 클래스 (전부 `kr.closeforyou.decision`, Android 의존 없음)

| 클래스 | 계약 | 역할 |
|---|---|---|
| `DecisionPopupClassifier` | `PopupClassifier` | F7 표시 분류 (광고/공지/결정/모름). 실행 허가가 아닙니다 |
| `DecisionActionPolicy` | `ActionPolicy` | F8 안전 판단. 금지 우선, 처음 본 팝업은 항상 묻기, 자동 반복 꺼짐 |
| `DecisionCandidateWiring` | `SnapshotSource` + `EvidenceExtractor`를 감쌈 | A의 분석에 같은 화면(토큰)의 B 닫기 후보(E1/E2)를 붙입니다 |
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

- **같은 `wiring` 하나를 C 흐름과 D 지문 생성기(F12 연결 때)에 함께 넘겨 주세요.** D의 `DeviceKeyedFingerprintProvider(evidenceExtractor = wiring.extractor, …)`가 저장 직전에 다시 `extract`해도 같은 화면이면 같은 후보가 붙습니다.
- `wiring.source.events`를 C가 구독할 때 B가 최신 `Candidate`의 감지 신호만 기억합니다. 스냅샷은 보관하지 않고, `Blocked`·`Invalidated`가 오면 잊습니다.
- A의 이벤트는 여러 곳에서 구독할 수 있어서, 늦게 처리된 옛 이벤트가 최신 기억을 덮어쓰지 않도록 화면 버전으로 거릅니다. `Invalidated(v)` 이후에는 v보다 오래된 화면이 돌아오지 않습니다. `Blocked(v)`는 버전 v 화면까지 막습니다.

## 3. 동작 규칙

- **후보:** `NEW_WINDOW`는 활성 창 전체, `SAME_WINDOW_OVERLAY`는 Dialog 노드가 하나일 때 그 하위 노드만 봅니다. `FULL_SCREEN_TRANSITION`·`INSUFFICIENT_EVIDENCE`에서는 후보를 만들지 않습니다. 라벨 없는 X·아이콘(E3)·그림(E4)은 후보가 아닙니다.
- **다른 화면:** 스냅샷·분석·감지의 토큰이 하나라도 다르거나, 그 스냅샷이 A의 `currentToken()`(TTL·차단·화면 변경 반영)과 다르면 후보 0개입니다. 이전 화면의 버튼을 새 화면에 내지 않습니다.
- **선택 처리:** 누르기 선택(`CLOSE_ONCE`/`SKIP_ONCE`)이 아니면 `null`을 반환하고 게이트를 부르지 않습니다. 정책이 `ALLOW_ONCE`가 아니면 게이트 없이 `BLOCKED`/`STALE`/`NOT_EXECUTED`를 반환합니다. `ALLOW_ONCE`면 토큰을 한 번 더 확인하고 게이트를 정확히 1번 부릅니다. 게이트 예외는 재시도 없이 `OUTCOME_UNKNOWN`(attempted=true)입니다.
- **`ALLOW_ONCE`는 요청일 뿐입니다.** 실제 화면 재검증과 실행 결정은 A 게이트가 합니다.
- **기억 규칙:** 첫 통합에서는 쓰지 않습니다(`matchingRule = null`). F12 연결 때 B가 정책 전에 `findExact` 결과를 받고, 기억으로 실행했는데 실패하면 D의 `recordFailure`를 부르는 부분을 추가합니다.
- **F12 주의:** 후보는 현재 화면일 때만 붙으므로, "클릭 성공 뒤 저장"을 하려면 D의 지문(`RuleKey`)은 **클릭 전에** 만들어 두고 성공이 확인된 뒤 `save`해야 합니다. 클릭 뒤에는 팝업이 닫혀 화면이 바뀌므로 후보가 0개가 되어 지문이 `UNKNOWN_TARGET`으로 실패합니다.

## 4. 브랜치·PR

| 내용 | 위치 |
|---|---|
| 분류기·정책·후보 탐색기 | `main`에 있음 (PR #8·#11·#12) |
| 이 연결 부품 (`DecisionCandidateWiring`, `DecisionChoiceHandler`, 후보 붙이기) | 브랜치 `feature/decision-integration` |
| 공용 라벨 계약 `ButtonLabelRules` | PR #14 (병합 뒤 B 후보 판정도 이 계약으로 교체) |
| 문서·평가 | PR #13 |

## 5. 시험

- `DecisionCandidateWiringTest` 10개: 같은 화면에만 후보 부착, 다른 화면·새 팝업·`Blocked`/`Invalidated`·다른 토큰 분석·불확실 감지·A 현재 화면이 아님에서는 0개, 늦게 온 옛 이벤트가 최신 기억을 덮지 못함, 같은 버전 차단, 미리 붙은 후보는 쓰지 않음, A 이벤트는 그대로 전달, 첫 통합 경로(분류 → 선택 → C의 `ChoiceHandler` 모양 → 게이트 1회, 클릭 없음)
- `DecisionChoiceHandlerTest` 9개
- 토큰 확인·현재 화면 확인·버전 확인을 하나씩 일부러 빼면 시험이 실패하는 것을 확인했습니다
