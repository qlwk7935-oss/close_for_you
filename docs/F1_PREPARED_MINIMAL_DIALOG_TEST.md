# F1 PREPARATION → 엄격 BASELINE → 실제 Android Dialog 독립 시험

기준 브랜치 `feature/f1-popup-detection`, HEAD `a326099a68973ac39a5c4306be1ebc941ae8bffa`. 커밋·푸시·병합하지 않은 검토용 구현입니다. PR #7 미병합, PR #10 Draft를 유지합니다. 기존 두 최소 계측과 원래 F1 14개·운영 코드·Manifest·정책·동의·수집 예산은 변경하지 않습니다.

## 질문과 기존 사용자 실측의 위치

사용자 API37 geometry 독립 시험에서는 LAUNCH에 WINDOW_OUTSIDE_FRAME와 유효한 일반 화면 평가가 섞였고, STABLE에는 유효한 ORDINARY_CONTENT_CHANGE를 관측했습니다. Dialog를 표시하지 않은 별도 세션이므로 그 결과를 이 시험의 baseline이나 후보 증거로 사용하지 않습니다. 원인·시간적 원자성·F1 정확도를 입증한 결과도 아닙니다.

기존 ServiceMinimalDialogOptInTest는 launch 기록 전체가 유효하고 BASELINE_ONLY가 있어야 하는 별도 엄격 시험으로 그대로 둡니다. 새 시험은 준비 기록을 별도 보존하고, RESET 이후 실제 유효한 일반 화면 평가를 관측한 뒤 별도 Dialog의 BOUNDED_WINDOW 후보를 요구합니다. 기존 시험을 통과시켰다는 의미가 아닙니다.

## 신규 파일과 기존 helper 재사용

- `ServicePreparedMinimalDialogOptInTest.kt`: 새 BEGIN/Activity, 실제 provider 조회, 두 번의 단계별 RESET, host 자극, 후속 검사·최종 RESULT·정리.
- `PreparedMinimalDialogContract.kt`: phase별 동일 행 판정, 관측 루프, 누적 RESULT 단조성, phase 소유 scope, 완료 상태 머신. 순수 Kotlin입니다.
- `PreparedMinimalDialogContractTest.kt`: 위 실제 공유 계약의 합성 IPC·가짜 시계·해제 callback 회귀.
- 이 문서.
- 추가 허용 helper `PreparedDialogReader.kt`: MinimalGeometryReader는 Dialog 존재를 거부하므로 Dialog의 native/원격 소유 창·최소 트리·raw geometry 검사를 분리했습니다. 기존 reader는 수정하지 않았습니다.

기존 MinimalDialogActivity의 TextView와 showOwnedDialog를 사용합니다. MinimalGeometryReader, MinimalGeometryPolling의 firstCoherent/stable, GeometryReadSafety, PriorityReleaseScope, DetectionAttemptOutput을 그대로 재사용합니다. 새 helper도 **동일한 minimal.readSafety 인스턴스**를 사용합니다. 새 Activity/권한/리소스/provider hook은 없습니다.

## PREPARATION

BEGIN으로 새 session을 확보한 다음 Activity를 시작합니다. 캡처 OFF/수집 동의/host 단독 허용은 기존 signature-gated provider가 확인하며 시험은 변경하지 않습니다.

첫 coherent 샘플과 같은 소유 창·회전·복사 Configuration을 요구합니다. 실제 raw geometry의 최소3회·1초 이상 동일 조건을 최대3초 안에 확인합니다. node/root/window는 각 취득 경계의 finally에서 해제합니다. 원격 최소 트리는 실제 getChild 순회로 확인하며 입력·암호·Scroll·클릭 기능·허용되지 않은 문구·누락 자식·역전 Rect를 거부합니다. 이 별도 표본은 서비스 F0 순회 완료 증명이 아닙니다.

같은 세션/Activity 창의 실제 RESULT를 최소2초·최대3초 관측합니다. PREPARATION에서만 양수 extent의 일관된 UNCERTAIN/INVALID_GEOMETRY/WINDOW_OUTSIDE_FRAME 행을 **거부 geometry의 진단 기록**으로 허용합니다. 유효한 NO_POPUP/BASELINE_ONLY·UNCHANGED·ORDINARY_CONTENT_CHANGE도 기록할 수 있지만 어느 것도 다음 단계 baseline 증거가 아닙니다. 보호/수집 차단, 미관측, overflow, 잘못된 진단·소유권·해제 실패는 종료합니다.

