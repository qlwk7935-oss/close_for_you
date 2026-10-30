# P01 삼성 실패 종합 조사·수정·회귀 검증

## 1. 작업 범위와 종료 판정

- worktree: `/workspace/a-samsung-p01`
- branch: `fix/a-samsung-p01-lifetime-20261028`
- 기준 HEAD: `2c6ba1a76371a35c2b43cf005990fd1e969af02a`, 시작 상태 clean
- Git commit/push/merge/PR 변경 없음. P23 및 다른 worktree 보존.
- **삼성폰에서 실제 닫힘을 확인한 작업이 아니다.** 기기가 연결되지 않아 삼성/LG/에뮬레이터 계측은 미실행이다.
- 실제 실패 지점을 코드와 연결했고, 세 가지 오류·현재성 경계를 보강했다. SYSTEM 가림과 초기 native/remote 목록 불일치는 원인 미확정이므로 기존 거부를 유지했다.

## 2. 사용자 실측으로 확정된 사실

| 구간 | 증거 | 확정 가능한 결론 |
|---|---|---|
| 03:14:40 ATTEMPT 3 | 음성 완료, 선택 접수, C 제거/quiet, 새 F0, snapshot/target 비교 통과 | 사용자 선택과 제거 이후 재수집 경로까지 도달 |
| 같은 시도 | B APPROVED/EXECUTE, MANUAL_ELIGIBILITY/OCCLUSION/OCCLUDING_WINDOW | B 승인과 독립적으로 F9 화면 읽기가 차단 |
| 가림 코드 | SYSTEM/ABOVE/PARTIAL/NOT_CHECKED_BOUNDED_TARGET | SYSTEM 타입의 더 높은 창에 대해 bounds 겹침이 참이고 passive helper는 외부 gate에서 미평가 |
| ATTEMPT 2/4 표시 중 | DISPLAY_REVIEW 가림 거부 → UNAVAILABLE → SPEECH CANCELLED | 이 시도들의 직접 표시·음성 중단 원인은 해당 read 거부 |
| 03:14:31 ATTEMPT 1 | native-before, NONE/COMPLETE/NO_ELIGIBLE_OVERLAY | 정상 반환된 필터 결과에서 type=ACCESSIBILITY_OVERLAY AND default display인 후보를 발견하지 못함 |
| 반복 표시 | FIRST_OFFER → IDENTITY_CHANGED/STRUCTURE_CHANGED → UNCHANGED_RECOVERY | 기존 identity 비교 키의 구조 지문 차이와 recovery admission이 관측됨 |
| 안내 | LIKELY_COUPON / PRESS_OFFERED=YES | 쿠폰 요약과 닫기 선택지가 함께 계획됨. 서로 모순되지 않음 |
| 최근 cleanup | REMOVAL_RECEIPT → OVERLAY_REMOVED, 실패 없음 | 해당 최신 실행은 cleanup 완료 경로를 통과. 과거 RETIRED_ID_EVENT를 재사용해 원인으로 단정할 수 없음 |

SYSTEM의 실제 소유 앱/기능, 불투명도, 터치 영역의 형태, 삼성 시스템 바 여부는 이 로그로 확정되지 않는다. B 승인도 실제 클릭·원본 소멸의 증거가 아니다.

## 3. Android 공식 코드 계약과 한계

AOSP `android-15.0.0_r1`의 다음 공개 소스를 읽었다. 이 태그는 삼성 OEM/API 37 구현과 동일하다는 보증이 아니다.

- [AccessibilityWindowInfo.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityWindowInfo.java): TYPE_SYSTEM(66), TYPE_ACCESSIBILITY_OVERLAY(73), layer(207), root(229), Region/bounds(379), active(409) 부근
- [AccessibilityService.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/accessibilityservice/AccessibilityService.java): getWindows(1036) 부근
- [AccessibilityInteractionClient.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityInteractionClient.java): getWindowsOnAllDisplays(490) 부근
- [AccessibilityCache.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityCache.java): windows/state 이벤트 처리(276), cache 조회(383) 부근

계약상 TYPE_SYSTEM은 시스템과 상호작용하는 UI 창이다. 상태 바라는 인증이 아니다. 큰 layer는 위쪽 Z-order다. `getBoundsInScreen()`은 **touchable Region의 bounding Rect**를 복사한다. 이것은 실제 불투명 픽셀 영역이나 모든 시각적 가림의 인증이 아니다. 따라서 bounding Rect의 부분 겹침은 실제 닫기 버튼의 가림을 단정할 수 없지만, 반대로 버튼과의 겹침이 없거나 Region에 구멍이 있다는 사실만으로 전체 팝업을 안전하게 볼 수 있다고 증명할 수도 없다.

