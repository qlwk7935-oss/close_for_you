# 안전 규칙 ↔ 코드 대조표 (초안, 리뷰 요청)

AGENTS.md 6장의 규칙 10개가 시연 엔진 코드 어디에 있고, 어떤 시험이 지키는지 찾기 위한 표입니다.

- **기준:** `integration/popup-helper` df726f4
- **대상:** 시연에 쓰는 엔진 `kr.closeforyou.simpleclose`
- **초안 범위:** B가 코드와 시험을 직접 읽고 썼습니다. C 칸은 재호 리뷰를 반영했고, A 칸은 우진의 확인이 필요합니다.
- **실기기:** 아래 "시험"은 JVM 단위 시험입니다. 실기기 결과는 이 표의 근거에 넣지 않았습니다.
- **코드 위치:** 줄 번호는 금방 바뀌어서 파일과 이름으로 적었습니다.

## 먼저 알아 둘 점

1. **엔진이 두 개입니다.** 매니페스트에 A의 `CloseForYouAccessibilityService`와 시연용 `SimpleCloseAccessibilityService`가 함께 있습니다. 이 표는 시연용 기준입니다. A 엔진의 F0·F9·AutoEligibility는 시연 경로에서 쓰지 않습니다.
2. **6-0 시연 모드 조항은 이미 있습니다.** `AGENTS.md`의 `6-0`이 10/29(5ea5ebc)부터 main과 통합 브랜치에 있습니다. 예전 브랜치에는 없을 수 있습니다.
3. **상태 칸의 뜻**
   - 유지: 규칙 그대로 코드에 있습니다.
   - 시연 완화: 6-0에 따라 원래 규칙보다 느슨합니다.
   - 일부: 규칙의 일부만 코드로 지킵니다. 아래 "확인이 필요한 점"에 이유를 적었습니다.

## 대조표

