# 삼성 P01: native 창 이벤트·표시 수명·음성 경계

기준: `b3b87b3c1286c985936b9fa09a32c13726a46b2b`, 별도 worktree `/workspace/a-samsung-p01`, 브랜치 `fix/a-samsung-p01-lifetime-20261028`. 지연 광고 브랜치를 포함한 기존 8개 worktree의 HEAD/status/기존 미커밋 파일을 보존한다. 커밋·푸시·병합하지 않는다.

## 실제 증거와 확정할 수 없는 것

사용자 삼성폰 보고는 다음과 같다. 실측 시각·원문·패키지·창 ID는 저장하지 않는다.

- ATTEMPT 1: SHOWN → WINDOW_SET_INVALIDATED → OVERLAY_REMOVED.
- ATTEMPT 2: SHOWN → OWNED_CONTENT_CONFIRMED/OWNED → WINDOW_STATE_INVALIDATED → OVERLAY_REMOVED.
- ATTEMPT 3: SHOWN → OWNED → PACKAGE=MISMATCH/NOT_OWNED → CONTENT_INVALIDATED → OVERLAY_REMOVED. PACKAGE_BLOCKED도 보고됨.
- 실제 표시 약 2초, 음성 없음. 수동 닫기 선택/F9 실행은 미확인.

**직접 제거 트리거는 이벤트 무효화다.** 이 기록을 TTL 만료나 cleanup 실패로 바꾸어 설명하지 않는다. PACKAGE_BLOCKED는 `shouldIgnore`에서 거부된 이벤트를 서비스가 기존 패키지 quick exclusion으로 처리한 후속 경계다. 앞선 패키지 불일치의 실제 발신자를 설명하지 않는다. 앞 행의 OWNED와 뒤 행의 WINDOW_STATE/CONTENT 이벤트는 서로 다른 이벤트일 수 있다.

기존 PACKAGE 거부는 native/window getter보다 앞서 short-circuit한다. 따라서 이 로그만으로 원본 앱 창, 시스템 상태 표시줄, C 자체 이벤트 중 어느 것인지 확정할 수 없다. 실제 발신 앱 이름이나 루트 내용을 출력하여 찾지 않는다.

## 코드로 확인하고 수정한 경계

### C 자체 창 이벤트

기존 `ManualPopupConnection.shouldIgnore`는 콘텐츠·버튼·포커스를 native 객체로 검증하지만 WINDOW_STATE/WINDOWS_CHANGED는 별도 title/token binding 경로로 보낸다. 후자는 ACTIVE/FOCUSED mask를 거부한다. 같은 C 창의 콘텐츠는 통과하고 창 상태/포커스 알림은 거부될 수 있는 차이가 있었다.

이제 실제 driver가 호출하는 `inspectManualEvent → inspectManualOwnedWindowEvent → inspectManualNativeOwnership`를 사용한다. 창 상태/집합 이벤트도 **현재 C의 native lifetime·owner·generation·유효한 30초 display lease·이벤트 시각·동일 이벤트 창 ID·show 이전 집합에 없던 ID·현재 유일한 C 소유 live root**를 모두 요구한다. live root의 package/window, 접근성 overlay 타입·display·C pane title 검사는 기존 `readOwned` 그대로다. title binding으로 fallback하지 않는다.

WINDOW_STATE는 자기 package가 필요하다. WINDOWS_CHANGED만 package가 없는 framework 알림을 허용할 수 있으며, 나머지 native/live/window 검사가 모두 통과해야 한다. 비어 있지 않은 다른 package는 절대로 허용하지 않는다. ADDED/BOUNDS/LAYER/TITLE/ACTIVE/FOCUSED와 그 조합만 이 **정확한 C 창**에서 처리한다. REMOVED, 혼합 removal, 부모/자식 변화, 0/알 수 없는 mask는 계속 거부한다. 대상 앱·다른 창·터치·스크롤에는 적용하지 않는다.

또한 `overlay.present` 안의 실제 addView가 이벤트를 만들 수 있는데 기존 이벤트 하한은 함수 반환 뒤 SHOWN 시각이었다. 현재 native View의 `created(generation)` callback에서만 생성 하한을 기록하고, SHOWN이 같은 generation으로 성공했을 때만 그 경계를 사용한다. 이 interval의 이벤트도 위의 전체 소유권 검사를 통과해야 한다. **표시 전 이벤트라는 이유만으로 무시하는 유예 시간이 아니다.** 이전 native 생성보다 오래된 이벤트·이전 generation·미확인 ID·clock 역전은 실패한다. 표시 lease의 30초 시작은 여전히 실제 SHOWN 시각이다. 수집/실행 token의 TTL은 갱신하지 않는다.