`getWindows()`는 기본 디스플레이에서 상호작용 가능한 창을 반환한다. 모든 WindowManager/native 창의 동기식 등록 영수증이 아니다. AOSP client는 창 캐시를 먼저 사용하고 cache miss에서 서비스 연결을 조회한다. cache는 WINDOWS_CHANGED/STATE 등의 이벤트로 갱신/무효화된다. **캐시 경로가 존재한다는 사실이 이번 삼성의 stale cache를 입증하지는 않는다.** C addView 반환, View attach/ID, accessibility observer 목록 및 이벤트 전달은 서로 다른 경계다. 캐시 강제 clear, 임의 지연, 자동 재시도로 관측 실패를 숨기지 않았다.

## 4. SYSTEM OCCLUSION의 정확한 판정

`AndroidF9ScreenPort.scan()` 144 부근 → `f9OccludingWindow()` (`F9ReadDiagnostics.kt` 118 부근):

1. 대상 ID와 정확한 표시용 native C ID를 제외한다.
2. TYPE_APPLICATION이 아닌 창만 고려한다.
3. layer가 대상 이상이어야 한다.
4. 원시 bounds끼리 양의 면적으로 겹쳐야 한다. 경계 접촉만으로는 해당되지 않는다.
5. `targetPath == null && activeBounds == frame`일 때에만 기존 passiveSystemBar 예외를 검사한다.
6. 첫 거부 창에서 any 순회를 중단한다. 다른 창의 속성과 혼합하지 않는다.

기존 passive 조건: TYPE_SYSTEM, inactive, unfocused, frame 전체 폭, 높이 1..frame/8, 상단 또는 하단 접촉, root 존재, 고정 SystemUI package 및 동일 window ID. 해당 callback은 일반 화면의 시스템 바 때문에 PAGE 읽기까지 전부 차단되는 것을 제한적으로 다루는 기존 예외다. bounded popup/실제 press의 가림을 허용하는 계약으로 일반화할 근거가 없다.

### 목적별 영향

| PURPOSE | 현 계약 |
|---|---|
| PAGE | full-frame 일반 화면에서만 기존 passive 후보 검사 가능 |
| AUTO_ELIGIBILITY | bounded popup의 비앱 창 겹침은 계속 거부. 자동 실행 gate도 OFF |
| MANUAL_ELIGIBILITY | fresh F0/B 승인과 별개로 동일한 겹침 거부. 최신 삼성의 직접 차단 지점 |
| DISPLAY_REVIEW | 확인된 C만 제외, 원래 대상 전체를 검증. bounded SYSTEM 겹침은 여전히 거부하여 표시/TTS 제거 |
| PRE_CLICK | 최종 전체 화면 읽기에서 동일한 엄격 검사 |
| PRESS | non-null targetPath이므로 passive 예외 미평가. 단일 ACTION_CLICK 직전 검사 유지 |
| POST_CLICK | 남은 창이 full-frame page이면 기존 passive 조건 검사 가능. 읽을 수 없으면 UNKNOWN이며 성공 아님 |

최신 SYSTEM은 ABOVE/PARTIAL이지만 inactive/focused/root owner는 **그 OCCLUSION 지점에서 새로 검사됐다는 증거가 없다.** manual normal-read 앞의 경쟁 창 guard가 통과했다는 사실과 순간 상태의 원자적 불변성은 구분한다. 삼성 One UI, cutout/inset/edge-to-edge, status/navigation bar, region의 disjoint 조각은 가능한 원인 후보지만 실제 형태가 없다. frame을 inset/확장하거나 popup/node bounds를 보정하지 않았다.

### 이번에 추가한 진단

이미 그 predicate가 읽은 raw bounds와 frame만으로 `FRAME_SHAPE`를 계산한다:

`NOT_CHECKED / EMPTY / OUTSIDE_FRAME / FULL_FRAME / TOP_EDGE_STRIP / BOTTOM_EDGE_STRIP / OTHER`

EDGE_STRIP은 기존 passive 후보와 같은 폭/높이/접촉 관계일 뿐 **시스템 바/투명성/소유권/실행 허가가 아니다.** 기존 SYSTEM/ABOVE/PARTIAL/PASSIVE_BAR와 같은 행에 붙는다. 추가 Android getter/root 조회/수집/OCR/IPC/시계 조회는 없다. root/window owner 비교는 원래 full-frame helper가 호출되던 곳에서만 공유 `f9PassiveBarOwner()`로 추출해 검사 순서를 JVM에서도 검증했다. bounded/press에서 owner 검사를 강제로 실행하거나 예외를 새로 허용하지 않았다.

형식 예시(실측값 아님):

```text
A_F9_READ;ATTEMPT=3;PURPOSE=MANUAL_ELIGIBILITY;STAGE=OCCLUSION;DETAIL=OCCLUDING_WINDOW;OUTCOME=REJECTED;RELEASE=NONE;CALLBACK_RETURNED=NO;OCCLUDER_TYPE=SYSTEM;LAYER=ABOVE;OVERLAP=PARTIAL;PASSIVE_BAR=NOT_CHECKED_BOUNDED_TARGET;FRAME_SHAPE=TOP_EDGE_STRIP
```

