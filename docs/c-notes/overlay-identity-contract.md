# C 선택 창 — 창 수명·식별 계약 초안 (A와 합의용, 2026-10-22)

> PR #18 우진 리뷰(Request changes) 1·2·3번 대응. **C 코드만 바꿨고 A·B·D 코드와 `core/**`는 고치지 않았다.**
> 아래는 A와 합의할 **초안**이다. 실기기(TalkBack) 검증은 하지 않았다 — "실기기 확인 필요" 표시 참고.
> 관련 코드: `ui/voice/overlay/OverlayWindowKeeper.kt`, `ChoicePromptPresenter.kt`, `AccessibilityChoiceOverlay.kt`, `integration/PopupChoiceFlow.kt`

## 1. C가 보장하는 것

### 1-1. 창 상태 (`ChoicePromptPresenter.windowState`)
| 상태 | 뜻 | 탭·음성 결과 |
|---|---|---|
| `HIDDEN` | C 창 없음 (`removeView` 성공, 또는 WindowManager가 그 뷰를 모름) | 없음 |
| `SHOWING` | 선택 창 1개가 떠 있음 | **현재 세대만** 받음 |
| `REMOVE_FAILED` | 창 제거 실패. 화면에 남아 있을 수 있음 | **전부 버림** (실패 순간 즉시 무효화) |

- "닫힘(`HIDDEN`)"은 **제거가 성공했을 때만**이다. 예전처럼 제거 전에 상태를 지우지 않는다.
- `REMOVE_FAILED` 창은 내용 비우기(`disposeComposition`) + `View.GONE` 처리한다. 창이 실제로 안 보이게 되는지는 **실기기 확인 필요**.
- 다음 `dismiss()`·`present()` 때 `removeViewImmediate`로 다시 지운다. 그래도 실패하면 **새 선택 창을 띄우지 않는다**(`PromptShowResult.BLOCKED`).
- 콜백 정리(듣기 중지·코루틴 scope 취소)와 lifecycle owner `destroy()`는 성공·실패와 관계없이 **창마다 정확히 1번**.

### 1-2. 띄우기 결과 (`present(prompt, onAnswer): PromptShowResult`)
| 결과 | 뜻 | `onAnswer` |
|---|---|---|
| `SHOWN` | 창이 떠 있음 | 이 프롬프트의 탭·음성마다 |
| `FAILED` | ComposeView 생성·`addView`·`onShown` 중 예외. 만든 자원(scope·컨트롤러·owner·뷰)은 모두 정리, 이미 추가됐으면 제거 시도 | 영원히 안 옴 |
| `BLOCKED` | 이전 창을 못 지워서 새 창을 안 띄움 | 영원히 안 옴 |

- `addView` 실패 시: AOSP `WindowManagerGlobal.addView`는 실패한 창을 스스로 지운다(android-36 소스 확인). C는 그래도 `removeViewImmediate`를 한 번 더 부르고, "모르는 뷰"(`IllegalArgumentException`)면 이미 없는 것으로 본다.
- 예전 `show(prompt) { choice -> }`도 그대로 쓸 수 있고, 이제 같은 `PromptShowResult`를 돌려준다.

### 1-3. 세대 번호와 늦은 콜백
- `present`마다 세대 번호(`generation`)가 1씩 오른다. 모든 답(`PromptAnswer`)에 세대가 붙는다.
- 아래 경우의 탭·음성 결과는 **C 안에서 버려지고 B/A로 가지 않는다** (JVM 회귀 시험 있음):
  - `dismiss()` 뒤 / 새 `present()` 뒤 (이전 세대) / 창 제거 실패 뒤 / `Invalidated`·`Blocked` 뒤 / flow 취소(서비스 종료) 뒤 / 서비스 재연결 후 새 overlay 인스턴스가 생긴 뒤 옛 인스턴스의 콜백 / 같은 버튼 두 번 탭
- 누르기·그대로 두기·버튼으로 이동은 **창을 먼저 닫고** 답을 보낸다. 닫기가 실패하면 답은 `PromptAnswer.Lost`로 바뀌고 **선택을 실행하지 않는다**(오래된 창이 화면에 남은 채로 B/A 요청을 하지 않기 위해).
- 음성 컨트롤러(`PromptControlsController`)도 `dispose()` 뒤에는 [말하기]·[다시 듣기]·인식 결과를 모두 무시한다.

