# P01 Debug 표시 전용 passive reflow — 2026-10-30

> 후속 ROOT action 표시 정책 검토 및 변경은 [A_P01_ROOT_DISPLAY_CAPABILITIES_2026-10-30.md](A_P01_ROOT_DISPLAY_CAPABILITIES_2026-10-30.md)를 따른다. 아래 native NONE만 허용했던 조건과 검증 수치는 각 이전 구현 시점의 기록이다.

## 근거와 범위

기준 `b5c23add9822dcbfe730c4d7174cb5cd6138c1a3`, branch `fix/a-samsung-p01-lifetime-20261028`.
사용자 제공 삼성 18:44 실측의 `DEMO_BOUNDS=POLICY_DESCENDANT_BOUNDS`는 selector와 기존 불변 조건을 통과한 후 root/window 크기 변화와 자식 geometry 변화의 조합이 기존 정책에서 거부됐음을 입증한다. **어떤 자식이 바뀌었는지는 입증하지 않는다.**

이번에는 기존 Debug 시연 표시 정책에만 제한된 reflow 분기를 추가했다. Release feature=false 및 Android reader의 strict gate, 원래 strict 비교는 그대로다. P2 보강으로 Debug의 whole-popup translation과 자식 고정 root resize에도 아래의 native action·그래프·겹침 검사를 공통 적용한다. 테스트 앱/C/P23/공통 DTO는 변경하지 않는다. 실제 삼성/LG 시험은 실행하지 않았다.

## 실제 호출 경로와 허용 조건

기존 Android driver의 정확한 대상 BOUNDS 이벤트 → 원본 실행 증거 retire → native/currentness 전후 확인 → 동일 AndroidF9ScreenPort의 전체 읽기 → ManualDisplayContinuity → ManualDemoBoundsPolicy → 최종 currentness/100ms/native/해제 검사 → 표시 유지/제거 순서를 재사용한다. 새 수집/getter/IPC/시계/대기/재시도는 없다.

기존 complete·package/window/frame·APPLICATION/type/layer/다른 창·원본/native 충돌·양수 bounds·전체 노드 수/순서/경로/ID/class/텍스트/description/clickable/enabled/actions·실제 B 단일 E2/CLOSE 후보·정확한 닫기 라벨/경로/ID/동작/가시성·보호 내용 검사는 모두 그대로 통과해야 한다. ROOT와 window Rect는 양 시점 각각 정확히 같아야 한다. 같은 ID 또는 라벨만으로 승인하지 않는다.

기존 전체 일관 이동 또는 root-only resize로 설명되지 않는 경우에만 다음의 추가 조건을 적용한다.

- window가 **실제로 크기 변경**되어야 한다. window 불변 상태에서 body만 변하거나, window는 단순 이동하는데 일부 자식만 다르게 움직이는 경우는 여전히 거부한다.
- 모든 노드의 실제 수집 path에 대해 canonical 부모 경로가 앞서 존재해야 한다. 누락/중복/역순/불명확한 경로를 만들어 채우지 않는다. 노드 수 1,200, 기존 Android 깊이/시간 한도를 늘리지 않는다.
- 모든 자식은 원본/현재 각각 직접 부모 Rect 안에 양수 영역으로 포함돼야 한다. 부모는 명확한 container descriptor여야 한다. 전체 노드를 검사하며 비가시성 추정으로 생략하거나 clipping/좌표를 보정하지 않는다.
- 원본과 fresh **각각** 기존 전체 native action 읽기에서 NONE/PRESENT/UNKNOWN을 기록한다. 공통 NodeSnapshot/PopupSnapshot은 바꾸지 않고 A 내부 `NativeNodeActions`를 쓴다. original은 실제 capture snapshot 객체, fresh는 실제 수집 노드 목록 객체에 identity로 결합한다. 누락/한도초과/복사본·다른 목록에 결합된 정보는 UNKNOWN으로 거부하고 두 시점의 presence가 다르면 변경으로 거부한다.
- container는 정확한 `android.widget.FrameLayout` 또는 `android.widget.LinearLayout`, clickable=false, 지원 action 집합 공백, 양 시점 native action **NONE**, 텍스트/description 공백이어야 한다. custom class, ScrollView, WebView, ImageView 등은 독립 reflow 허용 대상이 아니다.
- 독립 변경 가능한 본문은 정확한 `android.widget.TextView` **leaf**, nonblank 본문, clickable=false, 지원 action 공백, 양 시점 native action NONE이어야 한다. 닫기/건너뛰기 역할 라벨은 passive 본문으로 취급하지 않는다.
- 새로 바뀐 passive 노드와 모든 interactive 노드 사이의 원본/현재 Rect를 검사한다. 검증된 조상·자손의 포함 관계는 허용하지만, 별개 경로의 양수 영역 겹침은 z-order/불투명 영역을 확인할 수 없어 거부한다. visiblePaths는 이 검사의 대체 증거가 아니다. 비교 16,384회 상한에 닿으면 미완료 검사로 거부한다.
- 닫기 버튼뿐 아니라 **모든 clickable 또는 지원/native action이 있는 노드**는 독립 reflow에서 원래 bounds 그대로여야 한다. 버튼 이동/크기 변경, 다른 actionable 노드의 이동은 거부한다. 기존 whole-popup의 모든 노드가 정확히 같이 이동하는 별도 경로는 유지한다.
- CONTENT/STATE/ADDED/REMOVED/혼합 mask에는 새 분기를 선택하지 않는다. 노드/본문/버튼 교체, 다른 창 개입, 불완전/불명확 수집은 기존처럼 제거·실행 차단한다.

