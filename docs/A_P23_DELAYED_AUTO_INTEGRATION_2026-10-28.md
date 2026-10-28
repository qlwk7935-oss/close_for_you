# P23 지연 팝업: 실제 서비스 통합 경계와 삼성 재시험

## 작업 기준과 보존

- 기준 HEAD: `0af402b076e87092ec417a38a5fc6d41edd27e58`.
- 별도 브랜치: `fix/a-p23-delayed-auto-integration-20261028`.
- worktree: `/workspace/a-p23-delayed-auto-integration`.
- 중단 당시의 미커밋 15개 파일과 신규 회귀 20개가 실제로 남아 있음을 확인하고 이어서 검증했다. 처음부터 재구현하거나 초기화하지 않았다.
- `/workspace/a-p23-preserve.json`은 다른 worktree의 HEAD/status/파일 해시 비교 기록이다. 소스 백업이나 유실 파일 복원 근거로 사용하지 않는다.
- 기존 9개 worktree, 특히 `/workspace/a-samsung-p01`의 미커밋 11개 파일과 지연 광고 원본 브랜치는 별도로 보존한다. 이 브랜치에 P01 수명/TTS 수정본을 혼합하지 않는다.
- 커밋·스테이징·푸시·PR 생성/수정·병합은 하지 않는다. 이 문서를 포함한 변경은 16개다.

## 사용자 실측으로 확인된 사실

삼성폰에서 APK 설치, 접근성 서비스 Bound, 합성 앱 허용, 개발 시험 활성화를 확인했다. 제공된 최신 RUN에서는 `ARMED`가 있었고 `PAGE_VERIFIED`는 없었다. `Status: ok`, `LaunchState: COLD`, 대상 Activity의 top-resumed 상태는 Activity 실행을 확인할 뿐 F0/F9 검증 성공을 뜻하지 않는다.

다른 실행에서 `CANDIDATE` 뒤 `B_DECISION;NEXT=ASK;REASON=UNVERIFIED_TARGET`가 있었다. 과거 수동 팝업에는 `ORIGINAL_EVIDENCE_EXPIRED`가 있었다. 서로 다른 RUN과 수동/자동 흐름을 같은 원인으로 결합하지 않는다. 실제 자동 클릭 및 `CLOSED_CONFIRMED`는 미확인이다.

`--ez f9_defer_popup true`는 P23 Dialog를 **보류하고 배경 페이지만 시작**한다. `onNewIntent()`에 `f9_show_deferred_popup=true`가 전달되어야 기존 실제 Dialog가 표시되고 3초 후 native Button이 추가된다. 첫 명령만 성공한 것을 팝업 생성·버튼 등장으로 해석하지 않는다.

## 실제 호출 경로와 거부 경계

