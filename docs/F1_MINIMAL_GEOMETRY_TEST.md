# F1 최소 Activity — launch/stable geometry 출처 진단

기준: `feature/f1-popup-detection` / `57cc12989d47730a64fafa8923cc2fb182c6035d`. 새 독립 테스트 전용 계측이며 커밋·푸시·병합하지 않습니다. 기존 MinimalDialogActivity의 일반 화면만 재사용하고 Dialog를 표시하지 않습니다. Manifest·기존 helper/계측·원래14개 시나리오·운영 코드는 변경하지 않습니다. PR #7 미병합, PR #10 Draft 유지.

## 질문과 기존 실측

사용자 API37 MinimalDialog 시험은 서비스 연결 후 실제 서비스 두 ATTEMPT에서 INVALID_GEOMETRY/WINDOW_OUTSIDE_FRAME(RIGHT,BOTTOM 및 RIGHT)을 보고했습니다. 별도 native/UiAutomation 최소 트리는 확인됐으나 baseline은 GEOMETRY_REJECTED/INCONCLUSIVE로 실패했고 Dialog/후보 판정은 미시험입니다. WITHIN_ONE_SECOND만으로 애니메이션 원인이라고 단정하지 않습니다. 이 문제는 Scroll 숨김 자식 REVERSED_Y의 snapshot pass 차단과 별개입니다.

새 질문은 같은 최소 Activity의 시작 구간과 **실제로 관측된 안정 구간**에서 창 초과가 남는지, bounds 출처들끼리 어떤 관계인지입니다. 관측 완료는 F1 baseline·후보 성공이나 geometry 허용을 뜻하지 않습니다.

## 변경 범위와 재사용

- `ServiceMinimalGeometryOptInTest`: BEGIN → 최소 Activity → LAUNCH 관측 → STABLE 확립 → 기록 RESET → 실제 고정 metadata 자극 → STABLE 재검증 → 마지막 RESULT → END/종료/flags 정리.
- `MinimalGeometryReader`: 새 테스트 전용 reader. 기존 `OwnedIsolationGeometry`는 개별 recycle 실패 격리와 회전/구성 비교가 충분하지 않아 직접 호출하거나 수정하지 않습니다. 기존 `OwnedLayout`, `IsolationGeometrySample`, `IsolationRect`를 재사용합니다.
- `MinimalGeometryContract`: 서비스 행 검증·출처별 분류·완료 조건과 실제 계측/JVM이 공유하는 관측·안정성 루프.
- `MinimalGeometryContractTest`: 합성 응답/가짜 시계/해제 callback 회귀.
- 이 문서. 새 Activity/권한/운영 hook은 없습니다.

기존 `ObservedGeometryStability`와 `PriorityReleaseScope`를 그대로 사용합니다. 실제 driver의 안정성 호출은 `<MinimalGeometrySample>`을 명시합니다. JVM에서도 raw key 타입을 명시해 Kotlin의 Unit 반환형 추론으로 샘플 값이 버려지지 않도록 합니다.

## LAUNCH

실제 수집 동의/host 단독 허용/캡처 OFF 조건은 기존 signature-gated provider가 검사하며 시험은 이를 변경하지 않습니다. BEGIN을 먼저 수행하고 MinimalDialogActivity를 새로 시작합니다. decor에서 얻은 실제 ID로만 application/기본 display/active 창을 찾으며 root의 host package와 window 소속을 확인합니다. 다른 앱의 root·본문은 읽지 않습니다.

최초 coherent 샘플은 native → remote → remote → native의 일치 관계를 확인합니다. 최초 표시 전 root/window/layout 미준비만 최대3초 내 관측할 수 있고 소유권 불일치·서비스 차단·해제 실패는 재시도로 덮지 않습니다. 첫 coherent 시점은 이미 안정됐을 수 있으므로 LAUNCH를 애니메이션 중이라고 부르지 않습니다. LAUNCH의 서비스 기록은 RESET하지 않습니다.

