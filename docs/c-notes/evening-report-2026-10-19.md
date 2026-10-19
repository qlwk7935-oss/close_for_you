# 10/19 저녁 무인 작업 보고서 (C 재호)

> 지시서: `evening-plan-2026-10-19.md`. 진행 기록: `evening-log-2026-10-19.md`.
> **push·PR·팀 채팅 없음.** `feature/ui-voice`에는 커밋하지 않음. 모든 결과는 **Mock 기준, 실기기 미검증.**

## 1. 쉬운 요약

1. TTS 계약 시안(`FAILED`, `NotUnderstood`)과 설정 진입 버튼 시안을 **각각 별도 브랜치에 로컬 커밋 1개씩** 만들었어요.
2. "왜 대신 누르지 않나요" 문구 8종 + B 정책 이유 4종을 만들었고, **꺼진 플래그** 뒤에 두었어요. 결제라고 단정하지 않아요.
3. [앞으로 항상 닫기]·[다시 묻지 않기] 선택지를 **꺼진 플래그** 뒤에 추가했어요. 단, B 정책이 지금은 "항상 닫기"를 누르기로 보지 않아서 팀 결정이 필요해요.
4. C 창(선택 창)이 시스템 다크 모드를 따르게 했고, 대비는 라이트·다크 모두 4.5:1 이상(최저 5.26)이에요. 설정 화면은 앱 이름을 보여 주고, 못 읽으면 패키지명을 보여 줘요.
5. 시험 230개 → **254개, 모두 통과**. lint 경고는 예전부터 있던 7개 그대로. LG 폰은 연결 안 돼서 7단계는 건너뛰었어요.

## 2. 단계별 결과

| 단계 | 결과 | 내용 |
|---|---|---|
| 0 | 했음 | 시작 시 `feature/ui-voice`, 미추적은 지시서 1개 → 정상 |
| 1 TTS 계약 시안 | 했음 | `c/tts-contract` **12a2efc**. `AnnouncementState.FAILED`, `VoiceEvent.NotUnderstood` 추가(기존 값·이름 그대로). C 쪽: 재생 실패 → `FAILED`, 명령 없음·`ERROR_NO_MATCH` → `NotUnderstood`, 시간 초과 → `Cancelled`. 매핑을 `VoiceEventMapping` 순수 함수로 빼서 시험 5개. 이 브랜치 기준 시험 235개 통과. 문서 3절(`talkBackActive`)은 B 정책 영역이 걸려서 넣지 않음 |
| 2 설정 진입로 시안 | 했음 | `c/settings-entry` **0f20c62**. `MainActivity` 맨 아래에 [안내·음성 설정 열기] 버튼 하나. 새 Activity·매니페스트 변경 없이 같은 화면 안에서 전환, 시스템 뒤로/[기본 설정으로 돌아가기]로 복귀. 기억 목록은 `InMemoryMockPopupRuleRepository`(D 연결 전) |
| 3 라벨 거부 이유 문구 | 했음 (미커밋) | `choice/CannotPressReason.kt`: 임시 미러 `UiLabelRejectReason`(8종, `REPLACE-WITH-CONTRACT`), `CannotPressReasonFeature.enabled = false`, B 정책 이유 먼저 → 라벨 이유. 선택 창에는 **아직 연결 안 함**. 시험 9개 |
| 4 항상 닫기·다시 묻지 않기 | 했음 (미커밋) | `RememberChoiceFeature.enabled = false`. 항상 닫기는 누르기 선택지가 보일 때와 같은 버튼에만, 버튼 이름을 읽어 줌. 다시 묻지 않기는 USER_DECISION에서 숨김. 누르기 실패 뒤에는 항상 닫기도 빠짐(재시도 금지). 자동 닫기 꺼짐 안내 문구. 시험 9개 |
| 5 앱 공통 테마 준비 | 했음 (미커밋) | C 코드에 하드코딩 색은 원래 없었음. `UiVoiceTheme`(Material 기본 색 + 시스템 다크) 추가, 오버레이·디버그 미리보기·@Preview에 적용. 설정 화면은 A 테마를 따르도록 감싸지 않음. 대비 시험 2개. 기록 `theme-contrast-2026-10-19.md` |
| 6 설정 화면 앱 이름 | 했음 (미커밋) | `settings/AppNames.kt`: `AppLabelSource` 인터페이스, `PackageManagerAppLabelSource`(NameNotFound·SecurityException → 패키지명), 같은 이름이면 패키지명 덧붙임. 권한·`<queries>` 추가 없음. 시험 4개 |
| 7 LG 큰 글꼴 점검 | **건너뜀** | `adb devices`에 기기 없음. 폰 설정 바꾼 것 없음 |
| 8 정리 | 했음 | 전체 `assembleDebug`·`testDebugUnitTest`(254개, 실패 0)·`lintDebug`(경고 7: GradleDependency 6, OldTargetApi 1 — 기존) 통과. 병합 매니페스트: INTERNET 없음, 권한 변화 없음(`<queries>`는 원래 있던 TTS_SERVICE 하나) |