1. `CloseForYouAccessibilityService.onAccessibilityEvent`는 기존 관측 이벤트만 받고 overlay cleanup 이벤트를 처리한다. C 소유 이벤트 제외 판정, 사용자 조작 무효화, 동의/일시정지, 이벤트 package quick exclusion을 그대로 거친다. 이벤트 package는 화면 읽기 허가가 아니다.
2. `scheduleCapture`의 기존 180ms debounce 동안 새 이벤트는 기존 작업을 취소한다. cleanup permit·서비스·현재 screen version·사용자 조작 여부를 확인한 뒤 수집한다. 폴링/자동 재시도는 추가하지 않았다.
3. `AndroidSnapshotCollector.capture`의 기존 두 pass, 보호 검사, geometry 거부 및 수집 예산을 거친다. `Blocked`이면 B/F9로 실행 증거를 보내지 않는다.
4. `Ready`에서 `F9ServiceConnection.observePage(snapshot, diagnostic, checkpoint)`가 **실제 F0 결과**를 받는다. `F9ServiceSession.observePage`는 기존 `AndroidF9ScreenPort.read` 결과를 같은 source와 대조한다.
5. F9 reader는 기본 display, active/focused APPLICATION 창, 창 개수/배경 관계, overlay/IME, frame 포함, 가림, root 소유권, 전체 보호 트리, 종료 시 창/frame/root 및 100ms 예산을 확인한다. 실패한 stage는 고정 코드로만 보고한다. 시스템 바 예외를 넓히지 않는다.
6. 일반 페이지 proof를 만들려면 두 읽기의 package/window/frame/windows/nodes가 일치하고, 두 결과 모두 complete이며, source epoch·원래 5초 TTL이 유효해야 한다. target은 active/focused APPLICATION이며 **창 bounds가 전체 logical frame과 정확히 같아야 한다**. 자기 pageWindowId 및 비어 있지 않은 pageDigest도 필요하다.
7. 이어서 기존 `PopupCandidateDetector.detect`를 실행한다. WAIT 중인 팝업은 `DelayedPopupWatch.reobserved`의 새 전체 트리/identity 검사 뒤 기존 제한된 bounded-window 재관측 경로를 사용한다. 원래 F1 14개와 일반 detector 정답을 변경하지 않는다.
8. 실제 후보의 evidence pipeline이 새 보호·현재성 검사를 완료해야 `F9ServiceConnection.collected`에 도달한다. pipeline 차단·취소·오류는 별도 경계이며 B 거절로 부르지 않는다.
9. `F9CollectedSource`는 원래의 token/epoch/complete/5초/결합 텍스트 보호를 검사하고 private Candidate를 발행한다. 실제 `DecisionCandidateWiring.source` 구독은 최초 발행 전에 준비된다. 발행 승인과 worker 소비·B 결정은 별개의 경계다.
10. `DecisionCandidateWiring → F9EligibilityAdapter → B policy`는 새 전체 트리의 분류·버튼·안전성을 판단한다. 최초 버튼이 없을 때 B가 실제 `WAIT / WAITING_FOR_TARGET`를 반환하고 추가 A identity/frame/page 검증도 통과해야 watch를 시작한다. WAIT는 실행 승인이 아니다.
11. 같은 팝업의 새 콘텐츠/순수 bounds 이벤트는 기존 token/작업을 무효화한 뒤 새 수집만 요청한다. 터치·스크롤·포커스·다른 앱/창·정책 변경은 기존대로 취소한다. 같은 원래 노드/경로/문구/속성을 유지하고, 기존 부모 끝에 추가된 라벨 있는 native Button 하나만 자동 대상이 될 수 있다. 누락/교체/여러 신규 노드를 허용하지 않는다.
12. 재수집한 현재 token으로 B가 PRESET/EXECUTE를 승인하고 다섯 AutoEligibility 검사, 설정, cleanup, wait 연속성, 원래 5초 TTL을 통과해야 `F9AutoCloser`가 전체 화면/대상을 다시 읽고 **ACTION_CLICK 최대 1회** 호출한다. 좌표 클릭·fallback·반복 클릭은 없다.
13. 클릭 반환값과 닫힘은 다르다. 실제 관측에서 같은 앱의 변경되지 않은 배경 페이지로 복귀하고 popup 창이 사라져야 `CLOSED_CONFIRMED`다. 관측 부족은 UNKNOWN, 남아 있으면 STILL_PRESENT이며 성공 안내·재시도를 하지 않는다.

## 코드로 확인한 문제와 수정

### 페이지 증거와 F0 결과의 연결 누락

수정 전 `observePage()`는 앞선 F0 source를 받지 않고 독립 F9 읽기만으로 page proof를 만들었다. 이제 실제 source의 창·frame·노드와 일치해야 한다. 읽기 전후 현재성 checkpoint를 호출하고, proof 시각은 source의 실제 F0 수집 시각을 그대로 사용한다. 새 시각/토큰으로 과거 화면을 위장하지 않는다.

이 수정은 증거 결합을 강화한다. 삼성폰 PAGE 미발생의 직접 원인으로 확인한 것은 아니며, 잘못된 두 읽기가 우연히 각자 성공한 것을 한 페이지 증거로 사용할 수 없게 한다. 별도 수집의 일치가 Android 원자성을 뜻하지 않는다.

### 만료된 숨김 배경 proof의 WAIT 진입

기존 F9 클릭 직전 검사는 독립 배경 proof의 원래 5초를 검사했지만, watch의 fallback pageKey는 그 나이를 검사하지 않았다. 이제 WAIT 연속성/실행 guard도 같은 원래 5초를 거부한다. 이전 코드가 실제 클릭을 허용했다는 뜻은 아니다. 최종 F9가 거부할 증거를 watch에서 먼저 차단하는 수정이다.

