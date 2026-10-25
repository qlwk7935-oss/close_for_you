# C 연결: 공용 계약 v2(#38) — 쿠폰·프리셋 동의 범위·자동 처리 결과 안내

- 브랜치: `c/contract38-connect` = #38(`917800b`, 이후 `e35417f`까지 merge) 위에 `origin/feature/ui-voice`(`ce864c2`)를 merge(충돌 없음) + C 커밋 1개
- 10/25 갱신: `origin/feature/ui-voice`(`5e50653`: 도와줄 앱·말로 찾기·debug 진단 수정)와 #38 최신(`bfbf6dc`: 적격 근거 5초를 수집 시각부터, COUPON 임시 문구 "팝업이 떴어요.")을 다시 merge
  - 충돌 1: `res/values/ui_voice_strings.xml` 끝부분 — 양쪽 모두 새 문자열 추가(`ui_auto_*` / `ui_onboarding_step_*`·`ui_help_apps_*`·`ui_voice_search_*`) → 둘 다 유지
  - 충돌 2: `ChoicePromptPlanner.summaryOf`의 COUPON 줄 — #38 임시 `COUPON -> PromptSummary.UNKNOWN` 대신 C 쿠폰 문장 `COUPON -> PromptSummary.LIKELY_COUPON`("쿠폰으로 보이는 팝업이에요.", `ui_choice_summary_coupon`) 유지
  - 새로 들어온 도와줄 앱·말로 찾기 코드에는 `PopupClassification` 분기가 없다. `HandlingPreset`은 이미 COUPON 분기 있음(①②는 닫기, ③은 알려 주기, 결정·모름은 항상 묻기)
