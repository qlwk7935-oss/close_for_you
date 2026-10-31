# C — UI·TalkBack·음성

소유자: 재호/C, 브랜치: `feature/ui-voice`. 패키지 `kr.closeforyou.ui.voice`.

F10 TalkBack·음성 안내(`PopupAnnouncer`), 사용자 선택 UI("닫을까요?" 선택 창 → `UserChoice`), F13 음성 명령(`VoiceCommandInput`), C 설정 화면을 맡습니다. A의 `app` 패키지는 최초 설정·수집 동의 UI이며, 조립 변경은 함께 리뷰합니다. [연동 방법](../../../../../../../../docs/TEAM_INTEGRATION.md)

> **전부 Mock 기준입니다. 실제 A 서비스·B 정책과의 연동은 아직 검증하지 않았습니다.**

## 패키지 구조

| 위치 | 하는 일 | 주요 파일 |
|---|---|---|
| `ui/voice/` | 음성 안내와 결과 문장 | `TtsPopupAnnouncer`(계약 `PopupAnnouncer` 구현, 기기 TTS, 다시 듣기), `AnnouncementBatcher`(2초 묶음 "팝업 3개를 닫았어요"), `PressResultSpeech`(누르기 실패 안내), `AndroidTtsEngine`·`SpeechEngine`, `AnnouncementHistory`(메모리만), `SpeakableText`(팝업 원문 낭독용 기호·형식 변환: "02:00~04:00" → "오전 2시부터 오전 4시까지", `docs/c-notes/natural-reading.md`) |
| `choice/` | 선택 창 내용과 선택 결과 | `ChoicePromptPlanner`(무엇을 보여 줄지 → `UserChoice`), `ChoicePromptContent`(Compose 화면), `PressableLabelPolicy`(누르기 버튼을 보여 줄 라벨), `ButtonNameDisplay`(긴·간접 닫기 이름은 "'닫기' 버튼 눌러 주기"로, 원래 이름은 [내용 다시 읽기]에서), `MoveToButton`(버튼으로 이동, **꺼짐**), `VideoAdInfo`(B `DecisionVideoAd` 결과 → 영상 광고 요약·카운트다운 문장, 누르기는 그대로, **꺼짐**) |
| `overlay/` | 다른 앱 위에 선택 창 띄우기 | `ChoicePromptPresenter`(**A에게 제안하는 인터페이스**), `AccessibilityChoiceOverlay`(`TYPE_ACCESSIBILITY_OVERLAY` 구현), `PromptAnnouncementListener`·`PromptSpeech`(창이 뜰 때 0.7초 뒤 안내 문장, 그 전에 [말하기]로 듣기 시작하면 건너뜀), `PromptControls`([말하기]·[다시 듣기]·[그만 말하기]), `OverlayWindowKeeper`(창 세대·추가/제거 실패·정리 1회, JVM 시험), `ChoiceOverlayIdentity`·`OwnOverlayWindowTracker`(A가 C 창 이벤트를 구분하는 법 — 패키지명만으로는 안 됨, `docs/c-notes/overlay-identity-contract.md`) |
| `command/` | 음성 명령 | `OnDeviceVoiceCommandInput`(계약 `VoiceCommandInput` 구현, API 31+ 기기 안 인식만), `KoreanVoiceCommandMatcher`(문장 → 명령), `VoiceChoiceMapper`(명령 → 선택 창의 기존 버튼), `VoicePolicy`(바로 요청·탭 확정·거절 표, `VoiceUtterance`), `SpeakButtonAccess`(마이크 권한·[말하기] 보일지) |
| `settings/` | C 설정 | `UiVoiceSettings`·`UiVoicePreferences`(앱별 자동 닫기 기본 꺼짐, 안내 길이, 선택 창 음성, 음성 명령 켜기/끄기), `UiVoiceSettingsScreen`(허용 앱 이름, 마이크 상태, 기억 목록 해제·전체 삭제), `AppNames` |
| `integration/` | A가 부르기만 하면 되는 연결 부품 | `PopupChoiceFlow`(`PopupEvent` → 분석·분류(주입) → 선택 창 → `ChoiceHandler`(B/A) → 결과 안내), `ChoiceHandler`, `UiText` |

debug 전용(`app/src/debug/.../ui/voice/debug/`): `ChoicePreviewActivity`(가짜 팝업 미리보기, 누르기 결과 흉내, 말하기 시험), `ChoiceOverlayTestService`(C 시험용 접근성 서비스, 화면 내용 안 읽음, 아무것도 안 누름), `DebugRules`.

## 계약 대응 (`core/contract`, `core/model`)

