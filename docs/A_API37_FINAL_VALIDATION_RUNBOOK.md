# A 최종 API 37 검증 인계 — 실측과 로컬 검증 분리

## 이번 실행의 환경과 증거

2026-10-21 UTC 확인: `adb devices -l`의 연결 기기0대. 이 Linux 환경에는 emulator 실행 파일·system image·KVM이 없고, Windows 마운트/PowerShell/WSL/연결된 PC 터미널 또는 구성된 원격 Windows 접속 경로도 없습니다. 사용자 PC에 있는 Android Studio에는 직접 접근하지 못했습니다. 동의·접근성 설정을 우회하거나 미확인 주소로 원격 접속하지 않았습니다.

따라서 **이번 API37 계측·APK 기기 설치·P01/P13 수동 시험은 모두 미실행**입니다. 기존 사용자 실측의 원본14개 UNOBSERVED=14, Prepared Dialog/독립 LAYOUT_CHANGE 성공, ScrollClip의 화면 안팎 차이를 새 APK의 성공으로 승계하지 않습니다. 새 Android 실패 원인을 확인하지 못했으므로 추측으로 운영 코드를 변경하지 않습니다.

| 코드 구분 | 실행할 고정 코드 커밋 | 의미 |
|---|---|---|
| A7 원본14개 | `5db1dbf080febdfc59fb774e431a73e3a41798d2` | 기존 collector/진단/fixture/정답 그대로 |
| A→B→C 비실행 통합 | `759ba4e12823bdb345561b56b90b9740762cccd4` | A7·B19·C18 선택 코드와 원래 비실행 gate의 로컬 조립 |
| A10/F9 준비 | `e77734428614719d5dc5f02f9cd10226d02d22c2` | reader는 미연결, productionReady=false |

다음 문서와 실제 클래스/메서드/매니페스트를 대조했습니다: [원본14개](F1_DETECTION_VALIDATION.md), [baseline 격리](F1_BASELINE_ISOLATION.md), [서비스 연결](SERVICE_CAPTURE_TEST.md), [bounds 조사](F1_BOUNDS_INVESTIGATION.md), [비실행 조립](LOCAL_NONEXECUTING_INTEGRATION.md). compile SDK35와 실행 API37은 별개이며 SDK/정답/시간 한도를 바꾸지 않습니다.

## 1. 소스와 APK 전달

소스 인계 ZIP을 풀면 `a-api37-final` 디렉터리에 `a-api37-source.bundle`, 이 문서, `verification.json`, `SHA256SUMS.txt`가 생성됩니다. 선택적인 별도 APK ZIP을 같은 상위 경로에 풀면 `apks/f1/*`, `apks/integration/*`가 추가됩니다. 소스 ZIP만으로 Windows 빌드·실행 절차를 수행할 수 있습니다. bundle은 기존 PR을 원격으로 바꾸지 않고 로컬 통합 커밋까지 옮기는 용도입니다. manifest의 source/code SHA와 파일 SHA-256은 빌드 출처·파일 무결성 확인용이며 화면 지문이나 F0 안전 증명이 아닙니다. signing private key·사용자 설정·화면·raw Logcat은 포함하지 않습니다.

사전 빌드 APK는 클라우드 debug key로 서명됐습니다. Windows에서 기존에 설치한 APK와 키가 다르면 `install -r`가 거부될 수 있습니다. 이 경우 **자동 uninstall/데이터 삭제/권한 우회 없이 중단**하고, 아래처럼 Windows의 기존 debug key로 소스에서 빌드합니다. 클라우드 APK는 새 전용 에뮬레이터에서만 선택적으로 사용합니다. 서로 다른 source/key의 본 앱·호스트·runner를 혼합하지 않습니다.

아래 경로는 사용자 PC의 실제 경로로 한 번 지정합니다. 기존 브랜치를 switch/reset/stash하거나 기존 worktree에 파일을 덮지 않습니다. 대상 worktree 경로와 fetch ref prefix는 비어 있어야 합니다.

