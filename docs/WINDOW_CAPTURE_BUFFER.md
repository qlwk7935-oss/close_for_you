# PR #6 추가 조사: window 캡처 HardwareBuffer 경계

`fix/dialog-capture-coordinates`의 기존 PR #6에 추가합니다. 새 PR/브랜치 생성이나 main 병합은 하지 않습니다. 기준 커밋은 39c9bed입니다.

## 이번 기기 결과로 확인된 것

사용자 보고: Windows Medium Phone API 37.0에서 ScreenGeometryInstrumentedTest 및 SyntheticDialogGeometryTest 각각 OK (1 test), 서비스 연결/후보/F0-A/화면 검증/합성 Dialog 역할 확인 성공. 전체 시험은 BUFFER_GEOMETRY·REGION_UNCERTAIN, API=WINDOW, FRAME=VERIFIED, GEOMETRY=BOUNDED, ROOT_WITHIN_WINDOW=YES, LEGACY_FRAME=SAME에서 실패했습니다.

코드상 BUFFER_GEOMETRY는 단순 API 호출 지점이 아니라 **onSuccess에서 실제 HardwareBuffer를 받은 후** 설정됩니다. 이번처럼 bounded 창 자체를 ROI로 사용한 window 캡처에서는 ROI와 source 창이 같으므로, 기존 검사의 width/height 중 적어도 하나가 일치하지 않았음을 확인할 수 있습니다. 그러나 어느 축이 얼마나 다른지, surface inset/scale/rotation 중 무엇 때문인지는 이 문자열로 확정할 수 없습니다. CAPTURE_RETURNED가 없는 것은 유효한 CPU OCR 프레임을 반환하지 않았다는 뜻입니다. OCR/F0-B는 아직 실행되지 않았습니다.

이번 진단의 LEGACY_FRAME=SAME은 기존 서비스 metrics가 잘못됐다는 증거가 아닙니다. 현재 막힌 비교는 **buffer와 window 크기**입니다. EVENT_WINDOW=DIFFERENT도 원인으로 단정할 수 없습니다. API에는 이벤트 ID가 아니라 현재 보호 검사와 토큰에 결박된 snapshot.windowId를 전달합니다. 서로 다른 이벤트/분석 진단을 섞지 않는 기존 세션 검사는 유지합니다.

## API·AOSP·CTS 조사 결과

- 공개 takeScreenshotOfWindow API는 지정 창의 ScreenshotResult를 반환한다고 설명하지만, HardwareBuffer의 크기가 AccessibilityWindowInfo.getBoundsInScreen과 항상 같다는 계약은 없습니다. ScreenshotResult의 공개 정보는 버퍼·색 공간·timestamp이며 buffer-to-screen 원점/crop/변환 행렬은 제공하지 않습니다.
- AOSP Android 15의 AbstractAccessibilityServiceConnection은 해당 window ID의 원격 접근성 연결로 요청을 전달합니다. ViewRootImpl을 거쳐 대상 앱의 AccessibilityInteractionController가 자신의 SurfaceControl을 captureLayers로 캡처합니다. FLAG_SECURE 검사와 target UID 필터가 있으며, 접근성 창 사각형을 source crop으로 전달하지 않습니다. 확인한 현재 AOSP main도 이 경로가 같습니다. 이것이 사용자의 API 37 에뮬레이터 내부 빌드와 동일함을 실측한 것은 아닙니다.
- ScreenCapture.Builder의 기본 frameScale은 1이고 sourceCrop은 비어 있습니다. 이는 접근성 screen 좌표와 surface local 좌표가 같다는 증명이 아닙니다. Surface 자체의 크기·원점·부모 변환을 알아야 합니다.
- AOSP WindowLayout.computeSurfaceSize는 일반 frame 크기 외에도 FLAG_SCALED의 요청 크기, drag-resize의 최대 영역 및 surfaceInsets를 고려합니다. ViewRootImpl/WindowState에도 surface inset과 global scale/회전 관련 처리가 있습니다. 따라서 surface 기반 이미지와 접근성 screen 영역을 동일한 사각형으로 취급할 일반 근거가 없습니다.
- 확인한 CTS testTakeScreenshotOfWindow_GetScreenshotResult는 **일반 Activity의 decor View 너비/높이**와 버퍼를 비교합니다. Dialog·surface inset·compat scaling·모든 접근성 창 경계에 대한 동등성을 증명하는 시험이 아닙니다. CTS를 실행했다고 주장하지 않습니다.

