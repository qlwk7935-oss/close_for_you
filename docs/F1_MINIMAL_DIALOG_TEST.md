# F1 최소 일반 화면 → 실제 Dialog 독립 계측

개발 기준: `feature/f1-popup-detection` / `f337be05c59e43a0c87da0b534ca1b5acf4f2e76`. 이 변경은 testOnly 호스트의 별도 Activity/계측/순수 판정/JVM 시험과 이 문서입니다. 기존 MINIMAL 대조와 원본 14개 화면·정답, 운영 collector/F0/F1/detector/provider/Runtime/DTO/예산/캡처/OCR/행동 경로를 변경하지 않습니다. 커밋·푸시·병합하지 않습니다. PR #7 미병합, PR #10 Draft를 유지합니다.

## 질문과 성공 조건

ScrollView·입력·민감 내용 없는 일반 화면에서 baseline을 실제 관측한 뒤 실제 Android Dialog 하나를 띄웠을 때, 기존 운영 detector가 후보를 반환하는지 묻습니다. Dialog가 표시됐다는 사실만으로 성공시키지 않습니다.

| 단계 | 자극/소유 창 | 같은 서비스 ATTEMPT에 요구하는 값 |
|---|---|---|
| BASELINE | MinimalDialogActivity의 실제 Activity 창 | ASSESSED / NO_POPUP / BASELINE_ONLY 및 VALID / POSITIVE / WITHIN_FRAME / OUTSIDE_EDGES=NONE |
| DIALOG | 호스트 코드가 한 번 show한 실제 Dialog의 별도 창 | ASSESSED / POPUP_CANDIDATE 및 같은 행의 유효 geometry. 이유는 기존 BOUNDED_WINDOW / SEMANTIC_DIALOG / PANE_WITH_UNDERLAY 중 하나 |

baseline 기록의 다른 행은 유효 geometry의 NO_POPUP/UNCHANGED 또는 ORDINARY_CONTENT_CHANGE만 허용합니다. BASELINE_ONLY 행이 실제로 하나 이상 있어야 합니다. 후보 오탐·불확실 전환·차단을 섞어 통과시키지 않습니다. Dialog 기록의 나머지는 유효 geometry의 NO_POPUP/UNCHANGED만 허용하고, 후보 행이 반드시 하나 이상 있어야 합니다. 다른 phase/attempt의 geometry로 후보를 보충하지 않습니다.

BEGIN은 Activity 시작 전에 수행합니다. baseline은 시작 시 BASELINE_ONLY를 잃지 않도록 RESET하지 않고 관측합니다. 소유 일반 화면의 contentDescription 고정 변경은 실제 이벤트 자극일 뿐 detector 상태나 결과를 주입하지 않습니다. baseline 최초 확인 → 후속 native/원격 검사 → 최종 RESULT 재판정까지 성공한 뒤에만 기록 RESET → Dialog 한 번 show를 진행합니다. RESET은 기존 provider의 기록만 초기화하고 detector/설정/화면 세대/예산을 초기화하지 않습니다. show 뒤에는 후보의 최초 관측을 지우지 않도록 RESET하지 않습니다.

### 기존 detector 상태의 제약

provider BEGIN/RESET은 detector.clear가 아닙니다. 이전 동일 앱/화면의 baseline이 남아 있으면 실제 첫 결과가 UNCHANGED 또는 UNCONFIRMED_TRANSITION일 수 있습니다. 이 경우 BASELINE_ONLY를 만들어 채우거나 다른 앱 화면으로 강제 전환해 detector를 비우지 않습니다. BASELINE_ONLY를 관측하지 못하면 BASELINE_ONLY_NOT_SEEN/INCONCLUSIVE 또는 해당 실패로 종료하고 Dialog를 띄우지 않습니다. 정상 baseline 증거 없이 진행하도록 기준을 낮추지 않습니다. 이 제약은 기존 API에서 안전하게 우회할 수 없으며 사용자 기기 결과로 확인해야 합니다.

## 실제 창·노드와 서비스 시도 결박