```powershell
$repoPath = 'C:\dev\close_for_you'                 # 기존 저장소
$handoffPath = 'C:\dev\a-api37-final'              # 인계 ZIP을 푼 경로
$f1Path = 'C:\dev\close_for_you-api37-f1'           # 새 경로
$integrationPath = 'C:\dev\close_for_you-api37-integration' # 새 경로
$sdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' # SDK Manager의 실제 경로
$adbPath = Join-Path $sdkPath 'platform-tools\adb.exe'
$bundlePath = Join-Path $handoffPath 'a-api37-source.bundle'
$refPrefix = 'refs/remotes/a-api37-handoff'

function Invoke-CheckedNative {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'NATIVE_COMMAND_FAILED_STOP' }
}

Invoke-CheckedNative -Program git -Arguments @('-C', $repoPath, 'status', '--short')
if ((Test-Path $f1Path) -or (Test-Path $integrationPath)) { throw 'WORKTREE_PATH_EXISTS_STOP' }
foreach ($name in @('f1','integration','f9')) {
    & git -C $repoPath show-ref --verify --quiet "$refPrefix/$name"
    if ($LASTEXITCODE -eq 0) { throw 'HANDOFF_REF_EXISTS_STOP' }
    if ($LASTEXITCODE -ne 1) { throw 'HANDOFF_REF_QUERY_FAILED_STOP' }
}
Invoke-CheckedNative -Program git -Arguments @('-C', $repoPath, 'bundle', 'verify', $bundlePath)
Invoke-CheckedNative -Program git -Arguments @('-C', $repoPath, 'fetch', $bundlePath,
    "refs/heads/feature/f1-popup-detection:$refPrefix/f1",
    "refs/heads/local/a-integration-nonexecuting-20261022:$refPrefix/integration",
    "refs/heads/local/a-action-reader-prep-20261022:$refPrefix/f9")
Invoke-CheckedNative -Program git -Arguments @('-C', $repoPath, 'worktree', 'add', '--detach', $f1Path,
    '5db1dbf080febdfc59fb774e431a73e3a41798d2')
Invoke-CheckedNative -Program git -Arguments @('-C', $repoPath, 'worktree', 'add', '--detach', $integrationPath,
    '759ba4e12823bdb345561b56b90b9740762cccd4')
```

Android Studio의 Gradle JDK와 터미널의 JAVA_HOME은 JDK17을 사용합니다.

현재 PowerShell 세션에 SDK 경로를 지정합니다. 기존 Manifest/gradle.properties/local.properties·작업 내용은 수정하지 않습니다.

```powershell
$env:ANDROID_HOME = $sdkPath
Invoke-CheckedNative -Program $adbPath -Arguments @('devices','-l')
$serial = 'emulator-5554' # 실제 개인정보 없는 전용 API37 에뮬레이터만 선택
$api = (& $adbPath -s $serial shell getprop ro.build.version.sdk) -join ''
if ($LASTEXITCODE -ne 0 -or $api.Trim() -ne '37') { throw 'API37_DEVICE_REQUIRED_STOP' }
```

동일 장치라도 emulator image·방향·TalkBack ON/OFF·코드 SHA를 실행 기록에서 구분합니다. 실제 serial을 결과 공유물에 복사할 필요는 없습니다. 연결/빌드/서명 오류는 아래 시험의 성공이나 F1 미탐으로 집계하지 않습니다.

## 2. 최신 A7 APK 빌드·설치와 실제 UI 동의

```powershell
Push-Location $f1Path
try {
    Invoke-CheckedNative -Program '.\gradlew.bat' -Arguments @('--no-daemon',
        ':app:assembleDebug', ':app:assembleRelease', ':app:assembleDebugAndroidTest',
        ':capture-test-host:assembleDebug', ':capture-test-host:assembleDebugAndroidTest')
    Invoke-CheckedNative -Program $adbPath -Arguments @('-s',$serial,'install','-r','-t',
        '.\app\build\outputs\apk\debug\app-debug.apk')
    Invoke-CheckedNative -Program $adbPath -Arguments @('-s',$serial,'install','-r','-t',
        '.\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk')
    Invoke-CheckedNative -Program $adbPath -Arguments @('-s',$serial,'install','-r','-t',
        '.\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk')
} finally { Pop-Location }
```