| 계약 | C 구현·사용 | 비고 |
|---|---|---|
| `PopupAnnouncer` | `TtsPopupAnnouncer` | 실패도 지금은 `NOT_IMPLEMENTED` → `FAILED` 제안 (`docs/c-notes/contract-proposal-tts-2026-10-19.md`) |
| `AnnouncementState.TTS_COMPLETED` | 재생이 끝났다는 뜻만 | 사용자가 들었다는 뜻이 아님 |
| `DecisionContext.announcementAvailable` | `TtsPopupAnnouncer.announcementAvailable` (우리 TTS 준비됨) | TalkBack 상태는 별도 값 제안 |
| `VoiceCommandInput`·`VoiceEvent` | `OnDeviceVoiceCommandInput` | 못 알아들음도 지금은 `Cancelled` → `NotUnderstood` 제안 |
| `UserChoice`·`ChoiceKind` | `ChoicePromptPlanner.toUserChoice` / `select` | CLOSE→`CLOSE_ONCE`, SKIP→`SKIP_ONCE`, `buttonMeaningConfirmed=true`(버튼 이름을 보고 고름), 반복 허락은 항상 false |
| `ActionResult` | `PressResultSpeech.failureOf`, `AnnouncementBatcher.outcomeOf` | 성공만 "닫았어요". 나머지는 이유 + 다음 선택 |
| `PopupRuleRepository` | 설정 화면 목록·해제·전체 삭제. 목록은 규칙마다 켜짐/만료됨/꺼짐(실패)/해제됨과 만료 날짜를 한 줄로 보여 줌(`RememberedRuleList.kt`) | 기본·debug는 메모리 Mock. 실제 Room은 `RoomBackedRuleSettings.create(context)`로 묶어 A가 MainActivity에 붙임(열 때 `cleanUp`, 전체 삭제 `eraseAll`) ([사용 예](../../../../../../../../docs/c-notes/presenter-usage-for-a.md#4-설정-화면--기억-목록-f12-연결)) |
| `SnapshotSource`·`EvidenceExtractor`·`PopupClassifier` | `PopupChoiceFlow`가 주입받아 사용 | A/B 코드는 고치지 않음 |
| (계약 없음) 선택 창 띄우기 | `ChoicePromptPresenter` | A와 합의 필요 |
| (계약 없음) 버튼으로 이동 | `MoveToButtonRequest`, `ButtonFocusMover` | 기능 플래그 **꺼짐** |
| `ButtonLabelRules`·`LabelRejectReason` (#14, 허용 14개) | `SharedLabelPolicy`(`roleOf(label) == candidate.role`일 때만 누르기 버튼), `CannotPressReasonSpeech`(거부 이유 8종 → 문장, 기능 **꺼짐**) | C는 후보 라벨 한 줄만 받으므로 `judgeNode`가 아니라 `roleOf`. **A 게이트(`feature/a-action-gate`, main 미병합)는 아직 자체 8개 목록** → 광고 닫기·공지 닫기·닫기 버튼·close ad·skip ad·광고 건너뛰기는 C가 버튼을 보여도 A가 거부할 수 있음 |

## 지키는 안전 규칙 (요약)

- 선택 창에 누르기 버튼이 있어도 **실행 허가가 아니다.** B 정책과 A 게이트가 다시 확인한다.
- 누르기 버튼은 **공용 라벨 계약이 후보 역할과 같은 역할을 줄 때만** 보인다(허용 14개). X·×·라벨 없는 DISMISS·확인/OK·오늘 하루 닫기는 "이 팝업은 대신 닫을 수 없어요". 라벨 허용 ≠ 실행 허가(A 게이트가 최종).
- 중요 결정 팝업에는 누르기 버튼이 없다(음성으로도 못 누름).
- 실패한 누르기는 다시 누르지 않는다. 화면이 바뀌었으면(STALE) 예전 분석으로 다시 묻지 않는다.
- TalkBack 포커스는 사용자가 요청할 때만(버튼으로 이동) 옮긴다. 선택 창은 `FLAG_NOT_FOCUSABLE`.
- 화면 글자·인식 문장은 로그·파일에 남기지 않는다(`toString()`은 redacted). `announceForAccessibility()`는 쓰지 않는다.
- 음성 명령은 [말하기]를 누를 때 한 번만 듣는다. 원격 인식기 없음, INTERNET 권한 없음. `RECORD_AUDIO`는 debug 매니페스트에만 있다 → release에서는 [말하기]가 숨겨진다(A에게 main 매니페스트 추가 요청 예정). 선택 창 아래 [말하기]·[다시 듣기]·[그만 말하기]는 `overlay/PromptControls.kt`(`PromptControlsSupport.create`로 켬).

## 미검증 (실기기·연동)

| 항목 | 상태 |
|---|---|
| 실제 A 서비스에서 `PopupChoiceFlow`·오버레이 동작 | 미연동 (Mock 단위 시험만) |
| 오버레이가 뜰 때 TalkBack이 창 제목을 읽는지 | LG: 읽지 않음 → 자체 TTS 기본. S26 미확인 |
| 우리 TTS와 TalkBack 겹침 | LG 홈 화면 0.7/1.5초 모두 끝까지 들림. S26 미확인 |
| 기기 안 음성 인식 | LG 미지원([말하기] 숨겨짐 확인). S26 미확인 |
| 큰 글꼴(200%)에서 선택 창 스크롤·잘림 | LG 10/20: 글꼴 2.0배에서 시나리오 0·3·6 잘림 없음(화면 안에 들어가 스크롤은 안 생김). S26 미확인 |
| 오버레이 창의 상태 표시줄·내비게이션 바 여백 | 창이 화면 끝까지 차는 경우는 미확인 |
| 다크 모드 | 시스템 설정을 따름. LG 10/20: 선택 창·설정 화면 색 바뀌고 읽힘 |
| 버튼으로 이동 | 꺼짐. A 포커스 API·계약 값 필요 |
| [다시 듣기]·[그만 말하기]·상태 줄의 TalkBack 읽기 순서 | 코드 기준만, 실기기 TalkBack 미확인 |
| 앞으로 항상 닫기·다시 묻지 않기, 거부 이유 안내 | 플래그 꺼짐 (`RememberChoiceFeature`, `CannotPressReasonFeature`) |

상시 듣기와 F6 영상 줄거리 설명은 구현하지 않습니다.
