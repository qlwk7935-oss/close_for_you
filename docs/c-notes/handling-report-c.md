# C 연결: 처리 보고(HandlingReport, 계약 v3 #45) → 음성 안내

- 브랜치: `c/handling-report` (feature/ui-voice `5fb23bc` 위, 커밋 1개). **Mock 기준, 실제 연동 미검증.** 실기기·TalkBack 확인 안 함
- 바뀐 것(C 파일만): `ui/voice/SpeechHandlingListener.kt`(새), 시험 `SpeechHandlingListenerTest.kt`(새, 22개), 이 문서, `presenter-usage-for-a.md` 6장
- 고치지 않음: `core/**`, A·B·D 코드, 매니페스트, Gradle. `PresetAutoCloseFeature.live`·REVALIDATING은 계속 꺼짐

## 1. 계약 (C가 지킨다)

- `PopupHandlingListener.onHandled(report)` C 구현 = `SpeechHandlingListener`.
- **결과 안내는 C 리스너 하나만 말한다.** A가 이 리스너를 쓰면 #42 `F9ResultFeedback`은 말하지 않는다(A 조립 예: [presenter-usage-for-a.md](presenter-usage-for-a.md) 6장). 사용자가 직접 고른 누르기는 `PopupChoiceFlow`가 말하고, 둘은 같은 `AnnouncementBatcher`를 써서 2초 안의 결과는 한 문장이 된다.
- "닫았어요"는 `HandlingReport.closedConfirmed`(= attempted + MECHANICAL_SUCCESS + CLOSED_CONFIRMED)일 때만. 계약 값 그대로 쓰고, C의 `PressSuccess.isConfirmed`와 모든 조합에서 같은지 시험(`closedConfirmedMatchesPressSuccess`).
- 제목(`report.title`)은 화면 글자: 앞 20자만 말하고, 2초 묶음 동안 메모리에만 둔다. 로그·저장 없음. `HandlingReport`·`AutoSpeech.Batch`·`BatchSummary`·`AnnouncementRequest`·리스너의 toString에 제목이 없다(시험).

## 2. 문장 표 (시험이 실제 `ui_voice_strings.xml`로 확인)

| 보고 | C | 말 |
|---|---|---|
| EXECUTE + closedConfirmed, 제목 있음, 2초 안에 1개 | 묶음 | '가을맞이 특가 이벤트 지금 바로 확인' 광고를 닫았어요. (20자) |
| 〃 제목 없음 / 제목에 긴 숫자 | 묶음 | 광고를 / 쿠폰을 / 공지를 닫았어요. |
| 〃 2초 안에 여러 개 | 묶음 | 광고 2개, 쿠폰 1개를 닫았어요. |
| EXECUTE + LEFT_APP | 바로 | 쿠폰을 닫으려다 다른 화면이 열렸어요. 이 앱에서는 당분간 자동으로 처리하지 않을게요. |
| EXECUTE + 시도했지만 closedConfirmed 아님(STILL_PRESENT·UNKNOWN·관찰 없음·실패인데 사라짐) | 묶음 | 팝업 1개는 닫혔는지 확인하지 못했어요. |
| EXECUTE + 결과 없음 / NOT_EXECUTED·BLOCKED·STALE(attempted=false) | 말 안 함 | — |
| WAIT + WAITING_FOR_TARGET, 이 팝업 처음 | 바로 | 광고가 끝나면 닫을게요. / 쿠폰을 바로 닫을 수 없어서 잠시 기다릴게요. (영상 광고 초 안내는 F5 플래그 켜졌을 때만) |
| WAIT 같은 팝업 반복 / AWAITING_RESULT·USER_INTERACTION·STALE_SCREEN | 말 안 함 | — |
| ANNOUNCE | 바로 | 공지로 보이는 팝업이에요. + (`contentOf`가 주면) 기존 내용 읽기 문장 |
| ASK | 말 안 함 | 기존 "닫을까요?" 선택 창(A가 PopupChoiceFlow로) |
| DENY + SUSPICIOUS_CONTENT | 바로, 선택지 없음 | 사기일 수 있는 팝업이에요. 아무것도 누르지 마세요. |
| DENY (중요 결정) | 바로 | 확인이 필요한 팝업이에요. 직접 확인해 주세요. (기존 `AutoDecisionSpeech`) |

- 누른 뒤 결과 문장은 token 없이(`null`) 보낸다: 그 팝업 token은 이미 지났다(#42 F9ResultFeedback과 같은 방식). WAIT·ANNOUNCE·DENY는 보고의 token.
- 새 문장 없음(모두 기존 `ui_auto_*`·`ui_choice_summary_*`). 말한 문장에 음성 명령어가 없는지 시험(`spokenSentencesHaveNoCommandWords`).
- 포커스를 옮기지 않는다.

## 3. 준영 제목 규칙 검토 (리뷰 메모)

규칙: 팝업 안 글자 중 닫기 후보 라벨·결정·사기 문구가 아닌 **첫 글자**, 없으면 null.

- 자르기·말하기: 문제 없음. C가 공백 정리·따옴표 제거·20자(코드포인트, 이모지 안 깨짐) 자르기. 빈 문자열은 계약이 막음(`require(title == null || title.isNotBlank())`).
- **개인정보 위험**: "첫 글자"가 제목이 아닐 수 있다. 예: 배송·주문 팝업의 "홍길동님", "010-1234-5678로 문의", 주문번호·계좌. 묶음 안내가 이 글자를 소리로 읽으면 옆 사람에게 들린다.
  - C 쪽 보호(이번에 넣음): 숫자 7자리 이상(공백·점·하이픈 사이 허용)이 있으면 제목을 버리고 "광고를 닫았어요."만 말한다(`spokenTitle`). "50% 할인 쿠폰 2024" 같은 짧은 숫자는 그대로.
  - 이름("○○님")은 C가 가려낼 근거가 없어 그대로 둔다. **B 제안:** 제목 후보에서 "님"으로 끝나는 짧은 줄·숫자 위주 줄·`ExtractedText` 중 입력칸/버튼 아닌 작은 글자를 빼거나, 광고·쿠폰·공지 분류일 때만 제목을 채우기. 결정·모름 팝업은 지금도 자동 닫기가 없어 제목이 말해질 일 없음.
- 제목에 "건너뛰기" 같은 명령어가 섞일 수 있지만, B가 닫기 후보 라벨을 빼고, 음성 인식은 [말하기]를 누를 때만이라 위험 낮음.

## 4. 남은 것·질문

- [ ] **A 질문:** WAIT 1회의 "같은 팝업" 기준. 계약에 안정된 식별 값이 없다(`popupInstanceId`는 스냅샷마다 새 UUID). 지금 C 기본값은 `windowId + structureDigest`(초 카운트다운처럼 글자만 바뀌면 같음, 닫기 버튼이 새로 나타나면 다름). A의 F0 같은 팝업 규칙이 있으면 `waitKeyOf`로 넘겨 주기. 모르면(`null`) C는 말한다(조용히 버리지 않음).
- [ ] **A:** `F9ResultFeedback` 대신 이 리스너 사용(위 1장). ANNOUNCE 때 같은 token의 분석으로 `contentOf` 제공.
- [ ] B 처리 보고가 실제로 PRESET/REMEMBERED_RULE에서만 오는지(계약 require로 강제됨).
- [ ] LG 벨벳 TalkBack 실기기: 묶음 문장·LEFT_APP·사기 경고가 TalkBack 낭독과 겹치는지 — 미검증.
