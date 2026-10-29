# P01: LIVE_SET_UNCONFIRMED와 retired WINDOW_SET 진단

## 사용자 실측과 결론

작업 경로 `/workspace/a-samsung-p01`, 브랜치 `fix/a-samsung-p01-lifetime-20261028`, 기준 `92a8ee758cc674e0d0b1fb94b42842012db1444d`. 시작 worktree는 clean이었다. 다른 worktree/P23는 수정하지 않는다. 커밋·푸시하지 않는다.

사용자 제공 10/30 02:05:57 삼성 로그:

- SHOWN 뒤 원본 대상 창의 BOUNDS 알림에서 표시 재검증이 `NATIVE_BEFORE / LIVE_SET_UNCONFIRMED`로 끝났다.
- 실패한 재검증은 기존 fail-closed 규칙대로 C 표시와 음성을 제거했다.
- native cleanup 중 STATE가 WAIT_ONLY로 분류된 뒤, WINDOW_SET은 EVENT_TYPE에서 거절됐다.
- 첫 barrier 실패는 `RETIRED_ID_EVENT`, 그 순간 진행 상태는 `WAITING_WINDOW_ABSENCE`였다. 뒤의 HEALTH 제한은 이를 무시하거나 초기화하지 않는다.

**확정:** 표시 제거의 직접 분기와 첫 cleanup 실패 분기는 위와 같다. `EVENT_TYPE`은 당시 WINDOW_SET 마스크가 기존 transient whitelist에 속하지 않았음을 뜻한다. 다만 정확한 마스크, owned query의 null/빈 집합/복수/다른 단일 ID는 기존 로그에 없다. `WAITING_WINDOW_ABSENCE`는 이전 검사에서 기존 native removalComplete(동일 native 제거 반환 + detach), barrier 제거 반환 및 HIDDEN이 통과했지만 전체 창 목록의 presence가 PRESENT여서 대기했음을 뜻한다. 이는 실패 이벤트 시점에도 native 창이 존재한다는 새 관측이 아니며, native 제거 자체의 실패라고 단정할 근거도 아니다.

**미확정:** native 등록 직후 접근성 창 목록/루트/title 반영의 지연, 다른 overlay, root 조회/해제 실패, ID 불일치 중 무엇인지. native의 등록과 `service.windows` 표본은 원자적으로 결합되지 않는다. 이벤트가 C에서 유래했는지도 ID 또는 package만으로 확정하지 않는다. 이번에는 이를 우회하는 기능 수정 없이 기존 비교 결과의 진단만 보강했다.

## 실제 호출 순서

서비스 `onAccessibilityEvent`:

1. `compatibleRetirementEvent()` — 명시적 선택 의도 보존 여부
2. 위가 false이면 `nativeCleanupRetirementEvent()` — cleanup 대기 전용 분류
3. `observeEvent()` → `OverlayCleanupBarrier.observeWindowEvent()` → `observe()`
4. 원래 `handleAccessibilityEvent()` → `shouldIgnore()` → 필요한 경우 표시 재검증/일반 무효화
5. handler가 정상 반환했을 때만 `processed(receipt)`; 아니면 원래 cleanup 실패 처리

WAIT_ONLY는 4번을 생략하지 않는다. 원래 토큰·실행 증거는 무효화된다. WAIT_ONLY나 `REMOVAL_RECEIPT`는 실행 허가나 제거 완료가 아니다.

표시 재검증의 native 확인은 기존 순서 그대로다:

`manualDisplayOwnedId`: 현재 native ID → 저장된 before 집합에서 부재 → 기존 `readOwned()` 한 번 → 유일한 소유 후보 ID가 native ID와 같은지.

`readOwnedOverlayIds`: 기존 service.windows 조회 → overlay/default display 필터 → root → root package/window 관계 → 합성 pane title → ID 집합 → root/window 각각 독립 해제. 최초 실패와 해당 query의 read/release 실패만 고정 enum/boolean으로 기록하며 Android 객체·ID·문자열·예외를 진단 객체에 보관하지 않는다.

## 추가 Debug 필드

