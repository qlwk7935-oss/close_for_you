# P01 native 등록 초기 표시 수명 및 cleanup — 2026-10-30

## 범위와 결과 구분

- `/workspace/a-samsung-p01`, `fix/a-samsung-p01-lifetime-20261028`.
- 기준 HEAD `b34a2fb8d507dda470a751132029d88ec7291db5`, 시작 clean.
- 사용자 실측: SHOWN 약 0.13초 뒤 LIVE_SET_UNCONFIRMED/LIVE_COUNT=NONE → WINDOW_SET_INVALIDATED → dismiss → CLEANUP_FAILED/BARRIER_REJECTED/RETIRED_ID_EVENT. 서비스 재활성화 후에도 표시 실패.
- 이번 작업은 로컬 구현/회귀다. 실제 삼성 Android 창 등록 지연, 새 버전의 유지/TTS/클릭/닫힘은 실기기 미검증이다. 커밋·푸시·PR·병합 없음.

## 확정된 직접 원인과 미확정 원인

기존 경로: C `created` → addView → attach/첫 draw의 native id 관측 → SHOWN → 서비스 event → shouldIgnore의 독립 원격 소유권 검사 실패 → 대상 표시 review → `manualDisplayOwnedId()`가 빈 live 집합을 즉시 거부 → 실제 대상 root/tree/frame을 다시 읽기도 전에 UNAVAILABLE → C 제거와 TTS 취소.

native View의 add/attach/ID와 AccessibilityService.windows 목록은 원자적 등록 영수증이 아니다. 따라서 native 소유권이 확정된 View와 빈 원격 목록이 동시에 있을 수 있는 **코드상 순서**를 확인했다. 그렇다고 LIVE_COUNT=NONE만으로 이번 삼성의 정상 등록 지연이라고 확정하지 않는다. NONE에는 root/owner/title 실패도 포함될 수 있고, framework 전체 목록이 비거나 읽기/해제가 실패하면 별도 UNAVAILABLE이다.

두 번째로 기존 cleanup은 순수 REMOVED만 영수증으로 인정한다. 같은 native View가 실제 detach된 뒤 REMOVED+BOUNDS 같은 복합 마스크가 오면 EVENT_TYPE 거부 → RETIRED_ID_EVENT로 HEALTH가 영구 차단될 수 있다. 이번 제공 로그에는 정확한 마스크가 없으므로 이것이 최신 삼성의 그 이벤트라고 단정하지 않는다. 아래 수정은 해당 코드 경합을 결정론적으로 재현하고 실제 native 완료/부재 검증을 유지한 것이다. ADDED/unknown/native 미확인 등의 RETIRED_ID_EVENT는 계속 유효한 실패다.

## 수정 1 — native와 remote 등록을 분리한 표시 전용 재검증

`ManualDisplayRegistration`은 C의 실제 created(generation)에서만 시작한다. native owner/session/generation/add/attach/live id와 저장된 before 집합에서의 부재를 매 표본 확인한다. 직접 native id가 없거나 제거 중이거나 세대가 다르면 NATIVE_ID_UNAVAILABLE로 계속 거부한다.

등록 초기의 `NONE`은 다음 모두가 확인된 경우에만 **엄격한 새 대상 읽기를 요청하는 입력**으로 사용한다. NONE을 OWNED, Ready, 토큰, 실행 증거로 바꾸지 않는다.

