# PR #7: 두 baseline으로 수집과 창 geometry 분리

현재 작업은 `feature/f1-popup-detection` / 기존 PR #7입니다. F1 정확도 검증 완료나 baseline 복구를 보고하는 문서가 아닙니다.

## 사용자 API 37 실측과 남은 질문

`f79ad2d` 실측은 SERVICE_CONNECTION_READY 이후 14개 시나리오가 BASELINE_BLOCKED/UNOBSERVED=14입니다. 실제 서비스에서 REVERSED_Y / NOT_VISIBLE / SCROLL_ANCESTOR / DESCENDANT의 MALFORMED_NODE_BOUNDS가 반복됐고, 별도 native View에서도 안정된 ScrollView의 보이지 않는 자식에 REVERSED_Y가 관측됐습니다. 이는 이전 [AOSP clipping 조사](F1_BOUNDS_INVESTIGATION.md)와 일치하는 실제 합성 fixture 근거입니다.

별도의 완전 수집에서는 WINDOW_OUTSIDE_FRAME, RIGHT 또는 RIGHT,BOTTOM 초과가 나왔습니다. LOGIN baseline에서 VALID/BASELINE_ONLY도 나왔지만 이후 malformed 차단이 있었습니다. WITHIN_ONE_SECOND는 bounds가 최근 바뀐 단서일 뿐 진행 중인 애니메이션이나 노드 안정성의 증명이 아닙니다. 따라서 두 문제와 창 전환 시도를 분리해야 합니다. TARGET_MISSING도 별도의 목록 불일치로 유지합니다.

## geometry 제외 설계 검토: 이번에는 거부 유지

`NodeSnapshot.bounds`는 유효한 ScreenBounds가 필수이고 `PopupSnapshot.completeness`는 COMPLETE/TRUNCATED뿐입니다. 지금 일부 노드를 삭제하고 COMPLETE로 반환하면 구조 지문·노드 경로·후보/부모 관계를 바꿉니다. isVisibleToUser=false나 스크롤 조상만으로 버튼/팝업 의미 또는 자식의 부재를 증명할 수도 없습니다. **현재 계약으로 그 삭제의 안전성을 입증하지 못했으므로 프로덕션의 malformed 거부를 유지합니다.**

F1 경계 증거를 별도로 투영하려면 최소한 아래 불변식이 필요합니다.

1. geometry와 무관하게 모든 대상/같은 앱 배경 트리 및 모든 자식을 기존 한도 내에서 끝까지 보호 검사합니다. 비밀번호·editable/inputType·민감/불확실 텍스트와 getter/순회 실패는 계속 전체 거부합니다. 숨긴 geometry가 보호 검사 생략을 뜻해서는 안 됩니다.
2. F1에서 경계를 사용할 수 없어도 원래 노드 identity·부모/자식 관계·버튼/action/팝업 의미·지문 기여를 보존해야 합니다. 하위 트리를 잘라내지 않고, 형제 path를 재번호 매기거나 0 크기/정렬된 가짜 경계를 만들지 않습니다.
3. 미확인 geometry를 별도의 상태로 표현하고 그 geometry에 의존한 팝업/부모/underlay/버튼 판단을 불확실로 남깁니다. 유효한 다른 팝업을 놓치지 않았다는 보장 없이 일반 화면으로 확정하지 않습니다.
4. 원래 완전 snapshot을 만들 수 없는 결과는 불완전 수집입니다. 완전한 F0 검사와 완전한 geometry는 서로 다릅니다. F1용 부분 투영을 COMPLETE snapshot이나 기존 baseline 통과로 승격하지 않고, B 후보·캡처/OCR·행동 게이트에 전달하지 않습니다.
5. 보호 결과·전체 구조/본문 지문·geometry coverage는 같은 토큰/화면 세대에 속해야 합니다. 화면 변경/조작·누락/실패·상한 초과 때 전부 폐기합니다.
6. 숨김 입력/암호, malformed 부모와 유효한 자식 버튼, popup 의미 노드, 유효한 다른 Dialog, tree 변경/미확인 자식에 대한 실제 Android 회귀까지 필요합니다. 지금의 JVM 순서 검사나 isVisibleToUser 하나로 대체할 수 없습니다.

이번에는 이 조건을 충족하지 못하는 새 수집 모드를 만들거나 공통 계약/BCD 코드를 바꾸지 않았습니다. 현재 malformed는 UNREADABLE_TREE로 전체 폐기합니다. 기존 F0-A/F0-B, 캡처/OCR 동의·앱별 허용·시스템/다른 앱/가림·ROI/불명확 버퍼 거부·시간/메모리/해제·자동 행동 금지는 그대로입니다.