후속 동일 anchor 안정성 검사와 최소 트리 재검사 뒤 마지막 RESULT 전체를 다시 검증·고정 코드로 출력합니다. PREPARATION_RECORDED는 baseline 완료나 운영 geometry 허용을 뜻하지 않습니다.

## 엄격 BASELINE

PREPARATION_VERIFIED 뒤 기록 RESET을 정확히 한 번 호출합니다. detector·설정·budget은 초기화하지 않습니다. 기존 일반 TextView가 입력·클릭·포커스/선택 기능 없는 단일 자식인지 확인한 뒤 enabled를 false로 바꾸고 고정 합성 contentDescription을 지정합니다. 클릭·제스처 API를 호출하지 않습니다.

운영 AndroidSnapshotCollector의 NODE_PROPERTIES_READ에서 읽은 `enabled`는 기존 V1 structuralFields에 포함됩니다. contentDescription만의 변경은 content 지문에는 들어가지만 구조 변경을 보장하지 않습니다. 이 실제 fixture 변경이 특정 detector 판정을 보장한다고 가정하지 않습니다. 후속 원격 노드에서 고정 본문/description과 disabled 상태까지 확인하며 원문·값을 출력하지 않습니다.

RESET 이후 같은 창의 실제 서비스 행은 전부 ASSESSED/NO_POPUP, VALID, 양수 extent, WITHIN_FRAME, OUTSIDE_EDGES=NONE이어야 합니다. 그 중 BASELINE_ONLY 또는 ORDINARY_CONTENT_CHANGE가 최소1개 있어야 합니다. UNCHANGED만 있으면 불충분하며 관측 마감 또는 최종 검증에서 실패합니다. 좋은 행 옆에 후보·불확실성·geometry 오류·차단이 하나라도 있으면 실패합니다.

이 계약은 fresh BASELINE_ONLY와 동일하지 않습니다. 기존 detector의 ORDINARY_CONTENT_CHANGE 분기는 현재 일반 frame과 빈 popupKeys를 finish에 저장한다는 코드상 근거로 허용합니다. UNCHANGED는 기존 팝업의 중복 억제에서도 나오므로 단독 근거로 허용하지 않습니다. 이전 세션/준비 phase의 BASELINE_ONLY를 재사용하지 않습니다.

서비스 조회 전후 raw anchor를 확인하고, 조회 후 같은 anchor의 최소3회/1초 안정 조건과 실제 원격 자극 상태를 재검사한 다음 마지막 RESULT를 검증해야 BASELINE_VERIFIED입니다. 그 전에는 showOwnedDialog에 도달할 수 없습니다.

## 실제 DIALOG

BASELINE_VERIFIED 뒤 기록 RESET을 한 번 수행하고 host main에서 showOwnedDialog를 한 번 호출합니다. 표시 후 RESET하지 않습니다. Activity native 창 ID와 baseline 소유권·decor/content 경계·논리 frame·회전/Configuration이 유지돼야 합니다. Dialog는 표시 중인 별도 소유 application 창이어야 하며 기본 display·활성 상태·package/window 소속·확인 가능한 배경 layer 관계를 검사합니다. 배경이 interactive 목록에서 빠진 경우 다른 창으로 대체하지 않습니다.

Dialog reader는 native → remote → remote → native의 실제 값 일치를 확인합니다. root/창/native decor/content의 검증 가능한 포함 관계를 원래 좌표로 검사하며 보정·clamp·margin·리사이즈는 하지 않습니다. 창 ID/경계/configuration/remote node bounds를 짧은 RAM 비교 key로만 보유하고 문자열 표현은 redacted입니다. 같은 raw Dialog 샘플의 안정 조건 및 서비스 관측 전후 anchor를 요구합니다.

P2 보강 후에는 root 취득 직후 `PreparedNodeOwnership.read`에서 host package와 기대 window ID를 먼저 검사합니다. 통과한 callback 안에서만 root geometry·본문·자식 getter를 호출하며, 자식 순회도 동일 계약을 사용합니다. null/불명확/불일치/메타데이터 조회 예외는 데이터를 읽지 않고 종료하고 finally의 독립 해제는 계속 수행합니다. 이 계약은 테스트 전용 선행 검사이며 Android 소유권의 원자적 증명이 아닙니다.

