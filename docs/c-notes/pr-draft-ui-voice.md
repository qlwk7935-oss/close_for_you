# PR 설명 초안 — `feature/ui-voice` → `main` (C 재호)

> 초안이다. PR을 만들지·언제 만들지는 사용자(재호)가 정한다. **10/20 오후 작업까지 반영**(54da79e + 오후 미커밋 변경).
> GitHub 웹에서: 아래 "제목" 한 줄을 PR 제목 칸에, "본문" 아래 전체를 본문 칸에 붙인다.

## 제목
```
feat(ui-voice): C 선택 창·TalkBack 안내·음성 명령·설정 화면 (Mock 기준, 실제 연동 미검증)
```

## 본문

'닫아줄게' C 영역(UI·TalkBack·음성)의 첫 통합 PR입니다. **전부 Mock(`testing/mock`) 기준이며, 실제 A 서비스·B 정책·D 저장소와의 연동은 검증하지 않았습니다.** 리뷰어: D(동준) 부탁드립니다.

### 기능
- **F10 안내**: 기기 TTS 안내·다시 듣기(`TtsPopupAnnouncer`), 2초 묶음 결과 안내, 누르기 실패 안내(“닫았어요”라고 말하지 않음, 다시 누르기 권하지 않음)
- **선택 창 "닫을까요?"**: 같은 화면 미리보기 + 다른 앱 위 오버레이(`TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE`). A 게이트가 받는 라벨만 누르기 버튼으로 보여 줌. 중요 결정 팝업은 누르기 버튼 없음
- 창이 뜨면 0.7초 뒤 자체 TTS로 요약 안내. 그 전에 [말하기]로 듣기를 시작하면 첫 안내는 건너뜀(마이크 보호)
- 안내 길이 짧게/자세히(자세히 = 버튼 이름·선택지 개수만 추가)
- **F13 음성 명령**: [말하기]를 누를 때만 듣는 기기 안 인식(API 31+). 알아들은 말은 탭과 **같은 길**로 처리(B 정책 + A 실행 관문 그대로). 기기가 지원하지 않거나 권한이 없으면 [말하기]는 숨김
- 음성 정책: 닫기·건너뛰기·읽기·그대로 두기만 바로. "항상 닫아"·"다시 묻지 마"는 화면에서 직접 고르게, 결제·구매·구독·삭제·권한·동의·로그인은 "대신 누를 수 없어요"
- 선택 창에 [다시 듣기]·[그만 말하기]
- **설정 화면**: 앱별 자동 닫기(기본 꺼짐, 허용 앱 이름 표시), 안내 길이, 선택 창 음성, 음성 명령 켜기/끄기·마이크 상태, 기억 목록 해제·전체 삭제
- 시스템 다크 모드 따르기(대비 시험 포함)
- 연동 부품 `PopupChoiceFlow`: A가 이벤트 흐름과 실행 경로만 넘기면 선택 창 → 선택 → 결과 안내까지 이어짐

### 꺼 둔 기능 (플래그 false, 이 PR에서 켜지 않음)
| 플래그 | 켜는 조건 |
|---|---|
| `RememberChoiceFeature` (앞으로 항상 닫기·다시 묻지 않기) | D 규칙 저장소 연결 후 (나중 자동 닫기는 매번 B 정책·A 관문·동일 팝업 재검사) |
| `CannotPressReasonFeature` (대신 못 누르는 이유 안내) | 공용 라벨 계약(`LabelRejectReason`) 병합·연결 후. 꺼져 있으면 "이 팝업은 대신 닫을 수 없어요" |
| `MoveToButtonFeature` ([버튼으로 이동]) | 계약 값·A 포커스 API 생긴 뒤 |

`VoiceButtonFeature`([말하기])는 켜져 있지만, release에는 `RECORD_AUDIO` 선언이 없어서 release에서는 숨겨집니다.

