# Dialog OCR 영역: 화면 좌표 출처 수정과 API 35/37 재시험

> PR #6 기기 후속 결과 BUFFER_GEOMETRY 조사·버퍼 전용 계측 및 최신 Windows 명령은 [WINDOW_CAPTURE_BUFFER.md](WINDOW_CAPTURE_BUFFER.md)를 우선합니다.

기준: PR #5 병합 main `f72fd0f` → `fix/dialog-capture-coordinates`. 실제 기기 성공 보고서가 아닙니다.

## 사용자 관측과 확인된 실패 분기

Windows Medium Phone API 37.0의 사용자 보고:

```text
STAGE=PUBLISHED
REACHED=COLLECTION,POPUP_DETECTED,F0_A,F0_A_PASSED,REGION,PUBLISHED
BLOCK=NONE; EVIDENCE=REGION_UNCERTAIN
DETAIL=REGION_NO_BOUNDED_DIALOG; GEOMETRY=OUTSIDE
API=NONE; TARGET=APPLICATION; EVENT_WINDOW=SAME
```

이 결과로 확인되는 것은 다음과 같습니다.

- 접근성 수집/후보 검출/초기 F0-A를 통과했습니다. 캡처 API·OCR·F0-B는 아직 실행되지 않았습니다. PUBLISHED는 안전한 트리만 담은 부분 결과 발행이며 OCR 성공이 아닙니다.
- 이전 geometry 함수에서 OUTSIDE는 `contains(snapshot.screenBounds, targetWindow.bounds)`가 false라는 뜻입니다. 창이 비어 있는 경우는 먼저 EMPTY를 반환하므로, 이번 창 경계는 양수 크기지만 좌/상/우/하 중 적어도 한 경계가 저장된 화면 경계 밖이었습니다.
- 이전 resolver는 창 경계가 화면 안에 완전히 포함되면서 최소 32×24px, 화면 면적의 92% 미만인지 검사했습니다. OUTSIDE면 이 경로를 통과할 수 없어서 className에 `dialog`가 들어가는 노드 중 창·화면 안의 제한 영역을 찾았습니다. `REGION_NO_BOUNDED_DIALOG`은 **이 조건을 모두 만족하는 노드가 0개**였음을 뜻합니다. Dialog 클래스 노드 자체가 없었는지, 있었지만 크기/경계가 잘못됐는지는 이전 결과만으로 구별할 수 없습니다.
- EVENT_WINDOW=SAME은 이벤트 창 ID와 active root 창 ID가 같다는 뜻이며, Dialog인지 배경 Activity인지의 증명은 아닙니다. APPLICATION 유형도 둘 다 가질 수 있습니다.

**아직 모르는 것:** 사용자의 이전 서비스 metrics가 빈 경계였는지, 회전/컨텍스트 설정 때문에 작아졌는지, 실제 Dialog가 화면 밖이었는지. 좌표 원문이 없으므로 어느 쪽 경계가 얼마나 벗어났는지는 복원할 수 없습니다. 이 환경은 기기/에뮬레이터가 없어 API 37 실행 원인을 실측으로 확정하지 않았습니다.

## 좌표계 조사와 수정 전후

| 항목 | 이전 | 수정 후 |
|---|---|---|
| 화면 범위 출처 | 일반 AccessibilityService 컨텍스트의 maximumWindowMetrics를 화면 전체라고 가정 | 기본 디스플레이를 명시한 TYPE_ACCESSIBILITY_OVERLAY **WindowContext**의 maximumWindowMetrics 사용. 실제 overlay 창은 추가하지 않음 |
| 출처 검증 | 별도 검증 없음 | DisplayManager의 기본 논리 디스플레이 getRealSize와 원점/크기 교차 확인, 읽기 전후 크기·회전 동일성 확인. 하나라도 불일치하면 차단 |
| 예전 metrics 비교 | 확인 불가 | debug 세션에서만 새 값과 SAME/DIFFERENT/EMPTY/UNAVAILABLE 비교. 좌표 원문 보유/출력 없이 벗어난 방향만 표시 |
| 창 경계가 화면 밖 | Dialog 노드 fallback 실패가 NO_BOUNDED_DIALOG로 묶임 | 즉시 REGION_WINDOW_OUTSIDE로 차단. 내부 노드를 이용해 이 창을 우회하지 않음 |
| Android Dialog 창 경계 | 이미 bounded window 경로가 있었지만 잘못된 화면 범위와 비교될 수 있었음 | 검증된 화면 안의 active APPLICATION 창 원본 bounds를 그대로 사용. Dialog라는 노드 className을 요구하지 않음 |
| 화면 안의 큰 창 fallback | 조건을 만족하는 노드가 없으면 한 코드 | 클래스 노드 부재/노드 경계 이탈/큰 노드/여러 후보를 별도 코드로 구분 |
| 합성 Dialog/Activity 식별 | 패키지와 APPLICATION 유형만 확인 | 호스트의 Activity/Dialog에 서로 다른 고정 제목을 설정하고 debug에서 역할 enum만 전달. 제목은 캡처 허가 근거가 아님 |

