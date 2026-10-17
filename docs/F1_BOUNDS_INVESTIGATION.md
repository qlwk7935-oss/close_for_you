# PR #7: 노드 Rect 역전과 F1 창 경계 거부

기존 `feature/f1-popup-detection` / PR #7의 후속입니다. 일반 앱 OCR이나 실제 F1 정확도 개선 완료 보고가 아닙니다. F5 유지/F6 제외/F10·F12·F13 필수 및 B·C·D 공통 계약은 유지합니다.

## 실제 관측과 확정 범위

사용자 Windows API 37 실측: SERVICE_CONNECTION_READY 이후 14개 시나리오가 BASELINE_BLOCKED/UNOBSERVED입니다. 대부분의 baseline에는 서로 다른 ATTEMPT의 다음 두 결과가 있습니다.

- `ASSESSED / UNCERTAIN / INVALID_GEOMETRY`
- `BLOCKED / SNAPSHOT_PASS / COLLECTION_FAILED / FRAME=VERIFIED / NODE_BOUNDS_DTO / ILLEGAL_ARGUMENT`

일부 초기 WINDOWS/TARGET_MISSING도 별도 시도입니다. 시간 초과나 차단을 감지 성공/오탐/미탐으로 재분류하지 않습니다.

**확정:** NODE_BOUNDS_READ 다음의 기존 NODE_BOUNDS_DTO는 Rect의 네 성분으로 `ScreenBounds`를 만들었습니다. 그 생성자의 `right >= left && bottom >= top` 요구와 보고된 예외를 연결하면 X/Y 중 적어도 한 축이 역전된 입력이 해당 DTO에서 거부됐다고 판단할 수 있습니다. 어느 축인지, 그 노드가 가시적이었는지, 스크롤 또는 전환과 관련됐는지는 기존 출력만으로 확정하지 못합니다.

**별개:** INVALID_GEOMETRY는 수집이 성공한 Ready 뒤 F1 평가입니다. 같은 시도의 노드 DTO 예외가 아닙니다. FRAME=VERIFIED도 전체 논리 화면 검증이지 모든 노드/창의 포함 관계를 보장하지 않습니다.

## Android 공개 소스에서 확인한 경계 특성

공개 AOSP mirror의 고정 revision을 읽었습니다. 이것이 사용자의 API 37 시스템 이미지와 같은 바이너리/설정이라는 증거는 없습니다.

