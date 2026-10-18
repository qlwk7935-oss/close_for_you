# 밤샘 무인 작업 지시서 — 2026-10-18 (C 재호)

> 사용자가 자는 동안 Claude가 혼자 작업한다. 이 파일은 **사용자가 미리 승인한 범위**다.
> 이 범위 밖의 일은 하지 않고 아침 보고서의 "결정할 것"에 남긴다. 사용자에게 질문하지 않는다(답할 사람이 없다).

## 0. 시작 전 반드시 읽기
- 저장소 `CLAUDE.md`(→ `AGENTS.md`), 메모리(`team-repo-close-for-you`, `project-schedule`, `report-before-work`)
- 진행 기록: `docs/c-notes/night-log-2026-10-18.md` (없으면 새로 만들고, 있으면 마지막 항목부터 이어서)

## 1. 멈추는 조건 (하나라도 해당하면 정리하고 멈춤)
1. 시각 **2026-10-18 11:00 (KST)** 도달 → 아침 보고서 마무리 후 종료
2. **5시간 한도**(`mcp__ccd_session_mgmt__get_usage`의 "5-hour limit")
   - **85% 이상: 새 단계를 시작하지 않는다.** 지금 단계만 빌드·시험 통과 상태로 정리.
   - **90% 이상: 멈춘다.** 진행 기록에 다음에 할 일을 적고 상태를 `멈춤 — 5시간 한도 90%, 초기화 후 이어서`로.
   - 05:35 예약 세션 또는 한도 자동 재개(`autoContinueAtUsageLimit`)가 이어받는다.
- 사용량은 **빌드·시험을 돌릴 때마다** 확인한다 (단계 끝만이 아니라).
- **주간 한도는 신경 쓰지 않는다** (사용자 지시, 2026-10-18: 리셋권 있음).
- 추가 유료 사용량(extra usage)은 꺼져 있다. 절대 켜지 않는다.
- 이어받은 세션은 **먼저 빌드·시험을 돌려 깨진 곳이 있으면 그것부터 고친다** (앞 세션이 한도로 중간에 끊겼을 수 있음).

## 2. 동시 작업 방지
- 작업 중에는 진행 기록 맨 위 `상태:` 줄을 `진행 중 (HH:MM 갱신)`으로 **단계마다 갱신**한다.
- 예약 세션은 시작하자마자 이 줄을 본다. **갱신 시각이 15분 이내면 다른 세션이 작업 중이므로 아무것도 하지 않고 끝낸다.**
- 멈출 때는 `상태: 멈춤 — 사유` 로 바꾼다.

