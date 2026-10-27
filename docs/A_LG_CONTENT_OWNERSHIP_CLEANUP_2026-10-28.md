# LG 벨벳: C 콘텐츠 소유권과 cleanup 최초 실패 분리

## 기준과 실제 증거

- 기준: `aab57b26c2b070ba983b6c5279a32f4c90b415d1`.
- 별도 worktree: `/workspace/a-lg-content-cleanup`.
- 별도 로컬 브랜치: `fix/a-lg-content-cleanup-20261028`.
- 기존 6개 worktree/브랜치와 미커밋 파일을 보존한다. 이번 수정은 커밋·푸시·PR 변경하지 않는다.
- 사용자 LG Android 12 보고: TalkBack ON/OFF 모두 C 표시, 약 0.6초 뒤 `CONTENT_INVALIDATED → PACKAGE_BLOCKED → CLEANUP_FAILED`, 같은 세션 재표시 실패, 오클릭 없음.
- 확인되지 않은 사항: 실제 이벤트가 C에서 왔는지, 소유권의 어느 비교가 실패했는지, 최초 CleanupFailure 값, 실제 native detach/창 제거 성공 여부. 세 로그의 순서만으로 같은 원인이라고 단정하지 않는다.

## 실제 코드와 최소 보완

콘텐츠 경로는 `ManualPopupConnection.shouldIgnore → inspectManualEvent → inspectManualOwnedInteraction → ManualPromptLifetime.eventBelongsToShow / readManualNativeWindow`를 사용한다. 콘텐츠는 title/token 기반 `OwnedOverlayBinding`으로 fallback하지 않는다. 그 binding과 C의 `OverlayNativeWindowLifetime` 구현은 읽기 전용으로 대조했고 수정하지 않았다.

소유권의 허용/거부 조건, short-circuit getter 순서와 시계 조회 횟수는 그대로다. OWNED는 기존 모든 검증을 통과했을 때만 기록한다. 불명확한 이벤트, 외부 앱 콘텐츠, 만료·세대 불일치·native ID 미확보·live 집합 불확실은 기존처럼 무효화한다. 패키지를 통째로 허용하거나 native ID를 추정하지 않는다. `PACKAGE_BLOCKED`는 기존 이벤트 패키지 제외 경로이며 C 소유권 실패의 원인 자체를 설명하지 않는다.

정리에는 코드상 확인한 호출 경로 차이를 보완했다. Flow의 delegate dismiss는 기존에도 `cleanup.removalReturned(HIDDEN)`를 호출하지만, 화면 무효화와 close는 직접 `overlay.dismiss()`를 호출했다. 그 경로에서 게시된 native callback보다 제거 receipt가 먼저 처리되면, C 제거 함수는 이미 반환했어도 A의 returned 표식이 없어 `REMOVE_NOT_RETURNED`로 차단될 수 있었다.

모든 A 제거 호출이 공유 `dismissManualOverlay`를 거치도록 통일했다. 실제 C dismiss 반환과 HIDDEN을 확인한 뒤에만 반환 사실을 기록한다. **이것은 cleanup 완료 표식이 아니다.** native-backed 경로는 기존 View의 removalComplete(detach/반환 포함)와 전체 창 집합의 ABSENT, deadline/receipt 조건을 유지한다. 기존 barrier의 native 미결합 receipt 경로도 수정하지 않았으며, 그 경로를 native detach의 증거라고 해석하지 않는다. RETURN/HIDDEN만으로 어느 경로에서도 permit을 만들지 않는다. REMOVE_FAILED 상태와 예외는 차단한다. 재시도·임의 ready·deadline 갱신은 없다. 이 순서 차이가 이번 LG 실패의 실제 원인이었는지는 아직 미확정이다.

C keeper가 listener RuntimeException을 잡고 제거를 계속할 수 있으므로 A가 listener/TTS 예외를 먼저 고정 코드로 기록하고 barrier를 실패 처리한다. C가 반환했다고 그 예외를 성공으로 바꾸지 않는다. 최초 예외·취소 객체는 다시 던지고, 후속 실패 통지가 최초 오류를 덮지 않도록 한다. A 종료 cleanup은 기존 CollectionReleaseBatch/CollectionResourceSafety를 사용해 나머지 단계를 각각 시도한다. B/C/D 소스·공통 계약·Manifest·gradle.properties는 변경하지 않는다.

## 콘텐츠 소유권 Debug 로그

태그는 기존 `A_ManualPrompt`, 새 메시지는 `A_MANUAL_OWNERSHIP`이다. 시도 번호 외에는 enum만 출력한다. 화면·패키지 원문, ID, 좌표, 실제 시각, 토큰·지문·예외는 저장하거나 출력하지 않는다.