초기 회귀가 표시 전 token 조회에서 준비 중 native 경계까지 지우는 문제를 발견했다. 준비 기록은 표시 권한을 주지 않은 채 보존하고, 성공한 SHOWN 확인 전까지 `current()`는 null을 반환하도록 수정했다.

### 반복 재표시와 늦은 정리 receipt

기존 `nativeProcessed`의 실제 완료가 이미 재수집을 요청한 뒤 늦은 제거 receipt가 도착하면, `processed`는 `cleanup.ready`만 보고 재수집을 다시 요청하고 native 참조를 지울 수 있었다. 이제 실제 pending→ready/HIDDEN 전이에서만 완료 후처리하며 같은 barrier permit당 재수집은 한 번이다. 늦은 이전 receipt로 새 native 참조를 지우지 않는다. 실패한 정리 상태를 복구하거나 ready를 강제로 만들지 않는다.

일반적인 재표시 경로는 이벤트 무효화 → 실제 C 제거/정리 → fresh review → detector/evidence → 새 offer다. `PACKAGE_BLOCKED`가 detector baseline을 clear하여 다음 bounded Dialog가 다시 후보가 되는 경로도 존재한다. `ChoiceReviewBudget`은 동일 app/window/structure/content에서 첫 표시+회복 한 번을 유지한다. 3개 표시가 모두 이 경로라면 identity가 달랐거나 서비스/연결 상태가 달랐다는 추가 정보가 필요하며, 어느 필드가 달랐는지는 기존 로그로 확정하지 않는다. 새 로그는 FIRST/UNCHANGED_RECOVERY/IDENTITY_CHANGED/EXHAUSTED만 구분한다. 재표시 허용 횟수를 늘리거나 새 판정 없이 과거 후보를 재발행하지 않는다.

### 음성

C 기본 설정은 `PANE_TITLE_AND_TTS`지만 사용자 설정은 다를 수 있다. 기존 listener는 표시 후 700ms에 안내하며, dismiss 시 예약 job을 취소하고 TTS를 stop한다. 따라서 조기 제거는 예약/재생을 중단할 수 있다. 다만 약 2초 표시만으로 음성 부재가 오직 제거 때문이었다고 확정할 수 없다.

별도 코드상 경계도 있었다. 700ms 시점에 Android TTS가 INITIALIZING이면 `TtsPopupAnnouncer`는 전달되지 않은 결과로 끝내고 다시 시도하지 않는다. A announcer는 이제 기존 엔진 StateFlow의 초기화 완료만 C의 취소 가능한 안내 job 안에서 기다린다. 준비 후 같은 표시 의도를 다시 확인한다. 새 내용/결과 요청·Stop은 이전 대기 요청을 무효화하므로 오래된 안내가 나중에 새 음성을 덮지 않는다. 창 제거/화면 변경/서비스 종료/30초 lease 만료에서는 발화하지 않는다. UNAVAILABLE을 성공으로 바꾸거나 반복 재시도하지 않는다. 엔진의 일반 예외는 고정 FAILED와 NOT_IMPLEMENTED로 격리하여 서비스 coroutine의 일반 예외 로그에 backend 원문이 노출되지 않게 한다. 취소는 원래 CancellationException 객체를 그대로 전파한다.

이는 실행 증거가 아닌 표시 의도의 수명이다. 원본 +5초가 지나도 C가 유효하면 안내할 수 있으나, 닫기 선택은 여전히 **실제 C 제거+정리 → 새 F0 → 같은 팝업/대상 비교 → B USER_CHOICE 재판정 → F9 클릭 직전 재검사 → 최대 한 번**이다. 과거 snapshot/token 시각 복사, 자동 닫기 활성화는 없다.

C 음성·overlay·공통 계약 소스는 수정하지 않는다. 기본 700ms, pane-only 사용자 설정, 오디오 속성, C 표시 정책도 바꾸지 않는다. 한국어 TTS 데이터, 접근성 음량, 오디오 포커스/TalkBack 중첩 문제는 실제 폰 확인이 필요하다.

## Debug 진단

기존 `A_ManualPrompt` 태그와 OWNERSHIP/CLEANUP/ACTION 로그를 유지한다. Release `ManualPromptLog.create()`는 여전히 null이며 새 formatter/Logcat은 Debug에만 있다. 추가 root/창 수집이나 IPC는 진단에 넣지 않는다. event의 기존 primitive와 A가 보유한 native 객체/원본 대상의 ID 비교만 사용한다. 원문·ID·토큰·지문·시각·예외 원문은 출력하지 않는다.

