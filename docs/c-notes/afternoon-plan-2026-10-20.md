# 10/20 오후 무인 작업 지시서 (C 재호)

> C 혼자 할 수 있는 남은 일을 끝낸다. 중간에 묻지 않는다. 막히면 그 단계를 건너뛰고 보고서에 적는다.
> 규칙: `CLAUDE.md`, `AGENTS.md`. 기록·보고서·답은 **한국어**.

## 0. 멈춤과 이어 하기 (중요)

- **주간 한도가 6%밖에 안 남았다.** 중간에 사용량 한도로 세션이 멈출 수 있다. 그러면 사용자가 바로 **주간 초기화 쿠폰**을 쓰고 "이어서 해"라고 다시 부른다.
  - 그래서 **단계 하나가 끝날 때마다** `docs/c-notes/afternoon-log-2026-10-20.md`에 "N단계 끝, 바뀐 파일, 다음 할 일"을 바로 적는다. 단계 안에서도 큰 덩어리가 끝나면 적는다.
  - 다시 불려 오면 **이 지시서와 기록 파일을 먼저 읽고**, 끝난 단계는 다시 하지 말고 이어서 한다. LG 폰 설정(글꼴 크기·다크 모드·TalkBack)이 바뀐 채로 멈췄을 수 있으니 기록을 보고 먼저 원래대로 되돌린다.
- 5시간 한도: 각 단계 시작 전에 `mcp__ccd_session_mgmt__get_usage`로 확인. 80% 이상이면 새 단계를 시작하지 않고 6단계(정리)로. 90% 이상이면 기록만 남기고 멈춘다. (주간 한도는 쿠폰으로 해결하므로 멈춤 기준으로 쓰지 않는다.)
- 유료 추가 사용량(extra usage)은 **절대 켜지 않는다.**

## 1. 공통 금지

