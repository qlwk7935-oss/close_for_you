# 작업 보고서 — 2026-10-19 낮 무인 작업 (C 재호)

> 11:34~12:16. 지시서(`night-plan-2026-10-19.md`) **본 목록 1~9단계와 예비 목록 1~6을 모두 마쳤다.**
> **커밋·push는 하지 않았다** (시작할 때 `git merge origin/main` 1회만 — 병합 커밋 `d0aa0be`가 로컬에 생김, push 안 됨).
> 코드 단계마다 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 통과. 마지막: **단위 시험 230개, 실패 0, lint 경고 7개(모두 기존 Gradle 버전 알림, C 새 경고 0).**
> 전부 **Mock 기준, 실제 A 서비스와 연동은 미검증.** LG 폰은 연결되지 않아 **실기기 시험은 하지 않았다** (폰 설정 변경 없음).

## 1. 한눈에 보기

| 단계 | 결과 | 시험 |
|---|---|---|
| 시작 | `origin/main` 병합 (PR #11·#12 포함) — **충돌 없음** | — |
| 1 ⚠️ 라벨 표시 맞추기 | ✅ A 게이트가 받는 라벨일 때만 누르기 버튼. 판정은 주입 가능한 `PressableLabelPolicy`(B 초안 `roleOf` 모양) | B 시험 벡터 175개가 A 현재 게이트와 **전부 일치**, 거부 범주 오허용 0 |
| 2 누르기 실패 안내 | ✅ 성공이 아니면 이유 + 다음 선택, "닫았어요" 안 함, 다시 누르기 안 권함. 화면이 바뀌었으면 다시 묻지 않음 | 단위 시험 + 미리보기 "누르기 결과 흉내" |
| 3 [버튼으로 이동] 준비 | ✅ 기능 플래그 **꺼짐**. 선택 창·음성·실패 후속 안내에 연결만, A 포커스 요청 인터페이스 초안 | 단위 시험 9개 (플래그 켠 상태로) |
| 4 안내 길이 반영 | ✅ "자세히" = 버튼 이름 + 선택지 개수만 (분류 근거 없음). 묶음 안내는 종류가 섞일 때 전체 개수 | 단위 시험 |
| 5 TTS 상태 계약 초안 | ✅ `contract-proposal-tts-2026-10-19.md` (**C 제안**, `core/**` 안 고침) | 문서 |
| 6 연동 부품 | ✅ `PopupChoiceFlow`: A가 이벤트·분석·실행 경로만 넘기면 됨 | Mock 단위 시험 7개 |
| 7 접근성 마무리 | ✅ 명도 대비 전부 통과. 오버레이 **스크롤 없음** 발견 → 고침. 다크 모드 미대응 발견(결정 필요) | 계산값. 큰 글꼴 실기기 ❌ (폰 없음) |
| 8 README·시험 보강 | ✅ README 새로 씀, 경계 시험 추가 | 단위 시험 |
| 9 PR 초안·보고서 | ✅ `pr-draft-ui-voice.md`, 이 문서 | — |
| 예비 1 설정 다듬기 | ✅ 설명 문장(행 안에 넣어 TalkBack이 함께 읽음), 빈 상태 설명 | 빌드·lint |
| 예비 2 음성 변형 | ✅ 존댓말·반말 변형, 헷갈리는 말 10개 무시 시험 | 단위 시험 |
| 예비 3 실험 문서 | ✅ `overlay-talkback.md` 6장 "본 앱에 반영한 결론" (실험 앱 코드는 안 바꿈) | 문서 |
| 예비 4 선택 창 UI | ⚠️ 검토만. 큰 글꼴은 7단계에서 고침, 간격(12dp)·48dp 문제 없음. **다크 모드는 팀 결정 필요**라 안 바꿈 | — |
| 예비 5 A용 예제 | ✅ `presenter-usage-for-a.md` | 문서 |
| 예비 6 작은 일 | ✅ 진행 기록 시각 정리, 계약 초안 줄 번호 확인 | — |

## 2. 파일별 설명 (무엇·왜)

### 1단계 — 라벨 표시 맞추기
| 파일 | 무엇을 | 왜 |
|---|---|---|
| `ui/voice/choice/PressableLabelPolicy.kt` (새) | `fun interface PressableLabelPolicy { roleOf(label) }` + `AGateLabelPolicy` | A 10/18 요청 "실행 가능하다고 잘못 안내하지 말 것". A 게이트 실제 코드(`origin/feature/a-action-gate` 5729ec1 `ActionGateValidator.labelRole`)를 읽어 **그대로** 복사: NFKC → 소문자 → 앞뒤 공백 제거 → 8개 정확 일치. 내부 공백 합치기·문장부호 제거 안 함(A와 같게). `REPLACE-WITH-CONTRACT` 표시 |
| `ui/voice/choice/ChoicePromptPlanner.kt` | `plan(..., labelPolicy)`, 누르기 조건에 `roleOf(label) == candidate.role` 추가 | B 초안 11절의 C 교체 방식과 같은 모양 → 계약 병합 후 기본값만 바꾸면 됨 |
| `test/.../choice/PressableLabelPolicyTest.kt` (새) + `test/resources/.../label_test_vectors.csv` (B 초안 첨부 복사) | 8개 허용, 대소문자·전각·앞뒤 공백, X·×·확인·문장부호·폭 없는 공백 거부, **175개 벡터 = A 현재 열 일치**, 역할 불일치 거부, 주입 정책 시험, 라벨 공백 표시, `toString`에 라벨 없음 | |
| `test/.../command/VoiceChoiceMapperTest.kt` | 시험 라벨 "X 닫기" → "창 닫기" | 새 정책에서 "X 닫기"는 누르기 버튼이 아니라서 "닫기 버튼 2개 = 모호" 시험이 성립하지 않음 |

### 2단계 — 누르기 실패 안내
| 파일 | 무엇을 |
|---|---|
| `ui/voice/PressResultSpeech.kt` (새) | `ActionStatus` → `PressFailure`(안 누름 / 화면 바뀜 / 누르기 실패 / 결과 모름) → 이유 문장 + 다음 선택 문장. `AnnouncementBatcher.outcomeOf`와 같은 기준(성공만 "닫음") |
| `ChoicePromptPlanner.kt` | `ChoicePrompt.pressFailure`, `afterFailedPress()`: 누르기 버튼을 뺀 후속 선택 창. **화면이 바뀌었으면(STALE) null** (AGENTS 6-7) |
| `ChoicePromptContent.kt`, `PromptAnnouncementListener.kt` | 후속 선택 창에 이유 한 줄 표시 |
| `ui_voice_strings.xml` | `ui_result_*` 7개 |
| debug `ChoicePreviewActivity.kt`, `ui_voice_debug_strings.xml` | **"누르기 결과 흉내"** 구역: 흉내 안 함 / 눌림 / 막힘 / 화면 바뀜 / 누르기 실패 / 결과 모름 → 누르기 버튼을 누르면 결과 문장을 TTS로 말하고 후속 선택 창으로 바뀜. [선택 창 처음으로] |
| `test/.../PressResultSpeechTest.kt` (새) | 상태별 이유, 묶음 안내와 일관, "닫았어요" 안 함, 후속 선택 창, STALE은 후속 없음 |

### 3단계 — [버튼으로 이동] 준비 (꺼짐)
| 파일 | 무엇을 |
|---|---|
| `ui/voice/choice/MoveToButton.kt` (새) | `MoveToButtonFeature.enabled = false`, `MoveToButtonRequest(token, candidateId)`, **A 제안** `ButtonFocusMover`, `PromptSelection`(UserChoice 또는 이동 요청) |
| `ChoicePromptPlanner.kt` | `PromptOptionKind.MOVE_TO_BUTTON`: 누를 수 없는 버튼(확인/OK, 결정 팝업 버튼 포함) 중 노드·라벨이 있는 것. `select()`로 이동 요청을 따로 냄 — **실행 관문으로 가지 않음**. 실패 후속 창에서는 실패한 버튼이 "이동"으로 바뀜 |
| `ChoicePromptPresenter.kt`, `AccessibilityChoiceOverlay.kt` | `show(prompt, onMoveToButton = {}, onChoice)` — 기존 `show(prompt) { choice -> }` 호출이 그대로 동작하게 순서 배치 |
| `command/VoiceChoiceMapper.kt` | "버튼으로 이동" → 이동 선택지가 **정확히 1개**일 때만 |
| `test/.../choice/MoveToButtonTest.kt` (새) | 기본 꺼짐, 확인 버튼은 이동만, 누를 수 있는 버튼은 중복 안 됨, 결정 팝업은 이동만, `toUserChoice` 거부, 음성 1개/2개/꺼짐 |

### 4단계 — 안내 길이
| 파일 | 무엇을 |
|---|---|
| `overlay/PromptAnnouncementListener.kt` | `PromptSpeech.parts(prompt, detail)` → `SpeechPart(문장 리소스, 인자)` 목록(시험 가능). 자세히 = "대신 누를 수 있는 버튼: '닫기', '건너뛰기'." + "선택지는 N개예요." 리스너에 `detail` 추가 |
| `AnnouncementBatcher.kt` | `BatchSentence.build(summary, detailed, text)` — 종류가 2개 이상일 때만 "모두 N개예요." |
| debug `ChoiceOverlayTestService.kt` | 설정의 안내 길이를 사용 |
| 시험 | `PromptSpeechTest` 4개 추가, `AnnouncementBatcherTest` 1개 추가 |

### 5단계 — `docs/c-notes/contract-proposal-tts-2026-10-19.md` (새)
- `AnnouncementState.FAILED`, `VoiceEvent.NotUnderstood`, `announcementAvailable` = 우리 TTS 준비 + `talkBackActive` 별도 값(기본 false). diff·줄 번호(main 845214a 기준)·영향(A/B/C/D)·필요한 시험.
- 부록: `MOVE_TO_BUTTON` C 요구사항(C 추천 = `ChoiceKind` 대신 A 별도 요청 → B 변경 없음), 앱별 자동 닫기 C 요구사항.

### 6단계 — `ui/voice/integration/PopupChoiceFlow.kt` (새)
- `Candidate` → 토큰 확인 → `EvidenceExtractor` → `PopupClassifier` → 선택 창 → `ChoiceHandler`(A/B: 정책 + 게이트) → 성공은 묶음 안내, 실패는 **항상 즉시 말하고**(설정과 무관) 후속 선택 창은 조용히 뜸(같은 말 두 번 방지).
- `Blocked`/`Invalidated`/새 후보 → `collectLatest`가 이전 작업 취소, 창 닫기, 말하던 것 중지. 토큰은 단계마다 재확인.
- `PopupChoiceFlowTest.kt` (새): 성공 묶음, 막힘 + 후속 + 그대로 두기, STALE, 다시 읽기 후 창 유지, 무효화, 보호 화면, 결정 팝업.

### 7단계 — 접근성
| 파일 | 무엇을 |
|---|---|
| `docs/c-notes/a11y-check-2026-10-19.md` (새) | 명도 대비 표 16항목 **전부 통과** (material3 1.3.2 실제 색 토큰을 `javap`로 읽어 계산). 큰 글꼴 코드 검토 |
| `overlay/AccessibilityChoiceOverlay.kt` | 선택 창에 `verticalScroll` + `statusBarsPadding` — 글꼴 200%에서 버튼 4~5개면 창이 화면보다 길어져 **아래 버튼이 잘릴 수 있었음** |

### 8단계·예비
| 파일 | 무엇을 |
|---|---|
| `ui/voice/README.md` | 패키지 구조, 계약 대응표, 안전 규칙 요약, 미검증 표 |
| `test/.../command/KoreanVoiceCommandMatcherTest.kt` | 16자/17자 경계, 존댓말·반말 변형, 헷갈리는 말 10개 |
| `command/KoreanVoiceCommandMatcher.kt` | 변형 추가: 닫어·닫으세요·닫을래 / 건너뛰세요·건너뛸게·넘어가 / 읽어주라·읽어줄래 / 그대로두세요·그냥두세요·놔두세요·냅둬·내비둬. **어간만("닫")은 넣지 않음** ("문 닫는 시간" 같은 문장 방지) |
| `settings/UiVoiceSettingsScreen.kt`, `ui_voice_strings.xml` | 안내 길이 두 선택지·선택 창 음성 스위치에 설명 문장(행 안 → TalkBack이 이름·상태와 함께 읽음), 기억 목록 빈 상태 설명 |
| `docs/experiments/overlay-talkback.md` | 6장: 실험 결론 → 본 앱 반영 표, 남은 실험 4개 |
| `docs/c-notes/presenter-usage-for-a.md` (새) | A가 `PopupChoiceFlow`/`ChoicePromptPresenter`를 쓰는 예제, A에게 부탁 3가지 |
| `docs/c-notes/pr-draft-ui-voice.md` (새) | PR 설명 초안 (AGENTS 8장 형식) |
| `docs/c-notes/night-log-2026-10-19.md` (새) | 단계별 진행 기록 |

## 3. 시험 결과

| 시점 | 단위 시험 | lint |
|---|---|---|
| 1단계 끝 | 204 / 실패 0 | 경고 7 (기존 Gradle 버전 알림) |
| 3단계 끝 | 213 / 0 | 7 |
| 4단계 끝 | 218 / 0 | 7 |
| 6단계 끝 | 225 / 0 | 7 |
| 8단계 끝 | 228 / 0 | 7 |
| 예비 1·2 끝 (마지막 코드 상태) | **230 / 0** | 7 |

- 그 뒤로는 문서만 바꿨다 (빌드 영향 없음).
- 4단계 빌드는 16분 걸렸다 (다른 단계는 1~2분). 원인 미확인 — Gradle 데몬 첫 실행 또는 PC 부하로 추정.
- 실기기: **없음** (LG 미연결, 11:34·12:06·12:15 `adb devices` 빈 목록).

## 4. 추천 커밋 묶음 (순서대로, 각 커밋 전에 빌드)

> 빌드: `$env:JAVA_HOME = "D:\Android\Android Studio\jbr"; .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
> 로컬에 이미 **병합 커밋 `d0aa0be`** (origin/main 병합)가 있다. 아래 커밋들과 함께 push하면 된다.
> `ChoicePromptPlanner.kt`·`ui_voice_strings.xml`에 1~4단계 변경이 섞여 있어 단계별로 나누기 어렵다 → 본체를 한 번에 넣는 것을 추천.

1. **본체 + 시험** (debug 없이 이것만으로 빌드됨 — 기존 debug 코드는 바뀐 API와 호환)
   - `app/src/main/java/kr/closeforyou/ui/voice/` 전체 (새 파일: `PressResultSpeech.kt`, `choice/PressableLabelPolicy.kt`, `choice/MoveToButton.kt`, `integration/PopupChoiceFlow.kt`. README 포함)
   - `app/src/main/res/values/ui_voice_strings.xml`
   - `app/src/test/java/kr/closeforyou/ui/voice/` 전체, `app/src/test/resources/kr/closeforyou/ui/voice/choice/label_test_vectors.csv`
   - 메시지: `feat(ui-voice): A 게이트 라벨만 누르기 표시, 실패 안내, 안내 길이, 연동 부품 추가 (Mock 기준)`
2. **debug 시험 도구**
   - `app/src/debug/java/kr/closeforyou/ui/voice/debug/ChoicePreviewActivity.kt`, `ChoiceOverlayTestService.kt`, `app/src/debug/res/values/ui_voice_debug_strings.xml`
   - 메시지: `test(ui-voice): 미리보기에 누르기 결과 흉내 추가, 시험 서비스가 안내 길이 사용`
3. **문서**
   - `docs/c-notes/contract-proposal-tts-2026-10-19.md`, `a11y-check-2026-10-19.md`, `presenter-usage-for-a.md`, `pr-draft-ui-voice.md`, `night-report-2026-10-19.md`, (원하면) `night-plan-2026-10-19.md`·`night-log-2026-10-19.md`, `docs/experiments/overlay-talkback.md`
   - 메시지: `docs(ui-voice): TTS 상태 계약 제안, 10/19 접근성 점검, A용 예제, PR 초안`

## 5. 결정할 것 (사용자 / 팀)

### 사용자(재호)
1. **준영 답장에 "거부 이유 종류" 요청을 넣을지** — 아직 결정 전이라 선택 창은 "이 팝업은 대신 닫을 수 없어요" 그대로 둠.
2. **TTS 상태 계약을 C가 맡을지** — 문서는 "C 제안"으로 씀. 맡기로 하면 팀 공유(아래 8장) 후 계약 PR.
3. **B 시험 벡터 CSV를 C 시험 폴더에 복사**해 둠 (준영 첨부 파일). 계약 PR에 같은 파일이 들어오면 그쪽을 쓰고 C 복사본은 지울지.
4. **[버튼으로 이동]을 결정 팝업(결제·동의 버튼)에도 줄지** — CLAUDE.md 3-1("위치 안내까지만")에 따라 지금은 **줌**(플래그 꺼짐이라 화면엔 안 보임). 사용자가 누르는 쪽으로 유도하는 느낌이면 결정 팝업에서는 뺄 수 있음.
5. **[버튼으로 이동] 계약 방식** — (가) `ChoiceKind.MOVE_TO_BUTTON` vs (나) A 별도 요청 `ButtonFocusMover`. C 추천 (나).
6. **다크 모드** — C 화면은 기기가 다크 모드여도 항상 밝은 색. 앱 전체 테마(A `MainActivity`와 함께)를 정해야 함.
7. **설정의 앱 이름** — 지금 "kr.example.shop 에서 자동 닫기"처럼 패키지 이름이 보임. 앱 이름을 보이려면 다른 앱 정보 조회(Android 11+ 패키지 공개 범위, main 매니페스트 `<queries>`)가 필요해 **승인 범위 밖이라 안 함**.
8. 실패 후속 선택 창 동작 — 실패 문장은 설정과 무관하게 **항상** 우리 TTS로 말하게 함("창 제목만" 설정이어도). 맞는지.

### A(우진)에게
9. C 창 이벤트를 토큰 무효화·새 팝업 판정에서 빼기 (`ChoiceOverlayIdentity`) — 10/18부터 요청 중.
10. `ChoicePromptPresenter`·`ButtonFocusMover`를 계약에 넣을지, `PopupChoiceFlow`를 A 서비스에서 쓸지 (`presenter-usage-for-a.md`).
11. `talkBackActive`를 A가 채울 수 있는지 (`AccessibilityManager` 기준, 실기기 확인 필요).

## 6. 사람이 확인할 것 (실기기)
1. **(LG 연결 후) 큰 글꼴 200%**: 선택 창 시나리오 6(닫기·건너뛰기 둘 다)이 잘리지 않는지, 스크롤·TalkBack 쓸어넘기기 자동 스크롤, 상태 표시줄을 가리지 않는지. 설정 화면 "기억 전체 삭제"·확인 창. **끝나면 글꼴 크기를 원래대로.**
2. 미리보기 **"누르기 결과 흉내"**: 각 결과를 고르고 [누르기] → 들리는 문장, 후속 선택 창 버튼(다시 읽기·그대로 두기만), [선택 창 처음으로].
3. 설정 → 안내 길이 "자세히" → 시험 서비스로 다른 앱 위 창 띄우기 → "대신 누를 수 있는 버튼: '닫기', '건너뛰기'. 선택지는 4개예요."가 들리는지.
4. 설정 화면 TalkBack: "짧게, 어떤 팝업인지와 … , 선택됨" 처럼 설명이 함께 읽히는지, 너무 길지 않은지.
5. 갤럭시 S26: 4-5 시험 반복, 기기 안 음성 인식, 새 음성 변형(닫으세요·냅둬 등) 인식률.
6. 다크 모드에서 밝은 선택 창이 불편한지.

## 7. 사용량
| 시점 | 5시간 | 주간 |
|---|---|---|
| 시작 (11:38) | 20% | 72% |
| 2단계 끝 | 25% | 73% |
| 4단계 끝 (12:02) | 30% | 74% |
| 6단계 끝 (12:06) | 33% | 74% |
| 8단계 끝 (12:11) | 37% | 75% |
| 끝 (12:15) | 40% | 75% |

추가 유료 사용량: 꺼짐 (켜지 않음).

## 8. 팀 공유 메시지 초안 (재호가 직접 보냄)

> 우진님, 준영님 — C 10/19 진행 공유해요. (전부 Mock 기준, 실제 서비스 연동·실기기 확인은 아직이에요)
>
> **1. 선택 창 누르기 버튼을 A 게이트 기준에 맞췄어요.** 우진님 요청대로 게이트가 받는 라벨(지금 8개: 닫기/창 닫기/팝업 닫기/close/dismiss/건너뛰기/스킵/skip)일 때만 누르기 버튼이 보이고, X·×·라벨 없는 DISMISS·확인 등은 "이 팝업은 대신 닫을 수 없어요"로 나와요. 게이트 코드(PR #10 `labelRole`)를 그대로 옮긴 임시 정책이고, 준영님 초안 v1의 `roleOf(label) == candidate.role` 모양으로 만들어서 계약 병합되면 한 줄만 바꾸면 돼요. 준영님 시험 벡터 175개로 돌려 보니 A 현재 열이랑 전부 일치했어요.
>
> **2. 누르기 결과가 성공이 아니면** (BLOCKED/STALE/NOT_EXECUTED/ATTEMPT_FAILED/OUTCOME_UNKNOWN) "닫았어요"라고 안 하고 이유 + 다음 선택(다시 읽기/그대로 두기)을 알려요. 다시 누르기는 권하지 않고, STALE이면 예전 분석으로 다시 묻지 않아요.
>
> **3. 연결 부품 `PopupChoiceFlow`** 만들었어요. A 서비스에서 SnapshotSource·EvidenceExtractor·B 분류기·"선택 → B 정책 → A 게이트" 함수만 넘기면 선택 창·안내·실패 안내·묶음 안내는 C가 해요. 예제는 `docs/c-notes/presenter-usage-for-a.md`에 있어요.
>
> **4. [버튼으로 이동]**은 합의대로 "포커스 이동만, 클릭 허가 아님"으로 준비만 해 두고 꺼 놨어요. 계약 방식은 `ChoiceKind`에 넣는 것보다 A 쪽 별도 요청(`ButtonFocusMover.moveFocusTo(token, candidateId)`)이 B 변경이 없어서 좋을 것 같아요. 의견 주세요.
>
> **5. TTS 상태 계약 초안**(C 제안): `AnnouncementState.FAILED`, `VoiceEvent.NotUnderstood`, `announcementAvailable` = "우리 TTS 준비됨"(TalkBack 상태는 별도 `talkBackActive`, 기본 false) — `docs/c-notes/contract-proposal-tts-2026-10-19.md`. 기본값이 있어서 B 코드랑 IntegrationExample은 안 바뀌어요.
>
> **6. 다시 부탁:** C 선택 창 버튼 탭도 접근성 이벤트라서, `ChoiceOverlayIdentity`로 C 창 이벤트는 토큰 무효화·새 팝업 판정에서 빼 주실 수 있을까요?
