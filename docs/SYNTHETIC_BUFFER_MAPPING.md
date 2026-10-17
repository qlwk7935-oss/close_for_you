# PR #6: 합성 패턴으로 window 버퍼 좌표 대응 검증

기존 `fix/dialog-capture-coordinates` / PR #6의 추가 계측입니다. 프로덕션 캡처 허용 정책은 변경하지 않습니다. 이전 [버퍼 크기 조사](WINDOW_CAPTURE_BUFFER.md) 다음 단계입니다.

## 확인된 사실과 미확정 사항

사용자가 Windows Medium Phone API 37에서 실행한 ServiceBufferGeometryOptInTest는 OK (1 test)였고, BUFFER_WIDTH/HEIGHT=LARGER, BUFFER_EXTENT=BUFFER_LARGER, BUFFER_DISPLAY_SIZED=NO, BUFFER_SWAPPED=NO, BUFFER_MAPPING=UNPROVEN_WINDOW, BUFFER_MEMORY=WITHIN_LIMIT, BUFFER_RELEASED=true, FRAME=VERIFIED, ROLE=SYNTHETIC_DIALOG, API=WINDOW을 보고했습니다. **양 축 크기 증가와 실제 버퍼 해제는 확인됐지만 원점·콘텐츠 대응·OCR 성공은 확인되지 않았습니다.**

이번 새 패턴 계측은 이 개발 환경에서 기기로 실행하지 않았습니다. 아래의 기대 코드는 시험 판정 기준이며 실제 결과가 아닙니다.

공개 ScreenshotResult는 HardwareBuffer, ColorSpace, timestamp만 제공하며 화면 원점·surface inset·crop 행렬은 제공하지 않습니다. getLocationOnScreen과 View.width/height는 **자체 소유한 합성 View**의 기준 정보로만 사용합니다. 다른 앱의 decor나 숨겨진 LayoutParams.surfaceInsets를 읽거나 reflection을 사용하지 않습니다.

AOSP Android 15와 조사 시점 main의 WindowManager.LayoutParams.setSurfaceInsets는 View.z에 근거한 surface inset을 정하는 비공개 경로를 갖고 있습니다. ViewRootImpl이 자동 inset을 설정하고 WindowLayout.computeSurfaceSize가 좌우·상하 inset을 surface 크기에 더합니다. 접근성 takeScreenshotOfWindow는 대상 ViewRootImpl의 SurfaceControl에 captureLayers를 요청하며 접근성 bounds를 source crop으로 넘기지 않습니다. 따라서 그림자용 surface inset은 현재 결과와 일관된 **가설**입니다. 해당 AOSP 코드가 API 37 에뮬레이터 이미지 내부와 정확히 같거나 그 가설이 실제 원인이라고 단정하지 않습니다.

| 구분 | 이번 실험으로 얻을 근거 | 확정할 수 없는 것 |
|---|---|---|
| Dialog 콘텐츠 vs decor | 자체 View/자체 decor의 화면 위치와 크기를 메모리에서 비교하고 실제 접근성 창/이미지 노드와 일치 검사 | 다른 앱의 decor 구조 |
| 버퍼 vs 콘텐츠 | 네 개의 비대칭 색상 기준점과 콘텐츠 전체 픽셀이 동일한 정수 이동량에 대응하는지 검사 | 모든 창·배율·회전에서 적용되는 범용 행렬 |
| 그림자/surface inset | 새 기본 Dialog와 첫 show 전 z=0인 새 Dialog를 비교. 기본 INSET_ALL → z=0 EXACT면 CONSISTENT_WITH_ELEVATION_INSETS | 숨겨진 실제 inset 값, 그림자 픽셀과 다른 장식 픽셀의 확정 분류 |
| decor 밖 픽셀 | 투명/비투명/혼합의 고정 코드 | 비투명 픽셀이 반드시 그림자라는 주장 |
| scaling/rotation | 기준점 간격·식별 색·전체 콘텐츠가 어긋나면 검증 실패 | 실패 코드만으로 scale/rotation 중 특정 원인의 단정 |

