# TalkBack 주도권 ("선택 창이 뜨면 안내를 맡기기") — C 메모

- 날짜: 2026-10-29, 브랜치 `c/talkback-takeover`, 담당 C(재호)
- 근거: AGENTS.md 6-0 시연 모드(최종 발표까지 6-10·C 규칙 "포커스 강제 이동 금지" 완화)
- 상태: **Mock 기준, JVM 시험만 통과. 실기기 미검증. A 실제 서비스 연동 미검증.**

## 1. 동작 순서

TalkBack이 켜져 있고(터치 탐색), 기능 플래그와 사용자 설정이 켜져 있을 때 선택 창이 뜨면:

1. **포커스 가져오기** — A 서비스가 우리 창의 첫 항목(요약 줄 "광고로 보여요" 등)에 `ACTION_ACCESSIBILITY_FOCUS`.
   창이 A의 창 목록에 늦게 들어올 수 있어 100ms 간격으로 최대 10번 시도. TalkBack이 원본 팝업을 읽던 소리가 끊기고 요약을 읽음.
2. **원문 낭독** — 1초 뒤(TalkBack이 요약을 다 읽을 시간, 값 미검증) 우리 TTS가 **팝업 글을 추출된 그대로** 읽음
   (`ChoicePrompt.popupText` = `PromptSpeech.contentOrNull(analysis)`). 뒤에는 C 고정 문장만 붙음:
   누를 버튼이 없으면 "대신 닫을 수 없어요", 영상 광고 기다리기 문장. 글이 없으면 기존 짧은 안내 문장.
   [다시 듣기]는 이 낭독을 다시 읽음.
3. **버튼으로 이동** — TTS가 끝까지 재생되면(`UtteranceProgressListener.onDone` → `TTS_COMPLETED`)
   포커스를 "'닫기' 버튼 눌러 주기"로 옮김. 누를 버튼이 없으면 팝업 버튼을 누르지 않는 첫 C 버튼(내용 다시 읽기 등).
   포커스만 옮기며 **누르지 않는다**. 사용자가 두 번 탭해야 B 정책·A 관문으로 감.

중간에 멈추면(그만 말하기, 말하기, 창 닫힘, 사용자가 화면을 만짐) 3번 이동은 하지 않는다(포커스는 사용자 것).
누르기 실패 뒤 다시 뜨는 창(`pressFailure`)은 이전과 같이 조용히 둔다.

## 2. 스위치 (한 곳)

| 무엇 | 위치 | 시연 | 배포 |
|---|---|---|---|
| 사용자 설정 기본값 | `TalkBackTakeoverFeature.DEFAULT_ON` (`ui/voice/overlay/TalkBackTakeover.kt`) | `true` | `false`로 바꿈 |
| 기능 플래그 | `TalkBackTakeoverFeature.enabled` | `true` | 기능을 아예 빼려면 `false` |
| 사용자 설정 | 설정 화면 "선택 창이 뜨면 안내를 맡기기" (`UiVoiceSettings.talkBackTakeover`) | | |
| 읽는 속도 | 설정 화면 "읽는 속도" (`UiVoiceSettings.speechRate`) | 기기 음성 속도 따르기 | |

## 3. TTS 속도

- "기기 음성 속도 따르기"(기본): `Settings.Secure.TTS_DEFAULT_RATE`(공개·`@Readable`, 100 = 1배, AOSP 36 소스 확인)를 읽어
  `TextToSpeech.setSpeechRate(값/100)`. 못 읽으면 속도를 지정하지 않음 → `TextToSpeechService`가 기기 기본 속도를 씀(같은 소스 확인).
- TalkBack은 기기 TTS 속도를 쓰지만, TalkBack 자체 설정에서 속도를 바꾼 경우 그 값은 TalkBack 안에 있어 **공개 API로 못 읽음** → 설정에서 느리게/보통/빠르게/아주 빠르게(0.8/1.0/1.5/2.0배)를 고름.
- 매 문장마다 다시 읽어서 설정 변경이 다음 문장부터 적용.

## 4. 창 내용이 바뀌지 않게 (A가 C 창 content-changed로 창을 닫던 문제)

- 주도권 여부는 창을 만들 때 한 번 정하고 창이 있는 동안 바꾸지 않음. 말하는 동안 Compose 상태를 바꾸지 않음
  (포커스는 서비스가, 소리는 TTS가 처리 → 리컴포지션·semantics 변경 없음).
- 주도권 창은 paneTitle을 빼서 TalkBack의 창 알림이 우리 낭독과 겹치지 않게 함(창 `title` 레이아웃 값은 그대로 → `ChoiceOverlayIdentity` 영향 없음).
- 그래도 **우리가 옮긴 접근성 포커스 이벤트(`TYPE_VIEW_ACCESSIBILITY_FOCUSED`)와 그에 따른 content-changed는 C 창에서 나온다.**
  A는 이 이벤트를 화면 변경으로 보면 안 된다 → 아래 5-2.

## 5. A가 연결할 지점 (통합은 C가 함)

1. **선택 창 만들기**: 이미 쓰던 그대로 `AccessibilityChoiceOverlay(service, PromptAnnouncementListener(...), PromptControlsSupport.create(...))`.
   새 생성자 인자 `takeoverActive`의 기본값이 설정·TalkBack 상태를 읽으므로 **추가 코드 없음**.
   서비스 설정에 `flagRetrieveInteractiveWindows`·`flagReportViewIds`가 있어야 함(A 설정 XML에 이미 있음).
2. **C 창 이벤트 거르기**: `OwnOverlayWindowTracker.isOwnOverlayWindowEvent(event.windowId, overlay.windowState)`가 참이면
   새 팝업 감지·토큰 무효화·기준선 초기화에서 빼기(이미 제안된 계약, `overlay-identity-contract.md`). 주도권에서는 꼭 필요.
3. **사용자가 만지면 낭독 멈춤**: A의 `onAccessibilityEvent`에서 `TYPE_TOUCH_INTERACTION_START`가 오면
   `promptAnnouncementListener.onUserInteraction()` 호출(선택 창이 떠 있을 때만 의미 있음, 아니면 아무 일도 안 함).
   이 이벤트 종류는 A 설정 XML에 이미 있음.

## 6. 실기기 미검증 항목 (LG 벨벳, Android 12, TalkBack 17)

- A 서비스의 `performAction(ACTION_ACCESSIBILITY_FOCUS)`로 TalkBack 포커스가 실제로 옮겨지고, 원본 팝업 낭독이 끊기는지
- Compose `testTagsAsResourceId`가 `viewIdResourceName`으로 보이는지(오버레이 창에서)
- 요약 낭독과 우리 TTS가 겹치지 않는 대기 시간(현재 1초)
- 끝난 뒤 "'닫기' 버튼 눌러 주기"로 옮겨 TalkBack이 버튼 이름을 읽는지
- 포커스 이동·낭독 중 A가 우리 창을 닫지 않는지(5-2 연결 뒤)
- `TTS_DEFAULT_RATE` 값이 LG에서 읽히는지, TalkBack과 속도가 비슷하게 들리는지

debug 미리보기(`kr.closeforyou/.ui.voice.debug.ChoicePreviewActivity`)의 C 시험 서비스도 연결 시 같은 두 플래그를 켜서 주도권을 시험할 수 있다.
