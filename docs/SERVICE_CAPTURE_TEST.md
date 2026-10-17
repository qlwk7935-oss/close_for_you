# 실제 서비스 연결·캡처 시험: 외부 계측 드라이버

> PR #4 이후 연결 성공 및 전체 캡처 실패 조사: [세션별 진단·API 37 재시험](CAPTURE_SESSION_DIAGNOSTICS.md). 기존 BLOCK/EVIDENCE는 다른 시도의 값일 수 있으므로 새 진단 명령과 해석을 우선합니다.

2026-10-17. `main`의 `28c5a77`에서 `fix/service-test-connection` 생성.

사용자 보고(Windows, Medium Phone API 37.0): 본 앱/독립 합성 호스트 실행, BundledKoreanOcrTest 3개 통과, dumpsys의 Bound services 포함 및 Crashed services 비어 있음. 기존 서비스 시험은 10초 연결 대기에서 실패했으므로 **그 실패는 아직 화면 캡처/OCR 실패가 아닙니다**. 아래 변경은 이 클라우드에서 JVM/빌드/패키징만 검증하며 실제 기기 재시험은 별도입니다.

## 원인: 확인한 사실과 미확정 부분

- 기존 시험의 instrumentation targetPackage는 접근성 서비스가 실행되는 `kr.closeforyou`였습니다. AOSP ActivityManagerService의 일반 restart 경로는 계측 시작 때 targetPackage를 force-stop/relaunch하고 종료 때도 종료할 수 있습니다. 계측 시작 전에 본 Bound 상태는 시작 뒤 콜백 연결을 보증하지 않습니다.
- 기존 시험은 시험 classloader/프로세스의 Kotlin object인 AccessibilityRuntime.status를 직접 기다렸습니다. 매니페스트는 별도 service process를 선언하지 않으며 DEX에도 Runtime이 중복 정의되어 있지 않습니다. 하지만 dumpsys Bound는 JVM singleton 상태의 증명이 아닙니다. 사용자 API 37에서 정확히 어느 시점/프로세스/로더의 콜백이 누락됐는지는 추가 계측 없이 확정할 수 없습니다.
- UiAutomation은 기본적으로 다른 접근성 서비스를 억제합니다. 이전에도 DONT_SUPPRESS 플래그를 요청했으나 이것만으로 서비스 준비 완료를 판정하면 안 됩니다. 공개 SDK에는 실제 flags getter가 없으므로 숨겨진 API를 호출하지 않습니다. 보존 플래그 요청 뒤 실제 앱 콜백/상태를 검증합니다.
- runBlocking이 Android main을 막으면 콜백을 기다릴 수 없습니다. 새 시험은 시험 스레드가 main이 아닌지 검사하고 IPC는 Dispatchers.IO에서 실행합니다. 서비스 앱 main에는 250ms 한도의 heartbeat도 확인합니다. 연결 제한은 기존 **10초**, 결과 대기는 **20초**, 앱의 캡처/OCR/토큰 제한은 그대로입니다.

