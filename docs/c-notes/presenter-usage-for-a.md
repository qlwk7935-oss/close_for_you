# A용 사용 예제 — C 선택 창·연결 부품 (초안, 2026-10-19 / 10-21 갱신)

> A(우진)가 서비스에서 C 선택 창을 띄울 때 참고하는 예제다. **A 코드는 C가 고치지 않았다.** 아래 코드는 예시이며 컴파일 확인은 C 단위 시험(`PopupChoiceFlowTest`)의 가짜 부품으로만 했다.
> `ChoicePromptPresenter`·`ButtonFocusMover`를 계약(`core/contract`)에 넣을지는 A와 합의할 것.

## 1. 가장 간단한 방법 — `PopupChoiceFlow`

A는 이벤트 흐름(`SnapshotSource`), 분석(`EvidenceExtractor`), B 분류기, 그리고 **"선택 → B 정책 → A 게이트" 실행 경로(`ChoiceHandler`)** 만 넘긴다. 선택 창·안내·실패 안내·묶음 안내는 C가 한다.

```kotlin
class CloseForYouAccessibilityService : AccessibilityService() {
    private val scope = MainScope()                 // 선택 창은 메인 스레드에서만
    private var announcer: TtsPopupAnnouncer? = null
    private var flowJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val tts = TtsPopupAnnouncer.create(this).also { announcer = it }
        val settings = UiVoicePreferences.get(this)
        val overlay = AccessibilityChoiceOverlay(
            this,
            PromptAnnouncementListener(
                this, tts, scope,
                mode = { settings.current().promptAnnounceMode },
                detail = { settings.current().detail },
            ),
            // [말하기]·[다시 듣기]·[그만 말하기]. 빼면(기본 NONE) 선택지만 보인다.
            PromptControlsSupport.create(this, tts),
        )
        val batcher = AnnouncementBatcher(scope, onFlush = { summary ->
            val detailed = settings.current().detail == AnnouncementDetail.DETAILED
            val text = BatchSentence.build(summary, detailed) { res, n -> getString(res, n) }
            tts.announce(AnnouncementRequest(null, AnnouncementKind.ACTION_RESULT, text))
        })
        val flow = PopupChoiceFlow(
            source = snapshotSource,                // A
            extractor = evidenceExtractor,          // A
            classifier = popupClassifier,           // B
            presenter = overlay,
            announcer = tts,
            batcher = batcher,
            handler = ChoiceHandler { choice, analysis ->
                // A: B 정책 → (ALLOW_ONCE일 때만) A 게이트 1회 실행. KEEP_OPEN이면 null.
                runPolicyAndGate(choice, analysis)
            },
            text = UiText { getString(it) },
            contentText = { PromptSpeech.content(this, it) },
        )
        flowJob = scope.launch { flow.run() }
    }

    override fun onDestroy() {
        flowJob?.cancel()          // 선택 창 닫힘 + 말하던 것 중지 (run의 finally)
        announcer?.close()         // 최근 안내 기록도 지움
        scope.cancel()
        super.onDestroy()
    }
}
```

`DecisionContext.announcementAvailable`에는 `tts.announcementAvailable`(우리 TTS 준비됨)을 넣는다.

### 흐름이 하는 일
| 들어온 것 | C가 하는 일 |
|---|---|
| `PopupEvent.Candidate` | 토큰 확인 → 분석·분류 → 선택 창 표시 (토큰은 단계마다 다시 확인) |
| 사용자가 [누르기] | `UserChoice(CLOSE_ONCE/SKIP_ONCE)` → `ChoiceHandler` → 결과가 성공이면 묶음 안내, 아니면 바로 이유 + 다음 선택 |
| [내용 다시 읽기] | 팝업 글자 낭독, 창 유지. **B/A로 보내지 않음** |
| [그대로 두기] | `ChoiceHandler`에 `KEEP_OPEN` 전달 후 끝 |
| `Blocked` / `Invalidated` / 새 후보 | 창 닫기, 말하던 것 중지. 예전 분석으로 다시 묻지 않음 |

## 2. 직접 띄우는 방법 — `ChoicePromptPresenter`

```kotlin
val prompt = ChoicePromptPlanner.plan(analysis, classification)   // 누르기 버튼은 A 게이트가 받는 라벨만
overlay.show(prompt) { choice ->
    // choice: UserChoice. B 정책과 A 게이트를 반드시 거친다. 버튼이 보였다고 실행 허가가 아니다.
}
// 토큰 무효화·팝업 사라짐
overlay.dismiss()
```

- `show`는 이미 떠 있는 창을 바꾼다. `dismiss`는 두 번 불러도 안전하다. 메인 스레드에서만 부른다.
- [누르기]·[그대로 두기]는 창을 먼저 닫고 콜백을 부른다. [내용 다시 읽기]는 창을 유지한다.

## 3. A에게 부탁하는 것
1. **C 창 이벤트 구분:** C 창 버튼·`liveRegion` 안내도 우리 앱 패키지의 접근성 이벤트다. `ChoiceOverlayIdentity.isOwnOverlayEvent(event.packageName, packageName)` / `isOwnOverlayWindow(...)`로 C 창 이벤트인지 구분할 수 있다. **C 창 이벤트라고 토큰 무효화를 전부 빼지는 않는다** (10/20 우진): A가 C 오버레이의 소유권과 이벤트 출처를 확인한 뒤, C 자체의 무해한 이벤트만 새 팝업 판정·baseline 초기화에서 뺀다. 대상 앱의 실제 화면 변경과 사용자 조작에 따른 현재성 검사는 그대로 유지하고 회귀 시험으로 확인한다 (설계 F11).
2. **버튼으로 이동 (계약 후):** `ButtonFocusMover.moveFocusTo(MoveToButtonRequest(token, candidateId))` — 토큰이 현재인지 확인하고 그 노드에 접근성 포커스만 (`ACTION_ACCESSIBILITY_FOCUS`). 누르지 않음. C는 `MoveToButtonFeature.enabled`가 꺼져 있어서 지금은 호출하지 않는다.
3. **실행 결과:** `ChoiceHandler`가 돌려주는 `ActionResult.status`로 C가 문장을 고른다. 성공 = `MECHANICAL_SUCCESS`만. 실패 후 다시 누르지 않는 것은 A 게이트 규칙과 같다.