이 예시라도 missing owner/opacity proof가 남으므로 클릭 0회다. 실제 가림을 단순 타입이나 모양으로 무시하는 수정을 하지 않았다.

## 5. LIVE_COUNT=NONE 초기 제거

실제 순서 (`AccessibilityChoiceOverlay` 200~310, `ManualPopupConnection` 98~162, 340~380 부근):

- A native owner/generation 생성 → C Compose View 생성과 observer 등록
- keeper가 WindowManager.addView → native add 반환 기록
- attach/첫 draw callback에서 해당 View의 접근성 node 메타데이터로 ID 확인
- C keeper SHOWING, onShown listener, A present 반환 뒤 표시 lifetime 시작
- Android 이벤트 → shouldIgnore의 기존 소유권 검사 → 원래 대상의 순수 BOUNDS이면 표시 재검증
- native-before의 owner/generation/ID → before 집합에서 부재 → 기존 service.windows 필터의 정확한 단일 후보와 일치 확인
- 전체 읽기/비교 → native-after → SAME 유지 또는 CHANGED/UNAVAILABLE 제거

최근 NONE/COMPLETE/NO_ELIGIBLE_OVERLAY는 root/package/title 검사를 통과하지 못했다는 로그가 아니다. 해당 query에서는 type/display 필터를 통과한 overlay 후보 자체가 없었다. `native.liveWindow()`가 유효했으므로 A가 가진 등록/attach/add/generation/ID 경계는 통과했지만 원격 목록과의 일치를 증명하지 못했다.

**아직 모르는 점:** native ID가 목록에서 완전히 없었는지, 같은 ID가 다른 type/display로 보고됐는지, observer/cache 전달 시점 차이인지. 기존 query는 적격이 아닌 창의 ID까지 새로 읽지 않으므로 이를 추정하지 않는다. 초기 목록을 신뢰해 C라고 고르거나 NONE을 일시적 성공으로 재해석하지 않는다.

새 sequence 회귀는 실제 native lifetime을 attach/add로 진행하고, 실제 `readOwnedOverlayIds()`에 C 없는 목록을 주어 NONE을 만든 뒤 `manualDisplayOwnedId()`가 거부하는 것을 검증한다. 나중에 올바른 목록을 제공해도 제거된 generation의 이전 callback은 살아나지 않는다. 이는 **가능한 순서를 fake port로 재현**한 것이며 삼성의 캐시 문제를 재현했다고 주장하지 않는다.

## 6. 코드로 입증한 결함과 최소 수정

### 6.1 소유 창 조회의 취소 소실

`OverlayEventDiagnostic.kt:128`, `ManualPopupConnection.kt:356` 부근.

기존 outer `catch(Exception)`은 body/recycle CancellationException까지 null + HEALTH 실패로 바꿨다. body/cleanup scope가 보존한 원래 취소가 밖으로 전달되지 않았다. secondary health callback이 예외를 던지면 원래 취소가 대체될 수도 있었다.

취소를 별도로 처리한다. 모든 취득 root/window는 기존 batch가 독립적으로 해제한 뒤이며, sticky health를 실패 처리하고 원래 취소를 다시 던진다. secondary callback/진단 오류는 원래 취소를 덮지 않는다. 일반 조회 실패·빈 후보·목록 상한의 기존 결과는 유지한다. reader probe를 finally에서 회수해 취소 시에도 query/release의 고정 코드가 남는다.

### 6.2 실패한 C 정리가 F9/Runtime 무효화를 건너뜀

`F9ServiceConnection.invalidate()` 120, 서비스 `invalidate()` 365, `ManualOverlayDiagnostics.kt:179` 부근.

기존 코드는 manual.invalidate → session.invalidate, f9.invalidate → Runtime.invalidate를 순차 호출했다. 앞의 C cleanup이 던지면 뒤의 stale evidence 폐기가 생략될 수 있었다. 새 `invalidateAfterOwnedCleanup()`은 기존 CollectionResourceSafety를 재사용해 **뒤 무효화를 항상 시도**한다. 정상 순서는 동일하며, 최초 검사 실패/취소의 우선순위를 유지한다. Runtime의 token/analysis 폐기가 C 제거 성공의 인증으로 사용되지는 않는다.

### 6.3 동기 Android 이벤트와 코루틴 경계 구분

서비스 `onAccessibilityEvent()` 111, 공유 `completeSynchronousAccessibilityCallback()` (`ManualOverlayDiagnostics.kt:187`).