- native 별도 샘플은 실제 Activity/소유 Dialog decor에서 createAccessibilityNodeInfo로 얻은 창 ID를 RAM에만 보관합니다. Dialog가 실제 표시 중이고 두 ID가 다르며 Activity ID가 전후 동일해야 합니다. 원시 ID/좌표/원문은 출력하지 않습니다.
- UiAutomation은 실제 소유 ID로 application 창 하나를 선택하고 active 여부, Dialog의 별도 창/상위 layer(배경 창이 목록에 있을 때)를 확인합니다. modal 배경 창이 interactive 목록에서 빠질 수 있으므로 그 경우를 다른 창으로 대체하지 않고 native 소유 ID와 비교합니다.
- 실제 root부터 getChild preorder로 노드를 조회합니다. 같은 package/window, Scroll/입력/password 부재, 고정 어휘, 원시 Rect 비역전을 검사하고 기대 고정 텍스트가 보이는 노드도 요구합니다. 창32/노드128/깊이30/자식128 상한이며 누락·실패를 정상으로 채우지 않습니다. 다른 앱의 root/본문은 읽지 않습니다. 이 검사는 별도 fixture 샘플이며 실제 F0 검사를 대체하지 않습니다.
- 기존 signature/고정 host caller/실제 수집 동의·host 단독 허용·캡처 OFF 정책의 SyntheticDetectionTestProvider를 그대로 사용합니다. RESULT에 해당 소유 ID를 전달하면 provider가 그 창으로 제한한 실제 service rows만 반환합니다. 각 행의 ordinal·disposition·reason·geometry를 함께 검증하며 전체 목록의 중복 ordinal, 차단/오류/overflow도 거부합니다. aggregate candidates 수만으로 판정하지 않습니다.
- 각 phase는 최대3초(최소2초 관측) 동안 실제 서비스 결과를 기다립니다. 준비 샘플도 최대3초이며 실제 관계가 성립해야 합니다. 시간 경과만으로 유효성을 승인하거나 Dialog show/운영 수집을 재시도하지 않습니다. 운영100ms/노드 제한과 세션45초/256기록 제한을 유지합니다.
- 두 phase 모두 후속 노드 검사 직후 같은 session/owned window의 RESULT를 재조회하여 최종 스냅샷을 판정합니다. baseline RESULT↔RESET, Dialog RESULT↔END는 원자적이지 않으며 진행 중인 수집을 drain하지 않습니다.

### 커밋 전 P2/P3 보강

`MinimalDialogObservation.await()`는 실제 Android 계측과 JVM 회귀가 함께 호출하는 suspend 관측 루프입니다. Android에서는 기존 RESULT 조회와 uptimeMillis/delay를, JVM에서는 고정 합성 응답과 가짜 시계/재개 콜백을 전달합니다. 최소2초·마감3초·pause200ms는 유지합니다. 조회 전 및 조회 후 마감을 검사하며, 1.9초 성공 응답 뒤 3초 이후에 재개되거나 IPC 응답이 마감을 넘으면 마지막 verdict와 무관하게 고정 `OBSERVATION_TIMEOUT / INCONCLUSIVE`입니다. 비협력적인 IPC를 강제로 중단한다는 보장은 아닙니다.

`MinimalDialogProgress`의 정상 전이는 START → BASELINE_VERIFIED → DIALOG_WINDOW_VERIFIED → DIALOG_VERIFIED입니다. baseline의 실제 관측·후속 native/remote·최종 RESULT 콜백이 정상 반환해야만 다음 host Dialog 표시 콜백을 호출할 수 있습니다. 표시와 별도 소유 창 확인이 정상 반환해야 Dialog 검증 콜백으로 진행합니다. Dialog 관측·후속 검사·최종 RESULT까지 정상 반환한 마지막 상태만 전체 OBSERVED입니다. 예외/취소/정리 실패는 ABORTED이고 복귀하지 않습니다. 예외용 `failureOutcome()`은 성공 verdict가 잘못 들어와도 OBSERVED를 반환하지 않으며, 최종 JUnit도 마지막 완료 상태와 정리 성공을 함께 요구합니다.

