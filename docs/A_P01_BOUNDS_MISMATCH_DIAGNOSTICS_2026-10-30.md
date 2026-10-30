# 삼성 P01 bounds 불일치 형태 진단 — 2026-10-30

## 기준·확정 사실

- worktree `/workspace/a-samsung-p01`, branch `fix/a-samsung-p01-lifetime-20261028`.
- 기준 HEAD `940874fcd8bd2c2a284c70269080a6690094e1d8`, 시작 clean.
- 사용자 제공 삼성 실측: 2026-10-30 13:34:55 ATTEMPT=1. SHOWN 뒤 131ms에 DISPLAY_REVIEW 시작, 읽기 COMPLETE, CHANGED/NODES_CHANGED/NODE_MISMATCH=BOUNDS, LIVE_COUNT=NONE/NATIVE_LISTING=NOT_ENUMERATED. 이후 제거·TTS 중단, MASK=ADDED의 RETIRED_ID_EVENT/cleanup 실패.
- 노드 개수와 그 이전 위치들의 DTO, 첫 불일치 위치의 path/viewId/className/text/description/clickable/enabled는 이전 진단 순서에서 같았고 bounds에서 먼저 달랐다. 그 노드의 actions나 이후 노드, 뒤의 전체 창 비교까지 같았다는 증거는 아니다.
- 어느 노드/어느 방향/변형인지는 기존 로그로 모른다. 실제 대상 변화인지 자체 overlay 영향인지도 미확정이다. 이번에는 확정되지 않은 기하를 허용하지 않고 Debug 진단만 추가한다.
- 삼성/LG/에뮬레이터/P01 실행·커밋·푸시·PR·병합 없음. 실제 선택창 유지·닫힘 해결 완료가 아니다.

## 1. 원본·재수집 bounds의 실제 경로

원본: `CloseForYouAccessibilityService.capture()` → `AndroidSnapshotCollector.capture()`의 target-root walk → `node.bounds()` → 새 `Rect`에 `AccessibilityNodeInfo.getBoundsInScreen()` → `toScreenBounds()` → NodeSnapshot.bounds. `F9ServiceConnection.collected()` / `manual.offer()`의 original은 이 실제 원본 snapshot이다. 공개 evidence의 표시용 snapshot과 구별한다.

fresh: `ManualPopupConnection.reviewDisplayedEvent()` → 실행 증거 retire → native-before → `AndroidF9ScreenPort.verifyDisplayedPopup()` / scan(DISPLAY_REVIEW) → 같은 대상 창의 root를 소유권 확인 후 tree → `rect(node, trace)` → 새 `Rect`에 같은 `getBoundsInScreen()` → 역전 검사 → `ScreenBounds(left, top, right, bottom)` → descriptor. source와 fresh는 별도 시점이다.

양쪽 A 코드에는 dp 변환, inset 감산/가산, window-local 좌표 보정, 좌표 정렬/clamp, magnification 역변환이 없다. **같은 API의 nominal screen 좌표 도메인**을 사용하는 것은 확인했다. 각 시점의 실제 framework 변환·레이아웃·확대 상태까지 항상 같다는 증명은 아니다.

두 reader의 frame은 같은 `AndroidScreenGeometry` 설계(기본 display의 accessibility-overlay window context maximumWindowMetrics + logical getRealSize 교차 검사)를 쓴다. 객체와 read 시점은 독립적이다. 이 시도의 NODES_CHANGED 전에 `fresh.screen == original.screenBounds`는 통과했다. 그러나 같은 logical Rect가 window inset·회전·magnification spec·앱/embedded transform까지 같은 상태임을 인증하지 않는다. 이 정보를 새로 읽지 않았다.

원본 역전 Rect는 ScreenBounds 생성/기존 수집 거부 경로를 따르고 fresh는 F9ReadDetail.REVERSED_BOUNDS로 거부한다. 비양수·frame 밖 fresh 노드도 NODES_CHANGED보다 앞에서 거부된다. 보정/노드 생략/부분 성공은 없다.

## 2. 플랫폼 근거와 미확정 영향

공개 AOSP `android-15.0.0_r1` 소스를 읽었다. 이는 실제 삼성 OEM/OS 버전과 동일한 구현이라는 보증이 아니다.