reader의 취소 전파를 동기 Android callback 밖으로 무조건 던져 AndroidRuntime에 framework message가 노출되게 하지 않는다. 이 진입점에는 취소를 전달받을 코루틴 Job이 없으므로 CANCELLED/EXCEPTION 고정 코드로 **그 이벤트를 중단**, 원래 화면·정책 무효화를 시도하고 processed=false의 기존 cleanupFailed 경로로 HEALTH를 차단한다. handler 뒤의 receipt 처리/정리 콜백도 동일한 경계 안에 넣었다. body와 cleanup에 동시에 실패가 있으면 기존 resource scope가 원래 실패/취소 우선순위를 지키며, callback 오류가 Runtime 무효화를 건너뛰지 않는다. 실패한 이벤트의 removal receipt를 처리 완료로 승격하지 않는다. 재시도/새 토큰 승인/HEALTH reset은 없다.

이 adapter는 실제 Android callback에만 연결했다. 이 소유 창 reader를 호출한 코루틴 구간에는 원래 취소가 그대로 전파된다. 다른 collector의 모든 framework 실패/취소를 이번 변경으로 해결했다고 선언하지 않는다. VM Error는 정상 복구 결과로 잡지 않는다. 일반 framework 예외 원문도 새 Debug 로그로 출력하지 않는다.

### 6.4 오래된 TTS 완료 영수증

`ManualPromptSpeech.kt:45` 부근.

기존 코드는 deliver 반환 뒤 Job의 취소만 확인했으며, 표시 lease/request가 바뀌었지만 Job은 아직 살아 있는 순간에도 COMPLETED를 보고할 수 있었다. 반환 직후 기존 display currentness를 한 번 더 확인해 STALE/NOT_IMPLEMENTED로 돌린다. 엔진 stop/retry는 추가하지 않는다. 건강한 6초 이상 표시·음성은 유지하며 원래 실행 TTL을 갱신하지 않는다. 실제 들었다는 인증을 만들지 않는다.

이 네 경계는 코드/회귀로 입증된 결함·보강이다. **삼성 SYSTEM/NONE의 실제 발생 원인이라고 주장하지 않는다.**

## 7. 수동 선택부터 실제 클릭/소멸까지

`ManualPopupConnection.handleChoice()` → `ManualChoiceRefresh.afterRemoval()/collect()` → 서비스 `collectForManualChoice()` → `F9ServiceSession.executeUser()` → prepareUser/B → `F9AutoCloser.execute()` → Android `press()` → POST_CLICK.

1. C의 현재 generation/제시한 선택/토큰에 맞는 명시적 CLOSE_ONCE만 접수. ChoiceOrigin만으로 실제 사용자 출처를 새로 인증하지 않는다.
2. C HIDDEN/remove 반환만으로 진행하지 않는다. 같은 native owner/generation/ID의 실제 detach + 전체 접근성 창 목록 ABSENT + 기존 cleanup deadline 필요.
3. quiet 후 서비스의 `collector.capture()`를 **실제로 한 번 새 호출**한다. `collectForManualChoice()`는 old DTO의 timestamp/token을 바꿔 반환하지 않는다.
4. 새 캡처의 원래 시각, 새 epoch, COMPLETE, 전체 창/frame/digest/node 동일성, 민감/위험 텍스트, B 후보와 선택 대상의 정확한 일치 검사.
5. prepareUser가 새 F9Scene과 token/노드/창/geometry/보호를 비교하고 모든 기존 eligibility를 각각 계산. NEW_WINDOW를 수동 클릭에 무조건 부여하지 않는다.
6. 실제 B USER_CHOICE 승인 + candidate/token/eligibility 결합 필요. 승인만으로 F9 통과 아님.
7. PRE_CLICK read 후 B receipt 검증, PRESS의 실제 전체 재수집/대상 refresh와 즉시 재검증. 원래 새 F0의 5초 TTL/서비스 세대·설정·조작·정책/currentness를 유지.
8. 유일한 대상의 ACTION_CLICK 한 번. 호출 전에 attempted=true; 반환/예외도 실제 호출을 숨기지 않음. ACTION_DISMISS/Back/좌표/parent-click/gesture fallback 없음.
9. 원래 page의 독립적인 digest·창 집합·동일 앱·popup 창 부재를 확인해야 CLOSED_CONFIRMED. `performAction=true`만으로 성공하지 않는다. 읽기 실패/변경은 UNKNOWN 또는 LEFT_APP, 남아 있으면 STILL_PRESENT.
10. issued/reserved 장부는 같은 시도/identity를 재시도하지 않는다. F12 기억 규칙/자동 SKIP/자동 실행은 활성화하지 않았다.

