# P01 DISPLAY_REVIEW NODES_CHANGED 최소 진단 — 2026-10-30

## 기준과 사용자 실측

- worktree `/workspace/a-samsung-p01`, branch `fix/a-samsung-p01-lifetime-20261028`.
- 기준 HEAD `8308fd953e0c5dd4247125ee206457bfaa0703c0`, 시작 clean.
- 사용자 제공 삼성 실측: 10월 30일 13:11:56 ATTEMPT=1. SHOWN 뒤 약 0.14초에 review 시작, DISPLAY_REVIEW read COMPLETE, CHANGED/NODES_CHANGED, LIVE_COUNT=NONE/SCAN=NO_ELIGIBLE_OVERLAY/NATIVE_LISTING=NOT_ENUMERATED. 이후 WINDOW_SET_INVALIDATED/dismiss, ADDED 이벤트에 RETIRED_ID_EVENT/HEALTH 실패.
- 기존 수정은 초기 NONE에서도 실제 대상 읽기에 도달했다. 이번에는 읽기 실패가 아니라 대상 DTO 비교에서 차단됐다. NONE을 OWNED로 승인한 실행은 아니다.
- 새 코드의 삼성/에뮬레이터/P01 실행 없음. 직접 실측 성공이나 선택창 유지 해결을 주장하지 않는다. Git commit/push/merge/PR 변경 없음.

## 1. NODES_CHANGED의 실제 조건과 호출 경계

`CloseForYouAccessibilityService.handleAccessibilityEvent()` → `F9ServiceConnection.reviewDisplayedEvent()` → `ManualPopupConnection.reviewDisplayedEvent()` → 기존 Runtime/session 실행 증거 retire → `verifyRegisteredManualDisplay()`의 native-before → `AndroidF9ScreenPort.verifyDisplayedPopup()` / `scan(DISPLAY_REVIEW)` → `ManualDisplayContinuity.assess(original, fresh, ...)`.

assess에서 COMPLETE, 창 범위/경쟁 창/포커스, 양수 geometry, 앱·대상 창·frame 관계를 먼저 검사한다. 그다음 기존 **`fresh.nodes != original.nodes`**가 true면 CHANGED/NODES_CHANGED를 반환한다. 그 뒤의 전체 window set 비교까지 성공했다는 뜻은 아니다.

`scan()`의 consume 안에서 assess가 CHANGED를 반환해도 framework 읽기와 callback 반환 자체는 정상이다. 그 read는 A_F9_READ STAGE=COMPLETE로 기록된다. driver는 native-after/currentness를 확인한 뒤 A_MANUAL_DISPLAY_REVIEW의 CHANGED를 출력하고 C 제거/TTS 취소를 수행한다. 따라서 COMPLETE와 NODES_CHANGED는 서로 모순되지 않는다.

## 2. 원본과 fresh 노드 출처

원본: 서비스 `capture()`의 `AndroidSnapshotCollector.capture()`가 target root를 순회해 만든 실제 `CollectionResult.Ready.snapshot`이다. evidence pipeline의 표시용 snapshot과 구별된다. 서비스는 `f9.collected(result.snapshot, ..., evidence.snapshot, analysis)`를 호출하고, `manual.offer()`는 첫 번째 snapshot을 original로 보관한다. C 표시 전 admission의 추가 읽기는 동일성을 확인하지만 original을 새 값으로 덮어쓰지 않는다.

fresh: `AndroidF9ScreenPort.scan(DISPLAY_REVIEW)`가 같은 target window의 `active.root`를 소유권 검사 후 순회해 만든 `descriptors`다. `baseNodes`는 별도 배경 보호/지문 용도이며 `fresh.nodes`에 합치지 않는다. C overlay root를 target 노드 목록에 붙이는 코드도 없다.

두 구현은 모두 root path `0`에서 시작해 자식 index 순서의 깊이 우선 선행 순회로 NodeSnapshot을 추가한다. 정렬/좌표 보정/노드 생략은 하지 않는다. 원본 수집과 표시 후 재수집은 별도 관측이므로 원자적으로 같은 순간의 트리라는 보장은 없다.

NodeSnapshot의 data equality에 포함되는 전 필드:

1. path
2. viewId
3. className
4. text
5. contentDescription
6. clickable
7. enabled
8. bounds
9. actions(CLICK / DISMISS의 immutable set)