## 작은 독립 계측

새 `ServiceBaselineIsolationOptInTest` 하나가 아래 두 fixture를 각각 실행합니다.

| FIXTURE | 실행 화면 | 대조 조건 |
|---|---|---|
| ORIGINAL_SCROLL | 기존 DetectionScenarioActivity 그대로 | 실제 ScrollView와 잘려 보이지 않는 자식 존재 |
| MINIMAL | 새 MinimalBaselineActivity, 전체 영역의 고정 TextView 하나 | ScrollView/입력 없음, 자체 native content 자식 모두 보이고 content 경계 안에 있음, 역전 없음 |

원래 DetectionScenarioActivity의 코드와 14개 ServiceDetectionScenariosOptInTest는 수정하지 않았습니다. 최소 화면을 원래 14개의 baseline으로 교체하지 않습니다. 운영 앱 소스/모델/매니페스트도 변경하지 않았습니다. 최소 Activity는 독립 testOnly host에만 있으며 exported=false입니다.

각 fixture에서 LAUNCH_WINDOW와 OBSERVED_STABLE 구간을 나눕니다.

- LAUNCH_WINDOW는 시작 후 첫 일관된 소유 창 샘플입니다. 기기/스케줄링에 따라 이미 안정됐을 수 있으므로 이를 '애니메이션 중'이라고 부르지 않습니다.
- 안정 구간은 실제 소유 창 ID·논리 frame·overlay window context metrics·Activity max/current metrics·decor/content/모든 native 자식 경계·원격 root/window 경계·window bounds 변경 timestamp가 반복해서 같고 View의 attached/laid-out/layout-not-requested가 확인된 경우만 관측합니다. 1초 이상/3회 이상은 관측 조건일 뿐, 시간이나 관계 enum의 동일성만으로 통과하지 않습니다. 실제 원시 값의 동일성을 RAM에서 비교합니다.
- 안정성은 **관측된 값의 안정성**이며 유효성/안전 허가가 아닙니다. 안정된 WINDOW_OUTSIDE_FRAME도 그대로 보고합니다. 잠깐의 변동까지 없었다는 보장도 하지 않습니다. 실제 원격 수집과 metrics 조회는 원자적이지 않습니다.
- RESET은 기존 debug 관측 기록만 비웁니다. 설정·detector·예산·토큰을 바꾸지 않습니다. 이후 소유 content의 고정 contentDescription을 변경해 실제 접근성 이벤트/실제 서비스 수집을 일으킵니다. 결과·bounds/OCR/connected 값을 주입하지 않습니다. 서비스 결과를 관측하는 동안 샘플이 바뀌면 STABLE_PHASE_CHANGED로 실패합니다.
- 실제 연결 preflight와 DON'T_SUPPRESS_ACCESSIBILITY_SERVICES를 유지하고 UiAutomation의 기존 flags를 복원합니다. 기존 signature/고정 host/동의/단독 허용/캡처 OFF 검사도 그대로 사용합니다. 조회 실패·상한·창/레이아웃 불안정·대조 fixture 미확립·결과 미관측·정리 실패는 최종 실패입니다. 한 fixture가 실패해도 다른 fixture를 시도합니다.

테스트의 폴링은 제한된 합성 관측이지 프로덕션 캡처 재시도가 아닙니다. 창 시작 샘플 최대3초, 안정 샘플 최대6초, 서비스 구간 최대3초(최소2초 관측)이며 기존 14개 시험의 timeout을 바꾸지 않습니다. session은 기존45초/256개 상한입니다. native 순회도256개로 제한하고 노드를 즉시 finally에서 해제합니다. window context는 매 샘플 새로 만들지 않고 시험당 하나만 유지합니다.

## 관계 코드와 출처

