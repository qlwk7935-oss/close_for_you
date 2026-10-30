# F1 동일 Scroll 자식의 화면 밖 → 안 → 밖 독립 관측

최초 구현 기준: `feature/f1-popup-detection` / `366c0824cf5c9bceaf1b3f987c7749d6313a1457`.
최소 진단 보강 기준: `52c97dce5d5c8882edeb5d2d0752482620e342f5`.
안정화 후 기준 확정 변경의 기준: `4597b2fd23a83732dd09951df4b7ab6d38f18ed1`.
이번 계측은 테스트 전용입니다. 아래 사용자 첫 실행 및 진단 보강 버전의 API37 재실행은 모두 실패했습니다. 현재 안정화 후 기준 확정 버전은 Android에서 아직 실행하지 않았습니다. 기존 사용자 로그의 원본14개 UNOBSERVED=14, Scroll REVERSED_Y 및 별개 Window/frame 문제를 해결했다는 보고가 아닙니다. 최신 LAYOUT_CHANGE checkpoint의 사용자 제공 두 번 성공은 최소 fixture의 별도 관측으로만 유지합니다.

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

native BEFORE/AFTER 값을 한 표본 안에서 비교하고, 반복 raw 표본 최소3회/1초 조건을 기존 ObservedGeometryStability/MinimalGeometryPolling으로 검사합니다. INITIAL_PROBE와 phase 첫 표본은 환경 기준을 저장하지 않습니다. 첫 OUTSIDE_BEFORE 안정화에서만 최초 표본 anchor 없이 실제 전체 raw sample의 반복 일치를 관측하고, 기존3초 마감 안에서 안정화·취소·해제 검사를 통과한 뒤 기준을 한 번 확정합니다. 그 이후 window·rotation·두 Configuration·논리 frame·Activity max/current·decor·viewport·remote 경로·remote root/window는 세 상태 사이에 동일해야 합니다. native View identity·부모 경로는 초기 bind부터 매번 유지해야 하며, 기준 확정 전에도 이를 재선택하거나 생략하지 않습니다. 실제 scroll 위치·대상 layout/accessibility bounds·가시성은 각 상태 안에서 반복 일치해야 합니다. 이후 phase 및 서비스 관측 전후의 고정 anchor는 유지합니다. layout pending 초기 재관측은 기존 제한 안에서만 허용하며 해제 실패가 있으면 중단합니다.

이는 단속적 표본 안정성이지 연속 불변성의 증명이 아닙니다. 최초 확정 전 관측된 A→B→A는 반복 횟수·시작 시간을 재설정하며, 확정 후 기준/anchor 위반은 실패합니다. 표본 사이에서 나타났다 사라진 미관측 ABA는 감지 보장 밖입니다.

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

아래는 최초 구현 당시의 검증 및 미추적 파일 상태에 대한 과거 기록입니다. 이후 최초 구현5개 파일은 `52c97dce5d5c8882edeb5d2d0752482620e342f5`로 커밋됐으며, 이번 진단 보강의 검증 결과와 구분합니다.

신규 ScrollClipContractTest46개가 실패·오류·skip0으로 통과했습니다. 실제 reader/driver가 사용하는 동일한 소유권 선행 callback·identity/placement/shape/출력 계약·phase 전이·서비스 tuple 검사·관측 loop·final deadline·reset/scroll 순서를 가짜 시계/IPC/host callback으로 검사합니다. 동일 자식 대응 실패, 원래 Rect 형태 보존, PARTIAL/불명 소유권 거부, malformed/ASSESSED/MIXED 구분, 예산/보호 차단 거부, 행 소실/변경·중복·phase 재사용, 빈 응답/마감 후 정상 응답 거부를 포함합니다. 기존 stability/anchor의 관측 ABA, GeometryReadSafety/독립 해제의 검사 실패+해제 실패 및 취소, 실제 finally와 공유하는 cleanup callback의 개별 시도와 실패 보존도 검사합니다. Android 노드 identity/Binder/event/recycle 동작을 JVM에서 재현한 것은 아닙니다.

JDK17에서 다음 명령을 실행해 BUILD SUCCESSFUL(54초)을 확인했습니다. 양쪽 JVM task는 --rerun으로 실제 실행했고 전체789개(앱396/호스트393)가 실패·오류·skip0으로 통과했습니다.

```text
./gradlew --no-daemon :app:testDebugUnitTest --rerun :capture-test-host:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest :app:lintDebug :capture-test-host:lintDebug
```