목록 크기와 순서도 중요하다. child 추가/삭제/접근성 트리 노출 변경은 크기 또는 위치별 path/속성 차이로 나타날 수 있다. 자식 순서가 바뀌면 새로 만든 index path가 같은 위치를 가리켜도 viewId/다른 속성이 달라질 수 있다. path 일치만으로 같은 native node identity를 인증하지 않는다.

## 3. 포커스·자체 창 부수 효과에 관한 증거 수준

확정:

- NodeSnapshot에는 isFocused/isAccessibilityFocused/selected 필드가 없다. window active/focused는 WindowSnapshot의 별도 필드다.
- 양쪽 reader는 ACTION_CLICK/ACTION_DISMISS만 DTO에 넣는다. accessibility focus/clear focus 등 다른 raw action 변화만으로 actions DTO가 달라지지는 않는다.
- 원시 포커스 이벤트 자체를 NODES_CHANGED로 변환하는 비교식은 없다.
- 원격 C가 LISTED인 기존 표시 경로는 같은 target window의 active/focused 차이만 제한적으로 다룬다. NOT_ENUMERATED 경로는 target active AND focused가 필요하고 그 flag를 정규화하지 않는다.
- 앱 window의 bounds/layer/목록 비교는 노드 비교보다 뒤에 있다. NODES_CHANGED가 먼저 나왔다고 다른 window 필드가 모두 같다고 결론 내릴 수 없다.

가능성/미확정:

- 실제 popup text/description/action/geometry/child 목록이 변했을 수 있다.
- C 생성이나 TalkBack과 연동된 Android/OEM 트리·레이아웃 변화가 별도로 관측될 수도 있지만, 그것이 이번 직접 원인이라는 증거는 없다.
- 정적인 P01 `labeledAdDialog()`에는 자동 focus callback으로 본문/버튼을 교체하는 명시적 앱 코드가 보이지 않는다. 이것은 framework/OEM의 접근성 표현이 불변이라는 보증이 아니다.
- 두 reader의 descriptor 생성 규칙에서 이번 입력에 항상 차이를 만드는 구현 불일치는 확인되지 않았다. 단순 포커스 필드 무시·전체 노드 비교 삭제·정렬·null/empty 통합을 적용할 근거가 없다.

## 4. 구현: 첫 노드 불일치 유형만 Debug에 기록

기존 `fresh.nodes != original.nodes` 조건과 거부 결과를 그대로 두고, 그 조건이 true인 Debug 관측에서만 `firstManualNodeMismatch()`로 이미 존재하는 DTO를 비교한다.

고정 코드:

- NODE_COUNT
- PATH_OR_ORDER
- VIEW_ID / CLASS_NAME / TEXT / CONTENT_DESCRIPTION
- CLICKABLE / ENABLED / BOUNDS / ACTIONS
- UNKNOWN(진단 계산 실패 또는 예상 밖 불일치)
- LIMIT_EXCEEDED(방어적 상한; 실제 reader는 이미 1,200개를 제한)
- NOT_CHECKED(추가 불일치 분류를 실행하지 않음: 동일 입력/비Debug/앞선 실패 등)

정책의 원래 List.equals 내부 알고리즘을 추측하지 않는다. 진단 우선순위는 크기 차이 → 첫 위치의 첫 필드(path부터 위 선언 순서)로 명시적으로 고정했다. 다음 노드의 차이와 합치지 않고 index/path/실제 값/숫자는 출력하지 않는다. PATH_OR_ORDER는 실제 순서 변경인지 identity/path 변경인지 인증하는 코드가 아니다. TEXT도 실제 본문 변경인지 null/빈 문자열 표현 차이인지 이 코드만으로 확정하지 않는다.

기존 A_MANUAL_DISPLAY_REVIEW 한 줄에 `NODE_MISMATCH` 하나만 붙인다. 아래는 실제 실측값이 아닌 형식 예시다.

```text
A_MANUAL_DISPLAY_REVIEW;ATTEMPT=1;EVENT=WINDOW_SET;MASK=BOUNDS;RESULT=CHANGED;POINT=COMPARISON;DETAIL=NODES_CHANGED;LIVE_COUNT=NONE;LIVE_QUERY=COMPLETE;LIVE_SCAN=NO_ELIGIBLE_OVERLAY;NATIVE_RELATION=UNAVAILABLE;NATIVE_LISTING=NOT_ENUMERATED;NODE_MISMATCH=ACTIONS
```

