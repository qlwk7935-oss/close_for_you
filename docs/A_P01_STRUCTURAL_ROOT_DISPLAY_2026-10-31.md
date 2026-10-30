# P01 구조적 ROOT의 표시 연속성 — 2026-10-31

기준 `fb4278b1b6579f3eb31549b06df874650db1158e`, `fix/a-samsung-p01-lifetime-20261028`. 사용자 삼성 로그의 SHOWN → 약142ms 뒤 `POLICY_ROOT_INTERACTIVE`를 계기로 작성했다. 이 작업에서는 삼성/LG/에뮬레이터 재실행을 하지 않았다. 실제 삼성 ROOT의 action 종류를 확인했다고 주장하지 않는다.

## 이전 경계와 이번 최소 구현

기준 코드의 해당 거부는 ROOT clickable 검사와 전체 native action 집합의 정확 비교 뒤 발생한다. `oldRoot.actions.isNotEmpty()`는 CLICK/DISMISS projection이고, `!rootNavigationOnly`는 동일한 전체 집합에 좁은 navigation subset 밖의 표준 action이 있다는 뜻이었다. 따라서 최신 로그는 정상적인 수집/비교 실패가 아니라 이 별도 ROOT 제한에서 거부된 것으로 좁혀진다. 실제 action이 SELECT였는지 LONG_CLICK이었는지는 로그만으로 구별하지 못한다.

이번에는 세 판단을 분리했다.

1. **관측 가능한 구조적 ROOT 역할:** 양쪽 path가0이고, 보고된 className이 정확히 android.widget.FrameLayout/LinearLayout이며, non-clickable이고 text/description이 blank인 컨테이너다. root bounds가 그쪽의 실제 APPLICATION window bounds와 일치해야 한다. 전체 불변 descriptor 비교, canonical parent/path 그래프, 양쪽 부모 포함 및 bounded geometry 검증도 끝까지 통과해야 한다. className 한 값이나 같은 window ID만으로 승인하지 않는다. 실제 Java class/native 동일 세대 인증은 아니다.
2. **팝업·닫기 버튼의 관측상 동일성 및 capability:** 전체 원본/fresh package/window/frame/다른 창/노드 수·순서·경로·ID·class·내용·enabled·clickable·supported actions 및 정확한 전체 native action 집합을 비교한다. 기존 보호 내용/단일 B 후보 E2/역할·라벨·경로·동작·가시성·같은 버튼/가림·소유권 검사를 유지한다. 정보 불명/교체/불일치는 계속 거부한다.
3. **실행 권한:** 기존 retire → read → retain/remove 경계를 재사용한다. BOUNDS_RETAINED는 C/TTS 제거 콜백을 호출하지 않을 뿐이며, Raw Runtime/F9 증거를 복구하거나 원본 token/시각/5초 TTL을 갱신하지 않는다. 사용자 선택 후 실제 C 제거 → 새 F0 → 기존 엄격한 ManualChoiceRefresh → B → F9/SCREEN_MATCH/5초 TTL/최대1회 클릭/실제 popup 소멸 검증은 그대로다.

새 상태 기계, 공통 계약, 자동 실행 연결, collector/Android getter/IPC/clock/polling/재시도 또는 새 진단 필드를 만들지 않았다. 기존 순수 policy의 역할/능력/geometry 검사와 BOUNDS_RETAINED 결과를 사용한다. 실제 Android driver인 ManualDisplayReview/ManualPopupConnection은 이미 이 수명 분리를 제공하므로 바꾸지 않았다.

## ROOT capability의 제한

NativeActionSummary의 전체 ID 불변 목록·원본/fresh 객체 결합·label 불명 거부는 그대로다. 기존 좁은 navigation 조회와 별도로 `hasOnlyStructuralRootCapabilities` 조회만 추가했다.

| capability | 구조적 ROOT 표시 정책 |
|---|---|
| FOCUS/CLEAR_FOCUS | 기존처럼 존재 관측만 허용. 실제 포커스 이동을 호출하지 않음 |
| ACCESSIBILITY_FOCUS/CLEAR_ACCESSIBILITY_FOCUS | 기존처럼 존재 관측만 허용. 사용자 탐색 이벤트 정책 불변 |
| SHOW_ON_SCREEN | 기존처럼 존재 관측만 허용. scroll을 일으킬 수 있는 동작을 호출하지 않음 |
| SELECT/CLEAR_SELECTION | 이번에 ROOT 역할이 별도로 입증된 경우만 추가. 선택 state 설정/해제 capability이며 이를 실행하지 않음 |
| CLICK/LONG_CLICK/DISMISS/SCROLL/SET_TEXT/COPY/PASTE/EXPAND 등 | ROOT 유지 예외에 허용하지 않음 |
| custom/unknown/미지원 ID, 사용자 정의 label, 중복/누락/불완전/한도 초과 | 기존대로 UNKNOWN 또는 UNCONFIRMED로 거부 |