### 실제 발행 전 CANDIDATE 기록

수정 전 CANDIDATE를 기록한 뒤 private source가 조용히 발행을 거부할 수 있었다. `publish()`가 실제 승인 여부를 반환하고, 거부 시 `PUBLICATION_REJECTED`를 기록하며 그 offer만 폐기한다. 기존 source 보호/거부 조건은 동일하다. `CANDIDATE`는 source가 받아들였다는 뜻이며 SharedFlow 소비·B 승인·클릭 성공을 뜻하지 않는다.

### 보이지 않던 F9 reader 거부

서비스용 reader 생성 경로에 기존 `onRejected` callback을 연결했다. 예산 초과는 TIME_LIMIT, 해제 실패는 CLEANUP으로 구분한다. 타이머·예산·실패/취소/정리 동작을 완화하지 않았다. Debug 기록 실패는 기존 판단을 바꾸지 않는다.

## PAGE_VERIFIED가 없을 수 있는 원인: 증거 수준

| 경계 | 코드상 가능한 거부 | 이번 삼성 RUN에서 확정됐는가 |
| --- | --- | --- |
| 이벤트/스케줄 | 해당 이벤트 미수신, package/설정 거부, debounce 취소, cleanup permit 불가 | 아니오 |
| F0 | 보호·노드·창·geometry·예산 차단 | 아니오 |
| F9 reader | GEOMETRY/WINDOWS/ACTIVE_WINDOW/FOCUSED_WINDOW/OVERLAY_WINDOW/APP_COUNT/BACKGROUND_WINDOW/WINDOW_BOUNDS/OCCLUSION/ROOT/TREE/ENDPOINT/TIME_LIMIT/CLEANUP | 아니오 |
| page admission | source 불완전/만료/두 읽기 불일치, NOT_FULL_PAGE, pageWindowId/digest 불확실 | 아니오 |
| P23 표시 자극 | defer 명령 뒤 별도 show 명령이 없으면 Dialog 미생성 | 코드 동작은 확정, 제공 RUN에서 show 전달 여부는 미확인 |
| 후속 WAIT | B 거절, 배경 proof 부재·만료, 새 노드 구조 불일치, 조작/포커스 이벤트로 취소 | 최신 RUN에서 B 도달 여부부터 미확인 |

삼성의 일반 Activity가 시스템 바를 제외한 창 bounds를 보고한다면 `NOT_FULL_PAGE`가 될 수 있다. **가설**이며 이번 로그로 확정할 수 없다. 이 엄격한 capability 제한을 제거하면 inset Dialog를 배경으로 오인할 위험이 있어 그대로 거부한다. frame 확대/clamp/허용 오차/창 ID·패키지 예외를 도입하지 않는다. 실제로 이 코드가 보고되면 이 시험 구성은 현재 page-proof 계약을 만족하지 않는 것으로 기록한다.

## Debug 진단 해석

태그 `A_DelayedAuto`, 기존 `A_DELAYED_AUTO;RUN=...`를 유지한다. 추가 출력 형식은 다음과 같다. 아래는 **형식 예시이며 실제 삼성 로그가 아니다**.

```text
A_DELAYED_TRACE;RUN=3;ATTEMPT=11;PHASE=PAGE_READ_REJECTED;EVENT=NONE;READ=FOCUSED_WINDOW;PAGE=NONE;BLOCK=NONE;NEXT=NONE;REASON=NONE
A_DELAYED_TRACE;RUN=3;ATTEMPT=12;PHASE=PAGE_READ_REJECTED;EVENT=NONE;READ=NONE;PAGE=NOT_FULL_PAGE;BLOCK=NONE;NEXT=NONE;REASON=NONE
```