| 실패 | 구분하는 기존 코드 |
|---|---|
| 새 F0 차단/null | MANUAL_REFRESH / COLLECTION_UNAVAILABLE |
| 새 표본의 나이/불완전 | SNAPSHOT_MATCH / FRESHNESS_OR_COMPLETENESS |
| 앱/창/구조/본문/frame/창 집합 변화 | SNAPSHOT_MATCH / EvidenceMismatch 고정 코드 |
| 전체 nodes/선택 대상 변화 | NODES_CHANGED / TARGET_UNAVAILABLE / TARGET_CHANGED |
| 민감·위험 콘텐츠 | PROTECTED_CONTENT 또는 B safety enum |
| F9Scene 미확인 | SCREEN_AVAILABLE / SCENE_UNAVAILABLE + 같은 scan의 A_F9_READ |
| 가림/경쟁/owner/시간/해제 | A_F9_READ의 stage/detail/release 고정 코드 |
| B 불허 | B_DECISION REJECTED / safety enum |
| 클릭 직전 TTL/설정/cleanup/화면 변화 | F9_VALIDATION의 F9Block, CLICK_ATTEMPT NOT_ATTEMPTED |
| 클릭 전달 실패/미확정 | DELIVERY_FALSE/UNKNOWN + outcome |
| 실제 원본 남음/관측 불능 | STILL_PRESENT/UNKNOWN, 성공 보고 금지 |

## 8. 반복 표시와 음성 생명주기

- cleanup의 실제 완료만 `reviewAfterCleanup()`을 열며 같은 permit당 review 요청은 한 번이다.
- 서비스 `observeAfterCleanup()`은 기존 invalidate/clear/debounce 후 새 F0/F1/evidence를 수행한다. 과거 candidate를 replay하지 않는다.
- `ChoiceReviewBudget`의 키는 app/window/structureDigest/contentDigest. 동일 키 최초+recovery 1회, 다른 키는 새 예산이다. STRUCTURE_CHANGED는 같은 service의 두 구조 지문이 달랐다는 뜻이다.
- collector의 구조 입력은 path/viewId/class/bounds/clickable/enabled/childCount/actions다. popup UUID/version/capturedAt만 바뀌었다고 구조 identity가 달라지는 것은 아니다. 어떤 node/필드가 삼성에서 변했는지는 원문·좌표를 보존하지 않는 현 진단으로 확정할 수 없다.
- 구조가 번갈아 바뀌면 기존 예산이 새로 시작되어 여러 표시가 가능하다. 이 사실만으로 팝업 교체를 인정하거나, 반대로 이후 실제 팝업까지 모두 숨기는 global suppression을 추가하지 않았다.
- 명시적 선택 이후는 completedKey/issued/reserved가 별도로 중복을 막는다. 새 OFFER 거절을 실제 클릭 실패의 원인으로 섞지 않는다.
- C planner는 COUPON에서도 적격 닫기 선택지를 만들 수 있다. LIKELY_COUPON+PRESS_OFFERED=YES는 분류 변경의 증거가 아니다.
- 표시 전 admission의 원래 5초 조건과 표시 후 30초 lease는 그대로다. 실행 evidence의 5초 만료는 건강한 C 표시/TTS를 강제로 끝내지 않으며, 오래된 분석으로 클릭을 허용하지도 않는다.
- 실제 외부 변화/조작/동의 철회/민감 화면/확인 불가에서는 표시·작업을 폐기한다. 시스템 이벤트를 package만으로 무시하지 않는다.

## 9. cleanup/HEALTH 보호

기존 barrier/native 계약과 C keeper는 수정하지 않았다. HIDDEN/receipt/WAIT_ONLY는 단독 인증이 아니다. remove 반환 + detach + exact native registration + full ABSENT와 원래 deadline을 유지한다. ROOT/window 해제도 각각 독립 시도한다.

기존 회귀에 더해 새 sequence에서 사용자 선택 제거, 표시 재검증 실패 제거, 재생 중 제거, STATE/BOUNDS 후 REMOVED, receipt 전후 detach/목록 PRESENT·UNKNOWN·ABSENT, 실제 remove 실패, callback 재사용을 연결했다. 과거 generation/다른 retired ID/LAYER/중복 removal/허용되지 않은 mixed 마스크는 기존 native/barrier suite를 전체 실행해 보호한다. 실패 후 HEALTH를 초기화하지 않는다.

C 창이 없어졌다는 사실과 원본 popup이 없어졌다는 사실은 끝까지 분리한다.

## 10. 회귀 구성과 테스트 한계

`P01SamsungSequenceRegressionTest`는 실제 C Flow/ChoicePromptPlanner/OverlayWindowKeeper, B DecisionCandidateWiring/DecisionActionPolicy, A GuardedChoicePresenter/lease/admission/barrier/refresh/session/F9 coordinator를 연결한다. native window, F0 collector DTO, TTS backend, clock, Android action은 가짜 port다. B 승인 또는 eligibility를 테스트 편의상 전부 PASSED로 주입하지 않는다. production 자동 실행은 OFF인 상태에서 명시적 수동 경로만 시험한다.