참고로 보관된 AOSP View 구현은 일반 View에도 ACTION_SELECT와 ACTION_CLEAR_SELECTION을 기본 추가한다(`/workspace/a-p01-platform-contracts/View.java` 11403 부근). ACTION_SELECT/CLEAR_SELECTION handler는 선택 state를 설정/해제한다(15956 부근). 이 플랫폼 참고 사실이 이번 삼성 action 목록이나 삼성 handler의 의미를 증명하는 것은 아니다. 전체 ID 집합이 같다는 것도 앱 handler 의미의 인증은 아니다. 이 정책은 어떤 action도 호출하지 않고, 구조적으로 같은 팝업 안내를 계속 표시할 수 있는 관측 계약만 다룬다.

ROOT의 capabilities는 PRESENT 그대로다. NONE/passive로 바꾸지 않는다. CLICK/DISMISS projection 검사도 삭제하지 않았다. ROOT 역할 판정과 별도로 capability 제한이 통과해야 하며, 그 뒤에도 모든 graph/geometry/상호작용 노드 검증이 필요하다. 중간 검사 통과를 COMPLETE/Ready/eligibility로 만들지 않는다.

## geometry·이벤트·수명 경계

root-only resize, 전체 노드의 정확한 translation, 제한된 passive reflow라는 기존 세 관계만 유지한다. ROOT/window가 같다는 이유만으로 geometry를 승인하지 않는다. 역전·빈·화면/창 밖·부모 포함 실패·비일관 이동은 거부하며 clamp/오차/재보정은 없다.

자식 container/본문은 여전히 native NONE과 기존 class/역할 조건을 만족해야 reflow가 가능하다. ROOT 예외는 parent index0에서만 구조적 역할을 사용하며, 나머지 parent와 자식에 전파하지 않는다. 따라서 Android 기본 SELECT 등이 자식에 있고 해당 자식이 움직이거나 reflow parent인 경우에도 계속 거부될 수 있다. 다른 interactive 노드/닫기 버튼의 독립 이동·크기 변경, 형제 가림은 기존대로 실패한다. 증명된 부모 포함 관계만 가림 검사에서 구분한다.

정확한 대상의 순수 WINDOWS_CHANGE_BOUNDS에서 선택된 Debug Demo만 새 역할을 사용한다. Release strict, CONTENT/STATE/ADDED/REMOVED/혼합 이벤트, 다른 창의 이벤트 처리에는 새 예외가 없다. native-before/after/C generation/동의/epoch/사용자 조작/100ms/해제 검사는 그대로다. DEADLINE이나 native-after가 실패하면 내부 유지 코드가 있어도 최종 UNAVAILABLE/제거이며, 취소·정리 실패는 성공으로 바뀌지 않는다.

Cleanup RETIRED_ID_EVENT/ADDED/HEALTH는 별도 미해결이며 우회하지 않았다. 실패한 health를 초기화하거나 ADDED를 removal receipt로 바꾸지 않는다.

## 회귀와 검증

기존 공유 Rig와 실제 selector → retire → registered read → 전체 comparison → terminal retain/remove 경계를 사용한다. 새 구조 ROOT positive 회귀1개는 production 수정 전 실패했고 `/workspace/a-p01-structural-display-red.log`와 XML에 보존했다. 새로운 Root 시험은 synthetic native 목록이며 삼성 계측이 아니다.

회귀는 Frame/Linear ROOT의 세 geometry 관계, ROOT 역할과 capability 분리, clickable/금지 action, 추가·삭제·교체된 집합, custom/labelled/불완전 metadata, 버튼 역할/라벨/동작/가시성/독립 이동, passive body와 자식 예외 금지, 부모 그래프/geometry/형제 겹침, popup 교체·보호 내용·다른 창·frame/native ownership, 반복 이벤트의 원본 보존, fresh ManualChoiceRefresh/F9 실패·클릭0, deadline/native-after/취소+cleanup, 실제 variant gate를 포함한다. 기존 display fixture에는 유지 성공 시 prompt가 남고 speech stop 콜백이 호출되지 않는 회귀도 추가했다. 실제 TTS 음성/Android 창은 JVM으로 인증하지 않는다.