| 필드 | 코드와 해석 |
| --- | --- |
| RESULT | OWNED / NOT_OWNED / UNKNOWN. NOT_OWNED는 현재 표시와의 명시적 불일치이며, 실제 외부 사용자 조작의 출처 인증은 아님 |
| CHECK | 최초 거부/중단 경계: DISPLAY_LEASE, DISPLAY_STATE, GENERATION, EVENT_TIME, PACKAGE, BEFORE_SET, NATIVE_LOOKUP, NATIVE_ID, EVENT_WINDOW, LIVE_SET 등. 전체 통과는 COMPLETE |
| EVENT_WINDOW / PACKAGE | MATCH / MISMATCH / UNAVAILABLE / NOT_CHECKED |
| GENERATION | 현재 C 상태와 A 표시 lease의 세대 비교 |
| NATIVE_GENERATION | 기존 native 객체와 현재 C 상태의 세대 비교. 앞 단계에서 멈췄으면 NOT_CHECKED |
| TIME | BEFORE_SHOW / WITHIN_SHOW / FUTURE / NOT_CHECKED |
| LEASE | ACTIVE / NOT_STARTED / EXPIRED / CLOCK_REVERSED / NOT_CHECKED |
| DISPLAY | 실제 읽은 C 상태 enum 또는 NOT_CHECKED |
| BEFORE | AVAILABLE / UNAVAILABLE / NATIVE_ABSENT / NATIVE_ALREADY_PRESENT / NOT_CHECKED |
| LIVE | SINGLE_MATCH / SINGLE_OTHER / EMPTY / MULTIPLE / UNAVAILABLE / NOT_CHECKED |
| NATIVE | REGISTERED_OBJECT / ABSENT / NOT_CHECKED. REGISTERED_OBJECT는 observer에서 받은 객체의 존재일 뿐, native add/attach 성공의 단독 인증이 아님 |
| LOOKUP | 기존 liveWindow 조회의 성공 MATCH 또는 UNAVAILABLE. 앞 단계에서 멈추면 NOT_CHECKED |

이전 단계에서 거부하면 후속 getter를 진단 목적으로 추가 호출하지 않는다. NOT_CHECKED를 통과나 불일치로 해석하지 않는다. Native lookup 실패는 C 내부의 여러 조건을 포함하므로, 객체/세대가 같아도 내부의 어떤 private 상태가 원인인지까지 단정하지 않는다. 같은 offer에서 동일 진단은 중복 출력하지 않고 최대 8개 서로 다른 코드만 기록한다. 로그 sink의 예외·취소는 정책을 바꾸지 않는다. Release는 probe를 생성하지 않고 로거도 null이다.

아래는 형식 예시이며 LG 관측 결과가 아니다.

```text
A_MANUAL_OWNERSHIP;ATTEMPT=1;RESULT=UNKNOWN;CHECK=NATIVE_LOOKUP;EVENT_WINDOW=NOT_CHECKED;PACKAGE=MATCH;GENERATION=MATCH;NATIVE_GENERATION=MATCH;TIME=WITHIN_SHOW;LEASE=ACTIVE;DISPLAY=SHOWING;BEFORE=AVAILABLE;LIVE=NOT_CHECKED;NATIVE=REGISTERED_OBJECT;LOOKUP=UNAVAILABLE
```

## cleanup Debug 로그

`A_MANUAL_CLEANUP`은 실제 barrier fail 호출과 A cleanup callback 경계에서 생성한다. 기존 `AccessibilityRuntime.cleanupFailure`의 최초 상세 enum은 그대로이며, 같은 실제 실패 값이 새 Debug 채널에도 전달된다.

- `PHASE=FIRST/CHAIN`: 서비스에서 처음 관측한 실패 / 그 뒤 관측한 서로 다른 실패. 새 OFFER에도 최초 원인은 초기화하지 않는다. 반복 행은 억제하고 최대 32개 incident만 기록한다.
- `SITE`: BARRIER, OVERLAY_REMOVE, TTS_STOP, CALLBACK_SHOWN, CALLBACK_DISMISSED, GUARDED_PRESENTER, NATIVE_CALLBACK, EVENT_HANDLER, WORKER, FLOW, SERVICE_CLOSE, TTS_CLOSE.
- `ISSUE`: BARRIER_REJECTED, EXCEPTION, CANCELLED, REMOVE_FAILED_STATE, GUARD_REJECTED, HANDLER_NOT_COMPLETED, FAILURE_CALLBACK_EXCEPTION. 서비스 handler의 비정상 종료만 알면 EXCEPTION으로 추정하지 않는다.
- `BARRIER`: 기존 CleanupFailure 값 또는 NONE. RETIREMENT_UNCONFIRMED, REMOVE_NOT_HIDDEN, RETIRED_ID_EVENT, DUPLICATE_REMOVAL, DEADLINE, REMOVE_NOT_RETURNED, RECEIPT_UNCONFIRMED, WINDOW_PRESENT, WINDOW_QUERY_UNKNOWN, REFRESH_FAILED, EXTERNAL_UNCERTAINTY.
- `NATIVE`: 기존 검사에서 관측한 진행 경계. REGISTERED / WAITING_ID / WAITING_NATIVE_COMPLETION / WAITING_WINDOW_ABSENCE / CONFIRMED / NOT_AVAILABLE. 이 값은 진단에만 쓰며 권한 판단에 사용하지 않는다.
- `FIRST_SITE`, `FIRST_ISSUE`, `FIRST_BARRIER`, `FIRST_NATIVE`: 최초 incident를 후속 로그에도 유지한다.

