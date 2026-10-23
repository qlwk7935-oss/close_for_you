# 이슈 #29 진단 — C 선택 창이 뜬 직후 무효화되는 원인 (C 단독, 2026-10-23, 2차 갱신)

> 대상: qlwk7935-oss/close_for_you#29 (A→B→C 비실행 통합에서 C 선택 창이 P01 위에 다 그려진 뒤 약 0.2초 만에 사라짐)
> 근거: ① C debug 시험 서비스로 에뮬레이터 `Pixel_8`(API 37) 7회 실측, ② A V5 브랜치 `transfer/a-overlay-binding-detail-v5-20261023`(947ee73) 코드 읽기(수정 없음).
> A 서비스로 P01을 실제 재현하는 것은 **하지 못했다**(아래 5절).
> 기록은 숫자·enum·참/거짓만이다. 창 id·패키지 이름 원값, 화면 글자, 창 제목 글자는 문서와 로그에 남기지 않는다. 창은 "C 창 / 이전 C 창 / 대상 앱 창 / 시스템 창"처럼 상대 표기만 쓴다.

## 1. 결론

**C 창 결합은 성공한다. 그 뒤 C 창이 아닌 다른 창에서 온 이벤트 1개를 A가 화면 변화로 보고 토큰을 무효화하는 것이 직접 원인이다.** 그 다른 창이 무엇인지(대상 앱 P01 창인지, 시스템 창인지)는 아직 확정하지 못했다.

1차 결론("① 순서 — C 창 id를 몰라서 C 창 이벤트를 놓친다")은 **C 계약 문서의 방식**(show 직후와 `TYPE_WINDOWS_CHANGED` 때만 `update()`)을 따를 때의 이야기였다. **A V5는 그 방식을 쓰지 않는다.** 그래서 1차 결론은 A 통합의 원인이 아니다. 아래에 정정한다.

### A V5가 실제로 하는 일 (코드로 확인)
`accessibility/integration/ServiceChoiceIntegration.kt`, `OwnedOverlayEventPolicy.kt`, `CloseForYouAccessibilityService.kt`
1. `present()` 직전: `readOwnedIds()`로 "C 창처럼 보이는 창" 집합(before)을 읽는다.
   - 조건: 종류 `ACCESSIBILITY_OVERLAY`, 기본 디스플레이, 루트 노드의 패키지가 우리, 루트의 창 id가 그 창과 같음, 제목이 고정 제목.
2. `SHOWN`이면 `binding.expect(before, 토큰, 세대)`를 한다. **C의 `OwnOverlayWindowTracker.update()`는 쓰지 않는다.**
3. 이벤트마다(C 창이 `SHOWING`일 때) 이벤트를 종류별로 나눈다.
   - 내용 변화 / 창 상태 변화 / 포커스 / `WINDOWS_CHANGED` 중 추가·크기·층·제목 변화만: 그 순간 `readOwnedIds()`를 다시 읽어 결합을 검사한다.
   - 그 밖(클릭·터치·스크롤, 창 제거·활성 변화가 섞인 `WINDOWS_CHANGED` 등): 바로 "무시 안 함"이 된다.
4. 결합 검사 (`OwnedOverlayBinding.matches`)
   - 아직 결합 전: (지금 집합 − before)가 정확히 1개이고 이벤트 창이 그 창이면 **결합**하고 무시한다.
   - 결합 후: 이벤트 창이 결합된 창과 다르면 `BOUND_EVENT_WINDOW_MISMATCH`로 거부한다. 같아도 그 창이 지금 목록에 없으면 거부한다(`BOUND_WINDOW_NOT_LIVE`).
5. 무시되지 않은 이벤트는 서비스에서 **그대로 `invalidate(EVENT)`** 된다. 그러면 C 창이 `dismiss`되고, 재질문은 금지다.
6. A의 디버그 로그(`A_OverlayEvent`)는 표시 1회당 **처음 거부된 이벤트 1개만** 남긴다. 남기는 것은 `EVENT`(종류), `REASON`, `BINDING_DETAIL`뿐이고, 어느 창이었는지는 남기지 않는다.

