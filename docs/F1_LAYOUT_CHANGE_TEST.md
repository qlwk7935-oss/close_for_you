# F1 최소 일반 문서 LAYOUT_CHANGE 독립 음성 대조군

기준: `feature/f1-popup-detection`, `bb80e530920fffbe0046e75d9de85610c9eed2ad`. 음성 대조군은 negative control이라는 실험 용어이며 TTS/음성 UI를 추가하지 않습니다. Windows API37의 Prepared Minimal Dialog 두 번 성공은 사용자 제공 로그에 따른 별도 양성 사례입니다. 그 기록을 이 시험의 baseline·오탐 방지 성공으로 재사용하지 않습니다. 이 시험은 API37에서 아직 실행하지 않았습니다.

## 범위와 기존 시나리오와의 차이

원본 DetectionScenarioActivity의 LAYOUT_CHANGE는 ScrollView 문서에서 heading 문구와 첫 행 padding을 바꿉니다. 원본 14개는 기존 Scroll 역전 Rect 및 Window/frame 문제로 UNOBSERVED=14이며 그대로 둡니다. 본 시험은 그 시험의 화면·정답을 대체하지 않는 별도 최소 사례입니다.

기존 MinimalDialogActivity의 ScrollView/입력/민감 내용 없는 단일 TextView를 그대로 사용합니다. 새 Activity·Manifest·권한을 추가하지 않고 Dialog는 표시하지 않습니다. 기존 Prepared 계측·reader·시간 계약·provider·detector·collector를 수정하지 않습니다. 서비스 설정·동의·허용 목록을 쓰거나 접근성 클릭·제스처·스크린샷·OCR을 요청하지 않습니다.

신규 파일은 ServiceLayoutChangeOptInTest.kt, LayoutChangeReader.kt, LayoutChangeContract.kt, LayoutChangeContractTest.kt와 이 문서입니다. AndroidTest driver 및 JVM 전용이며 본 앱 실행 경로에 연결하지 않습니다.

## 실제 관측 단계

1. 기존 signature provider에서 명시 opt-in BEGIN을 받습니다. 서비스 연결·수집 동의·host 단독 허용·분석 시작·캡처 동의 OFF·캡처 허용 목록 비어 있음은 사용자가 미리 설정하며 시험은 변경하지 않습니다.
2. PREPARATION: 실제 최소 Activity를 시작하고 소유 창·raw geometry·회전/Configuration과 최소 트리를 확인합니다. 반복 raw 값 최소3회/1초 이상 안정성을 확인합니다. 초기 WINDOW_OUTSIDE_FRAME는 기존 준비 계약의 거부 진단으로만 기록합니다. 후보·차단·미관측·잘못된 진단·overflow는 준비 성공이 아닙니다. 후속 anchor 검사 뒤 마지막 RESULT를 검증합니다.
3. 엄격 BASELINE: 기록 RESET을 한 번 하고 기존 PreparedDialogReader.stimulate로 TextView의 enabled=false 및 고정 합성 description을 설정합니다. 실제 서비스의 같은 session/소유 window 행 모두 유효 geometry의 NO_POPUP이어야 하고 BASELINE_ONLY 또는 ORDINARY_CONTENT_CHANGE가 최소 하나 필요합니다. UNCHANGED만으로는 불충분합니다. 실제 remote 자극 필드와 후속 raw anchor·최종 RESULT를 확인하기 전에는 layout 변경을 요청하지 않습니다.
4. 실제 LAYOUT_CHANGE: 소유 창/기존 baseline을 재확인하고 기록 RESET을 한 번 합니다. host main에서 단일 TextView의 LayoutParams 높이를 content의 절반으로 바꿉니다. text·description·enabled는 바꾸지 않으며 sendAccessibilityEvent/판정 주입은 없습니다. 실제 layout 완료를 기다려 native 접근성 노드 bounds의 변화가 정확히 이 자식의 절반 높이에 해당하는지 확인합니다. content·window·display frame·root·Configuration은 그대로여야 합니다. 원래 bounds를 정렬·clamp·이동시키는 처리가 아닙니다.
5. 변경 후: 같은 raw 변경 샘플의 최소3회/1초 안정성을 확인한 뒤 실제 서비스 결과를 조회합니다. 별도 구간의 새 ordinal이며 동일 행의 NO_POPUP / ORDINARY_CONTENT_CHANGE / VALID / 양수 extent / WITHIN_FRAME / OUTSIDE_EDGES=NONE을 최소 하나 요구합니다. BASELINE_ONLY·UNCHANGED만으로는 성공하지 않습니다. 후속 anchor 안정성 검사와 마지막 RESULT 전체 재판정까지 끝내야 CHANGE_VERIFIED입니다.

