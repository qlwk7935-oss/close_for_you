# 아침 보고서 — 2026-10-18 밤 작업 (C 재호)

> 밤 작업 세션이 04:20~04:46에 0~5단계를 모두 끝냈다. **커밋·push는 하지 않았다.** 아래 "추천 커밋 순서"대로 파일별로 직접 커밋하면 된다.
> 모든 단계 끝에서 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 통과 (마지막: 단위 시험 188개, 실패 0, lint 새 경고 0).
> 전부 **Mock 기준, 실제 A 서비스와 연동은 미검증**이다.

## 1. 한눈에 보기

| 단계 | 결과 | 실기기 (LG 벨벳, Android 12) |
|---|---|---|
| 0 🐞 SKIP 후보 → CLOSE_ONCE 버그 | ✅ 고침: SKIP → `SKIP_ONCE`, CLOSE → `CLOSE_ONCE` | ✅ 미리보기에서 두 버튼 각각 확인 |
| 1 오버레이 | ✅ 다른 앱 위 "닫을까요?" 창 + A에게 줄 인터페이스 | ✅ 홈 화면 위에 뜸, 누르면 닫힘 / 다시 읽기는 유지 |
| 2 안내 연결·묶음 안내 | ✅ 뜰 때 자체 TTS(설정), 내용 다시 읽기 낭독, 2초 묶음 | ⚠️ TTS 합성 요청만 로그로 확인, **들리는지는 미확인** |
| 3 설정 화면 | ✅ 앱별 자동 닫기(기본 꺼짐)·안내 길이·선택 창 음성·기억 목록 해제/전체 삭제 | ✅ 저장·해제·확인 창 동작 |
| 4 음성 명령 | ✅ 문장 → 명령 매칭, 기기 안 인식기 | ❌ LG는 기기 안 인식 **미지원** → S26에서 확인 필요 |
| 5 접근성 점검 | ✅ `a11y-check-2026-10-18.md` | 라벨 없는 버튼 0, 48dp 미만 0 |

## 2. 파일별 설명

### 0단계 — SKIP_ONCE 버그
| 파일 | 무엇을 | 왜 |
|---|---|---|
| `ui/voice/choice/ChoicePromptPlanner.kt` | `PromptOption.candidateRole` 추가, `toUserChoice`가 역할대로 `CLOSE_ONCE`/`SKIP_ONCE` | A 지적: 건너뛰기 버튼도 `CLOSE_ONCE`로 나갔음 (계약 위반) |
| `test/.../ChoicePromptPlannerTest.kt` | 회귀 시험 3개 (SKIP만 / CLOSE·SKIP 둘 다 / 역할 값) | 같은 버그 재발 방지 |
| `debug/.../ChoicePreviewActivity.kt`, `ui_voice_debug_strings.xml` | 가짜 팝업 "광고 (닫기·건너뛰기 버튼 둘 다)" 추가 | D의 `MockPopupFixtures`는 고치지 않고 debug 파일 안에서 복사해 만듦 |

### 1단계 — 오버레이 (`ui/voice/overlay/`)
| 파일 | 무엇을 |
|---|---|
| `ChoicePromptPresenter.kt` | **A에게 제안하는 인터페이스** `show(prompt, onChoice)` / `dismiss()` / `isShowing`. + `ChoiceOverlayIdentity` (C 창을 A가 알아보는 방법: 우리 패키지 이름 + `TYPE_ACCESSIBILITY_OVERLAY`) |
| `AccessibilityChoiceOverlay.kt` | 접근성 서비스 컨텍스트에서 `TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE`, 화면 아래. 창 제목은 고정 문장 "팝업 안내"(팝업 글자 아님). 누르기·그대로 두기는 창을 닫고, 내용 다시 읽기는 창 유지 |
| `OverlayLifecycleOwner.kt` | 서비스에서 띄운 Compose에 Lifecycle·SavedState 연결 (실험 앱과 같은 방식) |
| `debug/.../ChoiceOverlayTestService.kt`, `debug/res/xml/ui_voice_overlay_test_service.xml`, `debug/AndroidManifest.xml` | **C 시험용 접근성 서비스 (권한 6)**. `canRetrieveWindowContent=false`, 우리 앱 이벤트만, 아무것도 누르지 않음 |
| `ChoicePreviewActivity.kt` | [5초 뒤 다른 앱 위에 띄우기], adb 진입점 `--ei ui_debug_overlay_scenario N` |
| `test/.../overlay/ChoiceOverlayIdentityTest.kt` | 식별 함수 시험 |