양쪽 Debug/AndroidTest APK 빌드, 양쪽 Lint(이슈0), 기존 tools/verify_capture_test_apks.py를 통과했습니다. 실제 DEX class_def를 검사해 ScrollClip reader/driver/공유 계약·상태·표본 클래스가 host AndroidTest APK에만 정의되고 본 앱·본 앱 AndroidTest·host APK에는 없음을 확인했습니다. JVM 회귀 클래스는 모든 APK에서 제외됩니다. Gradle 경고 출력은 없었습니다.

기존 추적 파일과 staged 변경은 없고 위 신규5개 파일만 미추적 상태로 보존합니다. gradle.properties 내용 hash는 작업 시작과 동일합니다. git diff --check 및 별도의 신규 파일 전체 공백/개행 검사를 통과했습니다. 실제 API37 계측, 커밋·푸시·병합은 실행하지 않았습니다. 원본14개·Scroll/Window 오류 및 F0/F1 미입증 범위는 그대로입니다.

## 사용자 제공 API37 첫 실행 — 실패

기준 커밋 `52c97dce5d5c8882edeb5d2d0752482620e342f5`에서 SERVICE_CONNECTION_READY 이후 OUTSIDE_BEFORE / ENVIRONMENT_CHANGED / INCONCLUSIVE로 종료됐습니다. 사용자 제공 실행 시간은1.543초이며 JUnit 실패1건입니다. Codex가 Android에서 직접 실행한 결과가 아닙니다.

기존 ENVIRONMENT_CHANGED 하나로는 display 없음/무효, 비양수 경계, 저장한 환경·remote 경로·창/root 경계 불일치, 좌표 덧셈 overflow를 구분할 수 없습니다. OUTSIDE_BEFORE도 초기 probe부터 사용되므로 실패한 조회 단계를 특정하지 못합니다. 실행 시간만으로 애니메이션이나 타이밍 원인을 확정하지 않습니다.

## 최소 환경 실패 진단 — 판정 변경 없음

이 절과 다음 로컬 검증 절은 `4597b2f` 진단 보강 당시의 동작·미실행 상태에 대한 과거 기록입니다. 이후 사용자 재실행 및 현재 기준 확정 시점 변경은 문서 마지막 절과 구분합니다.

기존 F1_SCROLL_CLIP_PROBLEM의 DETAIL=ENVIRONMENT_CHANGED 및 RESULT=INCONCLUSIVE를 유지하고, 해당 실패에만 READ_STAGE / CHECK / FIELD를 추가합니다. 아래는 출력 형식 예시이며 실제 관측 결과가 아닙니다.

```text
F1_SCROLL_CLIP_PROBLEM;PHASE=OUTSIDE_BEFORE;DETAIL=ENVIRONMENT_CHANGED;RESULT=INCONCLUSIVE;READ_STAGE=PHASE_FIRST_SAMPLE;CHECK=SAVED_BASELINE_MISMATCH;FIELD=CURRENT
```

READ_STAGE는 실제 sample 호출 위치에서 지정합니다.

| 코드 | 호출 위치 |
|---|---|
| INITIAL_PROBE | bind 뒤 초기 firstCoherent의 sample |
| PHASE_FIRST_SAMPLE | scroll 요청 뒤 phase의 첫 firstCoherent sample |
| STABILITY_CHECK | 서비스 관측 전/후 기존 stable loop의 sample |
| ANCHOR_CHECK | 기존 서비스 RESULT 전/후 및 최종 anchor sample |

CHECK/FIELD는 기존 검사 순서와 단락 평가를 유지합니다.

| CHECK | 실제 FIELD / 기존 분기 |
|---|---|
| DISPLAY_MISSING | DISPLAY: 기본 display 조회 결과 null |
| DISPLAY_INVALID | DISPLAY: 기존 isValid가 false |
| NONPOSITIVE_BOUNDS | LOGICAL → MAXIMUM → CURRENT → DECOR → VIEWPORT, 또는 REMOTE_WINDOW → REMOTE_ROOT: 기존 positive 검사 |
| SAVED_BASELINE_MISMATCH | WINDOW_ID → ROTATION → CONFIGURATION → APPLICATION_CONFIGURATION → LOGICAL → MAXIMUM → CURRENT → DECOR → VIEWPORT → REMOTE_PATH → REMOTE_WINDOW → REMOTE_ROOT: 저장값과 현재값의 기존 동등 비교 |
| COORDINATE_OVERFLOW | VIEWPORT / DECOR / TARGET_LAYOUT: 기존 Long 덧셈 결과가 Int 범위 밖 |