- [View의 접근성 경계 생성](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/view/View.java#L11392)은 `getBoundsOnScreen(bounds, true)`로 부모 clipping을 적용합니다.
- [부모 좌표 mapping](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/view/View.java#L9500)은 부모 scroll을 빼고 top/left는 max, bottom/right는 min으로 제한합니다. 자식이 부모 viewport 밖이면 역전될 수 있습니다. 이는 스크롤 밖 자식에서 가능한 **재현 경로**이며, 사용자 실패의 원인 확정은 아닙니다. [android15-release revision에서도 같은 연산](https://github.com/aosp-mirror/platform_frameworks_base/blob/cc8bb19595c661f5cf42f330457000e643c67d1b/core/java/android/view/View.java#L9467)을 확인했으므로 API 37에서만 도입된 결함이라고 주장하지 않습니다.
- [AccessibilityNodeInfo의 공개 getter/setter 구현](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/view/accessibility/AccessibilityNodeInfo.java#L2387)은 Rect 성분을 복사합니다. 앱/provider 또는 변환 경로가 제공하는 Rect에 우리 ScreenBounds의 순서 제약을 보장하는 검사는 없습니다. 공개 getter 문서에는 확대에 따른 좌표 조정도 명시되어 있습니다. 음수 위치 자체는 역전이 아닙니다.
- [AccessibilityWindowInfo.getBoundsInScreen](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/view/accessibility/AccessibilityWindowInfo.java#L398)은 region의 외접 경계입니다. [window region 변환](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/wm/AccessibilityWindowsPopulator.java#L689)도 있으며, 일반 Activity의 region과 이 앱의 검증한 논리 display frame이 항상 일치한다는 공개 API 보장은 확인하지 못했습니다. 이것만으로 사용자의 창이 왜 밖에 있었는지는 설명하지 못합니다. Insets/확대/전환/compat scaling을 임의의 원인으로 확정하거나 보정하지 않습니다.
- [getTransitionTimeMillis 공개 문서](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/view/accessibility/AccessibilityWindowInfo.java#L556)는 마지막 **창 bounds 변경 시각**(uptime)입니다. [server 구현](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/accessibility/java/com/android/server/accessibility/AccessibilityWindowManager.java#L971)은 창 생성/region 변경에 이를 갱신합니다. 애니메이션 진행 여부나 노드 안정성을 뜻하지 않습니다.

## 노드 경계: 예외에서 명시적 안전 거부로

이전에는 역전 Rect가 ScreenBounds 생성자에서 예외를 일으켜 일반 COLLECTION_EXCEPTION으로 끝났습니다. 이제 `NodeBoundsPolicy`가 기존 변환 직전에 순서를 검사합니다.

- 먼저 원래 전체 F0-A 보호 순회 및 snapshot pass의 해당 노드 `inspect`를 수행합니다. 비가시성·bounds를 이유로 패스워드/입력/민감 문자열 검사를 생략하지 않습니다.
- 역전이면 `UNREADABLE_TREE / TREE_PROTECTION / CAUSE=MALFORMED_NODE_BOUNDS / OP=NODE_BOUNDS_VALIDATE / EXCEPTION=NONE`으로 명시적으로 거부합니다. NONE은 의도한 수집 guard라는 뜻이며 정상 수집이 아닙니다. TREE_PROTECTION은 기존 collector guard의 상세 코드로, 실제 민감 정보가 검출됐다는 뜻은 아닙니다.
- 같은 거부에 NODE_RECT=`REVERSED_X / REVERSED_Y / REVERSED_BOTH`, NODE_VISIBILITY=`VISIBLE / NOT_VISIBLE / READ_FAILED`, NODE_ANCESTRY=`SCROLL_ANCESTOR / NO_SCROLL_ANCESTOR`, NODE_ROLE=`ROOT / DESCENDANT`를 연결합니다. 가시성 진단 getter도 실패하면 READ_FAILED로 남기고 계속 거부합니다.
- 스크롤 조상은 이미 순회하며 읽은 `isScrollable`의 boolean 문맥만 자식으로 전달합니다. 부모 node를 추가 취득하거나 경로/깊이/속성값을 출력하지 않습니다. 가시성은 그 노드의 순간적 flag일 뿐 안전 보증이 아닙니다.
- 좌우/상하 정렬·clamp·크롭·리사이즈·노드/하위 트리 생략·비가시성 예외를 추가하지 않았습니다. 역전이 하나라도 있으면 전체 부분 결과를 버립니다. 순서가 유효한 음수 좌표/빈 경계는 기존 ScreenBounds가 허용한 그대로 전달하며 팝업 인정 조건을 바꾸지 않습니다.

따라서 이 변경은 예외의 원인인 **지원하지 않는 역전 경계**를 명시적으로 처리하지만, 모든 baseline을 통과시키는 수정은 아닙니다. 실제 비가시성 노드 생략 등은 보호·완전성·자식 입력/보안 노드 보존을 입증하는 별도 설계/실측 없이는 적용하지 않습니다.

## F1: 창과 frame 관계를 독립 진단

`DetectionGeometryCheck`는 기존 guard와 **같은 거부 조건·우선순위·Int 너비/높이 의미**를 유지합니다. 결과는 같은 ASSESSED 시도에만 붙입니다.

| GEOMETRY | 기존 거부 조건 |
|---|---|
| NONPOSITIVE_FRAME | screenBounds.width 또는 height <= 0 |
| WINDOW_OUTSIDE_FRAME | windowBounds의 어느 변이 screenBounds 밖 |
| NONPOSITIVE_WINDOW | windowBounds.width 또는 height <= 0 |
| VALID | 위 조건에 해당하지 않음. 팝업 인정/캡처 허가가 아님 |

FRAME_EXTENT/WINDOW_EXTENT는 POSITIVE 또는 NONPOSITIVE_WIDTH/HEIGHT/BOTH, WINDOW_RELATION은 WITHIN_FRAME/WINDOW_OUTSIDE_FRAME, OUTSIDE_EDGES는 LEFT/TOP/RIGHT/BOTTOM 집합 또는 NONE입니다. 실제 치수/좌표/창 ID는 없습니다. 여러 조건이 동시에 맞더라도 각각의 extent/relation과 첫 거부 분기를 구분합니다. INVALID_GEOMETRY는 여전히 UNCERTAIN이며 baseline을 버립니다.

API 34+에서는 같은 수집의 대상 window metadata로 WINDOW_BOUNDS_AGE를 관측합니다. `WITHIN_ONE_SECOND`는 마지막 bounds 변경 후 1초 이내, OLDER는 그 이후, 0/미래 시각은 UNKNOWN, 읽기 실패는 READ_FAILED입니다. API 30~33은 UNSUPPORTED입니다. 실제 시각/경과 시간은 반환/보관하지 않습니다. 이 1초는 **진단 bucket일 뿐** 대기·재시도·F0/좌표 허용 조건이 아닙니다. 노드 거부에는 원래 이벤트의 고정 TRIGGER도 남기지만, 창/노드 전환의 원인으로 해석하지 않습니다.

TARGET_MISSING은 별도 창 목록 불일치 거부 그대로이며 다른 시도와 결합하지 않습니다. 세션 순번·RESET 이전 결과 폐기·소유 창 결박·한 시도의 한 결과·상한과 실패 판정을 유지합니다. 기존 baseline 성공 조건도 낮추지 않습니다.

## 검증 종류와 한계

- JVM: 역전 축별 안전 거부(visible/not-visible/read-failed 모두), 원래 좌표 보존, AOSP clamp 연산의 합성 재현, 기존 geometry predicate와 100개 frame/window 조합의 동등성, 초과 변/비양수/우선순위/baseline 폐기, bounds age가 판정을 바꾸지 않음, 서로 다른 시도 진단의 혼입 방지, host allowlist와 유출 방지 회귀를 추가했습니다.
- `SyntheticBaselineBoundsTest`: **변경하지 않은 기본 합성 Activity**의 실제 native View가 생성하는 AccessibilityNodeInfo를 읽습니다. 일반 가시 control과 ScrollView의 존재를 요구하며, 경계 형태·노드 가시성·스크롤 조상·자체 View의 LAYOUT_STABLE/LAYOUT_PENDING/DETACHED 조합만 출력합니다. 원문/좌표/픽셀은 읽어 출력하지 않고 node를 finally에서 해제합니다. 알려지지 않은 런타임 예외 메시지도 반사하지 않습니다.
- 이 계측의 SOURCE=OWN_VIEW/SERVICE_COLLECTION=NOT_TESTED는 **직접 생성한 소유 View 노드**라는 뜻입니다. 원격 서비스의 caching/변환/창 경로와 같다고 주장하지 않습니다. 이 계측의 OK는 API 관측 완료이며 F1 baseline/정확도 성공이 아닙니다. 역전이 나오지 않으면 이를 숨기지 않고 서비스 관측과 별도로 조사합니다. fixture나 서비스에 가짜 bounds/OCR 결과를 주입하지 않습니다.
- 개발 환경의 adb 연결 기기는 0대입니다. 새 API 35/37 계측, 역전 노드의 실제 가시성/스크롤 관계, 일반 Activity 창이 왜 geometry guard에 걸리는지, baseline 복구·F1 정확도·전체 캡처/OCR는 **미검증**입니다. 새 고정 코드 실측이 필요합니다.

F0-A/F0-B·입력/보안/민감 정보·다른 앱/시스템/가림·화면 좌표·캡처 동의/앱별 허용·ROI/큰 버퍼 거부·메모리/시간·버퍼 해제를 변경하지 않습니다. 캡처/외부 행동을 추가하지 않습니다. 공통 PopupSnapshot/PopupEvent/BCD 계약은 그대로이며 추가 모델은 A 내부 진단입니다. release에 host/probe/계측 구현을 포함하지 않습니다.

## Windows API 37 재시험

개인정보 없는 전용 에뮬레이터에서 프로젝트 터미널로 실행합니다. API 35에서도 대상 serial만 바꿔 별도로 관측합니다.

```powershell
git switch feature/f1-popup-detection
git pull --ff-only origin feature/f1-popup-detection
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adbPath devices
$serial = "emulator-5554" # 실제 API 37 대상 serial
& $adbPath -s $serial shell getprop ro.build.version.sdk
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk
```

본 앱 UI에서 서비스 실제 활성화·수집 동의·`kr.closeforyou.capturetest` 하나만 허용·분석 시작을 확인합니다. **캡처 동의 OFF / 캡처 앱 목록 비어 있음**을 유지합니다. 실제 메시지/로그인/은행/결제 화면은 열지 않습니다. adb로 동의/서비스 상태를 강제 설정하지 않습니다.

1. 소유 native View 경계 관측(서비스 성공을 측정하지 않는 독립 probe):

```powershell
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.SyntheticBaselineBoundsTest -e f1Detection true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

`F1_OWN_BOUNDS`의 고정 조합을 관측합니다. 예외 메시지·전체 Logcat·이미지는 수집하지 않습니다. NOT_VISIBLE/SCROLL_ANCESTOR/LAYOUT_STABLE에서 역전이 관측되면 **이 소유 fixture**에서 안정된 스크롤 clipping과의 관계를 뒷받침하지만 원격 서비스 원인 확정은 아닙니다.

2. 기존 실제 서비스 F1 시험:

```powershell
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceDetectionScenariosOptInTest -e f1Detection true -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

동일 CASE/PHASE/ATTEMPT의 NODE_RECT/VISIBILITY/ANCESTRY/ROLE와 ASSESSED의 GEOMETRY/FRAME_EXTENT/WINDOW_EXTENT/WINDOW_RELATION/OUTSIDE_EDGES를 **서로 다른 결과로** 비교합니다. WINDOW_BOUNDS_AGE만으로 전환 원인을 확정하지 않습니다. malformed 경계는 계속 BASELINE_BLOCKED/UNOBSERVED와 최종 FAILURES로 끝날 수 있으며, 이를 예상하고 허용한 성공으로 처리하지 않습니다. 통과하려면 원래 각 baseline·실제 자극·정답 조건을 모두 충족해야 합니다.