| 가능한 원인 | 확인한 근거와 한계 |
|---|---|
| 창 surface의 inset/그림자 | AOSP가 surfaceInsets를 크기에 더하는 경로가 있음. BUFFER_LARGER와 일관될 수 있지만 좌우/상하 여백이나 원점을 추론할 수 없음 |
| 요청 크기/compatibility scaling | surface 크기와 화면 frame/변환이 다를 수 있음. LARGER/SMALLER/MIXED만으로 배율을 확정할 수 없음 |
| 회전/고정 회전/전환 중 surface | 가로·세로 교환 관계는 단서일 뿐 회전 방향/원점/실제 원인의 증명이 아님 |
| resize용 큰 surface | DISPLAY_SIZED일 수 있으나 window API가 display 캡처를 했다는 증거가 아님 |
| 접근성 region과 decor/surface 차이 | 창의 screen bounds는 접근성 region의 bounding rectangle. CTS의 decor View 기준과 혼동하지 않음 |

일반 앱의 실제 surfaceInsets/transform을 공개 ScreenshotResult에서 얻을 수 없습니다. AOSP의 LayoutParams.surfaceInsets도 compile SDK 35의 공개 필드가 아니어서 참조를 제거했습니다. reflection/숨겨진 API는 사용하지 않습니다. 합성 호스트에서는 공개 decor.z가 양수인지 **OWN_DECOR_ELEVATED=YES/NO/UNKNOWN**만 별도 계측합니다. 높이 효과의 존재는 surface inset이나 버퍼 대응의 증명이 아닙니다. 기본 Dialog의 스타일·크기·이미지·elevation은 바꾸지 않습니다.

## 이번 수정과 보호 유지

1. CaptureBufferPolicy가 실제 반환 width/height/layers/format으로 메모리 검사를 수행합니다. 예상 window 크기가 작아도 큰 실제 버퍼를 통과시키지 않습니다. 실제 source 800만 픽셀·ROI 200만 픽셀, 단일 layer 및 RGBA/RGBX8888 조건을 Bitmap.wrapHardwareBuffer/CPU 복사 전에 검사합니다. 메모리 거부가 크기 불일치보다 우선합니다.
2. **허용 범위를 넓히지 않습니다.** 기존 exact-size 경로는 ROI 포함과 다른 모든 보호 조건을 요구합니다. 더 큰/작은/디스플레이 크기/가로세로 교환 버퍼는 공개 좌표 대응을 입증하지 못했으므로 REGION_UNCERTAIN으로 거부합니다. 큰 버퍼의 중앙을 자르거나 비율·elevation으로 padding을 추정하지 않습니다. EXACT_SIZE라는 진단도 크기 일치만 의미하며 자동 행동 허가가 아닙니다.
3. 새 CAPTURE_BUFFER_RECEIVED는 현재 토큰/활성 요청의 실제 onSuccess를 구분합니다. 고정 관계 정보만 같은 분석 시도에 기록하고, HardwareBuffer.close 이후에만 BUFFER_RELEASED=true를 기록합니다. 취소/새 시도/END 뒤의 늦은 진단은 폐기합니다. 취소·1초 timeout의 늦은 실제 버퍼는 기존 finally에서 해제하고 native lease 격리를 유지합니다.
4. BUFFER_RELEASED는 **HardwareBuffer** 해제 여부입니다. 성공 경로의 CPU Bitmap은 기존 BitmapFrame/native OCR lease가 별도로 관리합니다. 이미지 파일·DB·로그·서버 저장은 추가하지 않습니다. 800만 픽셀은 공개 실제 논리 크기 기준이며, native stride/driver 할당량을 얻을 수 없어 전체 GPU/RSS 상한의 증명은 아닙니다. 이미 OS가 할당한 버퍼 수신 뒤 초과를 확인하면 추가 Bitmap 할당 없이 즉시 해제합니다.
5. F0-A/F0-B·보안/시스템/외부 앱/가림·동의·앱별 허용·확대·빈도·메모리·모든 timeout을 유지합니다. API 34+의 display fallback, 강제 resize/회전, Mock OCR 주입은 없습니다. 공통 DTO/포트와 B·C·D 기능도 변경하지 않습니다.