P2-1: NodeSnapshot.actions의 공백을 native action 부재로 추정하지 않는다. 원본 collector가 이미 읽는 전체 ID 목록, fresh reader가 이미 읽는 같은 action 목록에서 count를 관측한다. 0만 NONE, 1..64는 PRESENT, 범위를 벗어나거나 정보가 없으면 UNKNOWN이다. LONG_CLICK/SCROLL/포커스/SHOW_ON_SCREEN/알 수 없는 ID도 모두 PRESENT이며, raw action ID는 metadata에 저장하지 않는다. 현재는 무해한 탐색 action 예외도 두지 않는다. 따라서 실제 Android root나 움직인 본문에 포커스 action만 있어도 해당 geometry 유지가 거부될 수 있다. 이를 해결하려고 PRESENT를 NONE으로 바꾸지 않는다.

P2-3: root는 모든 Demo geometry 경로에서 clickable=false·지원 action 공백·**원본/fresh native NONE**·정확한 허용 container를 요구한다. root-only resize/전체 이동이 먼저 true를 반환해 검사를 건너뛰던 경로를 닫았다. parent 그래프/양수 포함 관계도 공통 검사한다. 전체 일관 이동에서는 action이 있는 버튼도 전체와 정확히 같이 움직일 수 있지만, root의 action 부재와 정보 완전성·동일 presence 검사는 면제하지 않는다.

메타데이터는 Debug에서만 수집한다. Release Ready/일반 F9 read에는 null이고 projection·공통 DTO·기존 지문 입력/토큰 인코딩은 바꾸지 않는다. source→F9ServiceConnection→ManualPopupConnection→AndroidF9ScreenPort/ManualDisplayContinuity→정책으로 연결하며 original 제거 시 함께 폐기한다. 메타데이터는 F0 완료 인증이나 실행 자격으로 사용하지 않는다.

이 증거는 native action 목록의 관측에 한정한다. 네이티브 객체 세대·변경되지 않은 리스너·Android 캐시의 원자성·과거 가시성·실제 픽셀/z-order를 인증하지 않는다. 관측되지 않은 동일 descriptor 교체/ABA를 해결했다고 주장하지 않는다. 가림이 모호한 unrelated 겹침은 거부한다. unknown/custom widget, 누락 metadata, invalid geometry나 불명확한 창도 계속 거부한다.

추가 처리는 최대 1,200개 DTO/action-presence 행과 bounded path/부모 Rect 검사, 최대 16,384회의 겹침 비교다. 새 무제한 루프/Android 접근은 없다. 처리 비용은 0이 아니며 OEM 수집이 느리면 기존 100ms 검사에서 여전히 실패한다. 애니메이션 이벤트마다 원본과 다시 비교하며 원본 기준·token·TTL을 갱신하지 않는다.

## 결과 코드와 클릭 안전성

기존 한 줄 `A_MANUAL_DISPLAY_REVIEW` formatter를 그대로 쓴다.

