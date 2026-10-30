# P01 최초 거부 leaf의 capability·geometry 진단 — 2026-10-31

기준 HEAD `60fc40c5e369b7c615bc21a9695272400b189c7c`, 브랜치 `fix/a-samsung-p01-lifetime-20261028`.
삼성 사용자 실측의 `NONCLICK_NATIVE_CAPABILITY / NATIVE_ACTION / POLICY_INTERACTIVE_BOUNDS`를 구체화한다.
이번 작업은 진단만 추가하며 삼성/LG/에뮬레이터를 실행하지 않는다. 표시 유지 또는 실제 닫기 성공을 주장하지 않는다.

## 실제 연결

기존 checkedLayout의 최초 거부 hook·probe·ATTEMPT당1회 trace를 그대로 사용한다.
그 시점에 이미 수집한 original/fresh DTO 및 그 객체에 결합된 전체 native action 메타데이터만 검사한다.
기존 guard가 먼저 전체 action 집합의 정확한 동등성을 확인하며, 진단도 원본 snapshot/현재 node-list owner와 각 descriptor 참조를 확인한다.
원시 Android 객체, 새로운 getter·수집·IPC·OCR·시계·폴링·재시도는 추가하지 않는다.

NativeActionSummary의 private ID 목록은 계속 비공개다. 고정 그룹에 대한 포함 여부 조회만 추가했다.
NativeNodeActions에는 읽기 전용 observedSummary를 추가했다. 잘못된 진단 조회가 기존 정책의 invalidation latch를 바꾸거나 이미 invalidated된 자료를 복구하지 않는다.
기존 summary/equals, 수집기, ROOT/자식 정책, F0/B/F9/SCREEN_MATCH/5초 TTL/최대1회 클릭은 변경하지 않는다.

## 기존 한 줄에 추가하는 필드

예시이며 실제 삼성 관측 결과가 아니다.

```text
A_MANUAL_DISPLAY_REVIEW;ATTEMPT=1;...;DEMO_BOUNDS=POLICY_INTERACTIVE_BOUNDS;INTERACTIVE_NODE=NONCLICK_NATIVE_CAPABILITY;INTERACTIVE_CAUSES=NATIVE_ACTION;REJECTED_ROLE=TEXTVIEW_LEAF;CAPABILITY_PROFILE=NAVIGATION_AND_SELECTION_STATE_AND_TEXT_NAVIGATION_SELECTION;CLOSE_GEOMETRY=UNCHANGED;OTHER_OPERABLE_GEOMETRY=UNCHANGED
```

- `REJECTED_ROLE`: TEXTVIEW_LEAF는 정확한 android.widget.TextView class 및 leaf 관계, EMPTY_LAYOUT_LEAF는 정확한 FrameLayout/LinearLayout·빈 text/description·leaf 관계를 뜻한다. 나머지는 OTHER, class가 null/blank이면 UNKNOWN이다. 실제 Java class, passive 동작, 비편집 본문 또는 안전한 사용자 선택이라는 인증이 아니다.
- `CAPABILITY_PROFILE`: 아래 표의 고정 범주이다. 원본/fresh의 private 전체 ID 동등성 검사를 대체하지 않고 어떤 action도 실행하지 않는다.
- `CLOSE_GEOMETRY`: 실제 B 단일 닫기 후보와 원본 node binding을 확인한 뒤 원본/fresh bounds의 완전 동등성을 비교한다. 이동·크기 변화는 CHANGED이며 보정하지 않는다.
- `OTHER_OPERABLE_GEOMETRY`: **ROOT·닫기 후보·최초 거부 행을 제외**한 나머지 중 clickable·DTO action·native action이 있는 노드의 bounds를 검사한다. native action의 탐색/선택 capability도 보수적으로 포함한다. 거부 자식이나 ROOT가 움직였다는 이유만으로 CHANGED가 되지 않는다. 첫 거부 행이 닫기 후보 또는 다른 클릭 요소여도 같은 행을 중복 계산하지 않는다.

`CLOSE_GEOMETRY`의 UNCHANGED/CHANGED/UNKNOWN은 그대로다.
`OTHER_OPERABLE_GEOMETRY`는 별도 enum을 사용한다. NO_TARGETS는 제외 후 검사 대상이0개, UNCHANGED는 대상이1개 이상이고 모두 고정, CHANGED는 대상 중 하나 이상 bounds 변경, UNKNOWN은 결합/비교 정보를 확인할 수 없음을 뜻한다.
NO_TARGETS가 CLOSE_GEOMETRY로 출력되는 경로는 없다. 원시 개수나 노드 식별자는 출력하지 않는다.
전체 최대1200행을 검사하며 CHANGED를 보아도 나머지 metadata 검사를 계속한다. 뒤쪽에 불확실한 metadata가 있으면 UNKNOWN이 우선한다.
제외되는 ROOT/닫기 후보/최초 거부 행도 객체 결합·action 동등성·비교 정보 검사는 생략하지 않는다. 제외는 OTHER의 geometry 검사 대상에만 적용한다.
원본/fresh metadata가 없거나 다른 객체에 결합됐거나 전체 ID·projection·descriptor가 달라지면 분류/geometry를 확정하지 않는다.
기존 정책의 앞선 guard에서 거부되는 경우에는 이 최초 interactive 진단 자체가 만들어지지 않을 수 있다.

