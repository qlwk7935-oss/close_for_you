# 이슈 #29 진단 — C 선택 창이 뜬 직후 무효화되는 원인 (C 단독, 2026-10-23)

> 대상: qlwk7935-oss/close_for_you#29 (A→B→C 비실행 통합에서 C 선택 창이 P01 위에 다 그려진 뒤 약 0.2초 만에 사라짐)
> 환경: 에뮬레이터 AVD `Pixel_8` (API 37, Play 이미지), 재현 앱 P01, C debug 시험 서비스. **A 서비스는 켜지 않았고 A·B·D·core 코드는 바꾸지 않았다.**
> 기록은 숫자·enum·참/거짓만이다. 화면 글자·창 제목 글자·노드 내용은 기록하지 않았다.

## 1. 결론

**원인은 ① 순서다.** C 창이 `addView`로 뜬 직후에는 `getWindows()`에 C 창이 아직 없다. 그래서 계약대로 "show 직후 `update()`"를 해도 C 창 id를 모르고(`-1`), C 창에서 처음 오는 `TYPE_WINDOW_STATE_CHANGED` 2개가 **`TYPE_WINDOWS_CHANGED`(창 추가)보다 먼저** 도착한다. 이 순간 `OwnOverlayWindowTracker.isOwnOverlayWindowEvent()`는 보수적으로 `false`를 돌려주므로, A는 이 이벤트를 "C 창이 아닌 창의 변화"로 처리한다.

- ② id 차이: **아니다.** 7회 모두 `event.windowId`와 `getWindows()`의 C 창 id가 같았다 (30=30, 34=34 …).
- ③ 제목·종류·패키지 불일치: **아니다.** C 창이 목록에 나타난 뒤에는 7회 모두 `type=ACCESSIBILITY_OVERLAY`, 루트 패키지=우리, 제목=고정 제목 일치로 정확히 1개가 매치됐다.
- 창 자체는 사라지지 않는다: 시험 서비스(무효화 안 함)에서는 7회 모두 3초 뒤에도 `SHOWING`이고 `dumpsys window`에 `ty=ACCESSIBILITY_OVERLAY`, `isVisible=true`로 남았다. → 우진 영상의 "사라짐"은 A의 무효화 → `dismiss()` 경로로 보는 것이 맞다.

우진 로그의 `INVALIDATED;CAUSE=EVENT` → `BOUND_EVENT_WINDOW_MISMATCH`는 "A가 토큰에 묶은 창(P01 창) id ≠ 이벤트 창(C 창) id"로 읽힌다. 위 관측과 맞지만, **A 코드(우진 로컬, 미push)를 보지 못해서 이 해석은 추정이다.**

## 2. 진단 방법

- `app/src/debug/.../ui/voice/debug/C29OverlayDiagnostics.kt` (debug 전용). `ChoicePreviewActivity`에 `--ez ui_debug_c29_diag true`를 줄 때만 켜진다.
- 켜지면 시험 서비스가 **A와 같은** 이벤트 종류(A의 `accessibility_service_config.xml` 목록 그대로), `flagReportViewIds|flagRetrieveInteractiveWindows`, `notificationTimeout=0`, 모든 패키지로 `setServiceInfo`를 잠시 바꾸고, 3초 뒤 원래 설정으로 되돌린다.
- show 직후와 이후 1초 동안 이벤트마다 한 줄(logcat 태그 `C29Diag`):
  - `th`: show 기준 처리 시각(ms), `te`: show 기준 `event.eventTime`(ms)
  - `ev`, `wid`(=`event.windowId`), `evOwnPkg`(이벤트 패키지가 우리인지), `chg`(contentChangeTypes), `wchg`(windowChanges: 1=추가, 2=제거)
  - `wins`: 그 시점 `getWindows()`의 각 창 `id:종류/L층/own=루트 패키지가 우리인지/title=match|other|null`
  - `ownA`: **A 방식**(show 직후 + `TYPE_WINDOWS_CHANGED` 때만 `update`) 트래커의 판정 — 이벤트를 받은 그 순간 값
  - `ownFresh`: **제안 1 방식**(이벤트마다 `getWindows()`로 `update` 후 판정)
- `C29OverlayDiagnostics`는 main의 `OwnOverlayWindowTracker`를 **그대로** 쓴다(main 코드 변경 없음).
- 순서: `ChoicePreviewActivity`(시나리오 0 = 광고 픽스처, 진단 켬) → 1.5초 뒤 `PopupScenarioActivity --es scenario P01` → 5초 타이머에 C 창이 P01 위에 뜸 → 6.5초 뒤 `dumpsys window`.
- 7회: TalkBack 켬 4회(1~4), TalkBack 끔 3회(5~7).

## 3. 회차별 결과 (show 직후 1초)

