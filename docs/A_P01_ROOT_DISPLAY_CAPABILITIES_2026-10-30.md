# P01 ROOT action과 표시 전용 연속성

> 최신 P2 보완은 아래 「전체 native action 집합 P2 후속 수정」을 따른다. 초기 `other=true` 요약과 18개 회귀로는 전체 집합 일치가 증명되지 않았음을 최종 리뷰에서 재현했다.

기준 `7574ea30f6b27ac4d64990d44fe6ae5233bfb2dd`, Samsung 사용자 실측 기반. 이 작업에서는 삼성/LG/에뮬레이터 재실행을 하지 않았다. 기존 구조 검토 문서와 다른 worktree를 보존한다. 커밋·푸시·병합 없음.

## 확정 사실과 미확정 원인

사용자 로그에서 SHOWN 약139ms 뒤 순수 BOUNDS에 대해 `POLICY_ROOT_INTERACTIVE`가 나왔다. 기준 코드에는 같은 사유를 반환하는 두 위치가 있다: 원본 또는 fresh ROOT clickable, 또는 후반의 ROOT projected action/native PRESENT 검사. 이 코드만으로 어느 위치인지 또는 FOCUS/SHOW_ON_SCREEN/LONG_CLICK/CLICK 중 어떤 action인지 확정할 수 없다. 앞선 descendant 거부 로그가 이번 시도의 실제 ROOT getter 결과를 증명하지 않는다.

기존 `NONE/PRESENT/UNKNOWN`은 전체 action 유무를 보수적으로 증명하지만 종류를 표현하지 못한다. 일반 탐색 capability 존재 자체는 텍스트·트리·popup identity 변경의 증거가 아니다. 기존 CHANGED는 geometry 차이가 관측됐고 유지 정책이 거부됐다는 뜻이지 실제 popup 교체가 확정됐다는 뜻이 아니다. 반대로 FOCUS라는 이름만으로 native 동일성·popup 완전성·실행 안전성이 입증되는 것도 아니다.

## 선택한 최소 구조

새 display/execution 상태 기계를 만들지 않는다. 실제 서비스의 `reviewManualDisplay`는 `retire` → native-before → 전체 target/background read → 비교 → native-after/currentness/100ms/해제 검증 → retain/remove를 사용한다. `ManualDisplayVerdict.BOUNDS_RETAINED`는 표시 결과일 뿐이다. `CloseForYouAccessibilityService`의 retire 콜백이 먼저 Runtime/F9 실행 증거를 폐기한다. 원본 token/time/analysis/native generation을 갱신하지 않는다. 사용자 선택 후 C 실제 제거와 quiet 검증, 새 F0, 기존 엄격한 ManualChoiceRefresh 전체 snapshot/target 비교, B 재판정, F9/SCREEN_MATCH/5초 TTL/최대1회 클릭 및 실제 popup 소멸 확인을 요구한다. 표시 유지 후 변경된 geometry 때문에 이 실행 경로가 계속 차단될 수 있다.

이 분리를 재사용하고 native 관측 모델만 확장하는 편이 단순 조건 삭제나 새 상태 기계보다 범위가 작다. ROOT action을 passive descendant로 오인하지 않고, ROOT structural container의 표시 연속성이라는 별도 증거로만 사용한다. source/fresh 수집을 통합하거나 Android cache/native generation 문제를 해결한 변경은 아니다.

## 초기 action 분류와 허용/거부 (P2 보완 전 기록)