이 상태는 테스트 제어 흐름 기록입니다. 콜백의 실제 Android 동작은 별도 실측이 필요하며 F0 receipt·현재성·TTL·원자성을 증명하지 않습니다. 최종 RESULT는 해당 조회의 스냅샷일 뿐 RESET/END 이전 이후의 모든 진행 중 시도를 검증하는 것은 아닙니다.

유효 geometry의 실제 `UNCERTAIN / UNCONFIRMED_TRANSITION` 또는 `UNCONFIRMED_STRUCTURE`는 고정 `DETECTOR_UNCERTAIN / INCONCLUSIVE`로 구분합니다. 유효한 NO_POPUP/UNCHANGED에서 후보가 없는 `DIALOG_CANDIDATE_NOT_SEEN / FAILURE`와 다르며, 어느 쪽도 JUnit 성공이나 정확도 집계로 승격하지 않습니다.

native/UiAutomation 샘플은 SOURCE=NATIVE_UIAUTOMATION_SEPARATE, 서비스 tuple은 SOURCE=REAL_SERVICE로 출력합니다. 두 출처는 같은 시점이 아니며 원자적으로 결합하지 못합니다. 같은 창 ID의 재사용/이벤트 지연/샘플 사이의 모든 변화를 완전히 배제했다는 뜻도 아닙니다. provider는 해당 소유 창·host로 제한된 기록이므로 모든 다른 앱/시스템 이벤트를 전부 관측했다고 주장하지 않습니다.

## 실패·정리·출력

- FAILURE: baseline의 실제 후보 오탐, 유효한 Dialog 수집이 있으나 후보 없음, 예기치 않은 detector 판정. 이 한 건을 원본 14개 정확도/오탐률로 집계하지 않습니다.
- INCONCLUSIVE: baseline 미확립·서비스 미관측·관측 마감·detector 불확실성·보호/수집 차단·TIME_LIMIT/TARGET_MISSING·geometry 오류·창/노드 불일치·상한·유효하지 않은 응답/진단·overflow·정리 실패. 모두 최종 JUnit 실패이며 skip을 성공으로 세지 않습니다.
- opt-in 누락은 실패합니다. BEGIN의 세션 key는 NonCancellable에서 확보하고 finally의 END, 소유 Dialog dismiss, Activity 종료, UiAutomation flags 복구를 각각 시도합니다. 어느 정리 실패도 전체 성공을 만들 수 없습니다. 이전 P2에서 검증한 테스트 전용 PriorityReleaseScope를 재사용하여 개별 node/root/window 해제 예외가 다른 해제를 생략하거나 원래 검사 실패를 가리지 않게 합니다. 운영 collector finally는 변경하지 않습니다.
- UiAutomation에는 DON'T_SUPPRESS_ACCESSIBILITY_SERVICES를 사용합니다. 본 앱의 동의·설정·권한을 쓰거나 서비스에 클릭/제스처를 요청하지 않습니다. Dialog 레이아웃 크기는 소유 UI를 만드는 설정이며 관측한 bounds를 정렬/clamp/이동하는 것이 아닙니다.
- 출력은 고정 enum/단계·세션 내부 attempt 순번·관측 결과뿐입니다. raw node/text/coordinate/window ID/fingerprint/token/예외 원문은 로그·파일·DB에 남기지 않습니다. 취소·일반 예외도 최상위에서 고정 실패로 전달합니다.
- framework getter/IPC/main-thread 호출의 비협력적 blocking, VM 치명적 오류·프로세스 종료에서 강제 중단/완전 정리는 보장하지 않습니다. 별도 샘플과 서비스의 동일 시도·현재성·TTL 결합도 미구현입니다.

## Windows API 37 실행