## 고정 진단 코드

실제 픽셀 수·좌표·여백 수치·원점·원문 이미지·OCR 텍스트는 반환/출력하지 않습니다.

| 필드 | 값/의미 |
|---|---|
| BUFFER_WIDTH / BUFFER_HEIGHT | 요청 source 대비 MATCH / LARGER / SMALLER |
| BUFFER_EXTENT | MATCH, BUFFER_LARGER(양 축 이상이며 한 축 이상 큼), BUFFER_SMALLER, MIXED_MISMATCH |
| BUFFER_DISPLAY_SIZED | 논리 display 너비·높이와 일치하는지 YES/NO. 캡처 종류나 원점의 증명 아님 |
| BUFFER_SWAPPED | 실제 두 크기가 창의 높이·너비와 교환되어 일치하는지 YES/NO. 회전 허가 아님 |
| BUFFER_MAPPING | EXACT_SIZE, UNPROVEN_WINDOW, UNPROVEN_DISPLAY, ROI_OUTSIDE_SOURCE |
| BUFFER_MEMORY | WITHIN_LIMIT, SOURCE_LIMIT, ROI_LIMIT, LAYER_LIMIT, FORMAT_UNSUPPORTED, INVALID_DIMENSIONS |
| BUFFER_RELEASED | 실제 HardwareBuffer.close가 완료된 경우 true. 모든 Bitmap/native OCR 메모리 해제의 의미는 아님 |
| NOT_OBSERVED / UNKNOWN | 현재 시도에 대응하는 유효 callback 메타데이터가 아직 없음 |

메모리 거부는 DETAIL=BUFFER_MEMORY_LIMIT / EVIDENCE=MEMORY_LIMIT, 매핑 거부는 DETAIL=BUFFER_GEOMETRY / EVIDENCE=REGION_UNCERTAIN입니다. 두 경우 모두 OCR로 전달하지 않으며 최신 F0-A 통과 시 안전한 tree 부분 근거만 발행할 수 있습니다. 이때 PUBLISHED는 OCR 성공이 아닙니다.

## Windows: API 35와 API 37 각각 재시험

개인정보 없는 에뮬레이터 한 대씩 실행합니다. 기존 브랜치에서 새 커밋을 받고, JDK 17·SDK 35·Build Tools 35.0.0으로 빌드합니다.

```powershell
git switch fix/dialog-capture-coordinates
git pull --ff-only origin fix/dialog-capture-coordinates
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adbPath devices
$serial = "emulator-5554" # devices에서 해당 기기의 실제 serial 선택
& $adbPath -s $serial shell getprop ro.build.version.sdk
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk
```

본 앱 분석을 일시정지하고 geometry 계측부터 실행합니다. 본 앱 runner가 서비스 프로세스를 재시작할 수 있어 이 순서를 지킵니다.

```powershell
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.accessibility.capture.ScreenGeometryInstrumentedTest kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.SyntheticDialogGeometryTest -e syntheticHost true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

각각 OK (1 test)를 기대합니다. 두 번째는 기존 geometry 상태에 OWN_DECOR_ELEVATED 코드도 표시합니다. 그 다음 접근성 서비스를 재확인하고 사용자 UI에서 수집/별도 캡처 동의, 분석/캡처 허용 앱 **kr.closeforyou.capturetest 하나만**, **허용 앱 분석 시작**을 직접 설정합니다. 시험은 이 설정을 대신 바꾸지 않습니다.

```powershell
# 연결만 검사
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceCaptureOptInTest -e captureOcrService true -e connectionOnly true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner

# 새 버퍼 전용 관측/안전 거부 검사
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceBufferGeometryOptInTest -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
Start-Sleep -Seconds 3

# 기존 실제 캡처 + OCR 성공 검사
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceCaptureOptInTest -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