Dialog 소유 창의 **같은 ATTEMPT 행**에서 ASSESSED/POPUP_CANDIDATE/BOUNDED_WINDOW와 유효 geometry가 모두 있어야 합니다. 다른 candidate 사유는 이 좁은 시험에서 허용하지 않습니다. 나머지 행은 유효 geometry의 NO_POPUP/UNCHANGED만 허용합니다. Dialog 표시나 aggregate candidate 수만으로 성공하지 않습니다. 후속 원격/native/anchor 검사 뒤 마지막 RESULT 검증까지 끝나야 DIALOG_VERIFIED입니다.

## 공유 제어 흐름과 실패 보존

상태는 START → PREPARATION_VERIFIED → BASELINE_VERIFIED → DIALOG_WINDOW_VERIFIED → DIALOG_VERIFIED입니다. 오류/취소는 ABORTED이며 복귀하지 않습니다. 각 phase는 자신에게 발급된 scope만 사용할 수 있고 다른 owner·종료된 scope는 거부됩니다. scope는 인증된 Android 증명이나 운영 권한이 아닙니다.

각 phase가 정상 반환하려면 최종 RESULT 검증이 명시적으로 완료돼야 합니다. 이미 본 ordinal의 행 변경/누락, 중복 ordinal, 이전 phase 최종 ordinal 이하의 행을 거부합니다. 이전 phase scope의 기록은 baseline/candidate 플래그로 승계하지 않습니다. 숫자 순번과 이 경계는 provider의 실제 session/window scoping을 대체하지 않습니다.

PreparedDialogObservation.await는 실제 계측과 JVM이 함께 호출합니다. PREPARATION/BASELINE/DIALOG 모두 서비스 polling은 최소2초/마감3초/pause200ms이며 조회 전후 마감을 검사합니다. 마감 직전 성공 뒤 지연 재개나 늦은 응답도 고정 timeout입니다.

최종 RESULT의 P2 보강은 응답 수신 시점뿐 아니라 **완료 수락 경계**까지 포함합니다. `finalSnapshot`이 실제 조회 시작 시 같은 monotonic clock의 `PreparedFinalDeadline`을 만들고, 조회 전후 → 진단 출력/검증 callback → callback 뒤까지 확인합니다. 같은 deadline을 `assess(final=true)`에 전달해 모든 행 검증 후 scope.final 기록 직전·직후 확인하며, 그 사이 출력·suspend 작업은 없습니다. phase verify도 같은 deadline을 전이 직전·직후 검사합니다. 진단/판정/스케줄링으로 3,000ms에 도달하면 성공 verdict가 있어도 OBSERVATION_TIMEOUT/INCONCLUSIVE로 폐기합니다. deadline이 없으면 최종 표식도 거부합니다.

host 준비/전후 안정성/정리를 포함한 phase 전체가 3초라는 뜻은 아닙니다. 기존 서비스 polling 한도를 연장하거나 다시 시작하지 않습니다. 마지막 시계 조회 직후의 선점이나 실제 Android 상태까지 원자적으로 보장하는 장치는 아닙니다. phase-success 코드를 포함한 어떤 예외 verdict도 failureOutcome에서 OBSERVED가 될 수 없습니다.

GeometryReadSafety는 최초 검사/취소 사유와 동반 해제 실패를 고정 enum으로 따로 보존합니다. 원본 예외 객체·메시지는 기록에 보관하지 않습니다. 최초 root/layout 미준비의 재관측도 해제가 실패했으면 즉시 중단합니다. node/root/window의 개별 일반 해제 예외는 다른 해제를 생략하지 않으며 원래 검사 실패·취소를 가리지 않습니다. 공유 기록은 phase/RESET/close로 초기화하지 않고 최종 성공 조건에도 포함합니다.