| DEMO_BOUNDS | 의미 |
|---|---|
| `POLICY_RETAIN_PASSIVE_REFLOW` | 추가 passive/부모 검사를 통과. **policy 내부 결과만** 의미함 |
| `POLICY_REFLOW_PATH` | 누락/비정규 부모 경로 |
| `POLICY_REFLOW_PARENT` | 부모가 허용 container 아님 또는 원본/현재 부모 포함 관계 실패 |
| `POLICY_INTERACTIVE_BOUNDS` | clickable/지원 action 노드의 독립 이동·크기 변화 |
| `POLICY_DESCENDANT_BOUNDS` | 바뀐 노드가 허용 passive class/leaf/본문 조건을 충족하지 않음 |
| `POLICY_NATIVE_ACTIONS_UNCONFIRMED` | 어느 쪽 metadata 누락/UNKNOWN/결합 불일치/지원 action과 부재 정보 모순 |
| `POLICY_NATIVE_ACTIONS_CHANGED` | 원본/fresh의 full-native action 존재 상태가 달라짐 |
| `POLICY_ROOT_INTERACTIVE` | root clickable/지원 action 또는 native action 존재 |
| `POLICY_REFLOW_OVERLAP` | 바뀐 passive 요소가 별개 경로의 interactive 영역과 겹침 |
| `POLICY_LAYOUT_LIMIT` | 겹침 검사를 완전히 마치지 못해 거부 |
| 기존 `SELECT_*`, 기타 `POLICY_*` | 기존 selector/불변 조건의 첫 거부. 의미 유지 |

정상적인 새 경로의 예시(실측 아님):

```text
A_MANUAL_DISPLAY_REVIEW;...;RESULT=BOUNDS_RETAINED;...;DEMO_BOUNDS=POLICY_RETAIN_PASSIVE_REFLOW
```

policy가 통과해도 최종 timeout/native/currentness/해제 실패 시 RESULT=UNAVAILABLE로 끝난다. 표시 유지 결과는 원본 snapshot/token/시간/analysis/eligibility를 새로 만들거나 갱신하지 않는다. 사용자 선택 후에는 C 실제 제거 → 새 F0 → **기존 엄격한 전체 snapshot·버튼 비교** → B 재판정 → F9/SCREEN_MATCH/5초 TTL/클릭 최대1회/실제 popup 소멸 확인을 그대로 요구한다. 따라서 표시를 유지했어도 달라진 geometry가 남아 있으면 수동 refresh 또는 클릭 직전 검사에서 차단될 수 있다. 이것을 클릭 성공으로 바꾸지 않는다.

cleanup RETIRED_ID_EVENT/ADDED/HEALTH는 별도 미해결 장애로 유지한다. ADDED를 제거 완료로 취급하지 않으며 cleanup 실패 후 클릭을 허용하지 않는다.

## 회귀와 로컬 검증

기존 중첩 fixture와 실제 B 후보 생성, selector, registered display read, 최종 retain/remove/report 계약을 재사용한다. 기존 root/body reflow 거부 회귀 2개는 새로 명시한 허용 결과로 갱신하고, 첫 실패/취소 회귀는 여전히 거부해야 하는 버튼 이동 사례로 유지한다. 신규 회귀는 container/body reflow, 양 버튼 고정, 독립 버튼 이동/크기·속성·역할 변화, 교체/노드 수/순서/본문 변경, 미확인 class/action/부모/geometry/다른 창, 비bounds 이벤트/strict 정책, 반복 이벤트, timeout/native/취소/cleanup, 이후 실제 ManualChoiceRefresh와 F9 PRE_CLICK 실패/클릭0회를 검증한다.

이전 passive reflow 구현의 검증 기록(P2 보강 전):

- 신규 회귀 **18개**, 기존 Demo 31개를 포함한 대상 49개가 양 variant에서 통과했다. 기존 reflow 기대값 2개와 첫 실패 보존 사례를 새 정책에 맞게 갱신했다.
- 앱 전체 JVM Debug **1,382개**, Release **1,367개**, 실패/오류/skip 0. Debug 전체는 대상 실행 명령에서 실제 실행됐고 최종 전체 명령에서는 동일 입력 UP-TO-DATE, Release 전체는 최종 명령에서 실제 실행됐다. 두 variant를 고유 테스트 수로 합산하지 않는다.
- 호스트55개·합성 앱26개는 최종 명령의 UP-TO-DATE 결과를 재사용했고 XML 실패/오류/skip 0을 확인했다.
- 앱 Debug/Release APK 재빌드 및 앱/호스트 AndroidTest APK 빌드 태스크 통과(변경 없는 시험 산출물은 UP-TO-DATE). 합성 앱 Debug 빌드 태스크도 UP-TO-DATE 통과.
- 앱 Debug/Release, 호스트 Debug Lint 통과, 각 XML issue 0. 최종 Gradle `BUILD SUCCESSFUL in 3m 28s`, 250 task 중25 실행/225 UP-TO-DATE.
- 실제 APK 패키징·DEX 격리 검사 통과. DEMO_BOUNDS/NODE_MISMATCH/TARGET_BINDING/SCREEN_MATCH formatter marker는 Debug에만 존재하고 Release에는 없다. 신규 JVM 회귀 class는 운영 APK에 정의되지 않는다.
- 전체/대상 로그는 저장소 밖 `/workspace/a-p01-passive-reflow-full.log`, `/workspace/a-p01-passive-reflow-target.log`. diff 공백 검사와 변경 범위·기존 문서·다른9개 worktree 보존을 확인했다. 커밋/푸시/병합 없음.