- RUN은 명시적으로 활성화한 개발 시험, ATTEMPT는 그 서비스 수집 경로의 로컬 진단 번호다. 화면 ID·provider ATTEMPT·시간 정보가 아니다. 늦은 작업은 캡처한 원래 RUN으로 기록한다. 번호가 다른 수집은 동일 화면/동일 시점으로 결합하지 않는다.
- 같은 trace의 각 PHASE는 최대 한 번 출력한다. 저장은 유한 enum 집합과 Debug sink뿐이며 Android 객체·화면 원문·실제 package/window ID/좌표/토큰/지문/예외를 담지 않는다. 진단용 추가 화면 수집·OCR·IPC·폴링을 넣지 않는다.
- EVENT_RECEIVED/IGNORED/REJECTED, CAPTURE_SCHEDULED/CANCELLED/REJECTED/BLOCKED, F0_READY, PAGE_READ_STARTED/REJECTED/VERIFIED, F1_NOT_CANDIDATE/POPUP_DETECTED, EVIDENCE_READY/BLOCKED/CANCELLED/FAILED, CANDIDATE/PUBLICATION_REJECTED/CANDIDATE_REJECTED, B_DECISION, WAITING_FOR_TARGET, REOBSERVED, F9_RESULT를 구분한다.
- READ는 reader가 중단된 검사 단계다. 해당 단계의 모든 세부 원인을 구분하는 것은 아니다. active worker의 화면 읽기 거부는 SCREEN_READ_REJECTED이며 배경 page admission과 구분한다. 정리 실패가 있으면 CLEANUP이 우선 보고된다.
- PAGE는 SESSION_UNAVAILABLE/READ_FAILED/INCOMPLETE/STALE/PACKAGE_MISMATCH/WINDOW_MISMATCH/FRAME_MISMATCH/WINDOW_SET_MISMATCH/NODES_MISMATCH/WINDOW_UNCONFIRMED/NOT_FULL_PAGE/BACKGROUND_UNCONFIRMED/VERIFIED다. reader 자체가 먼저 실패하면 그 READ 행이 우선이다.
- NEXT와 REASON은 실제 B 결과다. REASON은 첫 사유 한 개이며 전체 reasons 집합이 아니다. WAITING_FOR_TARGET 행은 실제 B WAIT 이후 A watch까지 승인된 경계다.
- source의 CANDIDATE 승인 뒤에도 이벤트 무효화·conflation·worker 거부가 가능하다. B_DECISION이 없으면 B가 처리했다고 주장하지 않는다. 이벤트 출처나 시간 인과관계를 인증하는 진단이 아니다.
- 서비스가 연결되지 않았거나 관측 대상 이벤트가 아니거나 개발 티켓이 없으면 새 trace가 없다. coroutine 본문 진입 전 취소, 프로세스 종료, 이미 폐기된 offer 등 모든 조용한 종료를 각각 증명하는 로거는 아니다. 로그 부재를 성공 또는 특정 원인으로 해석하지 않는다.
- Release factory는 null을 반환하며 logger/활성화 API는 없다. 두 variant의 안전 판단 조건을 로그 유무로 바꾸지 않는다.

## 삼성폰 PowerShell 재시험

