# P01 표시 전 안정성 관측 및 수명 경계 검토 — 2026-10-31

> **2026-10-31 관측 기반 Debug 표시 계약 명시적 채택: 계약 분리 및 로컬 검증 완료.**
> 1~11절은 이전 구현·독립 리뷰 기록이다. 11절의 실패 회귀를 보존하고 STRICT_GENERATION_REQUIRED와 OBSERVATION_ONLY_DEMO를 분리했다. 최종 결과와 한계는 12절을 따른다.

기준: `fix/a-samsung-p01-lifetime-20261028`, `d9a3e48d89f8df70c4143f8b076704c7d003db6f`.
이 문서의 삼성 기록은 사용자 제공 실측이다. 이번 Linux 작업에서는 삼성·LG·에뮬레이터를 실행하지 않았다.
커밋·푸시·병합하지 않는다. 기존 구조 검토 문서와 다른 9개 worktree를 보존한다.

## 1. 결론과 범위

기존 후보를 움직인 버튼에 맞추어 유지하는 A안은 이번 증거와 맞지 않는다. 최초 거부 TextView뿐 아니라 닫기 후보와 다른 상호작용 노드도 이동했다. 기존 표시 거부는 타당하다.

B안의 **제한된 Debug rollout**을 구현했다. 독립적으로 경계가 잡힌 APPLICATION 팝업에 대해, 후보 발행 전에 실제 F0 collector를 새로 호출한다. 최대 3개의 추가 읽기 중 연속한 두 fresh 결과가 전체 geometry·내용·구조·native action 집합까지 정확히 일치해야 마지막 실제 수집 객체를 선택한다. 초기/중간 geometry는 버리고 그 마지막 객체에서 서비스 F1 → evidence → B → 기존 C 표시 Gate를 새로 진행한다.

이는 애니메이션 종료나 Android 큐 배출을 증명하는 기술이 아니다. 마지막 관측 직후/C 생성 중/생성 후 변경은 여전히 가능하며, 기존 무효화·실제 재검증·제거로 처리한다. **삼성의 약 139ms 문제 해결, C 실제 렌더링, 6초 유지, 음성 청취, 실제 닫힘을 입증하지 않는다.**

Release에는 이 추가 관측과 Debug 표시 정책을 활성화하지 않는다. 자원 정리·취소 전파를 안전하게 만드는 collector 수정은 공통 적용된다. B/C/D·공통 모델·합성 앱·P23 브랜치는 변경하지 않았다.

## 2. 공식 자료와 외부 조사

### 공식 API 계약 / AOSP 원문으로 확인한 사항

이번 네트워크 정책에서 developer.android.com 직접 접속과 웹 검색 도구가 실패했다. 공개 API 설명은 아래 공식 URL을 연결하고, 실제 구현 확인은 접근 가능한 AOSP 공식 mirror의 **android-15.0.0_r1** 원문으로 수행했다. 이 버전은 삼성 기기 내부 빌드와 같다는 의미가 아니다.