```text
A_MANUAL_EVENT;ATTEMPT=1;EVENT=WINDOW_SET;RESULT=OWNED;CHECK=COMPLETE;ID_RELATION=NATIVE_ID_MATCH;EVENT_PACKAGE=UNAVAILABLE;MASK=ACTIVE
A_MANUAL_EVENT;ATTEMPT=2;EVENT=CONTENT;RESULT=NOT_OWNED;CHECK=PACKAGE;ID_RELATION=ORIGINAL_TARGET_ID_MATCH;EVENT_PACKAGE=ORIGINAL_TARGET;MASK=NOT_APPLICABLE
A_MANUAL_SPEECH;ATTEMPT=2;STATE=WAITING_FOR_ENGINE
```

위는 **형식 예시**이며 새로운 삼성 실측이 아니다.

- `ID_RELATION`: NATIVE_ID_MATCH / ORIGINAL_TARGET_ID_MATCH / UNMATCHED_ID / UNAVAILABLE / AMBIGUOUS. 비교 일치만으로 출처를 인증하지 않는다. SYSTEM이라고 추측하지 않는다.
- `EVENT_PACKAGE`: OWN / ORIGINAL_TARGET / OTHER / UNAVAILABLE. MISMATCH를 허용하는 목록이 아니다. 다른 package+native ID 일치도 계속 거부한다.
- `MASK`: 실제 공유 mask 판정의 ADDED/BOUNDS/LAYER/TITLE/ACTIVE/FOCUSED/OWN_COMBINATION/REMOVED/MIXED_REMOVAL/UNSUPPORTED/ZERO/NOT_APPLICABLE.
- OWNERSHIP의 새 TIME=`CURRENT_NATIVE_CREATION`: 현재 native 생성 후 SHOWN 반환 전 발생한 이벤트이며, 전체 native 소유권 확인이 있어야만 통과한다.
- `A_MANUAL_SPEECH`: MODE_TTS / MODE_PANE_ONLY / REQUESTED / WAITING_FOR_ENGINE / READY / UNAVAILABLE / STALE / COMPLETED / NOT_DELIVERED / CANCELLED / FAILED / STOP_REQUESTED. COMPLETED는 엔진 완료 receipt이며 실제 청취 성공은 아니다. REQUESTED 없이 STOP_REQUESTED면 예약 발화 이전 취소와 구별할 수 있다.
- `REVIEW_REQUESTED/CLEANUP_REVIEW`, `REVIEW_ADMISSION/REVIEW_FIRST·REVIEW_UNCHANGED_RECOVERY·REVIEW_IDENTITY_CHANGED`, `OFFER_REJECTED/REVIEW_BUDGET_EXHAUSTED`로 정리 후 새 관측과 재표시 admission을 구별한다.

EVENT는 offer별 최대 16개 고유 코드, speech는 유한 enum 중복을 억제한다. 이전 안내의 취소는 원래 ATTEMPT로 기록한다. 로그 출력 실패가 판단·취소·정리를 바꾸지 않는다.

## 삼성 재실측 준비