- A 내부 `NativeActionSummary`에 full-list presence, 고정된 navigation subset, 나머지 action 존재, 분류 가능 여부만 보관한다. 원본 native ID/라벨/좌표/화면 문자열을 새로 보관·출력하지 않는다. navigation subset은 FOCUS, CLEAR_FOCUS, ACCESSIBILITY_FOCUS, CLEAR_ACCESSIBILITY_FOCUS, SHOW_ON_SCREEN의 정확한 조합이며 순서에는 의존하지 않는다.
- `SHOW_ON_SCREEN`은 Android에서 scroll을 요청할 수 있는 action이다. 이를 호출하거나 부작용이 없다고 인증하지 않는다. 해당 capability가 존재하는 동일 ROOT를 읽는 것만 허용한다. 실제 scroll/focus/터치 이벤트 처리와 이후 트리/frame 비교는 그대로다.
- source collector는 기존 `actionList.map { id }`의 동일 IDs를 DTO projection과 분류에 재사용한다. fresh port의 선택된 Debug display read는 기존 actionList 한 번에서 각 ID를 최대64개까지 복사하고 그 값으로 분류와 CLICK/DISMISS projection을 함께 만든다. 이전 short-circuit projection보다 개별 `getId`가 더 읽힐 수 있으므로 'getter 수가 완전히 동일'하다고 주장하지 않는다. 새 actionList/root/window/screen 조회, Binder IPC, OCR, polling, clock, retry는 없다. Release와 ordinary F9 read는 이전 actionList projection 그대로다.
- 기존 owner/각 descriptor 객체 참조/개수 결합, caller-list 복사, 관측된 재사용 뒤 UNKNOWN latch를 그대로 사용한다. original Ready의 실제 snapshot과 fresh target tree 목록에 독립 결합하고 background read가 target metadata를 대체하지 않는다. raw Android 노드를 metadata로 전달하지 않는다.
- 실제 Debug Demo가 선택된 뒤 summary가 UNKNOWN/누락/다른 owner면 거부. navigation subset이 바뀌면 presence가 둘 다 PRESENT여도 `POLICY_NATIVE_ACTIONS_CHANGED`. 선택된 strict SAME fallback에도 이 비교가 적용된다. 기존 presence-only PRESENT는 action 종류 증거로 사용할 수 없다.
- Geometry 예외의 ROOT는 여전히 `clickable=false`, supported action 없음, blank exact FrameLayout/LinearLayout, root/window 일치, 양쪽 동일하게 확인된 empty 또는 navigation-only summary를 요구한다. CLICK, DISMISS, LONG_CLICK, SCROLL, SET_TEXT, custom/그 외 action, 불명/중복/잘못된 ID/한도 초과는 ROOT geometry 예외를 거부한다. 존재하는 action을 NONE으로 바꾸지 않는다.
- 자식 container·본문의 passive 조건은 native NONE 그대로다. 자식에 navigation만 있어도 이동 허용 증거가 되지 않는다. 모든 geometry 경로는 같은 그래프/양쪽 부모 포함/가림/쌍 비교 한도 검사를 거친다. 버튼 독립 이동·교체, 내용·수·순서·경로·ID·class·clickable·enabled·projected action 변화, 다른 창/frame/package/대상 불일치, 보호 내용은 계속 거부한다.
- 정확한 target 순수 BOUNDS 이벤트에서만 Debug 예외를 선택한다. CONTENT/STATE/ADDED/REMOVED/mixed/다른 창은 대상이 아니다. Release feature=false의 기존 strict 경로, 동의·사용자 조작 무효화, TTL, SCREEN_MATCH, C 30초 lease, cleanup ADDED/HEALTH 제한은 수정하지 않았다.

## 진단 의미

기존 한 줄 `A_MANUAL_DISPLAY_REVIEW` formatter를 재사용한다. `DEMO_BOUNDS=POLICY_ROOT_CLICKABLE`은 원본/fresh 중 적어도 하나가 clickable임을 뜻한다. `POLICY_ROOT_INTERACTIVE`는 ROOT supported action 또는 허용되지 않거나 종류를 증명하지 못한 native capability가 남았음을 뜻한다. `POLICY_NATIVE_ACTIONS_UNCONFIRMED`는 누락/불명/모순/결합 실패, `POLICY_NATIVE_ACTIONS_CHANGED`는 관측된 presence 또는 summary 분류 차이다. 실제 Android action ID나 어느 화면의 값인지 출력하지 않는다. 새 로그/반복 로그/진단 IPC는 없다. Release formatter는 무동작 그대로다.

표시 정책이 통과하면 기존 `POLICY_RETAIN_ROOT_RESIZE/TRANSLATION/PASSIVE_REFLOW`를 쓴다. 뒤의 native-after/현재성/시간/해제 실패가 있으면 최종 UNAVAILABLE이므로 정책 사유 단독으로 성공이라 하지 않는다.

## 초기 ROOT action 분류 회귀·검증 기록

신규 `ManualRootDisplayCapabilitiesTest` 18개는 실제 classifier/factory/selector/registered read/retire/compare/terminal 경로를 기존 중첩 Android형 fixture와 함께 검증한다. 종류·정확한 navigation 집합·정보 누락/불명/중복·owner/목록 재사용·root clickable·모든 geometry 경로·자식 action·버튼/팝업/다른 창/보호 내용·실제 variant gate·비bounds 이벤트·timeout/native-after/cancel+cleanup·ManualChoiceRefresh 및 실제 F9 PRE_CLICK 실패/클릭0회를 다룬다. 기존 회귀의 root clickable 사유만 새 정확한 enum으로 갱신했다. 그 밖의 거부 기대값은 유지한다.