ScrollClipReadDiagnostics는 reader 수명 동안 첫 실패의 enum3개만 보존하며 reset API가 없습니다. Android main-thread bridge는 기존처럼 issue enum만 전달하고, instrumentation thread에서 같은 reader의 최초 진단을 다시 붙입니다. 원본 예외·raw 경계·Configuration 값·View 경로·window ID·timestamp·토큰·지문·Android 객체를 새 진단 payload에 저장하지 않습니다. 이미 존재하는 raw 표본의 비교·참조 수명은 그대로이며, 진단 출력은 기존 실패 보고1회 안에서만 추가합니다. polling 로그·getter·IPC·sleep·시계 조회는 추가하지 않습니다.

최초 성공 표본이 다회 안정화 전에 기준으로 고정되는 동작과 stable(anchor=first)는 그대로입니다. 초기 probe의 A가 저장된 뒤 phase 첫 표본이 B이면 기존대로 안정화 전에 거부합니다. 초기 변화를 실제로 관측했는지는 새 API37 실행이 필요합니다. ENVIRONMENT_CHANGED는 기존 firstCoherent의 재관측 허용 대상이 아니며 이번에도 즉시 실패합니다. 단일 sample 안의 BEFORE/AFTER 불일치는 기존 UNSTABLE이고, anchor 변경·deadline·소유권·민감 필드 검사·서비스 완료 조건·해제/취소/cleanup 차단도 변경하지 않습니다.

진단 분기는 순수 JVM 코드와 실제 Android reader가 공유하지만, JVM은 실제 Configuration 변화·display/window 조회·노드 identity·framework recycle 실패를 재현하지 않습니다. 관측된 첫 분기가 원인 전체나 Android 결함을 증명하지 않으며 F0 완전성·현재성·TTL·토큰 결합·원본14개 F1 정확도도 입증하지 않습니다. 초기 기준 고정의 보수적 false-INCONCLUSIVE 가능성과 기존 정리 후 보고 취소 순서 회귀(P3)는 후속 과제로 남깁니다. 이번에는 진단만 보강하며 커밋·푸시·병합하지 않습니다.

## 최소 진단 보강의 로컬 검증 (2026-10-20)

신규 JVM 회귀21개를 추가해 ScrollClipContractTest67개가 통과했습니다. 실제 reader가 호출하는 display null/validity, positive, saved equality, overflow, 단계 scope, main-thread issue 재전달 계약을 검사합니다. 각 실제 FIELD, 원래 단락 평가 순서, 초기 probe와 phase 첫 표본의 구분, 기존 firstCoherent에서 warmup의 첫 표본 고정 후 변화가 안정성 검사 전에 거부되는 callback 순서를 포함합니다. 최초 enum 보존, 환경 검사와 여러 release 실패의 동반 발생, 취소·cleanup 실패, 개인정보 비노출 및 기존 progress의 성공/실패 계약도 검사했습니다. 가짜 callback과 raw 합성 Rect를 사용한 시험이며 Android reader 전체 또는 실제 framework 실행의 재현은 아닙니다.

처음 별도 Gradle cache를 지정한 wrapper 실행은 배포본 다운로드의 호스트 조회 실패로 중단됐고 테스트는 실행되지 않았습니다. 기존 cache를 사용하는 아래 오프라인 JDK17 실행으로 대상 검증 및 전체 검증을 완료했습니다. 저장소 설정 파일은 수정하지 않았습니다.

```text
JAVA_HOME=/workspace/jdk17 ./gradlew --offline --no-daemon :capture-test-host:testDebugUnitTest --tests kr.closeforyou.servicetest.ScrollClipContractTest --rerun
JAVA_HOME=/workspace/jdk17 ./gradlew --offline --no-daemon :app:testDebugUnitTest --rerun :capture-test-host:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest :app:lintDebug :capture-test-host:lintDebug
```

전체 JVM810개(앱396/호스트414), 실패·오류·skip0을 실제 JUnit XML에서 확인했습니다. 전체 명령은 BUILD SUCCESSFUL(1분54초), 양쪽 Debug/AndroidTest APK 및 양쪽 Lint(이슈0)를 통과했으며 Gradle 경고 출력은 없었습니다. tools/verify_capture_test_apks.py와 추가 class_def 검사로 ScrollClip reader/driver/계약/진단 타입이 host AndroidTest APK에만 정의되고 앱·앱 AndroidTest·host APK에는 없음을 확인했습니다. JVM 회귀 클래스도 APK에 없습니다.