이 수정본은 아직 로컬이며 원격 기준 브랜치에는 없다. 아래는 **수정 파일을 적용한 Windows 소스**에서 실행한다. 권한/동의를 adb로 강제하지 않는다.

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :popup-test-app:assembleDebug
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
$serial = "<삼성 테스트폰 serial>"
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
# 필요한 경우만 재현 앱 설치. 더 최신 #59 APK가 있으면 버전을 구분한다.
# & $adb -s $serial install -r .\popup-test-app\build\outputs\apk\debug\popup-test-app-debug.apk
& $adb -s $serial logcat -v brief -s A_ManualPrompt:I '*:S'
```

폰 UI에서 접근성 서비스·수집 동의·시험 앱 허용·분석 시작, C의 선택창 음성 설정을 직접 확인한다. 접근성 음량·한국어 TTS 준비 상태도 별도로 확인한다. 새 PowerShell 창에는 같은 `$adb`, `$serial`을 설정한 뒤 다음을 실행한다.

```powershell
& $adb -s $serial shell am start -W -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01
```

TalkBack OFF/ON 각각 선택창을 건드리지 않고 6초 이상 유지되는지, 안내가 실제 들리는지 확인한 뒤 **C의 닫기 선택을 사람이 명시적으로** 누른다. adb로 원본 닫기 버튼이나 좌표를 누르지 않는다. 기존 ACTION 로그의 새 F0/SNAPSHOT_MATCH/TARGET_MATCH/B_DECISION/F9_VALIDATION/CLICK_ATTEMPT/CLICK_RESULT와 실제 원본 팝업 소멸을 구분한다. `CLOSED_CONFIRMED` 없는 클릭/창 제거를 성공이라 기록하지 않는다. 외부 앱 터치·스크롤·원본 팝업 교체·동의 철회에서는 제거/클릭 0회를 따로 확인한다. 미확인 이벤트에 대한 무효화가 발생하면 해제하지 않는다.

## 로컬 검증과 한계

실제 driver가 호출하는 native 창 판정·생성/표시 수명·정리 전이/permit·음성 초기화/취소/교체 계약을 JVM에서 검사한다. 음성 회귀는 실제 C `PromptAnnouncementListener`, 실제 `TtsPopupAnnouncer`와 fake SpeechEngine을 연결한다. Android 이벤트/오디오 출력이나 실제 클릭 성공을 JVM으로 입증하지 않는다. 기존 새 F0·B·F9·cleanup 회귀도 전체 suite로 확인한다.

최종 로컬 검증은 JDK 17에서 수행했다. 신규 회귀는 `SamsungPromptLifetimeTest` 19개와 `ManualPromptSpeechTest` 11개로 총 30개다. 전체 JVM 실행은 앱 Debug 1,050개, 앱 Release 1,035개, 호스트 Debug 55개, 합성 앱 Debug 26개로 **2,166건**이며 실패·오류·skip은 모두 0이다. Debug/Release 공통 테스트의 중복 실행을 포함하는 수치다.

앱 Debug/Release APK, 앱 AndroidTest APK, 합성 앱 Debug APK와 호스트 관련 빌드가 통과했다. 앱 Debug/Release 및 호스트 Debug Lint 이슈는 0개다. 기존 패키징·DEX 격리 검사도 통과했다. 새 `A_MANUAL_EVENT;`, `A_MANUAL_SPEECH;` 문자열은 Debug DEX에 존재하고 Release DEX에는 없다. `git diff --check`와 신규 파일 공백 검사도 통과했다. 기기 계측 통과를 뜻하지 않는다.

`adb devices -l`에는 연결 기기가 없다. 삼성/LG 실기기·에뮬레이터 시험은 **미실행**이다. 따라서 **6초 이상 표시+실제 음성+사용자 닫기 선택의 완료 목표는 아직 실기기에서 입증되지 않았다.**

변경은 다음 11개 파일(기존 7개 수정, 신규 4개)뿐이며 모두 이 별도 worktree에 미커밋 상태로 보존한다.

- `app/src/debug/java/kr/closeforyou/accessibility/integration/ManualPromptLog.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ChoicePresentationGate.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceConnection.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ManualOverlayDiagnostics.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ManualPopupConnection.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ManualPromptLifetime.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ManualPromptTrace.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ManualPromptSpeech.kt` (신규)
- `app/src/test/java/kr/closeforyou/accessibility/integration/ManualPromptSpeechTest.kt` (신규)
- `app/src/test/java/kr/closeforyou/accessibility/integration/SamsungPromptLifetimeTest.kt` (신규)
- `docs/A_SAMSUNG_P01_PROMPT_LIFETIME_2026-10-28.md` (신규)

기존 8개 worktree의 HEAD·status·기존 미커밋 파일 해시는 작업 전과 같다. 지연 광고 브랜치, 다른 계측, C/B/D 및 공통 계약, 서비스 본체, Manifest, `gradle.properties`는 변경하지 않았다. 새 F0·B USER_CHOICE 승인·F9 최종 검사·최대 1회 클릭의 기존 경로와 5초 실행 증거/30초 표시 lease를 유지한다. 운영 자동 실행·자동 SKIP·F12 반복 실행을 활성화하지 않았다. 커밋·푸시·병합·PR 변경은 하지 않았다.

외부/불명확한 package·창 ID·이전 generation 이벤트는 여전히 C를 제거한다. 특히 사용자 로그의 PACKAGE=MISMATCH 실제 출처는 아직 미확정이다. 이번 수정이 모든 조기 제거를 해결했다고 주장하지 않는다. 초기 SYSTEM/target events를 무조건 무시하거나 보호·TTL·시간 예산을 완화하지 않는다. 원본 F1 14개 UNOBSERVED·Scroll/Window 문제도 유지한다.