| 회 | TalkBack | show 직후 `getWindows()` | show 직후 A 트래커 id | 새 C 창 id | C 창 첫 이벤트 (th / te, 종류) | 그때 `ownA` | A 트래커가 id를 안 시점 (th, 이벤트) | `ownA=false`인 C 창 이벤트 | `ownFresh` 오판 | 3초 뒤 창 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 켬 | C 창 없음 (SYS 6, APP 28) | -1 | 30 | 395 / 317, STATE_CHANGED | false | 410, WINDOWS_CHANGED(추가) | 2 | 0 | 있음 |
| 2 | 켬 | **이전 C 창 30**(own=0) + SYS + APP | -1 | 34 | 386 / 355, STATE_CHANGED | false | 399, WINDOWS_CHANGED(추가) | 2 | 0 | 있음 |
| 3 | 켬 | 이전 C 창 34(own=0) + … | -1 | 38 | 422 / 379, STATE_CHANGED | false | 447, WINDOWS_CHANGED(추가) | 2 | 0 | 있음 |
| 4 | 켬 | 이전 C 창 38(own=0) + … | -1 | 42 | 480 / 433, STATE_CHANGED | false | 509, WINDOWS_CHANGED(추가) | 2 | 0 | 있음 |
| 5 | 끔 | 이전 C 창 42(own=0) + … | -1 | 46 | 412 / 368, STATE_CHANGED | false | 425, WINDOWS_CHANGED(추가) | 2 | 0 | 있음 |
| 6 | 끔 | 이전 C 창 46(own=0) + … | -1 | 50 | 418 / 380, STATE_CHANGED | false | 439, WINDOWS_CHANGED(추가) | 2 | 0 | 있음 |
| 7 | 끔 | 이전 C 창 50(own=0) + … | -1 | 54 | 502 / 363, STATE_CHANGED | **true** | 488, WINDOWS_CHANGED(**이전 창 50 제거**) | 0 | 0 | 있음 |

- show 직후 A 트래커 id는 **7회 모두 -1**.
- 7회 중 **6회**는 C 창의 첫 `STATE_CHANGED` 2개가 A 방식으로는 "C 창 아님"이었다. 7회차만 이전 C 창의 "제거" 이벤트가 늦게 처리되면서 그때 읽은 `getWindows()`에 새 C 창이 이미 있어 우연히 맞았다. → 운에 달린 순서 경쟁이다.
- `ownFresh`(이벤트마다 다시 읽기)는 **7회 모두** C 창 이벤트를 전부 C 창으로, C 창이 아닌 이벤트(SYS 창 6의 변화, 이전 C 창의 제거 이벤트)는 전부 C 창이 아닌 것으로 판정했다(오판 0).
- 이벤트 도착은 show 뒤 약 0.4~0.5초(에뮬레이터). 우진 실측의 약 0.2초와 차이는 기기·A 처리 시간 차이로 본다.
- `te`가 `th`보다 100~200ms 앞선 이벤트가 있다(같은 창의 두 번째 STATE_CHANGED). 시스템이 이벤트를 모아 늦게 보내는 것으로 보인다.

### 추가 발견: 이전 C 창이 잠깐 남아 있음
2~7회 show 직후 `getWindows()`에 **방금 지운 이전 C 창**(종류·제목은 일치, 루트 노드가 없어 own=0)이 남아 있었고, 그 창의 `WINDOWS_CHANGED(제거)` 이벤트(`wid`=이전 id)가 새 C 창 이벤트보다 먼저 왔다.
- 지금 트래커는 루트 패키지가 없어 이전 창을 매치하지 않는다(안전). 다만 루트가 아직 읽히는 타이밍이면 이전 창 id를 새 세대로 묶을 수 있다 → 제안 1에서 "C 창이 2개 보이면 매치 안 함" 규칙이 이것을 막는다(`singleOrNull`).
- A 쪽: 이전 C 창의 "제거" 이벤트도 C 창 이벤트로 인식되지 않는다. 두 번째 팝업부터는 이 이벤트가 새 토큰을 무효화할 수 있다 (A 코드 확인 필요).

## 4. 시험 서비스와 A 서비스의 차이 (확정 못 하는 부분)

| 항목 | 이번 진단 | A 서비스 | 영향 |
|---|---|---|---|
| 이벤트 종류·플래그·timeout | A 설정과 같게 `setServiceInfo` | XML | 같다고 봄 |
| `canTakeScreenshot`, `isAccessibilityTool` | 없음 | 있음 | 이벤트 순서에 영향 없을 것으로 봄 (미검증) |
| show 전후 처리 | 바로 show | OCR·분류(B) 뒤 show, 이벤트마다 A 처리 시간 | 이벤트 도착 간격이 다를 수 있음 |
| `update()` 호출 시점 | 계약대로 show 직후 + `WINDOWS_CHANGED` | **모름** (A 코드 미공개) | A가 show 직후 `update`를 안 한다면 결과는 같거나 더 나쁨 |
| 무효화 판단 | 없음 (판정만 기록) | `BOUND_EVENT_WINDOW_MISMATCH` | A의 규칙은 로그 이름으로만 추정 |
| 기기 | 에뮬레이터 API 37 | 우진 에뮬레이터 API 37 | 실기기(갤럭시 S26, LG)는 미검증 |