| 규칙 | 상태 | 코드 | 시험 | 담당 확인 |
|---|---|---|---|---|
| 1. 결제·구매·동의·권한·구독·삭제 자동 실행 안 함 | 유지 | `SimpleCloseDecider.decide`(결정은 `DenyDecision`, 위험 버튼은 `Ask`), `SimpleLabelRules`(금지어·위험어), `OverlayExecutionRules.PrePressRecheck`, `ui/voice/command/VoicePolicy`(음성은 버튼으로 이동만). 선택 창도 결정 팝업에는 [버튼으로 이동]·[내용 다시 읽기]·[그대로 두기]만 제시(`SimplePromptOptions.adjust`, 이동은 누르지 않음) | `StrongCloseTest.aRiskyButtonBesideTheCloseKeepsItAskedInEveryPreset`, `BLabelSafetyTest`, `PromoPurchaseDeciderTest`, `VoicePolicyTest.refusedWordsWinOverCommands` | B, C(음성) |
| 2. 처음 보는 팝업은 동의·판단·적격 조건을 모두 만족할 때만 닫음 | 시연 완화 | 동의: `UiVoiceSettings.autoCloseKinds(live = true)`. 판단: `SimpleCloseDecider`, `StrongCloseRules`, `DemoUnknownAutoClose`. AutoEligibility는 쓰지 않음 | `StrongCloseTest`, `F40Test`, `DecisionDemoRulesTest` | B, C |
| 3. 이름 없는 X는 처음 본 팝업에서 자동으로 안 누름 | 유지 | `StrongCloseRules.pick`(이름 있는 닫기만), `CloseButtonFinder`, `SimplePopupAnalysis`의 `scan.xButton`. 선택 창의 "'X 모양 닫기' 버튼 눌러 주기"로만, 사용자 탭일 때만 누르고, 위험 버튼이 있으면 제시도 안 함(`OFFER_X_BUTTON`) | `F22RegressionTest.theElevenStreetAppModalIsFoundAndItsXIsOfferedNeverPressed`, `CloseButtonFinderTest`, `BLabelSafetyTest` | B, C |
| 4. 사용자 허락과 동일 팝업 검증이 있어야 반복 자동화 | 유지 | `RememberedPopupRules.canRemember`·`match`, 저장은 닫힘 확인(`PressSuccess.isConfirmed`) 뒤, 서비스의 `rememberedButton` 조건(결정·사기·위험 버튼·실패 이력·스위치 꺼짐 제외). 해제는 설정 화면의 기억 지우기(`UiVoiceSettingsScreen`의 `onClearEngineRemembered`) | `F22RegressionTest.aRememberedXIsFoundOnlyForTheSamePopupInTheSamePlace`, `BReviewF33Test` | B, C |
| 5. 클릭 직전 화면과 대상 재확인 | 유지 | 서비스의 `pressAndConfirm`(앱·팝업·대상 재확인), `SimpleTreeCollector.targetMatches`, 자동 닫기는 `PrePressRecheck`로 재판단 | `OverlayExecutionRulesTest`, `F31RegressionTest` | B, A(수집) |
| 6. 실패 후 재시도·좌표 탭 안 함 | 유지 | `clickOnce`(`ACTION_CLICK` 한 번), `autoFailed`, `failedButtons`, `leftAppPaused`. 저장소 전체에 `dispatchGesture`·`GestureDescription` 없음 | `F20RegressionTest.theFailurePromptSaysSoAndDropsOnlyTheFailedButton`, `F34HandlingTest` | B |
| 7. 사용자 조작·화면 변경 시 오래된 분석으로 실행 안 함 | 일부 | 누르기 직전 다시 읽기(`prePressStop`), `checkPromptedAppStillShown`, `promptGone` | `OverlayExecutionRulesTest`, `F31RegressionTest`, `F28RegressionTest` | A, B |
| 8. 민감 화면의 캡처·분석·저장 제한 | 유지 | 민감 앱은 수집 전 차단(`SimpleAppAllowance`). 허용된 앱 안의 민감 화면은 제한된 트리를 읽은 뒤 판정(`SensitiveScreen.reason`, `BrowserSensitivity`, `isPassword`). 서비스 설정에 스크린샷 권한 없음. 기억은 `RememberedPopup.keyOf` SHA-256 | `BrowserSensitivityTest`, `F28RegressionTest`, `F22RegressionTest.aRuleKeepsNoTextAndSurvivesEncoding` | A, B |
| 9. 스크린샷·개인정보를 로그에 안 남김 | 유지 | `DebugSimpleLog`(코드·숫자만, 디버그 빌드에서만), `WindowBoundsRules.line`, `LastClosedContent`(메모리만), 매니페스트에서 `INTERNET` 제거 | `F41Test.keptUntilForgottenAndNeverPrinted`, `F22RegressionTest.aRuleKeepsNoTextAndSurvivesEncoding` | A, C |
| 10. TalkBack 탐색과 안내를 불필요하게 방해 안 함 | 시연 완화 | `SimpleCloseEngineFeature`의 `FOCUS_*`·`RESULT_BANNER_TAKES_FOCUS`, `TalkBackResultBanner`, `PopupMemory`, `TalkBackTakeover`, `CardFocusRules`, `ResultCardClock.restartOnRead`, `PromptFocusRules`, `ReclaimDeferral`. 사용자가 터치하면 초점 되찾기 중단. 선택 창 포커스는 사용자 설정(`talkBackTakeover`, 기본 켬)이고, 결과 카드와 되찾기는 상수입니다 | `PopupMemoryTest`, `TalkBackTakeoverTest`, `F42Test`, `F44Test`, `F45Test`, `F46Test`, `F47Test`. 실제 TalkBack 동작은 JVM으로 시험할 수 없음 | C, A |

## 시연 완화로 켜 둔 스위치

| 스위치 | 위치 | 켜면 달라지는 점 |
|---|---|---|
| `STRONG_CLOSE_ALL` | `SimpleCloseEngineFeature` | "전부 자동 닫기"에서 판단 불가 팝업도 조건을 만족하면 닫음 |
| `DemoUnknownAutoClose.ENABLED` | `SimpleCloseDecider.kt` | 위 강력 모드의 전제. 끄면 판단 불가 팝업은 물어봄 |
| `PROMO_PURCHASE` | `DecisionDemoRules` | 홍보 구매 버튼이 있어도 닫기를 누름. 구매 버튼은 누르지 않음 |
| `PROMO_LOGIN` | `DecisionDemoRules` | 홍보 로그인 버튼이 있어도 닫기를 누름. 로그인 버튼은 누르지 않음 |
| `IMAGE_DIALOG_AD_HINT` | `SimpleCloseEngineFeature` | 판단 불가인 큰 이미지 팝업을 광고로 봄 |
| `WORD_KIND_HINT` | `SimpleCloseEngineFeature` | 판단 불가 팝업을 B의 넓은 낱말 목록으로 쿠폰·광고·공지로 봄 |
| `demoLabelRulesV2`, `demoLabelRulesV3` | `SimpleCloseEngineFeature` | "○○ 닫기", "웹으로 볼래요" 같은 말도 닫기로 봄 |
| `TalkBackTakeoverFeature.DEFAULT_ON` | `TalkBackTakeover.kt` | 선택 창이 뜨면 TalkBack 포커스를 가져옴(설정으로 끌 수 있음) |
| `RESULT_BANNER_TAKES_FOCUS`, `FOCUS_RECLAIM_AFTER_CLOSE`, `FOCUS_PAGE_AFTER_CLOSE`, `FOCUS_PAGE_BEFORE_CLICK` | `SimpleCloseEngineFeature` | 닫기 전후로 TalkBack 포커스를 옮기고 지킴 |