P3 보강의 `PreparedDialogCleanup.run`은 실제 finally와 JVM에서 공유합니다. Android는 NonCancellable 안에서 이 계약에 END, Dialog dismiss, Activity 종료 및 isDestroyed 확인, flags 복구·재조회 callback을 전달합니다. 첫 callback이 일반 예외/취소로 실패해도 나머지를 순서대로 시도하고, 여러 실패를 SESSION_END/DIALOG_DISMISS/ACTIVITY_FINISH/FLAGS_RESTORE enum으로만 보존합니다. callback/원본 예외를 필드에 보관하지 않습니다. 실행 전·미완료·어느 실패라도 clean은 false이고, 재실행으로 실패를 지울 수 없습니다.

기존 body 취소가 finally를 통과하면 run은 이를 덮지 않고 원래 취소가 계속 전파됩니다. cleanup에서만 발생한 취소는 나머지 정리 뒤 `propagateCancellation()`에서 고정 메시지로 전파합니다. finally의 진단 출력 IPC 실패도 `report` 계약으로 격리해 원래 취소를 일반 예외로 덮지 않으며, 보고 실패/취소는 clean=false로 기록합니다. 원문 예외를 출력하지 않습니다. 정리 후 기존 Job ensureActive도 유지합니다.

정리/해제 실패는 전체 성공을 막습니다. 확인 직후의 취소까지 원자적으로 막는 보장이나 비협력적인 Binder/getter/main-thread 호출 강제 중단, VM 치명적 오류·프로세스 종료에서 완전한 정리 보장은 없습니다.

## 비원자성 및 결과 해석

native/UiAutomation 표본은 HOST_UIAUTOMATION_SEPARATE, 실제 service 행은 REAL_SERVICE로 구분합니다. ATOMIC_BINDING=NONE / SERVICE_FRAME_BINDING=UNAVAILABLE입니다. host frame을 같은 서비스 ATTEMPT의 frame이라고 주장하지 않습니다.

최종 RESULT는 완료 행 스냅샷입니다. RESULT↔RESET/END는 원자적이지 않으며 진행 중 수집을 drain하지 않습니다. RESET이 관측 세대를 바꾸어 이전 시도의 발행을 거부하더라도 pending 이벤트의 인과적 자극 결합이나 detector 상태 원자성을 보장하지 않습니다. ordinal floor는 확인한 이전 행의 재사용만 거부하며 관측되지 않은 모든 이전 이벤트를 식별하지 못합니다.

실제로 관측한 A→B→A는 안정 횟수/시작 시각을 초기화하고, anchor 확립 후 B는 즉시 거부합니다. 샘플 사이에서 발생했다가 사라진 미관측 ABA는 보장 밖입니다. 시간 경과나 관계 enum 일치만으로 stable을 인정하지 않습니다.

OBSERVED/JUnit 성공은 이 새 세션에서 최소 일반 화면 평가와 합성 Dialog 한 건의 BOUNDED_WINDOW 후보를 관측한 의미뿐입니다. F0 전체 완전성·현재성·TTL·토큰/지문 결합·일반 앱 F1 정확도·OCR·행동 안전성은 입증하지 않습니다. 원본14개 UNOBSERVED=14, Scroll 역전 Rect, Window/frame 원인과 운영 collector 정리 예외는 미해결입니다. 실패/INCONCLUSIVE 모두 JUnit 실패이며 skip을 성공으로 처리하지 않습니다.

## Windows API37 실행

개인정보 없는 전용 에뮬레이터에서 수동으로 서비스 연결, 수집 동의, host 단독 허용, 분석 시작을 확인합니다. 캡처 동의는 OFF, 캡처 허용 목록은 비어 있어야 합니다. adb로 권한·동의를 강제하지 않습니다.

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest :app:lintDebug :capture-test-host:lintDebug
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "emulator-5554" # 실제 API37 대상 확인
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServicePreparedMinimalDialogOptInTest -e f1PreparedMinimalDialog true -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

기대하는 정상 흐름은 SERVICE_CONNECTION_READY → PREPARATION_RECORDED → OBSERVED_NON_POPUP_BASELINE → BOUNDED_WINDOW_CANDIDATE → SUMMARY/OBSERVED, 마지막 OK(1 test)입니다. 실제로 이 출력이 나온다고 보장하지 않습니다. 다른 결과는 고정 문제/정리/해제 코드와 실패로 남깁니다. 전체 Logcat이나 화면을 수집하지 않습니다.