1. 완결된 기존 readOwnedOverlayIds 호출에서 `QUERY=COMPLETE / SCAN=NO_ELIGIBLE_OVERLAY`이다. root 없음/owner·title 불일치/예외/해제 실패/전체 목록 비어 있음/여러 후보/다른 단일 후보는 제외한다.
2. 이 native generation에서 아직 원격 소유권이 확인된 적이 없다. shouldIgnore에서 실제 OWNED가 확인되거나 live 후보가 일치한 뒤에는 latch를 닫고 이후 NONE을 다시 등록 초기로 해석하지 않는다.
3. 먼저 Runtime/session/이전 실행 증거를 기존 방식으로 무효화한다. 이벤트를 무시하지 않는다.
4. 같은 기존 DISPLAY_REVIEW scan으로 실제 대상 창/root 전체를 다시 읽는다. scan의 100ms·보호 검사·같은 앱 배경 검사·경쟁 창·SYSTEM 가림·endpoint/frame/자원 해제 검사는 유지한다.
5. 그 실제 목록에도 정확한 native id가 **없어야** 한다. 도중 등록돼 보이거나 같은 id가 다른 type으로 나타나면 거부한다. 다른 accessibility overlay/입력창/다른 활성·포커스 창을 허용하지 않는다.
6. 원본 대상은 정확히 하나의 TYPE_APPLICATION이며 여전히 active AND focused다. C가 목록에 없다는 이유로 대상의 focus/active를 정규화하지 않는다.
7. 원본 대상 전체 nodes와 screen/frame, 전체 windows 목록(순서·모든 필드 포함)이 같아야 한다. 대상 교체/소멸·텍스트/버튼·bounds·frame·배경 창 변화/불완전은 제거한다.
8. 읽기 전후 같은 native id·같은 listing 상태를 다시 확인한다. 어느 시점의 해제/취소/시간 초과/현재성 변화도 유지 성공으로 승격하지 않는다.

이 경우만 `SAME / DISPLAY_RETAINED / NATIVE_LISTING=NOT_ENUMERATED`다. 원격 소유권 코드는 여전히 NONE/UNAVAILABLE이며 OWNED 로그를 만들지 않는다. 이미 LISTED인 기존 경로의 C 포커스 비교 정책은 유지한다. 단순 grace period, 지연 후 승인, polling, 자동 재시도, 이벤트 출처 추정은 없다. 원래 표시 수명 30초를 늘리거나 시작 시각을 재설정하지 않는다.

이 경로는 판정이 필요한 관측 사실을 사용하므로 Release에서도 같은 read probe를 계산한다. Debug trace의 존재에 따라 정책이 달라지지 않는다. formatter/Logcat sink는 Debug 소스에만 있다.

## 수정 2 — 실제 native 완료 뒤 복합 제거 이벤트의 cleanup 순서

`inspectManualRetirementEvent`의 사용자 선택 보존 계약은 바꾸지 않았다. 다음은 cleanup 전용이다.

- 정확한 event/native id와 등록 owner/generation, HIDDEN, package 관계, actual native removalComplete가 있을 때만 알려진 REMOVED+teardown metadata 조합을 CLEANUP_WAIT로 분류한다.
- 허용 metadata는 BOUNDS/LAYER/TITLE/ACTIVE/FOCUSED/ACCESSIBILITY_FOCUSED다. ADDED/PARENT/CHILDREN/PIP/unknown/zero는 제외한다. 불명확한 세대/ID/제거 전 조합도 계속 거부한다.
- 복합 이벤트는 removal receipt가 아니며, 선택 callback·Runtime 증거·B/F9 실행 권한을 보존하지 않는다. 서비스는 일반 handler를 실행해 기존처럼 무효화한다.
- handler가 정상 완료한 뒤에만 `processed(receipt, cleanupWaitOnly)`가 기존 nativeProcessed를 다시 호출한다. 이전에는 null receipt라 이 단계가 생략돼, 마지막 복합 이벤트 뒤 실제 부재를 확인할 기회가 없었다.
- nativeProcessed는 기존 remove 반환 + 같은 View의 실제 detach + HIDDEN + 전체 창 목록 ABSENT + 원래 5초 cleanup deadline을 모두 다시 요구한다. PRESENT는 pending이고 UNKNOWN/실패/timeout은 계속 차단된다. deadline 갱신이나 HEALTH 초기화는 없다.
- handler/receipt/native 처리 예외는 기존 synchronous event 오류 경계와 독립적인 session/Runtime 무효화에 남긴다. handler 실패 뒤 cleanup을 완료 처리하지 않는다.

