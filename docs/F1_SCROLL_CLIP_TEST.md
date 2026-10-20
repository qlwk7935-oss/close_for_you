# F1 동일 Scroll 자식의 화면 밖 → 안 → 밖 독립 관측

기준: `feature/f1-popup-detection` / `366c0824cf5c9bceaf1b3f987c7749d6313a1457`.
이번 계측은 테스트 전용이며 Android API37에서 아직 실행하지 않았습니다. 기존 사용자 로그의 원본14개 UNOBSERVED=14, Scroll REVERSED_Y 및 별개 Window/frame 문제를 해결했다는 보고가 아닙니다. 최신 LAYOUT_CHANGE checkpoint의 사용자 제공 두 번 성공은 최소 fixture의 별도 관측으로만 유지합니다.

## 이전 시험과 다른 점

- SyntheticBaselineBoundsTest: 원본 기본 화면의 native bounds를 관측합니다.
- ServiceBaselineIsolationOptInTest: 서로 다른 ORIGINAL_SCROLL/MINIMAL fixture를 비교합니다.
- ServiceScrollClipOptInTest: 원본 DetectionScenarioActivity의35개 행·문구·구조를 변경하지 않고 같은 자식 View 하나를 세 상태에서 비교합니다. 원래14개 시나리오의 baseline·자극·정답을 교체하거나 실행하지 않습니다.

추가 파일은 ScrollClipReader, ServiceScrollClipOptInTest, ScrollClipContract, ScrollClipContractTest와 이 문서뿐입니다. Activity·Manifest·권한·provider·collector·NodeBoundsPolicy·detector·F0/F1·기존 Dialog/Layout 계측은 수정하지 않습니다. 새 계약은 host AndroidTest와 JVM에만 포함됩니다.

## 실제 경로와 동일 자식 근거

명시 opt-in과 기존 SyntheticServiceSession 연결 검사를 사용합니다. BEGIN은 기존 signature/host 소유권·수집 동의·host 단독 허용·분석 활성·캡처 동의 OFF/목록 비어 있음 조건을 그대로 요구합니다. 설정이나 권한을 자동 변경하지 않습니다.

host main에서 실제 ScrollView 하나, 그 안 LinearLayout의35개 자식, 끝에서 두 번째 TextView를 확인합니다. 해당 Java View 참조, 직접 부모 관계, decor까지의 실제 View 부모/자식 경로를 유지합니다. 이후 재선택하거나 다른 자식으로 대체하지 않습니다. 기존 Activity의 private 필드 접근·reflection·ID/텍스트 주입은 없습니다.

각 표본은 native에서 생성한 대상/부모/Scroll AccessibilityNodeInfo lease를 짧게 보유하고, UiAutomation의 실제 getChild preorder를256노드/깊이60 이내에서 순회합니다. 같은 source/window identity를 나타내는 공개 AccessibilityNodeInfo.equals 비교, 유일한 일치, 실제 부모 노드 identity, 실제 Scroll 조상 identity와 isScrollable을 모두 요구합니다. 클래스명·문구·View 추가 순서만으로 대응을 주장하지 않습니다. 실제 remote 경로도 세 상태에서 동일해야 합니다. 숨김 자식이 remote 트리에서 제외되거나 parent 대응이 불명확하면 INCONCLUSIVE/JUnit 실패입니다.

다른 앱 root는 읽지 않습니다. 선택 window는 기존 host의 native window ID와 일치하는 활성 기본-display TYPE_APPLICATION이어야 합니다. root와 모든 자식은 package/window 소유권을 먼저 확인하고 나서 geometry/child getter를 읽습니다. native·remote identity 비교는 두 표본 사이의 동일 노드 대응이며, collector의 특정 노드와 직접 결박된 증거는 아닙니다.

## 세 상태와 안정성

OUTSIDE_BEFORE는 BEGIN 뒤 Activity 시작에서 생긴 실제 관측을 유지하고 scrollY=0을 요청합니다. 이 첫 구간 앞에는 RESET을 하지 않습니다. 이후 INSIDE/OUTSIDE_AFTER 앞에는 각각 기록 RESET을 한 번만 하고 host 자신의 scrollTo를 호출합니다. INSIDE는 동일 자식이 viewport 안에 들어오는 host scroll 요청, OUTSIDE_AFTER는 다시 scrollY=0입니다. 플랫폼이 실제 스크롤 범위를 제한할 수 있으므로 요청값을 성공 근거로 쓰지 않고 결과를 검증합니다. 원시 Rect의 정렬·clamp·좌표 보정·가짜 경계 생성은 하지 않습니다. service scroll/click/gesture나 sendAccessibilityEvent는 요청하지 않습니다.

View.getLocationOnScreen과 실제 width/height로 얻은 별도의 unclipped host layout bounds와 padding을 제외한 실제 Scroll viewport를 사용해 INSIDE/OUTSIDE/PARTIAL/INVALID를 판정합니다. 이는 접근성 Rect를 복구하는 대체 값이 아닙니다. 안에서는 완전 포함 및 native/remote 양쪽 VISIBLE, 밖에서는 교차 없음 및 양쪽 NOT_VISIBLE을 요구합니다. PARTIAL이나 양쪽 가시성 불일치는 성공하지 않습니다. 접근성 bounds의 원래 ORDERED/EMPTY/REVERSED_X/REVERSED_Y/REVERSED_BOTH는 그대로 관측합니다.