LayoutMutation의 순수 JVM 검사는 실제 reader가 얻은 native nodeBounds에 사용됩니다. LayoutChangeReader는 기존 MinimalGeometryReader/PreparedDialogReader를 통해 실제 native 및 UiAutomation 최소 트리도 검사합니다. TextView 실제 높이·layout 완료와 raw bounds 변화는 host 증거이며, 서비스가 그 값을 원자적으로 읽었다는 증명은 아닙니다.

## 결과와 실패

모든 준비·baseline·변경 관측 행에서 보고된 POPUP_CANDIDATE는 오탐 실패입니다. 같은 bounded 응답의 다른 행이 geometry 오류/차단이거나 overflow가 있어도 allowlist를 통과한 후보 행을 먼저 확인해 FALSE_POSITIVE로 보고합니다. 이는 후보를 관측했다는 실패 분류이며 overflow·잘못된 응답/진단의 관측 완전성을 인정하는 것이 아닙니다. 어떤 경우에도 잘못된 geometry·차단·UNCERTAIN·소유권 불일치·해제 실패·빈 결과를 성공으로 계산하지 않습니다.

실제 계측과 JVM은 같은 LayoutChangeContract/Progress를 사용하며 기존 PreparedDialogObservation.await/finalSnapshot, PreparedFinalDeadline, PreparedDialogCleanup을 그대로 호출합니다. 변경 구간도 일반 화면 검사 분기를 재사용하되 ORDINARY_CONTENT_CHANGE가 없는 결과는 BASELINE_INSUFFICIENT로 남겨 기다립니다. PREPARATION 증거를 엄격 구간으로 승계하지 않습니다.

BASELINE/변경의 정상 초기 빈 RESULT만 같은 관측 시계 안에서 기다립니다. 이전 행의 소실/변경·중복 ordinal·확인된 이전 phase ordinal 재사용은 거부합니다. 최소2초/3초 마감이며 정확히3,000ms부터 실패합니다. RESET은 단계마다 한 번, 기록 세대만 바꾸고 detector·수집 예산·설정을 초기화하지 않습니다. 최초 준비 미관측은 계속 즉시 거부합니다. 마지막 RESULT가 없거나 불완전하면 완료하지 않습니다. IPC·진단 출력·최종 표식·phase 전이는 같은 final deadline을 공유합니다.

단계: START → PREPARATION_VERIFIED → BASELINE_VERIFIED → LAYOUT_CHANGED → CHANGE_VERIFIED. 실패/취소는 ABORTED이며 복귀하지 않습니다. 처음 정리가 실패해도 END·예상하지 않은 Dialog 정리/부재 확인·Activity 종료 및 isDestroyed 확인·flags 복구/재조회를 각각 시도합니다. NonCancellable 안의 공유 정리 계약을 사용하고 이후 Job 취소를 확인합니다. 해제 실패는 동일 MinimalGeometryReader.readSafety에 보존하며 RESET/close로 지우지 않습니다.

성공 출력은 F1_LAYOUT_SUMMARY / RESULT=OBSERVED_NEGATIVE_CONTROL 및 OK(1 test)입니다. 관측이 불완전하면 INCONCLUSIVE, 후보가 관측되면 FALSE_POSITIVE이며 둘 다 JUnit 실패입니다. skip·미관측을 성공으로 처리하지 않습니다. 출력은 고정 phase/상태/ordinal/진단 코드뿐입니다. 원문·좌표·실제 창 ID·timestamp·token·fingerprint·원본 예외 메시지를 로그나 파일에 저장하지 않습니다.