태그는 기존 `A_ManualPrompt`. 예시는 실제 삼성 결과가 아닌 **형식 예시**다.

```text
A_MANUAL_DISPLAY_REVIEW;ATTEMPT=1;EVENT=WINDOW_SET;MASK=BOUNDS;RESULT=UNAVAILABLE;POINT=NATIVE_BEFORE;DETAIL=LIVE_SET_UNCONFIRMED;LIVE_COUNT=NONE;LIVE_QUERY=COMPLETE;LIVE_SCAN=NO_ELIGIBLE_OVERLAY;NATIVE_RELATION=UNAVAILABLE
A_MANUAL_RETIREMENT;ATTEMPT=1;EVENT=WINDOW_SET;CHECK=EVENT_TYPE;MASK=MIXED_REMOVAL;DISPLAY=NOT_CHECKED;PACKAGE_CHECK=NOT_CHECKED;NATIVE_CHECK=NOT_CHECKED;RESULT=REJECTED
A_MANUAL_CLEANUP_EVENT;ATTEMPT=1;EVENT=WINDOW_SET;MASK=MIXED_REMOVAL;BOUNDS=YES;ADDED=NO;REMOVED=YES;OTHER_BITS=NO;RETIRED_RELATION=MATCH;WAIT_CONFIRMED=NO;REMOVE_RETURNED=YES;REMOVAL_OBSERVED=NO;NATIVE=WAITING_WINDOW_ABSENCE;RESULT=REJECTED
```

### owned read

- `LIVE_COUNT`: NOT_CHECKED / UNAVAILABLE / NONE / ONE / MULTIPLE. 해제가 성공한 기존 필터 결과의 **고유 후보 ID 집합** 크기다. 전체 창 수나 인증된 C 개수가 아니다. 중복 raw ID를 새로 승인하거나 거부하는 정책은 넣지 않았다.
- `LIVE_QUERY`: NOT_CHECKED / COMPLETE / EMPTY_WINDOW_LIST / WINDOW_LIMIT / READ_FAILED / RELEASE_FAILED / READ_AND_RELEASE_FAILED.
- `LIVE_SCAN`: 기존 필터의 NO_ELIGIBLE_OVERLAY / ROOT_UNAVAILABLE / ROOT_RELATION_UNCONFIRMED / TITLE_UNCONFIRMED / OWNED_CANDIDATE / MIXED 등. 모든 창 중 정확히 어느 창인지는 기록하지 않는다.
- `NATIVE_RELATION`: 기존 단일 후보와 이미 읽은 native ID의 MATCH / MISMATCH / UNAVAILABLE / NOT_CHECKED. 단일 ID가 같아도 이 필드 자체는 새 소유권 인증이 아니다.
- 빈 framework 창 목록이나 32개 상한 초과는 `UNAVAILABLE`이며 `NONE`으로 표시하지 않는다. 유효 후보가 발견돼도 recycle 실패면 `UNAVAILABLE/RELEASE_FAILED`다. 조회와 해제가 모두 실패하면 둘 다 보존한다.
- 첫 실패 point/detail 및 그때의 owned code를 고정한다. 뒤의 native-after 검사/cleanup이 덮지 않는다.

### cleanup event