LAUNCH의 별도 BEFORE/AFTER 샘플은 같은 소유 창/구성을 요구하지만 raw bounds 변화는 보고할 수 있습니다. SAMPLE_VALUES=UNCHANGED/CHANGED는 관계 enum과 별개로 전체 raw 비교 키의 전후 동일성을 나타냅니다. 첫/끝 샘플의 host geometry 분류가 다르면 MIXED입니다. 표본 사이의 모든 변화를 측정하거나 연속적인 초과를 증명하지는 않습니다.

## STABLE

실제 원시 bounds(논리/overlay/Activity max/current/decor/content/자식/원격 window/root), 소유 window, window transition 시각 값, display rotation 및 복사한 Activity/application/overlay Configuration이 안정성 키에 포함됩니다. Configuration은 짧은 테스트 RAM 값이며 문자열 출력하지 않습니다.

- native 각 조회에서 display 전후 크기/rotation과 configuration 전후 동일성, decor/content/자식 attached·laid-out·layout-not-requested, IME 미표시를 확인합니다.
- native/remote 전후 값이 같은 coherent 샘플을 최소3회, 최소1초 동안 확인합니다. 같은 관계 enum이나 시간 경과만으로 통과하지 않습니다. 최대3초 안에 확립하지 못하면 INCONCLUSIVE입니다.
- LAUNCH와 다른 소유 창 또는 회전/구성은 거부합니다. STABLE 확립 후 기존 provider 기록만 RESET하고 고정 합성 contentDescription을 바꿔 실제 이벤트를 유도합니다. detector.clear/서비스 재시작/설정 변경은 없습니다.
- 서비스 관측의 각 조회 전후에 동일 raw anchor를 확인합니다. 관측 후에도 **같은 anchor**로 최소3회/1초 조건을 다시 확립합니다. 다른 값으로 재안정화하여 이전 안정 구간으로 취급하지 않습니다.
- 실제로 관측한 A→B→A에서는 안정 확립 전 B가 기존 A의 횟수·시작 시각을 초기화하고, anchor 확립 후 B는 즉시 실패합니다. 다음 A로 복귀해도 같은 구간을 복구하지 않습니다. 표본 사이에서 발생했다가 사라진 **미관측 ABA는 감지 보장 밖**입니다.
- 모든 후속 검사 다음 마지막 RESULT를 다시 조회하고 전체 행을 검증합니다. 그 뒤에는 추가 host getter로 관측 창을 늘리지 않습니다.

서비스 루프는 최소2초/마감3초/pause200ms입니다. 조회 전후 마감을 검사하므로 마지막 성공 응답 뒤 지연 재개나 늦은 IPC 응답은 고정 timeout으로 실패합니다. 준비·전후 안정성 구간도 각각 최대3초입니다. 기존 운영100ms/노드 예산·세션45초/256기록 제한은 변경하지 않습니다. Binder/getter/main-thread의 비협력적 blocking을 강제 중단하는 보장은 아닙니다.

## 출처·행 검증·분류

HOST_UIAUTOMATION_SEPARATE는 overlay↔logical, Activity max/current↔logical, window↔logical/overlay/Activity max/root, root↔native decor/content 관계를 출력합니다. 고정 EQUAL/WITHIN/CONTAINS/OVERLAPS/DISJOINT와 초과 변 코드만 출력합니다. 빈/역전/미확인 경계는 진단 완료로 승격하지 않습니다.

REAL_SERVICE는 기존 provider가 동일 session/소유 window로 제한한 실제 ATTEMPT 행입니다. ordinal 중복/상한·행 형식·응답 code·overflow를 검사합니다. 같은 행에서 disposition/reason/geometry/extent/relation/초과 변을 검증하며 다른 시도의 geometry로 보충하지 않습니다. 보호 차단·TIME_LIMIT·TARGET_MISSING·미관측·비양수/미확인 geometry는 INCONCLUSIVE입니다.

유효한 VALID 및 UNCERTAIN/INVALID_GEOMETRY/WINDOW_OUTSIDE_FRAME는 모두 **geometry 진단 자료**가 될 수 있습니다. VALID에서 F1 UNCERTAIN이나 후보가 보고되더라도 실제 detector 행 그대로 보존하며 F1 판정을 통과시킨 것으로 해석하지 않습니다. 같은 phase의 VALID/OUTSIDE 혼재는 MIXED입니다.