최종 로컬 검증 결과:

- 신규19개(새 Root 시험18개와 기존 prompt/speech fixture1개). 수정 전 실패한 positive 회귀도 수정 후 Debug/Release 통과. 기존 회귀 기대값을 완화하지 않았다.
- 관련 대상 **148개** Debug/Release 각각 통과. 전체 앱 JVM Debug **1,462개**, Release **1,447개**, 실패/오류/skip0.
- 호스트55개·합성 앱26개는 UP-TO-DATE 결과 재사용, XML 실패/오류/skip0.
- 앱 Debug/Release APK 재빌드, 앱/호스트 AndroidTest APK 및 호스트/합성 앱 Debug assemble 태스크 통과(변경 없는 산출물은 UP-TO-DATE). 실제 Android 계측 실행은 아니다.
- 앱 Debug/Release·호스트 Debug Lint issue0. 전체 `BUILD SUCCESSFUL in 3m 37s`, 250 task 중26 실행/224 UP-TO-DATE. 중복 lintVital report/release만 SKIPPED이며 JVM skip은0이다.
- APK/DEX 격리 검사 통과. 새 JVM 시험 class는 운영 APK DEX에 정의되지 않는다. 기존 DEMO_BOUNDS/NODE_MISMATCH/TARGET_BINDING/SCREEN_MATCH formatter marker는 Debug에만 있고 Release에는 없다.
- CRLF 원본 불변, `git -c core.whitespace=cr-at-eol diff --check` 및 빈 index의 staged 공백 검사 통과. 실제 추가 space/tab 후행 공백 없음.
- 실제 수정5개: NativeNodeActions.kt, ManualDemoBoundsPolicy.kt, ManualDemoBoundsPolicyTest.kt, 신규 ManualStructuralRootDisplayTest.kt, 이 결과 문서. ManualDisplayReview/ManualPopupConnection/ManualChoiceRefresh/F9/collector/B/C/D/공통 DTO/Manifest/gradle.properties와 나머지 tracked 파일은 그대로다.
- 로그는 저장소 밖 `/workspace/a-p01-structural-display-red.log`, `/workspace/a-p01-structural-display-target.log`, `/workspace/a-p01-structural-display-full.log`. HEAD fb4278b 불변, index 비어 있음. 기존 구조 검토 문서 및 다른9개 worktree의 HEAD/status/미커밋 파일 hash를 보존했다.

새로운 P0~P2 결함은 이 검토/회귀 범위에서 발견하지 않았다. 검증한 Debug 표시 연속성 계약 범위에서 커밋 가능한 상태이며, 삼성 성공 또는 실제 닫기 완료라는 뜻은 아니다.

## 남은 삼성 조건과 범위

삼성 ROOT가 새로 인정한 capability subset이고 모든 역할/동일성/geometry 검사를 만족하면 기존 ROOT 거부를 넘을 수 있다. 실제 ROOT가 LONG_CLICK/SCROLL/CLICK, custom/labelled action, 다른 class/내용, 불확실한 native 소유권을 가지면 여전히 실패해야 한다. 현재 삼성의 정확한 action 종류와 자식 변화는 미확정이다. 자식의 기본 capability, 다음 parent/가림 검사, 원본/fresh reader 표현 차이, native/cache ABA 때문에도 계속 거부될 수 있다. 따라서 삼성 해결 성공이나 6초 유지/TTS 완료를 주장하지 않는다.

표시가 유지돼도 geometry가 원본과 달라진 채 남으면 엄격한 ManualChoiceRefresh에서 거부될 수 있다. 새 F0 또는 F9/SCREEN_MATCH/독립 배경 TTL/cleanup에서 거부되면 실제 클릭0이다. 이번 작업에서 그 실행 계약을 완화하지 않았다. Native 세대·pixel occlusion·같은 descriptor의 popup 교체·custom handler 의미를 완전히 입증하지 못하는 기존 한계도 유지한다.

기존 미추적 A_P01_STRUCTURAL_ROOT_REVIEW_2026-10-30.md, 다른9개 worktree 및 나머지 tracked 파일을 보존했다. Commit/push/main 병합/기기 재실행 없음.