포함: P01 TTS 완료→6초 대기→명시 선택→실제 shared cleanup/quiet 대기→새 collector callback→B→단일 simulated click→소멸 확인, 중복 callback, 최신 SYSTEM 실패, DISPLAY_REVIEW 가림/변경/NONE, 나중의 목록 복구에도 old callback 거부, wrong generation, fresh null/changed target, remove/absence 실패, PRE_CLICK/PRESS 교체/가림, POST_CLICK 불능/원본 잔존, 동의 철회, P02/P03 성격의 쿠폰/공지, 위험 콘텐츠.

`ManualP01BoundaryRegressionTest`는 위 취소·secondary cleanup·TTS 반환 경합·독립 상태 무효화·동기 callback의 고정 실패 분류를 실제 공유 함수로 검사한다. `F9OcclusionDiagnosticsTest`는 shape가 허용 조건에 영향을 주지 않는 것과 실제 root metadata 비교의 getter short circuit을 보강했다.

**JVM port의 fresh DTO는 합성 표본이다. 실제 Android full F0나 실제 버튼을 읽고 눌렀다는 증거가 아니다.** Android 캡처 호출 연결은 소스/컴파일로 확인했고 native 실제 목록·표면·포커스·TalkBack·물리적 닫힘은 삼성 실측이 필요하다. 현재 collector의 Ready를 F0 전체 완전성·현재성·원자성의 형식 증명으로 승격하지 않는다. 기존 F1 14개 UNOBSERVED 및 Scroll/Window 문제도 해결했다고 주장하지 않는다.

검증 과정에서의 실패도 남긴다:

- 수정 전 신규 boundary 6개 중 5개가 실패: 취소 소실 3개, stale TTS receipt 2개를 실제 공유 경로로 재현.
- 취소 수정 직후 기존 V5 parity 회귀 2개가 null 기대 때문에 실패. 일반 경로·getter/해제 횟수의 frozen V5 비교는 유지하고, cancellation만 Debug/Release 모두 전파하도록 기대를 강화했다. 기존 F1/geometry 정답을 바꾸지 않았다.
- 새 통합 fixture 초기 2개는 backgroundScope에서 advanceUntilIdle가 post-click 지연을 진행하지 않아 결과를 읽지 못했다. fixture의 가짜 시계를 명시 진행해 원래 100/200/400/500ms 관측이 끝나게 수정했다. 운영 지연/TTL/성공 조건은 변경하지 않았다.
- 추가 상태 무효화 경계를 발견한 뒤 다시 전체 검증했다. 아래 최종 결과만 최종 코드의 통과로 간주한다.

## 11. 변경하지 않은 범위와 남은 차단

F9AutoCloser의 실제 click/approval/reservation/POST_CLICK 규칙, prepareUser/자동 eligibility, ManualChoiceRefresh의 동일성/새 캡처 기준, native cleanup barrier, C/D/B 소스·공통 DTO, Manifest/gradle.properties는 유지했다. 기존 보호/geometry/100ms 예산을 낮추지 않았다.

SYSTEM과 NONE은 여전히 **기능 차단 요인**이다. 표본의 완전성/소유권 또는 가림 안전성을 증명하지 못하면 0회 클릭을 유지한다. ROOT 메타데이터가 아예 미평가된 bounded 분기를 모양만으로 패스시키지 않는다. 손대지 않은 실제 collector/F0 전체 보호의 형식 완전성, OEM cache/포커스 복구, 원자적 사용자 출처/토큰 결합, 실제 닫힘은 이 작업으로 입증하지 않았다.

저수준 native 동작/비협력적 Binder 호출의 강제 중단, callback 사이에 발생했다 사라지는 미관측 ABA, 다른 read 간 원자적 frame 결합도 보장하지 않는다.

## 12. 삼성 재시험 PowerShell