기존 Android getter·RESULT/RESET IPC·clock·delay·sendStatus 호출 수가 동일하고 서비스 판정/시간/progress 계약과 driver finally가 원문 그대로임을 소스 대조했습니다. 이는 framework 실행 순서·타이밍의 Android 실측 증명이 아닙니다. enum 전달과 순수 비교 처리에도 실행 비용이 있으므로 타이밍 영향이0이라고 보장하지 않습니다.

변경은 허용된 ScrollClip5개 파일뿐이며 나머지 추적 파일의 SHA-256은 작업 시작과 같습니다. AndroidManifest.xml·gradle.properties·운영 코드·원본14개·기존 다른 계측은 미변경입니다. git diff --check와 변경 파일 전체 공백/개행 검사를 통과했습니다. 진단 보강 버전의 API37 계측·커밋·푸시·병합은 실행하지 않았습니다. 위 Windows 명령으로 재시험해 첫 실제 READ_STAGE/CHECK/FIELD를 확인해야 하며, 이전 실패 원인은 아직 미확정입니다.

## 사용자 진단 재실행과 안정화 후 기준 확정

사용자가 `4597b2fd23a83732dd09951df4b7ab6d38f18ed1`의 API37 재실행에서 OUTSIDE_BEFORE / ENVIRONMENT_CHANGED / PHASE_FIRST_SAMPLE / SAVED_BASELINE_MISMATCH / REMOTE_WINDOW / INCONCLUSIVE를 제공했습니다. 이는 첫 probe에서 저장한 접근성 창 경계와 phase 첫 표본 경계가 달랐다는 증거입니다. Android가 경계를 달리 제공한 이유, 애니메이션 여부, 서비스 frame과의 원자적 관계는 확정하지 않습니다. Codex 직접 Android 실행이 아닙니다.

현재 ScrollClipBaseline은 NEW → STABILIZING → FIXED → DISPOSED의 테스트 소유 수명만 관리합니다. 임의 완료 표식·공개 commit/reset은 없습니다. NEW의 probe·phase 첫 표본과 STABILIZING의 표본은 기준을 저장하지 않습니다. 실제 reader가 매번 기존 소유권 선검사, 동일 native View/원격 source 대응·부모·Scroll 조상, 민감/입력 필드 거부, layout/양수 경계/안팎 위치·가시성, 한 표본의 native BEFORE/AFTER 일치와 독립 해제를 모두 수행해야 합니다. raw Rect 정렬·clamp·가짜 경계는 없습니다.

최초 establishBaseline만 기존 stable loop의 anchor를 생략합니다. 관계 enum 대신 ScrollClipSample 전체(raw 경계·경로·window ID·rotation·두 Configuration·scroll 위치·가시성)의 정확한 동등성을 기존 gate로 비교합니다. 변화하면 기존3초 안에서 횟수와 시작 시간이 다시 계산되며,3회 이상/1초 이상을 채우지 못하면 STABLE_UNOBSERVED / INCONCLUSIVE입니다. 중간 검사 예외나 release 실패를 새로 재시도하지 않습니다. 단일 표본 내부의 불일치도 기존 UNSTABLE 즉시 실패입니다.

stable 반환 후 reference 구성·Job 취소·동일 GeometryReadSafety latch를 확인하고 최초 establish 시작 시각의3초 마감을 기준 대입 직전/직후에도 검사합니다. 마감·실패·취소 시 참조를 폐기하고 원래 고정 예외/취소를 전파합니다. 환경·remote 경로·window/root만 보존하며 Android node/window나 본문·토큰·지문을 보관하지 않습니다. 기준이 FIXED이면 기존 필드별 정확한 비교와 READ_STAGE/CHECK/FIELD 진단을 사용하며 불일치 시 폐기합니다. phase별 재확정, 실패 후 복구, close 후 재사용은 거부합니다.

이후 INSIDE/OUTSIDE_AFTER의 안정화는 기존 phase 첫 표본 anchor를 유지하고, 서비스 관측 전/후와 최종 RESULT의 anchor도 그대로입니다. 실제 scroll에 따라 바뀌어야 하는 대상 경계와 scroll 위치를 전 phase의 초기 대상 값으로 고정하지 않습니다. 환경 기준은 교체하지 않으면서 각 phase 안의 전체 raw 표본 안정성은 별도로 엄격히 요구합니다.