개인정보 없는 전용 에뮬레이터에서 프로젝트 루트 PowerShell로 빌드·설치합니다.

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest :app:lintDebug :capture-test-host:lintDebug
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "emulator-5554" # 실제 API37 대상 serial 확인
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk
```

UI에서 서비스 활성화·수집 동의·host kr.closeforyou.capturetest 단독 허용·분석 시작을 직접 확인합니다. 캡처 동의 OFF/캡처 허용 목록 없음 상태를 유지합니다. 기존 detector 상태 때문에 baseline이 미확립될 수 있다는 위 제약도 확인합니다. 시험은 설정을 강제로 바꾸지 않습니다.

```powershell
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceMinimalDialogOptInTest -e f1MinimalDialog true -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

성공 기대값은 SERVICE_CONNECTION_READY → BASELINE_CONFIRMED → 실제 서비스 Dialog POPUP_CANDIDATE 행 → DETECTOR_CANDIDATE → SUMMARY RESULT=OBSERVED 및 OK (1 test)입니다. 표시만 성공하거나 baseline 없이 후보만 관측하면 통과하지 않습니다. 모든 출력은 ATOMIC_BINDING=NONE / F0_COMPLETENESS=NOT_PROVEN / F1_ACCURACY=NOT_TESTED / ACTIONS=NONE / OCR=NOT_TESTED 범위를 유지합니다.

## 검증과 미해결 범위

P2/P3 보강 전 로컬 검증 기록: 신규 JVM18개 포함 전체515개(앱396/호스트119), 실패·오류·skip0입니다. 이 기록만으로 이후 발견한 제어 흐름 결함이 검증된 것은 아닙니다.

보강 후 검증: `MinimalDialogContractTest`는 기존18개와 추가19개, 총37개 통과입니다. 실제 계측과 동일한 suspend 관측 루프/단계 계약에 가짜 시계·응답·pause 콜백을 연결해 baseline/Dialog 각각 1.9초 성공→3.1초 재개, 정확히3초/초과 IPC, 최소2초, 단계 누락·순서 위반, 예외 경로 성공 verdict, 최종 RESULT의 뒤늦은 차단/overflow/미관측/중복/잘못된 진단, 불확실성과 취소 전파를 검증했습니다. JVM의 host 표시 콜백은 합성이므로 실제 Dialog 표시를 검증한 것은 아닙니다.

전체 JVM534개(앱396/호스트138), 실패·오류·skip0이며 양쪽 JVM 작업을 `--rerun`으로 실제 재실행했습니다. 앱/호스트 Debug·AndroidTest APK와 양쪽 debug Lint(이슈0) 통과, Gradle BUILD SUCCESSFUL(46초), 대상 회귀/계측 컴파일 BUILD SUCCESSFUL(23초), 경고 출력 없음. 기존 APK 패키징 검사와 추가 DEX 정의 검사에서 새 관측/단계/판정/계측 클래스가 driver에만 포함되고 운영 앱/호스트에는 없음을 확인했습니다. Activity는 호스트에만 포함됩니다. `git diff --check` 및 미추적 파일 whitespace 검사도 통과했습니다. 기존 추적 파일은 호스트 Manifest의 Activity 한 줄 추가 외에는 변경하지 않았습니다. Release/CI 및 실제 Android 계측 성공을 뜻하지 않습니다.

JVM은 순수 판정과 공유 관측 루프/단계 완료의 제어 흐름을 검증하며 실제 Android 서비스/창/세션 순서와 수명은 입증하지 않습니다. Android 계측은 개발 환경에서 실행하지 않았습니다. baseline 실제 관측 가능성, Dialog 별도 창·서비스 후보·정리 동작은 API37 실측 전 미검증입니다. API35도 별도 검증 대상입니다.

성공하더라도 최소 합성 Dialog 하나의 F1 관측에 한정합니다. 기존 Scroll 역전 Rect, Window/frame 불일치, 원본14개 UNOBSERVED=14는 미해결입니다. F0 완전성·화면 현재성·TTL·일반 앱 F1 정확도·OCR·자동 행동 안전성을 입증하지 않습니다. 기존 F0 보호 우선순위 API37 4/4 결과도 이 새 시험의 실행 결과로 재사용하지 않습니다.
