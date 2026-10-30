# 삼성 P01 ROOT 확장·대상 창 선택 조사 — 2026-10-30

## 기준과 실제 증거

- worktree `/workspace/a-samsung-p01`, branch `fix/a-samsung-p01-lifetime-20261028`, 기준 HEAD `a0f7d4f3d84e8d5b1e51d57fd299c817cf5858c8`, 시작 clean.
- 사용자 제공 삼성 실측(14:06:14 ATTEMPT=1): SHOWN 후 약 126ms에 WINDOW_SET/BOUNDS, DISPLAY_REVIEW COMPLETE, CHANGED/NODES_CHANGED/BOUNDS, ROOT/EXPANDED/NOT_TRANSLATION/OTHER_CHANGE. LIVE_COUNT=NONE/NOT_ENUMERATED. 이어 제거, cleanup RETIRED_ID_EVENT/ADDED.
- EXPANDED는 새 root Rect가 원본 Rect를 포함하고 적어도 한 edge가 달라졌다는 수치 관계다. 원점·비율·clipping 원인이나 무해함의 인증이 아니다.
- NOT_TRANSLATION 분기는 같은 노드 수(1..1200), 모든 위치의 path/viewId/class/text/description/clickable/enabled/actions 일치, 양수 bounds 확인 이후에만 도달한다. 첫 root의 bounds뿐 아니라 다른 노드의 bounds도 전부 같다는 뜻은 아니다. native handle/창 세대의 동일성은 이 DTO에 없다.
- OTHER_CHANGE는 원본/새 대상 창의 고유 행이 있고 token과 fresh의 window ID가 같으며 창 Rect가 다른 경우다. EXPANDED가 translation이 아니므로 노드 이동 offset과의 비교는 성립하지 않는다. 창의 확대/축소 방향은 이 코드만으로 모른다.
- 이번 수정본의 삼성·LG·에뮬레이터 실측은 하지 않았다. 원인 해결/실제 선택창 유지/실제 클릭 성공이라고 주장하지 않는다.

## 원본과 재검증의 실제 선택 경로

| 항목 | 원본 수집 | DISPLAY_REVIEW 재검증 |
|---|---|---|
| 진입 | Service.capture → collector.capture → result.snapshot → f9.collected → manual.offer | manual.reviewDisplayedEvent → 등록/native-before → screen.verifyDisplayedPopup |
| 창 목록 | service.windows | windowSource() = service.windows, 시작/끝 두 기존 조회 |
| 대상 선택 | 먼저 rootInActiveWindow. root.windowId와 일치하는 창 행; framework 창은 single 확인 | 원본 token.windowId와 일치하는 유일한 APPLICATION 창. 현재 active 앱으로 임의 교체하지 않음 |
| root 출처 | service.rootInActiveWindow | 선택한 AccessibilityWindowInfo.root. 일반 read/PRE_CLICK/PRESS는 별도 activeRoot() 경로 |
| root/package | 원본 root package로 packageGate, 같은 앱 배경 root까지 보호 순회 | root.windowId == 선택한 창 ID, root.packageName == 원본 token package, 허용/보호 검사. 자식에도 package/window ID 검사 |
| display | 원본 대상 창 default display. requireCaptureIsolation=true이면 전체 목록도 default 확인 | 모든 창 displayId==0 필수. DTO에는 display 값 자체를 보관하지 않음 |
| type/layer | 원본 대상 APPLICATION, 비APPLICATION active/focused 차단. 다른 같은 앱 창은 보호 검사에 사용 | 대상 APPLICATION. 배경은 별도 낮은 layer·포함 bounds. LISTED이면 native C layer 우위, NOT_ENUMERATED이면 C 원격 창 부재와 target active+focused 필수 |
| node/frame | 원본 대상 root의 preorder path 0부터 순회, 다른 창의 노드를 target 목록에 합치지 않음. getBoundsInScreen 원본 edge 복사 | 같은 root 순회 방식·screen bounds API, 양수/frame 포함 및 endpoint 창 목록·frame·root package/ID 검사. 대상 노드에 배경/C 노드를 합치지 않음 |