## 3. 바뀐 파일

### feature/ui-voice (미커밋)
- 수정: `ui/voice/choice/ChoicePromptPlanner.kt`(4단계), `ui/voice/choice/ChoicePromptContent.kt`(4·5), `ui/voice/overlay/AccessibilityChoiceOverlay.kt`(5), `ui/voice/settings/UiVoiceSettingsScreen.kt`(5·6), `debug/.../ChoicePreviewActivity.kt`(5), `res/values/ui_voice_strings.xml`(3·4, 문구 16개 추가)
- 새 파일: `ui/voice/choice/CannotPressReason.kt`, `ui/voice/UiVoiceTheme.kt`, `ui/voice/settings/AppNames.kt`
- 새 시험: `choice/CannotPressReasonTest.kt`, `choice/RememberChoiceTest.kt`, `UiVoiceThemeContrastTest.kt`, `settings/AppNamesTest.kt`
- 문서(미추적): `docs/c-notes/evening-plan-2026-10-19.md`, `evening-log-2026-10-19.md`, `theme-contrast-2026-10-19.md`, 이 보고서

### c/tts-contract (커밋 12a2efc, push 안 함)
- `core/contract/PopupContracts.kt`, `ui/voice/TtsPopupAnnouncer.kt`, `ui/voice/command/OnDeviceVoiceCommandInput.kt`, `debug/.../ChoicePreviewActivity.kt`, `test/.../TtsPopupAnnouncerTest.kt`, 새 `test/.../command/VoiceEventMappingTest.kt`

### c/settings-entry (커밋 0f20c62, push 안 함)
- `app/MainActivity.kt`, `res/values/ui_voice_strings.xml`(문구 2개)

## 4. 추천 커밋 순서·메시지 (feature/ui-voice)

서로 같은 파일을 조금씩 건드려서(`ui_voice_strings.xml`, `ChoicePromptContent.kt`) 단계별로 나누려면 `git add -p`가 필요해요. 쉬운 방법은 2묶음입니다.

1. `feat(ui-voice): 거부 이유 안내·항상 닫기·다시 묻지 않기 준비 (플래그 꺼짐, Mock 기준)`
   - `CannotPressReason.kt`, `ChoicePromptPlanner.kt`, `ChoicePromptContent.kt`, `ui_voice_strings.xml`, 시험 2개
2. `feat(ui-voice): 시스템 다크 모드 따르기, 설정 화면 앱 이름 표시`
   - `UiVoiceTheme.kt`, `AccessibilityChoiceOverlay.kt`, `ChoicePreviewActivity.kt`, `UiVoiceSettingsScreen.kt`, `AppNames.kt`, 시험 2개, `theme-contrast-2026-10-19.md`
   - 주의: `ChoicePromptContent.kt`의 미리보기 테마 한 줄도 5단계 것이라 1번에 같이 들어가요(문제 없음).
3. (선택) `docs(ui-voice): 10/19 저녁 작업 기록·보고서`

## 5. 사용자가 결정할 것

