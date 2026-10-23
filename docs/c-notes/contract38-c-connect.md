# C 연결: 공용 계약 v2(#38) — 쿠폰·프리셋 동의 범위·자동 처리 결과 안내

- 브랜치: `c/contract38-connect` = #38(`917800b`) 위에 `origin/feature/ui-voice`(`ce864c2`)를 merge(충돌 없음) + C 커밋 1개
- 상태: **Mock 기준, 실제 연동 미검증.** 실기기·TalkBack 확인 안 함. A·B·D·`core/**`는 고치지 않음(#38 내용 그대로)

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
- [ ] `LEFT_APP` 뒤 "이 앱에서는 당분간 자동으로 처리하지 않을게요"는 B의 `LEFT_APP_BEFORE`가 실제로 그 앱을 멈춰야 참이다. 멈추는 기간("당분간")을 B와 맞출 것
- [ ] WAIT가 끝내 닫기 버튼 없이 끝났을 때(광고가 안 끝남) B가 ASK로 넘어오는지 — 그때는 기존 선택 창
- [ ] `ui_onboarding_intro`("광고는 자동으로 닫아요")는 `live = false`인 지금은 앞서 말하는 문장이다. `ui_preset_not_live` 줄이 같이 보이긴 하지만, live 전까지 첫 문장을 다듬을지 팀 확인
- [ ] `WaitSaidOnce` 키 후보: `SnapshotToken.popupInstanceId`가 같은 팝업의 다음 스냅샷에서도 같은지 A 확인
- [ ] A가 사용자가 직접 고른 누르기에도 `ActionResult.outcome`을 채우면, `AnnouncementBatcher.outcomeOf`도 `CLOSED_CONFIRMED`일 때만 CLOSED로(C 후속, 지금은 outcome이 늘 null이라 동작 같음)
- [ ] B: `SUSPICIOUS_CONTENT`면 `next == DENY`를 계약으로 강제할지 (C는 이미 EXECUTE가 아니면 경고만)
- [ ] 새 문장 TalkBack 실기기 확인(LG 벨벳) — 미검증