- retirement의 `MASK`와 DISPLAY/PACKAGE_CHECK/NATIVE_CHECK는 실제 short circuit 결과다. EVENT_TYPE에서 먼저 거절됐으면 후속 필드는 NOT_CHECKED다. 진단을 위해 후속 검사를 강제로 실행하지 않는다.
- `A_MANUAL_CLEANUP_EVENT`는 실제 pending barrier에 전달된 한 이벤트의 분류와 **observe 직전** 상태를 freeze한다. 결과는 기존 observe 실행 후 기록하며 handler/정리 순서를 바꾸지 않는다.
- RETIRED_RELATION은 기존 retired ID와 이벤트 ID 관계다. ID 재사용이나 이벤트 출처를 인증하지 않는다.
- BOUNDS/ADDED/REMOVED는 이미 복사한 마스크의 비교 결과다. OTHER_BITS는 이 세 비트 외의 비트가 있음을 뜻하며, 그 비트가 위험하다고 새로 판정한 것이 아니다. 기존 MASK 범주가 UNSUPPORTED라도 retirement 허용 여부는 기존 함수가 별도로 판단한다.
- WAIT_CONFIRMED는 서비스에서 넘긴 기존 retirement 분류 결과다. REMOVE_RETURNED / REMOVAL_OBSERVED / NATIVE는 barrier의 기존 상태다. 추가 native getter나 창 목록 조회는 없다.
- RESULT=REJECTED는 해당 observe 호출에서 barrier가 처음 실패 상태로 바뀌었다는 뜻이다. REMOVAL_RECEIPT는 순수 제거 알림 영수증일 뿐 완료가 아니다. NO_RECEIPT도 성공을 뜻하지 않는다.
- 기존 `A_MANUAL_CLEANUP;PHASE=FIRST`는 그대로 유지한다. 그 행과 직후 REJECTED cleanup-event 행이 같은 동기 호출의 원인과 상태를 보여준다. 해당 이후 일반 handler의 실패를 같은 event-origin으로 추정하지 않는다.

진단은 offer별 중복을 억제한다. 표시/retirement 최대 16개, 일반 cleanup-event 최대 16개 + **첫 실제 barrier 거부 전용 1개**를 예약한다. 이 예약으로 평상시 로그 한도가 차도 첫 거부 원인이 누락되지 않는다. sink가 실패해도 재시도하지 않으며 원래 판정/취소/cleanup 결과는 바뀌지 않는다. Release 팩토리는 null, 새 formatter/log prefix는 Debug에만 존재한다.

## 불변인 안전 계약

추가 Android getter·수집·OCR·IPC·시계 조회·polling·sleep은 없다. 제한된 enum 대입/기존 Set 크기·비트 비교만 추가했다. CPU/메모리와 Debug 로그의 타이밍 영향이 0이라고 보장하지 않는다.

미확인 native를 소유 창으로 승인하거나 임의 지연/재시도로 표시를 유지하지 않는다. 원래 100ms 확인, 5초 실행 TTL, 30초 표시 lease, cleanup 5초 마감은 유지한다. 기존 허용 마스크를 넓히지 않는다.

실제 동일 native remove 반환 + HIDDEN + detach + 전체 창 ABSENT, 새 전체 F0, 동일 팝업/대상, B 승인, F9 클릭 전 재검증, 단일 클릭 장부, 실제 원본 소멸 확인을 모두 유지한다. HEALTH는 sticky이며 reset하지 않는다. 위험 버튼 차단, 자동 실행/자동 SKIP/F12 OFF, 원본 F1 14개 미검증 상태도 유지한다.

## 로컬 검증

신규 `ManualLiveSetCleanupDiagnosticsTest`는 실제 Android driver와 공유하는 query/해제·표시 소유권 함수, retirement short circuit, barrier와 trace를 검증한다. Android getter/native callbacks만 가짜다. 실제 삼성의 native 창 목록 반영 시점을 JVM에서 재현했다고 주장하지 않는다.

- query null / framework 빈 목록 / 상한 / 소유 후보 0·1·복수·다른 ID / root·소유권·title 탈락
- getter/release 호출 순서 불변, 조회+해제 동시 실패, 독립 해제, 첫 실패 상세 고정
- STATE WAIT_ONLY → mixed-removal WINDOW_SET → 실제 RETIRED_ID_EVENT 실패 순서
- 순수 제거 알림도 실제 native 완료+전체 부재 전 성공 금지, 다른 ID·ADDED·불명 마스크 차단
- deadline 및 HEALTH/첫 실패 유지, 취소와 원래 실패 보존, 로그 실패 격리
- 제한된 보고와 첫 실제 거부 예약

최종 검증 수치와 빌드/Lint/DEX 결과는 아래 완료 결과에 기록한다. 실제 삼성/LG/에뮬레이터 실행은 하지 않았다.

## 삼성 재시험 (Windows PowerShell)