아래는 **이 worktree의 수정본이 적용된 Windows 소스**에서 실행한다. 현재 수정은 미커밋/미푸시이므로 원격 원본 브랜치 checkout만으로는 적용되지 않는다. 다른 팀의 #59 재현 APK를 이 브랜치 APK로 덮으면 P31 등이 사라질 수 있으므로 APK 버전을 구분한다. 이 기준에는 #59 P31이 없다.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
$serial = "<삼성 테스트폰 serial>"
.\gradlew.bat --no-daemon :app:assembleDebug :popup-test-app:assembleDebug :app:assembleDebugAndroidTest
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial install -r .\popup-test-app\build\outputs\apk\debug\popup-test-app-debug.apk
# opt-in 계측을 실행할 때만 설치
& $adb -s $serial install -r .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
```

폰 UI에서 접근성 서비스, 수집 동의, 시험 앱 허용, 분석 시작을 확인한다. C의 온보딩 완료·광고/쿠폰 프리셋·자동 닫기 스위치에 명시적으로 동의한다. 이 개발 경로는 Debug의 **정확한 합성 package**에만 실제 클릭 가능성이 있으므로 일상 앱 화면에서 시험하지 않는다. 캡처/OCR 동의·마이크·자동 SKIP·F12 반복 실행을 켤 필요가 없다. adb secure settings/동의 DB 쓰기로 우회하지 않는다.

먼저 별도 PowerShell 창에서 같은 `$adb`, `$serial`을 설정하고, 고정 코드 태그만 실시간 확인한다. 원문 포함 전체 logcat/screenshot/UI dump는 수집하지 않는다. 이전 RUN과 혼동하지 않도록 새로 ARMED된 RUN을 확인한다.

```powershell
& $adb -s $serial logcat -v brief -s A_DelayedAuto:I A_DelayedFixture:I A_ManualPrompt:I '*:S'
```

P23 두 단계 실행:

```powershell
& $adb -s $serial shell am start -n kr.closeforyou/.accessibility.testing.DelayedAutoCloseActivity
# 폰에서 실제 '켜기'와 확인 창을 누른다. 이 명령 자체는 실행 동의를 만들지 않는다.
& $adb -s $serial shell am start -W -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P23 --ez f9_defer_popup true
# 같은 RUN의 실제 PAGE_VERIFIED가 확인된 경우에만 즉시 다음 명령.
& $adb -s $serial shell am start -W -f 0x20000000 -n kr.closeforyou.popuptest/.PopupScenarioActivity --ez f9_show_deferred_popup true
```

PAGE_VERIFIED가 없으면 show를 진행하거나 반복 실행하여 통과시키지 말고 같은 RUN의 마지막 TRACE 경계를 기록한다. F0 차단과 F9 page 거부를 구분한다. PAGE_VERIFIED 뒤 너무 오래 기다리면 배경 proof의 원래 5초가 끝나며 이때 자동 클릭 0회가 정상이다. 표시 뒤 버튼 등장까지도 3초가 필요하므로 사람이 명령을 전달하는 지연으로 만료될 수 있다. TTL을 늘리거나 과거 proof를 갱신하지 않는다.

사람의 기존 설정을 검사한 뒤 개발 UI만 조작하고 page 관측 직후 show 명령을 보내는 기존 opt-in 계측도 사용할 수 있다. 원본 닫기 버튼은 계측이 직접 누르지 않고 실제 F9만 누른다. 첫 단독 시험 결과를 기록한 뒤 별도 시험을 진행하며 자동 반복하지 않는다.

```powershell
& $adb -s $serial shell am instrument -w -e delayedAutoSynthetic true -e class kr.closeforyou.accessibility.integration.DelayedAutoServiceInstrumentedTest#p23RealDelayedCouponIsPressedOnceAndClosureIsObserved kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner
# 별도의 위험 구매 문구 없는 합성 지연 광고. P23/P31의 성공을 대체하지 않는다.
& $adb -s $serial shell am instrument -w -e delayedAutoSynthetic true -e class kr.closeforyou.accessibility.integration.DelayedAutoServiceInstrumentedTest#independentSafeDelayedAdIsPressedOnceAndClosureIsObserved kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner
```

기대 순서는 `ARMED → PAGE_VERIFIED → CANDIDATE → B_DECISION(WAIT) → WAITING_FOR_TARGET → RECOLLECT_REQUESTED → REOBSERVED → B_DECISION(EXECUTE) → F9_RESULT(CLOSED_CONFIRMED)`다. **실측 결과가 아니라 기대값**이다. 버튼 등장 전 클릭 0회, 승인된 버튼 등장 뒤 최대 1회, 실제 원본 팝업 소멸과 최종 닫힘 관측을 모두 확인한다. `ATTEMPTED=YES`나 `CLICK=true`만으로 성공 처리하지 않는다. C 선택창만 사라진 것도 원본 팝업 닫힘이 아니다.

외부 앱 전환·터치·스크롤·포커스 탐색·팝업 교체·동의 철회·배경/현재 증거 만료에서는 클릭 0회를 확인한다. TalkBack의 포커스를 자동 포커스로 추측하여 무시하지 않는다. 종료/거부 뒤 재시험은 사람의 명시적 개발 UI 재활성화가 필요하며 F9/B 중복 이력을 임의로 지우지 않는다. 종료하려면 아래 Activity에서 '끄기'를 누른다.

```powershell
& $adb -s $serial shell am start -n kr.closeforyou/.accessibility.testing.DelayedAutoCloseActivity
```

## JVM·빌드·격리 검증

신규 공유 회귀 20개는 `F9PageAdmissionTest` 11개, `DelayedAutoTraceTest` 5개, 기존 `DelayedAutoFlowTest`에 추가한 4개다. 실제 session/admission/source/watch/trace 함수를 사용하며 B wiring/classifier/policy와 F9 coordinator도 재사용한다. 가짜 시계·Android port는 JVM에만 있다. Android framework getter/recycle/이벤트 전달/실제 ACTION_CLICK의 성공을 JVM으로 입증하지 않는다.

보존한 기존 회귀는 버튼 등장 전 0회, 새 F0 뒤 한 번, 원본 popup 유지 시 STILL_PRESENT, 변경/다른 앱/사용자 조작/위험 문구/미동의/cleanup/TTL의 거부, Release OFF와 수동 P01 계열 경로를 포함한다. 새 회귀는 정상 page admission, 두 읽기 불일치, epoch/시간/취소, 숨김 배경 proof 부재/만료, source 발행 거부와 진단 격리를 추가한다. 초기 신규 fixture의 Main dispatcher 미설정으로 발생한 11건은 fixture를 명시적 JVM dispatcher로 수정한 뒤 재검증했다. 테스트 정답이나 운영 차단 조건을 바꾸지 않았다.

최종 실행 수치·Lint·APK/DEX 격리·보존 해시 결과는 다음과 같다. 기존 0af402b의 2,154건이나 중단 전의 대상 39건을 이번 전체 실행으로 재표기하지 않는다.

| 이번 재개 후 실제 로컬 검증 | 결과 |
| --- | --- |
| 앱 Debug JVM | 1,066 통과 |
| 앱 Release JVM | 1,047 통과 |
| 테스트 호스트 Debug JVM | 55 통과 |
| 합성 팝업 앱 Debug JVM | 26 통과 |
| JVM 실행 합계 | **2,194건 / 실패·오류·skip 0** |
| 신규 공유 회귀 | 20개 메서드, Debug/Release에서 40건 실행 |
| 앱 Debug/Release APK | 통과 |
| 앱 AndroidTest 및 의존 호스트 Debug/AndroidTest APK | 통과 |
| 합성 팝업 앱 Debug APK | 통과 |
| 앱 Debug/Release·호스트 Debug Lint | 이슈 0 |
| 합성 팝업 앱 Debug Lint | 오류 0, 기존 TypographyFractions/SetTextI18n 경고 2 |
| 기존 APK 패키징·DEX 격리 | 통과 |
| 새 로그 및 개발 Activity의 Release DEX 제외 | 통과 |
| 중단 당시 P23 수정 15개 | 재개 시점 해시와 모두 동일, 추가 소스 수정 없음 |
| 다른 9개 worktree | 보존 기록의 HEAD/status/기존 미커밋 파일 해시 일치 |

JVM 실행 합계는 Debug/Release 공통 회귀의 중복 실행을 포함한다. 캐시 결과만 확인한 것이 아니라 아래 두 번째 명령에서 `--rerun-tasks`로 4개 JVM task를 모두 실제 실행했다. XML 결과도 대조했다. Android 계측은 컴파일/패키징만 검증했으며 기기에서 실행하지 않았다.

```bash
JAVA_HOME=/workspace/jdk17 ANDROID_HOME=/workspace/android-sdk ./gradlew --offline --no-daemon --max-workers=1 \
  :app:testDebugUnitTest :app:testReleaseUnitTest :capture-test-host:testDebugUnitTest :popup-test-app:testDebugUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :popup-test-app:assembleDebug \
  :app:lintDebug :app:lintRelease :capture-test-host:lintDebug :popup-test-app:lintDebug