### 바뀐 파일
| 구분 | 파일 |
|---|---|
| C 본체 | `app/src/main/java/kr/closeforyou/ui/voice/**` (choice·overlay·command·settings·integration, README) |
| C 문장 | `app/src/main/res/values/ui_voice_strings.xml` |
| C 시험 | `app/src/test/java/kr/closeforyou/ui/voice/**`, `app/src/test/resources/kr/closeforyou/ui/voice/choice/label_test_vectors.csv`(B 초안 v1 벡터 175개 복사본) |
| debug 전용 | `app/src/debug/java/kr/closeforyou/ui/voice/debug/**`, `app/src/debug/res/**`(ui_voice_*), **`app/src/debug/AndroidManifest.xml`** |
| 실험 앱 | `experiments/overlay-talkback/**` (팀 앱·CI와 별개) |
| 문서 | `docs/c-notes/**`, `docs/experiments/overlay-talkback.md`, `CLAUDE.md` |

### 권한·매니페스트
- main `AndroidManifest.xml`: **변경 없음.** `INTERNET` 없음. 새 라이브러리 없음.
- debug 매니페스트만: 시험용 접근성 서비스 `ChoiceOverlayTestService`(`canRetrieveWindowContent=false`), 미리보기 화면, **`RECORD_AUDIO`**(말하기 시험용).

### 시험
- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 통과 (Android Studio 내장 JDK)
- 단위 시험 **296개, 실패 0** (앱 전체, 10/20 오후). lint 경고 7개는 기존 `build.gradle.kts` 버전 알림, C 새 경고 0
- 라벨 정책: B 시험 벡터 175개 전부 A 현재 게이트와 일치, 거부 범주 오허용 0

### 실기기 (LG 벨벳, Android 12, TalkBack 17) — 본 것 / 못 본 것
- **본 것**
  - 10/17~18 (TalkBack 켬): 미리보기 선택 창 TalkBack 읽기, 다른 앱 위 오버레이 표시·누르기·다시 읽기, 우리 TTS가 TalkBack과 겹치지 않고 들림 (`docs/experiments/overlay-talkback.md`)
  - 10/20 (TalkBack 끔, 화면만): 오버레이 시나리오 0·3·6이 기본 글꼴·글꼴 2.0배에서 잘리지 않음, 다크 모드에서 색 바뀌고 읽힘, [말하기] 숨김(LG는 기기 안 인식 미지원), 설정 화면 음성 구역 "지원하지 않아요" 문장
- **못 본 것**: 실제 A 서비스 연동, [말하기]로 실제 인식(LG 미지원 → 갤럭시 S26 필요), 10/19 이후 버튼([다시 듣기]·[그만 말하기]·상태 줄)의 TalkBack 읽기 순서, 허용 앱 이름이 실제로 보이는지

### A·B·D에게 필요한 것
- **A**: 실제 서비스에서 `AccessibilityChoiceOverlay`를 만들 때 **세 번째 인자 `PromptControlsSupport.create(this, announcer)`**를 넘겨야 [말하기]·[다시 듣기]·[그만 말하기]가 보임(안 넘기면 예전 선택 창). 음성 명령을 release에서 쓰려면 main 매니페스트에 `RECORD_AUDIO` 추가 필요(팀 결정). `ChoicePromptPresenter`·`ButtonFocusMover` 위치, C 창 이벤트를 새 팝업 판정에서 빼는 것 합의 필요 (`docs/c-notes/presenter-usage-for-a.md`)
- **B/A**: 공용 라벨 판정 계약 병합 후 C 임시 복사본 `AGateLabelPolicy` → `ButtonLabelRules.roleOf`로 교체
- **D**: `PopupRuleRepository`가 아직 메모리 Mock. Room 저장소가 들어오면 기억 목록·"항상 닫기"를 연결
- 계약 제안(C): `AnnouncementState.FAILED`, `VoiceEvent.NotUnderstood`, `announcementAvailable` 정의 (`docs/c-notes/contract-proposal-tts-2026-10-19.md`)

### 공통 계약 변경
**없음.** `core/model`, `core/contract`, A·B·D 코드는 고치지 않았습니다.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