z=0에서도 여백이 남거나 두 경우가 다르게 실패하면 decor 배경/테마, surface 크기, compatibility transform 등의 가능성은 남습니다. AOSP의 inset 보존 때문에 기존 Dialog의 elevation만 나중에 바꾸지 않고 **각각 새 Activity/Dialog를 생성해 첫 show 전에** 설정합니다. 숨겨진 inset 공식으로 크롭 위치를 계산하지 않습니다.

## 실제 실행 경로와 안전 경계

1. 외부 호스트 runner가 기존 실제 서비스 연결 검사를 실행합니다. UiAutomation은 DONT_SUPPRESS_ACCESSIBILITY_SERVICES를 유지합니다. 연결 10초/결과 20초를 늘리지 않습니다.
2. signature 권한 + 고정 호스트 caller로 보호된 debug provider에 explicitOptIn 및 patternProbe를 요청합니다. 사용자가 직접 수집/캡처 동의, 분석 시작, 분석·캡처 허용 앱을 `kr.closeforyou.capturetest` 하나로 설정해야 합니다. 시험은 설정을 쓰지 않습니다.
3. 계측용 Intent에서만 합성 Dialog 본문을 흰색과 네 고정 색상 사각형으로 바꿉니다. 일반 실행의 한국어 공지·닫기 버튼은 그대로입니다. 별도 시험 Activity를 운영 앱에 넣지 않습니다.
4. 두 연속 pre-draw에서 자체 geometry와 유효 window ID가 안정됐는지 확인한 뒤 호스트는 자체 decor의 접근성 window ID와 자체 View/decor의 화면 위치·크기를 **일회성 비공개 IPC 메모리**로 등록합니다. 실제 수치는 RESULT/로그/파일/예외/성공 메시지에 포함하지 않습니다. 제목만으로 픽셀 검사를 허가하지 않습니다.
5. 기존 후보 감지 → F0-A → 안전한 영역 결정 → 실제 서비스 window API를 그대로 거칩니다. 반환 버퍼가 기존 실제 크기·layer·format 메모리 검사를 통과한 뒤에만 debug 관측기가 실행됩니다. 같은 호스트/등록 window ID, 자체 decor=접근성 창 bounds, 자체 콘텐츠=ImageView 노드 bounds, 최신 토큰, 연결, 동의와 단일 허용 앱 조건을 다시 확인합니다.
6. 합성 검사만 최대 400만 실제 버퍼 픽셀로 제한합니다. 전체 합성 window 버퍼를 임시 CPU Bitmap/IntArray로 읽어 기준점의 색·13×13 크기·유일성과 모든 기준점의 동일 이동량을 확인합니다. 색 채널 오차는 1까지만 허용하고 알파 255를 요구합니다. 알려진 콘텐츠 **전체**가 흰색/지정 색상이어야 합니다. 임의 크롭·resize·비율 추정·회전 보정은 없습니다.
7. 검사 결과는 고정 enum뿐입니다. 계산한 이동량은 함수 안에서만 쓰고 반환/캐시/영구화하거나 OCR 프레임에 적용하지 않습니다. 메모리상 예상 좌표는 첫 대응 callback에서 소비하고 END/30초 세션 만료 시 폐기합니다. 잘못된 대상/검증 실패도 성공으로 바꾸지 않습니다.
8. 검사 루프는 500ms 자체 예산과 연결/동의/토큰/END를 행마다 확인하며 중단합니다. native Bitmap 복사는 동기 API여서 도중 강제 중단할 수 없다는 한계가 있습니다. 픽셀 배열을 0으로 지우고 mutable Bitmap을 erase/recycle, wrapped Bitmap을 recycle합니다. 실제 HardwareBuffer는 기존 관리자 finally에서 닫고 그 이후에만 patternReleased=true가 됩니다. CPU 추가 저장은 최대 약 32MB(Bitmap+IntArray), GPU/driver 전체 RSS 상한의 증명은 아닙니다.
9. **기존 CaptureBufferPolicy가 계속 적용됩니다.** 큰 버퍼는 패턴 검증에 성공하더라도 REGION_UNCERTAIN으로 거부되고 B·C·D에 이미지나 변환을 전달하지 않습니다. 캡처 빈도/동시성·보안 창/시스템 창/가림 보호·F0-B·기존 OCR timeout은 유지합니다. release 구현은 no-op이며 패턴 검사/등록/provider/합성 클래스가 release APK에 포함되지 않는지 DEX로 검사합니다.