## 5. 고칠 방법 제안 (안전장치를 약하게 하지 않는 것)

### 제안 1 (권장) — id를 모르거나 다르면 그 이벤트 때 `getWindows()`를 다시 읽고 판단
- C 창이 떠 있고(`windowState != HIDDEN`) 트래커가 id를 모르거나 `event.windowId`가 기억한 id와 다를 때만, 그 자리에서 `getWindows()` → `update()` → `isOwnOverlayWindowEvent()`.
- 근거: 이번 7회에서 C 창 이벤트 정답률 100%, C 창이 아닌 이벤트 오판 0 (`ownFresh` 열).
- 안전: 판정 규칙(종류·루트 패키지·고정 제목·정확히 1개·세대·id≥0)은 그대로다. 다른 앱 창은 루트 패키지가 우리일 수 없어서 C 창으로 잘못 볼 수 없다. 판정이 true여도 지금 계약대로 **새 팝업 판정·baseline 초기화에서만** 빼고, 대상 앱 창 변화·사용자 조작·민감 화면 검사는 그대로 둔다.
- 위험: 다른 앱 이벤트를 놓칠 가능성은 없다(다른 창이면 다시 읽어도 false). 비용은 C 창이 떠 있는 동안 C 창이 아닌 이벤트마다 `getWindows()` 1회 — 매번 하기 싫으면 "id를 모를 때만" + "`wid`가 목록에 처음 보는 id일 때만"으로 줄일 수 있다.
- 누가: 판정을 쓰는 곳은 A의 이벤트 처리라 **A가 호출**한다. C는 `OwnOverlayWindowTracker`에 "모르면 다시 읽기" 도우미(예: `isOwnOverlayWindowEvent(eventWindowId, overlay) { readWindowFacts() }`)와 JVM 시험을 **별도 PR**로 낼 수 있다.

### 제안 2 — show 직후 C 창 id가 확정될 때까지 "확인 중" 상태
- `present()`가 `SHOWN`을 돌려준 뒤 C 창 id가 정해질 때까지 A는 C 패키지 이벤트를 무효화 근거로 바로 쓰지 않고, 제안 1처럼 다시 읽어 확정한 뒤 판단한다. C는 `windowState`에 "id 미확정"을 알려 줄 수 있다.
- 위험: 실질적으로 제안 1과 같다. 시간 기반 유예(예: "show 후 500ms는 무시")로 구현하면 **그 사이 대상 앱 변화를 놓치므로 쓰면 안 된다.**
- 누가: C(상태 추가) + A(사용). C가 자기 창 id를 직접 얻는 공개 API는 없다(`View`의 접근성 창 id는 숨김 API) → 결국 A의 `getWindows()`가 필요하다.

### 제안 3 — 이벤트 소스 노드로 확인
- `event.source?.window`로 그 창의 종류·제목·루트 패키지를 확인.
- 위험: `TYPE_WINDOWS_CHANGED`는 소스가 없는 경우가 많고, 이벤트마다 노드 IPC가 생긴다. 노드를 오래 들고 있으면 안 된다(AGENTS 5장). 제안 1보다 이점이 없다.
- 누가: A.

### 하지 말 것
- 패키지명만으로 C 창 판단 (A 서비스·C 창·시험 서비스가 같은 패키지 — 리뷰 #18-3에서 이미 버림)
- show 뒤 일정 시간 모든 이벤트 무시, 무효화 자체를 끄기, 무효화 후 재질문 허용

## 6. 다음 확인 (A와 함께)
1. A 통합 코드에서 `update()`를 언제 부르는지, `BOUND_EVENT_WINDOW_MISMATCH`가 정확히 어떤 비교인지 우진 확인.
2. 제안 1을 A 로컬에 넣고 같은 P01 통합을 3회 이상 → `SHOWN` 뒤 `INVALIDATED` 없음 + 대상 앱(P01)을 직접 바꾸면 여전히 무효화되는지(안전 회귀) 확인.
3. 두 번째 팝업 때 이전 C 창 "제거" 이벤트가 새 토큰을 무효화하는지 확인.
4. 실기기(LG, 갤럭시 S26)에서 같은 진단 1회 — 이벤트 순서는 기기마다 다를 수 있다.

## 7. 재현 명령 (debug 빌드)
```
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t popup-test-app/build/outputs/apk/debug/popup-test-app-debug.apk
adb shell settings put secure enabled_accessibility_services kr.closeforyou/kr.closeforyou.ui.voice.debug.ChoiceOverlayTestService
adb shell am start -n kr.closeforyou/.ui.voice.debug.ChoicePreviewActivity --ei ui_debug_overlay_scenario 0 --ez ui_debug_c29_diag true
adb shell am start -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01
adb logcat -s C29Diag
```
끝나면 접근성 설정을 원래 값으로 되돌린다. A 서비스(`CloseForYouAccessibilityService`)는 이 진단에 켜지 않는다.