### 1-4. `PopupChoiceFlow`가 하는 일
- `present`가 `SHOWN`이 아니면: 그 팝업은 **선택 없음으로 끝**. `ChoiceHandler`(B/A)를 부르지 않는다. 짧게 1번 안내: `ui_prompt_show_failed` "팝업이 나타났지만 선택 창을 띄우지 못했어요. 팝업은 직접 확인해 주세요."
  - 안내하는 이유: TalkBack 사용자는 앱이 팝업을 찾았는데 아무 말이 없으면 앱이 꺼진 줄 안다. 팝업 자체는 그대로라 사용자가 직접 처리할 수 있다는 것만 알린다. 같은 실패가 팝업마다 반복되면 팝업마다 1번씩 말한다(묶음 처리는 하지 않음 — 열린 질문 5).
- `Lost`이면: B/A를 부르지 않고 `ui_prompt_window_stuck` "선택 창을 닫지 못해서 고른 것을 실행하지 않았어요. 팝업은 그대로 있어요."
- 선택 출처: `ChoiceHandler.handle(choice, analysis, origin: ChoiceOrigin)`(TAP/VOICE). `core/contract`는 바꾸지 않았다. 기본 구현은 출처를 무시하고 기존 2인자 `handle`을 부르므로 **A 코드는 그대로 컴파일된다.** 출처를 쓰려면 3인자 메서드를 override 한다(로그에는 종류만, 들은 말은 절대 안 남김).

## 2. A가 C 창을 알아보는 방법 (리뷰 3번)

**패키지명만으로는 판단하지 않는다.** A 서비스, C 선택 창, debug 시험 서비스가 모두 같은 패키지(`kr.closeforyou`)다. 예전 `isOwnOverlayEvent(packageName)`·`isOwnOverlayWindow(type, pkg, own)`는 `@Deprecated`로 남겨 두었다(A 로컬 코드가 쓰고 있을 수 있어 바로 지우지 않음).

### 2-1. C 창의 조건 (`ChoiceOverlayIdentity.isOwnOverlayWindow(WindowFacts, ...)`)
모두 만족해야 C 창이다.
1. C가 창이 있다고 말함: `windowState.status != HIDDEN` (`REMOVE_FAILED`도 C 창으로 본다 — 남은 창의 이벤트를 새 팝업으로 착각하지 않게)
2. `AccessibilityWindowInfo.getType() == TYPE_ACCESSIBILITY_OVERLAY`
3. `getRoot()?.packageName == 우리 패키지`
4. `getTitle() == getString(R.string.ui_choice_pane_title)` ("팝업 안내", 팝업 글자는 절대 안 넣음)
5. `getId() >= 0`

- `getTitle()`: SDK 문서상 "Window의 title, 없으면 LayoutParams의 title". C는 `LayoutParams.title`에 고정 문장을 넣는다. **오버레이 창에서 실제로 이 값이 나오는지 실기기 확인 필요.** (`LayoutParams.accessibilityTitle`은 `@hide`라 쓰지 않음)
- `getWindows()`는 A 서비스 설정에 `flagRetrieveInteractiveWindows`가 있어야 한다(A 확인).

### 2-2. 이벤트 단위 판정 (`OwnOverlayWindowTracker`)
```kotlin
val tracker = OwnOverlayWindowTracker(packageName, getString(R.string.ui_choice_pane_title))

// 창이 바뀔 때(TYPE_WINDOWS_CHANGED)와 present() 직후
tracker.update(windows.map { WindowFacts(it.id, it.type, it.title, it.root?.packageName) }, overlay.windowState)

// 이벤트마다
val fromC = tracker.isOwnOverlayWindowEvent(event.windowId, overlay.windowState)
```
- 일치하는 창이 **정확히 1개**일 때만 그 창 id를 기억한다. 0개·2개 이상이면 아무것도 C 창으로 보지 않는다.
- `event.windowId == -1`, C 창 없음, 기억할 때와 **세대가 다르면** false. 새 선택 창이 뜨면 다시 `update` 해야 한다.
- false는 "C 창 이벤트라고 확신할 수 없다"는 뜻이다 → A는 평소처럼 처리한다(보수적).
- `getRoot()`가 돌려준 `AccessibilityNodeInfo`는 A가 쓰고 바로 놓는다(장기 보관 금지, AGENTS 5장).

