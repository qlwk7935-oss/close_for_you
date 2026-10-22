@AGENTS.md

# CLAUDE.md — C(재호) 작업용 추가 규칙

> 팀 공통 규칙은 위에서 불러온 `AGENTS.md`가 기준이다. 이 파일은 그 위에 **C 담당 작업 방식**만 더한다.
> 둘이 충돌하면 `AGENTS.md`와 `docs/README.md`의 최신 결정이 우선한다.

## 1. 사용자와 일하는 방식

- 사용자는 C 역할(재호)이고 Android·Git을 배우는 중이다.
- **모든 작업 전에 선보고한다.** 할 일·순서·바뀌는 파일·되돌릴 수 있는지를 먼저 말하고, OK를 받은 범위만 실행한다.
- 명령을 실행하기 전에 무엇을 할지 한 줄로 말한다. 새 개념(Lifecycle, Coroutine, semantics 등)은 2~3문장으로 설명한다.
- 확실하지 않은 API 동작은 추측하지 않는다. 공식 문서를 확인하거나 "실기기 확인 필요"라고 적는다.
- push·PR·팀 채팅 공유는 사용자가 결정한다. 초안까지만 쓴다.

## 2. C 범위

| 항목 | 위치·계약 | 비고 |
|---|---|---|
| F10 TalkBack 안내 | `PopupAnnouncer` 구현 | TTS·다시 듣기·중지. `TTS_COMPLETED`는 "들었다"가 아니다 |
| 사용자 선택 UI | "닫을까요?" 오버레이 → `UserChoice` | 오버레이를 띄우는 통로는 계약에 없음 → A에게 제안 |
| F13 음성 명령 | `VoiceCommandInput` 구현 | 말하기 버튼을 누를 때만. API 31+ 기기 안 인식만 |
| 설정 화면 | A의 `app/MainActivity`·`CorePreferences`와 함께 리뷰 | 앱별 자동 닫기 설정(기본 꺼짐), 기억 목록 해제 |
| TalkBack·KWCAG 점검 | 설정·오버레이 전체 | 실기기 갤럭시 S26 예정 |

- 패키지: `kr.closeforyou.ui.voice` (C 전용). 브랜치: `feature/ui-voice`.
- **C가 하지 않는 것:** OCR·캡처·감지(A), 분류·안전 판단·후보 찾기(B), Room·지문(D). 이 영역 코드는 고치지 않고 제안만 한다.
- `core/model`, `core/contract`는 공동 계약이다. 바꿔야 하면 **합의 후 별도 PR**로 한다.
- 실제 팝업 이벤트는 A의 PR #7 전까지 오지 않는다 → `testing/mock`의 `MockPopupFixtures`·`MockSnapshotSource`로 개발하고, PR에 "Mock 기준, 실제 연동 미검증"을 적는다.

## 3. C가 지킬 안전 규칙 (AGENTS.md 6장에 더해서)

1. **음성 명령 정책**
   - 바로 실행해도 되는 요청: 닫기(`CLOSE`), 건너뛰기, 다시 읽기, 그대로 두기, 버튼으로 이동. 그래도 B 정책과 A 실행 관문을 반드시 거친다.
   - 동의·로그인 대신 누르기: 음성으로 요청 → 누를 버튼 이름을 읽어 줌 → **화면 버튼 탭으로 확정**.
   - 결제·구매·구독·삭제·권한: 음성이든 탭이든 대신 누르지 않는다. 버튼 위치 안내(포커스 이동)까지만 한다.
   - 이유: 영상 소리, 옆 사람 말, TalkBack 낭독이 마이크로 들어가 명령으로 오인될 수 있다.
   - [팀 확정 필요] 로그인까지 음성만으로 실행할지.
2. **TalkBack 포커스는 사용자 것이다.** 사용자가 요청하지 않으면 포커스를 옮기지 않는다.
3. **화면 글자를 로그·파일에 남기지 않는다.** `AnnouncementRequest.text`도 출력하지 않는다. 최근 안내는 메모리에만 두고 서비스가 끝나면 지운다.
4. **INTERNET 권한을 넣지 않는다.** 라이브러리나 권한(`RECORD_AUDIO` 등)을 추가하면 병합 매니페스트를 확인하고 PR에 적는다. 새 라이브러리·권한은 사용자 확인 후 추가한다.
5. B는 "확인/OK"처럼 의미가 애매한 버튼은 사용자가 명령해도 누르지 않는다. C는 이때 "대신 닫을 수 없어요"라고 알리고 **[버튼으로 이동]**을 대안으로 제공한다.
6. 사용자가 직접 고른 닫기 버튼만 1회 허용될 수 있다. 선택 UI는 **어떤 버튼을 누를지(버튼 이름)**를 읽어 주고 확정받는다.
7. `announceForAccessibility()`는 사용 중단(deprecated)이다. 쓰지 않는다.

## 4. 이름·용어

- 분류: `ADVERTISEMENT`(광고) / `NOTICE`(공지) / `USER_DECISION`(중요 결정) / `UNKNOWN`(판단 불가). 쿠폰·이벤트 분류는 없다.
- 선택: `ChoiceKind.CLOSE_ONCE`, `SKIP_ONCE`, `READ_CONTENT`, `KEEP_OPEN`, `REMEMBER_EXACT_POPUP`(앞으로 항상), `STOP_ASKING`(다시 묻지 않기), `REVOKE_RULE`
- "앞으로 항상 닫기" = `REMEMBER_EXACT_POPUP` + `buttonMeaningConfirmed` + `repeatAutomationExplicitlyAllowed`
- C 리소스 이름은 `ui_` 접두사 (예: `ui_announce_ad_closed`). [팀 확정 전 임시 규칙]
- 문장은 전부 `res/values/strings.xml`. 코드에 한국어 문장을 직접 쓰지 않는다.

## 5. 개발 환경 (C 로컬)

- 작업 폴더는 팀 저장소 `D:\close_for_you` 하나다. (예전 실험용 `D:\Internet_project`는 내용을 옮기고 삭제함)
- C 참고 초안(설정 화면·TTS·음성 명령): `docs/c-notes/settings-tts-draft.md`
- Android Studio `D:\Android\Android Studio`, SDK `D:\Android\SDK`.
- 팀 빌드는 Gradle 8.11.1이라 **PC의 JDK 25로는 안 된다.** Android Studio 내장 JDK를 쓴다.
  ```powershell
  $env:JAVA_HOME = "D:\Android\Android Studio\jbr"; .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
  ```
- PowerShell 5.1의 `Get-Content`/`Set-Content`는 UTF-8을 잘못 읽어 한국어를 깨뜨린다. 파일 수정은 편집 도구로 한다.
- `experiments/`는 팀 앱과 별개인 독립 Gradle 프로젝트다 (CI·앱 빌드에 포함되지 않음).

## 6. 진행 중인 팀 논의 (C 관련)

- `DecisionContext`에 앱별 자동 닫기 설정 추가 (B 제안: C 설정 화면에서 켬, 기본 꺼짐) → C 동의 예정
- A 서비스가 C 선택 UI(오버레이)를 띄우는 인터페이스 추가 → C가 제안 예정
- `announcementAvailable`의 기준(TTS 준비, TalkBack 상태 등) 정의 → C가 정리 예정
- 근거 불완전 + 사용자 직접 선택 시 1회 허용 (B 구현, 의견 수렴 중) → C 찬성 예정