native BEFORE/AFTER 값을 한 표본 안에서 비교하고, 반복 raw 표본 최소3회/1초 조건과 고정 anchor를 기존 ObservedGeometryStability/MinimalGeometryPolling으로 검사합니다. window·rotation·두 Configuration·논리 frame·Activity max/current·decor·viewport·native 부모 경로·remote 경로·remote root/window는 세 상태 사이에 동일해야 합니다. 실제 scroll 위치·대상 layout/accessibility bounds·가시성은 상태 안에서 반복 일치해야 합니다. 서비스 관측 전후에도 같은 anchor를 확인합니다. layout pending 초기 재관측은 기존 제한 안에서만 허용하며 해제 실패가 있으면 중단합니다.

이는 단속적 표본 안정성이지 연속 불변성의 증명이 아닙니다. 관측된 변화/ABA는 anchor에서 실패하지만 표본 사이에서 나타났다 사라진 미관측 ABA는 감지 보장 밖입니다.

## 실제 서비스 관측과 완료 의미

같은 session/소유 window와 각 기록 generation의 실제 ATTEMPT만 조회합니다. 첫 구간의 서비스 기록은 launch 동안의 결과도 포함하며 안정된 host 표본과 동시 수집됐다고 주장하지 않습니다. 서비스 관측은 기존 최소2초/최대3초, 준비·안정 구간도 기존3초 한도를 사용하며 session45초/256개 상한은 그대로입니다. 시간 초과 후 마지막 정상 응답을 재사용하지 않습니다. 단계별 ordinal floor, 중복 ordinal 거부 및 이전 승인 행의 소실/변경 거부를 적용합니다. 후속 anchor 안정성 확인 뒤 마지막 RESULT 전체를 다시 검증합니다. 최종 IPC·진단 출력·완료 표식·phase 전이는 기존 PreparedFinalDeadline의 같은3초 마감을 검사합니다.

운영 서비스는 TYPE_VIEW_SCROLLED를 기록하지만 그 이벤트만으로 snapshot을 시작하지 않습니다. 실제 scrollTo 이후 WINDOW_CONTENT_CHANGED 등 수집을 시작하는 이벤트가 도착한다는 보장은 없습니다. 추가 metadata 변경·가짜 이벤트·ATTEMPT 주입으로 보완하지 않습니다. 다음 상태의 실제 수집이 없으면 기존3초 안에 UNOBSERVED/timeout으로 실패하며, scroll 이벤트 집계만으로 관측 완료를 만들지 않습니다.

정상 진단 자료는 MALFORMED_ONLY / ASSESSED_ONLY / MIXED로 구분합니다. MALFORMED_ONLY는 SNAPSHOT_PASS / UNREADABLE_TREE / MALFORMED_NODE_BOUNDS의 원래 차단 사실이며 Ready/F1 성공으로 바꾸지 않습니다. geometry가 일관된 WINDOW_OUTSIDE_FRAME도 별개 ASSESSED 진단으로 보존합니다. 보호·시간/방문 한도·TARGET_MISSING 등 다른 차단, malformed 진단 불일치, 응답 손상·세션 거부·overflow·미관측은 INCONCLUSIVE/JUnit 실패입니다. 준비 기록을 다음 상태로 복사하지 않고 provider 기록을 주입하지 않습니다.

출력은 고정 단계·출처·형태·가시성·조상·판정 코드뿐입니다. 서비스 행은 기존 allowlist formatter로 검증하고 ATTEMPT ordinal 필드를 제거한 뒤 원래 block/원인/geometry 코드를 유지합니다. 원문·좌표·노드 경로·실제 window ID·timestamp·token·fingerprint·원본 예외 메시지는 출력/파일/DB에 저장하지 않습니다. raw 표본은 bounded RAM에서만 비교하고 toString은 redacted입니다.

세 상태의 실제 관측·최종 RESULT·정리가 모두 끝나야 OBSERVATIONS_COMPLETE / OK(1 test)입니다. NATIVE_CONTRAST와 REMOTE_CONTRAST는 따로 보고합니다. REVERSED_Y_OUTSIDE_ONLY는 해당 출처에서 밖/안/밖이 REVERSED_Y/ORDERED/REVERSED_Y였다는 관계만 뜻합니다. OTHER_PATTERN도 완전한 진단 자료일 수 있지만 clipping 원인 확정은 아닙니다.

ATOMIC_BINDING=NONE / SERVICE_FRAME_BINDING=UNAVAILABLE / SELECTED_NODE_BINDING=UNAVAILABLE을 유지합니다. host/native, UiAutomation, 실제 서비스 ATTEMPT는 서로 다른 시점/연결입니다. 서비스가 malformed로 차단한 노드는 선택한 자식 외 다른 자식일 수 있습니다. scrollTo는 다른 자식 위치·접근성 이벤트·detector 상태에도 영향을 줍니다. 모든 차이가 선택한 자식의 clipping 때문이라고 해석하지 않습니다. RESULT↔RESET/END도 원자적이지 않으며 in-flight 수집을 drain하지 않습니다.