## P2 보강 회귀·검증

이번 P2에서 `NativeNodeActionsTest` 6개와 `ManualDemoP2SafetyTest` 17개, **23개**를 추가했다. 앞선 passive 회귀18개는 보존하고 실제 driver와 같은 metadata 입력을 연결했다. 새 생성자의 default null을 승인 증거로 만들지 않는다. 기존 Demo/삼성 시퀀스 fixture에는 명시적인 fake full-native 관측값을 공급했다. production이 projected actions에서 부재를 추정하는 코드는 없다. 전체 Kotlin fixture는 실제 native 읽기가 아니라는 구분을 유지한다.

회귀는 LONG_CLICK/SCROLL/포커스/임의 ID, source/fresh 누락·UNKNOWN·다른 객체/copy 결합, 지원 action과 모순, root native action이 있는 resize/translation/reflow 거부, 버튼/다른 actionable 대상 겹침, 정상 조상 포함·경계 접촉, preexisting 겹침의 불확실성, 그래프·연산 한도, 기존 strict/currentness/TTL/cleanup/최종 클릭0회 방어를 다룬다. 초기 전체 실행에서 legacy 삼성 sequence fixture 2건은 native 정보가 없어 실패했고, 안전 판정 기대값을 완화하지 않고 명시적인 fake 정보를 연결해 재검증한다.

최종 P2 검증 결과(현재 코드):

- 대상 **91개**가 Debug/Release 각각 통과. 신규 P2 23개 + 이전 passive 18개 + 기존 Demo 31개 + 삼성 sequence 19개이며, 전체 JVM 재실행에도 포함했다.
- 전체 앱 JVM Debug **1,405개**, Release **1,390개**, 실패/오류/skip 0. 두 variant를 고유 테스트 수로 합산하지 않는다.
- 호스트55개·합성 앱26개는 UP-TO-DATE 결과 재사용, XML 실패/오류/skip 0 확인.
- 앱 Debug/Release APK 재빌드, AndroidTest APK/호스트/합성 앱 Debug 빌드 태스크 통과(변경 없는 산출물은 UP-TO-DATE). Android 계측 실행이 아니다.
- 앱 Debug/Release 및 호스트 Debug Lint 이슈0. 전체 lintRelease가 실행되므로 중복 lintVitalRelease 작업은 Gradle에서 SKIPPED다. JVM skip은0이다.
- 최종 Gradle `BUILD SUCCESSFUL in 3m 44s`, 250 task 중38 실행/212 UP-TO-DATE. 최종 로그 `/workspace/a-p01-p2-verified.log`, 대상 로그 `/workspace/a-p01-p2-target-final.log`는 저장소 밖에 있다.
- 실제 APK 패키징/DEX 격리 통과. 기존 Debug formatter marker가 Release DEX에 없음을 재확인했다. Release feature=false/일반 F9 read는 sidecar=null을 유지한다. fresh sidecar는 실제 선택된 Debug Demo 읽기에서만 만든다. 원본 collector도 기존 full-ID 읽기를 재사용하며, 추가 actionList/getId/화면 getter나 IPC는 없다.
- 기존 CRLF4개 파일은 줄바꿈을 보존했다. 일반 `git diff --check`는 추가 CRLF 줄의 CR을 후행 공백으로 표시하지만, 실제 space/tab 후행 공백이 아님을 byte 검사했고 `git -c core.whitespace=cr-at-eol diff --check` 통과. index는 비어 있고 staged 공백 검사 통과. 소스 줄바꿈 전체 변환이나 git 설정 변경은 하지 않았다.
- 기존 구조 검토 문서와 다른9개 worktree, 공통 DTO/B/C/P23/Manifest/gradle.properties를 보존했다. 기존 미커밋18개 회귀 파일도 원형 보존했고 source/fixture/doc는 이어서 보강했다. HEAD 불변, commit/push/merge 없음.

