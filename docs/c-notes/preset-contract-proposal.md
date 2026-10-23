# 제안: 팝업 처리 방식(프리셋)을 B에 넘기는 방법 (C → B·A·D)

> 작성 2026-10-23, C(재호). **제안만** — `core/**`는 바꾸지 않았다. 합의 후 별도 PR.
> 팀 결정(10/23 우진): 광고 자동 처리와 프리셋별 처리를 팀 최우선 목표로 합의. AGENTS 6장 개정은 합의 중, 실제 클릭(F9)은 아직 없음(A 게이트 `NOT_EXECUTED`).
> 지금 상태: C가 프리셋 UI·저장만 만들었다. **프리셋을 골라도 지금은 동작이 바뀌지 않는다**(Mock 기준, 실제 연동 미검증).

## 1. C에 이미 있는 것 (feature/ui-voice)

| 항목 | 위치 |
|---|---|
| 선호 값 `PopupHandlingPreference { CLOSE, ANNOUNCE, ASK }` | `ui/voice/settings/HandlingPreset.kt` |
| 프리셋 3개 `HandlingPreset` + 표 | 같은 파일 |
| 분류별 선호 `HandlingPreset.preferenceFor(PopupClassification)` | 같은 파일 (`USER_DECISION`·`UNKNOWN` → 항상 `ASK`) |
| 저장 `UiVoiceSettings.handlingPreset`(기본 `CLOSE_ADS_ONLY`), `onboardingDone` | `UiVoiceSettings.kt` (C SharedPreferences) |
| 실제 동작 전 안내 한 줄 플래그 `PresetAutoCloseFeature.live = false` | `HandlingPreset.kt` |

### 프리셋 표

| 프리셋 | 광고 `ADVERTISEMENT` | 공지 `NOTICE` | 쿠폰 (분류 없음) | 중요 결정 `USER_DECISION` | 판단 불가 `UNKNOWN` |
|---|---|---|---|---|---|
| `CLOSE_ALL` 전부 자동 닫기 | CLOSE | CLOSE | CLOSE | **ASK (고정)** | **ASK (고정)** |
| `CLOSE_ADS_AND_COUPONS` 광고·쿠폰 닫기, 공지는 알려 주기 | CLOSE | ANNOUNCE | CLOSE | **ASK (고정)** | **ASK (고정)** |
| `CLOSE_ADS_ONLY` 광고만 닫기 (**기본**) | CLOSE | ANNOUNCE | ANNOUNCE | **ASK (고정)** | **ASK (고정)** |

- CLOSE = "안전 검증을 통과하면 닫기를 원함". **실행 허가가 아니다.** B 정책·A 게이트가 최종.
- ANNOUNCE = 내용을 알려 주고 닫지 않음(지금 선택 창처럼 고를 수 있음).
- "전부 자동 닫기"도 안전 검증을 통과한 광고·쿠폰·공지만이다. 중요 결정은 어떤 프리셋에서도 ASK.

## 2. B에 넘기는 방법 (제안)

```kotlin
// core/contract (합의 후 별도 PR) — 기본값으로 지금 동작이 그대로 유지된다
data class DecisionContext(
    ...기존 필드...,
    /** 사용자의 종류별 처리 선호. 실행 허가가 아님. null = 선호 없음(지금과 같음 = ASK). */
    val handlingPreference: PopupHandlingPreference? = null,
)
```

- 선호 값 enum은 `core/model`로 옮긴다(C 패키지에서 이동, 이름 그대로). C의 `HandlingPreset`은 C에 남고 `preferenceFor(classification)`만 A가 호출해 넣는다.
- A(조립): B 분류 결과 → `UiVoicePreferences.get(ctx).current().handlingPreset.preferenceFor(classification)` → `DecisionContext.handlingPreference`.
- B: `USER_DECISION`·`UNKNOWN`이면 받은 값과 **관계없이** 기존처럼 확인. 이 불변식은 C 표(`preferenceFor`)와 B 정책 **양쪽에** 둔다(이중 방어).
- 기본값 `null`이라 필드를 추가해도 기존 시험·동작은 그대로.
- 앱별 자동 닫기(`autoClosePackages`)는 **별개**: "기억한 똑같은 팝업 반복 자동"(F12, `matchingRule`). 프리셋과 섞지 않는다. 화면 문구도 둘을 따로 설명한다.

