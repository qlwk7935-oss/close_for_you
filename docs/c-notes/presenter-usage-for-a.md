# A용 사용 예제 — C 선택 창·연결 부품 (초안, 2026-10-19 / 10-21 갱신)

> A(우진)가 서비스에서 C 선택 창을 띄울 때 참고하는 예제다. **A 코드는 C가 고치지 않았다.** 아래 코드는 예시이며 컴파일 확인은 C 단위 시험(`PopupChoiceFlowTest`)의 가짜 부품으로만 했다.
> `ChoicePromptPresenter`·`ButtonFocusMover`를 계약(`core/contract`)에 넣을지는 A와 합의할 것.
> **창 수명·실패 처리·C 창 식별 계약 초안(10/22, #18 우진 리뷰 대응): [overlay-identity-contract.md](overlay-identity-contract.md)**

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
        flowJob?.cancel()          // 선택 창 닫힘 + 말하던 것 중지 (run의 finally). 늦은 탭은 C가 버림
        // 창 제거가 실패했으면(windowState.status == REMOVE_FAILED) 여기서 overlay.dismiss()로 1번 더 시도 가능
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
| 선택 창을 못 띄움(`FAILED`/`BLOCKED`) | 그 팝업은 선택 없음으로 끝. B/A 안 부름. "선택 창을 띄우지 못했어요" 1번 |
| 고른 뒤 창을 못 닫음 | 선택을 버림(`Lost`). B/A 안 부름. "선택 창을 닫지 못해서 실행하지 않았어요" |
| 탭·음성 출처 | `ChoiceHandler.handle(choice, analysis, origin)` 3인자 메서드를 override 하면 받음(기본은 무시) |

### 영상 광고 안내 (F5, 기능 꺼짐 — 10/23 추가)
B의 `DecisionVideoAd.inspect(analysis)`(B #20) 결과를 C가 받는 통로. **core 계약은 바꾸지 않는다.** A 조립이 함수 하나만 넘기면 된다.

```kotlin
val flow = PopupChoiceFlow(
    // ... 위와 같음 ...
    videoAdOf = { analysis -> VideoAdInfo.from(DecisionVideoAd.inspect(analysis)) },   // B 결과 → C 입력
    // videoAdEnabled 기본값 = VideoAdAnnouncementFeature.enabled (false). 팀 결정 전까지 켜지 않음
)
// 직접 띄우는 방법(2장)이라면:
val prompt = ChoicePromptPlanner.plan(analysis, classification, videoAd = VideoAdInfo.from(DecisionVideoAd.inspect(analysis)))
```

- `analysis.videoSurfaceObserved`는 A가 채운다. 이것이 false면 B가 영상 광고로 보지 않으므로 `VideoAdInfo.from`이 null → 기존 문장 그대로.
- 켜면(B가 영상 광고로 보고 분류가 `ADVERTISEMENT`일 때만): 요약 "영상 광고로 보이는 팝업이에요." / B가 화면에서 읽은 카운트다운이 있고 지금 누를 건너뛰기 버튼이 없으면 "N초 뒤에 넘길 수 있다고 나와요. 지금은 대신 누를 버튼이 없어요."(이때 "대신 닫을 수 없어요" 대신 나옴).
- **누르기 버튼은 플래그와 무관하게 똑같다**: 기존 규칙(B 역할 = 라벨 역할 `SKIP`, E1/E2, 중요 결정 아님)으로만 ['…' 버튼 눌러 주기]. 카운트다운 숫자는 B 값 그대로(C가 세거나 추측하지 않음), 값이 서로 다르면 B가 null → 숫자 안 말함.
- **카운트다운이 끝나도 C는 기다렸다가 누르지 않는다.** 화면이 바뀌어 건너뛰기 버튼이 생기면 A의 새 팝업 이벤트 → 새 선택 창에서 사람이 고른다.
- 중요 결정(`USER_DECISION`)이면 영상 문장도 누르기도 없음. `VideoAdInfo`·`VideoAdNotice`에는 화면 글자가 없다.

## 2. 직접 띄우는 방법 — `ChoicePromptPresenter`

```kotlin
val prompt = ChoicePromptPlanner.plan(analysis, classification)   // 누르기 버튼은 A 게이트가 받는 라벨만
val result = overlay.present(prompt) { answer ->
    when (answer) {
        // B 정책과 A 게이트를 반드시 거친다. 버튼이 보였다고 실행 허가가 아니다.
        is PromptAnswer.Picked -> handle(answer.choice, answer.origin)
        is PromptAnswer.Move -> focusMover.moveFocusTo(answer.request)   // 기능 꺼짐
        is PromptAnswer.Lost -> Unit   // 창을 못 닫아 선택을 버림. 아무것도 실행하지 않는다
    }
}
if (result != PromptShowResult.SHOWN) { /* 선택 창 없음: 이 팝업은 사용자 선택 없음으로 끝 */ }
// 토큰 무효화·팝업 사라짐
overlay.dismiss()
```

- `present`는 이미 떠 있는 창을 바꾼다. 예전 `show(prompt) { choice -> }`도 그대로 된다(`Lost`는 버림).
- `dismiss`는 몇 번 불러도 안전하다. 창 제거가 실패했으면 부를 때마다 다시 지운다. 메인 스레드에서만 부른다.
- [누르기]·[그대로 두기]는 창을 먼저 닫고 답을 보낸다. 닫기가 실패하면 `Lost`. [내용 다시 읽기]는 창을 유지한다.
- 현재 세대가 아닌 창의 답(닫힌 뒤, 새 창이 뜬 뒤, 서비스 재연결 뒤 옛 창)은 C가 버린다.

## 3. A에게 부탁하는 것
1. **C 창 이벤트 구분:** C 창 버튼·`liveRegion` 안내도 우리 앱 패키지의 접근성 이벤트다. **패키지명만으로는 구분하지 않는다**(A 서비스도 같은 패키지). 창 종류 + 루트 패키지 + 고정 창 제목 + C가 알려 주는 창 상태·세대 + windowId로 판정하는 `OwnOverlayWindowTracker`를 쓴다 → [overlay-identity-contract.md](overlay-identity-contract.md) 2장. **C 창 이벤트라고 토큰 무효화를 전부 빼지는 않는다** (10/20 우진): A가 C 오버레이의 소유권과 이벤트 출처를 확인한 뒤, C 자체의 무해한 이벤트만 새 팝업 판정·baseline 초기화에서 뺀다. 대상 앱의 실제 화면 변경과 사용자 조작에 따른 현재성 검사는 그대로 유지하고 회귀 시험으로 확인한다 (설계 F11).
2. **버튼으로 이동 (계약 후):** `ButtonFocusMover.moveFocusTo(MoveToButtonRequest(token, candidateId))` — 토큰이 현재인지 확인하고 그 노드에 접근성 포커스만 (`ACTION_ACCESSIBILITY_FOCUS`). 누르지 않음. C는 `MoveToButtonFeature.enabled`가 꺼져 있어서 지금은 호출하지 않는다.
3. **실행 결과:** `ChoiceHandler`가 돌려주는 `ActionResult.status`로 C가 문장을 고른다. 성공 = `MECHANICAL_SUCCESS`만. 실패 후 다시 누르지 않는 것은 A 게이트 규칙과 같다.

## 4. 설정 화면 — 기억 목록 (F12 연결)

C 설정 화면은 같은 `app` 모듈이라 D의 `RuleStorage`를 바로 쓸 수 있다. D #15(Room)·#25(`cleanUp`)가 main에 있으므로 C가 묶음 하나로 만들어 두었다(`settings/RoomBackedRuleSettings.kt`). A는 `MainActivity`에 한 줄만 붙이면 된다.

```kotlin
// MainActivity.setContent { ... } 안
RoomBackedUiVoiceSettingsRoute()

// 같은 뜻을 풀어 쓰면
val context = LocalContext.current
val storage = remember { RoomBackedRuleSettings.create(context) }
UiVoiceSettingsRoute(storage)
// = repository     = RuleStorage.repository(context)    서비스와 같은 DB
//   deleteAll      = { RuleStorage.eraseAll(context) }   규칙 + 지문 키 폐기
//   onOpenCleanUp  = { RuleStorage.cleanUp(context) }    열 때 1번, 만료·손상 행 정리
```

- debug 미리보기(`ChoicePreviewActivity`)는 그대로 메모리 Mock(`DebugRules.repository`)과 가짜 규칙을 쓴다. 가짜 규칙은 실제 DB에 들어가지 않는다.
- `eraseAll`·`cleanUp`은 `Dispatchers.IO`에서 돈다(Keystore 키 폐기가 Room 호출이 아니어서).
- `deleteAll`을 넘기지 않는 옛 방식(`UiVoiceSettingsRoute(repository)`)은 `repository.deleteAll()`만 부른다(지문 키는 남음). Room 연결 때는 위 묶음을 쓴다.
- `cleanUp`이 실패해도 화면은 그대로 열리고, 로그는 남기지 않으며(원문 없음), 다음에 열 때 다시 한다. `cleanUp` 결과(지운 개수)는 C가 쓰지 않는다.
- [전체 삭제]는 **목록이 비어 있어도 항상 보인다**(D가 못 읽은 행도 지울 수 있게, 10/22 동준 부탁). 누르면 확인 창 뒤 넘긴 함수를 1번만 부른다(진행 중 두 번 눌러도 무시). 실패하면 "기억을 지우지 못했어요"를 보이고 다시 누를 수 있다.
- 목록 상태는 `RuleStatusPolicy.statusOf(rule, now)`: 해제됨(`enabled=false`) → 꺼짐·실패(`failureCount>0`) → 꺼짐(만료일 없음·다른 스키마·저장 시각보다 이전) → 만료됨(`now ≥ expiresAt`) → 켜짐. D의 `RuleValidity.isUsable`과 같은 경계다. 만료·실패 규칙도 [해제]·[전체 삭제] 대상.
- 시각은 `clock: Clock`(기본 `Clock.systemDefaultZone()`)으로 주입하고 화면에 돌아올 때 다시 읽는다. 날짜는 기기 로케일("11월 13일까지").

## 5. F9 자동 닫기 동의 — `AutoCloseConsentSource` (C, 2026-10-25)

C 설정의 "자동 닫기" 스위치(기본 꺼짐)를 A F9가 읽는 자리. `core/**`는 바꾸지 않았다. 동의 분리 표와 프리셋 변경 규칙은 [preset-contract-proposal.md](preset-contract-proposal.md) 4-1장.

```kotlin
// kr.closeforyou.ui.voice.settings
data class AutoCloseConsent(
    val granted: Boolean,                                   // 첫 실행 완료 && 스위치 켜짐 && PresetAutoCloseFeature.live
    val autoCloseKinds: ImmutableSet<PopupClassification>,  // granted가 아니면 빈 집합. 결정·모름은 절대 없음
    val changedAtEpochMillis: Long?,                        // 스위치·범위가 마지막으로 바뀐 시각
)
fun interface AutoCloseConsentSource { fun current(): AutoCloseConsent }  // NONE = 동의 없음

UiVoicePreferences.get(context).autoCloseConsentSource   // 부를 때마다 최신 설정을 읽음
UiVoicePreferences.get(context).current().autoCloseKinds() // DecisionContext.autoCloseKinds 에 넣는 값과 같음
```

#42 `F9ServiceConnection`에 넘기는 예 (A 코드, 제안만 — C는 고치지 않음):

```kotlin
val consent = UiVoicePreferences.get(service).autoCloseConsentSource
F9ServiceConnection(
    ...,
    // 앱별 범위(allowedPackages·F12 스위치)는 A가 따로 본다. 종류 판단은 B가 DecisionContext.autoCloseKinds로.
    presetAndFirstSeenConsent = { _ -> consent.current().granted },
    policy = ...,
)

// 동의·범위가 바뀌면 이전 승인 무효화
scope.launch {
    UiVoicePreferences.get(service).state
        .map { it.autoCloseConsentNow() }
        .distinctUntilChanged()
        .collect { connection.invalidatePermissionOrInteraction() }
}
```

- 지금은 `PresetAutoCloseFeature.live = false`라 `granted`는 항상 false, `autoCloseKinds`는 빈 집합이다. 스위치를 켜도 그렇다.
- `granted`는 **정책 입력값**이다. B의 PRESET/EXECUTE 승인과 A의 AutoEligibility 5조건이 그대로 필요하다.
- 결과를 캐시하지 말고 판단할 때마다 `current()`를 부른다(끄면 즉시 빈 집합).