실제 selected intent가 복합 이벤트 때문에 무효화되면 클릭하지 않는다. 이것을 선택 보존 예외로 넓히지 않았다. 정상적인 순수 REMOVED와 등록된 native View 완료의 기존 경로는 유지한다.

## 유지한 실행 보호와 별도 미해결

- F0와 수집 geometry/100ms 예산, F9 read/press의 엄격한 overlay 거부, SYSTEM 가림 차단 유지.
- 이번 새 listing 상태는 DISPLAY_REVIEW에만 전달된다. 일반 read()/PRESS에는 새 예외가 없다.
- 표시 유지가 F9 실행 자격을 만들지 않는다. 명시 선택 후 실제 native 제거·전체 창 부재 → quiet → 실제 새 F0 → 동일 popup/버튼 → B 재판정 → PRE_CLICK/SCREEN_MATCH → PRESS 즉시 재검사 → 최대 1회 ACTION_CLICK → 실제 원본 소멸 확인을 유지한다.
- 원본 실행 evidence 5초와 pageBeforePopup의 별도 원래 TTL, 표시 lease 30초, cleanup 5초 유지. 과거 snapshot 시각/토큰/proof를 갱신하지 않는다.
- NATIVE_ID_UNAVAILABLE는 원격 정보나 event id로 채워 넣지 않는다. native 자체 id 미확인은 계속 표시 제거/실행 차단 대상이다.
- 원격 확인 이후의 LIVE_COUNT=NONE, 읽기/해제/소유권 실패, 초기 read 도중 등록 상태 변화는 계속 차단된다.
- 원본 14개 UNOBSERVED, F0 전체 완전성/원자성, SYSTEM 정체, 실제 삼성 등록 지연 여부, 이전 SCREEN_MATCH의 실제 첫 실패는 이번 작업으로 입증하지 않는다.
- 자동 실행/자동 SKIP/F12 OFF, 위험 버튼 거부, 재시도·좌표·제스처 없음. B/C/D·공통 계약·Manifest·gradle.properties·P23·다른 worktree 변경 없음.

## 회귀의 실제 연결과 한계

`verifyRegisteredManualDisplay`는 실제 driver의 checkpoint → native-before → whole-screen → native-after 순서를 공유한다. 새 회귀는 이 함수, readOwnedOverlayIds, ManualDisplayRegistration, ManualDisplayContinuity, 실제 native lifetime, cleanup classifier/barrier를 사용한다.

기존 `P01SamsungSequenceRegressionTest`도 새 공유 경로를 사용하도록 갱신했다. C의 실제 flow/planner/keeper + B wiring/policy + A guard/lifetime/cleanup/새 F0/session/F9를 연결해 초기 remote NONE에서도 대상이 같으면 TTS 완료/6초 표시를 유지하며, 이후 명시 선택은 실제 native 부재와 새 F0/B/F9를 기다리는 것을 검사한다. Android 창·collector DTO·TTS backend·ACTION_CLICK은 fake port다. 삼성 등록 지연이나 실제 클릭/청취를 JVM에서 검증했다고 주장하지 않는다.

새 `ManualRegistrationLifecycleTest`는 초기 NONE/원격 등장/이후 NONE/세대·ID·before 불일치, root/title/해제 실패, 대상 전체 변화/포커스/경쟁 창, 취소/100ms/endpoint 간 상태 변화, 생성·제거 순서/복합 마스크/실제 detach·PRESENT·UNKNOWN·deadline·최초 실패 보존을 검사한다.

첫 대상 실행에서 기존 sequence fixture의 root 소유권 거부 시험 1개가 실패했다. flat Set만 새 reader에 넘겨 원래 ROOT_RELATION_UNCONFIRMED라는 provenance를 시험 fixture가 버렸기 때문이다. 실제 reader의 query/scan을 전달하도록 고쳤고 원격 소유권 실패 거부 기대는 유지했다. 운영 root/owner 조건을 삭제해 해결하지 않았다. 이후 관련 회귀가 통과했다.