## 비원자성과 미입증 범위

ATOMIC_BINDING=NONE / SERVICE_FRAME_BINDING=UNAVAILABLE. host/native/UiAutomation과 REAL_SERVICE는 서로 다른 표본입니다. 확인한 소유 창의 관측 스냅샷 전체에서 후보 부재를 요구하지만 RESULT↔RESET/END는 비원자적이며 pending 수집을 drain하지 않습니다. 마지막 조회 뒤 미발행된 행까지 후보가 없었다고 보장하지 않습니다. 세대 검사와 ordinal floor도 이전 pending 이벤트와 자극의 인과관계를 증명하지 않습니다.

안정성은 단속적 동일 raw 표본이며 관측한 변화는 anchor에서 거부하지만 미관측 ABA는 보장 밖입니다. 비협력적 Binder/framework 호출의 강제 중단, VM 치명적 오류·프로세스 종료에서의 완전한 정리도 보장하지 않습니다. 실제 이벤트 전달·disabled TextView의 layout 결과·remote 장식 노드·3초 내 ORDINARY_CONTENT_CHANGE 도착은 Android 실측이 필요합니다. 조건을 만족하지 못하면 실패/INCONCLUSIVE로 남기며 운영 정책을 완화하지 않습니다.

이 한 사례가 성공해도 전체 F0 완전성·현재성·TTL·token/지문 결합, 일반 앱 F1 정확도·원본 14개 통과·OCR·행동 안전성을 뜻하지 않습니다. 기존 Scroll/Window 문제와 운영 collector 정리 예외는 미해결입니다. PR #7 미병합, PR #10 Draft를 유지합니다.

## Windows API37 재검증

개인정보 없는 전용 에뮬레이터에서 위 준비 설정을 수동 확인합니다. 실제 사용자 메시지·은행·결제 앱은 시험하지 않습니다.

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "emulator-5554" # 실제 API37 대상 확인
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceLayoutChangeOptInTest -e f1LayoutChange true -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

실제 API37 대상 serial을 확인해 모든 adb 명령의 -s에 지정합니다. 서비스에 클릭·제스처를 요청하지 않고 host가 자기 View의 LayoutParams만 바꿉니다. 기대 결과를 실제 실행 결과로 기록하지 않습니다.

## 로컬 검증

JDK17로 다음 명령을 실행해 BUILD SUCCESSFUL(1분14초)을 확인했습니다. 앱·호스트 JVM은 각각 --rerun으로 실제 실행했습니다.

```text
./gradlew --no-daemon :app:testDebugUnitTest --rerun :capture-test-host:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest :app:lintDebug :capture-test-host:lintDebug
```

신규 LayoutChangeContractTest 33개, 전체 JVM692개(앱396/호스트296)가 실패·오류·skip0으로 통과했습니다. 기존 Prepared Minimal Dialog JVM82개도 포함됩니다. 양쪽 Debug/AndroidTest APK와 양쪽 Lint(이슈0), 기존 패키징 검사 및 새 LayoutChange 클래스의 driver 전용 DEX class_def 격리 검사를 통과했습니다. JVM 회귀 클래스는 APK에 포함되지 않습니다. 경고 출력은 없었습니다.

기존 추적 파일은 변경하지 않았고 새 테스트·문서5개만 미추적 상태입니다. git diff --check 및 신규파일 공백/최종 newline 검사를 통과했습니다. JVM의 가짜 IPC·시계·host callback·해제 callback은 Android 서비스 F0나 실제 이벤트/recycle 성공을 증명하지 않습니다. API37 일반 화면 오탐 방지는 아직 미검증이며, 기존 Dialog 계측도 Codex가 이번에 Android에서 재실행하지 않았습니다. 커밋·푸시·병합하지 않았습니다.
