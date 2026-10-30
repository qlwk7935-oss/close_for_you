# "앞으로 항상 닫기" 표시 규칙 — C 메모

- 날짜: 2026-10-30, 브랜치 `c/talkback-takeover`, 결정 C(재호), 시연 모드(AGENTS.md 6-0)
- 상태: **Mock 기준, JVM 시험만 통과. D 저장·같은 팝업 판별 미연결, 실기기 미검증.**

## 1. 뜻

"앞으로 항상 닫기"는 **그 팝업 하나**를 기억한다(D 지문). 앱 단위가 아니다.
흐름은 그대로다. 먼저 이번 한 번 닫기(CLOSE_ONCE·SKIP_ONCE)를 보내고, 실제로 닫힌 것이 확인되면 규칙을 저장한다(`PopupChoiceFlow`).
저장소가 연결되기 전(`RememberedPopupStore.NONE`)에는 닫은 뒤 "이 팝업을 기억하지 못했어요. 다음에도 물어볼게요."라고 말한다(거짓 보고 없음).

## 2. 언제 보이나 (`RememberChoiceRules.offers`)

표시 = "이 팝업 종류가 현재 프리셋에서 묻는 종류" + 알아볼 수 있음 + 결정·사기 아님 (재호 확인 10/30).

| 프리셋 | 묻지 않고 닫음 (숨김) | 선택 창 + "앞으로 항상 닫기" 보임 |
|---|---|---|
| 전부 자동 닫기 | 광고·쿠폰·공지 | 판단 불가만 |
| 광고·쿠폰 닫기 | 광고·쿠폰 | 공지 + 판단 불가 |
| 광고만 닫기 | 광고 | 쿠폰·공지 + 판단 불가 |

자동 처리 종류인데 안전 조건 때문에 선택 창이 뜬 경우에도 숨긴다. 어느 프리셋이든 아래는 숨김:

| 경우 | 표시 |
|---|---|
| 중요한 결정(USER_DECISION) | 숨김 |
| 사기 의심(B의 `DecisionKinds.of(analysis) == SUSPICIOUS`, 화면에는 UNKNOWN) | 숨김 |
| 알아볼 정보 없음: 팝업 글(추출된 글자)이 하나도 없음 | 숨김 |
| 버튼별: D 지문이 만들 수 없는 버튼(노드 경로 `nodePath` 없음) | 그 버튼만 숨김 |

- 원래 규칙도 그대로: 이번 한 번 누를 수 있는 버튼(라벨이 계약상 닫기·건너뛰기인 버튼)에만 붙고, 버튼 이름을 말한다.
- "알아볼 정보 없음" 기준: D에 "지문을 만들 수 있나" 묻는 함수가 없어서(D의 `createKey`는 실제 저장용·suspend·예외) C가 가진 신호로 판정한다.
  글자가 없으면 숨김(이미지뿐인 팝업). 이름 있는 버튼이 없으면 원래 누를 버튼 자체가 없어서 안 보인다.
  D 지문 실패 조건 중 C가 볼 수 있는 것(대상 노드 경로 없음)은 버튼별로 반영했다. 글자 없는 광고·공지도 같은 이유로 숨긴다.
- "다시 묻지 않기"는 이번 결정에 없어서 계속 꺼 둔다(`RememberChoiceFeature.stopAskingEnabled = false`).

## 3. 스위치 (한 곳)

`RememberChoiceFeature.DEMO_MODE_ON` (`ui/voice/choice/ChoicePromptPlanner.kt`). 시연은 `true`.
배포 때 D 저장이 연결되지 않았다면 `false`로 되돌린다.

## 4. 통합 때 C가 연결할 것 (A·B·D 코드 수정 없음)

1. `PopupChoiceFlow(..., handlingPreset = { UiVoicePreferences.get(context).current().handlingPreset })`.
   연결하지 않으면 프리셋을 모르는 것으로 보고(null) 프리셋 숨김이 빠진다(광고·쿠폰·공지에도 보임). **시연 전 꼭 연결.**
2. `PopupChoiceFlow(..., rememberStore = RepositoryRememberedPopupStore(...))`.
   이미 있는 C 어댑터(`RememberedPopupStore.kt`)에 D의 `RuleStorage.fingerprintProvider`·`RuleStorage.repository`를 넘긴다.
3. 다음에 같은 팝업이 오면 묻지 않고 닫기: D의 `findExact` → B 정책(`REMEMBER_EXACT_POPUP` 규칙) → A 관문. 시연 모드에는 앱별 자동 닫기 스위치가 없다(`PerAppAutoCloseGateFeature.enabled = false`, 10/30 재호 결정): 도와줄 앱이면 어느 앱이든 반복하고, 기억 직후 안내도 "똑같은 팝업이 다시 뜨면 안전 확인을 한 뒤 닫아요"로 말한다(`RememberChoiceSpeech.afterRememberRes`).
4. 사기 의심은 기본으로 B 규칙을 그대로 읽는다(`plan(suspicious = ...)` 기본값). B가 판정 함수를 바꾸면 컴파일 오류로 드러난다.
