# P01 STABLE_FRESH 이후 제거: reader × C 생성 대조 시험

원 대조 계측 기준: `fdbb65d2922b43354550a72faaa049a77142cbd4`, `fix/a-samsung-p01-lifetime-20261028`.
이 문서는 대조 성공 보고가 아니다. 계측 준비 당시 Linux에서는 연결 기기/실행 가능한 emulator가 없었다.
이후 사용자가 제공한 API 37 첫 실패 관측·진단은 10절, 후속 8완료/4실패 및 새 준비 모드는 11절, 준비 TARGET_MISSING 처리 경계는 12절에 별도로 기록한다.
이번 작업에서는 Android 기기·에뮬레이터 명령을 실행하지 않았다. 운영 허용 정책, B안 3회/100ms, TTL, cleanup을 변경하지 않는다.

## 1. 최신 증거와 가설

사용자 제공 삼성 기록: 2026-10-31 13:08:42.647 STABLE_FRESH → .701 SHOWN → .818 대상 WINDOW_SET/BOUNDS →
.826 DISPLAY_REVIEW CHANGED/NODES_CHANGED, SELECT_DISABLED, 첫 NODE_MISMATCH=BOUNDS,
ROOT EXPANDED / WINDOW_BOUNDS OTHER_CHANGE → .827 제거·음성 중단 → .843 RETIRED_ID_EVENT/ADDED/CLEANUP_FAILED.

확정되는 범위:
- B안 사전 관측은 통과했다. 100ms는 최대 관측 예산이며 최소 안정 기간이나 향후 불변 보장이 아니다.
- 이후 표시 비교에서 ROOT bounds가 달랐다. 이 로그에 닫기 후보 geometry 결과는 없다.
- B안이 활성화되면 예전 Demo bounds 예외를 연결하지 않는다. SELECT_DISABLED는 B안 미실행을 뜻하지 않는다.
- cleanup 실패는 최초 제거 판단 이후의 별도 장애다.
- 이벤트 도착 시각은 실제 대상 layout 변경 시각이 아니다. 이 증거만으로 C가 ROOT 확대를 일으켰다고 할 수 없다.

미확정: 실제 대상 후속 layout, 접근성 표현/캐시 차이, 두 reader의 root 선택/refresh 차이,
C 창의 배치·포커스·inset 영향. 서로 배타적이지 않다.

## 2. 공식 API와 버전을 고정한 구현 근거