### 우리 7회 실측과 우진 로그가 맞는 이유
- A는 이벤트 때 창 목록을 다시 읽는다. 이것은 1차 문서의 "제안 1"과 같은 방식이다.
- 우리 7회에서 **C 창 이벤트를 처리하는 시점에는 7/7 모두 C 창이 이미 목록에 있었다.** 그래서 이벤트 때 다시 읽는 판정(`ownFresh`)은 C 창 이벤트를 전부 맞혔다.
  - 그렇다면 A도 C 창의 첫 이벤트에서 결합에 성공한다.
  - 우진 로그의 `BOUND_EVENT_WINDOW_MISMATCH`("이미 결합된 C 창과 이벤트 창이 다름")도 결합이 적어도 한 번 성공했다는 뜻이다.
- 같은 7회에서 C 창에서 온 이벤트(패키지=우리)는 **7/7 모두 이벤트 창 = 목록의 C 창**이었다. C 창이 자기 이벤트를 다른 창 id로 보내는 일은 없었다(1차의 ② 아님 유지).
- 따라서 `BOUND_EVENT_WINDOW_MISMATCH`를 일으킨 이벤트는 **C 창이 아닌 창**에서 온 내용·상태·포커스 이벤트, 또는 그 창의 추가·크기·층·제목 변화이다.
  - 그 이벤트는 A 설계대로 "화면이 바뀜"으로 처리되어 무효화된다.
  - 이것은 A 결합 로직의 오판이 아니라 **무효화 범위**의 문제다.

### 그 "다른 창" 후보
| 후보 | 근거 | 확정 여부 |
|---|---|---|
| 대상 앱(P01) 창이 아직 바뀌는 중 (대화상자 등장 애니메이션 끝, 레이아웃, 포커스) | A는 감지·분류 직후 바로 띄운다. 우리 시험은 P01이 3.5초 이상 안정된 뒤 띄워서 P01 이벤트가 0개였다 → 이 경우를 재현하지 못함 | 미확정 |
| 시스템 창(상태 표시줄 등)의 내용 변화 | 7회 중 1회(3회차)에 show 뒤 약 0.68초·0.79초에 시스템 창 내용 변화 2개 | 가능성 있음 |
| TalkBack 접근성 포커스 이벤트(앱 창) | 이번 유효 7회에서는 관측 0 | 낮음 |
| 다른 창의 층·크기 변화(`WINDOWS_CHANGED`) | 7회 동안 다른 창의 층·크기 변화 0 | 낮음 |

## 2. 진단 방법 (C 단독)
- `app/src/debug/.../ui/voice/debug/C29OverlayDiagnostics.kt` (debug 전용, adb extra로만 켜짐)
  - 표시 모드 `--ez ui_debug_c29_diag true`
  - 관찰 모드 `--el ui_debug_c29_watch_ms <ms>`: C 창을 띄우지 않고 이벤트만 기록. A 서비스 옆에서 쓰려고 만들었으나 이번에는 쓰지 못함.
- 켜지면 시험 서비스가 **A와 같은** 이벤트 종류·플래그(`flagReportViewIds|flagRetrieveInteractiveWindows`, `notificationTimeout=0`, 모든 패키지)로 `setServiceInfo`를 잠시 바꾸고, 끝나면 되돌린다.
- 한 줄에 남기는 것(logcat 태그 `C29Diag`)
  - 경과 ms, 이벤트 종류, 이벤트 창(**회차 안 임시 순번** `w1, w2…`)
  - 패키지가 우리인지(bool), `contentChangeTypes`·`windowChanges` 숫자
  - 그 순간 창 목록(순번·종류·층·우리 루트 여부·제목 일치/다름/없음)
  - 계약 방식 판정(`ownA`), 이벤트 때 다시 읽는 판정(`ownFresh`)
- **창 id 원값은 기록하지 않는다**(2차 갱신부터). 1차 7회 로그에는 숫자 id가 있었다. 아래 표는 상대 표기로 바꿨고, 원 로그 파일은 저장소에 넣지 않았다.

## 3. 회차별 결과 (show 직후 1초, 1차 실측 7회)
P01을 연 지 3.5초 뒤 C 창 표시. 1~4회 TalkBack 켬, 5~7회 끔.