JAVA_HOME=/workspace/jdk17 ANDROID_HOME=/workspace/android-sdk ./gradlew --offline --no-daemon --max-workers=1 --rerun-tasks \
  :app:testDebugUnitTest :app:testReleaseUnitTest :capture-test-host:testDebugUnitTest :popup-test-app:testDebugUnitTest

python3 tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff --check
```

첫 전체 명령은 BUILD SUCCESSFUL(3분 37초), JVM 강제 재실행도 BUILD SUCCESSFUL(2분 12초)이다. 앱/호스트 계측 APK 내 host·target-app·Kotlin runtime 격리 검사를 통과했다. APK DEX를 직접 조회하여 `A_DELAYED_AUTO;`, `A_DELAYED_TRACE;`, `A_DelayedAuto` 문자열과 개발 활성화 Activity가 Debug에 존재하고 Release에는 없음을 확인했다. Release factory의 null 반환 및 arm API 부재 회귀도 통과했다.

기존 CRLF 소스 3개는 줄바꿈을 그대로 보존했다. 기본 `git diff --check`는 이 파일의 새 diff 줄에 CRLF 줄 끝 알림 77건을 내지만, CRLF를 인식하는 위 명령 및 변경/신규 파일 전체의 실제 후행 공백 검사는 통과했다. 소스 전체 재포맷이나 Git 설정 변경은 하지 않았다. 기존 AutoDecisionSpeech의 Kotlin 경고와 native symbol strip 관련 빌드 경고를 새로 해결했다고 주장하지 않는다.

재개 후에는 기존 코드/테스트를 변경할 새로운 근거가 발견되지 않아 기존 15개 파일의 바이트를 그대로 보존하고 이 문서만 추가했다. `adb devices -l` 결과는 연결 기기 없음이다. 삼성폰·LG폰·에뮬레이터 시험은 이번 환경에서 **미실행**이며 실제 클릭도 실행하지 않았다.

## 변경 파일

기존 미커밋 15개를 보존하고 이 문서 1개를 추가한 총 16개다.

- `app/src/androidTest/java/kr/closeforyou/accessibility/action/F9SyntheticClickInstrumentedTest.kt`
- `app/src/debug/java/kr/closeforyou/accessibility/integration/DebugDelayedAutoClose.kt`
- `app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt`
- `app/src/main/java/kr/closeforyou/accessibility/action/AndroidF9ScreenPort.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/DelayedPopupWatch.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/F9CollectedSource.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceConnection.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceSession.kt`
- `app/src/release/java/kr/closeforyou/accessibility/integration/DebugDelayedAutoClose.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/DelayedAutoFlowTest.kt`
- `app/src/testRelease/java/kr/closeforyou/accessibility/integration/DelayedAutoReleaseTest.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/DelayedAutoTrace.kt` (신규)
- `app/src/main/java/kr/closeforyou/accessibility/integration/F9PageAdmission.kt` (신규)
- `app/src/test/java/kr/closeforyou/accessibility/integration/DelayedAutoTraceTest.kt` (신규)
- `app/src/test/java/kr/closeforyou/accessibility/integration/F9PageAdmissionTest.kt` (신규)
- `docs/A_P23_DELAYED_AUTO_INTEGRATION_2026-10-28.md` (신규)

AndroidTest harness의 변경은 새 session 인자에 맞추어 독립 Android page read로 시험 descriptor를 전달하는 것이다. 이 fixture가 운영 collector의 F0 성공 증거인 것처럼 사용하지 않는다. A 소유 harness 이외의 D 합성 앱·B/C 정책·공통 DTO·Manifest·gradle.properties·collector·원본 14개·P01 worktree는 수정하지 않았다.

## 남은 한계와 완료 판정

실행 가능한 개발 경로는 Debug의 한 시험에 한하며, Release 자동 닫기·운영 F9 스위치·자동 SKIP·F12 자동 반복은 계속 OFF다. 실제 실행은 기존 B PRESET 승인과 F9 전수 재검사를 통과해야 한다. 민감·구매·결제·동의·권한·사기·라벨 없는 X는 우회하지 않는다.

PAGE_VERIFIED 미발생의 삼성 직접 원인, 실제 버튼 추가 이벤트의 전달, 전체 노드 경로 안정성, 5초 안의 숨김 배경 proof 사용, TalkBack 및 실제 popup 소멸 관측은 아직 실기기에서 미검증이다. Android/Binder의 비협력적 호출과 수집 간 미관측 변화에 대한 원자성 보장은 없다. 이 진단의 비용이 0이라는 주장도 하지 않는다.

원본 F1 14개 UNOBSERVED 및 기존 Scroll/Window 문제는 해결했다고 주장하지 않는다. 실제 기기에서 `CLOSED_CONFIRMED`와 올바른 원본 팝업 소멸을 확인하기 전에는 **P23 실제 자동 닫기 기능 완료 판정을 보류**한다.