1. **[항상 닫기]를 눌렀을 때 이번 팝업도 닫을까?** B 정책은 지금 `REMEMBER_EXACT_POPUP`을 누르기 요청으로 보지 않고 DENY해요(`DecisionActionPolicy.kt` 85~91줄). 
   - (가) C가 `CLOSE_ONCE`를 먼저 보내고 성공하면 D에 규칙 저장 요청 — B 변경 없음 **(추천)**
   - (나) B가 `REMEMBER_EXACT_POPUP`도 이번 1회 누르기로 처리 — B 수정 필요
2. **c/tts-contract 시안을 팀 계약 PR로 낼지.** 추천: 우진·준영에게 문서(`contract-proposal-tts-2026-10-19.md`)와 이 브랜치를 보여 주고 OK 받으면 PR.
3. **c/settings-entry를 A(우진)에게 제안할지.** 추천: 버튼 한 개뿐이라 A 리뷰 받기 쉬움. A가 화면 이동 방식(같은 화면 전환 vs Navigation)을 정하게 하기.
4. **거부 이유 안내를 언제 켤지.** 추천: B의 `ButtonLabelRules` 계약 PR 병합 뒤, 미러 enum을 계약 것으로 바꾸고 켜기.
5. 문구 확인: DECISION 문구 "중요한 선택을 요구할 수 있는 버튼이라 대신 누르지 않아요." 괜찮은지.

## 6. 팀에 보낼 메시지 초안 (보낼지는 재호가 결정)

> 우진·준영께 (C 재호)
> 1) TTS 상태 계약 시안을 로컬 브랜치로 만들어 봤어요: `AnnouncementState.FAILED`, `VoiceEvent.NotUnderstood` 두 값 **추가만** 하고 기존 값은 그대로예요. A·B·D 코드는 영향 없음(시험 235개 통과). 괜찮으면 계약 PR로 올릴게요.
> 2) 우진: `MainActivity` 맨 아래에 "안내·음성 설정 열기" 버튼 하나만 넣는 시안이 있어요. 화면 구조·문구는 안 건드렸어요. 보실래요?
> 3) 준영: [앞으로 항상 닫기]를 고르면 지금 B 정책은 DENY라서, C가 `CLOSE_ONCE` 먼저 → 성공 시 규칙 저장 순서로 가려고 해요. 의견 주세요.
> 4) 라벨 거부 이유 8종 안내 문구 준비해 뒀어요(계약 PR 전이라 꺼 둠). DECISION_WORD는 "중요한 선택을 요구할 수 있는 버튼"으로만 말해요.

## 7. 사용량

| 시점 | 5시간 | 주간(모든 모델) |
|---|---|---|
| 시작 | 34% | 82% |
| 끝 (8단계 시작) | 42% | 83% |

유료 추가 사용량: 꺼짐(건드리지 않음).

## 사람이 확인할 것 (실기기)
- 선택 창 다크 모드: 창이 떠 있는 중에 다크 모드를 바꾸면 바로 바뀌는지.
- 설정 화면 앱 이름: 갤럭시 S26/LG에서 허용 앱 이름이 보이는지, 안 보이면 패키지명이 나오는지(패키지 가시성).
- `NotUnderstood`: 무관한 말 → "알아듣지 못했어요", 아무 말 안 함 → 조용히 끝나는지 (c/tts-contract 브랜치에서).

## 9. 10/20 추가: 항상 닫기 (가) 반영

> 5장 1번 결정 (가). B·A·D·core 코드는 고치지 않음. **Mock 기준, 실제 연동·실기기 미검증.** 커밋 안 함.