기존 서비스 최소2초/최대3초, session45초·256행, RESET 횟수, ownership/ordinal floor, 실제 ATTEMPT와 최종 RESULT·phase 완료·cleanup 조건은 미변경입니다. 초기 launch 기록도 삭제하지 않습니다. 안정 기준은 host 표본의 비교 기준일 뿐 서비스 F1 baseline·선택 노드의 collector binding·F0 완료 증거가 아닙니다. RESULT↔RESET/END 및 host/UiAutomation/서비스의 비원자성, Binder 강제 중단 불가, 미관측 ABA, 기존 Scroll/Window 문제와 원본14개 UNOBSERVED는 유지합니다. 실제 이벤트/ATTEMPT 미도착이나 후속 기준 변화로 여전히 실패할 수 있습니다. 기존 P3 정리 후 보고 취소 순서 회귀도 별도 후속 과제입니다.

## 안정화 후 기준 확정의 로컬 검증 (2026-10-20)

신규 JVM22개를 추가하고, 이전의 초기 표본 고정을 기대하던 회귀1개를 현재 probe 미확정 계약으로 교체했습니다. 첫 대상 실행은 신규 테스트1개의 반환 타입 컴파일 오류로 중단됐습니다. 테스트에 Unit 반환을 명시한 뒤 대상88개가 통과했고, 이후 기준 대입 직후의 정확한 마감 경계 회귀1개를 더한 전체 실행에서 ScrollClip89개 및 전체832개(앱396/호스트436), 실패·오류·skip0을 JUnit XML로 확인했습니다.

실제 reader와 공유하는 ScrollClipBaseline/MinimalGeometryPolling/ObservedGeometryStability 경로를 가짜 시계·표본·검사/해제 callback으로 검증합니다. 초기 probe와 phase 첫 표본은 NEW 유지, remote window 및 대상 raw bounds 변화 후 카운트 재시작, 최소3회와1초 각각의 필수성, A→B→A 초기 재시작,3초 내 안정화 실패, FIXED 후 configuration/path/window/root 변경 거부, phase 사이 대상 경계 변경과 phase 내부 anchor 거부의 구분을 포함합니다. 재확정·close 후 복구 금지, 기준 구성 및 대입 직전/직후3,000ms 마감, 취소와 여러 해제 실패의 동반 발생, release latch 유지, 실제 서비스·최종 RESULT·cleanup 없는 완료 금지도 검사합니다. Android framework 자체나 실제 node/window/configuration 변화를 JVM에서 재현한 것은 아닙니다.

```text
JAVA_HOME=/workspace/jdk17 ./gradlew --offline --no-daemon :app:testDebugUnitTest --rerun :capture-test-host:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest :app:lintDebug :capture-test-host:lintDebug
```

위 전체 실행은 BUILD SUCCESSFUL(1분26초)이었습니다. 양쪽 Debug/AndroidTest APK와 Lint(이슈0), tools/verify_capture_test_apks.py 및 새 ScrollClipBaseline/상태/Reference를 포함한 실제 DEX class_def 격리를 통과했습니다. ScrollClip 타입은 host AndroidTest APK에만 정의되고 앱·앱 AndroidTest·host APK에는 없으며 JVM 테스트는 모든 APK에서 제외됩니다. Gradle 경고 출력은 없었습니다.

전체 추적 파일의 작업 전후 SHA-256을 비교해 허용된5개 파일 외에는 변경이 없음을 확인했습니다. 기존 Android getter/recycle 호출 수와 서비스 판정·시간/progress 계약, driver의 anchor 이후 최종 RESULT·finally 코드는 원문 동일합니다. 최초 안정화에 필요한 표본 반복은 기존3초 안에서만 수행하며 실제 실행 횟수/타이밍이 과거와 같다고 주장하지 않습니다. git diff --check와 변경 파일 전체 공백/개행 검사를 통과했습니다. 기준 HEAD는 `4597b2fd23a83732dd09951df4b7ab6d38f18ed1`을 유지하며 staged·미추적 파일은 없습니다. 커밋·푸시·병합·PR 변경은 하지 않았습니다.

이 수정 버전의 API37 계측은 미실행입니다. 위 Windows 명령으로 같은 시험을 다시 실행해 초기 완전한 raw 표본이 실제로 안정화되는지, 확정 후 기준과 anchor를 유지하는지, 각 scroll 상태의 실제 서비스 ATTEMPT가 도착하는지 확인해야 합니다. 안정화 전 REMOTE_WINDOW 고정의 코드 경로는 수정했지만, Android가 서로 다른 창 경계를 제공한 원인이나 Scroll REVERSED_Y·운영 Window/frame·원본14개 문제를 해결했다는 증거는 아닙니다. F0 완전성·현재성·TTL·토큰/지문 결합·F1 정확도·OCR·행동 안전성은 미입증입니다.