| 출처별 시작/안정 관측 | 분류 |
|---|---|
| 시작 OUTSIDE, 안정 VALID | LAUNCH_ONLY |
| 안정 OUTSIDE, 시작이 MIXED는 아님 | STABLE_OUTSIDE |
| 시작 VALID, 안정 VALID | STABLE_VALID |
| phase 내 VALID/OUTSIDE 혼재 또는 host 시작/끝 분류 변화 | MIXED |
| phase/출처 미확보 또는 실패 | INCONCLUSIVE |

두 phase의 서비스/host 자료와 마지막 RESULT, 정리가 모두 완료돼야 OBSERVATIONS_COMPLETE입니다. STABLE_OUTSIDE나 MIXED도 관측 완료가 될 수 있으며, 기존 MinimalDialog/F1 시험의 실패를 성공으로 바꾸지 않습니다. native 표본이 서비스와 다르면 출처별 분류를 각각 보존합니다.

서비스에서 읽은 frame의 실제 성분은 기존 provider가 제공하지 않습니다. 따라서 host overlay/logical frame을 동일 ATTEMPT의 서비스 frame이라고 주장하지 않습니다. ATOMIC_BINDING=NONE / SERVICE_FRAME_BINDING=UNAVAILABLE입니다. 최종 RESULT는 해당 조회의 완료 행 스냅샷이며 RESET/END와 원자적이지 않고 진행 중인 수집을 drain하지 않습니다.

## 수명과 안전

node/root/window는 취득한 경계에서 finally로 해제합니다. 기존 `PriorityReleaseScope`를 변경하지 않고 사용하며, 개별 일반 해제 예외를 격리하고 나머지 해제를 시도하며 원래 검사 실패/취소를 보존합니다. 동일 window-list 객체는 identity로 중복 해제를 피합니다. native node lease는 각각 취득/해제합니다.

새 `GeometryReadSafety`는 native/remote 조회를 감싸 최초 **검사 사유와 해제 실패**를 별도 고정 코드로 보존합니다. 원본 예외·메시지·Android 객체를 payload에 보관하지 않습니다. 검사 예외/취소는 그대로 전파하고 finally에서 해제 결과를 기록합니다. `ROOT_UNAVAILABLE`, `REMOTE_UNAVAILABLE`, `LAYOUT_PENDING`은 해제가 모두 성공한 경우에만 초기 재관측을 허용합니다. 해제 실패가 한 번이라도 기록되면 해당 reader의 이후 조회와 전체 완료 판정을 영구 차단하며 close도 기록을 초기화하지 않습니다. 처음 검사가 실패해 기존 scope가 원래 예외를 보존하더라도 동반 해제 실패를 재관측으로 지울 수 없습니다.

실제 `firstCoherent()`는 JVM과 공유하는 `MinimalGeometryPolling.firstCoherent()`를 호출합니다. 재관측 분기와 조회 전후에 위 기록을 확인하고, 조회 뒤 3초 마감이 지났다면 성공 샘플도 반환하지 않습니다. STABLE 조회도 같은 reader를 사용합니다. 실패 출력은 `F1_MINIMAL_GEOMETRY_READ_FAILURE;INSPECTION=고정코드;RELEASE=고정코드;RESULT=INCONCLUSIVE`뿐입니다. 이 동기화는 내부 코드 기록의 가시성만 보장하며 Android 화면·샘플의 원자성을 보장하지 않습니다.

실패·취소에도 NonCancellable에서 END, Activity 종료, UiAutomation flags 복구를 각각 시도합니다. 종료 요청 후 최대3초 내 isDestroyed를 main에서 확인하고 flags를 재조회해 원래 값과 일치하는지도 확인합니다. 정리 후 ensureActive로 Job 취소도 다시 확인합니다. 어느 정리 실패도 관측 완료로 처리하지 않습니다. 취소는 고정 메시지로 전파하며 예외 원문/stack/cause를 출력하지 않습니다. 취소 확인과 최종 JUnit assertion은 원자적이지 않으며 VM 치명적 오류·프로세스 종료·비협력적 blocking에서 완전한 정리/강제 중단은 보장하지 않습니다.

