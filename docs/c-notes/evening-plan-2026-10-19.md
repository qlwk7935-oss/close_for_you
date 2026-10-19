# 10/19 저녁 무인 작업 지시서 (C 재호)

> 사용자가 공부하는 동안 혼자 진행한다. 중간에 사용자에게 묻지 않는다. 막히면 그 단계를 건너뛰고 보고서에 적는다.
> 규칙: `CLAUDE.md`, `AGENTS.md`. 답·기록·보고서는 **한국어**.

## 0. 멈춤 조건 (가장 중요)

- 각 단계를 시작하기 전에 `mcp__ccd_session_mgmt__get_usage`로 **5시간 한도**(`5-hour limit`)를 확인한다.
  - 75% 이상이면 새 단계를 시작하지 않고 8단계(정리·보고서)로 간다.
  - 80% 이상이면 즉시 멈추고 지금까지의 내용만 보고서에 적는다.
  - 5시간 한도가 중간에 리셋되면(퍼센트가 내려가면) 그 값 기준으로 계속한다.
- 유료 추가 사용량(extra usage)은 **절대 켜지 않는다.**
- 주간 한도는 사용자가 신경 쓰지 말라고 했다. 보고서에 숫자만 적는다.

## 1. 공통 금지

- **push 금지. PR 금지. 팀 채팅 금지.**
- `feature/ui-voice`에는 **커밋하지 않는다** (사용자가 확인 후 직접 결정). 단, 1·2단계의 `c/*` 별도 브랜치에는 로컬 커밋 허용.
- A·B·D 코드, `core/**`, `app/src/main/AndroidManifest.xml`, `MainActivity`는 `feature/ui-voice`에서 고치지 않는다 (1·2단계 별도 브랜치만 예외).
- 새 라이브러리·권한 추가 금지. INTERNET 금지.
- 화면 글자·라벨 원문을 로그에 남기지 않는다.
- PowerShell `Get-Content`/`Set-Content`로 파일을 고치지 않는다 (한국어 깨짐). 편집 도구 사용.
- 빌드: `$env:JAVA_HOME = "D:\Android\Android Studio\jbr"; .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
- 진행 기록: `docs/c-notes/evening-log-2026-10-19.md` (단계마다 시작·끝·결과 한두 줄)

## 2. 배경 (10/19 팀 결정)

- 공용 라벨 계약 v3: `core/model/ButtonLabelRules` (B 준영이 계약 PR 예정, 아직 없음). 허용 14개. 거부 이유 8종 `LabelRejectReason`:
  `NO_LABEL, SYMBOL_ONLY, DECISION_WORD, CONDITIONAL_WORD, NAVIGATION_WORD, AMBIGUOUS_WORD, NOT_IN_LIST, LABEL_CONFLICT`. C에는 원문 없이 enum만 온다.
  원본 문서: `C:\Users\jaeho\Downloads\닫아줄게_공용라벨판정_계약초안_v3.md` (4-1절에 안내 예시) — 읽기만.
- A(우진) 조건:
  - `DECISION_WORD`를 "결제 버튼"이라고 **단정하지 않는다.** "중요한 선택을 요구할 수 있는 버튼" 수준.
  - **B 정책 차단 이유 / 라벨 거부 이유 / A 게이트 실행 결과**는 서로 구분해서 안내한다. 기존 `ui_result_*`는 실행 결과용.
  - 라벨 허용 ≠ 실행 가능. 허용이라고 "누를 수 있어요"라고 말하지 않는다.
  - 다크 모드: 앱 전체가 시스템 테마를 따르고 통합 때 함께 맞춘다 → C 전용 고정 색 만들지 않기.
  - 앱 이름: 모든 앱 조회 권한(QUERY_ALL_PACKAGES, `<queries>` 추가) 금지. 허용된 앱만 조회해 보고, 안 되면 패키지명 표시.
  - [버튼으로 이동]은 계속 꺼 둔다.
- 계약 PR이 병합되기 전까지 `PressableLabelPolicy`의 A 게이트 8개 임시 목록은 그대로 둔다.

## 3. 단계

시작 전: `git status`가 깨끗하고(이 지시서 파일 하나만 미추적이면 정상) `feature/ui-voice`인지 확인. 아니면 멈추고 보고. 지시서·기록·보고서 파일은 `c/*` 브랜치 커밋에 넣지 않는다.

### 1단계 — TTS 상태 계약 시안 (별도 브랜치 `c/tts-contract`)

- `git switch -c c/tts-contract` (feature/ui-voice에서 분기).
- `docs/c-notes/contract-proposal-tts-2026-10-19.md` 내용대로 `core/contract/PopupContracts.kt`에 최소 변경 시안:
  `AnnouncementState.FAILED`, (문서에 있으면) 음성 인식 실패 이벤트 등. **기존 값·이름은 바꾸지 않고 추가만.**
- C 코드가 새 값을 쓰도록 바꾸는 부분과 시험 추가. 빌드·시험 통과 확인.
- 로컬 커밋 1개 (메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`). **push 금지.**
- `git switch feature/ui-voice`로 돌아온다. 빌드가 안 되면 커밋하지 말고 `git stash`도 하지 말고, 변경을 버리고(`git restore`/`git checkout -- .` 후 브랜치 이동) 보고서에 이유를 적는다.

### 2단계 — 설정 진입로 시안 (별도 브랜치 `c/settings-entry`)

- feature/ui-voice에서 `git switch -c c/settings-entry`.
- A의 `MainActivity`(기본 설정 화면)에 C 설정 화면(`UiVoiceSettings*`)으로 가는 진입 버튼 하나를 넣는 최소 시안. A 화면 구조·문구는 바꾸지 않고 진입점만 추가. 문구는 `ui_voice_strings.xml`.
- 빌드·시험 통과 → 로컬 커밋 1개, push 금지 → `feature/ui-voice`로 복귀.

### 3단계 — 라벨 거부 이유 안내 문구 (feature/ui-voice, 커밋 안 함)

- C 패키지에 **임시 미러 enum**(예: `UiLabelRejectReason`, 주석 `REPLACE-WITH-CONTRACT: core/model/ButtonLabelRules.LabelRejectReason`)과 문구 매핑을 만든다. 8종 모두 문구. `DECISION_WORD`는 단정 금지 표현.
- 세 가지 이유의 우선순위: B 정책 차단 이유가 있으면 그것 먼저, 그다음 라벨 거부 이유. 실행 결과(`ui_result_*`)와 섞지 않는다.
- 기능 플래그로 꺼 둔다 (선택 창에 아직 연결하지 않거나, 연결하되 플래그 false).
- 문구는 `ui_voice_strings.xml`, `ui_` 접두사. 시험: 8종 매핑 빠짐없음, DECISION 문구에 "결제" 단정 단어 없음, 원문 라벨이 문장에 안 들어감.

### 4단계 — [앞으로 항상 닫기]·[다시 묻지 않기] (플래그 꺼짐, Mock)

- `ChoicePromptPlanner`에 두 선택지를 플래그(기본 false) 뒤에 추가.
  - 항상 닫기 = `REMEMBER_EXACT_POPUP` + `buttonMeaningConfirmed=true` + `repeatAutomationExplicitlyAllowed=true`. 누르기 선택지가 보이는 조건(CLOSE/SKIP 역할, 근거 E1/E2, 라벨 통과, USER_DECISION 아님)과 같은 경우에만.
  - 다시 묻지 않기 = `STOP_ASKING`. USER_DECISION에서는 보이지 않게.
  - 앱별 자동 닫기 설정이 꺼져 있으면 "항상 닫기"를 고르더라도 "이 앱 자동 닫기가 꺼져 있어서 다음에도 물어봐요" 류 안내 (문구만, 저장은 D 담당이라 Mock).
- 시험 추가.

### 5단계 — 앱 공통 테마를 따를 준비

- C 화면(선택 창 `ChoicePromptContent`, 설정 화면)에 하드코딩 색이 있으면 `MaterialTheme.colorScheme` 기반으로 바꾼다. A의 테마 파일이 있으면 그걸 쓰고, 없으면 시스템 다크 여부만 따르는 최소 처리. A 파일은 고치지 않는다.
- 오버레이(ComposeView)도 시스템 다크 모드를 따르는지 확인. 대비 4.5:1 이상 유지되는지 문서에 적는다.

### 6단계 — 설정 화면 앱 이름

- 허용 앱 목록에서 `PackageManager.getApplicationInfo` + `getApplicationLabel`로 이름 조회. 실패(NameNotFound, 가시성 제한)하면 패키지명 표시. 권한·`<queries>` 추가 금지.
- 조회는 순수 함수/인터페이스로 분리해 JVM 시험 가능하게. 앱 이름은 로그에 남기지 않는다.

### 7단계 — LG 큰 글꼴 자동 점검

- `adb devices`에 기기가 없으면 **건너뛴다** (작성 시점 연결 안 됨). 있으면 `night-plan-2026-10-19.md`의 방식 참고, font_scale은 끝나면 원래대로.

### 8단계 — 정리·보고서

- 전체 빌드·시험·lint 다시 확인.
- `docs/c-notes/evening-report-2026-10-19.md`:
  1. 쉬운 요약 (5줄)
  2. 단계별 결과 (했음/건너뜀/실패, 이유)
  3. 바뀐 파일 목록 (feature/ui-voice 미커밋 / c/tts-contract 커밋 / c/settings-entry 커밋)
  4. 추천 커밋 순서·메시지 (feature/ui-voice)
  5. 사용자가 결정할 것 (짧게, 선택지 + 추천)
  6. 팀에 보낼 메시지 초안 (필요할 때만)
  7. 사용량 (시작/끝 5시간·주간 %)
- 끝날 때 `git status`·현재 브랜치가 `feature/ui-voice`인지 확인.
