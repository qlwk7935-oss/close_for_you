# 삼성 P01 Demo 선택/첫 거부 원인 분리 — 2026-10-30

## 판정과 변경 범위

기준 HEAD `ce3cc4286dc8693aa3fc17ad5f0cb10e6a7e609f`, branch `fix/a-samsung-p01-lifetime-20261028`, worktree `/workspace/a-samsung-p01`. 기존 미커밋 구조 검토 문서는 원형 보존한다. 커밋·푸시·병합·다른 worktree 수정 없음.

**17:55 삼성 실행의 정확한 거부 원인은 아직 미확정이다.** 기존 CHANGED/NODES_CHANGED/ROOT bounds 로그는 Demo가 미선택되어 strict로 간 경우와 Demo의 retains=false 뒤 strict로 간 경우에 공통으로 나온다. 따라서 이번에는 허용 범위를 넓히지 않고 기존 한 줄에 `DEMO_BOUNDS` enum **하나만** 추가한다. 판정과 현재성·cleanup 순서는 유지한다. 실제 P01 원인 수정/삼성 해결이라고 주장하지 않는다.

## 코드로 확인한 사실

- ManualPopupConnection은 admission을 통과한 실제 `DecisionCandidateWiring` B 분석을 저장한다. C 표시 이후 그 token과 original/detection token을 비교한다. SHOWN 자체는 close 후보가 정확히 하나라는 증거가 아니다.
- Debug feature와 callback 연결이 활성화되고, 같은 target ID의 정확한 WINDOWS_CHANGE_BOUNDS 이벤트이며, 분석·detection token이 같고 `signals == {NEW_WINDOW}`, 후보가 한 개일 때만 Demo scan을 선택한다.
- 현재 PopupCandidateDetector는 NEW_WINDOW를 추가하면 SAME_WINDOW_OVERLAY를 추가하지 않고, fallback 신호도 빈 집합일 때만 추가한다. 그러므로 정상 detector의 NEW_WINDOW 결과는 singleton이다. 이 제한이 이번 실패 원인이라고 확인된 것은 아니다.
- AndroidF9ScreenPort의 read/strict 선행 검사 이후, nodes가 다르고 후보 요청이 있는 경우에만 retains를 호출한다. retains가 false면 기존 first-node 진단/CHANGED로 끝난다. nodes가 같거나 선행 단계에서 실패하면 retains가 실행되지 않을 수 있다.
- 기존 로그의 TARGET_*는 주로 **대상 창**의 값이다. 다른 창 목록/순서·background/system 창의 metadata, 모든 자식 bounds·가시성, 원본의 잘못된 geometry, 실제 B 후보 수/등급/원문 라벨은 입증하지 않는다. ROOT에서 멈춘 첫 NODE_MISMATCH만으로 이후 모든 필드가 같다고 단정하지 않는다.

## 기존 정책의 실제 제한과 합성 테스트의 공백

기존 정책은 다음을 모두 요구한다. 이 조건은 이번에 변경하지 않았다.

1. complete, 같은 package/window/frame, 노드 수·한도·고유 path, 고유한 bounded APPLICATION 창. 다른 창과 target type/layer/필요 flags 일치.
2. root가 path 0·nonclickable이며 양 시점 각각 window Rect와 같음. 모든 원본/현재 노드가 양수이고 각 target window 안에 포함됨. **보이지 않는 노드도 생략하지 않는다.** 원본 DTO에는 isVisibleToUser 정보가 없으므로 원본 노드가 왜 잘못된 bounds였는지 추정하지 않는다.
3. 모든 노드의 비bounds 필드(경로/ID/class/원문/description/clickable/enabled/actions)가 정확히 일치함.
4. B 후보가 단일 E2/CLOSE, 현재 F9의 정확한 닫기 라벨, 원본 ID/bounds·경로·CLICK·widget·enabled/clickable과 일치하고 fresh visiblePaths에 있어야 함. 모든 라벨이 닫기여야 하며 다른 action-label 후보나 보호 본문이 없어야 함.
5. 전체 노드가 target window와 정확히 같이 이동하거나, 실제 root/window 크기가 달라지되 **모든 자식 bounds가 그대로**여야 함.

이전 주된 합성 DTO는 root/text/close 3개 수준이었다. 실제 P01 소스는 title/body/광고 이동/닫기 버튼을 column에 넣은 Dialog이고 Android는 decor/content 같은 추가 계층을 노출할 수 있다. root가 커질 때 내부 container나 본문 영역도 달라지면 기존 정책은 거부한다. 새 중첩 fixture(여러 Frame/Linear container + title/body + 이동/닫기 두 버튼, 실제 B 후보 생성)로 다음을 재현했다:

- root/window만 확대: SELECTED → POLICY_RETAIN_ROOT_RESIZE → BOUNDS_RETAINED.
- 동일 구조/본문/닫기 버튼인데 내부 container/body가 재배치됨: SELECTED → POLICY_DESCENDANT_BOUNDS → CHANGED/NODES_CHANGED/ROOT BOUNDS.
- 원본의 빈/영역 밖 node: POLICY_ORIGINAL_NODE_GEOMETRY. 현재의 영역 밖 node와 candidate invisibility는 각각 다른 코드.
- B는 공용 라벨 규칙상 공백 포함 닫기와 E1_DISMISS도 후보로 만들 수 있지만 기존 Demo는 정확한 F9 라벨/E2를 요구하므로 각각 POLICY_CANDIDATE_LABEL, POLICY_CANDIDATE_ROLE_EVIDENCE로 거부될 수 있음.

이는 코드상 가능한 거부와 기존 테스트의 공백을 확인한 것이다. **삼성의 실제 tree/후보/가시성/geometry가 이 fixture와 같다는 증거는 아니다.** 불명확한 노드/라벨을 허용하거나 E1을 실행 가능으로 바꾸지 않는다.

## 단일 추가 필드: 실제 코드 경계의 첫 사유

새 getter·수집·clock·OCR·IPC·polling·루프 로그는 없다. 기존 비교 결과를 나눠 동일한 boolean 결과와 함께 고정 enum을 보고한다. Debug probe는 NOT_CHECKED → SELECTED → 첫 policy 결과까지만 갱신하고 뒤의 검사/정리로 덮지 않는다. callback 실패는 기존 diagnosticWrite로 격리한다. report 값은 어떤 승인 분기에도 사용하지 않는다.

| DEMO_BOUNDS 값 | 의미 |
|---|---|
| SELECT_DISABLED / SELECT_EVENT / SELECT_TARGET | 기능/callback이 비활성, 순수 bounds 이벤트 아님, 이벤트 target 불일치 |
| SELECT_ANALYSIS_MISSING / SELECT_ANALYSIS_TOKEN | 실제 표시 분석 부재/token 불일치 |
| SELECT_DETECTION_MISSING / SELECT_DETECTION_TOKEN / SELECT_SCOPE | detection 부재/token/신호 집합 제한 |
| SELECT_NO_CANDIDATE / SELECT_MULTIPLE_CANDIDATES | 후보 0개/복수 — 정확한 수·후보 ID는 출력하지 않음 |
| SELECTED | 선택 통과. policy 결과는 아직 없음(선행 검사 실패·엄격 SAME·policy 중단 등은 POINT/DETAIL/RESULT와 함께 해석) |
| POLICY_INCOMPLETE / POLICY_BINDING / POLICY_NODE_COUNT / POLICY_WINDOW_SCOPE / POLICY_CANDIDATE_COUNT | 입력 완전성·결합·수/한도·창 목록 고유성 제한 |
| POLICY_TARGET_UNCONFIRMED / POLICY_ORIGINAL_TARGET / POLICY_WINDOW_UNCHANGED / POLICY_NATIVE_COLLISION | 대상/원본 대상 미확인, 창 Rect 불변, 원본/native ID 충돌 |
| POLICY_WINDOW_SET / POLICY_OTHER_WINDOW / POLICY_TARGET_METADATA / POLICY_WINDOW_GEOMETRY | 창 집합/NOT_ENUMERATED의 순서, 다른 창 값, target metadata, 양수·bounded frame 관계 |
| POLICY_ROOT_PATH / POLICY_ROOT_INTERACTIVE / POLICY_ROOT_WINDOW | root path·중복 path, clickable root, root/window Rect 불일치 |
| POLICY_NODE_FIELDS / POLICY_ORIGINAL_NODE_GEOMETRY / POLICY_CURRENT_NODE_GEOMETRY | 비bounds 필드 변화, 원본/현재 node의 양수·창 포함 실패. 노드 index/원문/좌표는 없음 |
| POLICY_CANDIDATE_NODE / POLICY_CANDIDATE_ROLE_EVIDENCE / POLICY_CANDIDATE_LABEL / POLICY_CANDIDATE_BINDING / POLICY_CANDIDATE_ACTION / POLICY_CANDIDATE_NOT_VISIBLE | 후보 경로, 역할/증거, 라벨, 원본 descriptor, 동작/위젯, fresh 가시성 제한 |
| POLICY_AMBIGUOUS_LABELS / POLICY_MULTIPLE_ACTION_LABELS / POLICY_PROTECTED_CONTENT | 혼합/모호 라벨, 다른 action-label 노드, 보호 내용 |
| POLICY_NONCOHERENT_TRANSLATION / POLICY_DESCENDANT_BOUNDS | 이동이 모든 노드와 일치하지 않음 / root 크기 변화와 자식 geometry 변화 동반 |
| POLICY_RETAIN_TRANSLATION / POLICY_RETAIN_ROOT_RESIZE | **policy 내부만 통과**. 최종 native/currentness/deadline·해제 실패로 RESULT는 UNAVAILABLE일 수 있음 |
| NOT_CHECKED | 관측 코드 없음(Release 진단 비활성 포함). 성공 근거 아님 |