### 2단계 — 안내 연결
| 파일 | 무엇을 |
|---|---|
| `ui/voice/AnnouncementBatcher.kt` | 2초 안 결과를 묶어 "팝업 3개를 닫았어요." 창은 **첫 결과 기준 고정** (계속 들어와도 2초 넘게 미루지 않음). `outcomeOf`: `BLOCKED/STALE/NOT_EXECUTED`는 세지 않음, `ATTEMPT_FAILED/OUTCOME_UNKNOWN`은 "닫혔는지 확인하지 못했어요" |
| `ui/voice/overlay/PromptAnnouncementListener.kt` | 창이 뜰 때: 창 제목만 / 창 제목 + 0.7초 뒤 자체 TTS 1회 (설정으로 선택). 닫히면 말하던 것 중지. `PromptSpeech`: 요약 문장, 내용 다시 읽기 문장 |
| `ui/voice/choice/ChoicePromptContent.kt` | 요약 문장 리소스 함수를 `internal`로 (TTS와 화면이 같은 문장) |
| `test/.../AnnouncementBatcherTest.kt`, `test/.../overlay/PromptSpeechTest.kt` | 시간 주입(TestScope) 시험 6개 + 2개 |

### 3단계 — 설정 화면 (`ui/voice/settings/`)
| 파일 | 무엇을 |
|---|---|
| `UiVoiceSettings.kt` | `UiVoiceSettings` (자동 닫기 앱 목록 기본 비어 있음 = 꺼짐, **A가 허용한 앱이어야만 켜짐으로 인정**), `UiVoicePreferences` (C 전용 SharedPreferences `ui_voice_settings`, 새 라이브러리 없음) |
| `UiVoiceSettingsScreen.kt` | 상태(A `CorePreferences` **읽기만**), 앱별 자동 닫기, 안내 길이, 선택 창 음성, 기억 목록 [해제]·[기억 전체 삭제](확인 창). 모든 행 48dp, 구역 제목 heading |
| `debug/.../DebugRules.kt` | 가짜 기억 규칙 2개 (메모리만, `InMemoryMockPopupRuleRepository`) |
| `test/.../settings/UiVoiceSettingsTest.kt` | 기본값·앱별·A 허용 조건·잘못 저장된 값 시험 |

### 4단계 — 음성 명령 (`ui/voice/command/`)
| 파일 | 무엇을 |
|---|---|
| `KoreanVoiceCommandMatcher.kt` | 닫아→CLOSE, 건너뛰어→SKIP, 다시 읽어줘→READ_CONTENT, 그대로 둬/닫지 마→KEEP_OPEN, 돌아가→GO_BACK, 버튼으로 이동→MOVE_TO_BUTTON, 무슨 그림이야→DESCRIBE_IMAGE. **16자 초과(배경 소리 가능성)·명령 두 개·부정형("건너뛰지 마")은 무시.** "동의/결제/확인/로그인"은 명령이 아님 |
| `VoiceChoiceMapper.kt` | 명령 → 선택 창의 **기존 버튼만** 고름. 같은 역할 버튼이 2개면 모호해서 안 고름. 결정 팝업은 누를 버튼이 없으니 음성으로도 못 누름. Back·포커스 이동·그림 설명은 C에서 실행 안 함 |
| `OnDeviceVoiceCommandInput.kt` | 계약 `VoiceCommandInput` 구현. API 31+ `createOnDeviceSpeechRecognizer`만, 원격 인식기 없음, [말하기] 1회 = 1회 듣기, 최상위 결과 1개만, 문장 로그 없음 |
| `debug/AndroidManifest.xml` | `RECORD_AUDIO` **debug 전용 (권한 7)**. 병합 매니페스트: RECORD_AUDIO 있음, **INTERNET 없음** |
| `ChoicePreviewActivity.kt` | [말하기] + 마이크 권한 요청, 결과는 명령 이름만 표시 |
| `test/.../command/*Test.kt` | 매칭 14개, 변환 6개 |

### 5단계 — 접근성 점검
- `docs/c-notes/a11y-check-2026-10-18.md` (표·KWCAG 점검표·사람이 확인할 것)