## 3. 승인된 권한 (사용자 2026-10-18 02시경 승인)
| # | 허용 |
|---|---|
| 1 | C 파일 생성·수정: `app/src/main/java/kr/closeforyou/ui/**`, `app/src/main/res/values/ui_voice_strings.xml`, `app/src/debug/**`(C 파일), `app/src/test/java/kr/closeforyou/ui/**`, `docs/c-notes/**`, `docs/experiments/**`, `experiments/**` |
| 2 | 빌드·시험·lint (팀 앱 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`, 실험 앱) — `JAVA_HOME=D:\Android\Android Studio\jbr` |
| 3 | LG 폰(adb `-d`): 설치, 앱 실행, `uiautomator dump`, **우리 앱 화면만** 캡처, TalkBack 켜고 끄기 (끝나면 반드시 끄기) |
| 4 | 05:35 예약 작업 1개 (이미 생성) |
| 5 | 잠들지 않게 요청 (앱 기능) |
| 6 | `app/src/debug/AndroidManifest.xml`에 **C 시험용 접근성 서비스** 등록 + debug 리소스에 서비스 설정 XML |
| 7 | `app/src/debug/AndroidManifest.xml`에 `RECORD_AUDIO` (debug 전용) |
| 8 | ~~debug 매니페스트 TTS `<queries>`~~ → **불필요**: A가 main 매니페스트에 넣음(PR #9 병합). 하지 않는다 |

## 4. 절대 하지 않는 것
- **git commit / push / PR / 브랜치 전환·리셋** (사용자가 아침에 파일별로 직접 커밋함)
- `app/src/main/AndroidManifest.xml`, `core/**`, `accessibility/**`, `decision/**`, `data/**`, `app/**`(A의 MainActivity·CorePreferences), Gradle 파일 수정
- 새 라이브러리·의존성 추가, 공통 계약 변경
- PC·폰의 시스템 설정 변경 (TalkBack 켜고 끄기만 예외)
- 화면 원문·개인정보를 로그·파일에 남기기 (AGENTS.md 6-9, CLAUDE.md 3장)
- INTERNET 권한 (병합 매니페스트 확인)

## 5. 작업 목록 (위에서부터, 각 단계 끝마다 빌드·시험 통과 상태 유지)
각 단계: 구현 → 단위 시험 → `assembleDebug testDebugUnitTest lintDebug` → (가능하면) LG 설치·uiautomator 확인 → 진행 기록 갱신 → 사용량 확인.

0. **🐞 SKIP_ONCE 버그 수정 (최우선, A 지적 2026-10-18)**
   - `ChoicePromptPlanner.toUserChoice`가 역할이 `SKIP`인 후보도 `CLOSE_ONCE`로 만든다. 계약상 `SKIP` → **`ChoiceKind.SKIP_ONCE`**, `CLOSE` → `CLOSE_ONCE`.
   - `PromptOption`에 후보 역할(`ButtonRole`)을 담고 그대로 변환. 버튼 문장은 기존처럼 실제 라벨("'건너뛰기' 버튼 눌러 주기").
   - 회귀 시험: SKIP 후보 → SKIP_ONCE + candidateId + buttonMeaningConfirmed=true + 반복 false / CLOSE 후보 → CLOSE_ONCE / CLOSE·SKIP 둘 다 있을 때 각각 맞게.
   - 디버그 미리보기 결과 표시도 확인.

1. **오버레이 (`ui/voice/overlay/ChoiceOverlay`)**
   - **토큰 무효화 대비 (A 지적):** 선택 창 버튼 탭도 "사용자 조작"이라 A 서비스가 토큰을 무효화할 수 있다. C 오버레이 창에 **A가 식별할 수 있는 표시**(고정 창 제목, 패키지/창 종류 정보를 노출하는 상수)를 두고, "C 자체 안내창 이벤트는 무효화·새 팝업 판정에서 제외"(설계 F11)를 A에게 제안하는 내용을 보고서에 쓴다. A 코드는 고치지 않는다.
   - 접근성 서비스 컨텍스트에서 `TYPE_ACCESSIBILITY_OVERLAY`로 `ChoicePromptContent`를 띄우고 내리는 C 부품. Lifecycle/SavedStateRegistry 연결(실험 앱 `OverlayLifecycleOwner` 참고). `FLAG_NOT_FOCUSABLE`, 포커스 강제 이동 없음.
   - 사용자 선택 → `UserChoice` 콜백. A가 나중에 호출만 하면 되게 인터페이스로.
   - debug 전용 **C 시험용 접근성 서비스**(권한 6)로 다른 앱 위에 가짜 팝업을 띄우는 시험 경로. `canRetrieveWindowContent=false`, 화면 읽지 않음.
2. **안내 연결**
   - 미리보기·오버레이의 [내용 다시 읽기] → `TtsPopupAnnouncer`로 fixture 본문 낭독. 오버레이 등장 시 안내(paneTitle + 필요 시 자체 TTS) — 실험 결과 미확정이라 방식은 설정으로 바꿀 수 있게.
   - **묶음 안내** `AnnouncementBatcher`: 2초 안의 처리 결과를 묶어 "팝업 3개를 닫았어요". 단위 시험 필수(시간은 주입 가능하게).
3. **설정 화면 (`ui/voice/settings`)**
   - Compose. 앱별 자동 닫기(기본 **꺼짐**), 안내 길이(짧게/자세히), 기억 목록 보기·해제·전체 삭제(`PopupRuleRepository` — 지금은 `InMemoryMockPopupRuleRepository`), 일시정지는 A의 `CorePreferences`를 **읽기만** 하거나 연결 지점만 표시.
   - 저장은 C 전용 SharedPreferences(`UiVoicePreferences`). DataStore 등 새 라이브러리 금지.
   - "오늘 하루 보지 않기" 옵션 없음(B가 가짜 닫기로 차단). 쿠폰·이벤트 분류 없음.
   - 모든 요소 라벨·heading·탐색 순서. debug 미리보기 화면에서 진입.
4. **음성 명령 (`ui/voice/command`)**
   - `KoreanVoiceCommandMatcher`: 인식 문장 → `VoiceCommand` 규칙 매칭(닫아/닫아줘→CLOSE, 건너뛰어→SKIP, 다시 읽어줘→READ_CONTENT, 그대로 둬→KEEP_OPEN, 돌아가→GO_BACK, 버튼으로 이동→MOVE_TO_BUTTON, 무슨 그림이야→DESCRIBE_IMAGE). 애매하면 매칭 안 함. 단위 시험 다수.
   - `OnDeviceVoiceCommandInput`: 계약 `VoiceCommandInput` 구현. API 31+ `isOnDeviceRecognitionAvailable` → `createOnDeviceSpeechRecognizer`, 말하기 버튼에서만 시작, 권한 없으면 `MICROPHONE_PERMISSION_REQUIRED`, 미지원이면 `ON_DEVICE_UNAVAILABLE`. 원격 인식기 사용 금지.
   - 미리보기에 [말하기] 버튼 + 런타임 권한 요청(권한 7). 인식 결과 문장은 로그 금지.
   - 결정·로그인 대신 누르기는 음성으로 실행하지 않음(CLAUDE.md 3장 1).
5. **접근성 점검**
   - LG에서 `uiautomator dump`로 미리보기·설정 화면의 라벨·역할·순서를 뽑아 `docs/c-notes/a11y-check-2026-10-18.md`에 표로. 48dp 미만 터치 영역, 라벨 없는 버튼 찾기.
   - KWCAG 2.2 주요 항목 점검표(대체 텍스트, 키보드/포커스 순서, 명도 대비, 레이블, 상태 알림).
6. **아침 보고서** `docs/c-notes/night-report-2026-10-18.md`
   - 파일별: 무엇을, 왜, 시험 결과, 남은 문제
   - **추천 커밋 순서**(파일 묶음 + 커밋 메시지 초안)
   - 결정할 것 목록 (A에게 요청할 것 포함), 실기기에서 사람이 확인해야 할 것
   - 사용량 기록(시작/종료 5시간·주간 %)
   - **팀 공유 메시지 초안** (A의 10/18 공유에 대한 C 답변): 진행 상황(구현·시험 완료/미검증 구분), SKIP_ONCE 수정, LG 실기기 TalkBack 결과, 오버레이 토큰 무효화 제외 제안, `AnnouncementState` 실패 값·`ChoiceKind` 버튼 이동 부재·`announcementAvailable` 기준·오버레이 띄우는 통로 계약 제안, "TTS_COMPLETED ≠ 사용자 확인" 동의. 사용자가 직접 보낸다.

## 7. main 반영 상태
- 2026-10-18 02시 기준 `origin/main`에는 B PR #8(`decision` 패키지)과 A PR #9(main 매니페스트 TTS queries)가 있다. C 파일과 겹치지 않는다.
- **반영 완료:** 사용자가 02시경 직접 merge함 → 커밋 `f5ea23d` (충돌 없음, push 안 함, origin보다 10개 앞섬). TTS queries가 main 매니페스트에 있다.
- **밤 세션은 merge·push하지 않는다.** 시작할 때 `git log --oneline -3`이 `f5ea23d`부터인지 확인한다.

## 6. 막혔을 때
- 빌드가 계속 깨지면 그 단계의 변경을 **되돌리지 말고**(사용자가 보고 판단) 보고서에 원인과 함께 적고 다음 단계로.
- 승인 범위 밖이 필요하면 하지 않고 "결정할 것"에 적는다.
- LG 폰이 안 잡히면(adb devices 비어 있음) 폰 관련 단계는 건너뛰고 아침으로.