- [AccessibilityNodeInfo.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityNodeInfo.java): getBoundsInScreen 계약은 screen 좌표이고 magnification scale/viewport offset이 반영됨을 명시한다(2213 부근).
- [View.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/View.java): node 초기화는 getBoundsOnScreen(bounds, true)를 사용한다(11306 부근). 부모 clipping, scroll, View matrix, WindowLeft/Top 등이 screen Rect 계산에 들어간다(9434 부근).
- [AccessibilityInteractionController.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/AccessibilityInteractionController.java): embedded host/window matrix, app scale와 screen matrix가 bounds를 변환한다(1000/1040 부근). 별도의 interactive-region 처리는 visibility를 바꾸며, 이 코드 자체가 모든 overlay 겹침을 bounds clipping으로 바꾼다는 뜻은 아니다.
- [AccessibilityWindowInfo.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityWindowInfo.java): 창의 getBoundsInScreen은 touchable Region의 bounding Rect다(404 부근). node Rect와 동일 변환의 캘리브레이션 인증은 아니다.

가능성: 실제 popup 이동·resize, 부모 viewport clipping/scroll/layout 변화, inset/window origin·앱 또는 embedded 좌표 변환, magnification/접근성 provider/OEM 표현 차이. A 코드의 좌표 복사 경로만으로 이 중 어느 것인지 확정할 수 없다. TalkBack ON 자체가 magnification 사용 증거는 아니다.

C 창은 bottom gravity의 TYPE_ACCESSIBILITY_OVERLAY, MATCH_PARENT/WRAP_CONTENT, FLAG_NOT_FOCUSABLE이다. 코드가 원본 앱 bounds를 직접 수정하거나 원본 노드를 fresh 목록에 섞지는 않는다. 이 사실만으로 framework의 layout/표현 부수 효과가 없다고 보증하지 않는다. 현재 P01 Dialog의 setLayout도 별도의 앱 layout 결정이므로 실제 이동을 자동으로 무해하다고 처리할 수 없다.

## 3. 추가한 최소 Debug 진단

기존 첫 NODE_MISMATCH=BOUNDS 분기에서 이미 보관 중인 원본/fresh DTO만 사용한다. 기존 A_MANUAL_DISPLAY_REVIEW 한 줄에 다음 4개 고정 enum을 붙인다. 별도 로그 행·수집·Android getter·OCR·IPC·시계 조회·polling은 없다.

| 필드 | 값과 의미 |
|---|---|
| BOUNDS_NODE | ROOT / BUTTON / TEXT / CONTAINER / SCROLL_CONTAINER / IMAGE / WEB / SURFACE / OTHER / UNKNOWN. ROOT는 DTO path `0` 위치이고 나머지는 알려진 className의 고정 범주다. 실제 path/class/ID는 출력하지 않으며 native identity 인증이 아니다. |
| BOUNDS_CHANGE | TRANSLATE_X / TRANSLATE_Y / TRANSLATE_XY / CONTRACTED / EXPANDED / RESHAPED / INVALID_RECT / UNKNOWN. translation은 네 edge의 정확한 같은 offset과 크기 보존, CONTRACTED/EXPANDED는 Rect 포함 관계다. clipping/확대 원인 인증이 아니다. |
| COMMON_SHIFT | ALL_NODES / PARTIAL / NON_UNIFORM / NOT_TRANSLATION / UNALIGNED / UNKNOWN. ALL은 모든 위치별 DTO의 비bounds 필드도 같고 모든 Rect가 첫 노드와 정확히 같은 이동인 경우다. PARTIAL은 같은 이동과 완전 불변 노드만 섞인 경우다. 서로 다른 이동/크기 변화는 NON_UNIFORM, 대응 필드/개수가 달라지면 UNALIGNED다. |
| WINDOW_BOUNDS | UNCHANGED / MATCHES_NODE_TRANSLATION / OTHER_CHANGE / UNAVAILABLE / UNKNOWN. 같은 대상 창의 기존 Rect끼리만 비교한다. 같은 node 이동과 같아도 실제 popup 이동일 수 있으므로 거부한다. 다른 창/포커스/layer/전체 목록의 일치 인증이 아니다. |

형식 예시이며 실제 실측값이 아니다:

```text
A_MANUAL_DISPLAY_REVIEW;ATTEMPT=1;EVENT=WINDOW_SET;MASK=BOUNDS;RESULT=CHANGED;POINT=COMPARISON;DETAIL=NODES_CHANGED;LIVE_COUNT=NONE;LIVE_QUERY=COMPLETE;LIVE_SCAN=NO_ELIGIBLE_OVERLAY;NATIVE_RELATION=UNAVAILABLE;NATIVE_LISTING=NOT_ENUMERATED;NODE_MISMATCH=BOUNDS;BOUNDS_NODE=ROOT;BOUNDS_CHANGE=TRANSLATE_Y;COMMON_SHIFT=ALL_NODES;WINDOW_BOUNDS=UNCHANGED
```

