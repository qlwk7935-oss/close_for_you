# C 연결: 처리 보고(HandlingReport, 계약 v3 #45) → 음성 안내

- 브랜치: `c/handling-report` (최신 `origin/main`을 merge해 맞춤, #34 squash·#50 포함). **Mock 기준, 실제 연동 미검증.** 실기기·TalkBack 확인 안 함
- 바뀐 것(C 파일만): `ui/voice/SpeechHandlingListener.kt`(새), 시험 `SpeechHandlingListenerTest.kt`(새), `AutoDecisionSpeech.kt` KDoc 한 줄, 이 문서, `presenter-usage-for-a.md` 6장, `contract38-c-connect.md` 체크 1줄
- 10/25 우진 요청 반영: **제목 낭독 기본 OFF**(`HandlingTitleSpeechFeature.enabled = false`), **WAIT 기록 초기화 입구**(`resetWaitMemory(reason)`), 기본 WAIT 키는 Mock 임시
- 고치지 않음: `core/**`, A·B·D 코드, 매니페스트, Gradle. `PresetAutoCloseFeature.live`·REVALIDATING은 계속 꺼짐

## 1. 계약 (C가 지킨다)

- `PopupHandlingListener.onHandled(report)` C 구현 = `SpeechHandlingListener`.
- **결과 안내는 C 리스너 하나만 말한다.** A가 이 리스너를 쓰면 #42 `F9ResultFeedback`은 말하지 않는다(A 조립 예: [presenter-usage-for-a.md](presenter-usage-for-a.md) 6장). 사용자가 직접 고른 누르기는 `PopupChoiceFlow`가 말하고, 둘은 같은 `AnnouncementBatcher`를 써서 2초 안의 결과는 한 문장이 된다.
- "닫았어요"는 `HandlingReport.closedConfirmed`(= attempted + MECHANICAL_SUCCESS + CLOSED_CONFIRMED)일 때만. 계약 값 그대로 쓰고, C의 `PressSuccess.isConfirmed`와 모든 조합에서 같은지 시험(`closedConfirmedMatchesPressSuccess`).
- **제목 낭독 기본 OFF** (첫 운영 통합, 우진 10/25): `HandlingTitleSpeechFeature.enabled = false`. 꺼져 있으면 `report.title`이 있어도 종류만 말한다("광고를/쿠폰을/공지를 닫았어요.", 묶음도 "광고 1개, 쿠폰 1개를 닫았어요."처럼 종류·개수만). A도 처음엔 `title = null`로 넘길 예정. B는 제목을 계속 채워 둔다. **나중에 C 설정으로 켬, 기본 OFF** — 켜는 곳은 리스너 생성자 인자 `titleSpeechEnabled: () -> Boolean = { HandlingTitleSpeechFeature.enabled }` 한 군데뿐이라 설정 스위치를 여기에 연결하면 된다(설정 UI는 아직 없음). 켜면 아래 규칙(앞 20자, 긴 숫자 제외)이 그대로 살아 있다(시험 `titleOnSaysTheShortTitle`, `longNumbersAreNotSpokenAsATitle`).
- 제목(`report.title`)은 화면 글자: 켜졌을 때도 2초 묶음 동안 메모리에만 둔다. 로그·저장 없음. `HandlingReport`·`AutoSpeech.Batch`·`BatchSummary`·`AnnouncementRequest`·리스너의 toString에 제목이 없다(시험).
- **사용자가 직접 고른 결과는 이 리스너로 오지 않는다.** 계약 `HandlingReport` init이 `decision.source`를 PRESET·REMEMBERED_RULE로 제한해 USER_CHOICE 보고는 만들어지지 않는다(시험 `userChoiceCanNotReachTheListener`). 그래서 같은 누르기를 PopupChoiceFlow와 이 리스너가 두 번 말할 수 없다. ASK와 attempted=false는 말하지 않아서, A가 선택 창을 띄워도 겹치지 않는다.

## 1-1. WAIT 1회와 초기화

- WAIT(WAITING_FOR_TARGET)는 같은 팝업에 1번만 말한다. "같은 팝업"은 `waitKeyOf(report)`가 정한다.
- **기본 `defaultWaitKey` = `windowId + structureDigest`는 Mock 임시이고 운영 기준이 아니다.** A가 F0 같은 팝업 규칙으로 `waitKeyOf`를 넘긴다. 같은 팝업인지 확실하지 않으면 `null` → C는 **매번 말한다**(새 안내를 조용히 버리지 않음, 시험 `nullKeyIsSaidEveryTime`).
- A가 부를 초기화 입구: `resetWaitMemory(WaitResetReason)` — 모든 이유가 기록을 전부 지운다(이유는 호출 지점 표시용).

| 언제 | A가 부르는 것 |
|---|---|
| 다른 팝업이 나타났다고 A가 판단했을 때 (같은 팝업의 새 스냅샷마다 부르면 WAIT 1회가 깨진다) | `resetWaitMemory(NEW_POPUP)` |
| 팝업이 사라졌다고 확인했을 때(닫힘·교체) | `resetWaitMemory(POPUP_REMOVED)` |
| 접근성 서비스 재연결(`onServiceConnected`) | `resetWaitMemory(SERVICE_RECONNECTED)` |
| 서비스 종료(`onDestroy`·`onUnbind`) | `clear()` (= `SERVICE_STOPPED`) |

- 그 밖에 이미 있던 것: 시도한 누르기(attempted=true) 뒤에는 그 키를 지운다(같은 팝업 WAIT가 다시 나오면 말함).

## 2. 문장 표 (시험이 실제 `ui_voice_strings.xml`로 확인)

| 보고 | C | 말 |
|---|---|---|
| EXECUTE + closedConfirmed, 2초 안에 1개 (**기본: 제목 낭독 OFF**, 제목 있어도) | 묶음 | 광고를 / 쿠폰을 / 공지를 닫았어요. |
| 〃 제목 낭독 ON, 제목 있음 | 묶음 | '가을맞이 특가 이벤트 지금 바로 확인' 광고를 닫았어요. (20자) |
| 〃 제목 낭독 ON, 제목 없음 / 제목에 긴 숫자 | 묶음 | 광고를 닫았어요. |
| 〃 2초 안에 여러 개 | 묶음 | 광고 2개, 쿠폰 1개를 닫았어요. |
| EXECUTE + LEFT_APP | 바로 | 쿠폰을 닫으려다 다른 화면이 열렸어요. 이 앱에서는 당분간 자동으로 처리하지 않을게요. |
| EXECUTE + 시도했지만 closedConfirmed 아님(STILL_PRESENT·UNKNOWN·관찰 없음·실패인데 사라짐) | 묶음 | 팝업 1개는 닫혔는지 확인하지 못했어요. |
| EXECUTE + 결과 없음 / NOT_EXECUTED·BLOCKED·STALE(attempted=false) | 말 안 함 | — |
| WAIT + WAITING_FOR_TARGET, 이 팝업 처음 | 바로 | 광고가 끝나면 닫을게요. / 쿠폰을 바로 닫을 수 없어서 잠시 기다릴게요. (영상 광고 초 안내는 F5 플래그 켜졌을 때만) |
| WAIT 같은 팝업 반복 (키가 같고 초기화 전) / AWAITING_RESULT·USER_INTERACTION·STALE_SCREEN | 말 안 함 | — |
| WAIT, `waitKeyOf`가 null(같은지 모름) | 매번 바로 | (위 WAIT 문장) |
| ANNOUNCE | 바로 | 공지로 보이는 팝업이에요. + (`contentOf`가 주면) 기존 내용 읽기 문장 |
| ASK | 말 안 함 | 기존 "닫을까요?" 선택 창(A가 PopupChoiceFlow로) |
| DENY + SUSPICIOUS_CONTENT | 바로, 선택지 없음 | 사기일 수 있는 팝업이에요. 아무것도 누르지 마세요. |
| DENY (중요 결정) | 바로 | 확인이 필요한 팝업이에요. 직접 확인해 주세요. (기존 `AutoDecisionSpeech`) |

- 누른 뒤 결과 문장은 token 없이(`null`) 보낸다: 그 팝업 token은 이미 지났다(#42 F9ResultFeedback과 같은 방식). WAIT·ANNOUNCE·DENY는 보고의 token.
- 새 문장 없음(모두 기존 `ui_auto_*`·`ui_choice_summary_*`). 말한 문장에 음성 명령어가 없는지 시험(`spokenSentencesHaveNoCommandWords`).
- 포커스를 옮기지 않는다.

## 3. 준영 제목 규칙 검토 (리뷰 메모)

> 10/25부터 제목 낭독은 기본 OFF라 아래는 **켤 때** 다시 볼 내용이다. 준영 #50(B 제목 개인정보 필터: 이름·긴 숫자·이메일·웹 주소 줄 제외)이 main에 병합됨 — 아래 "B 제안"의 일부가 반영됐다.

규칙: 팝업 안 글자 중 닫기 후보 라벨·결정·사기 문구가 아닌 **첫 글자**, 없으면 null.

- 자르기·말하기: 문제 없음. C가 공백 정리·따옴표 제거·20자(코드포인트, 이모지 안 깨짐) 자르기. 빈 문자열은 계약이 막음(`require(title == null || title.isNotBlank())`).
- **개인정보 위험**: "첫 글자"가 제목이 아닐 수 있다. 예: 배송·주문 팝업의 "홍길동님", "010-1234-5678로 문의", 주문번호·계좌. 묶음 안내가 이 글자를 소리로 읽으면 옆 사람에게 들린다.
  - C 쪽 보호(이번에 넣음): 숫자 7자리 이상(공백·점·하이픈 사이 허용)이 있으면 제목을 버리고 "광고를 닫았어요."만 말한다(`spokenTitle`). "50% 할인 쿠폰 2024" 같은 짧은 숫자는 그대로.
  - 이름("○○님")은 C가 가려낼 근거가 없어 그대로 둔다. **B 제안:** 제목 후보에서 "님"으로 끝나는 짧은 줄·숫자 위주 줄·`ExtractedText` 중 입력칸/버튼 아닌 작은 글자를 빼거나, 광고·쿠폰·공지 분류일 때만 제목을 채우기. 결정·모름 팝업은 지금도 자동 닫기가 없어 제목이 말해질 일 없음.
- 제목에 "건너뛰기" 같은 명령어가 섞일 수 있지만, B가 닫기 후보 라벨을 빼고, 음성 인식은 [말하기]를 누를 때만이라 위험 낮음.

## 4. 남은 것·질문

- [ ] **A:** WAIT 1회의 "같은 팝업" 기준을 `waitKeyOf`로 넘기기(기본 `windowId + structureDigest`는 Mock 임시, `popupInstanceId`는 스냅샷마다 새 UUID라 못 씀). 모르면 `null` → C는 매번 말한다.
- [ ] **A:** 1-1장 표의 시점에 `resetWaitMemory(...)` 호출.
- [ ] **A:** `F9ResultFeedback` 대신 이 리스너 사용(위 1장, 중복 호출 금지). ANNOUNCE 때 같은 token의 분석으로 `contentOf` 제공. 첫 통합은 `title = null`.
- [ ] 제목 낭독을 켤지(`HandlingTitleSpeechFeature`)는 실기기 확인·팀 합의 뒤. 지금은 꺼짐.
- [ ] B 처리 보고가 실제로 PRESET/REMEMBERED_RULE에서만 오는지(계약 require로 강제됨).
- [ ] LG 벨벳 TalkBack 실기기: 묶음 문장·LEFT_APP·사기 경고가 TalkBack 낭독과 겹치는지 — 미검증.