API37 전용 에뮬레이터의 Android UI에서 다음을 직접 수행합니다.

1. 본 앱을 열고 화면 정보 사용 설명을 확인한 뒤 **접근성 화면 정보 수집·임시 분석 동의**를 선택합니다.
2. **접근성 설정 열기** → ‘닫아줄게’ 서비스를 직접 켭니다. 외부 설치 앱의 제한된 설정 해제도 필요하다면 Android UI에서 직접 판단합니다.
3. 허용 앱은 **`kr.closeforyou.capturetest` 하나만** 둡니다. 다른 시험 앱도 이 구간에는 허용하지 않습니다.
4. **임시 화면 캡처·한국어 글자 분석 동의 OFF**, 캡처 앱 목록 비어 있음을 확인합니다. `captureOcrService=true`는 기존 preflight 인자 이름일 뿐 OCR 동의가 아닙니다.
5. **허용 앱 분석 시작**을 누릅니다. 원본 KEYBOARD 시나리오를 위해 Android UI에서 화면 키보드 표시를 준비합니다. 실제 계정·암호·금융 앱은 열지 않습니다.

동의/서비스/allowlist를 `settings put`, `appops`, `pm grant`, `run-as`, 파일 편집으로 설정하지 않습니다. 테스트 중 앱을 터치하거나 TalkBack으로 탐색하는 호환성 시험은 원본 suite와 분리합니다.

## 3. 연결 → baseline 격리 → 원본14개

연결만 확인하며 캡처하지 않는 기존 메서드입니다.

```powershell
& $adbPath -s $serial shell am instrument -w `
  -e class kr.closeforyou.servicetest.ServiceCaptureOptInTest `
  -e captureOcrService true -e connectionOnly true `
  kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

**SERVICE_CONNECTION_READY와 OK (1 test)를 모두 요구**합니다. 연결 실패/skip이면 후속 결과를 F1 검증으로 계산하지 않습니다. 세션·Activity 정리 실패가 있으면 다음 시험을 겹쳐 실행하지 않습니다.

```powershell
& $adbPath -s $serial shell am instrument -w `
  -e class kr.closeforyou.servicetest.ServiceBaselineIsolationOptInTest `
  -e f1Isolation true -e captureOcrService true `
  kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner

& $adbPath -s $serial shell am instrument -w `
  -e class kr.closeforyou.servicetest.ServiceDetectionScenariosOptInTest `
  -e f1Detection true -e captureOcrService true `
  kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

두 시험은 독립 기록입니다. baseline 격리의 관측 완료가 원본14개의 baseline을 대신하지 않습니다. 원본 suite 내부의 각 BASELINE 실패는 그 CASE의 STIMULUS 성공으로 전환할 수 없습니다. 이전 `UNOBSERVED=14`와 이번 14개 F1_CASE/F1_SUMMARY를 별도로 비교합니다. adb 종료 코드0만으로 JUnit 성공이라 하지 말고 **OK/FAILURES/skip와 실제 SUMMARY·CASE를 함께 확인**합니다. 원래 기대값/8초 등 기존 suite 관측 제한은 그대로입니다.

공유할 자료는 기존 formatter의 `SERVICE_CONNECTION_READY`, `F1_ISOLATION_*`, `F1_EVIDENCE`, `F1_ATTEMPT`, `F1_CASE`, `F1_SUMMARY`, JUnit 성공/실패 여부뿐입니다. raw Logcat·dump·노드 원문·좌표·window ID·토큰·화면 지문·예외 원문을 저장하지 않습니다.

| 같은 CASE/PHASE/ATTEMPT의 결과 | 해석 |
|---|---|
| BLOCKED / SNAPSHOT_PASS / MALFORMED_NODE_BOUNDS | 노드 geometry 변환 전 전체 차단. F1 후보 미평가 |
| ASSESSED / UNCERTAIN / INVALID_GEOMETRY / WINDOW_OUTSIDE_FRAME | 완전 수집 뒤 detector geometry 거부. 별개 시도와 원인 결합 금지 |
| TARGET_MISSING | 그 수집의 root/window 목록 불일치. 전환 원인이라고 단정하지 않음 |
| TIME_LIMIT | 기존100ms/방문 예산 실패. 시간 확대/자동 재수집 금지 |
| PROTECTION_PASS / PASSWORD·EDITABLE_FIELD·SENSITIVE_CONTENT | 해당 보호 차단. 일반 수집 실패를 이 성공으로 대체하지 않음 |
| ASSESSED / POPUP_CANDIDATE | 해당 ATTEMPT에서 후보 근거 관측. B/C 전달·광고 확정·실행 허가 아님 |
| BASELINE_BLOCKED / UNOBSERVED | 미관측 실패. true negative/정확도 성공으로 계산하지 않음 |

## 4. 통합 APK로 전환하고 P01 수동 검증

먼저 본 앱 UI에서 **화면 분석 일시정지**합니다. A7 APK로 시작한 관측 세션/계측이 끝났는지 확인한 뒤 통합 APK로 교체합니다. 다음은 **통합 worktree**에서만 실행합니다.

```powershell
Push-Location $integrationPath
try {
    Invoke-CheckedNative -Program '.\gradlew.bat' -Arguments @('--no-daemon',
        ':app:assembleDebug', ':app:assembleRelease', ':app:assembleDebugAndroidTest',
        ':capture-test-host:assembleDebug', ':capture-test-host:assembleDebugAndroidTest',
        ':popup-test-app:assembleDebug')
    foreach ($apk in @(
        '.\app\build\outputs\apk\debug\app-debug.apk',
        '.\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk',
        '.\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk',
        '.\popup-test-app\build\outputs\apk\debug\popup-test-app-debug.apk')) {
        Invoke-CheckedNative -Program $adbPath -Arguments @('-s',$serial,'install','-r','-t',$apk)
    }
} finally { Pop-Location }
```

필요한 설정은 Android UI에서 다시 확인합니다.

- 서비스 실제 연결·수집 동의를 확인하고 허용 앱을 **kr.closeforyou.popuptest 하나만** 두어 분석을 시작합니다. capturetest는 허용 목록에서 제외합니다. 캡처/OCR 동의는 OFF이며 마이크·기억·자동 닫기는 켜지 않습니다.
- Android 설정 UI에서 로컬 한국어 TTS voice를 준비합니다. 선택 voice의 non-network/installed 확인에 실패하면 A는 UNAVAILABLE로 처리합니다. 네트워크 voice fallback이나 설정 파일 편집은 하지 않습니다.
- 기존 Core 화면의 ‘개발 중’ 설명은 런타임 상태 증거가 아닙니다. 실제 오버레이·음성과 아래 결과를 확인합니다.

```powershell
# STATUS만 확인하는 기존 연결 preflight. BEGIN/RESULT는 시작하지 않습니다.
& $adbPath -s $serial shell am instrument -w `
  -e class kr.closeforyou.servicetest.ServiceCaptureOptInTest `
  -e captureOcrService true -e connectionOnly true `
  kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner

& $adbPath -s $serial shell am start -W -f 0x10008000 -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01
```

P01은 host 코드의 onCreate에서 실제 Dialog를 표시합니다. 위 flags는 D 합성 task만 새로 만들어 기존 Activity/onNewIntent 재사용을 피하며, 서비스·동의·detector를 초기화하지 않습니다. 목록 Activity의 ScrollView를 거쳐야 한다고 가정하지 않습니다. 표시했다는 사실만으로 A 감지 성공이라 하지 않습니다. 사용자 선택은 사람이 **C 선택 창의 닫기 요청**에서 직접 합니다. 원래 D Dialog의 닫기 버튼을 누르거나 adb/UiAutomator로 선택을 대신하지 않습니다. A는 실제 버튼을 누르지 않으므로 원래 Dialog가 남고 C가 ‘누르지 않았어요’ 계열의 결과를 안내하는 것이 코드상 기대입니다. 기계 상태인 NOT_EXECUTED/attempted=false를 읽어 낸 Android counter가 현재 제공된다는 뜻은 아닙니다.

각 항목은 별도의 새 시험으로 기록합니다. 기존 후보 TTL은 발행 후5초 그대로이며, TalkBack 탐색 중 만료되면 실패/사용성 문제를 그대로 기록하고 연장하지 않습니다.