**동작:** [앞으로 항상 닫기](`REMEMBER_EXACT_POPUP`)를 누르면 `PopupChoiceFlow`가
1. B/A 쪽 `ChoiceHandler`에 **같은 token·candidateId로 1회 누르기**를 보냄 → `CLOSE_ONCE`. 단 건너뛰기 버튼이면 `SKIP_ONCE` (B 정책이 종류와 버튼 역할이 같은지 봐서, 건너뛰기 버튼에 `CLOSE_ONCE`를 보내면 DENY됨). `repeatAutomationExplicitlyAllowed`는 false로 보냄(계약상 1회 누르기에는 못 붙임).
2. 결과가 `MECHANICAL_SUCCESS`이고 token이 같을 때만 `RememberedPopupStore`에 1번 저장 요청(`REMEMBER_EXACT_POPUP`, 버튼 뜻 확인·반복 허락 true). 닫힘 자체는 기존대로 묶음 안내("팝업 1개를 닫았어요")가 말하고, 덧붙여 "이 팝업과 버튼을 기억해 뒀어요." + 자동 닫기 꺼짐/켜짐 문구(`ui_remember_auto_close_off/on`)를 바로 말함. 저장 실패면 "이 팝업을 기억하지 못했어요. 다음에도 물어볼게요."
3. 차단·실행 안 함·화면 바뀜·누르기 실패·확인 못 함 → 저장 0회, 기존 실패 안내(`ui_result_*`) 그대로, 다음 선택지에 누르기·항상 닫기 없음(재시도 없음). 핸들러는 탭 1번에 항상 1회 이하.
4. `RememberChoiceFeature.enabled`는 그대로 false. 저장소 기본값은 "연결 없음"(`RememberedPopupStore.NONE`, 저장 안 함), 자동 닫기 기본값은 꺼짐.

**바뀐 파일 (미커밋)**
- 새 파일: `ui/voice/integration/RememberedPopupStore.kt` — `RememberedPopupStore` 인터페이스, `RepositoryRememberedPopupStore`(계약의 `PopupFingerprintProvider`+`PopupRuleRepository`로 `PopupRule` 만듦. 지금은 `FixtureOnlyFingerprintProvider`+`InMemoryMockPopupRuleRepository`로만 시험)
- 수정: `ui/voice/integration/PopupChoiceFlow.kt`(위 동작, 생성자에 `rememberChoicesEnabled`·`rememberStore`·`autoCloseEnabledForApp` 추가 — 모두 기본값 있음), `ui/voice/choice/ChoicePromptPlanner.kt`(플래그 설명 주석만), `res/values/ui_voice_strings.xml`(`ui_remember_saved`, `ui_remember_not_saved`)
- 시험: `integration/PopupChoiceFlowTest.kt`에 10개 추가 (성공→CLOSE_ONCE 1회+저장 1회, 자동 닫기 켜짐 문구, 저장 실패 안내, 건너뛰기→SKIP_ONCE, 실패 5종 저장 0회·핸들러 1회·실패 문구 같음, 실패 뒤 그대로 두기도 저장 0회, 저장소 없음, Mock 저장소 끝까지, Mock 저장소+차단, 1회 누르기는 저장 안 함)

**결과:** `assembleDebug`·`testDebugUnitTest`(**264개, 실패 0**)·`lintDebug`(경고 7: 기존과 같음) 통과. 병합 매니페스트 INTERNET 없음, 권한 변화 없음.

**남은 점**
- 실제 A 연동에서 닫힘 직후 A의 `Invalidated`가 먼저 오면 `collectLatest`가 이 팝업 작업을 취소함 → 핸들러 결과를 못 받으면 저장 안 됨(안전 쪽). 결과를 받은 뒤 저장은 끝까지 하도록 `NonCancellable`로 감쌌지만, 뒤의 "기억해 뒀어요" 안내는 `announcer.stop()`에 끊길 수 있음. 실기기 확인 필요.
- 규칙 만료 시간(지금 없음)·앱 버전 번호를 어디서 받을지는 D·A와 정해야 함 [팀 확정 필요].
- 실제 지문 제공자(D) 연결 전에는 Mock 팝업이 아닌 화면은 저장이 실패함(안내는 "기억하지 못했어요").
- [다시 묻지 않기](`STOP_ASKING`) 저장은 이번 범위 밖 — 지금도 핸들러에 그대로 넘기기만 함.
- 준영(B)에게 (가) 방식과 "건너뛰기 버튼이면 SKIP_ONCE" 처리를 알리는 것이 좋음.