실제 driver는 `ManualDisplayProbe(nodeDiagnostics = trace != null)`을 사용한다. Release ManualPromptLog.create()는 기존대로 null이다. Release는 추가 DTO 분류를 수행하지 않고 기존 equality/거부를 수행한다. Debug/Release의 정책 guard는 동일하다. 이 source-set 출력 외에 추가 로그, 수집, Android getter, OCR, IPC, 시계 조회, polling, sleep을 추가하지 않는다.

진단은 최대 1,200개 기존 DTO만 순회하고 String/Set의 기존 값을 비교한다. 처리 비용은 bounded지만 0이라고 주장하지 않으며 기존 100ms/현재성 검사 안에서 실패 가능성이 유지된다. node/화면/Throwable를 probe나 기록에 보관하지 않고 enum만 남긴다. 첫 detail/point/owned/listing/node 코드가 후속 실패로 덮이지 않는다. 계산·출력 예외/진단 취소는 기존 diagnosticWrite로 격리하며 실제 관측 취소·cleanup 실패는 기존 경로로 계속 전파한다.

## 5. 별도 cleanup: ADDED의 정확한 의미와 차단 유지

`nativeRetirementTransientEvent(WINDOWS_CHANGED, ADDED)`는 false다. `nativeCleanupRemovalCombination`도 REMOVED 비트를 요구하고 ADDED를 제외하므로 false다. 따라서 선택 보존 COMPATIBLE도 cleanup-only WAIT도 부여하지 않는다.

pending barrier에서 retired numeric id와 해당 event id가 같고 순수 REMOVED가 아니면 `OverlayCleanupBarrier.observe()`는 RETIRED_ID_EVENT로 실패한다. 같은 native View가 detach됐더라도 ADDED는 제거 영수증이 아니다. 한 번 실패한 barrier는 뒤늦은 native callback/ABSENT/REMOVED로 되살아나지 않으며 HEALTH는 실패 상태를 유지한다.

이번 로그는 MASK=ADDED에서 이 실패 경로를 통과했다는 직접 증거다. 그러나 ADDED가 원래 생성의 지연 알림인지, 실제 재등장인지, id 재사용인지 이벤트 자체에는 native generation 증거가 없어 구별할 수 없다. 과거 생성 이벤트라고 추정해서 허용하지 않았다. cleanup 운영 코드는 이번에 변경하지 않았으며 회귀로 그 거부를 재현했다.

서비스를 다시 켜도 새 시도의 동일한 node 차이/ADDED 순서가 재발할 수 있다. 이 사실만으로 이전 HEALTH가 영구 저장됐다고 판단하지 않는다. HEALTH를 초기화하거나 선택창을 자동 재시도하는 수정은 없다.

## 6. 유지한 보호·미해결

노드 equality·전체 트리/창/소유권·포커스·frame·100ms·native 전후 검사는 유지한다. LIVE_COUNT=NONE은 소유권 성공이 아니다. F0/F9·SCREEN_MATCH·원본 evidence/page proof 5초·표시 수명 30초·cleanup 5초·단일 클릭·위험 버튼 거부·실제 popup 소멸 확인·자동 실행/SKIP/F12 OFF를 유지한다.

NATIVE_ID_UNAVAILABLE, 원격 확인 이후 NONE, 시스템 가림, 이번 노드 차이의 실제 원인, ADDED 출처, 원본14 UNOBSERVED/Scroll/Window/원자성은 계속 미해결이다. 선택창 유지 기능을 해결했다고 주장하지 않는다. P23·B/C/D·공통 계약·Manifest/properties 변경 없음.

## 7. 회귀와 다음 삼성 로그

신규 `ManualDisplayNodeMismatchTest`는 실제 assess → review/제거 → terminal code를 호출한다. 각 DTO 필드, 크기·순서·최초 코드, Debug 분류 on/off의 같은 거부, 순수 window focus와 node field의 구분, 첫 실패 보존, 진단 계산/출력 실패, 실제 취소/cleanup 실패를 검사한다. 실제 공유 등록/native-before/whole-screen/native-after를 거친 CHANGED 이후 ADDED를 전달해 RETIRED_ID_EVENT와 sticky 차단도 재현한다. fake Android port/합성 DTO 시험이며 실제 삼성 node 값을 재현한 것이 아니다.