첫 사유만 출력하므로 뒤에도 다른 위반이 있을 수 있다. 기존 RESULT/DETAIL/NODE_MISMATCH와 ownership/cleanup 필드는 그대로다. SELECT_*와 POLICY_* 외 원시 값은 새로 저장/출력하지 않는다. 분석 실패/취소의 예외 원문도 출력하지 않는다.

## 내부 reflow 표시 정책의 가능한 다음 단위 — 아직 미적용

POLICY_DESCENDANT_BOUNDS가 실제로 확인되고 다른 보호·소유권·완전성·후보 조건을 통과한 경우에 한해 다음의 **표시 전용** 검증을 설계할 수 있다:

- 같은 package/유일한 APPLICATION 창·고정 parent/path 그래프·전체 node 수/ID/class/텍스트/description/클릭/동작을 대조한다. 다른 창의 개입/metadata 변화나 관측된 교체는 거부한다.
- 모든 노드를 유지한 채 원본/현재의 양수·frame/window/검증 가능한 parent 포함 관계를 검사한다. 숨김 노드라는 추정으로 잘못된 geometry를 생략·보정하지 않는다.
- 실제 표시한 B 후보와 fresh에서 다시 확인한 명확한 닫기 후보가 역할/라벨/경로/ID/동작상 유일해야 한다. 버튼이나 다른 interactive target의 독립 재배치는 허용하지 않는다. passive container/body 영역의 reflow만 별도 허용 대상으로 검토한다.
- old visibility/parent geometry가 없거나 매핑·native 세대가 불확실하면 완료 인증을 만들지 않는다. 관측되지 않은 동일 descriptor의 교체/ABA 한계는 남는다. 같은 ID 또는 라벨만으로 승인하지 않는다.
- 이 결과는 display-only이며 original token/time/analysis/eligibility를 갱신하지 않는다. 수동 선택은 기존 C 실제 제거 → 새 F0 → B → F9/SCREEN_MATCH/5초/1회 실행을 그대로 요구한다.

현재 실측은 정책 미선택/실제 내부 거부를 구별하지 못하므로 이 확장을 적용하지 않았다. 예를 들어 실패가 후보 0개/불가시성/잘못된 원본 geometry라면 reflow 완화는 해결책이 아니고 문제를 숨길 수 있다. 반대로 내부 container만 바뀌었다면 정확히 그 범위의 regression과 Android 확인을 거쳐야 한다. 현재 enum 판정으로 다음 단위를 정하며 무조건 허용 fallback은 만들지 않는다.

## 회귀와 유지한 안전 조건

기존 Demo/엄격 비교·P01 Flow 회귀는 그대로 유지한다. 신규 테스트는 실제 selector → native 전후/currentness → ManualDisplayContinuity/동일 retains → 기존 retain/remove → 동일 terminal code를 사용한다. 정상/중첩 reflow, 실제 B 후보/라벨·증거 차이, 원본/현재 geometry·visibility, selection 거부별 우선순위, 각 방어 분기의 첫 코드, 후속 timeout/native/cleanup·로그 취소와 첫 사유 보존, 진단 OFF 시 동일 판정을 검증한다. direct policy 방어 입력 테스트는 Android 선행 검사에서 먼저 막힐 수 있음을 구분한다.

Release feature=false, 서비스/Android reader의 엄격 gate는 변경하지 않는다. 새 field formatter는 Debug에만 있다. 기존 F0/F9/원본 TTL/SCREEN_MATCH/최대1회 클릭/실제 popup 소멸 확인은 변경하지 않는다. cleanup ADDED 거부/RETIRED_ID_EVENT/HEALTH는 별도 장애로 그대로 둔다. 정책 통과 자체가 cleanup 성공·실제 닫힘이 아니다. P23/B/C/D/공통 DTO/Manifest/gradle.properties 및 다른 worktree 변경 없음.