source는 evidence 표시용 snapshot으로 바뀌지 않고 최초 collector의 snapshot이다. ManualDisplayContinuity.assess는 complete/target/geometry/native 충돌/package/window/frame을 먼저 검사하고 다음에 nodes 전체 equality를 비교한다. 따라서 최신 NODES_CHANGED는 이 선행 검사들을 통과한 경로다. 뒤에 있는 전체 windows equality가 통과했다는 뜻은 아니다.

`rootInActiveWindow`와 `window.root`는 다른 API 경계다. 원본은 root와 창 목록을 별도 조회하고 DTO를 만들며, fresh는 원본 ID에 결합해 window.root를 읽는다. 원본에서 개별 getter가 여러 번 호출되므로 하나의 원자적 Android 상태를 인증할 수 없다. fresh endpoint도 package/ID/frame/창 목록을 검사하지만 root의 native node identity 전체를 인증하지는 않는다. 이 차이는 확인된 구조이며, 이번 삼성 실패 원인이라는 증거는 아니다.

공개 AOSP android-15.0.0_r1의 [AccessibilityInteractionClient](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityInteractionClient.java)(372 부근)는 active-root 조회에 ACTIVE_WINDOW_ID/ROOT_NODE_ID/bypassCache=false를 사용하고, [AccessibilityWindowInfo](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityWindowInfo.java)(247 부근)는 구체적인 창 ID/ROOT_NODE_ID/bypassCache=true를 사용한다. 이는 API 내부 cache 정책의 차이이며 **원본 조회가 실제 cache hit였다는 증거는 아니다**. 캐시 lookup의 ID 조건/미스·서버 조회도 있으므로 단순히 원본이 오래됐다고 확정할 수 없다. [AccessibilityCache](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityCache.java)(276 부근)는 일반 WINDOWS_CHANGED에서 cache를 비우는 경로도 가진다. 삼성 OS/OEM의 동일 구현·실제 cache 상태는 미검증이다. root API만 같게 바꾸어 원인이 해결된다고 판단하지 않는다.

## 확정 사실과 가설

확정: nominal screen 좌표 API는 양쪽 모두 getBoundsInScreen이다. A의 window-local 전환·inset 가산/감산·dp 변환·clamp는 없다. 이번 fresh는 원본과 다른 ID/package를 자유롭게 선택하는 경로가 아니며, COMPLETE와 NODES_CHANGED 이전의 ID/package/frame 검사를 통과했다. ROOT는 확대 포함 관계이고 대상 창 Rect도 다르다.

가능하지만 이번 실행에서 미확인:

- 동일한 대상 창의 실제 layout/크기/위치 변화. P01 앱의 Dialog.show/setLayout도 실제 layout 연산이다.
- rootInActiveWindow와 window.root의 독립 조회 또는 framework cache/provider 상태 차이. 서로 다른 native root가 같은 ID/서술자를 가질 가능성은 DTO만으로 배제할 수 없다.
- parent scroll/clipping, window origin/inset, app/embedded transform, magnification/OEM 접근성 표현 상태 변화. 이전 bounds 조사 문서의 플랫폼 근거를 따르며 TalkBack ON이 확대 사용 증거는 아니다.
- 동일 숫자 창 ID의 재사용. snapshot에는 native 창 세대가 없으므로 동일 ID만으로 배제할 수 없다.

C 자체가 대상 bounds를 바꿨거나 F9가 잘못된 루트를 골랐다고 확정하지 않는다. root/window가 같이 바뀌어도 무해한 좌표 표현 차이라는 증거가 아니다. collector root를 무조건 window.root로 교체하거나 bounds 무시/보정으로 통과시키지 않는다.

## 최소 추가 Debug 진단

기존 첫 BOUNDS 진단 코드/같은 한 줄에 아래 다섯 enum 필드를 추가한다. 별도 reporter나 수집 경로를 만들지 않는다. 기존 immutable source/fresh DTO와 이미 찾은 두 target window 행만 사용한다. root 관계는 각 목록 첫 항목의 path가 0인 경우에만 설명하고 없으면 UNAVAILABLE이다. 새 getter·시계·IPC·OCR·polling·화면 조작은 없다.

