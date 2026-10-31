# P01 STABLE_FRESH 이후 제거: reader × C 생성 대조 시험

기준: `fdbb65d2922b43354550a72faaa049a77142cbd4`, `fix/a-samsung-p01-lifetime-20261028`.
이 문서는 실측 성공 보고가 아니다. Linux에서 연결 기기 없음, emulator 실행 파일/AVD 없음을 확인했다.
삼성/LG 재실행은 하지 않았다. 운영 허용 정책, B안 3회/100ms, TTL, cleanup을 변경하지 않는다.

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
CONDITION=WITHOUT_C;ORDER=FORWARD;A_P01_READER;READ=1;PHASE=INITIAL;SOURCE=COLLECTOR_FRESH;STATUS=COMPLETE;START_MS=...;END_MS=...;EVENTS_DURING=0;F0=NONE;F9_STAGE=NONE;F9_DETAIL=NONE;F9_RELEASE=NONE;F0_RELEASE=COMPLETED
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
& $adb -s $serial install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adb -s $serial install -r popup-test-app\build\outputs\apk\debug\popup-test-app-debug.apk
& $adb -s $serial shell am start -n kr.closeforyou/.app.MainActivity
```

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

```powershell
$runner = "kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner"
$class = "kr.closeforyou.accessibility.integration.P01ReaderComparisonInstrumentedTest"
$methods = @("withoutCForward", "withoutCReverse", "withCForward", "withCReverse")
$logDir = Join-Path (Get-Location) ("p01-reader-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Path $logDir | Out-Null
foreach ($method in $methods) {
    $logFile = Join-Path $logDir "$method.txt"
    & $adb -s $serial shell am instrument -w `
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
새 instrumentation 호출은 Android가 target process를 재시작할 수 있다. 동일 process의 failed latch가
process 재시작을 넘어서 유지된다고 주장하지 않는다. 실패 뒤 자동 재실행하지 않는 위 PowerShell과 아래 사용자 종료 절차가 필요하다. INCONCLUSIVE가 나면 로그를 남기고 원인을 확인한다.
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

## 9. 로컬 검증과 해결 상태

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
이번 최종 전달 단계에서는 사용자 승인에 따라 위 12개 파일만 현재 A 브랜치에 커밋·일반 푸시한다.
main 병합/force push/PR 생성/기기 실행은 하지 않는다. 삼성 P01 해결 또는 네 조건 성공을 주장하지 않는다.