이 패턴은 실제 root layout 이동과 좌표 표현 차이 모두에서 가능하다. **ALL_NODES를 무해함/자체 C 이벤트/클릭 허가로 사용하지 않는다.** 같은 대상 창도 함께 이동했으면 MATCHES_NODE_TRANSLATION이며 여전히 CHANGED/제거다.

추가 데이터는 enum 4개뿐이다. 좌표/delta/크기/개수/index/경로/앱·창·노드 ID/원문/지문/토큰/예외를 report에 보관하지 않는다. 계산 중 delta는 지역 Long으로만 사용해 Int overflow를 피하고, 어떤 값을 보정해 DTO로 되돌리지 않는다. 최대 1,200개 기존 노드와 32개 창의 descriptor만 검사한다. 처리 비용은 bounded지만 0이라고 주장하지 않으며 기존 100ms/currentness 검사를 늘리거나 늦추지 않는다.

NODE_MISMATCH의 첫 field를 발견하는 기존 순서에서 바로 bounds callback을 부른다. 별도 첫 index 재탐색은 없다. 비bounds의 첫 불일치면 네 필드는 없다. 추가 요약 계산이 실패하면 UNKNOWN을 남겨 기존 BOUNDS와 CHANGED를 지우지 않는다. 후속 native/cleanup 실패가 첫 코드를 덮지 않는다. Debug trace가 없는 실제 Release driver는 추가 DTO 분석과 formatter/Logcat을 실행하지 않는다. 원래 equality/거부 결과는 동일하다.

## 4. cleanup MASK=ADDED와 후속 실패

운영 cleanup 코드는 이번에 변경하지 않았다. ADDED는 선택 보존·cleanup WAIT·제거 영수증 어느 것으로도 허용하지 않는다. pending retired numeric id와 같은 이벤트이면 기존 RETIRED_ID_EVENT/HEALTH 실패다. 생성의 지연 알림인지 재등장/id 재사용인지 확정할 증거가 없어 예외를 만들지 않는다.

첫 실패 뒤 barrier가 retiring을 폐기하고 failed를 유지하므로 뒤늦은 native callback/순수 REMOVED/ABSENT도 회복 권한이 아니다. 첫 ADDED를 무시한 채 '실제 창이 없어졌으니 성공'으로 만들지 않는다.

첫 실패가 없을 때도 후속 제거 완료는 다음에서 차단/대기한다:

- 세대/owner/등록/retired id 불일치 또는 native identity unavailable: RETIREMENT_UNCONFIRMED.
- id 아직 없음: WAITING_ID, 완료 아님. 실제 detach/remove 완료 전: WAITING_NATIVE_COMPLETION.
- remove 반환/HIDDEN 미확인: REMOVE_NOT_RETURNED/REMOVE_NOT_HIDDEN.
- 전체 창 목록 PRESENT: receipt 전에는 WAITING_WINDOW_ABSENCE, removal 관측 후 남아 있으면 WINDOW_PRESENT.
- 빈/과다/중복/음수 id 목록, 읽기 또는 release 예외: UNKNOWN → WINDOW_QUERY_UNKNOWN. 획득 객체는 기존 독립 해제를 시도한다. 이 read의 기존 catch는 취소 예외도 UNKNOWN으로 거부하며, 이를 클릭 또는 일반 성공으로 바꾸지 않았다.
- 기존 cleanup 5초 만료/시계 역전: DEADLINE. beforeRelease 실패: REFRESH_FAILED.
- service handler 실패: 기존 cleanupFailed/현재성 무효화. 정상 handler 이후에만 receipt/native 확인한다.

명시 선택 뒤 새 F0·B 승인·PRE_CLICK/SCREEN_MATCH·즉시 PRESS 검증·최대 1회 클릭·실제 원본 popup 소멸 확인은 그대로다. 새 기하 진단은 이 경로에 연결하지 않는다.

## 5. 보호·미해결·검증 범위

bounds/전체 노드 비교 삭제·정렬·clamp·허용 오차·offset/scale 보정·재수집 재시도·timeout 증가·HEALTH 초기화 없음. LIVE_COUNT=NONE은 여전히 원격 소유권 성공이 아니다. F0/F9·SCREEN_MATCH·evidence/page proof 5초·display 30초·cleanup 5초·위험 버튼 거부·자동 실행/SKIP/F12 OFF 유지. P23·다른 worktree·B/C/D·공통 계약·Manifest/properties 변경 없음.