| 프로파일 | 존재하는 action 그룹 |
|---|---|
| NAVIGATION | FOCUS/CLEAR_FOCUS, ACCESSIBILITY_FOCUS/CLEAR_ACCESSIBILITY_FOCUS, SHOW_ON_SCREEN 중 하나 이상만 있음 |
| SELECTION_STATE | SELECT/CLEAR_SELECTION 중 하나 이상만 있음 |
| TEXT_NAVIGATION_SELECTION | NEXT/PREVIOUS_AT_MOVEMENT_GRANULARITY, SET_SELECTION 중 하나 이상만 있음 |
| NAVIGATION_AND_SELECTION_STATE | 첫 두 그룹 혼합 |
| NAVIGATION_AND_TEXT_NAVIGATION_SELECTION | 첫째·셋째 그룹 혼합 |
| SELECTION_STATE_AND_TEXT_NAVIGATION_SELECTION | 둘째·셋째 그룹 혼합 |
| NAVIGATION_AND_SELECTION_STATE_AND_TEXT_NAVIGATION_SELECTION | 세 그룹 모두 혼합 |
| OTHER_STANDARD | 지원되는 전체 standard 집합이지만 위 세 그룹은 전혀 포함하지 않음. CLICK/LONG_CLICK/DISMISS/SCROLL 등도 포함 가능하며 안전함을 뜻하지 않음 |
| UNKNOWN | 기타 standard와 위 그룹의 혼합, custom/labelled/불완전/미지원 목록 등. 빈 집합도 요청된 프로파일 범주가 없어 UNKNOWN으로 표시함 |

FOCUS/SHOW_ON_SCREEN은 포커스·스크롤에 영향을 줄 수 있고 SELECT/CLEAR_SELECTION/SET_SELECTION도 상태를 바꿀 수 있다. 이들은 일반적인 passive action으로 승인되지 않는다.
프로파일이 같아도 전체 action ID 집합이 다르면 기존 정책이 계속 거부한다.

## 비용·보안·Release

거부가 확정된 행에서만 최대1200행 DTO/metadata 순회를 한 번 추가한다. private profile 조회는 기존 최대64개 action 목록으로 제한된다.
변경된 노드 경로를 포함한 임시 경로 집합은 호출 스택 안에서만 사용하고 반환 객체에는 enum6개만 남는다.
원문·description·viewId·class 원문·경로·좌표·action ID·hash·token·native 객체·예외는 출력하지 않는다.
작은 처리 비용은 존재하며 실제100ms 경계에 가까우면 후속 timeout 분류에 영향을 줄 수 있다. 예산을 늘리지 않으며 해당 경로는 이미 표시 거부가 확정된 상태다.
분류·그룹 상수·enum·formatter는 Debug variant에만 있다. Release는 null 반환 stub과 기존 trace=null/Demo OFF 정책을 유지한다.
기존 출력 예외 격리, 최초 실패 보존, 취소·cleanup 전파와 ATTEMPT당 최초 관련1건 제한은 유지한다.

## 다음 결정 기준과 남는 한계

- CLOSE_GEOMETRY=CHANGED면 A안의 버튼 고정 조건을 만족하지 못한다. 버튼 이동을 예외로 승인하지 않고 B안의 표시 전 안정성 확인을 검토한다.
- TEXTVIEW_LEAF 또는 EMPTY_LAYOUT_LEAF이며 group이 제한된 관측값이면 A안 검토의 근거가 추가된다. 그러나 class/profile 자체는 A 허가가 아니다. 정확한 전체 집합·팝업/버튼/내용/상태·부모/가림/소유권 증거와 별도 회귀가 필요하다.
- OTHER_STANDARD/UNKNOWN 또는 OTHER/UNKNOWN 역할이면 해당 자식에 대한 일반적인 passive 예외를 적용하지 않는다.
- OTHER_OPERABLE_GEOMETRY=UNCHANGED 또는 NO_TARGETS라면 세 제외 대상을 뺀 나머지의 bounds 변화는 관측되지 않았다. CLOSE_GEOMETRY도 UNCHANGED이고 역할/profile이 제한된 경우 A안 검토의 근거가 된다. 여전히 가림·보호·상태·동일성의 증명은 별개다.
- OTHER_OPERABLE_GEOMETRY=CHANGED이면 제외 대상 밖의 상호작용 가능 노드가 변했다. 특정 버튼이 움직였다고 단정하지 않으며 A안의 '다른 상호작용 노드 고정' 조건을 만족하지 못하므로 B안 또는 거부 유지를 검토한다. UNKNOWN은 NO_TARGETS/UNCHANGED로 해석하지 않는다.
- 원본/fresh 비교는 원자적 native 세대/handler 의미 인증이 아니며, C 생성이 geometry 변화의 원인이라는 증명도 아니다. selected/focused/scrollable 등 보관되지 않은 상태를 이번 진단이 새로 입증하지 않는다.
- 기존 전체 translation 표시 허용, Cleanup RETIRED_ID_EVENT/ADDED/HEALTH 및 Release 엄격 정책은 이번 작업에서 바꾸지 않는다. 실제 cleanup 실패와 실제 닫기 미검증은 남는다.