## 3. 쿠폰 분류가 생기면

- B가 `PopupClassification`에 쿠폰(예: `COUPON`)을 추가하면 C의 `preferenceFor(classification)`에 한 줄 연결 (`PresetPopupKind.COUPON` 값은 이미 저장됨).
- 그 전까지 화면은 "쿠폰을 따로 알아보는 기능은 준비 중이에요."만 말한다. 쿠폰이 광고로 분류되는지는 **단정하지 않는다**.

## 4. 처음 보는 광고 자동 닫기(AGENTS 6-2 개정) 조건 — 모두 만족할 때만

| # | 조건 | 담당 | 확인 방법 |
|---|---|---|---|
| 1 | 사용자가 그 종류에 CLOSE 프리셋을 골랐다 (`handlingPreference == CLOSE`) | C·A | DecisionContext 값 |
| 2 | B가 광고로 **확신** (확신 기준·임계값은 B 정의) | B | B 시험·D 정답지 |
| 3 | 중요 결정 신호 없음 (결제·구매·동의·권한·구독·삭제) | B | `USER_DECISION` 아님, 위험 단어 없음 |
| 4 | 대상 버튼이 공용 라벨 계약(`ButtonLabelRules`)상 안전 닫기 버튼, 근거 **E1/E2** | B | `CloseCandidate.evidence` |
| 5 | **E3/E4 라벨 없는 X는 위치·모양만으로 누르지 않음.** 팝업 소속·닫기 동작의 추가 근거가 있어야 하고, 그것도 별도 합의 | B·A | 지금은 제외 |
| 6 | 영상 '건너뛰기'는 후보 충돌(닫기·건너뛰기 동시, P17)과 실제 효과(눌러서 정말 넘어가는지) 검증 후에만 | B·D | P16·P17 재현 |
| 7 | 클릭 직전 A가 실제 화면·대상 버튼을 다시 읽어 검증 (토큰·창 일치), 실패 시 재시도·좌표 탭 없음 | A | A 게이트 시험 |
| 8 | 연속 팝업(P12): 이전 C 창 제거 완료 → 새 토큰 발행 순서로 상태 전이. 이전 선택·분석을 다음 팝업에 쓰지 않음 | A·C | P12 재현, `OverlayWindowKeeper` |
| 9 | AGENTS 2장·6장 문구, B `FIRST_SEEN` 정책, D 정답지(기대 처리)가 같은 내용으로 함께 바뀜 | 전원 | 문서·코드 리뷰 |
| 10 | 회귀 시험: 결정 팝업 자동 0건, 라벨 없는 X 자동 0건, 오클릭 0건 | D | 자동 시험 + 실기기 TalkBack |

- 조건 하나라도 빠지면 지금처럼 선택 창으로 묻는다.
- C 쪽 일: 조건이 모두 붙으면 `PresetAutoCloseFeature.live = true`로 "자동 닫기는 곧 켜질 예정이에요. 지금은 닫기 전에 물어봐요." 한 줄을 지운다. 결과 안내는 기존 묶음 안내(`ui_announce_batch_*`)를 쓰고 "닫았어요"는 버튼을 눌렀다는 뜻으로만 쓴다(닫힘 확인 전 단정 금지).

## 5. 남은 결정 (사용자·팀 확인)

- 기본 프리셋: 기획 의도와 안전 쪽을 따라 `CLOSE_ADS_ONLY`(광고만 닫기). 사용자 확인 필요.
- "직접 고르기(사용자 지정)" 프리셋: 이번 범위 밖, 다음 단계.
- 첫 실행 화면 진입: A `MainActivity`에 `UiVoiceOnboardingGate { … }`를 두르는 최소 변경 — 시안 브랜치 `c/settings-entry-v2`에만 있음(A 리뷰 전).