원시 값·실제 ID·timestamp·configuration은 bounded RAM에서만 비교하고 toString은 redacted입니다. 출력은 고정 단계·출처·관계·문제 코드와 세션 내부 attempt 순번뿐입니다. 이미지/원문/좌표/토큰/지문/사용자 앱 식별자를 파일·DB·로그에 기록하지 않습니다. 캡처/OCR/접근성 클릭·제스처를 요청하지 않습니다.

## Windows API37

개인정보 없는 전용 에뮬레이터에서 수동으로 서비스 연결·수집 동의·host 단독 허용·분석 시작을 확인합니다. 캡처 동의는 OFF, 캡처 허용 목록은 비어 있어야 합니다. adb로 동의를 강제하지 않습니다.

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest :app:lintDebug :capture-test-host:lintDebug
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "emulator-5554" # 실제 API37 대상 확인
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceMinimalGeometryOptInTest -e f1MinimalGeometry true -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

확인할 출력: SERVICE_CONNECTION_READY, LAUNCH/STABLE의 별도 SAMPLE과 실제 ATTEMPT/SERVICE, SUMMARY. OBSERVATIONS_COMPLETE/OK는 관측 자료 확보이며 WINDOW_OUTSIDE_FRAME 원인 확정이나 F1 성공이 아닙니다. 미관측/불안정/잘못된 행/정리 실패는 INCONCLUSIVE/JUnit 실패입니다.

## 검증 상태와 남은 위험

P2/P3 보강 전 검증 기록은 신규 JVM28개를 포함한 전체562개(앱396/호스트166), 실패·오류·skip0, 앱/호스트 Debug·AndroidTest APK 및 양쪽 Lint 이슈0입니다. 이 기록은 이전 구현의 결과이며 아래 보강의 최종 검증과 구분합니다.

이번 보강은 JVM15개를 추가했습니다. 실제 초기 재관측 루프에 연결해 검사+해제 실패의 복구 금지, 해제만 실패한 경우, 정상 해제된 초기 미관측의 재관측, 검사/취소와 여러 해제 실패의 독립 해제, 최초 코드 유지, 예외 원문 비보존, 마감 후 성공 샘플 거부를 검증합니다. 기존 안정성 gate 및 실제 공유 안정성 루프의 관측 A→B→A도 검증합니다. JVM 해제 회귀는 합성 callback이며 실제 Android framework의 recycle 예외를 재현한 것이 아닙니다.

보강 후 대상43개 및 전체577개(앱396/호스트181)를 실제 재실행해 실패·오류·skip0입니다. 전체 Gradle 명령은 BUILD SUCCESSFUL(48초), 앱/호스트 Debug·AndroidTest APK와 양쪽 Lint(이슈0) 통과이며 경고 출력은 없습니다. 기존 패키징 검사와 추가 실제 DEX 정의 검사에서 새 reader/루프/해제 기록/진단 클래스가 AndroidTest driver에만 있고 운영 APK/호스트 APK에는 없음을 확인했습니다. 기존 추적 파일176개는 보강 작업 시작 시 해시와 동일합니다. git diff --check 및 미추적 신규5개 파일 whitespace 검사 통과입니다. Release/CI는 이번에 실행하지 않았습니다.

실제 Android 계측은 이 환경에서 실행하지 않았습니다(adb 연결 기기 없음). API37의 공개 API 관계·실제 서비스 이벤트 발생·안정성/해제/수명·실제 Job 취소는 기기 실행 전 미검증입니다. JVM 취소 회귀는 공유 루프의 합성 callback이 던진 CancellationException 전파를 검사합니다.

새 운영 권한이나 보호 우회는 없습니다. 여전히 남는 P1은 전체 F0 coverage, 검사 값·토큰·지문의 동일 시도 결합, 세션·현재성·TTL입니다. P2 한계는 관측 출처/최종 RESULT↔END 비원자성과 기존 운영 collector 정리 예외입니다. P3 한계는 제한된 안정 표본을 연속 상태나 원인 확정으로 일반화할 수 없다는 것입니다. 원본14개 UNOBSERVED=14, Scroll 역전 Rect와 Window/frame 문제는 미해결이며 F0 완전성·F1 정확도·OCR·행동 안전성은 입증되지 않습니다.