버퍼 전용 시험의 성공은 BUFFER_GEOMETRY_OBSERVED와 OK (1 test)입니다. 실제 현재 합성 Dialog의 callback/FRAME=VERIFIED/API=WINDOW/BUFFER_RELEASED를 요구하고, 불일치 버퍼는 REGION_UNCERTAIN 및 OCR 미진입, 메모리 초과는 MEMORY_LIMIT를 검사합니다. **이 시험의 OK는 OCR 성공이 아닙니다.** connectionOnly 플래그로 버퍼 시험을 우회할 수 없습니다.

기존 전체 시험은 실제 OCR_MATCH 및 OCR 영역 수 > 0을 계속 요구합니다. 버퍼 불일치가 재현되면 새 코드와 함께 CAPTURE_RESULT_TIMEOUT으로 계속 실패하는 것이 안전한 현재 동작입니다. 그 실패를 성공으로 바꾸지 않습니다. 제한은 연결 10초/결과 20초/캡처 1초/OCR 3초/토큰 5초 그대로입니다. 시험 사이에는 최소 캡처 간격을 지키며 RATE_LIMITED/BUSY면 해당 제한이 풀린 뒤 재시험합니다. timeout을 늘리거나 서비스를 재시작해 예산을 우회하지 않습니다.

화면 이동·터치·회전 없이 합성 화면에서만 시험하고, 종료 후 일시정지/동의 철회를 합니다. 실제 메시지·은행·결제 화면이나 원본 Logcat/노드/이미지/좌표 dump를 기록하지 않습니다. API 35와 37에서 같은 절차를 각각 반복하고 고정 코드만 비교합니다.

## 로컬 검증과 한계

JVM은 축별 mismatch, larger/smaller/mixed/display-sized/swapped 관계, 임의 보정 없는 거부, 실제 픽셀 상한·overflow·layer/format·ROI 검사, 시도별 진단 초기화/취소 격리와 거부 후 tree만 남기는 경로를 합성 DTO로 검증합니다. 새 외부 버퍼 계측과 기존 캡처 시험은 같은 signature-gated 세션을 공유하며 본 앱 Runtime을 직접 로드하지 않습니다. 패키징 검사에 새 계측 클래스의 실제 DEX 포함도 추가했습니다.

이 환경은 adb 기기 0대, emulator/KVM 없음입니다. 새 버퍼 전용 시험 및 API 35/37 실제 캡처·OCR은 **미실행**입니다. JVM·빌드·Lint·패키징 결과는 [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md)에 별도로 기록합니다. 좌표 대응이 입증되지 않은 불일치 버퍼의 지원은 남은 문제입니다.

## 근거

- [공개 takeScreenshotOfWindow API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)) / [ScreenshotResult 공개 필드와 close 의무](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.ScreenshotResult)
- [AOSP Android 15 AbstractAccessibilityServiceConnection](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java)
- [AOSP Android 15 AccessibilityInteractionController](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/AccessibilityInteractionController.java) / [확인한 main 경로](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/view/AccessibilityInteractionController.java)
- [ScreenCapture의 crop/scale 기본값](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/window/ScreenCapture.java)
- [WindowLayout.computeSurfaceSize](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/WindowLayout.java) / [ViewRootImpl의 surface 처리](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/ViewRootImpl.java) / [WindowState의 scale/inset 처리](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/core/java/com/android/server/wm/WindowState.java)
- [CTS AccessibilityTakeScreenshotTest](https://android.googlesource.com/platform/cts/+/master/tests/accessibilityservice/src/android/accessibilityservice/cts/AccessibilityTakeScreenshotTest.java)

최종 로컬 결과: JDK 17의 위 전체 Gradle 명령으로 BUILD SUCCESSFUL(1분 17초, 196 tasks). JVM 99개(새 회귀 12개, 실패/오류/건너뜀 0), 양쪽 debug Lint 이슈 0. Debug/Release·계측 APK 및 새 버퍼 계측 DEX 포함/독립 classloader 의존성 검사 통과. 초기 비공개 surfaceInsets 참조와 계측의 Activity 수신자 타입 컴파일 오류는 공개 API 및 구체 타입 사용으로 수정했습니다. 실제 기기 성공의 의미는 아닙니다.