`WAITING_NATIVE_COMPLETION`은 기존 removalComplete의 복합 검증(detach와 native 제거 반환 등)이 충족되지 않았다는 뜻이다. C의 private detach 비트 하나가 실패했다고 추정하지 않는다. 첫 실패 뒤 실행되지 않은 검사를 새로 수행하거나 연쇄 원인을 꾸며내지 않는다. 실패한 세션은 계속 차단되며 C 창이 사라졌다는 이유만으로 복구하지 않는다.

아래는 형식 예시이며 실측 결과가 아니다.

```text
A_MANUAL_CLEANUP;ATTEMPT=1;PHASE=FIRST;SITE=BARRIER;ISSUE=BARRIER_REJECTED;BARRIER=RETIREMENT_UNCONFIRMED;NATIVE=NOT_AVAILABLE;FIRST_SITE=BARRIER;FIRST_ISSUE=BARRIER_REJECTED;FIRST_BARRIER=RETIREMENT_UNCONFIRMED;FIRST_NATIVE=NOT_AVAILABLE
A_MANUAL_CLEANUP;ATTEMPT=1;PHASE=CHAIN;SITE=GUARDED_PRESENTER;ISSUE=GUARD_REJECTED;BARRIER=NONE;NATIVE=NOT_AVAILABLE;FIRST_SITE=BARRIER;FIRST_ISSUE=BARRIER_REJECTED;FIRST_BARRIER=RETIREMENT_UNCONFIRMED;FIRST_NATIVE=NOT_AVAILABLE
```

## 우진 PC: 실제 Android 폰 설치·실행

이번 수정은 아직 로컬 worktree에만 있다. 아래는 **이 수정본을 반영한 Windows 작업 트리**에서 실행한다. 원격 aab57b2만 다시 빌드하면 새 진단은 포함되지 않는다. 이 문서 작성으로 커밋·푸시하거나 Windows 파일을 자동 변경하지 않는다.

PowerShell 1:

```powershell
Get-Location
git status --short
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :popup-test-app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'BUILD_FAILED' }
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
$serial = Read-Host 'LG 벨벳의 device 상태 serial 입력'
# unauthorized/offline이면 폰의 USB 디버깅 허용 UI를 먼저 확인. 다중 연결 시 반드시 -s 사용.
& $adb -s $serial shell getprop ro.product.model
& $adb -s $serial shell getprop ro.build.version.release
& $adb -s $serial shell getprop ro.kernel.qemu
# 실제 폰인지 확인: qemu=1은 에뮬레이터. 실제 폰의 빈 값/0만으로 모델 검증을 대신하지 않음.
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
if ($LASTEXITCODE -ne 0) { throw 'APP_INSTALL_FAILED' }
# 동일 소스의 개인정보 없는 재현 앱이 필요한 경우에만 설치. 더 최신 #59 APK를 임의로 덮지 않음.
# & $adb -s $serial install -r .\popup-test-app\build\outputs\apk\debug\popup-test-app-debug.apk
& $adb -s $serial shell am start -n kr.closeforyou/kr.closeforyou.app.MainActivity
& $adb -s $serial shell am start -a android.settings.ACCESSIBILITY_SETTINGS
```

사용자가 UI에서 수집 동의·재현 앱 허용·분석 시작·접근성 서비스·TalkBack ON/OFF를 설정한다. 자동 닫기·자동 SKIP·F12 반복은 계속 OFF다. 권한·secure settings·allowlist를 adb로 강제하거나 서명/버전 오류를 uninstall/-d로 우회하지 않는다.

PowerShell 2 (동일 `$adb`, `$serial` 설정 후; 개인정보 없는 고정 코드만 수집):

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = Read-Host '같은 LG 벨벳 serial 입력'
& $adb -s $serial logcat -v raw -s A_ManualPrompt:I '*:S' |
  Select-String '^A_MANUAL_(PROMPT|ACTION|OWNERSHIP|CLEANUP);' |
  ForEach-Object { $_.Line } |
  Tee-Object -FilePath .\lg-p01-fixed-codes.txt