| 항목 | 관측할 것 | 실패/미검증 구분 |
|---|---|---|
| 첫 구독 | 코드/JVM의 등록 장벽 + 실제 첫 안내 | UI만으로 등록 순서까지 원자적으로 입증할 수 없음 |
| A 후보→B→C | C 창에서 해당 합성 Dialog의 안내·허용 라벨 표시 | Dialog만 있고 C가 없으면 어느 경계인지 아직 미확정 |
| 사용자 선택 | 현재 C 선택만 처리, 반복 실행/기억/마이크 옵션 비활성 | 다른 창의 선택/만료된 선택은 실행 요청으로 받아들이지 않음 |
| 미실행 결과 | 원래 Dialog 유지 + 미실행 안내, 자동 반복 없음 | Dialog 유지나 D 로그 공백만으로 API 호출0회 계측을 대체하지 않음 |
| TTL·화면 변경 | 새 후보를 각각 열어5초 만료/앱 전환 시 UI·음성 정리 | 이전 화면의 안내/선택 유지나 재등장 실패를 숨기지 않음 |
| 연결 종료·재연결 | Android UI에서 서비스 OFF/ON 후 이전 UI·음성·선택 폐기 | 설정을 shell로 변경하지 않음 |
| 자체 오버레이 | 표시/제거 중 무한 반복·안내 재시작 여부 | 불명확 touch/창 제거 이벤트는 현재도 무효화. 광범위 ignore로 우회 금지 |
| TalkBack | 별도 반복에서 탐색/안내/포커스 간섭을 직접 확인 | 포커스 이동·실제 청취·동일 token/ATTEMPT 결합은 화면만으로 증명 못 함 |

**관측 경계 제한:** SyntheticDetectionTestProvider의 BEGIN/RESULT는 capturetest 단독 허용·고정 호출자 계약입니다. popuptest를 허용한 P01에서 이 API를 열기 위해 보안을 바꾸거나 소유 window를 대체하지 않습니다. 현재 제공된 도구로 P01의 모든 A/B/C 경계를 같은 서비스 ATTEMPT 행으로 원자적으로 추적할 수는 없습니다. 출력이 부족하면 UNVERIFIED로 남기고 UI 부재를 A/B/C 중 한 곳의 결함으로 추정하지 않습니다. 새 진단 hook이나 가짜 결과를 만들지 않습니다.

## 5. 선택적 P13 W01과 안전 거부

```powershell
& $adbPath -s $serial shell am start -W -f 0x10008000 -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P13
```

기존 P13은 host의 고정 asset 페이지를 표시합니다. 필요하면 전용 합성 페이지의 W01 버튼을 사람이 누르며, 실제 웹/계정/금융 사이트로 이동하지 않습니다. 동일 앱 배경의 결제·동의 문구 때문에 B가 USER_DECISION/IMPORTANT_DECISION으로 거부하는 알려진 동작을 완화하지 않습니다. B22 덤프 결과를 이번 A 서비스 성공으로 재사용하지 않습니다. A 수집이 먼저 차단되면 B의 실제 거부는 미관측이며, ‘B가 안전 거부했다’고 대신 기록하지 않습니다.

## 6. 실패 시 기록과 종료

| 경계 | 안전한 확인 수단 | 확인 불가이면 |
|---|---|---|
| 연결/동의/allowlist | Core UI + 기존 connection-only 고정 코드 | SETTINGS/CONNECTION_UNVERIFIED |
| A7 수집·detector | 기존 F1_ATTEMPT의 같은 행 | UNOBSERVED, 다른 행·host 샘플 결합 금지 |
| B 후보·C UI | 실제 선택 창·고정 안내 + 코드/JVM 대조 | BOUNDARY_UNVERIFIED, 원문 dump 금지 |
| TTS | 로컬 voice 지원과 실제 재생을 구분 | SPEECH_UNVERIFIED, 네트워크fallback 금지 |
| A gate 미실행 | reachable 코드에 Android 행동 port 없음 + 공유 JVM | Android API 호출 counter 실측은 NOT_MEASURED |