Android 공식 문서상 maximumWindowMetrics는 **해당 컨텍스트가 차지할 수 있는 최대 창 영역**이며 실제 전체 디스플레이보다 작을 수 있습니다. 일반 Service/Application은 UI 컨테이너 설정을 보장하지 않습니다. AOSP Android 15 WindowMetricsController도 Context Resources의 WindowConfiguration.maxBounds를 읽습니다. 따라서 기존 출처를 다른 앱의 접근성 screen 좌표와 비교한 것은 근거가 부족한 가정이었습니다. 이번 수정은 API 37만 예외 처리하지 않고 올바른 컨텍스트를 사용합니다.

WindowMetrics.bounds에는 시스템 바 영역이 포함되며 insets가 이미 빠진 값이 아닙니다. AccessibilityWindowInfo와 AccessibilityNodeInfo의 getBoundsInScreen도 screen 좌표입니다. 이를 섞으면서 status/navigation bar 높이를 빼거나 더하지 않습니다. dp 변환·비율 보정·좌표 원점 이동·사각형 교집합·노드 경계의 합집합도 사용하지 않습니다. 노드 screen 좌표에는 확대 배율이 적용될 수 있으므로 기존 magnification 차단도 유지합니다.

getRealSize는 API 31부터 deprecated이지만 여기서는 **레이아웃 계산/대체 화면 크기가 아닌 논리 크기 교차 검사**에만 사용합니다. 물리 패널 해상도와 같다고 주장하지 않습니다. 파티션·호환 모드에서도 영향을 받을 수 있으므로 일치하지 않으면 큰 쪽을 고르지 않고 실패합니다. 원점이 0이 아닌 영역, 보조 디스플레이, 출처 불일치의 임의 좌표 변환은 지원하지 않습니다. 회전·크기 변경 시 WindowContext 캐시를 갱신하고 서비스 설정 변경 때도 초기화합니다.

기본 Dialog는 View 트리의 className이 꼭 `android.app.Dialog`일 필요가 없습니다. AOSP Dialog가 클래스명을 넣는 곳은 dispatchPopulateAccessibilityEvent의 **이벤트**입니다. 이벤트 클래스와 노드 클래스를 혼동해 노드 값을 강제로 Dialog로 바꾸지 않았습니다. 호스트는 이미지·버튼·창 크기(420px 높이, 기존 폭 계산)를 유지하고 제목만 추가했습니다. 제목에 OCR 정답 단어 `점검`을 넣지 않아 제목 OCR만으로 통과하지 않게 했습니다.

## 캡처와 보호 유지

AndroidSnapshotCollector가 새 경계를 PopupSnapshot.screenBounds에 넣고 기존 후보/파이프라인으로 전달합니다. 공통 모델·포트 시그니처는 유지합니다. F0-A 재수집 때도 같은 출처를 재검증하며 창/노드/화면 경계 변경은 기존 토큰 비교로 무효화합니다.

API 34+는 계속 takeScreenshotOfWindow만 사용합니다. AndroidScreenshotManager의 창/버퍼 크기 일치, 영역 포함, 확대·보안 창·빈 이미지·메모리/빈도 제한과 캡처 후 F0-A/F0-B는 유지합니다. API 30~33의 기존 제한된 전체 디스플레이 경로로 API 35/37 실패를 우회하지 않습니다. 이미지나 좌표를 저장하지 않으며 가짜 OCR 결과도 추가하지 않았습니다.