# 시험 종료 시 Ctrl+C. 전체 logcat/예외 스택/사용자 화면은 수집하지 않음.
```

P01 실행 (PowerShell 1):

```powershell
# 새 독립 실행을 위한 합성 재현 앱 초기화. 이 종료는 A의 닫힘 성공으로 집계하지 않음.
& $adb -s $serial shell am force-stop kr.closeforyou.popuptest
& $adb -s $serial shell am start -W -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01 --ez f9_defer_popup true
# 일반 배경이 실제 표시되고 분석이 시작된 것을 확인한 뒤:
& $adb -s $serial shell am start -W -f 0x20000000 -n kr.closeforyou.popuptest/.PopupScenarioActivity --ez f9_show_deferred_popup true
```

1. TalkBack OFF/ON 각각 실제 C 표시와 음성을 확인한다. SHOWN 반환만으로 화면 표시 성공을 대신하지 않는다.
2. C 표시 후 손대지 않고 6초 이상 유지되는지 본다. 콘텐츠 이벤트가 오면 OWNERSHIP 결과와 첫 CHECK를 기록한다. UNKNOWN/NOT_OWNED이면 무효화가 유지되는 것이 안전 기대 동작이며 원인을 추측해 예외를 추가하지 않는다.
3. 유지된 경우에만 C 닫기 선택을 명시적으로 한 번 수행한다. C 제거, 새 F0/B/F9 재검증, 실제 원본 팝업 닫힘을 구분한다. 직접 원본 버튼을 눌러 결과를 대신 만들거나 adb input tap을 사용하지 않는다.
4. 사라짐/재표시 실패 시 가장 먼저 나온 CLEANUP FIRST와 CHAIN을 분리한다. native 완료가 불명확하거나 제거 실패면 선택/재표시는 차단돼야 한다. 같은 세션에서 무한 재시도하지 않는다.
5. 독립된 다음 모드를 시험하려면 사용자가 접근성 서비스를 중지하고 남은 C 창이 없는지 확인한 뒤 앱 프로세스를 완전히 종료·재시작하고 UI에서 다시 설정한다. Activity만 다시 열거나 서비스만 토글했다고 기존 프로세스의 실패 래치가 초기화됐다고 가정하지 않는다. 이때의 새 세션 결과를 이전 세션 복구 성공으로 기록하지 않는다. 실패 기록을 지우기 위한 자동 재연결은 하지 않는다.
6. 각 모드에서 실제 표시 여부·6초 유지 여부·최초 소유권 거부·최초 cleanup 코드·선택 접수·원본 닫힘·실제 클릭 횟수를 별도로 기록한다. 불확실하면 성공으로 집계하지 않는다.

## 검증 범위와 남은 제한

JVM은 실제 shared 소유권 검사·native lookup·cleanup barrier·공유 dismiss·C keeper·A 예외 경계를 사용한다. Android getter/WindowManager/TTS/Binder는 실제 LG 동작을 재현한 것이 아니다. 최초/연쇄 실패, null/불일치 소유권, 예외/취소 보존, partial cleanup에서 F9 클릭 0회, 진단 유무에 따른 판정·getter/시계 호출 동등성을 확인한다.

이번 실행 결과:

- 신규 26개(소유권 12, cleanup 14), 관련 대상 98개 통과.
- 앱 Debug 1,020 / 앱 Release 1,005 / 호스트 55 / 합성 앱 26: 총 **2,106 variant 실행**, 실패·오류·JUnit skip 0. 동일 앱 테스트의 Debug/Release 중복 실행을 포함한다.
- 앱 Debug/Release, 앱/호스트 AndroidTest APK, 호스트/합성 앱 Debug 빌드 성공. 변경 없는 일부 작업은 Gradle UP-TO-DATE였다.
- 앱 Debug/Release 및 호스트 Lint 이슈 0. 합성 앱 기존 TypographyFractions/SetTextI18n 경고 2개, 오류 0.
- 기존 패키징/DEX 검사 통과. 새 JVM 테스트 클래스는 운영 APK에 없고, 네 A_MANUAL 로그 마커는 Debug에만 있다. Release 로거 bytecode는 null 반환이며 Logcat sink가 없다.
- 공백 검사와 기존 worktree/미커밋 파일 해시 보존을 확인한다. 커밋·푸시·PR 변경은 하지 않는다.

원본 실행 증거 5초, 표시 lease 30초, 새 F0/B 재판정/F9 최종 검사와 기존 수집 예산을 유지한다. 원본 14개 UNOBSERVED나 Scroll/Window 문제를 해결했다고 주장하지 않는다. 실제 폰과 에뮬레이터 검증은 각각 미실행이며 이번 환경의 adb 연결 목록은 비어 있었다. 이번 LG 조기 제거의 정확한 소유권 실패와 최초 CleanupFailure는 재실측 전까지 미확정이다.