## 전부 자동 닫기의 실제 조건

`STRONG_CLOSE_ALL`이 켜져 있으면 `SimpleCloseDecider.decide`가 강력 모드 분기에서 먼저 끝납니다. 그래서 실제 조건은 아래와 같습니다.

1. 프리셋이 "전부 자동 닫기"이고 자동 닫기 스위치가 켜져 있음
2. 사기 의심과 결정이 아님
3. 위험 버튼이 없음(홍보 구매·로그인 완화 대상은 제외하고 셈)
4. 이름 있는 닫기가 **하나 이상** 있음. 여러 개면 `SimpleLabelRules.clarity`로 가장 분명한 것을 고름
5. 뒤이어 실패 이력, 앱 이탈 이력, 깊이 초과, 설치 광고의 단독 닫기, 연속 제한(`ChainGuard`)에 걸리지 않음
6. 누르기 직전 재확인(`PrePressRecheck`)을 통과함

## 확인이 필요한 점

1. **판단 불가 완화를 6-0에 적는 일은 PR #75에서 진행 중입니다.**
   - 6-2는 판단 불가 팝업을 어떤 프리셋에서도 제외하고, 지금 6-0은 이 완화를 이름으로 말하지 않습니다.
   - #75가 병합되면 이 항목은 지웁니다.
2. **`DemoUnknownAutoClose` 주석이 실제 동작과 다릅니다.**
   - 주석은 "확인된 버튼이 이름 있는 닫기 하나뿐"이라고 하지만, 이 조건은 `STRONG_CLOSE_ALL`이 꺼졌을 때의 경로입니다.
   - 지금은 "자세히 보기"와 "닫기"가 함께 있어도 닫습니다.
   - 주석의 "AGENTS 6-2 is not changed"도 함께 볼 부분입니다.
3. **규칙 7의 "사용자 조작"은 일부만 코드로 지킵니다.**
   - 터치는 초점 되찾기만 멈추고, 진행 중인 자동 누르기를 취소하지는 않습니다.
   - 누르기 직전에 화면을 다시 읽어서 화면이 바뀐 경우는 막습니다. 화면을 바꾸지 않은 터치는 막지 않습니다.
4. **규칙 8은 "안 읽음"이 아닙니다.**
   - 민감 앱은 수집 전에 막습니다. 허용된 앱 안의 민감 화면은 알기 위해 먼저 제한된 트리를 읽습니다.
   - 정확한 표현은 "읽은 뒤 민감 화면이면 판단·누르기·안내를 하지 않고, 코드만 기록한다"입니다.
   - 서비스 설정에 스크린샷 권한이 없어 이 엔진은 캡처하지 않습니다. A 엔진의 캡처 경로(`accessibility/capture`)는 우진의 확인이 필요합니다.
5. **규칙 9를 고정하는 시험이 넓지 않습니다.** `DebugSimpleLog`의 `line(codes: String)`은 문자열을 받으므로, 코드 값만 들어간다는 것을 컴파일러가 막아 주지 않습니다. 지금 호출하는 곳은 창 줄(`WindowBoundsRules.line`)과 선택 창 초점 줄(`PromptFocusRules`) 두 곳이고, 코드와 숫자만 만듭니다.
6. **사기 의심의 선택 창:** 6-0은 "누르지 않고 경고만"이라고 합니다. 코드는 경고를 선택 창 첫 줄로 말하고 [닫기 버튼으로 이동]을 제시하지만 대신 누르지 않습니다. 규칙에 맞다고 봤는데 다른 의견이 있으면 알려 주세요.

## 함께 정할 것

- 제출 빌드에서 완화 스위치를 끄는 방안은 시연한 코드와 제출한 코드가 달라지는 문제가 있습니다. B는 6-0에 조건을 정확히 적고 스위치를 그대로 두는 쪽을 생각했습니다. 팀 의견이 필요합니다.
- 위 1번을 6-0에 적을 때 "최종 발표 뒤 배포하면 끈다"를 스위치 이름과 함께 적을지 정해야 합니다.