| 회 | show 직후 목록에 C 창 | show 직후 목록에 이전 C 창(루트 없음) | C 창 첫 이벤트 처리 (ms) | 그때 목록에 C 창 | C 창 이벤트의 창 = 목록의 C 창 | 계약 방식(`ownA`)으로 놓친 C 창 이벤트 | 다시 읽기(`ownFresh`) 오판 | C 창이 아닌 창의 이벤트 (1초 안) | 3초 뒤 창 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 없음 | 없음(첫 회) | 395 | 있음 | 같음 | 2 | 0 | 0 | 있음 |
| 2 | 없음 | 있음 | 386 | 있음 | 같음 | 2 | 0 | 이전 C 창 제거 1 | 있음 |
| 3 | 없음 | 있음 | 422 | 있음 | 같음 | 2 | 0 | 이전 C 창 제거 1, 시스템 창 내용 2 | 있음 |
| 4 | 없음 | 있음 | 480 | 있음 | 같음 | 2 | 0 | 이전 C 창 제거 1 | 있음 |
| 5 | 없음 | 있음 | 412 | 있음 | 같음 | 2 | 0 | 이전 C 창 제거 1 | 있음 |
| 6 | 없음 | 있음 | 418 | 있음 | 같음 | 2 | 0 | 이전 C 창 제거 1 | 있음 |
| 7 | 없음 | 있음 | 502 | 있음 | 같음 | 0 | 0 | 이전 C 창 제거 1 | 있음 |

- 무효화하지 않는 시험 서비스에서는 7/7 모두 3초 뒤에도 C 창이 `SHOWING`이었다. `dumpsys window`에서도 `ACCESSIBILITY_OVERLAY`, `isVisible=true`였다.
  - 그러므로 우진 영상에서 창이 사라진 것은 A의 무효화 → `dismiss` 경로다.
- "계약 방식으로 놓친 이벤트"는 C 계약 문서대로 쓴 경우의 결과다. A V5는 이 방식을 쓰지 않으므로 #29 원인이 아니다. 다만 C 계약 문서는 고쳐야 한다(4절 제안 3).

### 이전 C 창의 "제거" 이벤트 (A V5 기준으로 다시 봄)
- 2~7회에는 show 직후 목록에 방금 지운 이전 C 창이 남아 있었다. 그 창의 `WINDOWS_CHANGED(제거)`는 새 C 창 이벤트보다 먼저 처리됐다.
- A V5 `readOwnedIds()`는 루트가 없는 창을 빼므로 결합에는 영향이 없다.
- 그러나 A V5는 "제거"가 섞인 `WINDOWS_CHANGED`를 무시하지 않는다(`MASK_UNSUPPORTED`). 그래서 **이전 C 창을 지운 직후 새 C 창을 띄우는 경우**에는 이 이벤트로 새 토큰이 무효화된다.
- #29(첫 표시, `BOUND_EVENT_WINDOW_MISMATCH`)의 원인은 아니다. 연속 팝업 때 같은 증상이 날 수 있으니 기록해 둔다.

## 4. 제안 (안전장치를 약하게 하지 않는 것)

### 제안 1 (먼저, A) — 거부된 이벤트가 "어느 창"인지 enum으로 남기기
- `OverlayEventDiagnostic`에 창 id 원값 대신 **관계 enum**을 하나 추가한다.
  - 예: `TARGET_WINDOW`(토큰의 대상 앱 창), `SYSTEM_WINDOW`, `OTHER_APP_WINDOW`, `INPUT_METHOD`, `OTHER_OVERLAY`, `NOT_IN_LIST`
- 지금 로그의 `EVENT=` 값(내용/상태/포커스/창 변화)과 합치면 원인이 바로 확정된다.
- 위험: 없음. 기록만 늘고 판단은 그대로다. 누가: A.
- 우진에게 먼저 부탁: 이미 남은 로그의 `EVENT=` 값.

### 제안 2 (원인이 대상 앱 창이면, A·B) — 대상 앱 창이 조용해진 뒤에 띄우기
- 대상 창의 변화가 일정 시간 없을 때만 `present()`한다. 또는 띄우기 직전에 대상 창 상태를 다시 확인한다.
- 대상 앱이 바뀌면 무효화하는 규칙(AGENTS 6장 7번)은 **그대로**다. 띄우는 시점만 늦춘다.
- 위험: 안내가 조금 늦어진다. 계속 움직이는 팝업(영상 등)에서는 영영 안 뜰 수 있으므로 상한과 안내가 필요하다. 누가: A(감지·현재성), B(분류 시점).