## 3. 추천 커밋 순서 (파일 묶음 + 메시지 초안)

> 각 커밋 전에 빌드: `$env:JAVA_HOME = "D:\Android\Android Studio\jbr"; .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
> 커밋 1~5는 앞 커밋에 기대므로 **순서대로** 해야 각 커밋이 빌드된다. (특히 `ChoicePreviewActivity.kt`, `ui_voice_debug_strings.xml`, `debug/AndroidManifest.xml`, `ui_voice_strings.xml`은 여러 단계 변경이 한 파일에 섞여 있어 단계별로 나누기 어렵다 → 아래처럼 **한 번에** 넣는 것을 추천)

1. **SKIP 버그만 먼저** (A에게 바로 알릴 수 있게)
   - `app/src/main/java/kr/closeforyou/ui/voice/choice/ChoicePromptPlanner.kt`
   - `app/src/test/java/kr/closeforyou/ui/voice/choice/ChoicePromptPlannerTest.kt`
   - 메시지: `fix(ui-voice): 건너뛰기 후보를 SKIP_ONCE로 변환`
   - 이 두 파일만으로 빌드된다 (다른 새 파일을 참조하지 않음).
2. **안내·오버레이·설정·음성 본체 + 시험** (main/test 새 파일 전부)
   - `app/src/main/java/kr/closeforyou/ui/voice/AnnouncementBatcher.kt`
   - `app/src/main/java/kr/closeforyou/ui/voice/overlay/` (4개)
   - `app/src/main/java/kr/closeforyou/ui/voice/settings/` (2개)
   - `app/src/main/java/kr/closeforyou/ui/voice/command/` (3개)
   - `app/src/main/java/kr/closeforyou/ui/voice/choice/ChoicePromptContent.kt`
   - `app/src/main/res/values/ui_voice_strings.xml`
   - `app/src/test/java/kr/closeforyou/ui/voice/AnnouncementBatcherTest.kt`, `.../overlay/`, `.../settings/`, `.../command/`
   - 메시지: `feat(ui-voice): 선택 창 오버레이, 묶음 안내, 설정 화면, 음성 명령 매칭 추가 (Mock 기준)`
3. **debug 시험 도구**
   - `app/src/debug/AndroidManifest.xml` (C 시험 서비스 + RECORD_AUDIO)
   - `app/src/debug/res/xml/ui_voice_overlay_test_service.xml`
   - `app/src/debug/res/values/ui_voice_debug_strings.xml`
   - `app/src/debug/java/kr/closeforyou/ui/voice/debug/` (`ChoicePreviewActivity.kt`, `ChoiceOverlayTestService.kt`, `DebugRules.kt`)
   - 메시지: `test(ui-voice): debug 미리보기에 오버레이·설정·말하기 시험 경로 추가`
4. **문서**
   - `docs/c-notes/a11y-check-2026-10-18.md`, `docs/c-notes/night-report-2026-10-18.md`, (원하면) `night-plan`·`night-log`
   - 메시지: `docs(ui-voice): 10/18 접근성 점검과 밤 작업 보고서`

## 4. 결정할 것 (사용자 / 팀)

### A에게 요청·제안
1. **C 선택 창 이벤트 제외 (설계 F11, 토큰 무효화 대비).** 선택 창 버튼 탭도 접근성 이벤트(사용자 조작)라서 A 서비스가 토큰을 무효화하거나 "새 팝업"으로 볼 수 있다. → `ChoiceOverlayIdentity.isOwnOverlayEvent(event.packageName, packageName)` / `isOwnOverlayWindow(...)`로 **C 창 이벤트는 무효화·새 팝업 판정에서 빼 달라**. (A 코드는 고치지 않았음)
2. **오버레이를 띄우는 통로 계약.** `ChoicePromptPresenter`를 `core/contract`에 넣을지, `ui/voice`에 두고 A가 직접 쓸지. 넣는다면 합의 후 별도 PR.
3. **`uiautomator dump` 주의**: 덤프하는 동안 다른 접근성 서비스가 잠깐 멈춘다 → A 서비스 시험에서도 덤프 직후 상태가 바뀔 수 있음.

### 공통 계약 제안 (합의 후 별도 PR)
4. `AnnouncementState`에 **실패 값**이 없다 → 지금은 재생 실패도 `NOT_IMPLEMENTED`로 보냄. `FAILED`(또는 `NOT_DELIVERED`) 추가 제안.
5. `ChoiceKind`에 **[버튼으로 이동]** 이 없다 → B가 "확인/OK"를 대신 못 누를 때 대안을 줄 수 없음. `MOVE_FOCUS_TO_BUTTON` 같은 값 또는 별도 요청 제안.
6. `VoiceEvent`에 **"못 알아들음"** 이 없다 → 지금은 `Cancelled`로 보냄. `NotUnderstood` 추가 제안.
7. `announcementAvailable` 기준 → 지금 C 구현은 "우리 TTS가 READY일 때 true". TalkBack 켜짐 여부를 넣을지 결정 필요.
8. `DecisionContext`에 앱별 자동 닫기 값 추가 (B 제안) → C는 `UiVoiceSettings.autoCloseAllowed(pkg, A허용목록)`을 준비해 둠. 찬성.

### 사용자(재호)가 정할 것
9. 선택 창이 뜰 때 **기본값을 "창 제목 + 자체 TTS"** 로 해 두었다 (TalkBack이 새 창을 안 읽을 위험이 더 크다고 봄). 실기기 청취 후 바꿀지.
10. 자체 TTS 지연 **0.7초**는 임시값 (실험 앱 0/700/1500 비교 결과 아직 없음).
11. **안내 길이(짧게/자세히)** 는 저장만 하고 문장에 아직 반영 안 함. "자세히"에 무엇을 더할지(앱 이름? 분류?) 정해야 함.
12. "닫기 버튼으로 이동"처럼 **명령 두 개가 섞인 문장은 무시**하게 했다 (안전 우선). 불편하면 규칙 조정.
13. **로그인 대신 누르기를 음성만으로 할지** [팀 확정 필요] — 이번엔 음성 매칭에 로그인·동의를 아예 넣지 않음.
14. LG에서 시험 서비스를 켜려고 `settings put secure enabled_accessibility_services`(TalkBack 켜고 끄기와 같은 설정)를 썼고 끝나고 **원래대로(없음) 되돌렸다**. 마이크 권한도 `pm grant` 후 `pm revoke`로 되돌림. 이런 시험을 앞으로도 허용할지.

## 5. 실기기에서 사람이 확인할 것
1. 오버레이가 **새로 뜰 때** TalkBack이 "팝업 안내"를 읽는지 / 우리 TTS와 겹치는지 (미리보기 설정 → "선택 창이 뜰 때 음성으로도 알리기" 켜고·끄고 비교)
2. 오버레이 위 쓸어넘기기 순서, 닫힌 뒤 TalkBack 포커스 위치, 키보드 열린 상태(메모 앱)에서 입력 포커스 유지
3. [내용 다시 읽기]가 실제로 들리는지 (가짜 본문)
4. 설정 화면: 제목 단위 이동, 스위치 켜짐/꺼짐 읽기, [해제] 버튼 설명
5. **갤럭시 S26**: 기기 안 음성 인식 가능 여부(LG는 미지원), 명령 인식률
6. 큰 글꼴에서 잘림, 명도 대비 측정

### LG에서 시험하는 법 (adb)
```bash
D:/Android/SDK/platform-tools/adb.exe -d shell settings put secure enabled_accessibility_services kr.closeforyou/kr.closeforyou.ui.voice.debug.ChoiceOverlayTestService
```
```bash
D:/Android/SDK/platform-tools/adb.exe -d shell am start -n kr.closeforyou/.ui.voice.debug.ChoicePreviewActivity --ei ui_debug_overlay_scenario 6
```
(5초 안에 홈으로 나가면 다른 앱 위에 선택 창이 뜬다. 6 = 닫기·건너뛰기 둘 다. 끝나면 설정 → 접근성에서 끄거나 `settings delete secure enabled_accessibility_services` — TalkBack을 같이 쓰면 TalkBack 항목도 함께 들어가야 하니 설정 화면에서 켜는 게 편하다)

## 6. 남은 문제
- 실제 A 서비스·B 정책과 연결 안 됨 (Mock). `PopupRuleRepository`도 D의 Room 전이라 메모리 Mock.
- `AnnouncementBatcher`는 만들었지만 아직 호출하는 곳이 없다 (A의 실행 결과가 들어와야 함).
- `ui/voice/README.md`는 새 패키지(overlay·settings·command) 설명을 아직 안 넣음.
- 오버레이 TalkBack 청취·음성 인식 실기기 확인 안 됨 (위 5장).

## 7. 사용량
| 시점 | 5시간 | 주간 |
|---|---|---|
| 밤 작업 시작 (04:20) | 67% | 38% |
| 밤 작업 끝 (04:46) | 약 84% | 40% |

05:35 예약 세션은 이 보고서가 있으면 할 일이 없다 (지시서 0~6단계 완료).

## 8. 팀 공유 메시지 초안 (A의 10/18 공유에 대한 C 답변 — 재호가 직접 보냄)

> 우진님 공유 고마워요! C 진행 상황이랑 의견 정리해서 남겨요. (전부 Mock 기준이고, 실제 서비스 연동은 아직 안 했어요)
>
> **1. SKIP_ONCE 버그 고쳤어요.** 말씀해 주신 대로 건너뛰기 후보도 `CLOSE_ONCE`로 나가고 있었어요. 이제 후보 역할이 SKIP이면 `SKIP_ONCE`, CLOSE면 `CLOSE_ONCE`로 나가고, 둘 다 있는 팝업도 각각 맞게 나가는 회귀 시험 넣었어요. LG 실기기 미리보기에서도 확인했어요.
>
> **2. 구현·시험 상태**
> - 완료(단위 시험 + LG 확인): 다른 앱 위 "닫을까요?" 선택 창(`TYPE_ACCESSIBILITY_OVERLAY`, 포커스 안 뺏음), 설정 화면(앱별 자동 닫기 기본 꺼짐, 기억 목록 해제/전체 삭제), 음성 명령 문장 매칭
> - 완료(단위 시험만): 2초 묶음 안내("팝업 3개를 닫았어요"), 선택 창 뜰 때 자체 TTS
> - 미검증: 오버레이가 뜰 때 TalkBack이 실제로 읽는지·TTS와 겹치는지, 음성 인식(LG는 기기 안 인식 미지원이라 S26에서 확인 필요)
> - LG TalkBack 시험(10/17): 미리보기 선택 창 6개 항목 통과 (안내 문장, 버튼 순서, 결정 팝업엔 닫기·동의 버튼 없음, 라벨 없는 버튼은 "대신 닫을 수 없어요")
>
> **3. 토큰 무효화 관련 제안** — 선택 창 버튼을 누르는 것도 접근성 이벤트라서, A 서비스가 이걸 "사용자 조작"으로 보고 토큰을 무효화하거나 새 팝업으로 볼 수 있을 것 같아요. C 창은 우리 앱 패키지 + `TYPE_ACCESSIBILITY_OVERLAY`라서 `ChoiceOverlayIdentity`로 구분할 수 있게 해 뒀어요. 설계 F11처럼 **C 자체 안내 창 이벤트는 무효화·새 팝업 판정에서 빼 주실 수 있을까요?**
>
> **4. 계약 제안 (합의되면 별도 PR)**
> - 오버레이 띄우는 통로: `ChoicePromptPresenter.show(prompt, onChoice)` / `dismiss()` — A가 호출만 하면 되게 만들어 뒀어요. 계약에 넣을지 같이 정해요.
> - `AnnouncementState`에 실패 값(예: `FAILED`)이 없어서 지금은 실패도 `NOT_IMPLEMENTED`로 보내요.
> - `ChoiceKind`에 "버튼으로 이동"이 없어서, B가 "확인" 같은 버튼을 대신 못 누를 때 대안을 못 줘요.
> - `announcementAvailable` 기준: 지금은 "우리 TTS 준비됨"이면 true예요. TalkBack 상태도 넣을지 의견 주세요.
> - `VoiceEvent`에 "못 알아들음"이 없어서 지금은 `Cancelled`로 보내요.
>
> **5. "TTS_COMPLETED ≠ 사용자가 들음" 동의해요.** C도 재생이 끝났다는 뜻으로만 쓰고, 사용자 확인으로 쓰지 않을게요.
>
> 참고로 `uiautomator dump` 하는 동안엔 다른 접근성 서비스가 잠깐 멈춰요. 서비스 시험할 때 덤프 직후 상태가 달라질 수 있어요.