## 다음 삼성 한 시도의 판정 기준

**표시 성공:** 동일 ATTEMPT에서 최종 허용 결과(`SAME` 또는 `BOUNDS_RETAINED`) 후 선택창 6초 유지 및 TTS 완료가 확인됨. ROOT/window 변화가 재현된 Demo 경로의 통과 증거는 `RESULT=BOUNDS_RETAINED` + `DEMO_BOUNDS=POLICY_RETAIN_*`다. strict SAME은 이번 읽기에 변화가 없었다는 결과로, Demo 적용 성공과는 구분한다. 어느 경우도 클릭 성공을 의미하지 않는다.

**실패:** 그 밖의 최종 RESULT/제거/음성 중단. 같은 한 줄의 SELECT_*이면 미선택, POLICY_* 거부이면 내부 첫 제한, SELECTED이면 POINT/DETAIL의 선행/중단 경계로 구분한다. POLICY_RETAIN_*여도 RESULT=UNAVAILABLE이면 실패다. 이후 ADDED cleanup 실패는 별도로 기록한다. 실제 창 유지/청취는 JVM이나 로그 하나만으로 성공이라고 하지 않는다.

새 Debug APK가 반영된 다음 기존 단일 P01 시험의 A_ManualPrompt 로그만 확인하면 된다. 원문·좌표·ID dump나 추가 진단 명령을 요구하지 않는다. 이번 변경은 삼성/LG/에뮬레이터에서 미실행이다.

## 최종 로컬 검증

실행 완료 결과를 아래에 기록한다. 이전 검증 기록이나 실제 단말 성공과 혼동하지 않는다.

- 신규 회귀 14개 통과. 앱 전체 JVM Debug **1,364개**, Release **1,349개**, 실패·오류·skip 0. 기존 Demo/표시/전체 P01/F9 회귀 포함. 두 variant를 고유 테스트 수로 합산하지 않는다.
- 호스트55개·합성 앱26개는 이번 전체 명령에서 UP-TO-DATE 결과 재사용, XML 실패·오류·skip 0 확인.
- 앱 Debug/Release APK 빌드 성공. 앱 AndroidTest·호스트 Debug/AndroidTest·합성 앱 Debug는 UP-TO-DATE 산출물 재사용.
- 앱 Debug/Release·호스트 Debug Lint 성공, 각 XML issue 0. 기존 패키징·DEX 격리 검사 통과.
- `;DEMO_BOUNDS=` 및 기존 NODE_MISMATCH/TARGET_BINDING/SCREEN_MATCH 진단 marker는 Debug DEX에만 있고 Release DEX에는 없다. 신규 JVM regression class는 APK에 없다. Release feature gate·실행 경로는 변경하지 않았다.
- 최종 Gradle BUILD SUCCESSFUL: 3분42초, 250 task 중 38 실행/212 UP-TO-DATE. 최종 로그 `/workspace/a-p01-demo-rejection-full.log`, 대상 로그 `/workspace/a-p01-demo-rejection-target.log`는 저장소 밖에 있다.
- 일반 `git diff --check` 및 신규 파일 후행 공백/EOF 검사 통과. 기존 구조 검토 문서와 P23 포함 다른9개 worktree의 HEAD/status/기존 변경 hash 보존을 확인했다. 커밋·스테이징·푸시하지 않았다.
- 이번 수정본의 삼성/LG/에뮬레이터 계측은 미실행이다. 17:55 실측의 정확한 selector/policy 거부 원인, native tree와 source/fresh visibility/기하, 표시 유지/실제 클릭 성공은 여전히 미확정이다.

변경 파일은 수정4개·신규2개다. 보존한 기존 미커밋 구조 검토 문서는 별도다.

1. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualDemoBoundsPolicy.kt` — 같은 조건/같은 결과를 유지하며 첫 고정 사유 보고
2. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualDisplayReview.kt` — 최초 enum 보존과 실제 policy callback 연결
3. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualPopupConnection.kt` — 실제 selection/최종 report 연결
4. `app/src/debug/java/kr/closeforyou/accessibility/integration/ManualPromptLog.kt` — 기존 한 줄에 DEMO_BOUNDS만 추가
5. `app/src/test/java/kr/closeforyou/accessibility/integration/ManualDemoBoundsRejectionTest.kt` (신규)
6. `docs/A_P01_DEMO_BOUNDS_REJECTION_2026-10-30.md` (신규)