JVM은 Android getter/Binder/native cache/실제 화면 렌더링을 실행한 검증이 아니다. 이 부분은 실제 driver의 정적 연결과 AndroidTest 컴파일로만 확인했다. 합성 JVM 결과는 삼성 동작의 증거가 아니다. 삼성/LG/에뮬레이터 계측은 미실행이다.

### 현재 변경 파일(기존 미커밋 포함16개)

- A source: `AndroidSnapshotCollector.kt`, `CloseForYouAccessibilityService.kt`, 신규 `NativeNodeActions.kt`, `action/AndroidF9ScreenPort.kt`, `action/F9AutoCloser.kt`(F9Scene의 nullable 내부 metadata 필드만), `integration/F9ServiceConnection.kt`, `integration/ManualPopupConnection.kt`, `integration/ManualDisplayReview.kt`, `integration/ManualDemoBoundsPolicy.kt`.
- A JVM: `NativeNodeActionsTest.kt`(신규), `ManualDemoP2SafetyTest.kt`(신규), 기존 미커밋 `ManualDemoPassiveReflowTest.kt`, `ManualDemoBoundsPolicyTest.kt`, `ManualDemoBoundsRejectionTest.kt`, `P01SamsungSequenceRegressionTest.kt`.
- 이 문서 `docs/A_P01_PASSIVE_REFLOW_2026-10-30.md`. 별도 보존 `docs/A_P01_STRUCTURAL_ROOT_REVIEW_2026-10-30.md`는 수정/스테이징하지 않았다.

## 커밋 전 최종 리뷰 보완

리뷰에서 추가8개 회귀와 최소 방어를 반영했다. 누적 변경 파일은 그대로16개다. 이번 리뷰에서 손댄 파일은 NativeNodeActions/ManualDemoBoundsPolicy/ManualDisplayReview, NativeNodeActionsTest/ManualDemoP2SafetyTest 및 이 문서6개다.

1. **Demo 선택 뒤 strict SAME fallback:** DTO가 같으면 기존에는 native metadata 누락/UNKNOWN/존재 상태 변화가 있어도 SAME으로 끝날 수 있었다. 이제 실제 Debug Demo가 선택된 경우 동일한 native 검사 함수를 SAME에도 적용한다. 누락/불명/모순은 UNAVAILABLE(`DETAIL=INCOMPLETE`, DEMO_BOUNDS가 정확한 native 사유), 존재 상태 변화는 CHANGED로 끝난다. 이는 F0의 snapshot.complete 필드를 재해석하거나 수정하는 처리가 아니다.
2. **fresh 목록 재사용 방어:** owner 객체/개수뿐 아니라 factory 시점의 각 불변 NodeSnapshot **객체 참조**도 보관한다(문자열/좌표값을 복제하지 않음). 같은 가변 목록을 같은 크기로 재사용하거나 동일 값의 새 descriptor로 바꾸어도 UNKNOWN이다. 해당 owner의 손상을 한 번 관측하면 기존 metadata는 복원하지 않는다. 실제 Android reader는 원래부터 scan/tree마다 새 목록과 새 DTO를 만들므로, 이번 방어가 삼성에서 관측된 혼용 원인의 수정이라는 주장은 아니다. 생산·소비는 실제 A main-thread 경계에서 이루어지며 임의의 동시 mutable producer에 대한 원자성 인증은 아니다.

**범위 구분:** root-only resize/전체 이동/passive reflow 세 geometry 예외는 모두 native metadata 완전성·일치, root native NONE, 그래프·부모 geometry 및 해당 겹침 검사를 통과해야 한다. 반면 geometry/DTO/window가 그대로인 기존 strict SAME은 root를 passive라고 인증하는 경로가 아니다. Debug Demo가 선택된 SAME은 native 정보가 알려지고 일치해야 하되, 안정된 root FOCUS(PRESENT)가 존재해도 추가 geometry 허용 없이 SAME일 수 있다. Demo 미선택 및 Release의 기존 strict SAME은 이번 metadata에 의존하지 않으며 기존 계약을 유지한다. ‘Release를 포함한 모든 SAME에서 native 부재를 인증한다’고 해석하지 않는다.