## 삼성 재시험 준비

이번 코드는 로컬 미커밋이다. 변경본 전달 후 Windows에서 빌드한다. 기존 원격 HEAD만 빌드하면 수정이 포함되지 않는다.

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :popup-test-app:assembleDebug
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
$serial = "<삼성 테스트폰 serial>"
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial install -r .\popup-test-app\build\outputs\apk\debug\popup-test-app-debug.apk
& $adb -s $serial logcat -v brief -s A_ManualPrompt:I '*:S'
```

접근성/수집 동의/허용 앱/분석 시작은 UI에서 직접 확인한다. 설정을 adb로 강제하지 않는다. 자동 실행/자동 SKIP/F12는 OFF로 유지한다. 새 PowerShell 창에 같은 adb/serial을 설정하고:

```powershell
& $adb -s $serial shell am start -W -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01
```

TalkBack OFF/ON에서 각각 SHOWN → 6초 이상 표시/음성 완료를 확인한다. 초기 WINDOW_SET/BOUNDS가 오면 다음을 구분한다.

- 성공적인 표시 전용 재검증: DISPLAY_REVIEW RESULT=SAME, NATIVE_LISTING=NOT_ENUMERATED, 실제 LIVE_COUNT=NONE/QUERY=COMPLETE/SCAN=NO_ELIGIBLE_OVERLAY → DISPLAY_RETAINED/TARGET_RECONFIRMED. 이는 원격 OWNED나 클릭 성공이 아니다.
- LISTED가 된 뒤 NONE 또는 NATIVE_ID_UNAVAILABLE/FRAME/TREE/FOCUS/가림/timeout이면 여전히 제거돼야 한다.
- 실제 원본 팝업을 바꾸거나 닫으면 C/TTS가 무효화돼야 한다. 오래된 선택을 다시 사용하지 않는다.
- cleanup은 CLEANUP_EVENT의 MASK/REMOVED/ADDED/WAIT_CONFIRMED/RESULT와 FIRST failure, native 단계, OVERLAY_REMOVED를 확인한다. 복합 제거는 NO_RECEIPT여도 실제 native 완료+전체 부재 후 OVERLAY_REMOVED가 가능하다. 영수증만으로 성공하지 않는다.
- 이후 명시적 닫기 1회 시험에서는 기존 새 F0/B/F9/SCREEN_MATCH/CLICK_ATTEMPT/원본 CLOSED_CONFIRMED를 각각 본다. C만 없어지거나 버튼 API 반환이 true여도 원본 닫힘 성공이 아니다. PAGE_PROOF_EXPIRED 등 기존 별도 차단을 우회하지 않는다.

로그는 기존 tag의 고정 enum만 사용하고 화면 원문·좌표·패키지·창 ID·지문·token·예외를 수집하지 않는다.

## 최종 로컬 검증

최종 결과와 변경 목록은 아래에 기록한다. 기기 실행 성공 결과가 아니다.

### 완료 결과

- 신규 JVM 회귀 **24개**: `ManualRegistrationLifecycleTest` 22개 + 실제 C/B/A sequence 2개. 기존 sequence의 다른 회귀도 새 공유 driver 경계를 사용해 통과했다.
- 앱 전체 Debug **1,278개**, Release **1,263개**, 각 실패·오류·skip 0. variant 공통 시험을 고유 총수로 더하지 않는다.
- 호스트 55개/합성 앱 26개는 기존 UP-TO-DATE 결과 재사용.
- 앱 Debug/Release APK와 AndroidTest APK 빌드 성공. 호스트 Debug/AndroidTest와 합성 앱 Debug 산출물 재사용.
- 앱 Debug/Release·호스트 Lint XML issue 0.
- 기존 APK/DEX 격리 통과. 신규/기존 JVM sequence 클래스는 앱 Debug/Release DEX에 없음.
- `;NATIVE_LISTING=`, 기존 `;SCREEN_MATCH=`, A_MANUAL_ACTION/A_MANUAL_DISPLAY_REVIEW/A_F9_READ 로그 문자열은 Debug에 존재, Release에는 부재.
- P23 포함 다른 9개 worktree의 HEAD/status/기존 미커밋 파일 SHA-256 보존 확인.
- F9AutoCloser/기존 SCREEN_MATCH 판정, F9ReadDiagnostics, ManualActionTrace, AndroidSnapshotCollector, F9ServiceSession, F9EligibilityAdapter, ManualChoiceRefresh, Manifest/properties는 HEAD bytes와 동일. 실제 AndroidF9ScreenPort 변경은 DISPLAY_REVIEW 입력·표시 검증 호출 전달에 한정되며 일반 read/PRESS guard를 바꾸지 않았다.
- 공백 검사(CRLF 유지)·신규 파일 공백·정확한 변경 범위 검사 통과.
- 최종 `BUILD SUCCESSFUL in 4m 12s`, 250 tasks(38 executed / 212 up-to-date). log `/workspace/a-p01-registration-full.log`.
- 삼성·LG·에뮬레이터 실측 **미실행**. 실제 음성 출력/6초 유지/원본 닫힘은 미검증이다.

검증 명령:

```bash
JAVA_HOME=/workspace/jdk17 ANDROID_HOME=/workspace/android-sdk ./gradlew --offline --no-daemon --max-workers=1 :app:testDebugUnitTest :app:testReleaseUnitTest :capture-test-host:testDebugUnitTest :popup-test-app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest :popup-test-app:assembleDebug :app:lintDebug :app:lintRelease :capture-test-host:lintDebug
PYTHONDONTWRITEBYTECODE=1 python3 tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff --check
```

### 변경 파일 (11개)

1. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualDisplayReview.kt`
2. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualPopupConnection.kt`
3. `app/src/main/java/kr/closeforyou/accessibility/action/AndroidF9ScreenPort.kt`
4. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualRetirementEvent.kt`
5. `app/src/main/java/kr/closeforyou/accessibility/integration/OverlayCleanupBarrier.kt`
6. `app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceConnection.kt`
7. `app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt`
8. `app/src/debug/java/kr/closeforyou/accessibility/integration/ManualPromptLog.kt`
9. `app/src/test/java/kr/closeforyou/accessibility/integration/P01SamsungSequenceRegressionTest.kt`
10. `app/src/test/java/kr/closeforyou/accessibility/integration/ManualRegistrationLifecycleTest.kt` (신규)
11. `docs/A_P01_NATIVE_REGISTRATION_LIFETIME_2026-10-30.md` (신규)

NATIVE_LISTING은 직전 identity 조회에서 선택한 **표시 검증 모드**다. 단독으로 raw 전체 목록의 부재나 대상 안전성을 인증하지 않는다. RESULT=SAME인 경우에만 실제 전체 scan/endpoint/전후 native 검사도 완료됐다는 범위로 읽는다.

코드로 확인 가능한 잔여 한계: native id 자체가 없거나, 처음 원격 조회의 provenance가 불명확하거나, 실제 target/포커스/frame/창 목록이 일치하지 않으면 여전히 제거된다. REMOVED+metadata가 아닌 ADDED·미확인 마스크·native 실패의 cleanup은 계속 HEALTH를 차단한다. 실제 삼성의 남은 첫 실패 코드를 보지 않고 이를 추가로 허용하지 않는다.

HEAD `b34a2fb8d507dda470a751132029d88ec7291db5` 유지. 수정 9개·신규 2개, staged 없음. 커밋·푸시·PR·병합·다른 worktree 수정 없음.