## 검증 상태

P2/P3 보강 **이전** 구현의 검증 기록은 신규42개 포함 JVM619개(앱396/호스트223), 실패·오류·skip0입니다. 이 기록을 이번 보강 결과로 재사용하지 않습니다. 당시 첫 전체 실행에서는 TIME_LIMIT/TARGET_MISSING 회귀의 Dialog 선행 입력을 바로잡았으며 운영/판정 정책은 완화하지 않았습니다.

이전 전체 Gradle 명령은 BUILD SUCCESSFUL(58초), 앱/호스트 Debug·AndroidTest APK, 양쪽 Lint 이슈0 및 DEX 격리 통과였습니다. 커밋 전 리뷰에서는 최종 IPC 뒤 출력/검증 지연에 대한 마감 공백과 root 소유권 검사 이전 geometry 조회를 P2로 확인했습니다. 이 두 경계를 위 공유 계약으로 수정했습니다.

이번 보강은 최종 마감/표식/phase 전이, root·자식 소유권과 getter 순서, 실제 정리 callback·취소·보고 실패에 대한 JVM27개를 추가했습니다. 대상67개와 계측 Kotlin 컴파일을 먼저 통과한 뒤 보고 실패 회귀2개를 더했습니다. 최종 대상69개, 전체 JVM646개(앱396/호스트250)를 --rerun으로 실제 재실행해 실패·오류·skip0입니다. 가짜 getter·시계·cleanup callback 회귀는 실제 Android framework getter/recycle 실패나 실기기 Job 취소를 재현한 것이 아닙니다.

이번 최종 Gradle 명령은 BUILD SUCCESSFUL(1분3초), 양쪽 Debug/AndroidTest APK 빌드와 양쪽 Lint(이슈0) 통과이며 경고 출력은 없습니다. 기존 패키징 검사와 추가 DEX class_def 검사에서 이번 deadline/소유권/정리 계약과 새 계측/reader는 호스트 AndroidTest driver에만 존재하고 본 앱·호스트 앱·본 앱 AndroidTest APK에는 없음을 확인했습니다. JVM 회귀 클래스도 driver에 포함되지 않습니다. 추적 파일181개의 SHA-256은 보강 시작 시점과 동일하며 git diff --check 및 미추적 신규5개 파일 공백 검사가 통과했습니다. 스테이징·커밋·푸시·병합·Release·CI는 수행하지 않았습니다.

기존 추적 파일181개는 작업 시작 시 SHA-256과 동일합니다. git diff --check와 신규5개 파일의 trailing whitespace/최종 newline 검사 통과, index는 비어 있고 신규파일은 미추적 상태입니다. Release/CI·커밋·푸시·병합은 이번에 수행하지 않았습니다.

실제 Android API37 계측은 실행하지 않았습니다(adb 연결 기기 없음). 합성 IPC/해제/취소 callback의 JVM 통과는 실제 framework recycle 실패·서비스 F0 완료·실기기 Job 취소를 재현한 것이 아닙니다. 실제 이벤트 전달·enabled 반영·최소 트리 장식 노드·Dialog 별도 창·창/노드 경계 포함·layer·안정성·수명·정리는 API37에서 확인해야 합니다.

운영 연결/새 권한/보호 우회는 없습니다. 남는 P1은 전체 F0 coverage, 실제 검사 값·salt/토큰/지문의 동일 시도 결합, 세션·화면 현재성·TTL입니다. P2 한계는 비협력적 호출 강제 중단 불가, RESULT/RESET/END·출처의 비원자성, 기존 운영 collector 정리 예외입니다. P3 한계는 미관측 ABA와 제한된 합성 fixture/표본의 일반화입니다. 새 엄격 helper가 실제 Android 장식·배경 창 조건을 만족하지 못하면 결과는 INCONCLUSIVE로 남겨야 하며 시험 통과를 위해 조건을 보정하지 않습니다.

이번 한정 정적 점검·합성 회귀에서 추가 P0/P1/P2 필수 수정은 확인하지 못했습니다. 검토 대상 P2 두 건과 P3 정리 회귀 공백은 위 실제 공유 코드 경계로 보강했으며, 이는 Android 실측 성공이나 일반 운영 안전성 검증을 뜻하지 않습니다.