공식 근거: [AOSP startInstrumentation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerService.java), [AOSP handleCreateService/classloader](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityThread.java), [UiAutomation](https://developer.android.com/reference/android/app/UiAutomation), [Instrumentation.getUiAutomation](https://developer.android.com/reference/android/app/Instrumentation). AOSP main의 동작 근거이며 사용자의 API 37 이미지 내부 구현을 직접 실행해 검증한 것은 아닙니다.

## 변경된 실행 구조

`kr.closeforyou.capturetest.test`의 AndroidJUnitRunner가 **합성 호스트**를 대상으로 실행됩니다. 본 앱은 계측 대상이 아니므로 시험 시작/종료 때문에 서비스 프로세스를 종료하지 않습니다. 합성 Activity는 task를 새로 열어 재생성하며 서비스 앱/계측 호스트를 force-stop하지 않습니다.

실제 앱의 debug 전용 SyntheticCaptureTestProvider가 기존 AccessibilityRuntime과 서비스 콜백을 같은 앱 프로세스/로더에서 관찰합니다. 외부 드라이버는 Runtime/CorePreferences/공통 DTO 클래스를 로드하지 않고 Bundle의 고정 코드·숫자·boolean만 받습니다. Bitmap, 노드, 본문, 패키지 목록, 화면 지문은 IPC로 보내지 않습니다.

- 서비스의 onCreate/연결 시작/연결 완료/unbind/destroy/초기화 실패를 메모리에서 기록합니다. Runtime.connect는 여전히 실제 onServiceConnected 초기화 경로에서만 호출합니다. 준비 판정은 콜백 phase와 Runtime.connected, 시스템 enabled, 프로세스/로더 일치, main 응답을 모두 요구합니다.
- debug provider는 signature 권한과 고정 합성 호스트 callingPackage를 모두 검사합니다. release에는 provider/시험 권한이 없습니다. Android 접근성 권한을 대신 켜거나 shell 권한을 채택하지 않습니다.
- provider는 **사용자 설정을 읽기만 합니다**. 연결 진단은 동의가 꺼져 있어도 되지만 캡처 시험은 UI에서 직접 동의·시작한 상태이고 분석/캡처 허용 앱이 합성 호스트 하나뿐이어야 합니다. 시험이 다른 앱을 허용하거나 동의를 강제 설정하지 않습니다.
- 결과는 실제 Candidate → 현재 토큰 extract → F0-A/F0-B를 통과한 OCR에 `점검`이 있는지 앱 내부에서 확인한 boolean/코드·영역 수뿐입니다. 연결 성공만으로 OCR 성공 처리하지 않습니다.
- 시험 세션 1개만 허용하고 30초에 관측 작업/결과를 폐기합니다. END/실패 정리도 관측만 중지하며 사용자가 정한 설정은 변경하지 않습니다. 시험 후 사용자 UI에서 분석을 일시정지/동의 철회합니다.

## Windows: 빌드·설치

PR 브랜치로 전환한 뒤 개인정보 없는 새 시험 에뮬레이터 한 대만 사용합니다. JDK 17, SDK 35/Build Tools 35.0.0을 사용하며 compile/target을 API 37로 올리지 않습니다.

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug
$androidSdk = "$env:LOCALAPPDATA\Android\Sdk"
$adbPath = Join-Path $androidSdk "platform-tools\adb.exe"
& $adbPath devices
& $adbPath install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath install -r -t .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adbPath install -r -t .\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk
& $adbPath install -r -t .\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk
& $adbPath shell pm list instrumentation
```

SDK 경로가 다르면 SDK Manager 값을 사용합니다. assembleDebugAndroidTest는 외부 드라이버도 빌드하지만 **installDebugAndroidTest만으로 APK 4개가 설치되지는 않습니다**. 본 앱을 먼저 설치해야 합성 호스트의 debug signature 권한도 정상 연결됩니다. `pm list instrumentation`에서 새 runner `kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner`의 target이 `kr.closeforyou.capturetest`인지 확인합니다. 두 앱/드라이버는 같은 프로젝트 debug 서명이어야 합니다. 일반 Android 접근성 설정에서 서비스를 직접 켭니다. 앱 재설치 후 켜짐을 다시 확인합니다.

### 1. 연결만 확인 (수집·캡처 동의 불필요)

```powershell
& $adbPath shell am instrument -w -e class kr.closeforyou.servicetest.ServiceCaptureOptInTest -e captureOcrService true -e connectionOnly true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

`SERVICE_CONNECTION_READY` 후 시험 통과가 나와야 합니다. 이 모드는 이미지 캡처나 사용자 설정 변경을 하지 않습니다. 성공해도 OCR 성공은 아닙니다.

### 2. 실제 캡처·OCR

본 앱 설정에서 다음을 직접 선택합니다.

1. 접근성 화면 정보 수집·임시 분석 동의.
2. 분석 허용 앱은 **kr.closeforyou.capturetest 하나만** 추가(다른 앱은 이 시험에서 사용하지 않음).
3. 별도 캡처 동의와 해당 합성 호스트의 이미지 글자 읽기 허용.
4. 접근성 서비스 켜짐 확인 후 **허용 앱 분석 시작**.

이전 시험처럼 동의/허용 목록을 비워 두면 이제 `SYNTHETIC_SETTINGS_REQUIRED`로 명확하게 실패합니다. 시험은 동의를 대신 켜지 않습니다.

```powershell
& $adbPath shell am instrument -w -e class kr.closeforyou.servicetest.ServiceCaptureOptInTest -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

시험 중 화면 이동·터치·회전하지 않습니다. READY 이후 실제 OCR_MATCH와 OCR 영역 수 > 0이어야 통과합니다. skip/ignored를 성공으로 기록하지 않습니다. `captureOcrService=true`가 없으면 명시적 opt-in을 하지 않은 것으로 skip됩니다. 완료 후 본 앱에서 분석 일시정지/동의 철회를 합니다. 실제 메시지·은행·실제 결제 화면은 시험하지 않습니다.

기존 BundledKoreanOcrTest 3개와 호스트 독립 실행 시험은 본 앱 runner에서 그대로 실행합니다. **기존 app runner의 ServiceCaptureOptInTest 명령은 폐기**했습니다. 본 앱 대상 전체 계측 묶음과 외부 드라이버 시험을 동시에 실행하지 마세요. 본 앱 대상 계측은 서비스 프로세스를 다시 시작할 수 있으므로 완료 후 연결 전용 시험부터 확인합니다.

```powershell
& $adbPath shell am instrument -w -e class kr.closeforyou.accessibility.capture.BundledKoreanOcrTest kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner
```

## 개인정보 없는 실패 코드 해석

| 코드 | 의미·다음 확인 |
|---|---|
| WRONG_INSTRUMENTATION_TARGET / TEST_RUNNING_ON_MAIN | 잘못된 runner/스레드. 새 host runner 명령을 사용 |
| UIAUTOMATION_UNAVAILABLE | 자동화 연결 자체 실패. 캡처 실패 아님 |
| PROBE_UNAVAILABLE | debug 본 앱/권한/authority 설치 확인. release APK에는 probe 없음 |
| PROBE_PERMISSION_DENIED | 같은 debug 서명·본 앱 먼저 설치·호스트 패키지/권한 확인. 보안 검사를 제거하지 않음 |
| SYSTEM_NOT_ENABLED | 앱 내 수집 동의와 별개로 Android 접근성 설정에서 서비스 켜짐 확인 |
| CALLBACK_NOT_OBSERVED | 앱 프로브에서 실제 서비스 onCreate를 보지 못함 |
| SERVICE_CREATED_NOT_CONNECTED / SERVICE_INITIALIZING | 객체 생성/연결 초기화와 준비 완료를 구분 |
| SERVICE_UNBOUND / SERVICE_DESTROYED / SERVICE_INITIALIZATION_FAILED | 실제 생명주기 이탈 또는 초기화 실패 |
| PROCESS_MISMATCH / CLASSLOADER_MISMATCH | 실제 서비스 소유자와 probe의 프로세스/로더 불일치. ready로 승격하지 않음 |
| CALLBACK_THREAD_MISMATCH / MAIN_LOOP_UNRESPONSIVE | 연결 콜백 스레드 또는 서비스 main 응답 문제 |
| RUNTIME_DISCONNECTED | 콜백 기록과 앱 Runtime 상태 불일치 |
| SYNTHETIC_SETTINGS_REQUIRED | 연결 이후 사용자 수집·캡처 동의/시작·합성 앱 단독 허용 조건 미충족 |
| CAPTURE_RESULT_TIMEOUT | 연결 단계는 통과했지만 실제 OCR 완료 근거를 얻지 못함. 같은 합성 root 시도의 STAGE/REACHED/DETAIL/BLOCK/EVIDENCE 확인([해석](CAPTURE_SESSION_DIAGNOSTICS.md)) |

연결 실패 시 `SERVICE_CONNECTION_TIMEOUT:코드;PHASE=코드;MAIN=boolean;CREATES=횟수;CALLBACKS=횟수;FRAMEWORK=코드`만 출력합니다. dumpsys는 실패 시 메모리에서 한 번 읽어 해당 서비스의 Bound/Binding/Crashed를 파싱하고 즉시 원문을 버립니다. 다른 서비스 라벨·앱·화면·PID·클래스로더 식별자나 원본 dump를 결과/로그/파일에 붙이지 않습니다. 형식/다중 사용자 상황을 확신할 수 없으면 FRAMEWORK=UNKNOWN입니다. Bound와 앱 콜백이 다르면 불일치로 보고하며 Bound만으로 통과시키지 않습니다.

진단 코드는 관련 실패 부류를 구분하지만 사용자의 이전 실행에서 정확히 어떤 콜백이 빠졌는지를 사후 증명하지는 않습니다. 같은 API 37에서 새 결과를 기록해야 합니다. 타임아웃을 늘리거나 연결 플래그를 강제로 설정하지 않습니다.

## 검증 범위

순수 JVM 시험은 ready 조건·콜백 상태·설정 보호와 합성 dumpsys의 Bound/Enabled/Binding/Crashed/불명·다중 사용자/원문 미출력을 검사합니다. 기기 콜백·바인딩·캡처 성공의 증거는 아닙니다. 두 계측 APK를 컴파일하고 DEX 의존성을 검사하며, release APK에서 debug probe가 빠지는 것도 별도로 확인합니다. 로컬/CI 최종 결과는 [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md)에 기록합니다. 이 환경에는 기기/에뮬레이터/KVM이 없어 실제 실행 성공은 주장하지 않습니다.