- 상태: **Mock 기준, 실제 연동 미검증.** 실기기·TalkBack 확인 안 함. A·B·D·`core/**`는 고치지 않음(#38 내용 그대로)

## 0. #38 병합 후 할 일 (사용자 재호)

순서 (작업 트리 `D:\close_for_you`, 다른 세션이 쓰지 않을 때):

1. `git fetch origin`
2. `git switch feature/ui-voice` → `git pull` (원격과 같게)
3. `git merge origin/main` — #38이 main에 들어와 `PopupClassification.COUPON`이 생긴다
4. `git merge c/contract38-connect` — C 쿠폰 연결(요약 문장·프리셋·자동 안내)
5. 빌드: `$env:JAVA_HOME="D:\Android\Android Studio\jbr"; $env:ANDROID_HOME="D:\Android\SDK"; .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
6. `git push` → PR #34가 자동으로 갱신된다. PR 설명에 "#38 COUPON 호환: C 쿠폰 문장·프리셋 COUPON 분기, Mock 기준"을 한 줄 더한다

예상 충돌 지점:

| 단계 | 파일 | 무엇 | 해결 |
|---|---|---|---|
| 3 | (보통 없음) | main의 #38은 C 파일 중 `ChoicePromptPlanner.kt` 한 줄만 바꾼다. 다만 3단계 직후 `HandlingPreset.preferenceFor`의 `when`에 COUPON이 없어 **컴파일이 깨진 상태**다. 4단계까지 이어서 하고 그 뒤에 빌드한다 | — |
| 4 | `ui/voice/choice/ChoicePromptPlanner.kt` `summaryOf` | main: `COUPON -> PromptSummary.UNKNOWN`(임시) / C: `COUPON -> PromptSummary.LIKELY_COUPON` | **C 줄 유지**, 임시 주석 2줄 삭제 |
| 4 | `core/**` (`PopupModels.kt`, `PopupContracts.kt`, `AutoCloseContractTest.kt`) | #38을 squash로 병합하면 커밋이 달라 같은 내용이 두 번 들어온다. 내용이 같으면 충돌 없음. #38이 `bfbf6dc` 뒤에 더 바뀌었으면 충돌 | **main 쪽으로**(`git checkout origin/main -- <파일>`). core는 C가 고치지 않는다 |
| 4 | `res/values/ui_voice_strings.xml` | 그 사이 feature/ui-voice에 새 문자열이 더 붙었으면 끝부분 충돌 | 양쪽 다 유지 |
| 빌드 | C 시험 | #38이 더 바뀌어 `AutoDecisionSpeechTest`·`HandlingPresetTest`가 실패하면 C 시험만 계약에 맞춘다 | — |


## 1. 바뀐 것 (C 파일만)

| 파일 | 내용 |
|---|---|
| `ui/voice/choice/ChoicePromptPlanner.kt` | `PromptSummary.LIKELY_COUPON` 추가. 준영의 임시 줄(`COUPON -> LIKELY_AD`)을 `COUPON -> LIKELY_COUPON`으로 교체. `summaryOf`를 `internal`로(자동 안내가 같은 요약을 씀) |
| `ui/voice/choice/ChoicePromptContent.kt` | `LIKELY_COUPON -> ui_choice_summary_coupon` |
| `ui/voice/settings/HandlingPreset.kt` | `preferenceFor(PopupClassification)`에 COUPON 연결(준영이 말한 그 한 곳). `HandlingPreset.autoCloseKinds()`(CLOSE인 종류만 → USER_DECISION·UNKNOWN은 구조상 못 들어감). `UiVoiceSettings.autoCloseKinds(live)`: **[시작하기] 전이면 빈 집합**, `PresetAutoCloseFeature.live == false`인 동안도 빈 집합 |
| `ui/voice/settings/HandlingPresetSection.kt` | "쿠폰을 따로 알아보는 기능은 준비 중이에요." 줄 제거 |
| `ui/voice/AnnouncementBatcher.kt` | `ClosedKind { AD, COUPON, NOTICE, OTHER }`. `add(outcome, kind?, title?)`(기본값으로 기존 호출 그대로). `BatchSummary.closedKinds`·`onlyTitle`(toString에서 가림). `BatchSentence.build(closedLine = null)` |
| `ui/voice/AutoDecisionSpeech.kt` (새) | 순수 함수 `speechFor(decision, classification, result?, title?, waitAlreadySaid, videoAd?)` → `AutoSpeech`(Quiet / Batch / Say / Announce / Ask), `batchSentence`, `render`, `shortTitle`(20자). `WaitSaidOnce`(같은 팝업 WAIT 한 번만, 메모리·최대 32개) |
| `res/values/ui_voice_strings.xml` | `ui_choice_summary_coupon`, `ui_auto_*` 15개 추가, `ui_preset_coupon_pending` 삭제 |

### 문장 표 (시험 `AutoDecisionSpeechTest`가 실제 xml로 확인)

| B·A가 준 것 | C가 하는 것 | 말 |
|---|---|---|
| EXECUTE + `CLOSED_CONFIRMED`, 제목 있음, 2초 안에 1개 | 묶음 | '봄맞이 할인 이벤트' 광고를 닫았어요. |
| 〃 제목 없음 | 묶음 | 광고를 / 쿠폰을 / 공지를 / 팝업을 닫았어요. |
| 〃 2초 안에 여러 개 | 묶음 | 광고 2개, 쿠폰 1개를 닫았어요. |
| 〃 사용자가 직접 고른 닫기와 섞임 | 묶음 | 팝업 2개를 닫았어요. (기존 문장) |
| EXECUTE + `LEFT_APP` | 바로 | 광고를 닫으려다 다른 화면이 열렸어요. 이 앱에서는 당분간 자동으로 처리하지 않을게요. |
| EXECUTE + `STILL_PRESENT`/`UNKNOWN`/관찰 없음(눌림·실패·모름) | 묶음 | 팝업 1개는 닫혔는지 확인하지 못했어요. (기존) |
| EXECUTE + 결과 아직 없음 / NOT_EXECUTED·BLOCKED·STALE | 말 안 함 | — (A의 다음 감지가 다시 B에 묻는다) |
| WAIT + `WAITING_FOR_TARGET`, 이 팝업 처음 | 바로 | 광고가 끝나면 닫을게요. / 쿠폰을 바로 닫을 수 없어서 잠시 기다릴게요. |
| 〃 영상 광고 + B가 읽은 초(F5 플래그 켜졌을 때만) | 바로 | 영상 광고로 보이는 팝업이에요. 5초 뒤에 넘길 수 있다고 나와요. 지금은 대신 누를 버튼이 없어요. (기존) |
| WAIT 같은 팝업 반복 / `AWAITING_RESULT` / 사용자 조작 중 | 말 안 함 | — |
| ANNOUNCE | 기존 요약 + 내용 읽기 경로 | 공지로 / 쿠폰으로 보이는 팝업이에요. … |
| ASK | 기존 "닫을까요?" 창 | (기존) |
| `SUSPICIOUS_CONTENT` (EXECUTE가 아니면 next와 관계없이) | 바로, **선택 창 없음** | 사기일 수 있는 팝업이에요. 아무것도 누르지 마세요. |
| DENY (결정) | 바로 | 확인이 필요한 팝업이에요. 직접 확인해 주세요. (+ `CannotPressReasonFeature` 켜지면 이유 한 줄) |

- "닫았어요"는 `CLOSED_CONFIRMED`일 때만. `MECHANICAL_SUCCESS`만으로는 말하지 않는다(우진 조건).
- 새 문장에 음성 명령어("닫아·닫기·넘겨·건너뛰·읽어·그대로·뒤로·버튼으로" 등)를 넣지 않았다(시험).
- 제목은 화면 글자다. `AutoSpeech.Batch`·`BatchSummary`의 toString에서 가리고, 묶음 창(2초) 동안 메모리에만 둔다.

## 2. 연결 지점 (아직 연결 안 함)

- **결과 통로가 계약에 아직 없다.** B의 `ActionDecision`(next·source·reasons)과 A의 `ActionResult.outcome`을 C에 실어 주는 경로가 없다. 지금 C가 받는 것은 `PopupChoiceFlow`의 `PopupEvent.Candidate`(분석)와 `ChoiceHandler.handle()`이 돌려주는 `ActionResult?`(사용자가 고른 누르기만)뿐이다.
- 그래서 `AutoDecisionSpeech`는 **순수 함수까지만** 만들고 `PopupChoiceFlow`에 붙이지 않았다. 지금 앱 동작은 그대로다.
- 붙일 때(제안):
  1. A가 자동 처리 결과를 C에 넘기는 콜백 하나(예: `AutoDecisionListener.onAutoDecision(analysis, decision, result?)`, 또는 `PopupEvent`에 `AutoHandled` 하나 추가 — 공용 계약이라 합의 후 별도 PR).
  2. C는 그 안에서 `speechFor(...)` → `Batch`면 `batcher.add(outcome, kind, title)`, `Say`면 `PopupAnnouncer`로 바로, `Announce`면 기존 내용 읽기, `Ask`면 지금의 `handleCandidate` 경로.
  3. 묶음 flush 쪽은 `BatchSentence.build` 대신 `AutoDecisionSpeech.batchSentence`를 부른다(종류가 없으면 기존 문장 그대로).
  4. `WaitSaidOnce`의 키는 "같은 팝업"을 뜻하는 값(A의 팝업 창/인스턴스 또는 D의 지문)으로 정해야 한다. 팝업이 닫히면 `forget`, 서비스가 끝나면 `clear`.
- **프리셋 → `DecisionContext.autoCloseKinds`**: `DecisionContext`를 만드는 쪽(지금은 B `decision/DecisionChoiceHandler.kt:47`, 자동 경로는 A 조립부)이 `UiVoicePreferences.get(context).current().autoCloseKinds()`를 넣으면 된다. 지금은 `PresetAutoCloseFeature.live = false`라 항상 빈 집합(화면의 "지금은 닫기 전에 물어봐요"와 맞춤). A F9·B 정책이 켜질 때 `live`를 함께 켠다.

## 3. 남은 것

- [ ] A: 자동 처리 결과(`ActionDecision` + `ActionResult.outcome`)를 C에 실어 줄 경로 (위 2-1)
- [ ] 팝업 **제목** 전달 여부: 계약에 제목이 없다. `PopupAnalysis.texts`에서 C가 첫 줄을 고르면 추측이 된다. A/B가 "제목으로 볼 글자"를 정해 줄지(예: `PopupAnalysis.title: String?`, 없으면 null) 합의 필요. 그 전에는 `title = null` → "광고를 닫았어요."
- [x] `LEFT_APP_BEFORE` 범위: 그 앱, 접근성 서비스가 끝날 때까지(B 메모리, #38 `e35417f`). "당분간"과 맞음. 묻기는 계속됨
- [ ] WAIT가 끝내 닫기 버튼 없이 끝났을 때(광고가 안 끝남) B가 ASK로 넘어오는지 — 그때는 기존 선택 창
- [ ] `ui_onboarding_intro`("광고는 자동으로 닫아요")는 `live = false`인 지금은 앞서 말하는 문장이다. `ui_preset_not_live` 줄이 같이 보이긴 하지만, live 전까지 첫 문장을 다듬을지 팀 확인
- [ ] `WaitSaidOnce` 키 후보: `SnapshotToken.popupInstanceId`가 같은 팝업의 다음 스냅샷에서도 같은지 A 확인
- [ ] `ActionResult.outcome`은 누가 요청했든(사람·F12·프리셋) 채운다고 계약에 적힘(#38 `e35417f`). → `AnnouncementBatcher.outcomeOf`도 `CLOSED_CONFIRMED`일 때만 CLOSED로 바꿀 것(C 후속, 지금은 A가 outcome을 안 채워 동작 같음)
- [x] 계약이 강제함(#38 `e35417f`): `IMPORTANT_DECISION`·`SUSPICIOUS_CONTENT`면 DENY만, WAIT면 `WAIT_REASONS` 중 하나 필수. C 시험 `contractAllowsOnlyDenyForSuspiciousOrDecision`·`contractRequiresAReasonForWait`
- [ ] 새 문장 TalkBack 실기기 확인(LG 벨벳) — 미검증