이번 변경은 미커밋이며 원격에 없다. 변경본을 Windows 프로젝트에 전달받은 뒤 빌드한다. 기존 원격 HEAD만 빌드하면 이번 경계 수정/FRAME_SHAPE가 포함되지 않는다.

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :popup-test-app:assembleDebug
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
$serial = "<삼성 테스트폰 serial>"
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial install -r .\popup-test-app\build\outputs\apk\debug\popup-test-app-debug.apk
& $adb -s $serial logcat -v brief -s A_ManualPrompt:I '*:S'
```

수집 동의·허용 앱·접근성 활성화는 사용자 UI에서 직접 확인한다. adb settings로 강제하지 않는다. 자동 실행/자동 SKIP/F12는 계속 OFF다. 새 PowerShell 창에 같은 adb/serial을 설정하고:

```powershell
& $adb -s $serial shell am start -W -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01
```

TalkBack OFF/ON 각각 시험한다. 표시 후 음성이 끝나는지, 같은 popup의 C가 6초 이상 유지되는지 확인하고 '닫기 버튼 눌러주기'를 명시적으로 한 번 선택한다. 실제 원본 팝업 소멸과 합성 앱의 버튼 기록을 별도로 확인한다. 실패한다고 같은 오래된 선택을 다시 실행하지 않는다. 일반 logcat 전체·화면 원문·좌표 dump·스크린샷은 수집하지 않는다.

## 13. 성공/실패 판정과 필수 로그

정상 전체 흐름의 성공은 다음 **모두**가 필요하다:

- SHOWN 및 필요한 음성 완료(엔진 receipt와 실제 청취는 별도)
- CHOICE_ACCEPTED, OVERLAY_REMOVAL PASSED, INTERACTION_END PASSED
- MANUAL_REFRESH COMPLETED, SNAPSHOT_MATCH/TARGET_MATCH PASSED
- 같은 새 표본의 eligibility 통과, B USER_CHOICE APPROVED/EXECUTE
- F9_VALIDATION PASSED, CLICK_ATTEMPT ATTEMPTED가 최대 한 번
- CLICK_RESULT의 실제 CLOSED_CONFIRMED와 원본 popup 소멸

다음은 안전한 차단 결과이며 전체 기능 성공은 아니다:

- OCCLUDING_WINDOW/SCENE_UNAVAILABLE/불완전/owner 불명/위험 콘텐츠/TTL/동의 철회
- 실제 native 제거 또는 전체 부재 미확인
- DELIVERY_TRUE라도 STILL_PRESENT/UNKNOWN, 단지 C만 사라진 경우

다음 실측에서 같은 attempt와 실제 순서로 보존할 고정 코드:

1. REVIEW admission/identity change, PRESENT_REQUESTED/SHOWN의 SUMMARY/PRESS_OFFERED
2. OWNERSHIP, DISPLAY_REVIEW의 POINT/DETAIL 및 LIVE_COUNT/QUERY/SCAN/NATIVE_RELATION
3. A_F9_READ의 PURPOSE/STAGE/DETAIL/RELEASE 및 OCCLUDER_TYPE/LAYER/OVERLAP/PASSIVE_BAR/**FRAME_SHAPE**
4. CLEANUP PHASE=FIRST/SITE/ISSUE/BARRIER/NATIVE와 CLEANUP_EVENT RESULT, OVERLAY_REMOVED
5. SPEECH READY/COMPLETED/STALE/CANCELLED/FAILED
6. ACTION의 새 F0·snapshot/target·B·eligibility·F9·attempt/result 전체

SYSTEM+TOP_EDGE_STRIP도 bar identity 인증은 아니다. 다른 scan/서비스 세대의 OWNED나 VALID를 한 시도의 실행 증거로 합치지 않는다.

## 14. 최종 검증 및 변경 목록

최종 코드의 실제 실행 결과와 정확한 파일 목록은 아래 완료 기록에 추가한다. 삼성/LG/에뮬레이터 계측은 미실행이다.

### 14.1 최종 실행 결과

| 검증 | 최종 결과 |
|---|---|
| 신규 회귀 | 33개: boundary 12 + sequence 15 + 기존 occlusion suite 추가 6 |
| 앱 Debug 전체 JVM | 1,237개, 실패·오류·skip 0, 이번 최종 Gradle에서 실행 |
| 앱 Release 전체 JVM | 1,222개, 실패·오류·skip 0, 이번 최종 Gradle에서 실행 |
| 호스트 / 합성 앱 JVM | 기존 55개 / 26개 통과 결과 UP-TO-DATE 재사용 |
| 앱 Debug/Release APK | 빌드 성공 |
| 앱 AndroidTest APK | 컴파일·빌드 성공, 기기 실행 아님 |
| 호스트 Debug/AndroidTest 및 합성 앱 Debug | 기존 산출물 UP-TO-DATE 검증·재사용 |
| 앱 Debug/Release 및 호스트 Lint | XML issue 0 |
| APK/DEX 격리 | 기존 검증 도구 통과, JVM 신규 회귀 클래스는 앱 DEX에 없음 |
| Release 진단 | A_F9_READ/A_MANUAL_PROMPT/A_MANUAL_REVIEW/FRAME_SHAPE=/A_MANUAL_CLEANUP_EVENT 문자열 부재, Debug에는 존재 |
| 공백 | 기존 CRLF를 유지한 diff 검사 및 신규 파일 별도 검사 통과 |
| 삼성/LG/에뮬레이터 | 연결 기기 없음, 모두 미실행 |

variant 공통 회귀가 중복되므로 Debug/Release 수치를 고유 테스트 총개수로 합산하지 않는다. 기존 C 테스트의 coroutine opt-in 컴파일 경고는 유지하며 다른 담당자 소스를 수정하지 않았다.

최종 명령(JDK 17, SDK의 로컬 compile platform 사용; 삼성 runtime 버전 검증이 아님):

```bash
JAVA_HOME=/workspace/jdk17 ANDROID_HOME=/workspace/android-sdk ./gradlew --offline --no-daemon --max-workers=1 :app:testDebugUnitTest :app:testReleaseUnitTest :capture-test-host:testDebugUnitTest :popup-test-app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest :popup-test-app:assembleDebug :app:lintDebug :app:lintRelease :capture-test-host:lintDebug
python3 tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff --check
```

최종 전체 검증 log는 프로젝트 밖 `/workspace/a-p01-root-verified-full.log`다. 앞선 전체 검증 후 receipt 콜백까지 오류 경계에 포함하고 다시 실행했다. `/workspace/a-p01-root-full.log` 및 `/workspace/a-p01-root-final-full.log`를 최종 소스의 검증으로 대신 사용하지 않는다. 최종 완료 시간과 task 결과는 아래 마지막 확인에 기록한다.

### 14.2 변경 파일 전체 (14개)

기존 수정 11개:

1. `app/src/debug/java/kr/closeforyou/accessibility/integration/ManualPromptLog.kt`
2. `app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt`
3. `app/src/main/java/kr/closeforyou/accessibility/action/AndroidF9ScreenPort.kt`
4. `app/src/main/java/kr/closeforyou/accessibility/action/F9ReadDiagnostics.kt`
5. `app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceConnection.kt`
6. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualOverlayDiagnostics.kt`
7. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualPopupConnection.kt`
8. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualPromptSpeech.kt`
9. `app/src/main/java/kr/closeforyou/accessibility/integration/OverlayEventDiagnostic.kt`
10. `app/src/test/java/kr/closeforyou/accessibility/action/F9OcclusionDiagnosticsTest.kt`
11. `app/src/test/java/kr/closeforyou/accessibility/integration/OwnedOverlayWindowReadTest.kt`