### 제안 3 (원인이 시스템 창이면, A·B 결정 사항) — 무효화 범위
- 상태 표시줄 같은 시스템 창의 변화까지 대상 앱 토큰을 무효화할지는 **A·B 안전 설계 결정**이다.
- C는 범위를 줄이자고 요구하지 않는다.
- 줄인다면 조건을 붙여야 한다. "대상 앱 창과 겹치지 않는 시스템 창의 내용 변화만" 같은 식으로 좁게 정하고, 사용자 조작·대상 앱 변화·민감 화면 검사는 그대로 둔다.
- 위험: 대상 앱 위에 뜬 시스템 대화상자(권한 요청 등)를 놓칠 수 있다. 그래서 겹침 검사가 꼭 필요하다.

### 제안 4 (C) — C 계약 문서·트래커 정리
- `OwnOverlayWindowTracker`의 "show 직후 + `WINDOWS_CHANGED` 때 `update`" 방식은 실측상 첫 C 창 이벤트를 놓친다(7회 중 6회).
- A V5처럼 "판정할 이벤트 때 다시 읽기"로 계약 문서를 고치거나, A가 쓰지 않는 트래커를 `@Deprecated` 처리한다.
- C main 코드 변경이므로 **별도 PR**로 한다.

### 하지 말 것
- 패키지명만으로 C 창 판단, show 뒤 일정 시간 모든 이벤트 무시, 무효화 끄기, 무효화 후 재질문 허용

## 5. 하지 못한 것·한계
- **A V5 빌드 + A 서비스로 P01 실제 재현은 하지 못했다.**
  - A V5 빌드(스크래치 worktree, C debug 진단 파일만 복사)는 성공했다.
  - 에뮬레이터에서 A 서비스를 켜고 A 동의 설정을 debug 저장소에 쓴 뒤 P01을 여는 실행은 Claude Code 자동 권한 검사가 막았다(사유 "Security Weaken"). 우회하지 않았다.
  - 그 직후 A 서비스를 끄고 A 설정 파일을 지워 원래대로 되돌렸다.
- C 단독으로 "P01이 뜬 지 약 1초 뒤 표시"하는 추가 관측을 6회 시도했으나 모두 **무효**다. 이유는 에뮬레이터 부팅 직후 부하(load 30, 시스템 UI 응답 없음 대화상자), C 창 추가 0.4~3.7초, C 창이 P01이 아니라 미리보기 화면 위에 뜬 것이다. 표에 넣지 않았다.
- 시험 서비스와 A 서비스의 남은 차이: `canTakeScreenshot`·`isAccessibilityTool`, A의 OCR·분류 처리 시간(이벤트 처리 간격이 달라짐). 실기기(LG, 갤럭시 S26)는 미검증이다.

## 6. 다음 확인 (A와 함께)
1. 우진: 이미 있는 로그의 `A_OverlayEvent ... EVENT=` 값을 알려 주기 → 내용/상태/포커스/창 변화 중 무엇인지.
2. A: 제안 1 enum을 추가하고 P01 통합 3회 → 거부된 창이 대상 앱 창인지 시스템 창인지 확정.
3. 확정 결과에 따라 제안 2 또는 3을 A·B가 결정. 대상 앱을 직접 바꾸면 여전히 무효화되는지 안전 회귀 확인.
4. 연속 팝업에서 이전 C 창 "제거" 이벤트로 무효화되는지 확인(3절).
5. C: 제안 4 별도 PR.

## 7. 재현 명령 (debug 빌드, C 단독)
```
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t popup-test-app/build/outputs/apk/debug/popup-test-app-debug.apk
adb shell settings put secure enabled_accessibility_services kr.closeforyou/kr.closeforyou.ui.voice.debug.ChoiceOverlayTestService
adb shell am start -n kr.closeforyou/.ui.voice.debug.ChoicePreviewActivity --ei ui_debug_overlay_scenario 0 --ez ui_debug_c29_diag true
adb shell am start -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01
adb logcat -s C29Diag
```
- 부팅 직후에는 부하가 내려갈 때까지(`/proc/loadavg` 첫 값 6 미만) 기다리고, 화면 잠금을 푼다.
- 끝나면 접근성 설정을 원래 값으로 되돌린다.