실행 결과:

- 신규18개 추가, 대상117개 Debug/Release 각각 통과. 공식 SHOW_ON_SCREEN 상수를 사용하는 최종 코드도 전체 실행에 포함했다.
- 앱 JVM Debug **1,431개**, Release **1,416개**, 실패/오류/skip0. 호스트55개·합성 앱26개는 기존 UP-TO-DATE 결과 재사용(실패/오류/skip0).
- 앱 Debug/Release APK 재빌드, 앱·호스트 AndroidTest APK 및 호스트/합성 앱 Debug 빌드 태스크 통과. 변경 없는 산출물의 UP-TO-DATE와 실제 Android 실행을 구분한다.
- 앱 Debug/Release·호스트 Debug Lint 이슈0. 최종 `BUILD SUCCESSFUL in 4m 4s`, 250 task 중34 실행/216 UP-TO-DATE. 전체 lintRelease와 중복되는 lintVital task만 SKIPPED이며 JVM skip은0이다.
- APK/DEX 패키징 격리 및 새 JVM 시험 class의 운영 APK 부재 확인. DEMO_BOUNDS/NODE_MISMATCH/TARGET_BINDING/SCREEN_MATCH formatter marker는 Debug에만 있고 Release에는 없다.
- 기존 CRLF를 보존했다. 일반 diff --check의 CR-at-EOL 진단과 실제 후행 space/tab을 byte로 구분했다. `git -c core.whitespace=cr-at-eol diff --check` 및 빈 index의 staged 공백 검사 통과.
- 수정은 A source4개(collector/NativeNodeActions/F9 reader/Demo policy), JVM2개(기존 Rig와 신규18개 시험), 문서2개(이 문서와 이전 문서의 후속 링크), **총8개**. 별도 미커밋 구조 검토 문서는 동일 byte로 보존했고 다른9개 worktree와 나머지 tracked 파일은 불변이다.
- 로그는 저장소 밖 `/workspace/a-p01-root-capabilities-target.log`, `/workspace/a-p01-root-capabilities-full.log`. HEAD는 기준7574ea3 그대로이며 index는 비어 있다. commit/push/merge/기기 실행 없음.

 JVM은 Android 실제 ROOT action 목록, native 동일 세대, 실제 렌더링/터치/사용자 화면을 인증하지 않는다.

## 남은 제한과 삼성 판정

이번 실제 ROOT가 clickable인지, navigation-only인지, LONG_CLICK/SCROLL/custom action도 있는지는 미확정이다. root action을 허용해도 자식 action/overlap/geometry/currentness/native listing 때문에 계속 거부될 수 있다. native action 집합이 focus 상태에 따라 바뀌는 경우도 보수적으로 거부한다. 관측상 같은 descriptor를 갖는 popup 교체나 native cache의 ABA를 증명한 정책은 아니다. pixel opacity/z-order와 임의 handler 의미도 인증하지 않는다.

Cleanup `RETIRED_ID_EVENT/ADDED/HEALTH`는 별도 미해결이다. ADDED를 removal receipt로 바꾸거나 HEALTH를 초기화하지 않았다. 이번에는 삼성 실행을 요청하거나 수행하지 않는다. 향후 실측에서 최종 `RESULT=BOUNDS_RETAINED`와 6초 표시/음성 완료를 함께 확인해야 표시 경로를 관측했다고 할 수 있다. 실제 닫기는 별도 새 F0/B/F9/SCREEN_MATCH 및 실제 popup 소멸 증거가 필요하다.


## 전체 native action 집합 P2 후속 수정

### 수정 전 재현과 원인

저장소 밖 snapshot/patch 백업을 만들고 기존8개 작업 파일과 구조 검토 문서를 보존한 상태에서 시작했다. 실제 공유 classifier → selector → retire → registered read → assess → retain/remove 경로의 JVM 회귀5개를 먼저 추가했다. production 코드 수정 전 **5개 모두 실패**했다. 원본 `CLICK + LONG_CLICK`, fresh `CLICK + SCROLL_FORWARD`의 요약이 같아 BOUNDS_RETAINED가 발생했고, 동일 custom ID와 presence-only PRESENT도 승인될 수 있었다. 코드/화면의 실기기 성공을 재현한 시험이 아니라 누락된 거부 계약의 실패다. 로그 `/workspace/a-p01-exact-actions-red.log` 및 XML `/workspace/a-p01-exact-actions-red.xml`을 저장소 밖에 보존했다.