- F1_ISOLATION_SERVICE/ATTEMPT는 기존 signature-gated provider의 **실제 서비스 수집 시도**입니다. 각 ATTEMPT의 원래 고정 코드를 유지합니다. READY_ONLY는 완전 DTO 수집/ASSESSED가 있다는 뜻이지 후보 감지/실행 성공이 아닙니다. MALFORMED_ONLY, OTHER_BLOCK, MIXED, UNOBSERVED, INVALID를 구분합니다. NORMAL/UNKNOWN 원인을 섞어 READY로 바꾸지 않습니다.
- F1_ISOLATION_GEOMETRY는 **다른 시점의 host native + UiAutomation 샘플**입니다(SOURCE=UIAUTOMATION_SEPARATE_SAMPLE). 소유 실제 window ID로만 조회하며 다른 앱 root/title/text를 읽지 않습니다. 서비스의 같은 ATTEMPT 좌표라고 주장하지 않습니다.
- window/logical, host overlay context/logical, Activity maximum/logical, current/maximum, window/maximum, window/remote root, remote root/own decor, own decor/logical, content/logical을 EQUAL/WITHIN/CONTAINS/OVERLAPS/DISJOINT/EMPTY_OR_REVERSED로 비교합니다. 초과 변은 LEFT/TOP/RIGHT/BOTTOM 또는 NONE입니다. Insets 크기·비율·margin을 추정해 좌표에 적용하지 않습니다.
- Activity와 같은 앱의 비시각 application context에서 primary display의 overlay window context metrics를 읽지만, 이것을 서비스 인스턴스의 metrics로 대체하지 않습니다. 논리 frame은 공개 getRealSize로 읽고 서비스의 기존 화면 검증은 그대로 둡니다.
- 정확한 좌표·시각·창 ID·native 자식 경계 리스트는 짧은 관측 구간의 bounded RAM에서만 비교하며 데이터 객체의 toString도 redacted입니다. 로그/파일/DB/IPC 출력에는 고정 코드만 사용합니다. 원문·스크린샷·픽셀·실제 사용자 정보는 출력/저장하지 않습니다.

해석 예시(아직 새 기기 실측 아님):

- 안정 Scroll에서 malformed가 있고 최소 화면에서 다른 차단 없이 완전 수집됐다면 MALFORMED_ISOLATED_TO_SCROLL. Scroll의 정상 수집/차단 혼재는 MIXED로 계속 표시합니다. 이 대조가 노드 제외의 일반 안전성이나 14개 F1 성공을 증명하지는 않습니다.
- 최소 화면도 완전 수집 후 OUTSIDE_ONLY라면 malformed와 별개의 geometry 거부가 재현된 것입니다. window와 Activity maximum이 일치하는지, remote root와 own decor가 일치하는지로 region/좌표 경로의 다음 조사 범위를 좁힙니다. 이것만으로 scale/animation/시스템 결함을 확정하지 않습니다.
- 처음만 outside이고 안정 샘플이 logical frame과 일치하면 '관측 구간의 관계 변화'입니다. 이후 기다리기만 하면 안전하다는 프로덕션 규칙이나 재시도로 전환하지 않습니다.
- 대조 화면에서 다른 차단·미관측·불안정이 있으면 INCONCLUSIVE/최종 실패입니다. probe의 OK는 비교 관측 완료일 뿐이며 항상 F1_ACCURACY=NOT_TESTED입니다. 원래 14개 시험의 UNOBSERVED/FAILURES는 여전히 별도로 유효합니다.

## Windows API 37 실행 순서

개인정보 없는 전용 에뮬레이터만 사용합니다. 기기에서 직접 실행하지 않은 새 계측/대조 결과·baseline 복구·실제 F1 정확도·캡처/OCR 성공은 **미검증**입니다.

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

본 앱 UI에서 실제 서비스 연결·수집 동의·허용 앱 `kr.closeforyou.capturetest` 하나·분석 시작을 확인합니다. 캡처 동의 OFF/캡처 목록 없음 상태를 유지합니다. 동의/서비스 상태를 adb로 강제 설정하지 않습니다.

1. 두 baseline과 시작/안정 구간의 독립 비교:

```powershell
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceBaselineIsolationOptInTest -e f1Isolation true -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

F1_ISOLATION_GEOMETRY, SERVICE, ATTEMPT, COMPARISON 및 실패 시 FAILURE 코드만 확인합니다. 전체 Logcat/좌표 dump/사용자 화면은 수집하지 않습니다.

2. 원래 14개 실제 F1 회귀(기존 성공 기준 그대로):

```powershell
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceDetectionScenariosOptInTest -e f1Detection true -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

원래 F1_EVIDENCE/F1_ATTEMPT/F1_CASE/F1_SUMMARY를 별도로 확인합니다. 두 문제가 해결되기 전 정확도 검증 완료라고 보고하지 않습니다. API35에서도 serial을 바꿔 별도 관측할 수 있으며 두 버전의 결과를 혼합하지 않습니다.