| 필드 | 고정 범주와 한계 |
|---|---|
| TARGET_BINDING | SAME_ID_APPLICATION / WINDOW_ID_MISMATCH / PACKAGE_MISMATCH / TYPE_MISMATCH / UNAVAILABLE / UNKNOWN. DTO의 같은 ID·package·APPLICATION 확인이며 native 창/root 세대 인증이 아니다. 여러 불일치는 목록 부재 → ID → package → type 순서다. |
| TARGET_FLAGS | SAME / ACTIVE_CHANGED / FOCUSED_CHANGED / BOTH_CHANGED / UNAVAILABLE / UNKNOWN. 같은 두 창 행의 active/focused 값 비교. |
| TARGET_LAYER | SAME / HIGHER / LOWER / UNAVAILABLE / UNKNOWN. 대상 창 자체의 이전/현재 layer 비교. 값·다른 창 ID는 출력하지 않는다. |
| ROOT_WINDOW_BEFORE | EQUAL / ROOT_WITHIN_WINDOW / WINDOW_WITHIN_ROOT / PARTIAL_OVERLAP / DISJOINT / INVALID_BOUNDS / UNAVAILABLE / UNKNOWN. 원본 root Rect와 원본 대상 창 Rect의 관계. |
| ROOT_WINDOW_AFTER | 같은 범주. 새 root Rect와 새 대상 창 Rect의 관계. 이 관계가 같아도 실제 화면 동일성/무해함/자동 실행 증거가 아니다. |

기존 COMMON_SHIFT의 대응 검사를 재사용해 root 텍스트·경로·class 정보나 중복 root field 진단은 추가하지 않는다. 반환 진단 객체는 enum 필드만 보유하고 source/root/창 객체·문자열·좌표·ID·offset을 추가 보관하지 않는다. 최대 기존 1200노드/32창 한도, bounded DTO 처리 비용만 있다. Release의 trace==null/분석 비활성 경로는 유지하며 formatter는 Debug source set에만 있다. 진단 오류는 기존 UNKNOWN/첫 BOUNDS 보존 경계를 따른다.

아래는 **형식 예시이며 실제 삼성 관측값이 아니다**:

```text
A_MANUAL_DISPLAY_REVIEW;ATTEMPT=1;EVENT=WINDOW_SET;MASK=BOUNDS;RESULT=CHANGED;POINT=COMPARISON;DETAIL=NODES_CHANGED;NODE_MISMATCH=BOUNDS;BOUNDS_NODE=ROOT;BOUNDS_CHANGE=EXPANDED;COMMON_SHIFT=NOT_TRANSLATION;WINDOW_BOUNDS=OTHER_CHANGE;TARGET_BINDING=SAME_ID_APPLICATION;TARGET_FLAGS=SAME;TARGET_LAYER=SAME;ROOT_WINDOW_BEFORE=EQUAL;ROOT_WINDOW_AFTER=EQUAL
```

실제 출력에는 기존 LIVE_COUNT/NATIVE_LISTING 등의 필드도 그대로 포함된다. 두 EQUAL은 root와 창이 함께 달라진 패턴과 양립하며 native 정체성/변화 원인의 인증이 아니다. LIVE_COUNT=NONE은 원격 소유권 성공으로 바꾸지 않는다.

## 별도 cleanup ADDED 문제와 보호

MASK=ADDED는 removal receipt나 native wait-only 허용 집합에 없다. retiring ID의 해당 이벤트는 기존 RETIRED_ID_EVENT로 실패하며 나중 detach/ABSENT만으로 HEALTH를 되살릴 수 없다. 실제 지연 생성 알림인지 재등장/ID 재사용인지는 미확정이다. 이번 변경에서 OverlayCleanupBarrier, ManualRetirementEvent, native lifetime을 수정하지 않았다.

검증 후 제거 완료에도 native 실제 detach/remove 반환, 전체 창 부재, 원래 cleanup deadline, handler 완료가 필요하다. F0/F9·SCREEN_MATCH·evidence 5초·표시30초·최대1회 클릭·위험 버튼 거부·자동 실행/SKIP/F12 OFF는 유지한다. P23/B/C/D/다른 worktree/공통 DTO/Manifest/gradle.properties 변경 없음. 창 선택 API나 비교·거부·취소·cleanup 순서는 수정하지 않았다.