검증 결과는 최종 작업 보고와 아래 기록에 구분한다. 커밋·푸시·main 병합 없음.

## 최초 4필드 구현 검증 — 이번 OTHER 의미 수정 전 기록

- 신규 Debug 회귀19개 추가. 기존18개 Debug interactive 회귀 및2개 Release 무동작 회귀와 함께 대상39개 통과.
- 앱 전체 Debug1,499개 / Release1,449개, 실패·오류·skip0. 호스트55개·합성 앱26개는 기존 결과를 Gradle UP-TO-DATE로 재사용했으며 재실행했다고 주장하지 않는다.
- JDK17 offline 전체 작업 BUILD SUCCESSFUL(3분56초). 앱 Debug/Release APK·AndroidTest APK 처리 통과. 앱 Debug/Release·호스트 Debug Lint issue0.
- 기존 패키징/DEX 격리 검사 통과. 새 역할/profile/geometry enum과 네 필드 formatter marker는 Debug에만 있으며 Release APK에는 없다. Release bytecode는 metadata/profile/Logcat 작업 없이 null을 반환하는 stub이다. JVM 회귀 class도 운영 APK에 없다.
- CRLF-aware working/index 공백 검사 및 신규 파일 space/tab 후행 공백 검사 통과. 원래 줄바꿈과 기존 구조 검토 문서·다른9개 worktree를 보존했다.
- 검증 로그는 저장소 밖 `/workspace/a-p01-native-profile-target.log`, `/workspace/a-p01-native-profile-full.log`에 있다. 실제 삼성/LG/Android 계측은 미실행이며 A안 정책을 구현하거나 실제 popup을 닫지 않았다.

## OTHER_OPERABLE_GEOMETRY 의미 수정 검증

- 기존8개 파일을 보존한 채 Debug 진단·두 Debug 회귀·이 문서4개만 추가 수정했다. ROOT/닫기 후보/최초 거부 행은 geometry 집계에서만 제외했다. REJECTED_ROLE·CAPABILITY_PROFILE의 enum 및 분류 코드는 수정 전과 정확히 동일하다.
- NO_TARGETS를 가진 별도 ManualOtherOperableGeometry 타입을 추가해 CLOSE_GEOMETRY의 세 값과 구분했다. 클릭 가능한 최초 거부 행과 닫기 후보가 같아도 중복 계산하지 않는다.
- 이번4개 회귀 추가로 관련 Debug41개·Release2개 대상43개 통과. ROOT-only 변화 시 거짓 거부 진단 없음, 최초 거부만 변경, 다른 버튼 변경, 닫기 후보 변경, 검사 대상0개와 모두 고정 구별, 제외된 행의 metadata 불확실성도 거부하는 경우를 검증했다.
- 앱 전체 Debug1,503개 / Release1,449개, 실패·오류·skip0. 호스트55개·합성 앱26개는 UP-TO-DATE 재사용이다.
- JDK17 offline BUILD SUCCESSFUL(2분31초). Debug/Release APK·AndroidTest 컴파일/빌드 처리 통과. 앱 Debug/Release·호스트 Debug Lint issue0. Release 분석 일부는 UP-TO-DATE다.
- APK/DEX 격리 및 새 OTHER enum/formatter의 Debug 전용 포함 확인. Release null stub 무동작 유지. 기존 CRLF 보존, working/index 공백 및 신규 파일 space/tab 후행 공백 검사 통과.
- 로그는 저장소 밖 `/workspace/a-p01-other-geometry-target.log`, `/workspace/a-p01-other-geometry-full.log`에 있다. HEAD와 index는 변경하지 않았고 삼성/LG/에뮬레이터 재실행·커밋·푸시·main 병합은 하지 않았다.