**결합 호출 경로:** source collector는 target SNAPSHOT_PASS에서 DTO와 같은 action 읽기로 행을 만들고 Ready의 실제 snapshot 객체에 결합한다. service는 pipeline의 display 사본이 아니라 result.snapshot과 result.nativeActions를 함께 F9ServiceConnection→ManualPopupConnection으로 넘긴다. original=null인 모든 경로에서 metadata도 비운다. fresh port는 해당 target tree의 지역 목록에만 결합하고 background tree가 이를 대체하지 않으며, release 실패·불완전 read는 Scene/유지 승인으로 반환하지 않는다. 원본 rootInActiveWindow와 fresh window.root가 서로 다른 Android handle인 것은 정상이다. 둘의 metadata를 같은 객체로 비교하지 않고 각 DTO 집합에 독립 결합한 뒤 window/package/frame/노드 값과 presence를 비교한다. 이 차이가 실제 root 표현/기하 차이를 일으키면 계속 거부하며 동일 native 세대라는 인증을 만들지 않는다.

**세대·취소:** service의 Main scope·isCurrent/epoch/동의 검사는 기존대로이며, 원본은 참조 baseline일 뿐 현재 실행 증거가 아니다. fresh scan은 native-before→전체 read→native-after 및 currentness/100ms/healthy 검사를 통과해야 한다. 취소·service close·새 화면 세대·recycle 실패를 metadata의 NONE으로 복구하지 않는다. Binder/OEM cache의 원자성·강제 중단이나 관측되지 않은 ABA를 JVM으로 증명한 것은 아니다.

리뷰 검증 결과:

- 이번 추가8개(목록 재사용2개, selected SAME/strict 범위6개). 대상 **99개** Debug/Release 각각 통과.
- 최종 전체 앱 JVM Debug **1,413개**, Release **1,398개**, 실패/오류/skip0. 호스트55·합성 앱26은 UP-TO-DATE 결과 재사용.
- 앱 Debug/Release 및 AndroidTest/호스트/합성 앱 빌드 태스크, 앱 Debug/Release·호스트 Debug Lint 통과. 최종 Gradle `BUILD SUCCESSFUL in 3m 25s`, 250 task 중26 실행/224 UP-TO-DATE. 최종 로그 `/workspace/a-p01-review-full.log`, 대상 `/workspace/a-p01-review-target.log`.
- 기존 CRLF 유지, 일반 whitespace 경고는 CR이며 실제 추가 후행 space/tab은 없음. CRLF-aware diff 검사 및 빈 index의 staged 검사 통과. 기존 구조 검토 문서와 다른9개 worktree 보존, commit/push/merge 없음.

안전성이 관측된 계약 범위에 한정되는 Debug 패치로 검토하며, 실제 삼성 P01의 표시 유지/닫힘 해결 판정과는 구별한다. 일반 FOCUS/SHOW_ON_SCREEN 등을 가진 root, 움직인 native-action-bearing 본문, metadata 부재/불명, invalid/clipped/부모 불명 geometry, 별개 경로 겹침, 예산 초과, source/fresh 표현 차이와 cleanup ADDED/HEALTH는 여전히 실제 기기 거부 원인이 될 수 있다. pixel/z-order·리스너 의미·native 세대·동일 descriptor의 관측되지 않은 교체는 입증하지 못했다.

## 삼성/LG에서 남은 단일 판정

기존 설정으로 P01 한 번을 실행하고 같은 ATTEMPT의 로그와 실제 화면·음성을 함께 본다. ROOT/window와 자식 변화가 재현됐을 때 **최종** `RESULT=BOUNDS_RETAINED;DEMO_BOUNDS=POLICY_RETAIN_PASSIVE_REFLOW` 뒤 선택창 6초 유지/TTS 완료가 확인되면 이 표시 경로를 관측한 것이다. 다른 최종 결과, 선택창 제거, 음성 중단이면 실패다. POLICY_RETAIN 코드 단독으로는 성공이 아니다.

이번에 실제 바뀐 자식이 버튼/미확인 위젯이거나 부모 geometry가 불명확하면 새 정책도 거부해야 한다. 따라서 삼성 P01 해결을 보장하지 않는다. 성공해도 기존 cleanup ADDED나 refresh/SCREEN_MATCH/TTL에서 클릭이 차단될 수 있다. 삼성에서 표시를 확인한 후 LG TalkBack ON/OFF에서 같은 표시 유지와 실제 사용자 탐색/외부 화면 변경 시 안전 무효화, 별도 클릭 전 검증을 확인해야 한다.
