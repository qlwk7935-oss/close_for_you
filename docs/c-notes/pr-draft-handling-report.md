# PR 초안 — main ← c/handling-report

> 사용자(재호)가 GitHub 웹에서 올릴 때 쓰는 초안. push·PR 생성은 하지 않았다.

## 제목

C: 자동 처리 결과 음성 안내 — SpeechHandlingListener (계약 v3 HandlingReport, 제목 낭독 OFF·WAIT 초기화)

## 본문

### 무엇

계약 v3(#45) `PopupHandlingListener`의 C 구현 `SpeechHandlingListener`. 프리셋·F12 규칙으로 **묻지 않고 처리한** 팝업의 `HandlingReport`를 TalkBack 사용자에게 말한다. 문장 표·규칙: `docs/c-notes/handling-report-c.md`, A 조립 예: `docs/c-notes/presenter-usage-for-a.md` 6장.

**Mock 기준, 실제 연동 미검증.** A 서비스에 아직 연결되지 않았고 실기기·TalkBack 확인을 하지 않았다.

### 우진 요청(10/25) 반영

- 자동 처리 결과 안내는 이 리스너 하나만 — `F9ResultFeedback` 중복 호출 금지(문서·KDoc 명시, A 코드는 고치지 않음)
- 사용자가 직접 고른 결과는 `PopupChoiceFlow` 담당. USER_CHOICE 보고는 계약 `HandlingReport` init이 막아 리스너로 오지 않음(시험)
- 성공 안내("닫았어요")는 `HandlingReport.closedConfirmed`일 때만(attempted + MECHANICAL_SUCCESS + CLOSED_CONFIRMED)
- ASK·미실행(attempted=false)·결과 없음은 말하지 않음
- **제목 낭독 기본 OFF**: `HandlingTitleSpeechFeature.enabled = false` → "광고를 닫았어요"처럼 종류(묶음은 종류·개수)만. 켤 때의 기존 규칙(앞 20자, 숫자 7자리 이상 제외)은 남김. **나중에 C 설정으로 켬, 기본 OFF** — 켜는 곳은 생성자 인자 `titleSpeechEnabled` 한 곳(설정 UI는 이 PR에 없음). 제목은 toString·로그·저장 없음
- WAIT 기본 키 `windowId + structureDigest`는 **Mock 임시**(KDoc·문서 명시), 운영 키는 A가 `waitKeyOf`로. 불확실하면 null → 매번 말함
- **WAIT 기록 초기화 입구** `resetWaitMemory(WaitResetReason)`: NEW_POPUP·POPUP_REMOVED·SERVICE_RECONNECTED·SERVICE_STOPPED(`clear()`)

### 바뀐 파일 (C만)

- `app/src/main/java/kr/closeforyou/ui/voice/SpeechHandlingListener.kt` (새)
- `app/src/test/java/kr/closeforyou/ui/voice/SpeechHandlingListenerTest.kt` (새)
- `app/src/main/java/kr/closeforyou/ui/voice/AutoDecisionSpeech.kt` (KDoc 한 줄)
- `docs/c-notes/handling-report-c.md` (새), `docs/c-notes/presenter-usage-for-a.md` 6장, `docs/c-notes/contract38-c-connect.md` 체크 1줄, 이 초안

고치지 않음: `core/**`, A·B·D 코드, 매니페스트, Gradle, 라이브러리·권한. `PresetAutoCloseFeature.live`·REVALIDATING·제목 낭독은 계속 꺼짐.

### 시험

`:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug` 통과(로컬, Android Studio jbr). SpeechHandlingListenerTest 26개 포함, 전체 Debug 763개 / Release 748개 실패 0.

### 우진이 확인할 항목

- [ ] **리스너 연결**: A가 PRESET·REMEMBERED_RULE 처리마다 `onHandled(HandlingReport(...))` 1번, `F9ResultFeedback`는 부르지 않음. batcher는 PopupChoiceFlow와 같은 것
- [ ] **3조건**: "닫았어요"는 `closedConfirmed`(attempted + MECHANICAL_SUCCESS + CLOSED_CONFIRMED)일 때만, 그 밖의 시도는 "확인하지 못했어요"
- [ ] **2초 묶음**: 2초 안의 자동 닫기가 "광고 2개, 쿠폰 1개를 닫았어요." 한 문장, token null
- [ ] **WAIT 1회 + 초기화**: `waitKeyOf`에 A의 같은 팝업 키(불확실하면 null), 새 팝업·제거 확인·서비스 재연결 때 `resetWaitMemory(...)`, onDestroy `clear()`
- [ ] **LEFT_APP**: 묶지 않고 바로 "…다른 화면이 열렸어요. 이 앱에서는 당분간 자동으로 처리하지 않을게요."
- [ ] **사기 경고**: DENY + SUSPICIOUS_CONTENT는 경고만, 누를 것 없음
- [ ] **ASK/미실행 무음 · 제목 로그·저장 금지/낭독 OFF**: ASK·attempted=false는 말하지 않고(선택 창은 A가 기존 흐름으로), 첫 통합은 `title = null`, 제목 원문을 로그·저장하지 않음

### 남은 위험

- 실제 A 서비스 연결·LG 벨벳 TalkBack 실측 전(묶음 문장·LEFT_APP·사기 경고가 TalkBack 낭독과 겹치는지 미검증)
- WAIT "같은 팝업" 운영 키 미정(Mock 기본값 사용 중)
- `PresetAutoCloseFeature.live = false`라 운영에서는 아직 말할 일이 거의 없음

🤖 Generated with [Claude Code](https://claude.com/claude-code)