### 2-3. A가 C 창 판정을 쓰는 범위 — **토큰 무효화를 통째로 빼지 말 것**
| 이벤트 | C 창 판정이 true일 때 A가 할 수 있는 것 | 그대로 유지할 것 |
|---|---|---|
| C 창 안의 클릭·포커스·liveRegion·창 상태 변화 | **새 팝업 판정**과 **baseline 초기화**에서만 제외 | — |
| 대상 앱 창의 화면 변화(다른 windowId) | — | 현재성 검사·토큰 무효화 **그대로** |
| 사용자가 대상 앱을 직접 조작(다른 windowId) | — | 토큰 무효화 **그대로** |
| 민감 화면 감지(`Blocked`) | — | **그대로** (C 창이 떠 있어도) |
| A 실행 관문의 클릭 직전 재확인 | — | **그대로** |

C가 C 창 이벤트를 제외해 달라고 하는 이유는, C 창의 버튼 탭·안내 갱신 때문에 A가 대상 팝업을 "새 팝업"으로 다시 잡거나 baseline을 지워 선택 창이 계속 다시 뜨는 것을 막으려는 것뿐이다. 선택한 뒤 실제 실행의 안전성은 B 정책과 A 관문이 그대로 판단한다.

## 3. A가 확인할 것
1. A 서비스 `onDestroy`에서 `flowJob.cancel()` 뒤 `overlay.windowState`가 `REMOVE_FAILED`면 한 번 더 `overlay.dismiss()`를 부를지 (C는 서비스 연결이 끊기면 시스템이 오버레이 창을 지우는 것으로 알고 있으나 **실기기 확인 필요**).
2. `present`가 `FAILED`/`BLOCKED`일 때 A 쪽 지표(SafeDiagnostics 등)에 종류만 남길지. 화면 글자·프롬프트 문장은 남기지 않는다.
3. `OwnOverlayWindowTracker`를 A 이벤트 필터에 넣을지, A가 같은 조건을 직접 구현할지.
4. `flagRetrieveInteractiveWindows` 사용 여부, `TYPE_WINDOWS_CHANGED` 수신 여부.

## 4. 열린 질문 [팀 확정 필요]
1. `ChoicePromptPresenter`·`PromptShowResult`·`OverlayWindowState`를 `core/contract`로 옮길지 (옮기면 합의 후 별도 PR).
2. `ChoiceOrigin`(TAP/VOICE)을 `UserChoice`에 넣을지 — 지금은 C 쪽 인자. 넣으면 계약 변경.
3. `REMOVE_FAILED`가 오래 계속될 때(재시도 계속 실패) 서비스를 다시 시작할지, 사용자에게 설정 화면에서 알릴지.
4. C 창 판정에 창 제목 대신 더 확실한 표지가 필요한지 (예: `getWindows()`의 창 layer/bounds). 실기기 결과를 보고 정한다.
5. 띄우기 실패 안내가 연속될 때 묶을지 (현재는 팝업마다 1번).

## 5. 시험 (JVM, 2026-10-22, 전체 386개 통과)
- `OverlayWindowKeeperTest`(18): 정상 닫기 1회 정리 / 두 번 닫기 / 연속 표시 / 표시 직후 닫기 / 제거 실패 → 즉시 무효화·blank·destroy 1회 / 재시도(`removeViewImmediate`) / 막힌 창이 새 창을 막음 / 재시도 중 NOT_ATTACHED / 닫기 실패 선택 → `Lost` / 정리 중 예외 / 생성 실패 / `addView` 실패(남은 창 없음·있음) / `onShown` 실패 / 다시 읽기 유지 / 옛 세대 / 재연결 후 옛 인스턴스 / 세대 0
- `PopupChoiceFlowTest`(+7, 실제 keeper 사용): 띄우기 실패 → B/A 미호출·안내 / 닫기 실패 → 미호출·다음 팝업 차단·복구 / 무효화 뒤 늦은 탭 / 새 후보 뒤 옛 프롬프트 탭 / flow 취소(+제거 실패) 뒤 늦은 탭·음성 / 두 번 탭 / 음성 출처 전달
- `ChoiceOverlayIdentityTest`(5): 조건 하나라도 빠지면 불일치 / 막힌 창도 C 창 / tracker 세대·id / 애매하면 불신 / 같은 패키지만으로는 불일치
- `PromptControlsControllerTest`(+1): dispose 뒤 [말하기]·[다시 듣기]·인식 결과 무시

**미검증:** 실제 `WindowManager` 예외 상황, `View.GONE`으로 창이 숨는지, `getTitle()` 값, TalkBack 낭독. 실기기(갤럭시 S26 예정)에서 확인한다.