시험 후 본 앱 UI에서 분석을 일시정지하거나 동의를 철회합니다. 설정/서비스를 원상 복구할 때도 사람이 직접 합니다. 세션·Activity·flags 복원 실패, 취소, 서명/설치/연결 오류를 성공으로 숨기지 않습니다. C private View 제거 실패 복구와 신뢰 가능한 선택 출처, B14 라벨 계약·B/D popup identity/시도 이력은 공동 합의 항목입니다. 실제 F0 완전성·현재성·TTL·같은 시도 지문 결합·F1 일반 정확도·OCR·최종 행동 안전성은 여전히 미입증입니다.

## 7. 이번 환경의 결과표

| 시험 | 이번 실행 |
|---|---|
| API37 연결·기기 APK 설치 | NOT_RUN / NO_DEVICE_ACCESS |
| baseline 격리 | NOT_RUN / NO_DEVICE_ACCESS |
| 원본14개 | NOT_RUN. 이전 사용자 UNOBSERVED=14 유지 |
| P01 A→B→C 실제 화면·음성·선택 | NOT_RUN / NO_DEVICE_ACCESS |
| P13 실제 A 수집 이후 B 거부 | NOT_RUN / NO_DEVICE_ACCESS |
| Android 클릭·제스처 호출 counter | NOT_MEASURED. 코드/JVM의 비실행 근거와 구분 |

이 표의 미실행을 성공/실패 횟수에 추가하지 않습니다. 소스·APK checksum과 별도의 verification.json에 로컬 재실행 결과를 기록하며 Android 실측 결과와 혼합하지 않습니다.


## 8. 이번 로컬 재검증과 Git 보존

아래는 이번 작업에서 JDK17로 다시 실행한 결과입니다. 앞선 보고의 테스트 수를 복사한 결과가 아닙니다. JVM 작업은 `--rerun`으로 실행했으며 실패·오류·skip은 모두0입니다.

| 코드 | 앱 JVM | 호스트 JVM | D JVM | 합계 |
|---|---:|---:|---:|---:|
| A7 | 563 | 436 | 해당 없음 | 999 |
| A10/F9 | 197 | 55 | 해당 없음 | 252 |
| A→B→C 통합 | 878 | 436 | 13 | 1,327 |

세 worktree 모두 앱 Debug/Release, 앱 AndroidTest APK, 호스트 Debug/AndroidTest APK 빌드가 통과했습니다. 통합의 D Debug APK도 빌드했습니다. 앱 Debug/Release·호스트 Debug Lint의 XML issue는 각각0이며, 각 브랜치의 `verify_capture_test_apks.py`와 추가 DEX 격리 검사가 통과했습니다. 통합 코드의 서비스 연결 클래스는 통합 APK에만, F9 reader는 F9 APK에만 있고 JVM 테스트 클래스는 APK에 포함되지 않습니다. A 접근성 소스의 `performAction`/`dispatchGesture`/`performGlobalAction` 직접 호출 검색은0건이며, 이는 Android 런타임 호출 횟수 측정과 다릅니다.

실측 근거가 없는 운영 수정이나 테스트 추가는 하지 않았습니다. 기존 A7/A10 커밋과 B19/C18 원격 브랜치는 보존하며, 이번 인계 문서 변경만 통합 로컬 브랜치의 별도 커밋으로 남깁니다. 통합의 main 대비 diff에는 아직 미병합인 B/C 코드가 포함되므로 A 단독 PR처럼 제출하거나 원격으로 push하지 않습니다. 기존 PR의 성공 CI를 이번 문서 커밋의 새 CI로 해석하지 않습니다.

남은 A 실행 작업은 접근 가능한 전용 Android 환경에서 위 시험을 실제로 수행하고, 관측된 A 결함에 한해 수정·재검증하는 것입니다. 기기 없이 원본14개·P01/P13·TalkBack·서비스 종료 이벤트 순서를 완료 처리할 수 없습니다. C의 View 제거 복구·선택 출처, B 라벨/팝업 identity/시도 이력은 담당자와 합의가 필요하며, F9는 계속 `productionReady=false`와 비실행 상태입니다.