미해결: 실제 삼성 bounds 변형의 종류와 출처, 초기 native 등록/원격 목록의 비원자성, NATIVE_ID_UNAVAILABLE, SYSTEM 가림, ADDED 출처, 원본14 UNOBSERVED/Scroll/Window/F0 전체 완전성·원자성. 새 진단으로도 변형 원인을 인증할 수 없으면 차단을 유지한다.

신규 JVM은 실제 `ManualDisplayContinuity.assess()` → 기존 거부/제거와 probe → terminal code를 사용한다. 공통/부분/다른 이동·변형, later metadata 불일치, 대상 창과 동시 이동, overflow·진단 실패·Debug off·최초 코드 보존, ADDED 뒤 detach/부재 및 receipt 이후 미확인을 검사한다. geometry/Android port는 합성 입력이다. 실제 Samsung clipping/magnification/overlay 부작용을 재현했다고 주장하지 않는다.

## 6. 다음 삼성 로그

변경본은 아직 로컬 미커밋이다. 수정본이 반영된 Debug APK의 기존 P01 시험에서 같은 ATTEMPT의 SHOWN → read COMPLETE → review POINT/DETAIL/NODE_MISMATCH와 위 네 필드 → cleanup FIRST/MASK/RESULT/NATIVE/OVERLAY_REMOVED를 구분한다. 첫 노드 종류/변형 형태/공통 이동/창 bounds 관계를 알 수 있지만 실제 native node identity나 변경 원인은 이 로그만으로 확정하지 않는다.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "<삼성 테스트폰 serial>"
& $adb -s $serial logcat -v brief -s A_ManualPrompt:I '*:S'
```

원문·좌표·트리 dump·추가 수집·설정 강제 변경은 필요 없다. 새 수집 API, magnification/inset getter나 별도 시간 조회를 넣지 않았다.

## 7. 최종 로컬 검증

이번 Linux 로컬 실행 결과이며 실제 삼성 실행 결과가 아니다.

- 신규 공유 경로 JVM 회귀 20개 통과. 앱 전체 Debug 1,317개 / Release 1,302개 통과, 각 variant의 실패·오류·skip 0. 같은 회귀가 두 variant에 포함되므로 고유 테스트 수로 합산하지 않는다.
- 호스트 JVM 55개, 합성 팝업 앱 JVM 26개는 이번 전체 명령에서 UP-TO-DATE 결과를 재사용했다. 실패·오류·skip 0인 기존 XML 결과를 확인했다.
- 앱 Debug/Release 및 앱 AndroidTest APK 빌드 통과. 호스트 Debug/AndroidTest와 합성 팝업 앱 Debug 빌드는 UP-TO-DATE 결과를 재사용했다.
- 앱 Debug/Release 및 호스트 Debug Lint 성공, 각 XML issue 0.
- 기존 APK 패키징·DEX 격리 검사 통과. 신규 JVM 테스트 클래스는 APK에 없으며 신규 네 필드 및 기존 NODE_MISMATCH/SCREEN_MATCH 진단 문자열은 Debug DEX에 존재하고 Release DEX에는 없다.
- 전체 Gradle 결과: BUILD SUCCESSFUL, 3분 35초, 250개 task(37 실행 / 213 UP-TO-DATE). 실행 로그는 저장소 밖 `/workspace/a-p01-bounds-mismatch-full.log`에 있다.
- P23을 포함한 다른 9개 worktree의 HEAD·status·기존 변경 파일 해시를 보존 상태와 비교했다. 변경이 없다. 커밋·스테이징·푸시는 하지 않았다.
- 삼성폰·에뮬레이터 계측은 실행하지 않았다. BOUNDS 차이의 실제 원인과 삼성에서의 신규 로그는 미검증이다.

변경 파일은 아래 여섯 개뿐이다(기존 수정 3개 / 신규 3개).

1. `app/src/debug/java/kr/closeforyou/accessibility/integration/ManualPromptLog.kt`
2. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualDisplayReview.kt`
3. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualPopupConnection.kt`
4. `app/src/main/java/kr/closeforyou/accessibility/integration/ManualBoundsMismatchDiagnostics.kt` (신규)
5. `app/src/test/java/kr/closeforyou/accessibility/integration/ManualBoundsMismatchDiagnosticsTest.kt` (신규)
6. `docs/A_P01_BOUNDS_MISMATCH_DIAGNOSTICS_2026-10-30.md` (신규)