- **커밋·push·PR·팀 채팅 금지.** 브랜치 `feature/ui-voice` 유지(현재 origin과 같음, 54da79e).
- A·B·D 코드, `core/**`, main `AndroidManifest.xml`, `MainActivity` 수정 금지. 새 라이브러리·권한 금지. INTERNET 금지.
- 꺼진 플래그(`RememberChoiceFeature`, `CannotPressReasonFeature`, `MoveToButtonFeature`) 그대로.
- 화면 글자·라벨 원문·인식 문장을 로그에 남기지 않는다. 스크린샷은 `docs/` 밖(스크래치 폴더나 `build/`)에 두고 커밋 대상에 넣지 않는다. 시험 화면은 Mock 팝업만 쓴다.
- PowerShell `Get-Content`/`Set-Content`로 파일 수정 금지 (한국어 깨짐).
- 빌드: `$env:JAVA_HOME = "D:\Android\Android Studio\jbr"; .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
- 보고서: `docs/c-notes/afternoon-report-2026-10-20.md`

## 2. LG 시험폰

- LG 벨벳 LM-G900N, Android 12, TalkBack 17.0.1, 연결됨. adb: `D:\Android\SDK\platform-tools\adb.exe -d`.
- 기기 안 음성 인식 미지원 → [말하기]는 숨겨지는 게 정상.
- 디버그 도구: `ChoicePreviewActivity` (`--ei ui_debug_overlay_scenario 0..6`, `--ez ui_debug_open_settings true`, `--el ui_debug_tts_delay_ms`), `ChoiceOverlayTestService`. 사용법은 `docs/c-notes/night-plan-2026-10-19.md`, `night-report-2026-10-19.md`, `docs/experiments/overlay-talkback.md` 참고.
- 알려진 함정: `uiautomator dump` 중에는 다른 접근성 서비스가 잠깐 멈춤 → 오버레이 존재 확인은 `dumpsys window`. adb `settings put`으로 TalkBack 켜기는 불안정 → **TalkBack은 켜지 말고**, TalkBack이 필요한 확인은 "사람 확인 필요"로 남긴다. PowerShell에서 dumpsys 한국어가 깨질 수 있음.
- 바꿔도 되는 폰 설정: `settings put system font_scale`, `cmd uimode night yes/no`. **시작 전 원래 값을 기록에 적고, 끝나면(또는 멈추기 전) 반드시 되돌린다.** 그 밖의 시스템 설정은 바꾸지 않는다.
- 접근성 서비스 목록이 꼬이면 복구: `settings delete secure enabled_accessibility_services` + `settings put secure accessibility_enabled 0` (기록에 적기).

## 3. 단계

시작 전: `git status`가 깨끗하고(이 지시서 하나만 미추적이면 정상) `feature/ui-voice`인지 확인.

### 1단계 — 작은 수정: 듣는 중이면 첫 안내 건너뛰기
- `PromptAnnouncementListener`는 창이 뜨고 0.7초 뒤 첫 TTS를 낸다. 그 전에 [말하기]를 눌러 듣는 중이면 첫 안내를 건너뛴다(마이크 보호). 듣기가 끝난 뒤에 다시 내지 않는다(사용자가 이미 행동함).
- 시험 추가, 빌드 통과.

### 2단계 — LG 설치·자동 점검
- `assembleDebug` 설치(`adb -d install -r`). 시험 서비스로 시나리오 0~6 선택 창을 띄워 확인:
  1. 기본 글꼴 / `font_scale 1.3` / `font_scale 2.0`(가능하면): 창이 화면을 넘으면 스크롤되는지, 버튼 글자가 잘리지 않는지, [다시 듣기]·[그만 말하기] 한 줄이 깨지지 않는지 (스크린샷 보고 판단).
  2. 다크 모드 켬/끔: 선택 창 색이 바뀌는지, 글자가 읽히는지.
  3. [말하기]가 숨겨지는지 (LG는 기기 안 인식 미지원).
  4. 설정 화면(`--ez ui_debug_open_settings true`): 음성 구역이 "이 기기는 지원하지 않아요" 류로 보이는지, 허용 앱 이름이 보이는지.
- 결과는 표로 기록. 깨진 화면이 있으면 C 코드에서 고치고 다시 확인(최대 2회). 고칠 수 없으면 보고서에.
- 끝나면 font_scale·다크 모드를 **원래 값으로 되돌리고** 기록.

### 3단계 — PR 초안 갱신
- `docs/c-notes/pr-draft-ui-voice.md`를 오늘(54da79e + 이번 작업) 기준으로 고친다: 기능 목록, 꺼진 플래그, Mock 기준·실기기 범위(LG에서 본 것 / 못 본 것), 권한(debug RECORD_AUDIO만), 시험 개수, A·B·D에게 필요한 것(RECORD_AUDIO, 오버레이 세 번째 인자, 계약 PR 뒤 교체, D 저장소), 리뷰어 D. 본문 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- 사용자가 GitHub 웹에서 복사해 붙일 수 있게 제목 한 줄 + 본문 형태.

### 4단계 — 중간 발표(10/28, 발표자 우진)용 C 부분 자료
- `docs/c-notes/midterm-c-part.md`: 발표자가 읽기 쉬운 문서.
  1. C가 맡은 것 한 줄 요약
  2. 화면 흐름(팝업 감지 → 선택 창 → 선택/음성 → 결과 안내) — 짧은 글 그림
  3. 안전 장치(포커스 안 뺏음, 명령어 오인 방지, 결제·동의 안 누름, 라벨 허용≠실행)
  4. 실기기에서 확인한 것 / 아직 Mock인 것 (정직하게)
  5. 시연 순서(LG + debug 도구로 3~5분, adb 명령 포함) 와 예상 질문·답 5개
- 숫자는 보고서·실험 문서에 있는 것만 쓴다. 추측 금지.

### 5단계 — 남은 시간이 있으면
- `README.md`(ui/voice)가 현재 기능과 맞는지 정리.
- 그 밖에 C 범위에서 혼자 할 수 있고 위험이 낮은 정리만. 새 기능은 만들지 않는다.

### 6단계 — 정리·보고서
- 전체 빌드·시험·lint. 폰 설정이 원래대로인지 다시 확인(font_scale, 다크 모드, 접근성 서비스).
- 보고서: 쉬운 요약 5줄 / 단계별 결과 / LG 점검 표 / 바뀐 파일 / 추천 커밋 묶음·메시지 / 사용자가 결정할 것 / 팀 메시지 초안(필요할 때만) / 사람이 확인할 것(TalkBack 순서 등) / 사용량(시작·끝, 주간 포함, 중간에 멈췄다 이어 했으면 그 사실도).
- 끝날 때 `feature/ui-voice`, 미커밋 확인.