신규 3개:

12. `app/src/test/java/kr/closeforyou/accessibility/integration/ManualP01BoundaryRegressionTest.kt`
13. `app/src/test/java/kr/closeforyou/accessibility/integration/P01SamsungSequenceRegressionTest.kt`
14. `docs/A_P01_SAMSUNG_ROOT_CAUSE_AND_REMEDIATION_2026-10-30.md`

전체 변경은 A 소유 경계·시험·문서다. B/C/D 및 공통 계약 수정 없음. HEAD는 시작 SHA와 동일, index 비어 있음. 다른 worktree 9개는 HEAD/status/기존 미커밋 파일 SHA-256을 기존 보존 기록과 대조해 일치했다.

## 15. 후속 검증에서 놓치면 안 되는 추가 조건

SYSTEM 문제가 해결돼도 다음 gate가 통과됐다고 가정하지 않는다. 특히 Android가 modal 뒤 배경 page를 창 목록에서 숨겨 앱 창 하나만 반환하면, `F9AutoCloser.matches()`는 popup 이전에 독립 수집된 같은 앱 page proof의 원래 5초 시각·다른 window ID·digest를 요구한다. 6초 기다린 뒤 그 과거 page가 만료된 경우에도 backdate/임의 새 proof를 만들지 않는다. 현재 배경을 완전하게 검증하지 못한 경로는 계속 차단된다. 기존 absent/expired page regression도 전체 실행했다.

이 작업의 정상 JVM은 **원래 배경 page와 popup이 모두 읽히는 합성 조건**을 포함한다. 실제 삼성에서 다음 ROOT/TREE/ENDPOINT/background/focus/TTL gate까지 통과할지는 아직 미검증이다. 실제 폰에 연결하지 않은 상태에서 'SYSTEM만 허용하면 P01이 반드시 닫힌다'고 결론 내리지 않는다.

남은 완료 차단은 SYSTEM 창의 신뢰 가능한 실제 역할/가림 증거와 초기 remote/native 동시성, 실제 전체 읽기 및 물리적인 닫힘이다. 이번 A 경계 수정은 이를 우회하지 않고 실패·취소·오래된 결과가 정상 완료로 처리되지 않도록 보완한 것이다.


### 마지막 확인

최종 소스 고정 후 `BUILD SUCCESSFUL in 3m 26s` (250 tasks: 38 executed, 212 up-to-date). 신규 33개를 포함한 Debug 1,237개/Release 1,222개 모두 실패·오류·skip 0이다. 최종 APK/DEX/Release 문자열/Lint/공백 및 다른 worktree 보존 검사도 다시 통과했다. HEAD `2c6ba1a76371a35c2b43cf005990fd1e969af02a` 유지, 기존 수정 11개/신규 3개, staged 없음. 커밋·푸시·PR 변경·병합·폰/에뮬레이터 실행 없음.