패턴 결과는 해당 실제 callback의 고정 진단과 함께 고정하여 이후 다른 이벤트의 API/FRAME과 섞지 않습니다. 이 snapshot은 CAPTURE_BUFFER_RECEIVED 시점이며 최종 OCR 발행 진단이 아닙니다. 별도 patternReleased는 그 callback의 실제 버퍼 해제를 뜻합니다. 새 테스트의 OK는 **합성 패턴 대응 검증만** 의미하고 실제 한국어 OCR 성공이나 타 앱 캡처 지원의 의미가 아닙니다.

## Windows: API 35와 API 37 각각 실행

개인정보가 없는 전용 에뮬레이터 한 대씩 실행합니다. 실제 메시지·은행·계정·결제 앱은 열지 않습니다. JDK 17, SDK 35/Build Tools 35.0.0으로 저장소 루트에서 PowerShell을 실행합니다.

```powershell
git switch fix/dialog-capture-coordinates
git pull --ff-only origin fix/dialog-capture-coordinates
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adbPath devices
$serial = "emulator-5554" # devices에서 실제 대상 serial 선택
& $adbPath -s $serial shell getprop ro.build.version.sdk
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk
```

우선 본 앱 분석을 일시정지한 채 기존 geometry 회귀를 확인합니다. 본 앱 runner가 서비스 프로세스를 다시 시작할 수 있으므로 먼저 실행합니다.

```powershell
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.accessibility.capture.ScreenGeometryInstrumentedTest kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.SyntheticDialogGeometryTest -e syntheticHost true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

그다음 본 앱 UI에서 접근성 서비스, 수집 동의, 별도 캡처 동의, 분석·캡처 허용 앱 `kr.closeforyou.capturetest` 하나, **허용 앱 분석 시작**을 직접 확인합니다. 서비스 재시작/동의 강제 설정/예산 초기화 명령은 사용하지 않습니다.

```powershell
# 새 계측: 내부에서 실제 연결 확인 후 기본/zero-elevation Dialog를 각각 검사
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceSyntheticPatternOptInTest -e captureOcrService true -e syntheticPattern true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
Start-Sleep -Seconds 3

# 일반 한국어 Dialog에 대한 기존 버퍼 회귀 관측
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceBufferGeometryOptInTest -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
Start-Sleep -Seconds 3

# 실제 한국어 OCR 성공 여부는 별도 기존 전체 시험으로만 판단
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceCaptureOptInTest -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

새 패턴 시험에 connectionOnly=true를 전달하면 캡처를 생략하지 않고 실패합니다. 잘못된 opt-in은 JUnit assumption으로 건너뛰므로 **OK만 보지 말고 두 MODE의 결과와 최종 비교 코드까지** 확인합니다. 실행 중 터치/화면 이동/회전은 하지 않습니다. 끝나면 본 앱 분석을 일시정지하거나 동의를 철회합니다. raw Logcat/이미지/노드/좌표 dump는 제출하지 않습니다.

## 기대 코드와 실패 해석

성공 조건: SERVICE_CONNECTION_READY → 두 번의 SYNTHETIC_PATTERN_OBSERVED에서 ARM=PATTERN_ARMED, PATTERN=TRANSLATION_VERIFIED, BUFFER_RELEASED=true, API=WINDOW, FRAME=VERIFIED → SYNTHETIC_PATTERN_COMPARISON;PRODUCTION_POLICY=UNCHANGED → OK (1 test).