시스템 창·IME·외부 앱·가림 판정은 그대로입니다. 새 경계 검증은 캡처 동의/앱별 허용/개인정보 검사와 독립입니다. FRAME=VERIFIED나 ROLE=SYNTHETIC_DIALOG만으로 캡처 또는 자동 실행을 허용하지 않습니다. 보안/입력 보호, B·C·D의 판단 책임, F6 제외와 F10/F12/F13 후속 구현 상태도 유지합니다.

## 새 진단 해석

아래 필드는 같은 합성 root 시도에 묶이며 원문·수치 좌표·실제 창 ID·지문·제목 문자열을 IPC/일반 로그로 보내지 않습니다. 노드 재검사가 여러 번 실행되면 같은 시도의 마지막 검사 관계가 표시됩니다.

| 필드 | 의미 |
|---|---|
| FRAME=VERIFIED | WindowContext의 양수 크기·0 원점과 논리 크기 일치, 읽기 전후 표시 상태 안정. 개인정보 보호 통과와 별개 |
| FRAME=EMPTY/OFFSET/SIZE_MISMATCH/DISPLAY_CHANGED/UNAVAILABLE | 새 화면 출처 검증 실패. DISPLAY_GEOMETRY_UNCERTAIN으로 차단, 이전 metrics로 fallback 안 함 |
| LEGACY_FRAME=EMPTY/DIFFERENT/UNAVAILABLE/SAME | 기존 서비스 metrics와 새 경계의 관계. EMPTY/DIFFERENT이면 기존 출처 문제를 추가로 확인할 수 있음 |
| WINDOW_OUTSIDE / LEGACY_OUTSIDE | 새 화면/이전 metrics 대비 창이 벗어난 LEFT/TOP/RIGHT/BOTTOM 방향만 표시. 없으면 NONE |
| ROOT_WITHIN_WINDOW=YES/NO/UNKNOWN | root screen bounds가 대상 window screen bounds에 포함되는지. 진단용이며 경계 수정에 사용하지 않음 |
| DIALOG_CLASS=PRESENT/ABSENT/UNKNOWN | 수집된 노드 className에 Dialog 단서가 있는지. ABSENT여도 실제 별도 Dialog 창일 수 있음 |
| ROLE=SYNTHETIC_DIALOG/SYNTHETIC_ACTIVITY/UNKNOWN | 같은 합성 호스트의 고정 제목 일치 여부. UNKNOWN을 임의 승격하지 않음 |
| DETAIL=REGION_WINDOW_OUTSIDE | 실제 선택된 창 경계가 검증된 화면 밖. 어느 방향인지 WINDOW_OUTSIDE로 확인 |
| DETAIL=REGION_DIALOG_OUTSIDE / REGION_DIALOG_NOT_BOUNDED | 큰 창에서 Dialog 클래스 단서는 있지만 경계가 이탈했거나 제한 영역 조건을 충족하지 못함 |

`FRAME=VERIFIED;ROLE=SYNTHETIC_DIALOG;GEOMETRY=BOUNDED;WINDOW_OUTSIDE=NONE;DETAIL=REGION_WINDOW_BOUNDED`이면 원본 창 경계가 영역 검사에 사용됐는지 확인할 수 있습니다. 최종 성공에는 **API=WINDOW, OCR_RETURNED, F0_B_PASSED, 실제 OCR_MATCH와 OCR 영역 수 > 0**까지 필요합니다. API 호출 이후 버퍼 검증과 OCR의 실제 실행 성공은 아직 미검증입니다.

## Windows: API 35 및 API 37에서 각각 실행

개인정보 없는 에뮬레이터 한 대만 실행하고 아래 절차를 API 35, API 37에서 **각각** 반복합니다. JDK 17, compile/target SDK 35, Build Tools 35.0.0은 그대로입니다.

```powershell
git fetch origin
git switch fix/dialog-capture-coordinates
git pull --ff-only
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adbPath devices
$serial = "emulator-5554" # devices에 표시된 해당 에뮬레이터 값
& $adbPath -s $serial shell getprop ro.build.version.sdk
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk
```

먼저 본 앱 분석은 일시정지하고 geometry 계측만 실행합니다. 본 앱 runner의 계측은 본 앱 프로세스를 재시작할 수 있으므로 **전체 캡처 시험보다 먼저** 실행합니다.