이 로컬 변경을 전달받은 소스에서 빌드한다. 이번 작업은 push하지 않으므로 기존 원격 HEAD만 받아서는 새 진단이 들어가지 않는다.

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
$serial = "<삼성 테스트폰 serial>"
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial logcat -v brief -s A_ManualPrompt:I '*:S'
```

사용자 UI에서 접근성·수집 동의·시험 앱 허용을 확인한다. 설정을 adb로 강제하지 않는다. 다른 PowerShell 창에 같은 adb/serial을 설정하고 합성 P01을 시작한다:

```powershell
& $adb -s $serial shell am start -W -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01
```

TalkBack ON/OFF 각각 SHOWN 뒤 첫 DISPLAY_REVIEW의 LIVE_COUNT/QUERY/SCAN/NATIVE_RELATION을 본다. 유지 성공을 미리 가정하지 않는다. 실패 시 최초 CLEANUP FIRST 행과 같은 observe 뒤의 CLEANUP_EVENT RESULT=REJECTED, 직전 RETIREMENT MASK/NOT_CHECKED 필드까지 함께 보존한다. 특히 WAITING_WINDOW_ABSENCE와 이벤트의 실제 부재 검사를 동일시하지 않는다.

표시가 유지되면 6초 뒤 명시적 닫기 선택 → overlay 제거 확인 → 새 F0/B/F9 → 실제 원본 소멸을 별도로 확인한다. 창이 사라진 것만으로 원본 팝업 닫힘을 성공이라 기록하지 않는다. 동의 철회/외부 화면 변화/잔존 native/위험 대상/불확실성에서는 클릭 0회여야 한다. 일반 logcat 전체·화면 원문은 수집/공유하지 않는다.

## 최종 완료 결과

- 신규 JVM 22개, 관련 query/display/bounds/retirement suite 69개 통과.
- 최종 앱 전체 JVM Debug 1,182개 / Release 1,167개, 실패·오류·skip 0. variant 공통 회귀를 중복 포함한 수치이며 고유 테스트 총합으로 합산하지 않는다.
- 호스트 55개 / 합성 앱 26개는 기존 통과 결과를 이번 Gradle에서 UP-TO-DATE 재사용했다.
- 앱 Debug/Release APK, AndroidTest APK 컴파일·빌드, 합성 앱 Debug 빌드 성공. 최종 전체 Gradle BUILD SUCCESSFUL (4분 16초).
- 앱 Debug/Release 및 호스트 Lint 이슈 0. 기존 C 테스트의 ExperimentalCoroutinesApi opt-in 컴파일 경고는 유지하며 해당 코드는 수정하지 않았다.
- 패키징 검사 통과. A_F9_READ/A_MANUAL_DISPLAY_REVIEW/A_MANUAL_RETIREMENT/A_MANUAL_CLEANUP_EVENT/A_MANUAL_ACTION prefix는 Debug DEX에 있고 Release DEX에 없다.
- 중간 전체 검증 1회는 실행 중 진단 결과 enum/회귀가 갱신되면서 Release 단위 테스트가 이전 main 컴파일 산출물을 참조해 compileReleaseUnitTestKotlin에서 실패했다. 이후 소스를 고정하고 위 전체 작업을 다시 실행해 모두 통과했다. 해당 중간 실행을 통과로 계산하지 않았다.
- critical barrier observe/processed/nativeProcessed/permit/deadline 동작, retirement 허용 마스크, F9EligibilityAdapter/ManualChoiceRefresh/F9ServiceSession/AndroidF9ScreenPort/F9AutoCloser, 서비스·collector·Manifest·gradle.properties는 기존 HEAD와 동일함을 확인했다.
- 추적 변경 및 신규 파일 공백 검사 통과. 변경 11개(기존 수정 8개 + 신규 3개), index 비어 있음. P23 포함 다른 worktree 9개의 HEAD/status/기존 미커밋 파일 해시 동일.
- adb 연결 기기 없음. 삼성/LG/에뮬레이터 실측 미실행. 조기 제거·cleanup 문제의 실기기 해결을 주장하지 않는다. commit/push/merge/PR 변경하지 않았다.