수정본을 전달받아 Debug로 빌드한 뒤 기존 P01 절차의 제한된 tag만 확인한다. 이번 코드는 아직 로컬 미커밋이다.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "<삼성 테스트폰 serial>"
& $adb -s $serial logcat -v brief -s A_ManualPrompt:I '*:S'
```

같은 ATTEMPT에서 SHOWN → DISPLAY_REVIEW_STARTED → A_F9_READ PURPOSE/STAGE/OUTCOME → A_MANUAL_DISPLAY_REVIEW RESULT/POINT/DETAIL/**NODE_MISMATCH**/NATIVE_LISTING/LIVE_* → dismiss/cleanup FIRST+MASK/RESULT를 순서대로 구분한다. NODE_MISMATCH가 의미하는 기존 DTO 속성만 확인하며 원문/좌표/앱·창·노드 ID/토큰/지문/예외나 전체 node dump를 요구하지 않는다. 성공 여부와 실행 가능성을 올리는 입력으로 사용하지 않는다.

## 8. 최종 로컬 검증

최종 실행 결과와 변경 범위는 아래에 기록한다. 삼성 실측 결과가 아니다.

### 완료 결과

- 신규 `ManualDisplayNodeMismatchTest` **19개** 통과. 실제 표시 비교에서 DTO 9필드/개수/순서/최초 코드·분류 비활성 동일성·순수 창 focus·ADDED 거부·취소/정리/진단 실패를 포함한다.
- 앱 전체 Debug **1,297개**, Release **1,282개**, 각 실패·오류·skip 0. 두 variant를 고유 총수로 더하지 않는다.
- 호스트 55개 / 합성 앱 26개는 기존 UP-TO-DATE 결과 재사용.
- 앱 Debug/Release와 AndroidTest APK 빌드 성공. 호스트 Debug/AndroidTest 및 합성 앱 Debug 산출물 재사용.
- 앱 Debug/Release·호스트 Lint XML issue 0.
- 기존 APK/DEX 격리 검사 통과. 새 JVM 회귀 클래스는 앱 Debug/Release DEX에 없음.
- `;NODE_MISMATCH=`, A_MANUAL_DISPLAY_REVIEW, 기존 SCREEN_MATCH/NATIVE_LISTING 로그 문자열은 Debug에 존재, Release에 부재. Release는 추가 DTO 분류도 비활성이다.
- 최종 Gradle `BUILD SUCCESSFUL in 3m 42s`, 250 tasks(32 executed / 218 up-to-date). log `/workspace/a-p01-node-mismatch-full.log`.
- CRLF 보존 diff 공백 검사·신규 파일 공백·정확한 변경 범위 검사 통과.
- 기존 collector/AndroidF9ScreenPort/F9AutoCloser/F9ReadDiagnostics/session/eligibility/refresh/retirement/barrier/Release ManualPromptLog/Manifest/properties는 HEAD bytes와 동일. P23 포함 다른 9개 worktree HEAD/status/기존 미커밋 파일 SHA-256 보존 확인.
- 삼성·LG·에뮬레이터/P01 실측은 미실행. 초기 표시 소멸의 구체적인 노드 필드와 ADDED 출처는 아직 미확정.

검증 명령:

```bash
JAVA_HOME=/workspace/jdk17 ANDROID_HOME=/workspace/android-sdk ./gradlew --offline --no-daemon --max-workers=1 :app:testDebugUnitTest :app:testReleaseUnitTest :capture-test-host:testDebugUnitTest :popup-test-app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest :popup-test-app:assembleDebug :app:lintDebug :app:lintRelease :capture-test-host:lintDebug
PYTHONDONTWRITEBYTECODE=1 python3 tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff --check
```

### 변경 파일 (정확히 5개)

1. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualDisplayReview.kt` — 첫 DTO 불일치 enum/helper와 Debug opt-in probe, 기존 NODES_CHANGED 거부 보존.
2. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualPopupConnection.kt` — 실제 Debug trace에만 분류 활성화, 기존 보고 연결.
3. `app/src/debug/java/kr/closeforyou/accessibility/integration/ManualPromptLog.kt` — 기존 한 줄의 NODE_MISMATCH 필드.
4. `app/src/test/java/kr/closeforyou/accessibility/integration/ManualDisplayNodeMismatchTest.kt` — 신규 19개 회귀.
5. `docs/A_P01_NODE_MISMATCH_DIAGNOSTICS_2026-10-30.md` — 이 분석·검증 기록.

HEAD `8308fd953e0c5dd4247125ee206457bfaa0703c0` 유지. 수정 3개·신규 2개, staged 없음. 커밋·푸시·PR·병합·다른 worktree 수정 없음. **이번 결과는 원인 식별 준비 완료이며 삼성 선택창 유지 문제의 해결 완료가 아니다.**