| 대상 | 확인한 동작 | 적용·한계 |
|---|---|---|
| [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) / [AccessibilityEvent](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent) | 창/노드 조회와 이벤트 수신은 별개 경계다. `notificationTimeout=0`도 원격 View traversal과 서비스 callback의 원자성을 만들지 않는다. | 이벤트 수신 시각만으로 화면 변경의 원인을 확정하지 않는다. |
| [WINDOWS_CHANGE_BOUNDS](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent#WINDOWS_CHANGE_BOUNDS) | 창의 영역 변경 알림이며, Android R 이후 영역 변화는 사각 bounds 자체가 같아도 발생할 수 있다. | BOUNDS mask를 순수 이동의 증거나 이벤트 무시 권한으로 쓰지 않는다. |
| [AccessibilityWindowInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo) / [AccessibilityNodeInfo.refresh](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#refresh()) | `refresh()`는 원격 View 최신 상태를 요청하고 obsolete이면 false를 반환한다. 공개 API는 원자적 전체 화면 snapshot/native ABA 인증을 제공하지 않는다. | 실제 각 노드를 refresh하되 실패·시간 초과·ID 변경은 폐기한다. |
| [Dialog.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/app/Dialog.java#L311) | `show()`는 decor를 `WindowManager.addView`에 전달한 뒤 showing 상태를 설정한다. | 반환만으로 최종 layout/draw 완료라고 해석하지 않는다. |
| [Window.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/Window.java#L1144) / [setLayout](https://developer.android.com/reference/android/view/Window#setLayout(int,int)) | width/height 속성을 바꾸고 attribute 변경을 전달한다. | show → setLayout은 서로 다른 갱신 단계다. |
| [ViewRootImpl.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/ViewRootImpl.java#L2888) | traversal을 Choreographer callback에 예약한다. | main dispatcher 한 turn만 지났다고 다른 프로세스 traversal이 완료됐다고 하지 않는다. |
| [AbstractAccessibilityServiceConnection.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java#L1758) | 접근성 이벤트를 복사하여 handler에 전달한다. | 큐/원격 호출 경합 및 늦은 이벤트가 가능하다. 우리 이벤트 timeout은 변경하지 않았다. |
| [AccessibilityInteractionClient.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityInteractionClient.java#L604), [AccessibilityNodeInfo.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityNodeInfo.java#L1196), [AccessibilityCache.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityCache.java) | 일반 조회에는 캐시가 있으며 public `refresh()`는 이 AOSP 버전에서 bypassCache=true로 조회한다. 캐시는 수신 이벤트로도 갱신된다. | 새로운 DTO/토큰만 만든 것을 독립된 원격 읽기라고 부르지 않도록 실제 refresh를 추가했다. 캐시 전체 비활성화나 hidden API는 쓰지 않는다. |
| [WindowManagerGlobal.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/WindowManagerGlobal.java#L536) | removeView는 ViewRoot의 die 처리와 연결되고 deferred 제거 상태도 존재한다. | removeView 반환/HIDDEN만으로 실제 native detach·창 목록 부재를 인증하지 않는다. |

### 다른 개발자의 사례

GitHub 공개 issue 검색을 실제 수행했다. [Appium uiautomator2 #730](https://github.com/appium/appium-uiautomator2-server/issues/730)은 외부 컴포넌트가 바뀌어도 cached 정보를 관측한다는 보고이며, [#477](https://github.com/appium/appium-uiautomator2-server/issues/477)은 보관한 요소가 더 이상 현재 트리에 없을 때의 실패 보고다. 이는 Android 자동화 클라이언트에서 최신성/캐시를 주의해야 한다는 참고 사례다. 삼성 P01의 재현·해결 사례로 사용하지 않았다. Appium 내부 cache-clear/idle 설정을 우리 접근성 서비스의 안전 해결책으로 가져오지 않았다.

Stack Overflow 검색 및 직접 공식 문서 접속은 이 환경에서 완료하지 못했다. 삼성 One UI의 동일 원인이라고 검증된 공식 자료나 해결 사례도 확보하지 못했다. **OEM 버그로 확정하지 않는다.** 관련성이 낮은 검색 결과는 근거에서 제외했다.

## 3. 실제 증거와 원인 수준

사용자 실측 d9a3e48:

- 03:33:31.692 SHOWN → 03:33:31.823 BOUNDS → 03:33:31.831 재검증/제거 요청.
- `POLICY_INTERACTIVE_BOUNDS`, `NONCLICK_NATIVE_CAPABILITY`, `NATIVE_ACTION`.
- `TEXTVIEW_LEAF`, `NAVIGATION_AND_SELECTION_STATE_AND_TEXT_NAVIGATION_SELECTION`.
- `CLOSE_GEOMETRY=CHANGED`, `OTHER_OPERABLE_GEOMETRY=CHANGED`.
- 별도의 Cleanup `RETIRED_ID_EVENT / ADDED`.

**확정:** 표시 함수 반환 후 원본/재수집의 여러 노드 geometry가 달랐다. 해당 후보를 그대로 유지/실행하면 안 된다. 이 TextView의 action 프로필이 일반 플랫폼 동작과 양립한다는 사실은 닫기 버튼 이동까지 무해하다는 증거가 아니다.

**코드로 확인한 구조적 공백:** 이전 Gate는 Handler turn → fresh F0의 원본 지문/창 비교 → Handler turn을 사용했다. 독립된 fresh 전체 노드/native action 집합의 연속 일치를 후보 발행 전에 요구하지 않았다. 최초 수집은 active root, 표시 후 F9 read는 target window.root를 사용할 수 있었다. 창 ID/package/flags/layer 일치는 같은 시점·같은 native root 세대·전체 트리 동등성 인증이 아니다.

**미확정:** Dialog.show → setLayout의 traversal, 늦은 이벤트, C addView 영향, active-root/window.root 표현 차이, OEM 캐시, 창 변형 중 어느 것이 삼성 결과를 일으켰는지. 새 구현도 native 동일 객체 세대/원자적 화면을 인증하지 못한다.

`ChoicePresentationGate.eventPhase()`에는 관측 interval을 비교하는 기존 보조 코드가 있지만, 실제 `ManualPopupConnection.offer()`는 `prepare(snapshot, permit)`으로 호출하여 initial interval을 제공하지 않는다. 따라서 이 구현만으로 이벤트가 마지막 전체 수집보다 전인지 후인지 확정할 수 없다. 새 코드도 capturedAt이나 SHOWN 시각으로 interval을 만들어 채우지 않는다. 늦은 이벤트를 무시하는 규칙을 추가하지 않고, 현재 전체 팝업 재검증으로 처리한다.

### 실제 호출 순서

| 순서 | 코드·실행 경계 | 증거와 실패 처리 |
|---|---|---|
| 1 | `PopupScenarioActivity.labeledAdDialog` → `showDialog` | LinearLayout + TextView 2 + Button 2. Dialog.show 후 setLayout. 합성 앱 원문은 수정하지 않음. |
| 2 | Android traversal / 접근성 이벤트 큐 | 두 프로세스 사이에 원자적 순서 보장이 없음. |
| 3 | 서비스 `onAccessibilityEvent` | 기존 화면 증거를 무효화, 기존 180ms debounce. 터치/스크롤/정책 변경은 그대로 취소. |
| 4 | `scheduleCapture` → cleanup permit → collector initial | 기존 대상·배경 F0 검사. 초기 결과는 아직 Runtime.begin/B/C 후보가 아님. |
| 5 | Debug 별도 창 scope probe → `PrePresentationStability.collect` | scope probe의 detector는 서비스 baseline을 바꾸지 않고 그 signal도 전달하지 않음. 추가 읽기 최대 3회, 전체 추가 관측 100ms. |
| 6 | 각 Handler post → 실제 collector 호출 | post는 취소·이벤트 기회일 뿐 표본이 아님. 각 원격 노드 refresh + 두 F0 순회 + endpoint/root-source/frame/window 검사 및 해제 완료 결과를 비교. |
| 7 | 연속 fresh 두 결과 정확히 일치 | 마지막 actual Ready만 반환. 초기/중간 후보 geometry 재사용·token/capturedAt 복사 없음. 내용/구조/action/다른 창 변경은 그 작업을 종료. |
| 8 | 서비스 detector.detect(final.frame) | F1을 마지막 frame에서 실제 실행. 신호가 없으면 C로 안 감. |
| 9 | Runtime.begin(final.token), 원래 5초 TTL → evidence | 기존 F0/evidence/OCR 정책. 추가 OCR/캡처 활성화 없음. |
| 10 | Runtime.publish → F9CollectedSource → Manual.offer → 실제 B wiring | 최종 snapshot/analysis/detection token 결합을 그대로 사용. B 후보를 이전 tree에서 복사하지 않음. |
| 11 | ChoicePresentationGate의 기존 두 posted turn과 final F0 | 원본 full snapshot과 독립 새 read의 exact nodes/windows/actions를 비교. Gate 자체는 계속 node-free 비교 baseline을 보관하며 이미 소유한 Manual.original을 callback으로 사용. |
| 12 | 최종 Gate 실패 | Debug에서는 후보를 retire하고 새 실제 이벤트/확인된 cleanup 전까지 자체 재수집을 예약하지 않음. Release의 기존 회복 동작 유지. |
| 13 | C present/add → SHOWN → TTS | SHOWN은 함수 결과이며 draw/사용자 청취 확인이 아님. 30초 display lease와 5초 execution evidence는 기존대로 분리. |
| 14 | 표시 후 BOUNDS/CONTENT/STATE 등 | 이벤트 시각만으로 무시하지 않음. 실행 증거를 먼저 폐기하고 실제 DISPLAY_REVIEW. 새 경로에서는 geometry 예외로 움직인 버튼을 유지하지 않음. |
| 15 | 실제 변경/불확실 | 기존 선택창·TTS 제거/중단. SAME일 때만 불필요한 제거를 피함. |
| 16 | dismiss listener → retiring → removeView return/detach → handler/native callback | Cleanup permit을 되돌리려면 기존 정확한 세대·native detach·전체 창 부재 검사가 필요. ADDED/HEALTH 우회 없음. |

## 4. 구현한 안정성 계약

### 읽기·비교

`PrePresentationStability`는 coroutine 호출 하나에만 존재하는 지역 관측 절차다. 전역 상태 기계, 자동 재시도, 임의 delay, 새 이벤트 주입은 없다.

- 적용 범위: Debug이고 기존 F1 로직의 독립 bounded application-window popup scope가 확인된 경우. 비팝업 페이지/별도 scope가 아닌 기존 경로에는 반복 수집을 추가하지 않는다.
- 초기 seed + 최대 3 fresh. **fresh와 fresh**가 연속 일치해야 하므로 최소 추가 읽기는 2회다. seed와 fresh 한 번 일치만으로 통과하지 않는다.
- 추가 관측 전체 100ms(큐 대기·비교·정리 포함), 각 collector의 기존 100ms/2,400 visits도 유지한다. 원본 seed의 5초 경계도 계속 검사한다.
- 서로 다른 actual snapshot 객체·token, 수집 호출 구간 안의 수집 시각, 동일 서비스 owner/epoch/cleanup permit/동의/허용 앱/사용자 비조작을 요구한다.
- 두 F0 pass의 모든 방문 노드에 실제 public refresh를 적용한다. 보호 대상/같은 앱 배경의 숨김 자식을 생략하지 않는다. 실패한 refresh, package/window 혼합, 한도·시간 초과는 전체 폐기.
- snapshot 종료에 active root와 별도 target window.root의 package/window/bounds가 원본 DTO ROOT와 일치하는지 추가 확인한다. 서로 다르면 표시 전에 거부한다. root를 교체·보정·확대하지 않는다. geometry reader와 전체 window DTO도 다시 확인한다.
- 각 결과의 frame/window/outline은 그 snapshot의 geometry와 결합되어 있어야 한다. 다른 frame으로 F1을 실행하지 않는다.
- 전체 node 수·순서·path·viewId·class·text·description·clickable·enabled·DTO action, full native known action set을 비교한다. unknown/custom/라벨 있는 native action, 잘못 결합된 sidecar는 거부한다.
- 양수 geometry, frame/target/parent 포함, canonical parent path, 중복 window/path, 다른 활성·가림·입력·접근성 overlay 창을 검사한다. 같은 package/window ID만으로 승인하지 않는다.
- 내용·구조·action 변경은 즉시 종료한다. geometry만 바뀌면 이전 표본은 비교용으로만 버리고 제한된 다음 fresh를 읽는다. 이 단계에는 보존할 B 닫기 후보 자체가 아직 없다.
- 각 실제 fresh 수집은 별도 CaptureTrace/ATTEMPT를 사용한다. 마지막 실제 수집의 trace만 이후 F1/evidence 진행에 연결하며 초기/중간 표본의 진단 진행을 섞지 않는다.
- 같은 앱·창·본문 문맥에서 geometry 지문만 달라져도 회복 예산이 초기화되던 경로를 Debug에서 제한한다. 첫 표시+회복 1회 상한을 유지한다. 이 key는 거부 예산에만 사용하며 팝업 동일성 승인에 사용하지 않는다. class/action만 다른 실제 새 팝업도 같은 문맥 예산에 포함되어 보수적으로 추가 표시가 거부될 수 있다. 다른 앱·창·본문 문맥은 새 실제 수집·판정 후 별도로 검토된다.
- 최종 F0 Gate도 full nodes/native sets를 검사한다. 표시 이후는 Debug strict native set 비교를 추가하고, 기존 Demo translation/reflow 유지 callback을 연결하지 않는다. 예전 정책 함수/회귀는 삭제하지 않았지만 B rollout runtime의 버튼 이동 유지에 사용하지 않는다.

### 실행과 분리

새 snapshot의 token/시각은 actual collector가 새로 생성한 것이며 이전 snapshot의 값을 수정하지 않는다. 실제 새 증거가 자기 수집 시각부터 갖는 5초를 이전 후보 TTL 연장이라고 처리하지 않는다.

표시 성공은 실행 허가가 아니다. 기존 `ManualChoiceRefresh.afterRemoval` → 새 F0 전체 트리/geometry 동일성 → B 정책 → 수동 Eligibility → F9 PRE_CLICK/SCREEN_MATCH → 최대 1회 클릭 → 원본 팝업 소멸 확인 경로를 수정하지 않았다. 표시 후 변경 시 Runtime/F9 실행 증거를 복구하지 않는다. 자동 실행·자동 SKIP·F12 반복을 활성화하지 않았다.

### 정리·취소

추가 refresh/endpoint root를 넣으며 기존 collector의 `finally`에 있던 예외 경계도 바로잡았다. 기존 공용 A `CollectionResourceSafety`/`CollectionReleaseBatch`를 재사용한다.

- capture cancellation은 일반 Blocked로 변환하지 않는다.
- root/window 모두 독립적으로 해제를 시도하고 같은 실제 handle의 중복 release를 막는다.
- 정상 Ready 뒤 정리 실패는 Blocked. 이미 나온 첫 Blocked 사유는 ordinary cleanup 실패로 덮지 않는다.
- primary 예외/취소와 cleanup 취소의 우선순위는 기존 공용 helper 계약을 따른다.
- child release도 같은 helper를 사용하여 검사 예외를 ordinary release 예외로 가리지 않는다.

이는 기존 검사를 줄이는 성능 최적화가 아니다. refresh는 추가 원격 요청이다. 최악의 처리 비용/대상 기기에서의 성공률 개선을 측정했다고 주장하지 않는다. 동기 Binder 호출을 100ms 순간에 강제 중단할 수는 없으며, 반환 후 경계를 초과한 결과는 승인하지 않는다.

## 5. 경쟁 상태와 안전한 최종 결과

| 경우 | 결과 |
|---|---|
| seed 이후 geometry 변경 → fresh 둘 일치 | seed 폐기, 최종 actual fresh에서 F1/evidence/B 새 판단 |
| fresh 1→2 사이 변경 | 세 번째 제한된 읽기까지 검토. 연속 일치가 없으면 종료 |
| 내용/버튼/action/window/epoch 변경 | 그 관측 작업 거부. 실제 후속 이벤트만 새 검토를 시작할 수 있음 |
| 원격 refresh가 obsolete/캐시 이전 결과/불완전 정보를 반환 | false/비교 불일치/unknown는 거부. framework가 서로 일치하는 오래된 결과를 반환하는 것까지 완전 판별한다고 주장하지 않음 |
| 마지막 비교 뒤/C 전 변경 이벤트 | 기존 Gate/currentness/취소로 표시 거부 |
| 실제 변경 이벤트가 늦게 C 후 도착 | 시각만으로 무시하지 않고 기존 실제 DISPLAY_REVIEW. 변경이면 제거·실행 폐기 |
| C 생성 중/SHOWN 뒤 버튼 이동 | 기존 표시 후보를 그대로 유지하지 않음. cleanup 성공 뒤에만 새로운 actual collection 가능 |
| 다른 앱·입력창·가림 개입 | 새 관측 거부 또는 기존 표시 무효화; 클릭 0회 |
| 터치·스크롤·동의 철회·재연결 | old coroutine/token/permit 무효화. 늦은 posted callback은 복구하지 못함 |
| cleanup pending/health failed | 새 수집·표시 admission 차단 |
| 선택 순간 팝업 변경 | 독립 fresh F0/B/F9에서 차단. 표시 안정화 성공으로 실행 검사를 대체하지 않음 |
| TTS 시작과 제거 교차 | 실제 변경이면 중단이 정상. 안정된 실제 C Flow 테스트에서는 TTS 완료, 단일 표시, 클릭 0회를 확인 |

## 6. Cleanup 독립 분석

`OverlayCleanupBarrier.observeWindowEvent`는 서비스 일반 이벤트 처리 **이전**에 실행된다. native return+detach 이후라도 window 목록이 PRESENT이면 `WAITING_WINDOW_ABSENCE`로 남는다. 이어진 WINDOW_STATE는 정확한 native 세대/ID의 wait-only로 처리될 수 있지만, pure ADDED는 그 허용 범주가 아니다. 같은 retiring ID의 ADDED는 `RETIRED_ID_EVENT`로 최초 실패를 확정하고 HEALTH로 이어진다. 이 이후 뒤늦은 REMOVED/absence가 도착해도 실패를 복구하지 않는다.

이번 신규 순서 회귀는 WINDOW_STATE(wait) → ADDED(reject) → REMOVED → 실제 absence 순서를 확인하며, 끝까지 새 presentation permit이 없음을 확인한다.

AOSP에서 제거와 이벤트 전달이 비동기적이라는 사실은 ADDED가 반드시 과거 생성 알림이라는 인증이 아니다. 현재 로그에는 event time/native generation/source를 결합하여 ID 재사용·재등장을 배제할 충분한 증거가 없다. 따라서 **Cleanup 운영 로직은 수정하지 않았다.** ADDED를 제거 완료로 인정하지도 wait-only로 새로 확장하지도 않았다. 이 문제는 삼성에서 여전히 발생할 수 있고 선택 후 fresh 수집/다음 표시를 차단한다.

## 7. 회귀와 검증 기록

새 fixture는 P01의 중첩 ROOT/container, TextView 2개, Button 2개를 반영한다. TextView의 navigation/selection/text-navigation native action 조합도 넣었다. 좌표는 순수 합성 값이며 삼성 좌표가 아니다. framework 노드/Binder/Choreographer/실제 TalkBack/OEM 캐시를 JVM에서 재현했다고 주장하지 않는다.

공유 새 경로 회귀:

- 연속 실제 read 객체/새 token/수집 시각, seed 재사용·같은 token·잘못된 metadata 거부.
- show→setLayout을 모델링한 ROOT/window 확장 + TextView/두 버튼 이동 후 새 stable 후보 생성.
- ROOT resize/전체 translation/passive reflow/각 버튼 이동이 계속되면 bounded 거부.
- 내용·경로·class·ID·상태·노드 수·action·창/epoch/frame/layer·가림 교체 거부.
- F1 frame/outline과 최종 snapshot 결합 불일치 거부.
- final strict display read에서 동일 DTO여도 native action 변경/누락 거부.
- 100ms/5초 만료·취소·예외·로그 실패·post 실패·late callback 거부.
- refresh 호출 순서·obsolete/ID 변경·기한·취소, primary 오류/전체 자원 정리 보존.
- 실제 기존 B attacher/C Flow/keeper/TTS에 안정화 결과를 연결하여 표시 1회·음성 완료·자동 클릭 0회.
- 안정화 뒤 실제 변경은 C/TTS 제거. pending cleanup은 다음 admission 차단.
- 기존 ManualChoiceRefresh/B/F9/SCREEN_MATCH 및 위험 화면·중복 클릭 회귀를 전체 실행으로 재검증.

실제 발견한 로컬 실패와 처리:

1. 새 suspend 함수의 finally 보고에서 inlined callback 예외가 반환을 방해하는 회귀가 실패했다. non-inline 보고 경계로 격리하여 sink 실패가 승인/거부/취소에 영향을 주지 않게 수정했다.
2. 새 fixture의 `List.removeLast()`가 SDK35/JDK17에서 `NoSuchMethodError`를 일으켰다. `removeAt(lastIndex)`로 변경했다. 운영 수집 실패가 아니다.
3. 새 C sequence fixture가 서로 다른 frame 크기를 섞고 제거 전 `retirementWindow`를 참조하여 테스트가 실패했다. source에 맞춘 scene과 실제 live fixture ID를 사용하도록 harness를 수정했다. 안전 검사를 완화하지 않았다.
4. 첫 전체 명령에 시험 앱 `assembleRelease`를 요청했지만 해당 모듈은 의도적으로 Release variant를 만들지 않아 task 조회가 실패했다. 앱 Release/합성 Debug/host Debug의 실제 존재하는 task로 다시 검증한다. 시험 앱 Release 설정을 변경하지 않았다.
5. 별도 안전 리뷰에서 final Gate 거부 뒤 기존 자체 review 재시작이 geometry 지문 변경과 겹치면 bounded 관측을 반복할 수 있음을 확인했다. 새 Debug 경로는 거부 시 후보를 폐기하고 자체 재시작을 하지 않도록 수정했다. 또한 동일 표본 반복 정규화 비용을 줄이되 검사는 생략하지 않았다. 해당 수정 이후 전체 검증을 다시 실행한다.

최종 결과는 아래 10절에 기록한다. trace 분리·회복 예산 제한·가상 시계 수정까지 포함한 마지막 전체 실행 결과이며, 중간 실행 수치로 대신하지 않았다.

6. Debug 전체 1,533개 실행 중 기존 `NativeOverlayCleanupTest.removalReturnOrAbsenceWithoutActualDetachCannotRelease`가 1회 실패했다. `runTest`와 barrier의 실제 10ms clock을 혼용하여 시스템 부하가 deadline을 먼저 소진한 것이다. 테스트의 clock만 같은 `testScheduler.currentTime`에 연결했다. 운영 timeout/판정은 변경하지 않는다. 해당 Debug/Release 대상 및 전체 회귀를 다시 실행한다.

7. 구조 지문에는 bounds가 포함되어 있고 기존 ChoiceReviewBudget은 그 지문 변경마다 offered를 초기화했다. 새 경로의 반복 C 생성/geometry 변화가 복구 예산을 계속 열 가능성을 회귀로 재현했다. Debug 연결에서만 동일 앱·창·본문 문맥의 구조 지문 변화로 예산을 초기화하지 않도록 기존 budget을 재사용해 제한한다. 이는 추가 거부이며, 새 구조/버튼을 기존 팝업이라고 승인하지 않는다. Release는 기존 budget을 유지한다.

## 8. 별도 안전성 검토

- 이전 snapshot/token/시각의 최신화·복사: 없음. actual 새 collector 결과만 사용.
- 움직인 버튼을 원본으로 인정: 없음. 발행 전에는 새 B 후보를 만들며 표시 후는 strict 비교.
- package/window ID만으로 동일성: 없음. 전체 DTO/native set/geometry/창/부모 그래프 비교.
- action sidecar 혼합: 객체별 `observedSummary` 결합 검증, 누락/불일치 거부.
- 동일 ID의 다른 팝업: 관측 가능한 내용·구조·상태·action/geometry가 다르면 거부. 완전히 동일하게 위장한 native ABA의 의미까지 인증하지 못함.
- C를 외부 팝업으로 처리: pre-show 관측에서 접근성 overlay/다른 가림 창 거부. 실제 C 이후는 기존 native identity/registration 검증 유지.
- display lease와 execution TTL: 변경 없음. 수동 선택은 제거 완료 이후 새 F0/B/F9를 요구.
- 취소 뒤 callback: 기존 cancellable post가 제거하며 inactive continuation은 resume하지 않음.
- cleanup failure 무시: 없음. barrier/HEALTH 유지.
- Release Debug 예외: feature=false/stub. 표시 후/최종 Gate도 기존 strict 정책, 추가 읽기 rollout은 Debug만.
- fixture 과장: synthetic values/remote port 대체를 명시. 현장 성공 근거로 사용하지 않음.

검토 범위에서 새 표시 승인을 위해 제거한 기존 안전 검사는 없다. 다만 아래 원천 한계는 남는다. 이 결과는 제품 전체의 P2 부재 인증이 아니다.

## 9. 남은 한계와 삼성 단일 시험

### 남은 한계

- 동일하게 읽힌 두 원격 상태가 이후에도 안정적이라고 보장하지 못한다. C 생성이 새 layout을 유발한다면 pre-show 관측만으로 해결되지 않는다.
- 일반 active root와 window.root의 전체 native identity/캐시 세대를 인증할 공개 정보가 없다. 이번 witness는 불일치를 조기에 거부하는 역할이며 root-source 통합 성공을 선언하지 않는다.
- NodeSnapshot에는 모든 View 상태/실제 visible-to-user 정보가 들어 있지 않다. 기존 F9의 visibility/전체 tree/가림/보호 검사와 native ownership을 생략할 근거로 사용하지 않는다. actual F0 필드 getter의 플랫폼 일관성도 완전한 원자성 증거가 아니다.
- refresh가 많은 트리는 100ms 안에 통과하지 못할 수 있다. 처리 성능·성공률은 삼성/LG 미측정이다.
- 늦은 focus/action 변화, unknown/custom action, 외부창, 등록 미확인, SYSTEM 가림 등은 계속 거부할 수 있다.
- Cleanup RETIRED_ID_EVENT/ADDED/HEALTH, F9 pageBeforePopup TTL/SCENE_UNAVAILABLE/SCREEN_MATCH 차단은 별도 미해결로 남는다.
- 원본 F1 14개 UNOBSERVED와 P23 다른 브랜치의 실동작은 이번 JVM 결과로 해결됐다고 하지 않는다.

### 실측 시 한 번에 확인할 지표

Debug 신규 한 줄은 `A_PRESENTATION_STABILITY;RESULT=<고정 enum>`이다. 화면·창·노드 식별자나 시각/원문을 넣지 않는다. 한 관측 작업당 종료 1건이며, 로그 출력 실패는 무시한다. ATTEMPT 전 단계의 operation이므로 이후 ATTEMPT와 1:1 동일 시도라고 합치지 않는다.

- `STABLE_FRESH`: 두 actual fresh가 정확히 일치. 초기와도 같은 geometry.
- `STABLE_RECOLLECTED`: 초기/중간 geometry를 폐기한 후 연속 fresh 둘 일치.
- `UNSTABLE`: 최대 추가 읽기를 소진해도 연속 일치 없음.
- `IDENTITY_CHANGED`: 내용·구조·action·창·epoch/frame 등 불변 조건 불일치.
- `METADATA_UNAVAILABLE`: 구조/geometry/metadata/frame 결합 증명 실패.
- `UNAVAILABLE / DEADLINE / STALE / CANCELLED / FAILED`: 조회/시간/현재성/취소/예외 거부. 정확한 Android 세부 조회 실패는 기존 capture 진단과 함께 본다.

**단일 성공 판정은 STABLE_*만이 아니다.** 실제 F1 후보 → B/C SHOWN 이후 동일 대상에서 6초 이상 표시·TTS 완료가 현장에서 확인되고, 그동안 위험 클릭이 없어야 표시 문제 개선이라고 판단한다. STABLE_* 뒤 다시 TARGET_CHANGED/제거가 나오면 마지막 관측 이후 또는 C 생성에 연결된 변화 등 미확정 원인이 남은 것이다. 로그만 보고 C가 원인이라고 확정하지 않는다. ADDED cleanup 실패가 나오면 여전히 별도 장애다.

다음은 이 미커밋 수정본을 Windows에 별도로 전달한 뒤 사용자 PC에서만 실행할 명령 예시다. 이번에는 커밋·푸시하지 않으므로 GitHub의 d9a3e48을 pull하는 것만으로 새 구현을 받을 수 없다. 이 명령은 이번 작업에서 실행하지 않았다. 서비스 활성화/수집 동의/합성 앱 허용/분석 시작은 UI에서 직접 수행한다. 자동 실행 동의를 새로 켜지 않는다. P01 실제 닫기를 시험할 때는 명시적 사용자 선택 이후의 기존 검사 결과를 별도로 기록한다.

```powershell
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adbPath devices -l
$serial = '<삼성폰 serial>'
.\gradlew.bat :app:assembleDebug :popup-test-app:assembleDebug
& $adbPath -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath -s $serial install -r .\popup-test-app\build\outputs\apk\debug\popup-test-app-debug.apk
& $adbPath -s $serial logcat -c
& $adbPath -s $serial shell am start -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01
& $adbPath -s $serial logcat -v threadtime A_ManualPrompt:I '*:S'
```

원본 `PopupScenarioActivity.EXTRA_SCENARIO = "scenario"` 및 exported Activity 선언을 정적으로 확인했다. 로그는 위 고정 코드 tag로 한정하고 일반 전체 logcat/노드 dump를 공유하지 않는다. TalkBack ON/OFF 비교, 동일 조건의 LG 검증은 후속 사용자 기기 시험이며 이번에 수행하지 않는다.


## 10. 최종 검증 및 변경 파일

최종 실행: JDK 17, offline Gradle, `/workspace/a-p01-stability-final-all.log`.
`BUILD SUCCESSFUL in 3m 42s`, 260 tasks(29 executed, 231 UP-TO-DATE).

| 범위 | 실제 JVM 테스트 수 | 실패 / 오류 / skip |
|---|---:|---:|
| app Debug | 1,536 | 0 / 0 / 0 |
| app Release | 1,482 | 0 / 0 / 0 |
| capture-test-host Debug | 55 | 0 / 0 / 0 |
| popup-test-app Debug | 26 | 0 / 0 / 0 |

app 신규 회귀는 Debug/Release 각각 33개(공유 32개 및 variant별 1개)다. 같은 공유 테스트가 두 variant에서 실행되므로 두 합계를 서로 다른 현장 시험 횟수로 해석하지 않는다. host/popup 테스트는 `--rerun`으로 실제 재실행했다.

- app Debug/Release APK 및 popup Debug APK: 성공.
- app/host AndroidTest compile·APK assemble: 성공. 변경 없는 host compile 및 일부 package task는 UP-TO-DATE 재사용. Android 실행 결과가 아니다.
- app Debug/Release Lint: issue 0. host Lint: issue 0.
- popup Debug Lint: 성공, 기존 warning 2개. `TypographyFractions`(`strings.xml:66`), `SetTextI18n`(`ScenarioListActivity.kt:53`). 시험 앱/다른 담당자 소스는 수정하지 않았다.
- popup/host Release variant는 원래 비활성화되어 시험용 Release APK를 새로 만들지 않았다.
- `verify_capture_test_apks.py --release-apk ...`: 성공. host 독립 runtime, target-app/driver/host DEX 격리, instrumentation class 존재 확인.
- 새 안정화 로그 및 기존 native capability 로그 formatter: Debug DEX에 존재, Release DEX에 부재.
- Release 서비스 javap: `PrePresentationStability` 호출 부재. Release feature stub: Logcat/formatter 부재.
- 신규 unit/sequence test class가 Debug/Release 운영 APK에 없음을 실제 DEX class_def로 확인.
- Debug/Release merged manifest: INTERNET/ACCESS_NETWORK_STATE 없음. Manifest/마이크 설정을 변경하지 않음.
- `git diff --check`: 기존 CRLF의 CR을 후행 공백으로 표시하는 실패. `git -c core.whitespace=cr-at-eol diff --check`: 성공. 새 파일까지 별도 byte 검사하여 실제 space/tab 후행 공백 없음.
- cached diff 검사: 성공, index는 비어 있음. stage/commit/push하지 않음.
- 기존 CRLF 파일의 원래 CRLF 비율 보존. 다른 9개 worktree의 HEAD/status/기존 dirty file SHA256 및 구조 검토 문서 SHA256 보존 확인.
- 마지막 검증 시작 시 저장한 19개 code/test 파일 SHA256과 현재 값 일치. 이후 결과 문서만 갱신.

실행 명령:

```bash
JAVA_HOME=/workspace/jdk17 ANDROID_HOME=/workspace/android-sdk \
PATH=/workspace/jdk17/bin:$PATH ./gradlew --offline --no-daemon --max-workers=2 \
  :app:testDebugUnitTest :app:testReleaseUnitTest \
  :capture-test-host:testDebugUnitTest --rerun :popup-test-app:testDebugUnitTest --rerun \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  :popup-test-app:assembleDebug \
  :app:lintDebug :app:lintRelease :capture-test-host:lintDebug :popup-test-app:lintDebug
python3 tools/verify_capture_test_apks.py \
  --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
git -c core.whitespace=cr-at-eol diff --check
```

### 실제 변경 20개

- `app/src/main/java/kr/closeforyou/accessibility/AndroidSnapshotCollector.kt`
- `app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt`
- `app/src/main/java/kr/closeforyou/accessibility/action/AndroidF9ScreenPort.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ChoicePresentationGate.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceConnection.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ManualDisplayReview.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ManualPopupConnection.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/ChoicePresentationGateTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/ManualLiveSetCleanupDiagnosticsTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/NativeOverlayCleanupTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/P01SamsungSequenceRegressionTest.kt`
- `app/src/debug/java/kr/closeforyou/accessibility/integration/ManualPresentationStabilityFeature.kt`
- `app/src/main/java/kr/closeforyou/accessibility/PresentationNodeRefresh.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/PrePresentationStability.kt`
- `app/src/release/java/kr/closeforyou/accessibility/integration/ManualPresentationStabilityFeature.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/PrePresentationStabilityTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/PresentationNodeRefreshTest.kt`
- `app/src/testDebug/java/kr/closeforyou/accessibility/integration/PresentationStabilityDebugTest.kt`
- `app/src/testRelease/java/kr/closeforyou/accessibility/integration/PresentationStabilityReleaseTest.kt`
- `docs/A_P01_PRE_PRESENTATION_STABILITY_REVIEW_2026-10-31.md`

운영/variant 코드 11개, JVM 테스트 8개, 결과 문서 1개다. `ChoicePresentationGate`는 기존 Gate 본체의 await 순서를 바꾸지 않고 recovery budget만 보강했다. `NativeOverlayCleanupTest` 변경은 가상 시계 주입만이다. B/C/D·공통 모델·합성 앱·P23의 파일은 포함하지 않는다.

### 최종 판단

- 코드 수준: 제한된 Debug B안 구현 완료. 이전 geometry 승인을 넓히지 않고 실제 새 read/F1/B 후보 생성으로 연결했다.
- 로컬 검증: 위 최종 JVM/빌드/Lint/APK/DEX/공백 검사 완료. popup 기존 warning 2개는 보존.
- 별도 안전 리뷰: 이번 delta에서 새 P0~P2 승인 우회 경로를 발견하지 않았다. 회복 루프 상한을 보강하고 재검증했다. native ABA/원자성/실제 visibility 한계와 기존 F0 구현 전체의 완전성을 이번 패치가 인증하는 것은 아니다.
- 구현 단계에서는 로컬 Debug 범위의 커밋 검토 가능 상태로 보고했으나, 최종 독립 리뷰의 native 세대 거부 요건이 충족되지 않아 현재는 커밋 보류다(11절). 커밋·푸시하지 않았다.
- 삼성 실측: 로컬 APK 준비. Windows 전달·설치·실측은 미실행. STABLE_*와 실제 6초 유지·음성 완료를 함께 확인해야 한다.
- 미해결: C 이후 실제 변화/Root source 불일치/등록·캐시·가림/100ms 초과/동일 문맥 recovery 소진이면 계속 거부한다. 예산 공유 때문에 동일 앱·창·본문의 서로 다른 새 팝업도 보수적으로 추가 표시가 거부될 수 있다.
- Cleanup ADDED/HEALTH 문제와 수동 F9 별도 차단은 해결되지 않았다. 제거 완료·fresh F0/B/F9/SCREEN_MATCH를 모두 통과하기 전 클릭하지 않는다.
- main 병합, 자동 실행 확대, 삼성/LG/에뮬레이터 실행 없음. HEAD는 기준 d9a3e48 그대로다.


## 11. 정책 채택 전 독립 안전성 리뷰 — 커밋 보류 이력

이번 리뷰 시작 시 브랜치/HEAD가 기준 d9a3e48과 일치하고, 작업 파일 정확히 20개와 별도 보존 문서 1개만 존재함을 확인했다. 구현 단계 마지막 SHA256 목록과 19개 code/test 파일이 모두 일치하여 기존 검증 이후 숨은 변경은 없었다. 원본 20개 파일은 리뷰 시작 전에 저장소 밖 `/workspace/a-p01-b-independent-review-before-20261031`에 보존했다.

### P2-1: 관측 동등성과 실제 대상 native 세대의 증명 부재를 구별하지 않음

이번 최종 요청은 “구조·내용·native action이 같아도 실제 native 세대가 불확실하면 거부”를 요구한다. 현재 구현은 그 엄격한 조건을 충족하지 않는다.

- `PrePresentationStability.collect`의 동일 객체/재사용 token 거부는 **수집 DTO**의 독립성을 검사한다. `sameIdentityChecked`의 screenVersion/captureEpoch는 서비스가 수신한 이벤트 세대이지 원격 target popup/노드의 native 세대가 아니다.
- `PrePresentationStability.kt:75` 부근은 두 fresh DTO/window/structure/native action 값이 같고 `current()`이면 마지막 Ready를 반환한다. target native 세대 정보의 존재/완전성/동일성을 묻는 입력이나 거부 조건은 없다.
- `NativeNodeActions.kt:69` 이하의 owner/descriptor `===` 검사는 action 목록이 어느 DTO에 결합됐는지를 보장한다. 코드 주석대로 native-generation proof가 아니다. action UNKNOWN 거부와 **native generation 정보 자체의 부재**는 서로 다른 문제다.
- `AndroidSnapshotCollector.kt:169`의 UUID는 A가 매 수집마다 생성한다. 원격 popup의 물리적 세대 인증으로 사용할 수 없다.
- endpoint/target window.root 검사(`AndroidSnapshotCollector.kt:178`, `:185`)도 window ID/package/bounds/frame을 비교한다. refresh 성공은 해당 요청의 최신 상태 조회 보조 증거이지, 두 수집 사이 동일 native root 세대/ABA 부재 증명이 아니다.

**재현:** 실제 공유 `PrePresentationStability.collect`에 독립 객체·새 token·정확히 결합된 native action을 가진 두 동일 descriptor 결과를 전달하면 native 세대 증명이 전혀 없는 상태에서도 `STABLE_FRESH`로 수락한다. 새 `reviewMissingNativeGenerationProofMustNotGrantStableAdmission`은 최종 요청의 거부 조건을 assert하며 현재 Debug/Release에서 각각 실패한다.

이 재현은 fake read port를 사용한 **공유 판정 계약의 공백**이다. 삼성에서 실제 native 교체·캐시 재사용·위험 클릭이 발생했다는 재현이 아니다. 기존 수동 F0/B/F9 실행 게이트의 우회를 입증한 것도 아니다. 해당 불확실성을 최종 리뷰 승인으로 덮지 않는다는 뜻이다.

별도 characterization `reviewUnobservedRoundTripWithLateEventIsNotRepresentedByTheReadContract`는 event receipt가 없고 반환 DTO가 A로만 보일 때 숨은 A→B→A/native 세대 변화를 현재 입력 계약으로 판별할 수 없음을 모델링한다. 실제 Samsung native generation 값을 읽거나 확정했다고 주장하지 않는다.

### 100ms와 130~140ms의 구분

100ms는 최대 관측/승인 예산이다. 최소 안정 기간이 아니다. 두 fresh read는 예를 들어 40ms 안에도 일치할 수 있다. 따라서 첫 100ms가 같고 130~140ms에 바뀌는 현상을 배제하지 못한다. 동기 Android Binder 호출을 100ms 순간에 선점 중단하는 기능도 아니며, 반환 후 초과 결과를 거부하는 경계다.

추가한 결정적 회귀는 다음을 확인한다.

| 요청 시나리오 | 공유 경로와 결과 |
|---|---|
| 첫 100ms는 동일, 130/135/140ms에 변경 | 20ms씩 두 read 후 40ms에 통과해도, 뒤의 현재 대상 변경은 실제 `reviewManualDisplay`/`ManualDisplayContinuity`/C Flow에서 CHANGED·제거·클릭 0회 |
| 관측된 A→B→A→A | 마지막 실제 A snapshot만 선택하고 seed/B token을 재사용하지 않음. 물리적 세대 인증은 아님 |
| 관측되지 않은 왕복 + 늦은 이벤트 | 동일 반환 DTO만으로 구별 불가. P2 native 세대 계약 공백의 한계로 남김 |
| 최종 F0 후 C 생성 직전 수신된 무효화 | 기존 `eventConsentTouchOrTtlAfterFinalF0ButBeforeCStillCancelsAdmission` 회귀 재사용. Gate가 표시를 거부 |
| C SHOWN 135ms 뒤 버튼·TextView 동시 이동 | 실제 재검증은 CHANGED, 선택창 제거·실행 증거 폐기·클릭 0회 |
| 변경 후 200ms 늦게 이벤트 전달 | event-time 예외 없이 전달 시점의 현재 화면을 검증하여 제거·클릭 0회 |
| 계속 변하는 geometry | 기존 최대 3개 fresh·100ms deadline 회귀 재사용, 안정화 실패 |

회귀는 서비스 driver가 공유하는 순수 안정화/Gate/표시 비교/Flow 경계에서 실행했다. Android Binder, 실제 getWindows 캐시 또는 OEM rendering을 JVM에서 실측한 것은 아니다. C sequence fixture도 실제 `F9ServiceConnection`과 같은 `strictActions` feature switch를 전달하도록 보완했다. 이 수정은 테스트 port의 연결이며 운영 코드를 바꾸지 않았다.

### 나머지 검토

- 비교하는 관측 데이터: 전체 nodes/windows/geometry/path/class/text/description/clickable/enabled/action 집합과 sidecar 객체 결합을 검사. 패키지/window ID만 같다고 통과하지 않음.
- 현재성: 서비스 owner, epoch, consent/package gate, cleanup permit, cancel, 원래 seed TTL과 final candidate TTL 유지. late event를 무시하는 신규 경로 없음.
- 후속 F1/evidence/B는 반환한 마지막 Ready에 결합. 이전 token/time을 새 값으로 복사하지 않음.
- recovery budget은 추가 거부 상한이며 identity 또는 action permission을 만들지 않음. 같은 app/window/content의 geometry/structure 지문 변경으로 상한이 다시 열리지 않음. 다른 문맥이 새 예산을 받는 것은 새 실제 수집·판정을 요구하며 기존 후보 승인으로 쓰지 않음.
- 실행 증거 5초와 ChoiceDisplayLease 표시 30초 분리 유지. 표시 성공/유지로 수동 클릭을 허가하지 않음. 새 F0/B/F9/SCREEN_MATCH 및 최대 1회 클릭 경로 유지.
- resource/cancellation 처리는 기존 공유 lifetime helper를 사용하며 검사/취소/정리 실패에서 후보를 복구하지 않음. VM fatal error까지 자원 정리를 보장한다고 주장하지 않음.
- 최대 3회의 추가 full scan, 각 2,400 visit 및 원격 refresh가 main thread에서 수행됨. 논리적 budget과 반환 결과의 한도는 확인되지만 실제 main-thread 부하/최대 Binder blocking은 기기 미측정이다. 새 P0/P1 클릭 결함을 확인한 것은 아님.
- Cleanup ADDED/HEALTH는 기존대로 거부. Release feature=false 및 formatter/stub 격리 유지.

### 이번 실행 결과

운영·variant 코드 11개는 리뷰 시작본과 byte 단위로 동일하다. 새 기능/안전 조건 수정은 하지 않았다. 이번에 보완한 파일은 기존 목록 안의 테스트 2개와 이 보고서뿐이다.

- 대상 Debug: **65개 중 64 통과 / 1 실패**, 오류·skip 0.
- 대상 Release: **65개 중 64 통과 / 1 실패**, 오류·skip 0.
- 두 실패는 동일한 `reviewMissingNativeGenerationProofMustNotGrantStableAdmission`이다. 의도적으로 남긴 엄격 계약 재현이며, 현재 전체 통과 상태라고 보고하지 않는다.
- Release 실패는 shared pure helper를 직접 호출한 계약 테스트다. Release 서비스가 B안 관측을 활성화했다는 의미가 아니다.
- 기존 전체 Debug 1,536 / Release 1,482 / host 55 / popup 26, 빌드·Lint 기록은 **이전 구현 검증**으로 재사용한다. 이번에는 전체 테스트·빌드·Lint를 다시 실행했다고 주장하지 않는다.
- APK/DEX 패키징 도구와 Debug formatter의 Release 부재는 이번에도 실제 재확인해 통과했다. 기존 APK는 변경되지 않은 운영 코드에 대한 빌드 결과다.
- CRLF-aware working/cached 검사 통과, 실제 space/tab 후행 공백 없음. staged 파일 0개.
- 원본 구조 검토 문서와 다른 9개 worktree의 HEAD/status/기존 dirty file SHA256 보존 확인.

재현 로그: `/workspace/a-p01-b-independent-review-both.log`.

### 최소 수정 방향과 중단 판단

1. 수집 독립성, 관측 값 동일성, 실제 target native 세대/identity 확인을 별도 계약으로 표현해야 한다. DTO object identity/UUID/수집 시각/서비스 epoch를 native 세대 확인으로 대체하지 않는다.
2. A 내부 실제 collector에서 정당하게 확보·검증할 수 있는 짧은 수명의 target provenance 증거가 필요하다. 공용 모델이나 B/C를 임의로 바꾸지 않는다. public API가 제공하는 값의 의미와 재사용 가능성을 검토하기 전 임의 ID/hash를 세대 인증으로 삼지 않는다.
3. 해당 증거가 없거나 세대를 판별할 수 없으면 새 Debug B안 admission은 명시적으로 거부해야 한다. feature만 끄면서 기존 Demo geometry 유지 예외로 자동 fallback시키는 방식은 대안이 아니다.
4. 이를 만들 수 없다면 현재 B안이 보장하는 범위를 “독립 수집에서 관측된 표현의 동일성”으로 제한하는 별도 설계 합의가 선행되어야 한다. 이번 요청의 엄격한 거부 요건을 자동으로 완화하지 않는다.
5. 수정 후 위 red 재현과 후속 변화/왕복/세대 교체/소유권·가림·cleanup 및 전체 검증을 다시 통과시켜야 한다. 100ms/TTL 증가로 대체하지 않는다.

**정책 채택 전 판정: FIX_REQUIRED(12절에서 갱신).** P2 재현을 발견했으므로 운영 코드는 임의 수정하지 않았고, staging/commit/push하지 않았다. 커밋 파일 0개. HEAD d9a3e48 유지. 미커밋 작업 20개와 별도 보존 문서 1개가 그대로 남아 있다. 삼성/LG/에뮬레이터 재실행 없음. 다음 삼성 실측을 요구하거나 성공을 주장하지 않는다.

## 12. 명시적으로 채택한 관측 기반 Debug 표시 계약 — 2026-10-31

### 정책 결정과 공식 API 한계

사용자가 이번 작업에서 `OBSERVATION_ONLY_DEMO`를 Debug Demo 선택창 표시 승인에 한정해 명시적으로 채택했다. 이 절은 11절의 정책 결정 전 보류 판단을 갱신한다. 이전 실패 및 재현 이력은 삭제하지 않는다. native generation 인증을 새로 구현하거나 삼성 ABA가 발생했다고 주장하지 않는다.

- [AccessibilityWindowInfo 공식 문서](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo): window ID는 창 식별자이며 내부 popup 생애 인증값이 아니다. 창 refresh는 최신 문서상 36.1 공개 API여서 compileSdk 35/Android 12에 그대로 적용할 수 없다.
- [AccessibilityNodeInfo 공식 문서](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo): getUniqueId는 API 33+, nullable이고 setUniqueId는 교체된 유사 노드에 같은 ID를 부여하는 용도를 명시한다. refresh 성공은 조회 성공이며 원자적인 전체 트리 또는 ABA 부재를 증명하지 않는다.
- [AccessibilityEvent 공식 문서](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent): ADDED/REMOVED는 창 추가/제거 신호다. BOUNDS는 R부터 region 변화도 포함하고 단일 이벤트에는 mask가 혼합될 수 있다. 이벤트 부재를 완전한 변경 이력의 부재로 인증하지 않는다.
- [AOSP NodeInfo](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/view/accessibility/AccessibilityNodeInfo.java): public refresh는 기존 window/source 식별자로 재조회한다. sourceNodeId getter는 숨겨진 API이고 native 생성 세대 계약이 아니다.
- [AOSP 창 관리](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityWindowManager.java): 내부 Binder token과 창 ID를 연결한다. 외부 서비스에 임의 popup의 generation 증명을 제공하지 않는다. 같은 창 내부 popup 교체에 새 창 ID가 반드시 필요한 것은 아니다.

공개 API로 임의 타사 popup generation 직접 인증과 관측되지 않은 동일 ABA 완전 검출은 UNAVAILABLE이다. 관측된 동일성/현재성은 OBSERVABLE_BUT_NOT_PROVEN이다. UUID, 시각, 지문, action 목록 일치를 세대 인증으로 대체하지 않는다.

### 두 계약과 최소 구현

`PrePresentationStability.kt`에 A 내부 `PresentationAssurance` enum과 명시적 마지막 인자를 추가했다. 기존 수집 및 비교 알고리즘을 재사용한다. 새로운 공통 DTO·상태 기계·세대 식별값은 만들지 않았다.

| 계약 | 승인 조건 | 현 구현 |
|---|---|---|
| STRICT_GENERATION_REQUIRED | 실제 세대 증명이 있어야 함 | 기본값. 현재 read 계약에는 정당한 증명 수단이 없어 GENERATION_UNPROVEN/null. 추가 수집/posted turn도 실행하지 않음 |
| OBSERVATION_ONLY_DEMO | 독립적인 두 fresh 수집의 관측 값이 정확히 같고 모든 기존 보호/현재성 조건 통과 | Debug feature가 명시적으로 선택. 마지막 실제 Ready 객체만 반환. native generation/ABA 부재/클릭 권한을 뜻하지 않음 |

서비스 driver는 `ManualPresentationStabilityFeature.enabled`와 기존 bounded application popup 조건 안에서 variant의 `contract`를 명시적으로 전달한다. Debug는 OBSERVATION_ONLY_DEMO, Release는 enabled=false/STRICT_GENERATION_REQUIRED다. 순수 함수 계약 테스트에서 Release 컴파일의 공통 helper에 OBSERVATION_ONLY_DEMO를 명시하는 경우는 있지만, Release 서비스는 이를 선택하거나 추가 수집을 실행하지 않는다. 실제 variant 선택을 사용한 공유 B/C Flow 회귀와 compiled driver 검사로 이 차이를 확인한다.

최종 ChoicePresentationGate가 쓰는 `sameExact`는 관측 값의 엄격한 동등성 함수다. STRICT_GENERATION_REQUIRED 증명이나 클릭 허가는 아니다. 검증용 boolean/UUID를 받아 세대 증명으로 승격하는 API는 만들지 않았다.

### 승인·거부의 정확한 의미

- 최대 3개 추가 fresh, 총 100ms 논리적 예산 유지. 최소 안정 기간이나 Binder 호출 강제 선점 시간이 아니다.
- seed/중간 geometry 변화는 기존 후보를 버리고 다음 실제 수집을 요구한다. 서로 다른 두 geometry를 같은 결과로 승인하지 않는다. 예산 안에서 연속 두 fresh가 정확히 같을 때만 최종 객체를 사용한다.
- 내용·구조·경로·class·버튼 상태·정확한 action 집합·창 관계가 달라지면 즉시 거부한다. geometry가 계속 바뀌면 UNSTABLE/DEADLINE으로 종료한다.
- 실제 collector를 새로 호출하고 방문 노드 refresh 및 endpoint 검사를 수행한다. Java DTO 독립성/메타데이터 결합을 확인하지만 OEM 캐시나 target native 세대까지 인증한다고 주장하지 않는다.
- 수신된 제거/재추가와 사용자 조작/동의 철회는 서비스 ScreenFreshness 세대를 바꾼다. 같은 descriptor로 돌아와도 이전 epoch를 복원하지 않는다.
- 새 F1/evidence/B는 마지막 수집 객체에서 수행한다. 기존 후보/token/timestamp를 복사해 갱신하지 않는다. 후보 승인과 C 최종 admission은 후속 실제 B/Gate 조건을 통과해야 한다.
- C 생성 뒤 ROOT/TextView/닫기/다른 버튼이 이동하면 기존 엄격 DISPLAY_REVIEW에서 제거한다. 130~140ms 이후 이벤트를 무시하는 예외는 없다.
- 30초 표시 lease와 5초 실행 evidence TTL은 별개다. 표시 성공/유지로 만료된 실행 증거를 되살리지 않는다.
- 수동 선택 시 실제 C 제거·quiet 확인 후 새 F0, 전체 snapshot/후보 비교, B 재판정, F9/SCREEN_MATCH가 필요하다. 실패하면 클릭 0회, 성공해도 기존 최대 1회만 허용한다.

### P2 회귀 처리와 공유 경로 검증

기존 red 회귀를 삭제하거나 무시하지 않았다. 수정 전 같은 이름의 테스트를 Debug/Release 각각 실행하여 실패 1건씩을 다시 확인했다(`/workspace/a-p01-observation-contract-before.log`).

`reviewMissingNativeGenerationProofMustNotGrantStableAdmission`은 STRICT_GENERATION_REQUIRED를 명시하고 그대로 거부를 요구한다. 추가한 `omittedContractCannotAccidentallyOptIntoObservation`은 기본값도 거부인지 확인한다. 별도 관측 계약 회귀는 마지막 새 객체를 반환하며 native 세대를 주장하지 않음을 확인한다.

신규 회귀 5개(공통 Debug/Release 각각):

1. 계약 생략 시 strict 거부, 수집/posted callback 0회.
2. 명시적 관측 계약에서 정확한 두 fresh 결과의 마지막 객체만 승인.
3. 실제 서비스가 사용하는 ScreenFreshness에서 제거·재추가로 epoch를 두 번 바꾸면 같은 데이터라도 거부.
4. 실제 variant 계약 → 공유 안정화 → 기존 B wiring/C Flow/guard 경로: Debug만 표시 가능, Release는 추가 수집 0회.
5. 관측 안정화 후 실제 C Flow 표시, 원래 evidence TTL 만료, C/TTS lease 유지, 선택 후 새 F0 실패 시 B 실행 및 클릭 0회.

기존 회귀를 유지하여 내용/구조/action/가림/소유권/미완성/취소/cleanup, Dialog.show→setLayout 형태의 ROOT/TextView/두 버튼 이동, 계속되는 geometry 변화, 마지막 F0와 C 사이 무효화, SHOWN 130/135/140ms 후 변화 및 지연 이벤트를 검증한다. 관측되지 않은 완전한 ABA characterization도 그대로 남으며 관측 계약의 한계로 명시한다. fake Android 포트 + 실제 공유 정책/Flow 경로의 JVM 검증이며 삼성 프레임워크/실물 렌더링 시험은 아니다.

### 이번 실제 변경 파일

기존 20개 작업 파일의 범위 안에서 다음 9개만 이번 단계에 수정했다. 다른 11개 작업 파일, 별도 구조 검토 문서 및 다른 9개 worktree는 보존한다.

- `app/src/main/java/kr/closeforyou/accessibility/integration/PrePresentationStability.kt`
- `app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt`
- `app/src/debug/java/kr/closeforyou/accessibility/integration/ManualPresentationStabilityFeature.kt`
- `app/src/release/java/kr/closeforyou/accessibility/integration/ManualPresentationStabilityFeature.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/PrePresentationStabilityTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/P01SamsungSequenceRegressionTest.kt`
- `app/src/testDebug/java/kr/closeforyou/accessibility/integration/PresentationStabilityDebugTest.kt`
- `app/src/testRelease/java/kr/closeforyou/accessibility/integration/PresentationStabilityReleaseTest.kt`
- `docs/A_P01_PRE_PRESENTATION_STABILITY_REVIEW_2026-10-31.md`

### 전체 검증 결과

이번 단계에서 실제로 다시 실행한 결과다. 이전 통과 수치를 재실행 결과로 대체해 적지 않았다.

| 범위 | 테스트 | 실패 / 오류 / skip |
|---|---:|---:|
| 대상 Debug (variant 포함) | 71 | 0 / 0 / 0 |
| 대상 Release (variant 포함) | 71 | 0 / 0 / 0 |
| 전체 app Debug | 1,547 | 0 / 0 / 0 |
| 전체 app Release | 1,493 | 0 / 0 / 0 |
| capture-test-host Debug | 55 | 0 / 0 / 0 |
| popup-test-app Debug | 26 | 0 / 0 / 0 |

이전 전체 1,536/1,482 대비 각 11개 증가: 직전 독립 리뷰에서 추가한 공통 6개 + 이번 공통 5개다. 기존 red 테스트는 유지하고 계약 선택을 명시했으며, variant 테스트 2개에는 실제 계약값 검사를 보강했다. 테스트 수는 variant별 실행 수이며 중복 없는 고유 테스트 수라는 뜻이 아니다.

- Debug/Release 앱 APK, 앱/호스트 AndroidTest APK, 호스트/합성 앱 Debug APK: 성공. AndroidTest는 빌드 검증이며 기기 실행 아님.
- 앱 Debug/Release 및 호스트 Lint: 이슈 0. 합성 앱 Lint도 성공하나 기존 TypographyFractions와 SetTextI18n 경고 2개 유지. 다른 팀 파일을 수정하지 않음.
- 전체 Gradle 결과: BUILD SUCCESSFUL, 4분 5초, 260 tasks 중 41 실행/219 UP-TO-DATE. lifecycle/vital 보조 SKIPPED와 JVM skip 0을 혼동하지 않음. 전체 Debug/Release Lint task는 실행 완료.
- APK/DEX: 기존 verify_capture_test_apks.py 통과. Debug bridge/시험 클래스의 운영 APK 격리, Kotlin runtime/host 정의, external driver의 target-process 의존성 차단 확인.
- Debug 진단 formatter 6종은 Debug DEX에 있고 Release DEX에는 없음. Release 서비스와 중첩 클래스 25개에 PrePresentationStability/OBSERVATION_ONLY_DEMO 참조 없음. Release feature bytecode는 strict 계약과 무동작 report만 사용. 공통 helper 자체의 존재를 Release 서비스 활성화로 혼동하지 않음.
- merged Manifest의 Internet/network-state 권한 추가 없음. Manifest/gradle.properties/실행 정책 파일 변경 없음.
- 최종 검증 시작 시 기록한 19개 코드/테스트 파일 SHA256이 종료 후 모두 동일. 이후 변경은 이 결과 문서뿐.
- CRLF 원본 보존, working/cached CRLF-aware 공백 검사 통과, 새 파일 포함 실제 space/tab 후행 공백 없음. staged 0개.
- 모든 10개 worktree HEAD, 다른 9개 status/미커밋 파일 hash, 구조 검토 문서 hash 보존 확인.

로그: `/workspace/a-p01-observation-contract-before.log`(수정 전 red), `/workspace/a-p01-observation-contract-targeted.log`(대상 통과), `/workspace/a-p01-observation-contract-final-all.log`(전체), `/workspace/a-p01-observation-contract-release-driver.txt`(Release feature bytecode). 이전 20개 작업의 시작본은 `/workspace/a-p01-observation-contract-before`에 별도 보존했다.

실행한 전체 Gradle 명령은 10절의 명령에 `:capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest`를 명시적으로 포함했고 `--continue`를 사용했다. 호스트·합성 JVM은 각 task의 `--rerun`으로 실제 재실행했다.

### 잔여 위험 및 Cleanup 독립 장애

엄격한 관측 일치는 동일 native 생애를 증명하지 않는다. 관측 사이 완전히 동일한 popup으로 교체되고 관련 이벤트도 아직 전달되지 않았다면 구별할 수 없다. 최종 확인과 외부 앱 ACTION_CLICK 사이에도 원자적 compare-and-click이 없어 TOCTOU 위험이 남는다. 동일 라벨/구조가 실제 handler 의미의 불변성을 인증하지도 않는다. 이번 변경은 기존 실행 정책을 확대하지 않으며, 관측 표시 승인을 실행 허가로 전환하지 않는다.

`ManualChoiceRefresh`는 실제 새 F0의 전체 비교 이후 기존 detection 범위를 fresh token에 연결하고 B 후보를 다시 생성한다. 이를 새 native 세대 증명이라고 표현하지 않는다. 이 기존 실행 경로와 F9 검사/TTL/1회 제한은 이번에 수정하지 않았다.

Cleanup `RETIRED_ID_EVENT / ADDED / HEALTH`는 별도 미해결 장애다. ADDED를 제거 완료로 인정하지 않고 HEALTH를 초기화하지 않는다. 대상 변화로 C를 제거한 뒤 해당 장애가 발생하면 cleanup permit이 닫혀 다음 수집/admission/선택 실행까지 막을 수 있다. 이 상태는 정상 표시가 다시 나오지 않는 원인이 될 수 있으며 이번 계약으로 우회하지 않는다.

### 다음 단일 삼성 실측의 판정 기준

새 Debug APK에서 개인정보 없는 P01 한 번만 실행하는 후속 실측을 준비한다. 이번 Linux 작업에서는 삼성/LG/에뮬레이터를 실행하지 않았다.

- 관측 경계 성공: `A_PRESENTATION_STABILITY;RESULT=STABLE_FRESH` 또는 `STABLE_RECOLLECTED` 이후 실제 F1/evidence/B/C `SHOWN`이 이어짐. STABLE 로그만으로 실제 표시·유지 성공으로 판정하지 않는다.
- 사용자 표시 성공: 실제 C 선택창이 6초 이상 유지되고 TTS COMPLETED가 관측됨. 후속 실제 대상 변화가 없는 조건에서 판단한다.
- 안전한 거부: UNSTABLE/DEADLINE/STALE/METADATA_UNAVAILABLE 등으로 표시하지 않거나, SHOWN 이후 실제 bounds/내용/버튼 변화로 DISPLAY_REVIEW CHANGED→제거되는 경우. 기능 목표 미달과 안전 거부를 구분한다.
- SHOWN 약 130~140ms 뒤 변화가 재현되면 기존대로 제거되어야 한다. 이 경우 안정화가 향후 geometry를 보장하지 못했다는 결과이며 정책 완화로 해결하지 않는다.
- 수동 닫힘은 표시 성공과 별도다. OVERLAY_REMOVAL→fresh F0→SNAPSHOT_MATCH/TARGET_MATCH→B 승인→F9 검증→최대 1회 CLICK_ATTEMPT→실제 CLOSED_CONFIRMED까지 모두 확인해야 성공이다. 미도달/cleanup 장애/SCREEN_MATCH 실패면 클릭 0회가 정상이다.
- 재수집 예산 100ms나 TTL을 늘리지 않는다. 중복 표시/중복 TTS/클릭 재시도를 성공 기준에 포함하지 않는다.

### 최종 안전성 판단

이번 명시적으로 승인된 Debug 관측 표시 계약 범위에서 P2 계약 불일치를 해결했다. strict 기본값/증명 부재 거부와 관측 계약의 제한된 승인을 별도 검증했고, 기존 red 2건이 각 계약 아래 통과했다. 검토한 변경에서 새 P0~P2 승인 우회를 발견하지 않았다. **이번 변경 범위는 로컬 커밋 검토 가능 상태**다. 사용자 지시에 따라 실제 staging/commit/push는 하지 않았다.

이는 native generation/ABA/TOCTOU 제거 또는 삼성 P01 해결 완료 판정이 아니다. 기존 실행 조건은 유지했고 Cleanup 장애와 실기기 부하·캐시·등록/geometry 변화는 미검증/미해결로 남긴다. 해당 조건이 발생하면 계속 거부해야 한다. HEAD는 d9a3e48 그대로이며 미커밋 작업 20개 + 별도 보호 문서 1개를 보존했다.