## 회귀와 다음 실측

신규 JVM은 실제 ManualDisplayContinuity.assess → probe의 첫 bounds 요약 → reviewManualDisplay의 거부/제거·terminal record를 사용한다. 확대 root/window, 창 불변/확대/겹침/분리, layer/flags 차이, 원본 root 부재, ID/package/type 조기 거부, ambiguous window, 최초 코드 보존, 로그 취소, Debug 분석 off와 같은 판단, 고정 enum 보유를 검사한다. flags/잘못된 binding의 helper 입력 테스트는 DTO 설명 함수만 검증하며 실제 target 검사는 이를 노드 비교 전에 거부함을 별도로 검사한다. 합성 입력의 수치 패턴 재현은 실제 삼성 원인 재현이 아니다.

다음 삼성 실측이 필요하다. 기존 P01 Debug에서 동일 ATTEMPT의 기존 fields + 신규 다섯 필드와 이어지는 cleanup FIRST/MASK/NATIVE/RESULT를 함께 확인한다. 추가로 원문·창 ID·좌표 dump를 요구하지 않는다. 다른 대상/팝업 변화는 여전히 차단한다. 새로운 로그만으로도 원자적 native identity나 원인을 인증할 수 없으면 해결 완료로 간주하지 않는다.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "<삼성 테스트폰 serial>"
& $adb -s $serial logcat -v brief -s A_ManualPrompt:I '*:S'
```

## 최종 로컬 검증

실제 로컬 완료 결과는 아래에 기록한다. 실기기/에뮬레이터 계측 결과가 아니다.

- 신규 공유 진단 회귀 14개: 최종 코드의 Debug/Release JVM 양쪽에서 통과.
- 앱 전체 JVM Debug 1,331개 / Release 1,316개, 실패·오류·skip 0. 양쪽에 포함된 회귀를 고유 테스트 수로 더하지 않는다.
- 호스트 JVM 55개·합성 팝업 앱 JVM 26개는 이번 명령에서 UP-TO-DATE 결과 재사용, XML의 실패·오류·skip 0 확인.
- 앱 Debug/Release APK 빌드 성공. 앱 AndroidTest와 호스트 Debug/AndroidTest·합성 앱 Debug는 동일 검증 명령에서 UP-TO-DATE 결과 재사용.
- 앱 Debug/Release·호스트 Debug Lint 성공, 각 XML issue 0.
- 기존 패키징·DEX 격리 검사 통과. 신규 5개 및 기존 BOUNDS/NODE_MISMATCH/SCREEN_MATCH 로그 marker는 Debug DEX에만 있고 Release DEX에 없다. 신규 JVM 테스트 클래스는 두 앱 APK에 없다.
- 최종 Gradle BUILD SUCCESSFUL: 3분 51초, 250 task 중 38 실행·212 UP-TO-DATE. 로그 `/workspace/a-p01-target-root-full.log`는 저장소 밖에 있다.
- collector/service/F9/cleanup/TTL/공통 DTO/B/C/D/Manifest/properties를 포함한 범위 밖 모든 추적 파일 hash, 다른 9개 worktree HEAD/status/기존 변경 hash를 작업 시작 기록과 비교해 보존 확인.
- 삼성폰·에뮬레이터 실행 없음. 새로운 원인 확정 또는 선택창 유지/팝업 닫힘 성공이 아니다. 커밋·스테이징·푸시·병합하지 않았다.

변경 파일은 수정 2개·신규 2개다.

1. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualBoundsMismatchDiagnostics.kt`
2. `app/src/debug/java/kr/closeforyou/accessibility/integration/ManualPromptLog.kt`
3. `app/src/test/java/kr/closeforyou/accessibility/integration/ManualBoundsTargetDiagnosticsTest.kt` (신규)
4. `docs/A_P01_TARGET_ROOT_DIAGNOSTICS_2026-10-30.md` (신규)