API 계약:
- [UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES): 기본 서비스 억제를 막는 연결 옵션이다. 옵션 지정이 서비스 연결/이벤트 수신 성공 증거는 아니다.
- [AccessibilityService.getWindows](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getWindows()): default display의 상호작용 가능한 창 목록이며 전체 물리 창 목록이 아니다. retrieve-window-content capability와 interactive-windows flag가 필요하고, 미연결/권한 부족에서도 빈 목록이 가능하다. 빈 목록을 변화 없음으로 처리하지 않는다.
- [AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo#TYPE_ACCESSIBILITY_OVERLAY): 아래 창을 introspect할 수 있는 특성이 있지만, 이 사실이 현재 C 창의 native 소유권/렌더링 또는 아래 팝업의 불변성을 증명하지 않는다.
- [AccessibilityEvent.WINDOWS_CHANGE_BOUNDS](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent#WINDOWS_CHANGE_BOUNDS): R부터 region 변화도 의미하며 사각 bounds가 같을 수도 있다.
- [AccessibilityWindowInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo): getRegionInScreen은 touchable region; getBoundsInScreen은 그 region의 bounding rectangle이다. 노드 bounds와 동일한 대상이 아니다.
- [AccessibilityNodeInfo.getBoundsInScreen](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getBoundsInScreen(android.graphics.Rect)): 노드의 screen 좌표이며 확대 배율과 viewport offset의 영향을 받는다. window-relative bounds로 바꿔 비교하지 않는다.
- [Window.setLayout](https://developer.android.com/reference/android/view/Window#setLayout(int,int)): width/height layout parameter를 바꾸는 API. 호출 반환이 accessibility tree의 원자적 반영 완료를 보장하지 않는다.

참조 구현은 **android-15.0.0_r1**로 고정했다. 삼성 OEM 구현이나 API 37 전체를 동일하다고 간주하지 않는다.
- [AccessibilityController.WindowsForAccessibilityObserver](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/core/java/com/android/server/wm/AccessibilityController.java): scheduleComputeChangedWindows는 Handler에 계산을 예약하고, computeChangedWindows는 WM lock 내 목록 취득 후 접근성 callback을 호출한다. feature flag에 따라 callback 경로도 다르다.
- [AccessibilityWindowManager.sendEventsForChangedWindowsLocked](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityWindowManager.java): 이전/새 목록 비교로 ADDED/REMOVED/difference 이벤트를 생성한다.
- [AccessibilityWindowInfo.differenceFrom](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityWindowInfo.java): mRegionInScreen 차이가 BOUNDS bit를 만든다.
- [AbstractAccessibilityServiceConnection.notifyAccessibilityEvent](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java): event 복사 후 Handler 전달. notificationTimeout=0이어도 원격 layout과 동일 호출 스택/동일 프레임이라는 뜻은 아니다.
- [Dialog.show](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/app/Dialog.java), [Window.setLayout](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/Window.java), [ViewRootImpl.scheduleTraversals](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/ViewRootImpl.java): addView / 속성 갱신 / Choreographer traversal 사이에 비동기 경계가 있다.

프로젝트 `PopupScenarioActivity.showDialog()`는 실제로 `show()` 다음 `setLayout()`을 호출한다.
이는 후속 변화 **가능성의 코드 근거**이며 이번 삼성 ROOT 확대의 확정 원인은 아니다. D/P01 fixture는 변경하지 않았다.

## 3. 실제 재사용 경로와 별도의 시험 서비스가 필요한 이유

운영 서비스의 이벤트 무시, C 표시 허용 또는 제거 정책을 바꾸면서 A/B를 만들지 않는다.
기존 app AndroidTest 환경에서 A 전용 **Debug 시험 서비스**를 명시적으로 연결한다.
이 서비스는 arm 전에는 읽거나 표시하지 않으며 executor, Runtime 발행, OCR, screenshot, TTS, 제스처를 연결하지 않는다.
운영 서비스와 C 자체 test service를 함께 켜면 시험을 거부한다. 시험 중 C 버튼을 눌러도 취소하며 외부 클릭 경로는 없다.

재사용하는 코드:
- `AndroidSnapshotCollector.capture(freshPresentation=true, requireCaptureIsolation=true)`: 실제 두 F0 pass, 숨김/배경 검사, 노드 refresh, 실제 UUID/시각, endpoint, 자원 해제.
- `AndroidF9ScreenPort.scan`: 실제 protection/tree/window/frame/occlusion/endpoint/cleanup. 비교 진입점은 targetPath를 전달하지 않고 press callback을 연결하지 않는다.
- `CaptureOcrPipeline` tree 경로 + 실제 추가 F0 exact preflight + `DecisionCloseCandidateFinder` + `DecisionPopupClassifier` + `ChoicePromptPlanner`.
- 조건 B: 수정하지 않은 실제 `AccessibilityChoiceOverlay.present()` / native View / Compose 배치 / native observer.

이 시험은 **운영 서비스 전체 admission/recovery/cleanup/TTS의 E2E 대체물이 아니다**.
C 창 생성이라는 요인을 격리하기 위해 별도 서비스에서 실제 C View를 짧게 생성한다. C controls는 기본 NONE, TTS는 연결하지 않는다.
C의 기존 WindowManager.LayoutParams도 그대로 사용한다: MATCH_PARENT × WRAP_CONTENT,
TYPE_ACCESSIBILITY_OVERLAY, FLAG_NOT_FOCUSABLE, BOTTOM, TRANSLUCENT. 별도 대체 overlay 구현은 없다.
시험의 callback은 취소만 하고 실제 대상의 클릭·접근성 focus action을 호출하지 않는다.
원래 C Compose 내용도 명시적 focus 요청을 하지 않는다. 플랫폼 자체 포커스/배치 영향은 관측 대상이다.
따라서 운영 TTS 완료·선택창 6초 유지·수동 닫기 성공은 이 시험으로 판정하지 않는다.

운영 A 파일 변경은 F9 reader의 Debug 비교 read 진입점과, 기본값 false인 scan 옵션 두 개뿐이다.
기존 read/readFor/verifyDisplayedPopup/press 호출은 모두 기존 root/metadata 경로를 유지한다.
Release 비교 진입점은 즉시 null을 반환한다. Debug service/자료 비교기/로그/AndroidTest는 Release에 없다.
B/C/D/P23, 운영 collector/service/표시 정책/cleanup 코드는 수정하지 않는다.

## 4. 4개 조건과 reader 정의

| 조건 | C 창 | 읽기 순서 |
|---|---|---|
| withoutCForward | 생성하지 않음 | COLLECTOR×2 → F9_ACTIVE×2 → F9_WINDOW×2 |
| withoutCReverse | 생성하지 않음 | F9_WINDOW×2 → F9_ACTIVE×2 → COLLECTOR×2 |
| withCForward | 실제 C View 생성 | Forward |
| withCReverse | 실제 C View 생성 | Reverse |

각 method는 P01을 새로 시작한다. 서로 다른 실행의 raw window ID를 같은 대상으로 묶지 않는다.
원시 실행 간 equality 대신 각 실행 내부의 변화 패턴을 비교한다. 동일 기기/해상도/확대/방향/설정으로 실행한다.

| SOURCE | root 출처 / refresh | C 생성 후 제한 |
|---|---|---|
| COLLECTOR_FRESH | 실제 rootInActiveWindow, 모든 방문 노드 refresh 포함 | 기존 collector 차단을 그대로 결과로 기록 |
| F9_ACTIVE | 기존 일반 F9 read와 같은 rootInActiveWindow, display 예외 없음 | C overlay 존재 시 기존 OVERLAY_WINDOW 차단 가능/예상 |
| F9_WINDOW | C 전에는 같은 active APPLICATION의 window.root를 읽되 일반 F9 전체 창 검사를 유지; C 후에는 실제 DISPLAY_REVIEW scan | 현재 native View generation/ID + 생성 전 창 집합 + 실제 단일 overlay 목록의 일치가 필요 |

현재 fresh collector는 마지막에 active root와 해당 window.root를 각각 refresh하고 package/window/root bounds/frame을 다시 확인한다.
따라서 "collector가 window.root를 전혀 확인하지 않는다"는 설명도 잘못이다. 이 검사에서 서로 다르면 F0는 Blocked다.
F9 DISPLAY_REVIEW는 매 노드 refresh를 하는 collector와 동일한 캐시 접근 순서는 아니다.
양쪽 READY와 후속 차이만으로 수집 시점 사이의 변화와 캐시 차이를 구분할 수 없다.

C 전 F9_WINDOW는 **root getter만 바꾼 시험 대조**이고 운영 DISPLAY_REVIEW 성공이라고 부르지 않는다.
C 후 native ID 미확정, LIVE_COUNT=NONE/여러 개/불일치는 OWNERSHIP_UNAVAILABLE다.
NOT_ENUMERATED를 소유권 확인으로 바꾸거나 임의 ID를 넣지 않는다. 따라서 삼성의 초기 미등록 상황에서는 해당 arm이 관측 불능일 수 있다.
이때 C가 원인이라는 결론도, F9와 collector가 같다는 결론도 내릴 수 없다.

구간:
- INITIAL, BEFORE_C, AFTER_0, AFTER_150, AFTER_300. 각 구간은 위 6회 읽기를 새로 수행한다.
- INITIAL/BEFORE_C의 reader가 한 번이라도 차단되면 C를 생성하지 않고 INCONCLUSIVE로 종료한다. 직전 성공 표본을 빌려 실패 baseline을 덮지 않는다.
- A에서도 동일 evidence/B preflight를 수행한다. 시간 기준점은 C arm은 SHOWN 함수 반환 **직후**, A arm은 NOT_CREATED 기록 직후다(BOUNDARY=AFTER_PRESENT_RETURN). addView 호출 소요 시간이 이후 관측 구간을 소비하지 않는다.
- AFTER_150/300은 그 기준 이후 최소 150/300ms에 읽기를 시작하므로 SHOWN 후 117~140ms 후속 구간을 포함한다. 이 구간을 연속 촬영하거나 매 프레임 관측하는 것은 아니며 구간 사이 transient 변화는 놓칠 수 있다.
- 150/300은 **시험 표본 예약 시점**이지 승인 대기시간/안정화 시간/TTL 연장이 아니다.
- 이전 읽기가 길면 실제 구간은 더 늦어진다. START_MS/END_MS와 BOUNDARY_ELAPSED_MS를 사용하고 명목 구간으로 layout 시각을 추정하지 않는다.
- evidence preflight의 추가 F0도 BEFORE_C READ 행에 기록한다.
- 동의/일시정지 검사는 각 checkpoint에서 메모리의 CorePreferences를 확인한다. 다른 접근성 서비스 활성 여부 IPC는 arm/각 read/표시 경계에서만 검사하며 매 노드마다 호출하지 않는다.
- UiAutomation은 `getUiAutomation(FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)`로 한 번 얻고 shell fixture 명령에만 사용한다. 다른 UiAutomator/기본 flag 연결은 추가하지 않는다.
- 서비스 연결을 최대 3초 확인한 후 arm한다. 실제 P01 STATE/CONTENT 이벤트가 arm 이후 발생하여 서비스에 수신됐는지 최대 1초 확인한다. 수신된 window와 매 읽기의 실제 target window를 결합한다. 이전 실행 이벤트, 다른 package/창, 미래 시각은 수신 증거가 아니다.
- 이 기다림은 이벤트 통신 전제 확인일 뿐 geometry 안정화·표시 승인 지연이 아니다. 수신 확인 직후 진행하며 기존 read/운영 예산을 늘리지 않는다.
- 첫 성공 읽기의 package/window/frame을 조건 내 기준으로 고정한다. 이후 target 교체 또는 frame 변경은 TARGET_CHANGED/FRAME_CHANGED로 중단한다. 다른 창 결과를 새 baseline으로 교체하지 않는다. display=0 및 APPLICATION binding은 실제 reader/getWindows에서 확인한다.
- 설정 Configuration은 arm/각 읽기 전후/표시 경계에서 비교한다. 변경은 CONFIGURATION_CHANGED. 네 실행 간 설정 동일성은 사용자가 아래 전제를 기록해야 하며 접힘/확대 등 모든 OEM 설정을 인증한다고 주장하지 않는다.
- 최대 30개 계획 read + 1개 evidence F0, 8초 시험 watchdog. 각 실제 reader의 기존 100ms 제한은 그대로다.
- Binder 호출을 강제로 원자화하거나 100ms에 선점할 수 없다. 동기 읽기/진단 자체가 main queue와 접근성 캐시에 영향을 준다.
- 반복 순서를 뒤집어 편향을 살피지만 원자적 동시 관측, 원격 native generation/ABA 부재는 증명하지 않는다.

## 5. 비교 및 출력

출력은 계측 `INSTRUMENTATION_STATUS: p01_reader=...`이다. 원문 logcat/tree 덤프는 수집하지 않는다.
모든 package/window ID/path/action ID/원문/좌표/native handle은 시험 내부의 단기 비교에만 사용한다.
샘플의 toString도 redacted. 원본 token/timestamp를 복사해 실행 가능한 객체를 만들거나 Runtime에 발행하지 않는다.

예시(형식 예시이며 실측 아님):
```text
CONDITION=WITHOUT_C;ORDER=FORWARD;A_P01_READER;READ=1;PHASE=INITIAL;SOURCE=COLLECTOR_FRESH;STATUS=COMPLETE;START_MS=...;END_MS=...;EVENTS_DURING=0;F0=NONE;F9_STAGE=NONE;F9_DETAIL=NONE;F9_RELEASE=NONE;F0_DETAIL=NONE;F0_POINT=UNKNOWN;F0_RELEASE=COMPLETED
CONDITION=WITH_C;ORDER=FORWARD;PAIR=ADJACENT;FROM=COLLECTOR_FRESH;SOURCE=F9_WINDOW;PHASE=AFTER_150;BINDING=SAME;FRAME=SAME;WINDOW_SET=CHANGED;WINDOW_BOUNDS=CHANGED;WINDOW_REGION=CHANGED;ROOT_BOUNDS=CHANGED;CLOSE_BOUNDS=UNKNOWN;NODES=CHANGED;NATIVE_ACTIONS=SAME
```

- PAIR=REPEAT: 같은 reader의 직전 호출과 비교. PAIR=ADJACENT: 직전 reader와 교차 비교. 첫 표본/차단 뒤에는 UNKNOWN.
- BINDING: package/window 일치. 이것만으로 같은 native popup 세대라고 승인하지 않는다.
- WINDOW_SET: 전체 window DTO(id/type/layer/active/focused/bounds) 비교. C 추가 자체도 CHANGED이며 대상 변형과 구별해야 한다.
- WINDOW_BOUNDS: target window만 비교. WINDOW_REGION: 동일 getWindows 핸들에서 시험 전용 getRegionInScreen을 읽어 사각 조각을 비교. 서로 다른 getter 구간이며 한 프레임이라고 주장하지 않는다. 해당 read 안의 창 목록/region 변동·미확인은 UNKNOWN.
- ROOT_BOUNDS: path 0의 node bounds. window region과 합치거나 보정하지 않는다.
- CLOSE_BOUNDS: 실제 B 단일 CLOSE 후보 경로의 비geometry DTO identity를 확인한 경우에만 geometry 비교. 다른 identity이면 UNKNOWN. 전체 native action 비교는 별도 필드다.
- NODES: 전체 순서/구조/path/ID/class/내용/상태/bounds/DTO action 비교.
- NATIVE_ACTIONS: 양쪽 실제 객체에 결합된 전체 known action 집합을 정확히 비교. custom/labelled/누락/결합 실패는 UNKNOWN이지 SAME이 아니다. 비교는 정책 sidecar의 latch를 변경하지 않는 observedSummary를 쓴다.
- EVENT: package/eventTime/window 비교만 사용하고 source를 조회하지 않고 event 종류/mask/관계 범주만 기록(최대48행). OCCURRENCE_MS와 DELIVERY_MS는 각각 eventTime와 수신 시각이며 layout 시각은 아니다. 동기 read는 main queue를 점유하므로 EVENTS_DURING=0이 원격 화면 불변을 증명하지 않는다.
- F0 Blocked/F9 stage/detail/release 실패는 정상 관측으로 승격하지 않는다. COMPLETE F0는 기존 resource wrapper가 정상 반환한 경우만이다.
- SESSION=STARTED/ENDED와 PHASE_STATUS=STARTED/COMPLETED를 기록한다. SERVICE=CONNECTED와 RECEPTION=HOST_EVENT_CONFIRMED를 구분한다.
- 읽기별 C_BEFORE/C_AFTER는 NOT_CREATED/NATIVE_ATTACHED/UNCONFIRMED/LOST. SHOWN은 C 함수 반환이고 NATIVE_ATTACHED는 View lifecycle로 확인한 상태다. 읽기 후 상태를 확인하기 전에 반환한 실패에서는 C_AFTER=UNCONFIRMED다. 실제 렌더 완료는 직접 검사하지 않아 항상 RENDER=UNOBSERVED다. C 부착 미확인/소실 이후 읽기를 'C가 계속 존재한 조건'으로 승인하지 않는다.
- SUMMARY의 ISSUE는 최초 고정 원인 코드이며 후속 cleanup 결과가 덮지 않는다. UIAUTOMATION_UNAVAILABLE, SERVICE_UNAVAILABLE, SETUP_REQUIRED, EVENT_UNCONFIRMED, TARGET_CHANGED, FRAME_CHANGED, CONFIGURATION_CHANGED, SERVICE_DISCONNECTED, INVALIDATED, STALE_CALLBACK, NATIVE_C_UNCONFIRMED, C_LOST, READ_UNAVAILABLE, CLEANUP_FAILED, TIME_LIMIT, CANCELLED 등을 구분한다.
- SUMMARY=OBSERVED는 read/teardown 완료라는 시험 상태일 뿐 동일성/원인/기능 성공 승인이 아니다. C 후 F9_ACTIVE의 정확한 ACCESSIBILITY_OVERLAY_PRESENT만 예상된 안전 차단 대조로 따로 세어 OBSERVED_WITH_GUARD_BLOCK으로 기록한다. 그 읽기는 여전히 BLOCKED이고 pair 값은 UNKNOWN이다. 다른 차단, F9_WINDOW missing arm, native 미확인, timeout, cleanup 실패는 INCONCLUSIVE이며 JUnit도 실패한다. 예상 차단을 F9 display 관측 성공으로 바꾸지 않는다.

## 6. 실행 방법 — 전용 에뮬레이터용, 이번 작업에서는 미실행

개인정보 없는 전용 API 37 에뮬레이터만 대상으로 한다. 이번 단계에서 기기 명령은 실행하지 않았다.
네 조건 전제표를 로그 폴더에 함께 수동 기록한다: 같은 emulator/API·빌드, 방향·접힘 상태,
화면 크기/글꼴·표시 배율/확대 기능/키보드/시스템 overlay·animation 설정, TalkBack OFF.
네 조건 사이 설정을 바꾸지 않는다. 설정 전제가 달라지면 결과를 묶어 결론 내리지 않는다. 아래 명령으로 삼성/LG 실측을 실행하지 않았다.
ADB로 접근성 활성화/동의/허용 앱 설정을 강제하지 않는다. 설정 UI를 사람이 직접 조작한다.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "emulator-5554" # adb devices -l에서 전용 에뮬레이터인지 확인
& $adb devices -l
& $adb -s $serial shell getprop ro.build.version.sdk
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :popup-test-app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw "APK 빌드 실패: 이전 APK로 시험하지 않음" }
& $adb -s $serial install -r app\build\outputs\apk\debug\app-debug.apk
if ($LASTEXITCODE -ne 0) { throw "Debug APK 설치 실패: 시험 중단" }
& $adb -s $serial install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
if ($LASTEXITCODE -ne 0) { throw "AndroidTest APK 설치 실패: 시험 중단" }
& $adb -s $serial install -r popup-test-app\build\outputs\apk\debug\popup-test-app-debug.apk
if ($LASTEXITCODE -ne 0) { throw "합성 앱 APK 설치 실패: 시험 중단" }
& $adb -s $serial shell am start -n kr.closeforyou/.app.MainActivity
```

AndroidTest APK는 test-only 설치를 위해 `-t`를 포함한다. APK 교체로 프로세스·서비스 연결이 끊길 수 있으므로
이전 Bound 상태를 재사용하지 않는다. 설치 후 UI 설정과 **실행 직전** Bound 확인을 다시 수행한다.

UI 준비:
1. 수집 동의를 확인하고 분석 시작. 허용 앱은 `kr.closeforyou.popuptest` 한 개만.
2. 캡처 동의 OFF. 자동 닫기/자동 SKIP/기억 OFF. 시험은 OCR·스크린샷 포트를 연결하지 않는다.
3. 접근성 설정에서 아래 상태를 사람이 직접 설정한다. 명령으로 service/권한/동의를 부여하지 않는다.

| 설정 화면 이름(현재 resource) | 정확한 component | 상태 |
|---|---|---|
| 닫아줄게 | `kr.closeforyou/kr.closeforyou.accessibility.CloseForYouAccessibilityService` | OFF |
| 닫아줄게 C 선택 창 시험 | `kr.closeforyou/kr.closeforyou.ui.voice.debug.ChoiceOverlayTestService` | OFF |
| P01 읽기 대조 시험 (Debug) | `kr.closeforyou/kr.closeforyou.accessibility.testing.P01ReaderComparisonService` | ON |
| TalkBack 및 그 외 접근성 서비스 | 기기별 표시 이름 확인 | 모두 OFF |

시험은 enabled service가 위 시험 component 하나뿐인지 실제 package와 class 모두 확인한다.
4. **P01 읽기 대조 시험 (Debug)**만 직접 켠다. 이 통제 조건은 TalkBack 문제 전체를 재현하는 시험이 아니다.
5. 시험 중 화면을 터치/스크롤/클릭하지 않는다. 사용자 입력은 시험 취소 사유다.
6. 실행 전 아래 읽기 전용 조회에서 시험 서비스가 **Bound 목록**에 있는지 확인한다.
   Enabled 목록의 이름만으로 Bound라고 판정하지 않는다. 전체 dumpsys 원문은 일반 로그로 저장/공유하지 않는다.

```powershell
& $adb -s $serial shell dumpsys accessibility |
    Select-String -Pattern "Bound services|boundServices|P01ReaderComparisonService"
```

출력 구조는 Android 버전별로 다를 수 있다. Bound 위치를 식별할 수 없으면 시작을 보류한다.
실행 이후에도 `SERVICE=CONNECTED`, `RECEPTION=HOST_EVENT_CONFIRMED`가 별도로 필요하다.
이 둘은 P01 전체 트리 준비 증거가 아니며 새 출력은 `POPUP_READY=UNCONFIRMED`를 명시한다.


```powershell
$runner = "kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner"
$class = "kr.closeforyou.accessibility.integration.P01ReaderComparisonInstrumentedTest"
$methods = @("withoutCForward", "withoutCReverse", "withCForward", "withCReverse")
$logDir = Join-Path (Get-Location) ("p01-reader-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Path $logDir -ErrorAction Stop | Out-Null
foreach ($method in $methods) {
    $logFile = Join-Path $logDir "$method.txt"
    if (Test-Path -LiteralPath $logFile) { throw "기존 로그 보존: 새 출력 폴더 사용" }
    & $adb -s $serial shell am instrument --no-restart -w `
        -e class "$class#$method" -e p01ReaderComparison true -e dedicatedEmulator true $runner `
        2>&1 | Tee-Object -FilePath $logFile
    $adbExit = $LASTEXITCODE
    $result = Get-Content -Raw $logFile
    if ($adbExit -ne 0 -or $result -notmatch 'SUMMARY=OBSERVED(?:_WITH_GUARD_BLOCK)?;' `
        -or $result -notmatch 'OK \(1 test\)' -or $result -match 'INCONCLUSIVE|FAILURES|INSTRUMENTATION_FAILED') {
        Write-Host "대조 중단: $method, 앞선 로그 보존. UI에서 시험 서비스를 끄고 정리 상태 확인."
        break
    }
}
Select-String -Path (Join-Path $logDir "*.txt") -Pattern "p01_reader=|COMPARISON_|FAILURES|OK \("
```

4 method는 A/B/C/D 순서의 대조 묶음이며 자동 클릭/닫기 재시도 루프가 아니다.
각 method는 명시된 별도 `am instrument` 호출이다. `withoutCForward`/`withCForward`는
COLLECTOR×2→F9_ACTIVE×2→F9_WINDOW×2, Reverse는 그 역순이다.
STARTED만 있고 ENDED/유효 SUMMARY/JUnit OK가 없으면 중단/미완료다. skip/기기 미연결도 성공이 아니다.
한 조건에서 INCONCLUSIVE나 cleanup 미확인 시 후속 조건은 실행하지 않고 전체 로그를 보존한다.
이 환경에서 사용자가 확인한 `--no-restart`로 target process 재시작을 요청하지 않는 실행을 사용한다.
옵션이 서비스 연결/수신/팝업 준비를 보증하지는 않는다. 서비스의 실제 Bound 상태와 SESSION 로그를 각각 확인한다.
정상 종료나 프로세스 사망을 넘어서 failed latch가 유지된다고 주장하지 않는다. 실패 뒤 자동 재실행하지 않는 위 PowerShell과 아래 사용자 종료 절차가 필요하다. INCONCLUSIVE가 나면 로그를 남기고 원인을 확인한다.
C teardown이 UNCONFIRMED이면 같은 서비스 인스턴스의 후속 arm을 거부한다. 단순 HEALTH 초기화 코드가 없다.
실제 창 제거가 의심되면 사람이 시험을 종료하고 서비스 OFF로 정리한다. 이 절차를 production Cleanup 성공으로 기록하지 않는다.
시험 도중 연결 끊김·동의 철회·다른 서비스 활성화·사용자 입력·Configuration 변화는 실패다.
마지막 callback은 arm epoch와 active 상태를 확인하며 이전 조건 callback은 새 조건의 선택으로 처리하지 않는다.
cleanup 이후 늦은 callback도 실패 latch로 남겨 같은 service instance의 다음 arm을 막는다.

안전한 종료/복귀(UI):
1. 명령 중단·INCONCLUSIVE이면 로그를 보존하고 **P01 읽기 대조 시험 (Debug)**를 OFF로 한다.
2. C 창이 없어졌는지 확인하고 P01 앱을 종료한다. 필요할 때 합성 앱만 `& $adb -s $serial shell am force-stop kr.closeforyou.popuptest`로 종료한다.
3. 창 제거가 불확실하면 시험을 계속하지 않는다. 정상 Android 서비스 종료/사용자 재시작 뒤에도 앞선 실패 기록은 유지한다.
4. 시험 서비스를 OFF로 유지한 상태에서 원래 켜져 있던 운영 A 서비스·TalkBack과 원래 동의/허용 앱/분석 설정을 UI로 복구한다. C 별도 시험 서비스는 원래 상태로 돌린다.
5. 재시작 뒤 새 대조를 할 때는 네 조건 전제를 다시 확인하고 새 로그 폴더를 쓴다. HEALTH 강제 초기화나 이전 실패를 성공으로 수정하는 절차는 없다.

## 7. 한 대조 묶음의 판정표

| 관측 패턴(유효한 같은-target pair만) | 다음 조사 대상 | 금지 결론 |
|---|---|---|
| C 없는 A에서도 두 reader의 ROOT/닫기/노드가 시간에 따라 함께 변경 | 대상 layout/접근성 표현의 후속 변화 | C가 원인이라는 확정 |
| C 전부터 C-C와 F9-F9는 같지만 C-F9가 반복해서 다름, 역순도 같은 방향 차이 | root getter/refresh/cache/좌표·region 경로 | 임의로 bounds 무시/보정 |
| A는 관측 구간에서 동일, B에서만 변화, 두 읽기 순서에서도 재현 | C View 생성/포커스/inset 및 관측 간섭 | 특정 C 코드/OEM 버그 확정 |
| window region만 달라지고 node/window rect는 같음 | region 변화 이벤트와 node 변경을 분리 | BOUNDS 이벤트만으로 node 이동 확정 |
| native/창 목록/reader/후보 정보 UNKNOWN 또는 BLOCKED | 해당 비교 불능, 기존 거부 이유 유지 | C 유무 차이의 확정 원인 |
| JVM fixture만 재현 | 판정표·포맷·불확실성 처리를 검증한 것 | Android/삼성 재현 성공 |

A/B의 측정 구간이 다르거나 이벤트/예산/observer overhead가 큰 경우 결론은 제한된다.
추가 허용 예외는 넣지 않았으며, 이 단계에는 운영 결함의 수정 전→후 Android 재현 증거가 없다.
시험 코드의 1차 패키징에서는 Release 무동작 feature가 testing namespace에 놓여 기존 DEX 격리 검사에서
`FAIL: Debug probe leaked into release APK`가 실제 발생했다. 검사 기준을 수정하지 않고 feature를 A reader의
Debug/Release variant 위치로 옮겼다. 시험 service/비교기/문자열의 Release 부재와 no-op bytecode를 별도로 검사한다.

## 8. 최종 전제 리뷰에서 보완한 사항

검토 전에는 연결 pointer만 확인하고 대상 이벤트 수신은 완료 조건으로 사용하지 않았다.
reader pair가 TARGET 변경을 기록해도 조건의 target을 고정하지 않았고, 늦은 native callback의
`check(native === lifetime)`이 종료 이후 예외를 던질 수 있었다. 최종 보완은 모두 이번 시험 파일 안에만 있다.

- 실제 연결 + arm 이후 host STATE/CONTENT 이벤트 수신 + 읽은 창과의 결합을 요구한다.
- 조건 전체의 첫 package/window/frame 결합을 고정하고 변경 시 중단한다.
- callback의 arm epoch/active 상태를 검사하고 늦은 callback은 fail-closed하며 framework callback에서 raw 예외를 던지지 않는다.
- SHOWN/native 부착/렌더링 미확인을 분리하고 C 상태 상실·미확인을 완료로 승인하지 않는다.
- 연결 이전 실패부터 종료까지 fixed ISSUE/phase/summary를 남긴다. 첫 실패와 cleanup 확인 결과를 분리한다.
- 완료 판정은 이벤트 수신, 5개 phase 완료, 모든 필수 read, 정리 성공을 요구한다. 종료 후 대상/window/frame 비교값은 지우고 고정 원인 코드와 수신 여부만 남긴다.
- 150/300ms 기준점을 C 표시 호출 전에서 **SHOWN 반환 후**로 수정했다. 호출 지연으로 후속 관측 구간이 짧아지는 문제를 막는다. 렌더 완료 시각으로 부르지 않는다.
- 추가 회귀 11개는 실제 service/driver가 호출하는 ComparisonRunChecks, comparisonCState,
  comparisonComplete 경계를 검사한다. 실제 Android 실행을 대체하지 않는다.

운영 정책과 AndroidF9ScreenPort의 기존 시험 진입점은 최종 보완에서 추가 변경하지 않았다.
기존 12개 파일 안에서 보완했으며 파일 수를 맞추려고 다른 변경을 제외한 것이 아니다.

## 9. 이전 전달 단계의 로컬 검증과 해결 상태 (997437c)

실제 변경 파일(12개; 기존 구조 검토 문서는 제외):

1. `app/src/main/java/kr/closeforyou/accessibility/action/AndroidF9ScreenPort.kt`
2. `app/src/debug/AndroidManifest.xml`
3. `app/src/debug/java/kr/closeforyou/accessibility/action/P01ReaderComparisonFeature.kt`
4. `app/src/release/java/kr/closeforyou/accessibility/action/P01ReaderComparisonFeature.kt`
5. `app/src/debug/java/kr/closeforyou/accessibility/testing/P01ReaderComparison.kt`
6. `app/src/debug/java/kr/closeforyou/accessibility/testing/P01ReaderComparisonService.kt`
7. `app/src/debug/res/values/a_p01_reader_test.xml`
8. `app/src/debug/res/xml/a_p01_reader_test_service.xml`
9. `app/src/androidTest/java/kr/closeforyou/accessibility/integration/P01ReaderComparisonInstrumentedTest.kt`
10. `app/src/testDebug/java/kr/closeforyou/accessibility/testing/P01ReaderComparisonTest.kt`
11. `app/src/testRelease/java/kr/closeforyou/accessibility/testing/P01ReaderComparisonReleaseTest.kt`
12. `docs/A_P01_READER_C_COMPARISON_2026-10-31.md`

로컬 검증(2026-10-31):

| 항목 | 결과 |
|---|---|
| 앱 Debug JVM | 이번 최종 보완 후 재실행: 1,575 / 실패·오류·skip 0 |
| 앱 Release JVM | 기존 1,494개 통과 결과 재사용; 이번 Gradle 작업 UP-TO-DATE |
| 호스트 JVM | 기존 55개 통과 결과 재사용; 이번 Gradle 작업 UP-TO-DATE |
| 합성 앱 JVM | 기존 26개 통과 결과 재사용; 이번 Gradle 작업 UP-TO-DATE |
| 앱 Debug/Release APK | Debug 재빌드 통과 / Release 기존 빌드 UP-TO-DATE |
| 앱/호스트 AndroidTest APK 및 합성 앱 Debug APK | 빌드 통과; 계측 실행 아님 |
| 앱 Debug/Release 및 호스트 Lint | issue 0 |
| APK/DEX 격리 | 기존 검사 통과; 시험 서비스/비교기와 진단 문자열의 Release 부재 확인 |
| Release 비교 진입점 bytecode | scan/getWindows/root getter 호출 없이 null 반환 |
| CRLF 및 공백 | 원본 F9 CRLF 보존; CRLF-aware 검사 통과 |
| Android A/B 4조건 | 모두 미실행: adb 연결 기기 0, emulator 바이너리/AVD 없음 |

기존 전달 전 결과는 Debug 1,564 / Release 1,494였다. 최종 전제 보완으로 Debug 공유 경계 회귀 11개를 추가하고,
`/workspace/a-p01-reader-review-full.log`의 전체 작업을 다시 실행했다(BUILD SUCCESSFUL, 250 tasks).
Release·호스트·합성 앱 JVM과 Release APK/Lint는 변경이 없어 Gradle UP-TO-DATE 결과를 재사용했다.
대상 27개 회귀도 별도 실행했다. 첫 실행의 한 테스트는 교체 window ID만 바꾸고 window DTO를 바꾸지 않아
TARGET_UNCONFIRMED를 반환했다. fixture를 실제 일관된 교체 창으로 바로잡은 뒤 27개 모두 통과했다.
이는 Android 실측 실패/수정 증거가 아니라 새 회귀 fixture 보정이다.
그 뒤 AndroidTest의 관측 기준점을 SHOWN 반환 후로 옮긴 APK/Debug Lint도 통과했다
(`/workspace/a-p01-reader-review-final-instrumentation.log`). 조기 실패 read의 C_AFTER를 이전 상태로
채우지 않고 UNCONFIRMED로 남긴 전체 검증도 통과했다(`/workspace/a-p01-reader-review-final-all.log`).
마지막으로 종료 후 대상·window/frame 메타데이터 해제를 회귀로 확인했고, 전달 소스 전체 검증은
`/workspace/a-p01-reader-review-delivery.log`에서 BUILD SUCCESSFUL로 완료했다. 최종 Debug 1,575개 전부 통과(실패/오류/skip 0), Lint issue 0이며 최종 APK/DEX 격리도 재검사했다.
- 누적 새 JVM: ReaderComparison/전제 경계 Debug 28개 + Release 격리 1개.
- Android 계측 4조건은 컴파일 검증만 가능. 기기 미연결로 모두 미실행.
- 새 비교기: root/본문/후보/region/frame/action 변화, 객체 결합 불일치, missing read, identity 변경, 고정코드 비노출.
- 기존 B안, F0/F9, 표시·실행 분리 및 cleanup 회귀는 변경하지 않고 전체 회귀에서 함께 실행한다.

선택창 유지: 미해결/실기기 미검증. 수동 닫기: 이번 시험에서 실행하지 않음.
Cleanup RETIRED_ID_EVENT/ADDED/HEALTH: 별도 미해결 그대로. test teardown 확인은 production barrier 해결 증거가 아니다.
다른 9개 worktree, 기존 구조 검토 문서, P23/C/B/D 코드, 원본 P01은 보존한다.
이전 전달 단계에서는 사용자 승인에 따라 위 12개 파일을 커밋·일반 푸시했다. 10절 최초 진단 구현 단계에서는 커밋·푸시하지 않았다. 이번 최종 전달은 별도 사용자 승인에 따라 검토를 통과한 10개 파일만 커밋·일반 푸시한다.
main 병합/force push/PR 생성/기기 실행은 하지 않는다. 삼성 P01 해결 또는 네 조건 성공을 주장하지 않는다.


## 10. 첫 F0 실패 세부 진단 보완 (기준 997437cedff68d1bfcc806f756e3c5c10034f9df)

### 10.1 실제 관측과 확정 범위

사용자 제공 API 37 / emulator-5554 요약 (`am instrument --no-restart`, 시험 서비스만 ON):

```text
SERVICE=CONNECTED;RECEPTION=HOST_EVENT_CONFIRMED
READ=1;PHASE=INITIAL;SOURCE=COLLECTOR_FRESH;START_MS=505;END_MS=595
STATUS=BLOCKED;F0=UNREADABLE_TREE;F0_RELEASE=UNCONFIRMED
READ=2~12: COMPLETE;BEFORE_C 후속 유효 비교 SAME
SUMMARY=INCONCLUSIVE;ISSUE=BASELINE_UNAVAILABLE
COMPLETE_READS=11;UNAVAILABLE_READS=1;COMPLETED_PHASES=2;CLEANUP=CONFIRMED;EXTERNAL_CLICK=NOT_WIRED
```

참조된 원본 `p01-reader-withoutCForward-norestart.txt`는 이번 작업 환경/첨부 목록에서 접근할 수 없었다.
위 내용은 사용자가 제공한 이전 버전 로그 발췌이며 원본 파일을 열어 전체 순서를 검증했다고 주장하지 않는다.
새 F0_DETAIL/F0_POINT는 아직 기기에서 관측되지 않았다. 새 코드를 과거 로그에 소급 적용하지 않는다.
이전 F0_RELEASE=UNCONFIRMED는 당시 Ready 기반 표시였으므로 실제 해제 실패를 입증하지 않는다.
원래 실패 로그는 보존한다. 새 파일이나 후속 성공으로 덮어쓰지 않는다.

확정: 시험 서비스 연결과 실제 F0 읽기에 도달했고 첫 capture가 Blocked였다.
이후 11개 성공과 BEFORE_C의 SAME은 첫 실패를 지우지 않는다. C는 생성하지 않았고 AFTER 구간도 실행하지 않았다.
`CLEANUP=CONFIRMED`는 시험 종료 정리 결과이며 **그 첫 capture의 해제 성공 증거가 아니다**.
대조 성공·삼성 원인 규명·C 영향 확인이 아니다.

현재 `UNREADABLE_TREE`만으로 root/child 누락, refresh 실패, 종료 root/window/frame 불일치,
일반 예외, 정리 실패를 특정할 수 없다. refresh의 시간 제한 실패도 기존대로 UNREADABLE_TREE이고,
별도의 traversal/end-budget 초과는 기존대로 TRUNCATED_TREE다. 제공된 90ms 구간을 timeout 원인으로 해석하지 않는다.
실제 다음 실패 분기의 고정 코드를 얻기 전에는 재시도/실패 무시를 도입하지 않는다.

### 10.2 진단 경계와 코드

시험 service의 **각 COLLECTOR_FRESH 읽기마다 새 CaptureReadTrace**를 만들고
`CaptureTrace(null, collectionRead=...)`로 전달한다. 기존 CaptureDiagnosticSession은 연결하지 않는다.
따라서 session bind를 필요로 하는 title getter와 legacy maximumWindowMetrics getter가 새로 켜지지 않는다.
진단 객체에는 enum/boolean만 있고 package·node·path·bounds·예외 객체를 보관하지 않는다.
로그 sink는 capture 내부에 없으며 기존 READ 보고 한 줄에만 필드를 붙인다.
추가 수집, refresh, getter, 시계, OCR, IPC 호출은 없다. 기존 getter/검사 순서를 유지한다.
고정 코드 기록의 CPU 오버헤드는 있으므로 시간 경계에 대한 관측 간섭이 0이라고 보증하지 않는다. 기존 예산은 늘리지 않는다.

```text
...;READ=1;PHASE=INITIAL;SOURCE=COLLECTOR_FRESH;STATUS=BLOCKED;F0=UNREADABLE_TREE;...
F0_DETAIL=REFRESH_FAILED;F0_POINT=PROTECTION_PASS;F0_RELEASE=COMPLETED
```

위는 **형식 예시**이며 이번 실측의 확정 원인이 아니다. 실제로는 한 줄로 출력된다.

| 실제 첫 실패 분기 | F0_DETAIL | 해석/한계 |
|---|---|---|
| 최초 active root/package 없음 | ROOT_UNAVAILABLE / ROOT_PACKAGE_UNAVAILABLE | 해당 getter가 null. null의 원격 내부 원인은 UNKNOWN |
| 같은 앱 배경 창 root/package 없음 | BACKGROUND_ROOT_UNAVAILABLE / BACKGROUND_PACKAGE_UNAVAILABLE | 이전 WINDOW_LIST_UNAVAILABLE에서 진단만 분리 |
| child 조회 null | CHILD_UNAVAILABLE | 원본 경로/index는 기록하지 않음 |
| refresh() false | REFRESH_FAILED | API가 false 반환. 예외와 구분하며 장치 내부 원인은 추정하지 않음 |
| refresh 전 window ID 음수/refresh 후 다름 | INVALID_WINDOW / REFRESH_WINDOW_CHANGED | 이미 읽은 값 비교, 실제 ID 출력 없음 |
| clock 역행/기존 deadline 초과 | INVALID_CLOCK / TIME_LIMIT | 새 clock 조회 없음. 기존 reason·100ms 경계 그대로 |
| pass 중 package/window 결합 실패 | NODE_PACKAGE_MISMATCH / NODE_WINDOW_MISMATCH | F0 전체 차단 유지 |
| 종료 active root 없음/ID/package/bounds 차이 | END_ROOT_UNAVAILABLE / END_ROOT_WINDOW_MISMATCH / END_ROOT_PACKAGE_MISMATCH / END_ROOT_BOUNDS_MISMATCH | 기존 OR 조건을 기존 순서대로 분리 |
| 종료 window.root 없음/ID/package/bounds 차이 | END_WINDOW_ROOT_UNAVAILABLE / END_WINDOW_ROOT_ID_MISMATCH / END_WINDOW_ROOT_PACKAGE_MISMATCH / END_WINDOW_ROOT_BOUNDS_MISMATCH | active root와 window.root의 다른 소스 구분 |
| 종료 frame 차이/읽기 불가 | END_FRAME_MISMATCH | 기존 frame 결과가 다르거나 null. 내부 geometry 실패 원인은 이 코드만으로 확정 불가 |
| 종료 display/창 DTO 목록 차이 | END_DISPLAY_MISMATCH / END_WINDOWS_MISMATCH | 새 창 조회 없음, 기존 비교 관찰 |
| node/depth/text 제한 | NODE_LIMIT / DEPTH_LIMIT / TEXT_LIMIT | 기존 제한 그대로 |
| 기존 보호 검사 | TREE_PROTECTION, PACKAGE_GATE 등 | F0 reason과 함께 판독, 보호 내용 원문 없음 |
| 일반 수집 예외/취소 | COLLECTION_EXCEPTION / COLLECTION_CANCELLED | 예외 type/message 미출력. point 안에서 어느 Android getter인지는 UNKNOWN일 수 있음 |
| 실제 release 호출/배치 실패·취소 | RELEASE_FAILED / RELEASE_CANCELLED | 앞선 수집 실패가 있으면 그 detail을 유지하고 F0_RELEASE만 별도 보고 |

`F0_POINT`는 첫 실패를 기록할 때 저장된 **마지막 처리 구간 표식**이다.
정확한 실패 predicate는 F0_DETAIL로 구분하고, POINT만으로 특정 getter나 내부 원인을 추정하지 않는다.
release 자체가 최초 실패면 해당 해제 wrapper의 구간을 사용한다. 구간 값은 다음과 같다: ROOT, ROOT_REFRESH, WINDOWS, TARGET_BINDING,
BACKGROUND_ROOT, FRAME, PROTECTION_PASS, SNAPSHOT_PASS, END_ACTIVE_ROOT, END_WINDOW_ROOT,
END_FRAME, END_WINDOWS, END_BUDGET, RESULT, CHILD_RELEASE, ROOT_RELEASE, WINDOW_RELEASE, RELEASE_BATCH.
구간이 확인되지 않으면 UNKNOWN. 새 세부 코드가 root bind 이전에도 남으며 최초 사유/point만 유지한다.
첫 guard를 throw 이전에 기록하고 child body 예외도 cleanup 이전에 관찰하여 연쇄 해제 실패가 원인을 덮지 못한다.
원래 취소 전파/예외 우선순위/Blocked reason은 바꾸지 않는다.

`F0_RELEASE`의 정확한 의미:
- COMPLETED: 실제 확보한 child/root/window의 로컬 release 호출과 전체 root/window 배치가 예외 없이 완료됨.
- FAILED / CANCELLED / FAILED_AND_CANCELLED: 실제 호출에서 관측한 실패. 나머지 batch cleanup은 원래대로 시도한다.
- UNKNOWN: F9 읽기, 미시작, 해제할 객체를 얻지 못함, 전체 해제 완료를 관찰하지 못함.
- Ready 유무로 계산하지 않는다. Blocked + COMPLETED와 정상 body + FAILED가 모두 가능하다.
- API 33+의 recycle은 pooling 제거로 무동작일 수 있다. COMPLETED는 로컬 API 호출 완료이지
  원격 native 해제·창 제거·C cleanup·F0 승인 또는 native generation 증명이 아니다.

### 10.3 준비 신호와 INITIAL 분리 검토

`awaitReception()`은 arm 이후 HOST package의 STATE/CONTENT 이벤트를 받았는지만 확인한다.
첫 `am start -W ... f9_defer_popup true`의 배경 Activity 이벤트도 조건을 만족할 수 있다.
두 번째 `am start ... f9_show_deferred_popup true`와 onNewIntent/Dialog.show()/setLayout() 뒤의
원격 트리 읽기가 모두 준비됐음을 확인하는 handshake는 아니다. 실제 읽은 창과 수신 창의 결합 검사는
계속하지만 이 역시 “P01 layout 완료”를 뜻하지 않는다.

공식 근거와 구현 근거:
- [adb am start -W](https://developer.android.com/tools/adb#am): launch 완료까지 기다리는 옵션이다. 임의 Dialog의 후속 layout/전체 접근성 트리 완료 계약은 없다.
- [AccessibilityEvent TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOW_CONTENT_CHANGED](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent): UI 구역 또는 내용 변화 이벤트다. 트리 전체를 원자적으로 읽을 수 있는 준비 신호로 정의되지 않는다.
- [AccessibilityNodeInfo.refresh](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#refresh()): 해당 View의 최신 상태 갱신 API이며 false는 obsolete node 계약이다. 원격 전체 트리의 동시성이나 native 세대 증명은 제공하지 않는다.
- [AccessibilityNodeInfo.recycle](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#recycle()): API 33부터 deprecated, pooling 중단 후 호출 효과가 없다.
- AOSP **android-15.0.0_r1** [ActivityManagerShellCommand.runStartActivity](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/core/java/com/android/server/am/ActivityManagerShellCommand.java): `-W` 경로는 startActivityAndWait/WaitResult를 사용한다.
- 같은 버전의 Dialog.show/addView, Window.setLayout/속성 전달, ViewRootImpl의 예약 traversal,
  접근성 Handler 이벤트 전달 경계는 2절 링크와 같다. API 37/OEM 구현과 동일하다고 간주하지 않는다.
- 실제 P01 fixture는 background setContentView → deferred onNewIntent → Dialog.show → setLayout 순이다.
  이 코드와 공식 API는 초기 변화 가능성의 근거이고 **READ1 실패 원인의 증거는 아니다**.

준비 단계 분리는 향후 시험 해석에 유용하지만 이번에는 **구현하지 않는다**.
원인을 확인한 뒤 필요하면 PREPARATION 표본(실패 포함)을 별도 보존하고, 명시적인 대상/보호/자원 확인을
통과한 시점을 새로운 INITIAL로 정의하는 계약을 검토할 수 있다. 그 경우 초기 변화를 측정 밖으로
밀어내는 편향과 기존 INITIAL 기준과 비교할 수 없다는 점을 함께 기록해야 한다.
UNKNOWN/보호 차단/해제 실패는 일시적 오류로 분류하지 않는다. 성공까지 반복, blocked 카운터 초기화,
첫 실패 덮기, 고정 sleep 승인, 수집 예산/TTL 확대는 도입하지 않았다.

### 10.4 다음 한 번의 진단 실행 (명령만 준비; 이번 작업에서 미실행)

6절 APK 빌드/설치와 UI 설정, Bound 확인을 한 뒤 **withoutCForward 한 조건만** 먼저 실행한다.
사용자가 확인한 --no-restart를 유지하며 전체 원래 로그와 별도 폴더로 저장한다.

```powershell
$runner = "kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner"
$class = "kr.closeforyou.accessibility.integration.P01ReaderComparisonInstrumentedTest"
$logDir = Join-Path (Get-Location) ("p01-first-f0-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Path $logDir -ErrorAction Stop | Out-Null
$logFile = Join-Path $logDir "withoutCForward-norestart.txt"
if (Test-Path -LiteralPath $logFile) { throw "기존 로그 보존: 새 출력 폴더 사용" }
& $adb -s $serial shell am instrument --no-restart -w `
    -e class "$class#withoutCForward" -e p01ReaderComparison true -e dedicatedEmulator true $runner `
    2>&1 | Tee-Object -FilePath $logFile
Select-String -Path $logFile -Pattern "SERVICE=|RECEPTION=|READ=|F0_DETAIL=|SUMMARY=|FAILURES|OK \("
```

첫 BLOCKED/CANCELLED/FAILED READ의 F0_DETAIL + F0_POINT + F0_RELEASE를 함께 본다.
COLLECTION_EXCEPTION/UNKNOWN이면 그 구간의 내부 원인은 여전히 미확정이다. 이번에 실패가 재현되지 않아도
기존 READ1 실패 원인이 해결됐다고 판단하지 않는다. INCONCLUSIVE이면 후속 세 조건은 실행하지 않고
6절 UI 종료/복구 절차로 돌아간다. 권한·접근성 설정을 명령으로 강제 변경하지 않는다.

### 10.5 이번 변경 및 검증

이번 변경은 다음 10개 파일이며 원래 12개 전달 목록(9절)과 구분한다.

1. `app/src/main/java/kr/closeforyou/accessibility/AndroidSnapshotCollector.kt`
2. `app/src/main/java/kr/closeforyou/accessibility/CaptureDiagnostics.kt`
3. `app/src/main/java/kr/closeforyou/accessibility/PresentationNodeRefresh.kt`
4. `app/src/debug/java/kr/closeforyou/accessibility/CaptureReadTrace.kt` (신규)
5. `app/src/release/java/kr/closeforyou/accessibility/CaptureReadTrace.kt` (신규 무동작 stub)
6. `app/src/debug/java/kr/closeforyou/accessibility/testing/P01ReaderComparisonService.kt`
7. `app/src/test/java/kr/closeforyou/accessibility/integration/PresentationNodeRefreshTest.kt`
8. `app/src/testDebug/java/kr/closeforyou/accessibility/CaptureReadTraceTest.kt` (신규)
9. `app/src/testRelease/java/kr/closeforyou/accessibility/CaptureReadTraceReleaseTest.kt` (신규)
10. `docs/A_P01_READER_C_COMPARISON_2026-10-31.md`

공통 collector/helper 수정은 기존 결과를 관찰하는 상세 코드와 callback 전달에 한정한다.
수집/보호/refresh/endpoint/cleanup의 원래 predicate, getter 순서와 횟수, 예산 및 결과를 유지한다.
시험 driver의 읽기 순서·반복 수·baseline 거부·카운터와 기존 운영 service/policy는 변경하지 않았다.
Release는 CaptureReadTrace 무동작 stub만 사용하며 시험 service/log formatter는 포함하지 않는다.

최종 로컬 검증 (2026-10-31, JDK 17, offline):

| 검증 | 결과 / 재실행 구분 |
|---|---|
| 대상 Debug / Release JVM | 이번 재실행 55 / 18 통과, 실패·오류·skip 0 |
| 전체 앱 Debug JVM | 이번 재실행 **1,589** 통과, 실패·오류·skip 0 |
| 전체 앱 Release JVM | 이번 재실행 **1,498** 통과, 실패·오류·skip 0 |
| 호스트 / 합성 앱 JVM | **55 / 26** 통과 기록 재사용, 이번 task UP-TO-DATE |
| 앱 Debug / Release APK | 이번 변경 반영 재빌드 통과 |
| 앱·호스트 AndroidTest APK | build task 통과(UP-TO-DATE), 실행 아님 |
| 호스트 / 합성 앱 Debug APK | build task 통과(UP-TO-DATE) |
| 앱 Debug / Release 및 호스트 Lint | 전체 task 통과, XML issue 0 |
| APK/DEX | 기존 verify_capture_test_apks.py 통과; 새 로그 필드/시험 서비스 Debug 존재, Release 부재 |
| Release probe | 실제 DEX instance field 0개(Debug 5개), JVM bytecode 무동작 stub 확인 |
| 공백 / CRLF | CRLF-aware diff 검사 통과; collector 기존 CRLF 유지; 신규 파일 실제 후행 공백 없음 |
| 보존 상태 | 10개 worktree HEAD 유지, 다른 9개 status 유지, 보호 문서 bytes 유지; staging 없음 |
| Android 실행 | **미실행**, 이번 작업에서 adb/device 명령 실행 없음 |

전체 실행 기록: `/workspace/a-p01-first-f0-full.log` (BUILD SUCCESSFUL, 250 tasks),
대상 회귀: `/workspace/a-p01-first-f0-targeted-final.log`,
패키징: `/workspace/a-p01-first-f0-packaging.log`, Release stub 확인: `/workspace/a-p01-first-f0-release-stub.javap`.
새 회귀는 공유 refresh/trace/resource-safety/최종 comparisonComplete를 사용한다.
12개 Debug + 2개 공유 + 2개 Release 테스트 정의를 추가했다(공유 2개는 두 variant에서 실행).
실제 Android getter의 null/예외나 emulator 첫 실패 자체를 JVM에서 재현했다고 주장하지 않는다.
첫 대상 실행에서 Release 무상태 reflection 검사가 Compose의 정적 `$stable` 필드까지 검사하여 실패했다.
인스턴스 상태만 검사하도록 고쳤고, 이후 대상/전체 모두 통과했다. 정책이나 원래 테스트 정답을 완화한 것은 아니다.

Release에는 공통 고정 enum 선언이 남지만, 수집 관찰 상태·시험 service·새 READ 로그 formatter는 없다.
빌드 산출물을 통한 격리 확인이며 기기 런타임 성공 증거가 아니다.
Android 계측/삼성/LG/에뮬레이터 실행은 하지 않았다.
선택창 유지·수동 닫기·production Cleanup 장애 해결을 주장하지 않는다. 이번 작업은 원인 구분 준비다.


### 10.6 전달 전 최종 검토

- 기준 HEAD 및 origin은 `997437cedff68d1bfcc806f756e3c5c10034f9df`로 일치했다.
- 10.5의 10개 파일 외에는 기존 미추적 구조 검토 문서만 있다. 그 문서는 수정/스테이징하지 않는다.
- 검증 당시 저장한 파일 해시와 대조하여 소스·테스트 9개가 동일함을 확인했다.
  이번에는 이 보고서의 실행 옵션·Bound 재확인·로그 보존·POINT 의미만 보완했다.
- 따라서 Debug 1,589 / Release 1,498, 대상 55 / 18, 호스트 55 / 합성 앱 26,
  빌드·Lint·APK/DEX 결과는 **기존 결과 재사용**이다. 이번에 테스트/빌드를 다시 실행했다고 보고하지 않는다.
- 기존 회귀와 실제 연결을 검토했다: 첫 사유 고정, body 예외/취소를 정리 전에 관찰,
  child release 실패가 뒤의 정상 배치로 사라지지 않음, 정리 실패/취소 조합 보존,
  매 read 새 관찰자, refresh의 원래 getter/clock 순서, Release 무상태 stub.
- 관찰자는 고정 enum/boolean을 갱신하며 외부 진단 sink/callback을 호출하지 않는다.
  공유 refresh의 진단 callback에서 발생한 Exception은 기존 false 결과를 바꾸지 않으며,
  실제 checkpoint/refresh/cleanup의 예외·취소는 원래 수명주기 규칙대로 전파한다.
  복구 불가능한 VM Error까지 정상 정리를 보증한다는 의미는 아니다.
- `F0_RELEASE=COMPLETED`는 확보한 로컬 자원의 해제 호출 및 전체 배치 완료만 의미한다.
  첫 수집 실패와 후속 정리 실패는 DETAIL/POINT와 RELEASE로 분리한다.
- 새 policy/준비 단계/retry/sleep, 시간 예산 또는 TTL 변경은 없다. 기존 baseline 실패 카운터와
  C 미생성·후속 구간 미실행 판정은 유지한다. 현재 검토에서 미해결 P2 이상 또는 진단 정보 노출은 발견하지 않았다.
- 최종 CRLF-aware 작업/staged 공백 검사와 파일 범위 확인 후 현재 브랜치에만 전달한다.
  main/다른 worktree/보호 문서는 보존하며 기기 명령은 실행하지 않는다.
- 첫 F0 실패 원인, P01 표시 유지·수동 닫기 및 Cleanup ADDED/HEALTH 문제는 여전히 미해결이다.

## 11. 준비 완료 후 대조 모드 — root 조회 실패·시간 초과 반영

기준 `34fb274031d3a12239ef425e5f5ae71f51c1cc78`. 10절은 이전 진단 개발·전달 기록으로 보존한다.
이번에는 사용자가 명시적으로 승인한 **시험 준비 분리**만 구현한다. 운영 표시/클릭 정책이나
기존 즉시 시작 시험을 변경하는 결정이 아니다. 아래 새 모드는 Android에서 아직 실행하지 않았다.

### 11.1 이번 실제 관측과 한계

`p01-reader-withoutCForward-34fb274.txt` 원본 파일은 이 환경에서 찾지 못했다.
**사용자가 제공한 발췌**를 근거로 하며 원본 전체를 직접 검토했다고 주장하지 않는다.
API 37, emulator-5554, WITHOUT_C/FORWARD/--no-restart:

| 표본 | 제공된 실제 진단 | START–END (ms) |
|---|---|---|
| READ 1 / COLLECTOR_FRESH | ROOT_UNAVAILABLE / ROOT / F0_RELEASE=UNKNOWN | 1158–1191 |
| READ 2 / COLLECTOR_FRESH | TIME_LIMIT / ROOT_REFRESH / F0_RELEASE=COMPLETED | 1211–1318 |
| READ 3 / F9 | TIME_LIMIT / TREE | 1348–1496 |
| READ 8 / COLLECTOR_FRESH | TIME_LIMIT / SNAPSHOT_PASS / F0_RELEASE=COMPLETED | 1754–1865 |

SERVICE=CONNECTED, RECEPTION=HOST_EVENT_CONFIRMED이나 POPUP_READY=UNCONFIRMED에서 INITIAL이 시작됐다.
최종 COMPLETE_READS=8 / UNAVAILABLE_READS=4, BASELINE_UNAVAILABLE, COMPLETED_PHASES=2,
CLEANUP=CONFIRMED, EXTERNAL_CLICK=NOT_WIRED. C와 AFTER는 미실행이다.
10절의 **11완료/1실패**와 합산·대체하지 않는다. 각각 한 번의 관측으로 새 진단의 성능 회귀를 확정하지 않는다.

직접 확인된 범위:
- READ1은 최초 `rootInActiveWindow`가 null인 분기다. 왜 null인지는 미확정이다.
  이 분기는 roots/windows에 객체를 넣기 전이며 UNKNOWN은 로컬 release 호출 증거가 없다는 뜻이다.
- READ2/3/8은 **프로젝트의 읽기 예산** 검사에 걸렸다. POINT/STAGE는 처리 구간이며 어떤 IPC가
  오래 걸렸는지 특정하지 않는다. READ8도 실패했으므로 준비 분리만으로 timeout이 해결된다고 할 수 없다.
- Android가 100ms 안에 응답해야 한다는 플랫폼 보장을 위반한 것이 아니다.
- 이 결과는 삼성 ROOT 변화 원인이나 C 영향 확인, 선택창 유지·닫기·Cleanup 성공 증거가 아니다.

### 11.2 공식 근거와 시험 정책 구분

- [AccessibilityService.getRootInActiveWindow](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getRootInActiveWindow())는 활성 창의 root를 반환하는 접근성 API다.
  [공식 서비스 예제](https://developer.android.com/guide/topics/ui/accessibility/service)는 창이 unavailable일 때 root가 null일 수 있음을 설명한다.
  null 자체가 해당 화면이 안전하거나 팝업이 없다는 증거는 아니다.
- [UI test stability](https://developer.android.com/training/testing/instrumented-tests/stability)는 비동기 작업에 대한 동기화/관측 조건을 다룬다.
  조건 확인과 고정 sleep은 다르며, 이 자료가 보호 실패를 무시하거나 운영 수집을 성공까지 재시도할 권한을 부여하지 않는다.
- host Activity 이벤트, `am start -W`, Dialog.show/setLayout의 비동기 경계는 2절/10.3의
  API 및 AOSP android-15.0.0_r1 근거와 같다. host 이벤트는 준비 **재확인 계기**이지 POPUP_READY가 아니다.
- 아래 4회/1,500ms 및 한정된 재관측 조건은 **이번 시험 설계의 고정 상한**이며 Android API 계약이 아니다.

### 11.3 두 시작 모드와 실패 보존

아래는 최초 준비 모드 계약이다. 이후 실측에 따른 정확한 TARGET_MISSING 추가 대기 조건은 12절을 따른다.

기존 4개 method에 `-e p01PreparedStart true`를 명시한 경우만 새 모드다.
생략/다른 값이면 `START_MODE=IMMEDIATE`로 기존 INITIAL부터 읽는다. 기존 실패 카운터와
첫 baseline 실패 뒤 11회 성공해도 INCONCLUSIVE인 회귀를 유지한다.

| 모드 | 측정 질문 | 준비 및 INITIAL |
|---|---|---|
| IMMEDIATE | 시작 직후 전체 구간을 실제로 읽을 수 있는가 | 기존 awaitReception 후 INITIAL |
| PREPARED | 안전한 bounded P01이 한 번 관측된 뒤 reader/C 조건 간 차이는 무엇인가 | 별도 준비 후 **새** INITIAL 읽기 |

네 조건은 동일한 `P01ComparisonPreparation.run/assess`를 사용한다. 준비 읽기는 모두
실제 `AndroidSnapshotCollector.capture(freshPresentation=true, requireCaptureIsolation=true)`이며
원래 F0 보호 두 순회/refresh/endpoint/로컬 해제를 거친다. 시험용 준비를 production service에 연결하지 않는다.

- 준비 최대 **4회**, 시작부터 최종 준비 판정까지 **1,500ms**. 기존 세션 전체 8초, 각 수집 100ms,
  실행 TTL 5초는 변경하지 않는다. 시간 역행·만료 후 결과로 승인하지 않는다.
- 첫 읽기는 즉시 한 번. 그 후 재관측은 새 host STATE/CONTENT 이벤트 수신으로만 시작한다.
  conflated 채널 하나를 사용해 이벤트 폭주를 누적 작업으로 바꾸지 않는다. 같은 trigger를 재사용하지 않는다.
  세션 시작 이전/시작과 동시각, 미래 시각, 미확인 window, 다른 앱 및 마지막 유효 발생 시각 이하의
  중복·역순 이벤트는 재수집을 깨우지 않는다. 원래 reception/대상 창 관측 기록은 유지한다.
  동시각의 실제 별개 이벤트도 보수적으로 재수집 계기로 쓰지 않을 수 있으며, 이때 NOT_READY 가능성을 감수한다.
  고정 sleep, 자동 재실행, 시간만 지나면 READY, 무한 polling은 없다.
- 재관측 대기는 정확한 초기 ROOT_UNAVAILABLE/ROOT/UNREADABLE_TREE/UNKNOWN 분기,
  TIME_LIMIT + 로컬 해제 COMPLETED, 또는 전체 F0 성공이나 아직 bounded popup이 아닌 배경 Activity에 한정한다.
  null/timeout을 안전한 화면으로 승인하지 않으며 UNKNOWN 일반 예외를 임시 실패로 분류하지 않는다.
- 보호 차단, 명시적 소유권/결합 실패, 해제 FAILED/CANCELLED, refresh 실패, 불완전 metadata,
  예상 밖 오류는 종료한다. 사용자 조작·동의 철회·서비스 해제·단독 서비스/설정 변화도 기존 경계에서 중단한다.
- 메인 스레드의 동기 Android IPC나 진단 전달을 deadline으로 강제 중단할 수는 없다.
  실제 읽기 구간을 남기고 늦게 반환된 관측은 준비 승인에 사용하지 않는다. final summary 전달 자체의
  벽시계 소요까지 1,500ms 이내라고 보증하지 않는다. 진단/검사 CPU 비용도 0이라고 주장하지 않는다.

준비 READY의 관측 조건:
- 합성 앱의 실제 snapshot과 frame·APPLICATION target window 결합, active/focused, 기본 display 격리,
  창 중복 없음, frame/window 내부의 유효한 노드 geometry, 시험 region 관측, 정확한 native action 메타데이터 결합.
- 입력창/다른 overlay/경쟁 focus/겹치는 상위·동일 layer 창 거부. passive system bar 예외를 새로 넣지 않는다.
- 실제 F1의 bounded APPLICATION `NEW_WINDOW`와 실제 B finder의 단일 CLOSE 후보 및 노드 결합.
- 이는 실제 B 실행 승인도, layout 완료나 native generation/ABA 인증도 아니다. 이후 evidence/B preflight와
  각 reader의 보호 검사는 원래대로 다시 수행한다.

준비 중 기록은 `SEGMENT=PREPARATION;READ=n;PHASE=PREPARATION`으로 분리하고 기존
F0_DETAIL/F0_POINT/F0_RELEASE를 그대로 출력한다. 매 시도 START/END, OBSERVATION, ISSUE 및
최종 ATTEMPTS/UNAVAILABLE_READS/WAITING_OBSERVATIONS/ELAPSED_MS/FIRST_F0_*를 보존한다.
READY 관측이 있어도 최종 deadline/경계 검사에 실패하면 NOT_READY다. 취소/예외도 최종 사유를 남긴다.
모든 표본은 로그에 남고 첫 F0 사유가 뒤의 성공/실패로 덮이지 않는다.

```text
START_MODE=PREPARED;A_P01_READER;SEGMENT=PREPARATION;READ=1;PHASE=PREPARATION;...;F0_DETAIL=ROOT_UNAVAILABLE;F0_POINT=ROOT;F0_RELEASE=UNKNOWN
START_MODE=PREPARED;A_P01_READER;PREP_ATTEMPT=1;START_MS=0;END_MS=33;OBSERVATION=WAITING;ISSUE=READ_UNAVAILABLE
START_MODE=PREPARED;A_P01_READER;PREPARATION=READY;END=READY;ISSUE=NONE;ATTEMPTS=2;UNAVAILABLE_READS=1;...;FIRST_F0_DETAIL=ROOT_UNAVAILABLE;...
START_MODE=PREPARED;MEASUREMENT=STARTED;PREPARATION=READY;INITIAL_SOURCE=NEW_READ
```

위는 형식 예시이며 실행 결과가 아니다. READ의 시각은 arm 기준, PREP_ATTEMPT/ELAPSED는 준비 시작 기준이다.
준비의 UNAVAILABLE_READS와 최종 SUMMARY의 측정 UNAVAILABLE_READS는 **별개 카운터**다.
준비 실패를 삭제/초기화한 것이 아니며 각각의 segment에서 해석해야 한다.
준비를 못 끝내면 NOT_READY/INCONCLUSIVE, INITIAL·C·AFTER 미실행이다.
성공 시에도 준비 token/시각/후보/closePath/비교쌍을 INITIAL에 넘기지 않고 실제 새 읽기를 한다.
준비 DTO는 세션 안에서 대상 관측 결합 비교에만 잠시 보유하고 finish에서 해제한다. Android 객체를 보관하지 않는다.
package/window/frame 및 비geometry 노드·native action이 달라지거나 준비 대상의 ADDED/REMOVED를 받으면 중단한다.
geometry는 이 실험의 관측 변수이므로 차이로 기록하며 승인/보정하지 않는다. 관측상 동일한 ABA는 배제할 수 없다.
측정 중 READ8 같은 실패는 그대로 unavailable이며 BASELINE_UNAVAILABLE/INCONCLUSIVE다.

이 모드는 초기 transient 구간을 측정 밖으로 미루므로 IMMEDIATE를 대체하거나 같은 기준의 개선율로
비교할 수 없다. 초기 실패 표본은 PREPARATION에 남는다. 이번 네 조건도 서로 동일 설정/시작 모드로 비교해야 한다.

### 11.4 시간 제한과 시험 비용 검토

| 구간 | 코드상 시작·검사·종료 및 비용 |
|---|---|
| Collector fresh | capture 진입 시 수집 시각. root/child refresh 전후에 그 시각 기준 100ms 검사. 보호·snapshot 두 순회, 종료 active root/window.root/frame/window 재검증 후 최종 예산 검사. 별도 TraversalBudget은 geometry 취득 뒤 시작하며 node/depth/100ms를 제한 |
| F9 | scan의 초기 clock부터 geometry/window/occlusion/root/tree/endpoint를 읽고 tick에서 100ms 확인. TREE 표시는 마지막 구간이며 개별 Android 호출 지연의 증거가 아님 |
| 로컬 cleanup | 두 reader 모두 원래 try/finally/batch 정리를 끝까지 시도. 마지막 예산 검사 뒤 정리 시간이 READ 종료에 더해질 수 있음 |
| 시험 region | 기존 window 핸들에 한정한 getRegionInScreen/iterator·목록 비교. getWindows override 안에서 실행되므로 reader 예산에도 비용이 들어감. 이번 작업에서 추가/제거하지 않음 |
| READ START–END | 첫 checkBoundary 뒤부터 producer 반환·정리, 후속 boundary/C/region/sample 확인 및 finally 구간까지. READ 로그 송신은 END 시계 평가 뒤이며 producer의 100ms 구간과 같지 않음 |
| 비교·로그 | pair 비교/진단 송신은 읽기 외에도 실행되어 다음 표본 시점에 영향을 줄 수 있음. 준비 자체도 별도 관측 간섭을 만들며 동일 네 조건에 적용 |

root/child 원격 조회·refresh, 보호 순회와 snapshot 순회, 종료 검사는 서로 다른 안전 목적이므로
중복이라고 제거하지 않았다. 지금 증거로 불필요함이 입증된 시험 작업은 없어 최적화하지 않았다.
추가 operation별 시계/진단 체계도 넣지 않았다. 각 API의 기여 시간이나 준비 모드의 성능 개선은 미측정이다.
기존 READ 전체 구간만으로 특정 getter의 비용을 역산하지 않는다. TIME_LIMIT이 지속되면 새 모드도 INCONCLUSIVE다.

### 11.5 다음 한 조건 — Windows 명령만 준비

6절의 APK 3개 빌드/설치(`AndroidTest -t`), UI 수집 동의·허용 앱, 운영/C 시험 서비스 OFF 및
P01 시험 서비스 ON, TalkBack OFF, **APK 교체 후 실제 Bound 재확인** 절차를 그대로 따른다.
이 작업에서는 아래 명령이나 기기 시험을 실행하지 않았다. 새 폴더를 사용해 과거 실패 파일을 보존한다.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "emulator-5554"
& $adb -s $serial shell dumpsys accessibility | Select-String -Pattern "Bound services|P01ReaderComparisonService|CloseForYouAccessibilityService|ChoiceOverlayTestService" -Context 0,4
# Enabled 목록만으로 충분하지 않음. 실제 Bound에 시험 서비스가 연결됐는지 UI와 출력에서 확인.
$runner = "kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner"
$class = "kr.closeforyou.accessibility.integration.P01ReaderComparisonInstrumentedTest"
$logDir = Join-Path (Get-Location) ("p01-prepared-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Path $logDir -ErrorAction Stop | Out-Null
$logFile = Join-Path $logDir "withoutCForward-prepared-norestart.txt"
if (Test-Path -LiteralPath $logFile) { throw "기존 로그 보존: 새 출력 폴더 사용" }
& $adb -s $serial shell am instrument --no-restart -w `
    -e class "$class#withoutCForward" -e p01ReaderComparison true -e dedicatedEmulator true `
    -e p01PreparedStart true $runner 2>&1 | Tee-Object -FilePath $logFile
Select-String -Path $logFile -Pattern "START_MODE=|RECEPTION=|SEGMENT=|PREP_ATTEMPT=|PREPARATION=|MEASUREMENT=|READ=|PAIR=|SUMMARY=|FAILURES|SKIP|IGNOR|ASSUMPTION|OK \("
```

완료 기준: PREPARATION=READY → 새 INITIAL → BEFORE_C/AFTER_0/AFTER_150/AFTER_300 모두 완료,
측정 unavailable=0, SUMMARY=OBSERVED, CLEANUP=CONFIRMED, EXTERNAL_CLICK=NOT_WIRED와
**해당 JUnit 1개 실제 실행 성공(실패·skip·ignored·assumption failure 없음)**을 모두 확인한다.
PAIR=CHANGED는 유효하게 관측된 차이일 수 있으며 P01 기능 성공/실패와 같지 않다.
준비 실패 이력은 이 경우에도 별도로 함께 보고한다.

NOT_READY, INCONCLUSIVE, 취소, 보호/해제/소유권 실패가 나오면 **나머지 조건은 실행하지 않는다**.
6절의 정상 UI 서비스 종료/재연결 절차로 복구하며 failed latch/HEALTH를 강제 초기화하지 않는다.
withoutCForward가 유효하게 완료된 뒤에만 동일 옵션의 withoutCReverse/withCForward/withCReverse를 각각 별도 로그로 검토한다.
WITH_C에서는 기존 F9_ACTIVE의 정확한 overlay 차단만 OBSERVED_WITH_GUARD_BLOCK의 별도 예상 안전 차단 대조로 분류한다.
그 외 실패는 그대로 INCONCLUSIVE이며 C가 실제 렌더됐다고 주장하지 않는다.

### 11.6 이번 변경 및 로컬 검증

이번 변경 파일(7개):
1. `app/src/debug/java/kr/closeforyou/accessibility/testing/P01ComparisonPreparation.kt` (신규)
2. `app/src/debug/java/kr/closeforyou/accessibility/testing/P01ReaderComparison.kt`
3. `app/src/debug/java/kr/closeforyou/accessibility/testing/P01ReaderComparisonService.kt`
4. `app/src/androidTest/java/kr/closeforyou/accessibility/integration/P01ReaderComparisonInstrumentedTest.kt`
5. `app/src/testDebug/java/kr/closeforyou/accessibility/testing/P01ComparisonPreparationTest.kt` (신규)
6. `app/src/testRelease/java/kr/closeforyou/accessibility/testing/P01ReaderComparisonReleaseTest.kt`
7. `docs/A_P01_READER_C_COMPARISON_2026-10-31.md`

새 공유 준비 판정·driver를 실제 서비스와 JVM 회귀가 함께 사용한다. 실제 Android getter, remote cache,
연결 timing, 준비 READY 또는 네 조건 성공을 JVM fixture로 입증했다고 주장하지 않는다.
최종 검증 결과는 아래에 실제 실행/재사용을 구분해 기록한다.

로컬 검증 완료 (2026-10-31, JDK 17/offline):

| 검증 | 실제 결과 / 재사용 구분 |
|---|---|
| 새 준비 회귀 | Debug **17개** 신규: 제한/이벤트 대기, 첫 실패 보존, 실제 F1/B finder, 결합/가림/보호/정리/취소, 준비 DTO 미전달, 기존 측정 실패 유지 |
| Release 격리 회귀 | **1개** 신규, 준비 driver/class 부재 확인 |
| 전체 앱 Debug JVM | **1,606** 재실행 통과; 실패·오류·skip 0 |
| 전체 앱 Release JVM | **1,499** 재실행 통과; 실패·오류·skip 0 |
| 호스트 / 합성 앱 JVM | **55 / 26** 기존 통과 결과 재사용; 이번 task UP-TO-DATE |
| 앱 Debug / AndroidTest APK | 변경 반영 재빌드·계측 컴파일 통과; Android 실행 아님 |
| 앱 Release APK | build task 통과, 변경 없는 Release 산출물 UP-TO-DATE |
| 호스트 APK·AndroidTest / 합성 앱 APK | build task 통과, UP-TO-DATE |
| 앱 Debug/Release·호스트 Lint | task 통과, XML issue 0; 변경 영향 없는 분석/보고 task는 UP-TO-DATE |
| APK/DEX | 기존 패키징 script 재실행 통과; 새 준비 class/필드·옵션이 Debug/AndroidTest에 있고 Release DEX 정의·참조·로그에는 없음 |
| Manifest | 실제 APK 확인: 비교 시험 service Debug에만 존재, Release 부재 |
| 공백/보존 | CRLF-aware 작업/staged 검사, 신규 파일 후행 공백 검사 통과. 다른 9개 HEAD/status 및 기존 보호 문서 bytes 유지 |
| 기기/commit/push | 모두 **미실행** |

전체 로그: `/workspace/a-p01-prepared-full.log` — BUILD SUCCESSFUL, 250 tasks (28 executed / 222 up-to-date).
대상 회귀: `/workspace/a-p01-prepared-targeted.log`, 패키징: `/workspace/a-p01-prepared-packaging.log`.
첫 대상 실행의 실패 2개는 fixture의 중복 target 생성 예외와 준비 단계에 없는 closePath를 CHANGED로
기대한 테스트 오류였다. 중복 창 입력은 실제 판정까지 도달하도록 구성하고, 미결합 CLOSE_BOUNDS=UNKNOWN을
유지하면서 ROOT_BOUNDS=CHANGED를 검증하도록 수정했다. 이후 대상 및 전체 검증이 통과했다.
보호 판정이나 측정 실패 정답을 완화하지 않았다.

남은 한계: 새로운 host 이벤트 없이 root가 뒤늦게 읽기 가능해져도 준비를 자동 반복하지 않아 NOT_READY일 수 있다.
지연된 target ADDED/REMOVED도 확정할 수 없으면 중단한다. 이 계약은 native generation/ABA 인증이 아니다.
준비 시점의 성공 이후라도 후속 시간 초과·다른 창·노드 변경·C 미등록·cleanup 실패 때문에 실험이 미완료일 수 있다.
삼성 P01 선택창 유지, 수동 닫기, 운영 RETIRED_ID_EVENT/ADDED/HEALTH는 이번 변경의 해결 대상이 아니며 미해결 상태를 유지한다.


### 11.7 전달 전 최종 검토 및 이벤트 재수집 결함 수정

- 시작 HEAD와 원격은 모두 `34fb274031d3a12239ef425e5f5ae71f51c1cc78`였다. 지정 7개와
  기존 미추적 구조 검토 문서만 있으며 보호 문서는 수정/스테이징하지 않는다.
- 시작 시 저장된 `/workspace/a-p01-prepared-validated.json`과 비교하여 코드·테스트·문서 7개가
  이전 전체 검증 당시와 일치함을 확인했다. 이후 아래 시험 범위 결함을 발견해 최소 수정했다.
- **확인된 결함:** `checks.event()`는 오래된/미확인 이벤트를 reception에서 제외하지만,
  준비 채널은 그 결과를 사용하지 않고 host package/type만으로 깨어났다. 이전 세션에서 발생한
  지연 host 이벤트 또는 중복 전달이 새 준비 읽기를 유발할 수 있었다. 새 수집의 F0/B 검사를
  건너뛰는 클릭 취약점은 아니지만, 이번 준비 시험 계약을 만족하지 않는 경로였다.
- 실제 서비스가 사용하는 공유 event 경계에서 기존 wake 조건을 동작 변경 없이 분리하여
  `preparationTriggerRejectsPreviousSessionUnknownWindowAndForeignEvents`,
  `preparationTriggerCannotReuseDuplicateOrOutOfOrderEvent`를 실행했다.
  수정 전 두 회귀 모두 **expected false / actual true**로 실패했다.
- `ComparisonRunChecks.event()`가 기존 reception 기록은 유지하면서 검증된 최신 이벤트의
  재수집 가능 여부를 반환하고, 실제 service 채널이 그 반환값만 사용하도록 고쳤다.
  현재 세션 범위/유효 window/host/시각 순서를 확인하며, 동일 발생 시각의 재전달도 차단한다.
  원문·ID·시각 필드를 새로 로그에 추가하거나 getter/시계/IPC를 추가하지 않았다.
- 채널은 suspend receive + 기존 timeout이다. 추가 coroutine 회귀는 대기 중 다른 이벤트 처리 가능,
  유효 이벤트 한 번만 수용, 중복·역순 무시, 새 이벤트가 없을 때 1,500ms에 NOT_READY를 확인한다.
  active/preparing 종료 guard, read 전 채널 비우기, prepare finally/finish의 비우기 및
  서비스 중단 시 깨운 뒤 checkpoint 거부도 실제 연결에서 확인했다.
- 준비 max 4회/1,500ms, 기존 수집 100ms, 세션 8초, 실행 TTL 5초는 그대로다.
  동기 Android 호출의 강제 중단이나 네트워크/진단 반환 시간 보장은 추가하지 않았다.
- PREPARATION=READY는 수집 가능한 대상의 관측 조건이며 표시/클릭 허가가 아니다.
  준비 실패 기록과 측정 카운터는 별도 유지한다. INITIAL은 새 읽기이며 준비 객체는
  비geometry 동일성과 정확한 action 결합 검사에만 사용한다. 관측상 동일한 ABA는 인증하지 않는다.
- 지정 7개 밖의 소스, 운영 정책, C/P23, Release, Cleanup ADDED/HEALTH는 변경하지 않았다.
  문서의 --no-restart/명시적 prepared 옵션/설치 -t/Bound 재확인/새 로그/한 조건 중단 절차도 확인했다.
- 아래 최종 결과는 이번 수정 이후의 검증으로 구분한다. 이전 Debug 1,606 통과를 그대로 재사용하지 않는다.

전달 전 최종 검증 결과:

| 항목 | 결과 |
|---|---|
| 수정 전 재현 | 공유 event/wake 경계 회귀 2개 실패 (`false` 기대 / `true` 반환) |
| 수정 후 Debug JVM 전체 | **1,609개 재실행 통과**, 실패·오류·skip 0; 준비 회귀 총 20개 |
| 추가 회귀 | 위 2개 + suspend/channel 대기·중복 방지·무이벤트 만료 1개 통과 |
| Release JVM | **1,499개 기존 결과 재사용**, 이번 task UP-TO-DATE |
| 호스트 / 합성 앱 JVM | **55 / 26개 기존 결과 재사용**, UP-TO-DATE |
| 빌드 | Debug APK 재빌드, AndroidTest 컴파일 재실행·APK task 통과. Release 및 호스트/합성 APK task는 UP-TO-DATE |
| Lint | 영향받는 Debug 분석 재실행, 앱 Debug/Release 및 호스트 task 통과·XML issue 0 |
| APK/DEX/Manifest | 다시 확인: 시험 기능/옵션/로그 Debug 및 AndroidTest에만 존재; Release 정의·참조·로그·service 부재 |
| 공백/파일 범위 | 지정 7개만 전달, CRLF 원본 보존, CRLF-aware 작업/staged 검사 통과 |
| 기기 실행 | Android/삼성/LG/에뮬레이터 모두 미실행 |

수정 전 로그 `/workspace/a-p01-prepared-review-before.log`, 수정 후 전체 로그
`/workspace/a-p01-prepared-review-full.log` (BUILD SUCCESSFUL, 250 tasks: 21 executed / 229 up-to-date),
패키징 `/workspace/a-p01-prepared-review-packaging.log`.
현재 검토 범위에서 미해결 P2 이상/실패 은폐/판정 우회/민감정보 출력 문제는 발견하지 않았다.
준비 모드 실측, reader 비교 완료, 삼성 P01 표시 유지·닫기 및 별도 Cleanup 장애는 여전히 미검증/미해결이다.


## 12. 대상 미확보와 확보 후 소실 — TARGET_MISSING 준비 경계

기준 `c7fb08dddbd1d6cbeeab4d9ba1ee5348459e712b`. 이번 변경은 **시험 준비 종료 조건**의 수정이다.
삼성 운영 표시 정책이나 collector/F9 결과를 변경하지 않는다. 기기 실행·커밋·푸시는 하지 않았다.

### 12.1 제공된 실제 관측

사용자 제공 withoutCForward / --no-restart / p01PreparedStart=true 발췌:

```text
SERVICE=CONNECTED;RECEPTION=HOST_EVENT_CONFIRMED;POPUP_READY=UNCONFIRMED
READ=1;START_MS=467;END_MS=525;STATUS=BLOCKED;F0=UNREADABLE_TREE
F0_DETAIL=TARGET_MISSING;F0_POINT=TARGET_BINDING;F0_RELEASE=COMPLETED
PREPARATION=NOT_READY;END=REJECTED;ISSUE=READ_UNAVAILABLE
ATTEMPTS=1;UNAVAILABLE_READS=1;WAITING_OBSERVATIONS=0;ELAPSED_MS=63
SUMMARY=INCONCLUSIVE;ISSUE=PREPARATION_NOT_READY;COMPLETED_PHASES=0
CLEANUP=CONFIRMED;EXTERNAL_CLICK=NOT_WIRED
```

이번 실행에는 TIME_LIMIT이 없다. 이전 ROOT_UNAVAILABLE/시간 초과 시험과 혼합하지 않는다.
collector에서 `windows.firstOrNull { it.id == root.windowId }`가 null인 실제 분기다.
root가 조회되었지만 읽은 창 목록에서 그 window ID에 대응하는 창을 확보하지 못했다.
이후 ADDED/CONTENT 이벤트의 관계 UNCONFIRMED만으로 실패한 root의 창이라고 연결하거나
캐시 오류·일시적 상태·C 영향으로 단정하지 않는다. C 및 본 INITIAL은 시작하지 않았다.
F0_RELEASE=COMPLETED는 기존 로컬 정리 호출/배치 완료만 뜻하며 native 창 제거 증거는 아니다.
기존 준비 분류가 정확히 구현된 결과이나, 이번에 승인한 시험 목적상 미확보 상태의 제한된 대기를 추가한다.

### 12.2 이번에만 바뀌는 조합과 상태 경계

다음 모든 값이 일치할 때만 추가 WAITING이다:

```text
Debug / p01PreparedStart=true / 준비 진행 중 / 대상 미고정 / 본 측정 미시작 / C 미생성
기존 세션 검사 통과 / 외부 실행기 미연결
status=BLOCKED / ready=null / sample=null / block=UNREADABLE_TREE
f0Detail=TARGET_MISSING / f0Point=TARGET_BINDING / f0Release=COMPLETED
```

- `assess`와 공유 `run`의 새 `awaitingTarget` 입력은 기본 false다. 위 문맥을 제공하지 않으면
  TARGET_MISSING은 이전처럼 REJECTED다. 공통 재시도 목록에 추가한 것이 아니다.
- 실제 service만 준비 진행 중, 대상 미고정, INITIAL 미시작, C 미요청/미생성 상태를 명시적으로 전달한다.
  기존 checkBoundary는 읽기 전후/대기 후에 동의·단독 서비스·연결·입력·취소/설정 실패를 확인한다.
- 공유 `ComparisonRunChecks.beginRead`는 실제 정상 측정 읽기 시작을 기록한다. **첫 INITIAL이 실패해도**
  준비 모드로 돌아갈 수 없다. 실제 준비 함수/읽기 진입점도 재진입과 C 생성 후 준비를 거부한다.
  대상 고정 후 TARGET_MISSING/교체 및 본 측정 TIME_LIMIT은 기존 unavailable/INCONCLUSIVE로 남는다.
- 새 enum·진단 필드·대규모 상태 기계는 없다. 기존 target binding과 측정 시작 boolean guard를 사용한다.
- 불완전한 표본은 폐기한다. TARGET_MISSING의 WAITING은 대상 소유권이나 준비 완료 승인이 아니다.
  실제 complete fresh F0와 기존 window/frame/보호/metadata/단일 B CLOSE 조건을 모두 통과해야 READY다.
- 검증된 새 host 이벤트가 있어야 다음 읽기를 한다. 이전 세션·다른 앱·중복·역순 이벤트 차단을 유지한다.
  최대 4회/1,500ms, 개별 읽기 100ms, 기존 세션/TTL은 그대로다. 고정 sleep이나 자동 시험 재실행은 없다.
  동기 Android 호출은 강제 중단할 수 없으며 늦은 반환을 준비 승인에 사용하지 않는다.
- FIRST_F0_DETAIL/POINT/RELEASE, 시도·unavailable·대기 횟수 및 경과 시간은 뒤의 성공에도 유지한다.
  READY 다음 INITIAL은 독립적인 새 읽기이고 준비 token/시각/후보를 갱신·복사하지 않는다.

### 12.3 전체 준비 판정표

아래 대기/READY는 모두 유효한 준비 세션 내부에서만 의미가 있다. 본 측정 실패에는 적용하지 않는다.

| 입력/문맥 | 처리 | 근거 및 이번 변경 여부 |
|---|---|---|
| 세션 검사 실패, 대상 고정 후/INITIAL 후 준비 재진입, C 생성 후 준비 | 종료 | 새 read 전 거부. 준비 예산이나 대상 기준을 새로 만들지 않음 |
| 로컬 release FAILED / CANCELLED / FAILED_AND_CANCELLED | REJECTED | 최우선 cleanup 실패, 뒤의 성공으로 복구 안 함. 유지 |
| BLOCKED/null/null, UNREADABLE_TREE, ROOT_UNAVAILABLE, ROOT, release UNKNOWN | WAITING | 정확한 최초 root 미획득 분기. UNKNOWN 일반 허용 아님. 유지 |
| **BLOCKED/null/null, UNREADABLE_TREE, TARGET_MISSING, TARGET_BINDING, release COMPLETED + 명시적 미확보 문맥** | **WAITING** | **이번에 추가한 유일한 실패 분류 변경**. 결합 확인/일시적 오류 증명이 아님 |
| BLOCKED/null/null, TIME_LIMIT, UNREADABLE_TREE 또는 TRUNCATED_TREE, release COMPLETED | WAITING | 기존 준비 계약 그대로. 처리 구간/실패 원인을 추가 완화하지 않음 |
| 그 외 BLOCKED, 보호/다른 앱/일반 예외, 위 조합의 point/release/status 불일치 | REJECTED | UNKNOWN/미확인 해제를 성공으로 승격하지 않음 |
| COMPLETE가 아니거나 F0 detail이 NONE 아님, release 미확인 | REJECTED | 불완전/오류 결과는 준비 성공 아님. 유지 |
| COMPLETE여도 객체 결합·package/window/frame·창 종류/focus·geometry·region·action metadata 불확실, 경쟁 창/가림 | REJECTED | 기존 target/소유권/보호 조건 유지 |
| 위 검증을 통과한 complete 배경 Activity이나 bounded F1 popup 아님 | WAITING | 이벤트만으로 P01 준비를 승인하지 않음. 유지 |
| bounded popup이나 B CLOSE 후보 없음/복수/노드 결합 불일치 | REJECTED | 실제 단일 닫기 후보 필요. 유지 |
| 모든 complete F0/F1/B 준비 조건 충족 | READY | 측정 시작 조건일 뿐 표시/클릭/향후 안정성/native 세대 보증 아님 |
| 새 유효 이벤트 없음 또는 4회/1,500ms 상한 소진 | NOT_READY | 시간만 지나서 승인하거나 추가 무제한 수집하지 않음 |
| coroutine 취소/경계 예외 | 종료·전파 | 실제 취소를 WAITING으로 변환하지 않음. 원래 정리 및 최종 기록 유지 |

### 12.4 회귀와 실제 연결

공유 run/assess, 실제 서비스의 `ComparisonRunChecks.beginRead` 및 기존 event 검증을 테스트에서 사용한다.
수정 전 `targetMissingWaitsForVerifiedEventThenUsesOnlyCompleteFreshRead`와
`targetMissingWithoutNewEventEndsNotReadyAtBudget`가 실패했다. 첫 시험은 대기 없이 종료했고,
두 번째는 TIME_LIMIT까지 대기할 기대와 달리 REJECTED를 반환했다.
수정 후 두 회귀 및 추가 경계 회귀를 검증한다:

- 정확한 조합은 READY가 아닌 WAITING이고, 이벤트 전 read 1회/승인 0회.
- 새 검증 이벤트 뒤 actual B finder를 통과한 완전한 새 fixture 객체만 준비 승인.
- 첫 TARGET_MISSING/포인트/해제 결과와 unavailable=1이 후속 READY에도 남음.
- 이벤트 없음, 4회 상한, 읽기 반환 시 이미 1,500ms이면 NOT_READY.
- 문맥 입력 누락, 모든 핵심 tuple 불일치, release 미확인/실패는 REJECTED.
- target 고정 및 첫 INITIAL(결과가 실패여도) 이후 준비 재진입 거부.
- 대기 후 보호/소유권/정리/예외/취소/세션 무효화가 생기면 후속 성공 표본을 읽지 않음.
- 기존 즉시 시작 baseline 실패, 측정 중 timeout, 이전 세션/중복/역순 이벤트와 Release 격리 회귀 유지.

JVM은 합성 DTO를 사용하는 공유 제어 경계 검증이다. 실제 Android에서 TARGET_MISSING이
재현되거나 이후 준비가 성공했다는 증거는 아니다. collector의 100ms/자원 처리 코드는 변경하지 않았다.

이번 변경 파일(5개):
1. `app/src/debug/java/kr/closeforyou/accessibility/testing/P01ComparisonPreparation.kt`
2. `app/src/debug/java/kr/closeforyou/accessibility/testing/P01ReaderComparison.kt`
3. `app/src/debug/java/kr/closeforyou/accessibility/testing/P01ReaderComparisonService.kt`
4. `app/src/testDebug/java/kr/closeforyou/accessibility/testing/P01ComparisonPreparationTest.kt`
5. `docs/A_P01_READER_C_COMPARISON_2026-10-31.md`

### 12.5 다음 시험의 판독

11.5의 withoutCForward/--no-restart/p01PreparedStart=true 한 조건 명령을 그대로 사용한다.
이번 작업에서는 실행하지 않았다. 과거 로그를 새 결과로 덮어쓰지 않는다.
정확한 TARGET_MISSING이 반복되면 READ는 여전히 BLOCKED이며 PREP_ATTEMPT의 OBSERVATION만 WAITING이 된다.
새 이벤트가 없으면 추가 읽기 없이 NOT_READY, 새 이벤트가 있어도 4회/1,500ms 안에 완전한 관측을
얻지 못하면 NOT_READY다. 후속 READ가 정상이어도 FIRST_F0_DETAIL=TARGET_MISSING과 이전 unavailable 횟수는 남는다.

준비 성공과 본 대조 완료는 별개다. READY → 새 INITIAL → 5구간 완료 → 측정 unavailable=0 →
OBSERVED → CLEANUP=CONFIRMED → 실제 JUnit 성공(skip 없음)을 모두 확인해야 한다.
본 측정 실패나 NOT_READY/INCONCLUSIVE면 후속 조건은 중단한다.
시험 준비 종료 조건을 조정했으며, 삼성 선택창 유지·수동 닫기 또는 별도 Cleanup ADDED/HEALTH 문제가
해결됐다고 주장하지 않는다. TARGET_MISSING의 Android 내부 원인과 새 대기의 실효성은 아직 미검증이다.


### 12.6 최종 로컬 검증과 보존 상태

| 검증 | 이번 결과 / 재사용 구분 |
|---|---|
| 수정 전 재현 | 새 TARGET_MISSING 공유 run 회귀 2개 실패: 즉시 REJECTED되어 이벤트 대기/예산 종료 기대 불충족 |
| 수정 후 대상 회귀 | 준비 26개 + reader 비교 28개 = **54개 재실행 통과** |
| 앱 Debug JVM 전체 | **1,615개 재실행 통과**, 실패·오류·skip 0 |
| 앱 Release JVM | **1,499개 기존 통과 결과 재사용**, Gradle UP-TO-DATE, 실패·오류·skip 0 |
| 호스트 / 합성 앱 JVM | **55개 / 26개 기존 결과 재사용**, UP-TO-DATE, 실패·오류·skip 0 |
| 빌드 | Debug/Release, 앱·호스트 AndroidTest, 호스트·합성 앱 Debug task 모두 성공. Debug 앱 재빌드 및 앱 AndroidTest Kotlin 재컴파일; 변경 없는 Release/호스트/합성 산출물은 UP-TO-DATE |
| Lint | 앱 Debug 분석 재실행. 앱 Release/호스트 기존 분석 재사용. 세 XML 보고서 issue 0 |
| APK/DEX/Manifest | 이번 산출물 재검사 통과. Debug 준비/reader/service 존재, Release 정의·참조·준비 로그·시험 서비스 부재. 기존 호스트/앱/계측 DEX 격리 검사 통과 |
| 공백 / 줄바꿈 | CRLF-aware working/staged 검사 통과(index 비어 있음). 변경 5개 파일의 기존 LF 유지, 다른 파일 줄바꿈 변환 없음 |
| 보존 | 기준 HEAD 유지, 다른 9개 worktree HEAD/status 및 기존 미커밋 파일 해시 일치. 보호 구조 검토 문서 해시 일치 |
| 기기 / Git | 기기 실행·stage·커밋·푸시·병합 없음 |

수정 전 로그 `/workspace/a-p01-target-missing-before.log`, 대상 회귀
`/workspace/a-p01-target-missing-targeted.log`, 전체 검증
`/workspace/a-p01-target-missing-full.log` (BUILD SUCCESSFUL, 250 tasks: 15 executed / 235 up-to-date).
이 결과는 시험 제어 경계의 JVM/빌드 검증이며 Android 대조 시험 완료가 아니다.
이번 범위의 추가 판정 완화·실패 은폐·실행 권한 확대는 발견하지 않았다.
첫 실패의 내부 Android 원인, 삼성 선택창 유지·수동 닫기 및 Cleanup RETIRED_ID_EVENT/ADDED/HEALTH는
여전히 별도 미확정/미해결이다.