## 해제·취소·정리

동일 reader-lifetime GeometryReadSafety와 PriorityReleaseScope를 사용합니다. child/root/window/native lease를 각 경계의 finally에서 독립적으로 해제합니다. 한 release 실패가 남은 해제를 막거나 원래 검사 실패·취소를 대체하지 않으며, release 실패 latch는 재관측/RESET/close로 지우지 않습니다. 정상 body 뒤 release만 실패해도 성공하지 않습니다. framework 호출을 강제로 중단하거나 VM 오류/프로세스 종료의 완전한 정리를 보장하지 않습니다.

실제 finally는 기존 PreparedDialogCleanup을 NonCancellable에서 호출해 session END, Activity 종료 및 isDestroyed 확인, UiAutomation flags 복구/재조회를 각각 시도합니다. 이 시험은 Dialog를 만들지 않으며 Activity.onDestroy의 기존 정리는 그대로입니다. 하나라도 실패하면 성공하지 않고 보고 전용 취소도 정리 후 재전파합니다. 이후 실제 Job ensureActive를 유지합니다. 기존 정리 후 보고 취소 순서 회귀(P3)는 다른 계측의 후속 과제로 유지하며 이번 코드로 해결됐다고 주장하지 않습니다.

## Windows API37

개인정보 없는 전용 에뮬레이터에서 기존 서비스/수집 동의/host 단독 허용/분석 활성, 캡처 동의 OFF/캡처 목록 비어 있음을 수동 확인합니다. 테스트는 설정을 바꾸지 않습니다.

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "emulator-5554" # 실제 API37 대상 확인
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk
& $adbPath -s $serial shell am instrument -w -e class "kr.closeforyou.servicetest.ServiceScrollClipOptInTest#sameScrollChildOutsideInsideOutside" -e f1ScrollClip true -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

숨김 remote 자식/부모 identity의 공개 여부, 가시성과 clipping 형태, window/configuration 안정성, 실제 이벤트/ATTEMPT 도착 및 해제 성공은 API37 실측이 필요합니다. 전체 F0 완전성·현재성·TTL·token/지문 결합·F1 정확도·원본14개·일반 앱·OCR/행동 안전성은 미입증입니다. PR #7 미병합, PR #10 Draft를 유지하고 이번에는 커밋·푸시·병합하지 않습니다.

## 로컬 검증 기록 (2026-10-20)

신규 ScrollClipContractTest46개가 실패·오류·skip0으로 통과했습니다. 실제 reader/driver가 사용하는 동일한 소유권 선행 callback·identity/placement/shape/출력 계약·phase 전이·서비스 tuple 검사·관측 loop·final deadline·reset/scroll 순서를 가짜 시계/IPC/host callback으로 검사합니다. 동일 자식 대응 실패, 원래 Rect 형태 보존, PARTIAL/불명 소유권 거부, malformed/ASSESSED/MIXED 구분, 예산/보호 차단 거부, 행 소실/변경·중복·phase 재사용, 빈 응답/마감 후 정상 응답 거부를 포함합니다. 기존 stability/anchor의 관측 ABA, GeometryReadSafety/독립 해제의 검사 실패+해제 실패 및 취소, 실제 finally와 공유하는 cleanup callback의 개별 시도와 실패 보존도 검사합니다. Android 노드 identity/Binder/event/recycle 동작을 JVM에서 재현한 것은 아닙니다.

JDK17에서 다음 명령을 실행해 BUILD SUCCESSFUL(54초)을 확인했습니다. 양쪽 JVM task는 --rerun으로 실제 실행했고 전체789개(앱396/호스트393)가 실패·오류·skip0으로 통과했습니다.

```text
./gradlew --no-daemon :app:testDebugUnitTest --rerun :capture-test-host:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest :app:lintDebug :capture-test-host:lintDebug
```

양쪽 Debug/AndroidTest APK 빌드, 양쪽 Lint(이슈0), 기존 tools/verify_capture_test_apks.py를 통과했습니다. 실제 DEX class_def를 검사해 ScrollClip reader/driver/공유 계약·상태·표본 클래스가 host AndroidTest APK에만 정의되고 본 앱·본 앱 AndroidTest·host APK에는 없음을 확인했습니다. JVM 회귀 클래스는 모든 APK에서 제외됩니다. Gradle 경고 출력은 없었습니다.

기존 추적 파일과 staged 변경은 없고 위 신규5개 파일만 미추적 상태로 보존합니다. gradle.properties 내용 hash는 작업 시작과 동일합니다. git diff --check 및 별도의 신규 파일 전체 공백/개행 검사를 통과했습니다. 실제 API37 계측, 커밋·푸시·병합은 실행하지 않았습니다. 원본14개·Scroll/Window 오류 및 F0/F1 미입증 범위는 그대로입니다.