### 정확 비교와 정보 불완전 거부

`NativeActionSummary`의 `navigation/other` 요약 대신 **최대64개 ID의 비공개 정렬된 복사 목록**을 사용한다. 원본 caller 목록과 참조를 공유하지 않고, 외부 getter나 mutable 컬렉션/AccessibilityAction을 반환하지 않는다. 전체 목록을 구조적으로 비교하므로 hash나 분류 category의 충돌로 동일성을 판단하지 않는다. action 순서 변화는 같은 집합이며, 중복은 정렬 전에 UNKNOWN으로 거부한다. 정확한 빈 전체 목록만 NONE이다. `presenceOnly(PRESENT)`는 기존 존재 관측 API로 남아도 `hasExactKnownSet=false`이므로 승인 증거가 될 수 없다.

원본/fresh 전체 노드에서 정확하고 알려진 집합이어야 하며, 같은 row의 CLICK/DISMISS projection도 DTO와 양방향으로 일치해야 한다. 미관측/누락/정보 불완전/잘못된 owner·descriptor 결합/한도 초과/중복/미지원 ID는 `POLICY_NATIVE_ACTIONS_UNCONFIRMED`, 유효한 두 집합의 차이는 `POLICY_NATIVE_ACTIONS_CHANGED`로 거부한다. unknown==unknown 같은 객체 동등성을 승인으로 쓰지 않는다.

지원 platform subset은 기존 Android 기본 action22종과 SHOW_ON_SCREEN이다. 여기 없는 custom/미확인 action 및 아직 명시적으로 지원하지 않은 새 framework action도 전체 Demo 유지 요청을 거부한다. 알려진 ID라는 이유만으로 앱 handler의 의미를 인증하지 않는다. 같은 표준 ID에 사용자 정의 label을 붙인 경우도 확인된 기본 action이라고 가정하지 않는다. source/fresh 둘 다 선택된 Debug 수집에서 **같은 frozen action 참조**의 `getLabel()!=null` 여부를 확인하고, 하나라도 해당하면 UNKNOWN이다. label 문자열 길이/내용은 읽거나 복사하지 않고 원본 CharSequence를 metadata에 저장하지 않는다. ID·label·텍스트·좌표·토큰을 새로 출력하지 않는다.

이 label 존재 검사는 Debug 수집에서 최대64개의 **추가 getLabel 로컬 getter**를 사용한다. 플랫폼 구현은 immutable action의 mLabel을 반환하며, 별도 화면/root/window/actionList 조회·Binder IPC·clock·polling·OCR·재시도는 추가하지 않았다. 기존100ms 예산과 마지막 native/currentness/release 검사가 그대로 적용되므로 늘어난 처리 비용으로 실패할 수 있으며 0비용이라고 주장하지 않는다. Release/ordinary F9 read는 이 추가 복사·분류·getLabel을 실행하지 않는다. 실제 Android API의 getter/캐시는 JVM에서 실행한 검증이 아니다.

### ROOT 및 실행 조건

ROOT의 `clickable=false`, blank FrameLayout/LinearLayout, supported action 없음, 정확한 양쪽 navigation-only 또는 empty 집합, 기존 window/package/frame/전체 노드 구조·내용·부모 포함·다른 창·가림 검사는 유지한다. ROOT의 클릭/long-click/dismiss/scroll/custom action을 navigation으로 바꾸지 않는다. ROOT 예외는 자식 passive 조건을 완화하지 않는다.

root-only resize, 전체 translation, passive reflow와 selected Debug strict SAME fallback이 모두 같은 정확 집합 검사를 통과해야 한다. unselected/Release strict 계약은 그대로다. 표시 검증 전에 실행 증거를 retire하고 원본 token/time을 보존한다. 선택 후 C 실제 제거·새 F0·엄격한 snapshot/target 비교·B·F9/SCREEN_MATCH/원래5초 TTL/최대1회 클릭/실제 popup 소멸 검증도 수정하지 않았다. Cleanup ADDED/HEALTH를 우회하지 않았다.

### 회귀 범위와 결과

