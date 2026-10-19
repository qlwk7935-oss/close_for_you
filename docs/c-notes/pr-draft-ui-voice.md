# PR 설명 초안 — `feature/ui-voice` → `main` (C 재호)

> 초안이다. PR을 만들지·언제 만들지는 사용자(재호)가 정한다. 10/19 낮 작업까지 반영.

## 제목 (안)
`feat(ui-voice): C 선택 창·안내·음성 명령·설정 화면 (Mock 기준, 실제 연동 미검증)`

## 요약
'닫아줄게' C 영역(UI·TalkBack·음성)의 첫 통합 PR입니다. **전부 Mock(`testing/mock`) 기준이며 실제 A 서비스·B 정책과의 연동은 검증하지 않았습니다.**

- F10: 기기 TTS 안내·다시 듣기(`TtsPopupAnnouncer`), 2초 묶음 결과 안내, 누르기 실패 안내
- 사용자 선택 UI: "닫을까요?" 선택 창(같은 화면·다른 앱 위 오버레이), A 게이트가 받는 라벨만 누르기 버튼으로 표시
- F13: [말하기]를 누를 때만 듣는 기기 안 음성 명령, 문장 → 명령 → 선택 창의 기존 버튼
- 설정: 앱별 자동 닫기(기본 꺼짐), 안내 길이(짧게/자세히), 선택 창 음성, 기억 목록 해제·전체 삭제
- 연동 부품: `PopupChoiceFlow` (A가 이벤트 흐름과 실행 경로만 넘기면 됨)

## 변경 파일

| 구분 | 파일 |
|---|---|
| C 본체 | `app/src/main/java/kr/closeforyou/ui/voice/**` (choice·overlay·command·settings·integration, README) |
| C 문장 | `app/src/main/res/values/ui_voice_strings.xml` |
| C 시험 | `app/src/test/java/kr/closeforyou/ui/voice/**`, `app/src/test/resources/kr/closeforyou/ui/voice/choice/label_test_vectors.csv`(B 초안 v1 시험 벡터 175개 복사본) |
| debug 전용 | `app/src/debug/java/kr/closeforyou/ui/voice/debug/**`, `app/src/debug/res/values/ui_voice_debug_strings.xml`, `app/src/debug/res/xml/ui_voice_overlay_test_service.xml`, **`app/src/debug/AndroidManifest.xml`** |
| 실험 앱 | `experiments/overlay-talkback/**` (팀 앱·CI와 별개 Gradle 프로젝트) |
| 문서 | `docs/c-notes/**`, `docs/experiments/overlay-talkback.md`, `CLAUDE.md`(C 작업 규칙) |

## 구현 내용
1. **선택 창 판단** (`ChoicePromptPlanner`): 중요 결정 팝업은 누르기 버튼 없음. 누르기 버튼은 역할 CLOSE/SKIP + 근거 E1/E2 + 라벨 있음 + **`PressableLabelPolicy.roleOf(label) == candidate.role`**일 때만. 지금 정책은 A 게이트(PR #10) 8개 라벨의 C 임시 복사본(`AGateLabelPolicy`), 공용 계약 `ButtonLabelRules.roleOf`가 생기면 교체.
2. **선택 결과**: CLOSE→`CLOSE_ONCE`, SKIP→`SKIP_ONCE`, 다시 읽기→`READ_CONTENT`(창 유지), 그대로 두기→`KEEP_OPEN`. 반복 허락은 항상 false.
3. **오버레이** (`AccessibilityChoiceOverlay`): `TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE`, 화면 아래, 창 제목 고정 문장, 큰 글꼴에서 스크롤. A에게 `ChoicePromptPresenter` 인터페이스 제안.
4. **안내**: 창이 뜰 때 자체 TTS(기본, 0.7초 뒤), 안내 길이 "자세히" = 버튼 이름·선택지 개수만 추가. 실패한 누르기는 이유 + 다음 선택("닫았어요"라고 말하지 않음, 다시 누르기 권하지 않음).
5. **음성 명령**: 16자 초과·명령 두 개·부정형 무시. 동의·결제·확인·로그인은 명령 아님. 같은 역할 버튼이 둘이면 고르지 않음.
6. **[버튼으로 이동]**: 기능 플래그 **꺼짐**. 계약 값·A 포커스 API가 생기면 켬.
7. **연동 부품** (`PopupChoiceFlow`): `Candidate` → 분석·분류(주입) → 선택 창 → `ChoiceHandler`(B 정책 + A 게이트) → 결과 안내. `Blocked`/`Invalidated`/새 후보면 창 닫고 말하던 것 중지, 토큰을 매 단계 다시 확인.

## 시험 결과
- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 통과 (Android Studio 내장 JDK)
- 단위 시험 **228개, 실패 0** (10/19 기준, 앱 전체). lint 경고 7개는 모두 기존 `build.gradle.kts` 버전 알림, C 새 경고 0
- 라벨 정책: B 시험 벡터 175개 전부 A 현재 게이트(`a_current`)와 일치, 거부 범주(R1~R9) 오허용 0
- 실기기 (LG 벨벳, Android 12, TalkBack 17): 미리보기 선택 창 TalkBack 6항목, 다른 앱 위 오버레이 표시·누르기·다시 읽기, 우리 TTS가 TalkBack과 겹치지 않고 들림 (10/17~18, `docs/experiments/overlay-talkback.md` 4-4·4-5)
- **미검증**: 실제 A 서비스 연동, 갤럭시 S26(TalkBack·기기 안 음성 인식), 큰 글꼴 실기기, 10/19 변경분(라벨 정책·실패 안내·안내 길이·스크롤)의 실기기 확인

## 남은 문제
- 공용 라벨 판정 계약 병합 후 `AGateLabelPolicy` → `ButtonLabelRules.roleOf` 교체
- 계약 제안(C): `AnnouncementState.FAILED`, `VoiceEvent.NotUnderstood`, `announcementAvailable` 정의 + `talkBackActive` (`docs/c-notes/contract-proposal-tts-2026-10-19.md`)
- A와 합의: `ChoicePromptPresenter`·`ButtonFocusMover` 위치, C 창 이벤트를 토큰 무효화·새 팝업 판정에서 제외
- `PopupRuleRepository`는 D의 Room 전이라 메모리 Mock
- 다크 모드 미대응(C 화면은 항상 밝은 색)

## 공통 계약 변경 여부
**없음.** `core/model`, `core/contract`는 고치지 않았습니다. 필요한 변경은 제안 문서로만 냈습니다.

## C 영역 밖 변경
- `app/src/debug/AndroidManifest.xml` (debug 빌드 전용): C 시험용 접근성 서비스 `ChoiceOverlayTestService`(`canRetrieveWindowContent=false`), 미리보기 화면, **`RECORD_AUDIO`**(말하기 시험).
- 병합 매니페스트(debug): `RECORD_AUDIO` 있음, **`INTERNET` 없음**. release 매니페스트 변경 없음. (10/18 확인. 10/19에는 매니페스트를 바꾸지 않음)
- 새 라이브러리 없음.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