| 코드 | 의미 |
|---|---|
| DECOR_BUFFER=EXACT / INSET_ALL / INSET_SOME | 패턴으로 검증한 이동량을 적용한 자체 decor가 버퍼 전체/모든 변 안쪽/일부 변 안쪽에 해당. 수치는 반환하지 않음 |
| OUTER=NONE / TRANSPARENT / NONTRANSPARENT / MIXED | 자체 decor 바깥 픽셀의 존재/알파 관계. 그림자라는 자동 결론 없음 |
| ELEVATION_EFFECT=CONSISTENT_WITH_ELEVATION_INSETS | 기본 z>0에서 INSET_ALL, 새 z=0에서 EXACT. elevation 기반 inset 가설과 일관됨 |
| ELEVATION_EFFECT=NO_RELATION_CHANGE / INCONCLUSIVE | 같은 관계 유지 / 나머지 경우. 크기 증가 원인 미확정 |
| OWNED_WINDOW_MISMATCH | 자체 decor/콘텐츠와 실제 접근성 창/노드 비교 실패. 픽셀을 읽지 않음 |
| MARKERS_MISSING_OR_AMBIGUOUS | 기준점 누락/중복/색·크기 불일치. 컬러 변환·클리핑·다른 프레임 등을 구분할 추가 근거 필요 |
| NON_UNIT_OR_INCONSISTENT_MAPPING | 기준점들이 동일한 1:1 이동에 해당하지 않음. 배율/회전을 추정해 통과시키지 않음 |
| DECOR_OUTSIDE_BUFFER / CONTENT_NOT_SYNTHETIC | 검증된 위치로도 decor 포함 불가 / 알려진 콘텐츠 전체 불일치 |
| BUFFER_LIMIT / PLAN_INVALID / STALE_OR_NOT_ALLOWED | 별도 메모리/등록 입력/토큰·동의·연결 조건 실패 |
| INSPECTION_INTERRUPTED / INSPECTION_FAILED | 500ms/취소·만료 등으로 검사 미완료 / 내부 검사 실패. 성공으로 보고하지 않음 |
| PATTERN_OBSERVATION_TIMEOUT | 20초 내 해당 callback 해제 결과를 얻지 못함. ARM/고정 API·FRAME 코드를 함께 확인 |

기존 전체 OCR 시험은 기본 Dialog의 큰 버퍼가 재현되면 CAPTURE_RESULT_TIMEOUT;REGION_UNCERTAIN으로 **여전히 실패할 수 있습니다**. 이 PR은 그 실패를 숨기거나 패턴 검사로 대체하지 않습니다. 패턴을 실제로 검증한 후에도 일반 앱용 변환을 안전하게 얻는 방법과 지원 범위를 별도 검토해야 합니다.

## 검증과 남은 작업

JVM 회귀는 순수 합성 데이터로 정확/비대칭 이동, 크기·색 기준점 누락/중복, 서로 다른 이동, 콘텐츠 오염, decor 범위 초과, 메모리/좌표 overflow, 취소를 검사합니다. APK 검사는 새 계측 DEX 존재, 호스트 독립 Kotlin 런타임, 본 앱과 외부 driver의 클래스 분리, release의 패턴 구현/디버그 provider 부재를 확인합니다.

개발 환경에 연결된 adb 기기/에뮬레이터/KVM이 없습니다. **신규 패턴 계측, 실제 API 35/37 캡처·OCR, surface inset 원인 확정은 미검증**입니다. 로컬 Gradle/단위 테스트/Lint 결과는 [개발 상태](DEVELOPMENT_STATUS.md)에 기록합니다. B·C·D 공통 계약/기능과 F10/F12/F13 TODO, F6 제외 정책은 변경하지 않습니다.

## 공개 근거

- [ScreenshotResult](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.ScreenshotResult), [takeScreenshotOfWindow](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))
- [View.getLocationOnScreen](https://developer.android.com/reference/android/view/View#getLocationOnScreen(int[])), [View elevation](https://developer.android.com/reference/android/view/View#getElevation())
- [Android 15 WindowManager: 비공개 setSurfaceInsets](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/WindowManager.java), [main 비교](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/view/WindowManager.java)
- [Android 15 WindowLayout.computeSurfaceSize](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/WindowLayout.java), [main 비교](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/view/WindowLayout.java)
- [ViewRootImpl](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/ViewRootImpl.java), [AccessibilityInteractionController](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/AccessibilityInteractionController.java)