이번에 회귀12개(수정 전 실패5개 포함)를 기존 ROOT 시험에 추가했다. ROOT 시험은 누적30개다. 다른3개 기존 fixture도 presence-only에서 실제 mock ID 목록으로 바꾸어 검증되지 않은 PRESENT를 허가로 쓰지 않게 했다. 이는 production의 DTO 기반 action 추정이 아니며, fixture는 선언한 synthetic native 입력을 제공할 뿐이다. 기존 unknown/누락/거부 기대값은 완화하지 않았다. 추가 projection 모순이나 unknown ID가 있는 옛 fixture는 더 이른 UNCONFIRMED 거부 사유로 구분했다.

회귀는 두 집합의 같은 개수/다른 ID, 모든 네 경로의 교체, 정확한 기본 집합의 순서 독립 일치, ROOT navigation 추가/삭제/교체, custom/labelled/unknown/누락·중복·과다 및 DTO projection 모순, 원본/fresh 객체 결합, caller mutation, false retention 뒤 클릭0회를 다룬다. 새 회귀만 통과시키기 위해 판정 기준을 완화하지 않는다.

최종 검증 결과(이전 ROOT 검증과 구별):

- 수정 전 신규 회귀5개 모두 실패 → 수정 후 같은5개 모두 Debug/Release 통과. 기존 fixture와 projection이 모순되던 중간 검사1건은 더 이른 UNCONFIRMED 거부로 구분하고, 일관된 ROOT CLICK/DISMISS 별도 회귀도 추가했다. 거부를 성공으로 바꾸지 않았다.
- 이번 신규12개, ROOT 누적30개, 관련 대상129개가 Debug/Release 각각 통과. 앱 전체 Debug **1,443개**, Release **1,428개**, 실패/오류/skip0.
- 호스트55개·합성 앱26개는 UP-TO-DATE 결과 재사용, 실패/오류/skip0.
- 앱 Debug/Release APK 재빌드, 앱/호스트 AndroidTest APK 및 호스트/합성 앱 Debug 빌드 태스크 통과. 실제 Android 계측 실행은 아니다.
- 앱 Debug/Release 및 호스트 Debug Lint 이슈0. 전체 `BUILD SUCCESSFUL in 3m 59s`, 250 task 중30 실행/220 UP-TO-DATE. 중복 lintVital report/release 작업만 SKIPPED이며 JVM skip은0.
- APK/DEX 패키징·시험 class 격리 및 Release 진단 formatter 부재 확인. Release의 feature=false/sidecar=null 경로가 새 label 읽기·metadata 생성을 실행하지 않는다. 공용 클래스/조건부 getter의 bytecode 존재 자체와 실제 실행 여부를 구별한다.
- CRLF 보존. `git -c core.whitespace=cr-at-eol diff --check`, 빈 index의 staged 검사 통과, 실제 추가 space/tab 후행 공백 없음.
- 이번 실제 수정10개: A source4개(collector/NativeNodeActions/F9 reader/Demo policy), JVM5개(Root/P2/BoundsPolicy/BoundsRejection/SamsungSequence), 이 결과 문서1개. 앞선 passive 문서 링크까지 포함한 누적 작업은 **11개**다. 원래8개 작업을 초기화·삭제하지 않았고 기존 구조 검토 문서와 다른9개 worktree를 보존했다.
- 전체 로그 `/workspace/a-p01-exact-actions-full.log`, 대상 `/workspace/a-p01-exact-actions-target-final.log`, red 로그/XML은 앞의 경로에 보존했다. HEAD7574ea3 불변, index 비어 있음. commit/push/merge/삼성 재실행 없음.

정확한 관측 집합 비교와 Debug 표시 유지 계약 범위에서 커밋 가능한 상태로 검토한다. native generation·캐시·렌더링·실기기 해결 완료 인증은 아니다.

### 여전히 인증하지 못하는 사항

native action ID/label이 동일한데 앱 handler 의미만 바뀌거나 Android가 목록 일부를 누락하는 경우까지 인증할 수 없다. same-value native/cache ABA·동일 표현의 popup 교체, pixel opacity/z-order, 사용자 기기/OEM 이벤트 순서는 여전히 미입증이다. 원본 rootInActiveWindow/fresh window.root 차이, 불완전/역전/clipped geometry, 다른 창 개입, native ownership/currentness/timeout/cleanup 실패는 계속 거부된다. 삼성 ROOT의 실제 전체 action 목록이 지원 subset과 unlabelled 조건을 만족하는지 미확정이므로 해결 성공을 보장하지 않는다. 삼성/LG/에뮬레이터 재실행은 하지 않았다.