```powershell
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.accessibility.capture.ScreenGeometryInstrumentedTest kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.SyntheticDialogGeometryTest -e syntheticHost true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

각각 `OK (1 test)`를 기대합니다. 두 번째는 `SYNTHETIC_DIALOG_GEOMETRY_READY;ROOT_CLASS_DIALOG=YES 또는 NO`도 표시합니다. NO는 root 클래스가 Dialog가 아니라는 관측이며 그 자체로 실패는 아닙니다. 이 시험은 실제 Android 창 ID 분리, APPLICATION/active 및 배경 창이 노출된 경우 레이어, Activity의 UI frame 대비 Dialog 경계, root 경계 포함 및 실제 ImageView 노드를 확인합니다. 캡처/OCR 시험은 아닙니다. 모달 Dialog 때문에 배경이 getWindows 목록에 없을 수 있으므로, 배경의 ID는 호스트 자신이 소유한 decor 노드에서 확인합니다.

그 다음 Android 접근성 설정에서 본 앱 서비스를 다시 확인하고, 본 앱에서 수집/별도 캡처 동의, 분석·캡처 허용 앱 `kr.closeforyou.capturetest` 하나만, **허용 앱 분석 시작**을 직접 설정합니다.

```powershell
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceCaptureOptInTest -e captureOcrService true -e connectionOnly true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceCaptureOptInTest -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

연결 전용은 SERVICE_CONNECTION_READY와 OK (1 test), 전체 시험은 위 최종 성공 조건과 OK (1 test)를 기대합니다. 전체 시험은 FRAME=VERIFIED 및 ROLE=SYNTHETIC_DIALOG도 assert합니다. 예상 결과이며 이 클라우드의 기기 성공 기록이 아닙니다. 연결 10초/결과 20초/캡처 1초/OCR 3초/토큰 5초는 늘리지 않았습니다.

시험 중 터치·회전·다른 앱으로 이동하지 않습니다. 시험 후 분석 일시정지/동의 철회를 합니다. 실제 개인 메시지·은행·결제 화면은 사용하지 않습니다. 실패하면 고정 코드만 기록하고 원본 노드·좌표·화면 이미지·전체 Logcat/dumpsys를 저장하지 않습니다.

## 검증 범위와 근거

JVM은 빈/축소/회전/offset frame 거부, 정확한 논리 크기 일치, 1픽셀 이탈 차단, className 없이 별도 창 경계 사용, 전체 Activity·잘못된 Dialog 노드 차단, 진단의 관계 정보와 시도 초기화를 합성 DTO로 검증합니다. 이 Mock 경로는 실제 화면 캡처 성공의 증거가 아닙니다. 계측 2개는 API 35/37에서 재시험할 수 있게 컴파일/패키징하며 실제 기기 실행은 별도입니다. 로컬 결과는 [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md)에 기록합니다.

공식 근거:

- [Context.createWindowContext / Service UI 컨텍스트 주의](https://developer.android.com/reference/android/content/Context#createWindowContext(int,android.os.Bundle))
- [WindowManager.getMaximumWindowMetrics](https://developer.android.com/reference/android/view/WindowManager#getMaximumWindowMetrics()) 및 [WindowMetrics.bounds와 insets](https://developer.android.com/reference/android/view/WindowMetrics#getBounds())
- [AccessibilityWindowInfo.getBoundsInScreen](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo#getBoundsInScreen(android.graphics.Rect)) 및 [노드 screen 좌표와 확대](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getBoundsInScreen(android.graphics.Rect))
- [Display.getRealSize](https://developer.android.com/reference/android/view/Display#getRealSize(android.graphics.Point))
- [Window.setTitle / 접근성 창 제목](https://developer.android.com/reference/android/view/Window#setTitle(java.lang.CharSequence))
- [AOSP Android 15 WindowMetricsController](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/window/WindowMetricsController.java), [ContextImpl의 WindowContext 생성](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/app/ContextImpl.java), [AOSP Dialog 이벤트 클래스 설정](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/app/Dialog.java)

공식 명세/공개 AOSP의 근거와 사용자의 API 37 이미지 내부 동작 실측을 구분합니다.

최종 로컬 검증: 위 Gradle 명령과 `:app:assembleRelease` 성공(28초, 196 tasks). JVM 87개(실패/오류/건너뜀 0), 양쪽 debug Lint 이슈 0. 새 계측 2개를 포함한 DEX 패키징 검사와 release의 debug provider/시험 권한/일반 overlay 권한 부재 확인 통과. 신규 계측은 컴파일/패키징만 했으며 API 35/37 실행 성공을 뜻하지 않습니다.
