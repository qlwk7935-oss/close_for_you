# TTS·음성 상태 계약 초안 — C 제안 (2026-10-19)

> **상태: C 제안 초안.** 우진(10/19) 방향 "TTS 상태 계약은 C가 맡는다"는 **사용자(재호) 확인 전**이라 C 제안으로 씀.
> 실제 `core/**`는 고치지 않았다. 합의되면 **별도 계약 PR**로 진행한다 (CLAUDE.md 2장).
> 기준: `main` 845214a (PR #12까지) + `feature/ui-voice` 10/19 작업본.

## 0. 한눈에 보기

| # | 바꿀 것 | 파일 | 왜 | 영향 |
|---|---|---|---|---|
| 1 | `AnnouncementState.FAILED` 추가 | `core/contract/PopupContracts.kt` | 지금은 재생 실패도 `NOT_IMPLEMENTED`라서 "아직 없음"과 "시도했지만 못 함"을 구분할 수 없음 | C 구현 1곳. A·B·D는 이 값을 `when`으로 다루는 곳이 없음(확인함) |
| 2 | `VoiceEvent.NotUnderstood` 추가 | 같은 파일 | 지금은 "못 알아들음"도 `Cancelled`. 사용자가 취소한 것과 다르게 안내해야 함 ("다시 말해 주세요" vs 조용히) | C 구현 1곳, `MockVoiceCommandInput`은 변경 불필요 |
| 3 | `announcementAvailable` 뜻을 KDoc으로 고정 + `talkBackActive` 별도 값 | `DecisionContext` | B는 `announcementAvailable=false`면 기억 규칙 자동 실행을 확인으로 바꿈(`FEEDBACK_UNAVAILABLE`). 기준이 문서에 없음 | B `DecisionActionPolicy` 동작 그대로. 새 값은 기본값이 있어 기존 호출 컴파일 유지 |
| 부록 A | `MOVE_TO_BUTTON` (C 쪽 요구사항) | — | 합의: 안내·이동만, 클릭 허가 아님 | B `when` 한 줄, A 포커스 요청 |
| 부록 B | 앱별 자동 닫기 (C 쪽 요구사항) | `DecisionContext` | 합의: 기본 false | B 정책, C 설정 화면 |

## 1. `AnnouncementState.FAILED`

### 바꿀 코드 (`app/src/main/java/kr/closeforyou/core/contract/PopupContracts.kt` 56~57줄)

```diff
-/** C: these states do not mean the person heard or understood an announcement. */
-enum class AnnouncementState { NOT_IMPLEMENTED, UI_UPDATED, FOCUS_OBSERVED, TTS_COMPLETED, USER_ACKNOWLEDGED }
+/**
+ * C: these states do not mean the person heard or understood an announcement.
+ * FAILED: speaking was attempted (or the engine was not ready) and playback did not complete.
+ * NOT_IMPLEMENTED stays for implementations that have no output at all (mocks).
+ */
+enum class AnnouncementState {
+    NOT_IMPLEMENTED, UI_UPDATED, FOCUS_OBSERVED, TTS_COMPLETED, USER_ACKNOWLEDGED, FAILED,
+}
```

### C 쪽 변경 (계약 PR 뒤, `feature/ui-voice`)
- `ui/voice/TtsPopupAnnouncer.kt` `notDelivered()` → `AnnouncementState.FAILED`.
- `test/.../TtsPopupAnnouncerTest.kt` 53·60·73·90줄 기대값 `NOT_IMPLEMENTED` → `FAILED` (엔진 준비 안 됨, 빈 문장, 재생 실패, 다시 들을 것 없음).

### 영향 확인
| 담당 | 파일 | 영향 |
|---|---|---|
| A | (사용 안 함) | 없음 |
| B | `decision/**` | `AnnouncementState`를 읽지 않음 → 없음 |
| C | `TtsPopupAnnouncer.kt` | 1줄 |
| D | `testing/mock/MockTeamPorts.kt` `MockPopupAnnouncer` | 그대로 `NOT_IMPLEMENTED` (출력 없음이 맞음) |

### 필요한 시험
- `FAILED`는 `TTS_COMPLETED`가 아님을 확인하는 C 시험 (이미 있는 시험 기대값 변경).
- 계약 시험: enum에 `FAILED`가 있고 `USER_ACKNOWLEDGED`와 다름 (선택).

## 2. `VoiceEvent.NotUnderstood`

### 바꿀 코드 (같은 파일 72~76줄 `VoiceEvent`)

```diff
 sealed interface VoiceEvent {
     data class Recognized(val command: VoiceCommand, val token: SnapshotToken) : VoiceEvent
+    /** Speech was heard but matched no command (or was ignored for safety: too long, two
+     * commands, negation). Not a user cancel. Never carries the recognized sentence. */
+    data object NotUnderstood : VoiceEvent
     data object Cancelled : VoiceEvent
     data object Unavailable : VoiceEvent
 }
```

- `Cancelled` 뜻을 "사람이 취소했거나 아무 말도 없음(시간 초과)"으로 좁힌다.
- **인식된 문장은 이벤트에 넣지 않는다** (화면 글자처럼 개인정보 취급, CLAUDE.md 3-3).

### C 쪽 변경
- `ui/voice/command/OnDeviceVoiceCommandInput.kt` `onResults`: 명령 없음 → `NotUnderstood`. `onError`의 `ERROR_NO_MATCH` → `NotUnderstood`, `ERROR_SPEECH_TIMEOUT` → `Cancelled`.
- 미리보기·선택 창: `NotUnderstood`면 "명령을 알아듣지 못했어요. 다시 눌러 말해 주세요." (지금 `Cancelled`에 쓰는 문장), `Cancelled`면 안내 없음.

### 영향 확인
| 담당 | 영향 |
|---|---|
| A·B·D | `VoiceEvent`를 `when`으로 다루는 코드 없음 (main 845214a 확인). `MockVoiceCommandInput`은 이벤트를 내보내지 않음 → 변경 없음 |
| C | 위 2곳. `when (event)`에 가지 하나 추가 (sealed라 컴파일러가 빠진 곳을 알려 줌) |

### 필요한 시험
- `KoreanVoiceCommandMatcher`가 null → `NotUnderstood` (Listener를 분리해 단위 시험하거나 매핑 함수로 뽑아 시험).
- 실기기: 갤럭시 S26에서 무관한 말 → "알아듣지 못했어요" / 아무 말 안 함 → 조용히 끝남.

## 3. `announcementAvailable` 정의 + `talkBackActive`

### 정의 (C 제안, A·B 10/18~19 동의한 방향)
- `announcementAvailable` = **우리 앱 TTS가 지금 말할 수 있다** (`TtsPopupAnnouncer.announcementAvailable`, 엔진 READY).
- TalkBack 켜짐 여부는 **포함하지 않는다.** TalkBack이 켜져 있어도 새 오버레이 창을 스스로 읽지 않는다(LG 실험 4-5절)는 결과가 있어서, "결과를 알릴 수 있다"의 근거는 우리 TTS다.
- TalkBack 상태가 필요하면 **별도 값**으로 둔다.

### 바꿀 코드 (같은 파일 36~42줄 `DecisionContext`)

```diff
 data class DecisionContext(
     val currentToken: SnapshotToken?,
     val userChoice: UserChoice?,
     val matchingRule: PopupRule?,
     val userInteracting: Boolean,
+    /** C: true only while this app's own TTS can speak a result (engine READY). Not TalkBack. */
     val announcementAvailable: Boolean,
+    /** A: a screen reader (TalkBack) is enabled. Informational; does not mean results were heard. */
+    val talkBackActive: Boolean = false,
 )
```

- 기본값 `false`라서 기존 호출(`IntegrationExample`, B 시험)은 그대로 컴파일된다.
- **누가 채우나:** `announcementAvailable`은 C(`TtsPopupAnnouncer`)가 값을 주고 A가 `DecisionContext`를 만들 때 넣는다. `talkBackActive`는 A(`AccessibilityManager.isTouchExplorationEnabled` 등, **실기기 확인 필요**).
- `talkBackActive`를 B 정책에 쓸지는 B가 정한다. C 제안: 지금은 정책에 쓰지 않고 안내 방식 선택(창 제목만 / 자체 TTS)에만 쓴다.

### 영향 확인
| 담당 | 파일 | 영향 |
|---|---|---|
| A | `DecisionContext`를 만드는 곳 (아직 main엔 없음, 게이트 PR #10) | 값 2개 채우기 |
| B | `DecisionActionPolicy.kt` 50·109·129줄 | 동작 그대로. `talkBackActive` 무시해도 됨 |
| C | `TtsPopupAnnouncer.announcementAvailable` | 이미 정의대로 동작 |
| D | `IntegrationExample.kt` 21줄 | 기본값 덕분에 변경 없음 |

### 필요한 시험
- B: `announcementAvailable=false` → 기억 규칙 자동 실행이 확인으로 바뀜 (이미 있음, 회귀 확인만).
- C: 엔진 READY 아닐 때 false (이미 있음).

---

## 부록 A. `MOVE_TO_BUTTON` — C 쪽 요구사항

합의(10/18~19): **사용자가 직접 찾도록 안내·포커스 이동**이지 자동 클릭 허가가 아니다.

C가 필요한 것:
1. 선택 결과로 "이동"을 표현할 값. 두 가지 안:
   - (가) `ChoiceKind.MOVE_TO_BUTTON` 추가 → B `DecisionActionPolicy`의 `when`에 **"항상 DENY(실행 아님)" 한 줄**이 필요 (`DecisionActionPolicy.kt` 88~89줄의 실행 아님 가지에 `ChoiceKind.MOVE_TO_BUTTON` 추가). 실행 관문을 거치지 않는다는 것을 B 시험으로 고정.
   - (나) `ChoiceKind`는 그대로 두고 A에 별도 요청 `ButtonFocusMover.moveFocusTo(MoveToButtonRequest(token, candidateId))`. **C 추천: (나)** — 실행 경로와 섞이지 않아 B 변경이 없다.
2. A가 할 일: 토큰이 아직 현재인지 확인 → 후보 노드에 접근성 포커스(`ACTION_ACCESSIBILITY_FOCUS`)만. 누르지 않음. 실패하면 false.
3. C 준비 상태(10/19): `ui/voice/choice/MoveToButton.kt`에 `MoveToButtonFeature.enabled`(**기본 꺼짐**), `MoveToButtonRequest`, `ButtonFocusMover` 초안. 켜면 선택 창·음성 명령("버튼으로 이동")·실패 후속 안내에 [버튼으로 이동]이 나온다.

## 부록 B. 앱별 자동 닫기 — C 쪽 요구사항

- `DecisionContext`에 `autoCloseEnabledForApp: Boolean = false` (B 제안, C 동의). 기본 false.
- C 설정 화면이 앱별로 켜고 끈다(`UiVoiceSettings.autoCloseAllowed(pkg, A 허용 목록)` — **A가 허용한 앱이어야만** true).
- 켜져 있어도 B 정책·A 게이트·동일 팝업 재검사(AGENTS 6-4)를 모두 통과해야 한다. C는 "켜짐 = 허락"으로 안내하지 않는다.
- 누가 채우나: A가 `DecisionContext`를 만들 때 C 설정을 읽어 넣는다 (C가 읽기 함수 제공).
