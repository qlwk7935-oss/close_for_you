# B 위협 모델 — 분류·안전 판단·닫기 후보

작성: B 준영 · 2026-10-19 (PR #13 리뷰 반영 2026-10-20, 프리셋 자동 닫기 반영 2026-10-25) · 기준 코드: `main` `e38aac5` (#37·#38·#44·#45·#46·#48·#50·C #51 포함)

이 문서는 B(`decision` 패키지)가 막아야 하는 위협과, 각 위협을 **어떤 코드 규칙과 어떤 단위 시험으로 막는지**를 정리합니다. 근거는 [AGENTS.md](../../../../../../../AGENTS.md)의 필수 안전 규칙, 통합 설계 v5(`docs/통합설계.md`), 검증 실험 계획(`docs/검증 실험 계획.md`), 그리고 프리셋 자동 닫기 설계([AUTO_CLOSE.md](AUTO_CLOSE.md), 계약 v2 #38)입니다.

> **이 문서는 JVM 단위 시험과 API 37 에뮬레이터 덤프 기준입니다.** 실기기·TalkBack에서 검증하지 않았습니다. main의 실제 서비스는 아직 B 정책을 부르지 않고(A 조립은 `transfer/…` 브랜치), 클릭 실행(F9 #42)도 병합 전이라 **지금 앱은 아무것도 누르지 않습니다.**
>
> **AGENTS.md 6장 2번**("광고처럼 보인다는 이유만으로 처음 보는 팝업을 자동 클릭하지 않는다")은 아직 옛 문구입니다. 프리셋 자동 닫기는 "광고처럼 보인다"만이 아니라 사용자가 고른 프리셋 + A 적격 근거 + B의 18개 조건을 모두 요구하지만, 문구 개정은 팀 결정으로 남아 있습니다(T9).

## 1. 지키는 것

| 자산 | 잃으면 생기는 일 |
|---|---|
| 사용자의 결정권 | 결제·동의·구독·설치·삭제를 사용자 대신 누름. **복구할 수 없음** (설계 1-2-7) |
| 사용자의 상황 인지 | 무엇이 닫혔는지 모름, 중요한 공지를 놓침 (설계 F10) |
| 화면의 개인정보 | 화면 글자가 로그·예외·DB로 새어 나감 (AGENTS 6-8·6-9) |
| TalkBack 탐색 | 사용자 조작 중 끼어들어 포커스·흐름을 깨뜨림 (AGENTS 6-10) |

## 2. 신뢰 경계

```text
다른 앱 화면 ──(A: F0 보호, 노드 DTO 복사, AutoEligibility)──> B 분류·후보·판단 ──(ActionDecision)──> A 게이트·F9 ──> 실제 클릭(#42, 병합 전)
                                               ↑ UserChoice (C)   ↑ autoCloseKinds 프리셋 (C)   ↑ PopupRule (D)
                                               └──────── ActionResult·ClickOutcome (A → B onActionResult) ────────┘
```

- **프리셋 자동 닫기는 사람의 그때그때 선택 없이 B가 승인하는 유일한 경로**라서 아래 T17~T25를 따로 둡니다.

- **다른 앱은 믿지 않습니다.** 라벨·글자·버튼 구성은 광고주나 악성 앱이 마음대로 꾸밀 수 있습니다.
- **다른 팀 모듈의 값도 그대로 믿지 않습니다.** B는 전달받은 후보의 역할을 라벨로 다시 확인합니다. A 게이트도 B의 요청을 다시 검증합니다.
- **B의 `ALLOW_ONCE`는 실행 허가가 아니라 요청**입니다. 최종 판단은 A 게이트가 실제 화면을 다시 읽어서 합니다(PR #10).

## 3. 위협 목록

표기: **막음**(B 규칙으로 차단) · **줄임**(B가 줄이고 다른 담당이 함께 막음) · **남음**(B만으로는 못 막음)

### T1. 가짜 닫기 버튼 — 설치·결제·이동 버튼을 닫기처럼 꾸밈

- **예:** "앱 설치하고 닫기", "Close and install", 라벨은 "닫기"인데 role=CLOSE로 위조된 "닫기 전 저장"
- **근거:** AGENTS 6-1, 설계 3-1 D·P 등급, 검증 계획 A05("X인데 실제로 광고 이동")
- **B 대응:** 라벨 **전체**가 정해진 닫기/건너뛰기 표현과 정확히 같아야 하고, 위험 단어가 하나라도 있으면 거부합니다. 전달받은 역할을 믿지 않고 라벨로 다시 판정합니다.
- **시험:** `closeAndSkipLabelsMatchAsWholeLabels`, `lookalikeButtonsAreNotCandidates`, `fakeCloseButtonsAreNotPressed`, `textAndDescriptionMustAgree`
- **상태: 줄임.** 라벨이 정확히 "닫기"인데 실제로는 설치하는 버튼은 라벨만으로 구별할 수 없습니다(**남음**, 7절).

### T2. 다크패턴 문구 — 의미가 바뀌는 닫기·모호한 확인

- **예:** "오늘 하루 닫기", "7일간 보지 않기", "다시 보지 않기", "확인", "OK", "나중에"
- **근거:** 설계 3-1 K·O 등급
- **B 대응:** K·O 등급 단어를 위험 단어로 거부합니다. 확인/OK는 기억 규칙이 있어도 누르지 않습니다.
- **시험:** `lookalikeButtonsAreNotCandidates`, `fakeCloseButtonsAreNotPressed`
- **상태: 막음**

### T3. 결정 화면 위장 — 결제·동의 화면에 광고 문구를 섞음

- **예:** "광고 · 가상 상품 구매 안내", A가 보낸 고정 요약 "결제 선택 필요", "무료 체험 시작" 버튼
- **근거:** AGENTS 6-1, 설계 F7 "결정 필요 신호 우선", 설계 1-2-10
- **B 대응:** 분류기는 결정 신호를 **가장 먼저** 봅니다. 안전 판단은 분류 결과와 별도로 본문·라벨·역할의 결정 신호를 다시 확인하고, 기억 규칙이나 사용자 명령이 있어도 거부합니다.
- **시험:** `decisionWordingWinsOverAdvertisementAndNoticeWording`, `fixedDecisionSummariesFromAAreUserDecisions`, `decisionButtonLabelOrRoleMakesAUserDecision`, `importantDecisionsAreDeniedEvenWithARuleOrACommand`
- **상태: 막음** (A의 F0-A가 결정 원문 대신 고정 요약만 보냄)

### T4. 뒤 화면 버튼 오인 — 같은 창 덮개 팝업

- **예:** 같은 창에 그려진 팝업 뒤에 있는 페이지의 "닫기"(메뉴 닫기 등)
- **근거:** 설계 F1·F3, PR #8 리뷰 1번
- **B 대응:** 같은 창 덮개는 가장 바깥 Dialog 노드가 **하나일 때만**, 그 **하위 노드**이면서 영역 안에 있는 버튼만 후보로 봅니다. 그 외에는 후보를 만들지 않습니다.
- **시험:** `sameWindowOverlayIsLimitedToOneDialog`, `pageButtonUnderTheDialogBoundsIsNotTheDialogs`, `dialogClassesNestedInOneDialogStillCount`
- **상태: 막음.** 대신 Dialog 노드가 없는 웹 모달 등은 찾지 못합니다(잘못 거부 쪽).

### T5. 일반 화면 이동을 팝업으로 오인

- **예:** 평범한 Activity 전환 화면의 "닫기"
- **근거:** 설계 F1 "발견 결과는 후보일 뿐", PR #8 리뷰 3번
- **B 대응:** 전체 화면 전환과 `INSUFFICIENT_EVIDENCE`가 붙은 감지에서는 후보를 만들지 않습니다.
- **시험:** `uncertainDetectionsGiveNothing`, `onlyANewWindowIsSearchedWhole`, `staleDetectionMissingSignalsOrNoNodesGiveNothing`
- **상태: 막음**

### T6. 오래된 분석으로 실행 — 화면이 바뀐 뒤 누름

- **예:** 분석 후 화면이 바뀌었는데 이전 결정으로 누름, 이전 화면에서 고른 선택을 새 화면에 적용
- **근거:** AGENTS 6-5·6-7, 설계 2-3
- **B 대응:** 현재 토큰과 분석 토큰, 사용자 선택의 토큰이 모두 같아야 합니다. 다르면 거부합니다.
- **시험:** `staleScreensAndOldChoicesAreDenied`
- **상태: 줄임.** 토큰 비교는 추가 검사일 뿐입니다. 실제 화면 재검사와 TTL은 A 게이트 담당입니다(TEAM_INTEGRATION).

### T7. 사용자 조작 중 끼어들기

- **근거:** AGENTS 6-7·6-10, 설계 F11
- **B 대응:** 사용자 조작 중이면 거부합니다.
- **시험:** `personInteractingIsDenied`
- **상태: 줄임.** "조작 중" 판단은 A·C가 제공합니다.

### T8. 라벨 없는 X·아이콘·픽셀 버튼을 모양만 보고 누름

- **예:** 단독 "X", "×", 라벨 없는 `DISMISS`, 아이콘(E3), 픽셀로만 찾은 X(E4)
- **근거:** AGENTS 6-3, 설계 3-2, PR #11 이후 우진 결정
- **B 대응:** 단독 X 계열과 라벨 없는 `DISMISS`는 후보로도 내지 않고, 안전 판단에서도 거부합니다. E3·E4는 누르지 않습니다.
- **시험:** `lookalikeButtonsAreNotCandidates`, `labeledDismissActionIsE1Evidence`, `onlyALabeledDismissActionIsPressed`, `pixelOnlyAndIconButtonsAreNotPressed`, `nodesThatCannotBePressedAreNotCandidates`
- **상태: 막음**

### T9. 처음 보는 팝업 자동 클릭

- **예:** 광고처럼 보이는 처음 보는 팝업을 자동으로 닫음
- **근거:** AGENTS 6-2, 설계 3-3-4, 계약 v2(#38)·재호 AGENTS 6장 개정 초안
- **B 대응:** 프리셋이 없으면(기본) 처음 본 팝업은 항상 사용자에게 묻습니다(`FIRST_SEEN`). 분류가 광고여도 허가 근거로 쓰지 않습니다. 사용자가 설정에서 프리셋을 고른 경우에만, 그 프리셋에 든 종류(쿠폰·광고·공지)이고 A 적격 근거와 [AUTO_CLOSE.md](AUTO_CLOSE.md) 4절의 18개 조건을 모두 만족할 때 1회 자동 승인합니다. 결정·사기 의심·모름은 어떤 프리셋에서도 닫지 않습니다. 세부 위협은 T17~T25.
- **시험:** `firstSeenPopupWithAVerifiedCloseButtonAsksThePerson`, `firstSeenWithSeveralVerifiedButtonsAsksWithoutPickingOne`, `foundCandidatesFeedTheSafetyPolicy`(후보 탐색 → 안전 판단을 이어서 확인), `withoutAPresetEverythingIsAsBefore`, `presetOffClosesNothing`, `eachPresetClosesOnlyItsKinds`, `thePresetClosesAnEligibleFirstSeenAd`
- **상태: 막음 (프리셋 없음).** 프리셋이 켜진 경우는 T17~T25로 줄입니다. AGENTS 6-2 문구 개정은 팀 결정 대기입니다.

### T10. 기억 규칙 악용·오래된 허락 재사용

- **예:** 날짜가 바뀐 공지에 예전 "항상 닫기"를 적용, "묻지 마"를 자동 닫기로 해석
- **근거:** AGENTS 6-4, 설계 F12 "'묻지 마'는 자동 닫기와 다르다"
- **B 대응:** 기억 규칙의 **자동 반복은 꺼져 있습니다**(항상 사용자 확인). 반복 허락 계약이 아직 없고, A가 모든 분석에 `INCOMPLETE_EVIDENCE`를 붙이기 때문입니다. 프리셋 자동 닫기도 같은 팝업은 세션에 한 번뿐이라(`CLOSED_BEFORE`) 반복 수단이 되지 않습니다. `STOP_ASKING`은 닫지도 다시 묻지도 않습니다. 이번 화면에서 고른 선택이 기억 규칙보다 우선합니다. 기억 규칙으로 누른 결과가 성공 3조건(`attempted` + `MECHANICAL_SUCCESS` + `CLOSED_CONFIRMED`)이 아니면 D의 `recordFailure`로 규칙을 끕니다(#48).
- **시험:** `automaticRepeatStaysOffUntilTheContractCarriesItsConditions`, `stopAskingRuleNeitherClosesNorAsksAgain`, `personsCurrentChoiceWinsOverARememberedRule`, `ruleWithAnAmbiguousOrUnverifiedTargetIsDenied`, `DecisionRulePressOutcomeTest`의 `onlyAConfirmedCloseIsASuccess`·`onlyFailuresAreRecorded`
- **상태: 막음** (정확 일치 규칙 조회는 D 담당)

### T11. 사용자 선택·명령의 오해석

- **예:** 의미를 확인하지 않은 탭, 건너뛰기를 닫기로 처리, "기억하기"를 지금 누르기로 처리, 음성 "닫아"가 동의 버튼을 누름
- **근거:** 설계 3-5, 설계 1-2-10, A 게이트(PR #10)
- **B 대응:** `CLOSE_ONCE`/`SKIP_ONCE`만 누르기 요청이고, 역할이 맞아야 합니다. `buttonMeaningConfirmed`가 없으면 다시 묻습니다. 결정 신호가 있으면 명령이 있어도 거부합니다.
- **시험:** `unconfirmedTapAsksAgainAboutTheSameButton`, `skipCommandNeedsASkipButton`, `choicesThatDoNotPressAnythingAreDenied`, `personsCommandOnAVerifiedButtonAllowsOnce`, `labelIsKeptVerbatimForTheGate`(B가 허용한 버튼을 A 게이트가 몰래 거부하지 않도록 라벨 원문 유지)
- **상태: 막음** (음성 인식과 명령 매칭은 C 담당)

### T12. 이미지·OCR·영상 근거로 잘못 판단

- **예:** 이미지의 "AD" 글자만 보고 광고로 확정, 영상 창을 광고로 단정, OCR 글자로 버튼 후보를 만듦
- **근거:** TEAM_INTEGRATION "OCR 본문만으로 광고·안전한 버튼·자동 허가를 판단하지 않습니다", 설계 F5
- **B 대응:** 광고·쿠폰 판정은 접근성 트리 글자에서만 합니다. 쿠폰·광고 단어가 OCR에만 있으면 모름입니다. 영상 표면만으로는 광고가 아닙니다. 후보는 트리 노드에서만 만듭니다. A가 모든 분석에 붙이는 `INCOMPLETE_EVIDENCE`가 있으면 기억 규칙 자동 반복을 막습니다. 프리셋 자동 닫기는 닫을 이유가 트리 글자만으로도 나와야 하고(`NOT_TREE_EVIDENCE`), 반대로 OCR에 보인 결정·사기 단어는 그대로 막는 근거로 씁니다.
- **시험:** `ocrOnlyAdvertisementWordingIsNeitherAnAdvertisementNorANotice`, `videoSurfaceAloneIsNotAnAdvertisement`, `realRuntimeLimitationsStillShowAdvertisementsAndNotices`, `realRuntimeAnalysisWithoutCandidatesIsNeverPressed`, `aNoticeReadOnlyByOcrIsToldButNotClosed`, `scamWordsInOcrAlsoStopIt`
- **상태: 막음**

### T13. 실패 후 재시도·좌표 탭 대체

- **근거:** AGENTS 6-6, 설계 F9 "자동 좌표 재시도 금지"
- **B 대응:** B에는 재시도 경로가 없습니다. 이전 실패가 있는 규칙은 자동 경로에서 사용자 확인으로 바뀝니다(`ALREADY_ATTEMPTED`). 프리셋 경로는 B의 세션 기록(메모리, 키 = 패키지 + A 구조 지문)으로 실패·결과 불명·앱 이탈 뒤 같은 팝업을 다시 승인하지 않습니다(T21).
- **시험:** `automaticRepeatStaysOffUntilTheContractCarriesItsConditions`, `aFailedOrUnknownPressIsNeverRetried`, `aDeadCloseIsPressedOnlyOnce`
- **상태: 줄임.** B 기록은 B가 승인한 것만 압니다. 실제 실행 1회·좌표 탭 금지의 최종 보장은 A 게이트·F9의 장부입니다.

### T14. 위장 문자로 라벨 검사 우회

- **예:** 키릴 문자로 쓴 "сlose", 폭 없는 공백이 든 "닫​기"
- **근거:** 공용 라벨 계약 초안 v1~v3 (v3에서 A Q8 승인: NFKC 후 정확 일치만 허용, 키릴 문자·U+200B·U+FEFF는 거부)
- **B 대응:** 정확 일치라서 일치하지 않고 거부됩니다. 보이지 않는 문자를 지우지 않습니다.
- **시험:** 공용 라벨 계약 벡터 176개 중 R8 범주 7개와 정규화 표(`ButtonLabelRulesTest`, `button_label_vectors.csv`·`button_label_normalization.csv`, #14)
- **상태: 막음.** 분류 쪽 위장(결정 단어 숨기기)은 T18

### T15. 개인정보 유출

- **예:** 화면 글자를 로그·예외 메시지·`toString`으로 출력
- **근거:** AGENTS 6-9, TEAM_INTEGRATION
- **B 대응:** `decision` 코드에는 로그·파일·네트워크·Android 객체가 없습니다. 공용 DTO의 `toString`은 원문을 가립니다.
- **확인:** 정적 점검(로그·`println`·파일·네트워크·`import android.` 0건)
- **상태: 막음.** 정적 점검을 자동 시험으로 만드는 일은 남아 있습니다(8절).

### T16. 잘못된 분류로 인한 잘못된 안내

- **예:** 광고 본문의 "구매" 때문에 "결정 필요"로 안내, 불확실한 문구를 단정
- **근거:** 설계 F7 "'판단 불가'를 정상 결과로 허용", PR #8 리뷰 6번
- **B 대응:** 애매하면 `UNKNOWN`으로 둡니다. 표시 분류는 실행 허가와 분리돼 있습니다. C는 "광고로 보이는 팝업이에요"처럼 단정하지 않게 안내합니다.
- **시험:** `fixturesMatchTheirExpectedDisplayClassification`, `fixturesYieldOnlyTheLabeledCloseButton`, `onlyWordingThatDoubtsItselfIsUncertain`, `blankOrMissingTextIsUnknown`, `installAdvertisementIsNotADecision`, `englishWordsUseWordBoundaries`("Download"의 ad를 광고로 보지 않음), `noDecisionIsShownAsAnAdvertisementOrANotice`, `confusionMatrixMatchesTheRecordedBaseline`
- **상태: 줄임.** 합성 82개 평가([평가 보고서](CLASSIFIER_EVAL.md) 12절)는 여섯 종류 적용 뒤 73/82이고, 따로 만든 held-out 80개([AUTO_CLOSE.md](AUTO_CLOSE.md) 6절)는 73/80입니다. 두 평가 모두 결정·사기 화면을 닫을 수 있는 종류로 안내한 위험 오류는 0건입니다. **B가 정답을 붙인 합성 문구의 JVM 평가**라서 실제 앱 정확도가 아닙니다. 정답 교차 검토(D)와 실제 앱 수집(검증 계획 A03)이 남아 있습니다.

## 3-2. 프리셋 자동 닫기 위협 (T17~T25)

프리셋 자동 닫기([AUTO_CLOSE.md](AUTO_CLOSE.md))는 사용자가 설정에서 "광고만 닫기" 같은 프리셋을 고른 뒤, **처음 본 팝업을 그때 묻지 않고** 닫는 경로입니다. 잘못 허용하면 사용자가 알아차리기 전에 눌린다는 점이 T1~T16과 다릅니다. 그래서 B는 위험 신호는 넓게, 닫을 이유는 좁게, 누를 버튼은 하나만, 한 팝업은 세션에 한 번만 봅니다. 아래 시험은 별도 표기가 없으면 `DecisionAutoCloseTest`입니다.

### T17. 사기·낚시 팝업을 광고로 보고 자동 닫기

- **예:** "축하합니다! 1등 당첨, 경품 수령" + 닫기, "휴대폰이 바이러스에 감염되었습니다 · 즉시 치료" + 닫기, OCR로만 보이는 "you won"
- **근거:** AGENTS 6-1·6-2, 설계 F7 (사기성 팝업을 광고와 구분), 재호 개정 초안
- **B 대응:** 사기 의심(SUSPICIOUS)을 여섯 종류 중 **가장 먼저** 봅니다. 트리·OCR·팝업 밖 어디에서든 보이면 어떤 프리셋에서도 닫지 않고 DENY + `SUSPICIOUS_CONTENT`로 경고 안내만 합니다. 사용자가 직접 골라도 B는 누르기를 요청하지 않습니다.
- **시험:** `decisionScamAndUnknownAreNeverClosedAndNeverWaited`, `scamWordsInOcrAlsoStopIt`, `reviewScamProbesAreSuspicious`, `DecisionActionPolicyPresetTest.decisionsAndScamsAreOnlyExplainedWhoeverAsks`, `DecisionKindEvaluationTest.noDecisionOrScamIsEverAClosableKind`
- **상태: 줄임.** 사전에 없는 새 사기 문구는 쿠폰·광고로 보일 수 있습니다. 이때도 버튼 조건(T23)과 앱 이탈 정지(T21)가 남은 방어입니다.

### T18. 결정 단어를 숨겨서 자동 닫기 대상이 되게 함

- **예:** "구 독", "동의ㅤ하기"(한글 채움 문자 U+3164), "결​제"(폭 없는 공백), 전각 "ＳＩＧＮ ＵＰ", "sign up하기"(영어 뒤에 한글이 바로 붙음), "주문하시겠어요?", "자동 갱신됩니다"
- **근거:** AGENTS 6-1, 독립 리뷰 1·2차(우회 사례), #37
- **B 대응:** 결정·사기·불확실 신호는 원문, NFKC + 소문자 + 보이지 않는 문자·한글 채움 문자 제거, 띄어쓰기 제거 세 가지 형태 모두에서 찾습니다. 행동 요청("~하기/하세요/하시겠")·공손한 질문·정기결제도 결정입니다. 영어 경계는 ASCII(`[a-z0-9_]`)라 Android ICU에서도 한글 옆 영어를 잡습니다.
- **시험:** `reviewDecisionProbesAreDecisions`, `reviewRound2StillRefused`, `DecisionKindEvaluationTest.overWarningStudyDecisionsStayNonClosable`, `DecisionEnglishBoundaryTest`, androidTest `DecisionKindInstrumentedTest`·`DecisionEnglishBoundaryInstrumentedTest`(API 37 에뮬레이터에서 직접 실행. **CI는 컴파일만 함**)
- **상태: 줄임.** 사전 기반이라 사전 밖 표현은 남습니다. 대부분은 A가 결정 화면을 고정 요약으로 바꿔서 B에 오기 전에 결정이 됩니다(T3).

### T19. 계정 공지를 쿠폰·광고로 보고 닫음

- **예:** "보유 쿠폰이 내일 만료됩니다", "포인트 소멸 예정 안내", "보안 정책 변경 안내", "할인 적용 오류 안내"
- **근거:** 설계 F10 (중요한 공지를 놓치지 않음), 재호 개정 초안 (공지는 프리셋 ①에서만 닫음)
- **B 대응:** 쿠폰·광고 단어가 있어도 서비스·계정 공지어(만료·소멸·환불·보안·정책·약관·오류·점검…)가 있으면 공지입니다. 공지는 프리셋 ①에서만 닫고 ②③에서는 안내만 합니다. 공지 단어가 OCR에만 있으면 닫지 않고 안내합니다.
- **시험:** `reviewNoticeProbesAreNotices`, `eachPresetClosesOnlyItsKinds`, `aNoticeReadOnlyByOcrIsToldButNotClosed`
- **상태: 줄임.** 프리셋 ①(전부 닫기)을 고른 사용자에게는 공지도 닫힙니다. 무엇을 닫았는지 알리는 일은 T24입니다.

### T20. 뒤 페이지 글자로 팝업 종류를 바꾸거나 경고를 지움

- **예:** 공지 팝업 뒤 페이지의 "쿠폰 모음"·"오늘의 특가" 때문에 팝업이 쿠폰으로 분류됨. 반대로 범위를 좁히다가 페이지의 "결제 선택 필요"를 빼 버림
- **근거:** PR #8 리뷰 1번(같은 창 덮개), AUTO_CLOSE.md 5절
- **B 대응:** 같은 창 덮개는 Dialog 노드가 하나일 때 그 하위 노드가 **같은 위치에 같은 글자로** 가진 글자만 팝업 글자로 봅니다. 결정·사기·불확실 신호는 팝업 밖에 있어도 절대 빼지 않습니다. 입구 함수가 범위를 먼저 적용하고, 스냅샷·감지·분석 토큰이 섞이면 거부합니다.
- **시험:** `DecisionPopupScopeTest`의 `overlayKeepsPopupTextsAndPageWarningsOnly`·`everyPageWarningKindSurvives`·`aPopupStringRepeatedOnThePageIsKeptOnlyWherePopupNodesCarryIt`, `theEntryPointScopesFirstAndRejectsMixedTokens`
- **상태: 막음.** Dialog 노드가 없는 덮개는 범위를 정하지 못해 후보도 없습니다(잘못 거부 쪽).

### T21. 가짜 닫기·죽은 닫기·광고 순환으로 반복해서 누르게 함

- **예:** "닫기"를 누르면 광고 앱·스토어로 이동, 눌러도 안 닫히는 닫기, 닫자마자 같은 광고가 다시 뜸, 광고가 계속 바뀌며 뜸
- **근거:** AGENTS 6-6, 검증 계획 A05("X인데 실제로 광고 이동"), 우진 조건(세션 1회)
- **B 대응:** B 세션 기록(메모리, 화면 글자 없음)으로 막습니다.
  - 같은 팝업은 결과가 올 때까지 다시 승인하지 않습니다. 30초 안에 결과가 없으면 결과 불명으로 봅니다.
  - 성공 3조건이 아닌 결과(그대로 있음, 결과 모름, 실패했는데 사라짐) 뒤에는 그 팝업을 세션 끝까지 자동 금지합니다.
  - 성공했어도 같은 팝업은 세션에 한 번만 닫습니다.
  - 누른 뒤 앱 밖으로 나가면(`LEFT_APP`) 그 앱 전체를 세션 동안 자동 금지합니다. 사용자가 직접 고른 누르기의 결과여도 적용합니다.
  - 같은 앱에서 실제로 누른 자동 닫기는 1분에 3회까지입니다.
- **시험:** `onePopupIsSentOnceUntilItsResultComesBack`, `aFailedOrUnknownPressIsNeverRetried`, `aDeadCloseIsPressedOnlyOnce`, `aPressThatLeftTheAppStopsThatAppOnly`, `resultsForPopupsNeverApprovedAreIgnoredButLeavingTheAppIsNot`, `atMostThreeAutomaticClosesPerAppPerMinute`, `anApprovalWithoutAResultEventuallyCountsAsUnknown`, `aPopupThatVanishedAfterAFailedPressIsAFailure`, `DecisionActionPolicyPresetTest.resultsFromAShapeTheNextDecision`
- **상태: 줄임.** **처음 1번의 가짜 닫기는 막지 못합니다**(라벨이 정확히 "닫기"면 누르기 전에는 구별 불가, 7절 1번). A가 `onActionResult`로 결과를 넘기지 않으면 기록이 맞지 않으므로 연결 조건입니다([연결 안내](INTEGRATION.md) 4절).

### T22. 오래된·다른 화면·모의 데이터로 자동 닫기

- **예:** 5초 넘게 지난 화면의 적격 근거, 늦게 발급돼 기한이 늘어난 근거, 다른 화면의 근거가 붙은 분석, Mock 분석
- **근거:** AGENTS 6-5·6-7, #38 우진 리뷰(기한은 수집 시각부터)
- **B 대응:** A의 `AutoEligibility`에 5개 검사가 모두 있고, 같은 토큰이고, **원본 화면 수집 시각**부터 5초 안이어야 합니다. B 배선은 근거의 수집 시각이 그 스냅샷과 다르면 버립니다. 토큰이 현재 화면과 다르거나 사용자가 조작 중이면 대기합니다. `MOCK_ONLY` 분석은 닫지 않습니다.
- **시험:** `noCompleteCurrentEligibilityNoAutomaticClose`, `staleScreenUserActionSilenceAndOtherPathsBlock`, `mockDataIsNeverClosed`, `DecisionCandidateWiringTest.asEligibilityIsKeptOnlyForThisScreensOwnCaptureTime`, `AutoCloseContractTest`의 `eligibilityNeedsEveryCheckThisTokenAndFiveSeconds`·`lateIssuedEvidenceNeverOutlivesTheCapture`, `DecisionActionPolicyPresetTest.withoutAsEligibilityItAsksAsToday`
- **상태: 줄임.** 검사 내용의 정확성은 A 담당이고, 누르기 직전 재확인은 F9가 합니다. **정책의 시계가 A와 같아야 합니다**(`DecisionActionPolicy { SystemClock.uptimeMillis() }`). 잘못 조립하면 B가 알 수 없습니다(7절 5번).

### T23. 여러 버튼 중 잘못 고르거나, 건너뛰기를 대신 누름

- **예:** 닫기 버튼이 둘(하나는 가짜), "자세히 보기"와 "닫기", 카운트다운이 끝나지 않은 "건너뛰기", 라벨 없는 X 옆의 닫기
- **근거:** AGENTS 6-3, A F9가 CLOSE만 누름(10/25)
- **B 대응:** 검증된 E1/E2 CLOSE가 **정확히 하나**일 때만 그 버튼입니다. 둘 이상이면 묻습니다(`AMBIGUOUS_TARGET`). CLOSE와 SKIP이 같이 있으면 CLOSE입니다. 자동 SKIP은 기본 꺼짐이라 건너뛰기만 있는 광고는 묻습니다(#46). 버튼이 아직 없으면 대기합니다.
- **시험:** `onlyOneVerifiedCloseIsPressed`, `unverifiedButtonsAreNeverTargets`, `skipIsAskedWhileAsExecutorCannotPressIt`, `skipOnlyOnAnAdAfterItsCountdown`, `delayedCloseWaitsForAdsAndCouponsButANoticeIsOnlyTold`, `DecisionActionPolicyPresetTest.aSkipOnlyAdIsAskedUntilAPressesSkip`, 에뮬레이터 덤프 `DecisionAutoCloseScenarioTest`(28화면 × 프리셋 3, D 정답지의 금지 버튼 누름 0)
- **상태: 막음.** 에뮬레이터 덤프 시험은 A 적격 근거가 있다고 가정합니다.

### T24. 조용히 닫거나, 닫지 못했는데 "닫았어요"라고 안내

- **예:** TTS를 쓸 수 없는데 자동으로 닫음, 눌렀지만 팝업이 남았는데 닫았다고 안내, 무엇을 닫았는지 모름
- **근거:** 설계 F10, 검증 계획 B04, 팀 성공 기준(10/25)
- **B 대응:** 결과를 안내할 수 없으면 승인하지 않습니다(`FEEDBACK_UNAVAILABLE`). 닫힘은 `attempted` + `MECHANICAL_SUCCESS` + `CLOSED_CONFIRMED`일 때만이고, 처리 보고(`HandlingReport.closedConfirmed`)도 같은 기준입니다. 정책은 `next`(WAIT·ANNOUNCE·ASK·DENY)로 C가 할 안내를 정해 줍니다.
- **시험:** `staleScreenUserActionSilenceAndOtherPathsBlock`, `onlyAConfirmedCloseCountsAsClosed`, `DecisionActionPolicyPresetTest.eachOutcomeTellsCWhatToDo`, `HandlingReportContractTest`의 `onlyAConfirmedCloseCountsAsClosed`·`onlyAutomaticHandlingIsReported`
- **상태: 줄임.** 실제 안내는 C(`SpeechHandlingListener`, #51)가 main에 있지만, 처리 보고를 C에 넘기는 A 조립은 아직 main에 없습니다.

### T25. 팝업 제목으로 개인정보를 소리 내어 읽음

- **예:** 팝업 첫 줄 "홍길동님, 쿠폰이 도착했어요", "010-1234-5678", "abc@example.com"
- **근거:** AGENTS 6-8·6-9, 재호 #45 리뷰
- **B 대응:** 제목은 팝업 범위 안 트리 글자 중 버튼 단어·후보 라벨·경고가 아닌 첫 줄이고, 이름+님·숫자 7개 이상·이메일·웹 주소가 든 줄은 건너뜁니다. 안전한 줄이 없으면 null입니다. 제목은 말로만 쓰고 로그·저장하지 않으며 `toString`은 가립니다. **첫 통합에서는 A가 제목을 넘기지 않고**(팀 결정), C도 제목 낭독이 기본 꺼짐입니다(`HandlingTitleSpeechFeature`, #51).
- **시험:** `DecisionPopupScopeTest`의 `theTitleIsTheFirstSafePopupText`·`aLineThatMayHoldPersonalDataIsNeverTheTitle`, `HandlingReportContractTest.titleAndReportNeverShowScreenText`
- **상태: 줄임.** "님" 없는 이름 한 줄("홍길동")은 구별할 수 없습니다. 대부분은 A의 F0가 화면을 먼저 차단합니다.

## 4. 요약표

| # | 위협 | 상태 | 함께 막는 담당 |
|---|---|---|---|
| T1 | 가짜 닫기 버튼 | 줄임 (정확히 "닫기"인 위장은 남음) | A 게이트 |
| T2 | 다크패턴 문구 | 막음 | — |
| T3 | 결정 화면 위장 | 막음 | A (F0-A 고정 요약) |
| T4 | 뒤 화면 버튼 오인 | 막음 | A (F1) |
| T5 | 일반 화면 이동 오인 | 막음 | A (F1) |
| T6 | 오래된 분석으로 실행 | 줄임 | A 게이트 (재검사·TTL) |
| T7 | 사용자 조작 중 끼어들기 | 줄임 | A·C |
| T8 | 라벨 없는 X·아이콘·픽셀 | 막음 | A 게이트 |
| T9 | 처음 보는 팝업 자동 클릭 | 막음 (프리셋 없음), 프리셋은 T17~T25 | C (프리셋 설정), 팀 (AGENTS 6-2 개정) |
| T10 | 기억 규칙 악용 | 막음 | D (정확 일치) |
| T11 | 선택·명령 오해석 | 막음 | C |
| T12 | 이미지·OCR·영상 오판 | 막음 | A (OCR 경계) |
| T13 | 재시도·좌표 탭 | 줄임 | A 게이트·F9 (실행 장부) |
| T14 | 위장 문자 (라벨) | 막음 | 공용 라벨 계약 |
| T15 | 개인정보 유출 | 막음 | 전원 |
| T16 | 잘못된 안내 | 줄임 (합성 82개 73개, held-out 80개 73개 정답, 위험 오류 0) | C (안내 문장), D (정답 교차 검토) |
| T17 | 사기 팝업 자동 닫기 | 줄임 (사전 밖 문구는 남음) | — |
| T18 | 결정 단어 숨기기 | 줄임 (사전 밖 표현은 남음) | A (결정 고정 요약) |
| T19 | 계정 공지를 쿠폰·광고로 | 줄임 (프리셋 ①은 공지도 닫음) | C (프리셋 설명) |
| T20 | 뒤 페이지 글자 | 막음 | A (F1 범위) |
| T21 | 가짜·죽은 닫기, 광고 순환 | 줄임 (첫 1회 가짜 닫기는 남음) | A (결과 전달, `LEFT_APP` 관측) |
| T22 | 오래된·다른 화면·Mock | 줄임 | A (적격 근거, F9 재확인), 조립 (시계) |
| T23 | 버튼 잘못 고름·건너뛰기 | 막음 | A F9 (CLOSE만) |
| T24 | 조용한 닫기·거짓 "닫았어요" | 줄임 | C (안내), A (처리 보고 전달) |
| T25 | 제목으로 개인정보 낭독 | 줄임 | A (F0 차단, 첫 통합은 제목 끔), C (자르기) |

## 5. 검증 계획과의 연결

| 검증 계획 항목 | 관련 위협 | B 쪽 현재 근거 |
|---|---|---|
| A05 위험 화면 시나리오 정답지 | T1·T2·T3 | 단위 시험 사례. 라벨 계약 벡터 176개(v3, 초안 v1·v2는 175개)는 계약 PR에서 Kotlin 시험으로 추가 |
| C03 라벨 없는 버튼과 실제 안전 클릭 | T1·T8·T13 | 단위 시험. 실기기 미실시 |
| C04 팝업 분류 성능 | T3·T16·T17·T19 | 합성 82개 73/82, held-out 80개 73/80(여섯 종류), 위험 오류 0. B 단독 정답이라 실제 앱 정확도 아님 |
| C06 위험 동작·민감정보 보호 | T3·T15·T17·T18·T25 | 단위 시험, 에뮬레이터 계측 시험, 정적 점검 |
| C08 연속 팝업·상태 관리 | T6·T7·T13·T21·T22 | 토큰·조작 거부, 세션 기록(1회·앱 이탈·1분 3회) 단위 시험. 실기기 미실시 |
| C09 사용자 선택 기억 | T10 | 자동 반복 꺼짐. 실패 기록(#48)까지 구현, D 연동 전 |
| C03·C05 실제 안전 클릭·자동 닫기 | T21·T23·T24 | 에뮬레이터 덤프 28화면 × 프리셋 3(A 적격 근거 가정): 금지 버튼 누름 0. F9 병합 전이라 실제 클릭 0회 |

## 6. 공격 비용 관점

- **잘못 허용**(위험 버튼을 누름)은 결제·동의처럼 되돌릴 수 없어서 비용이 매우 큽니다. **잘못 거부**(닫기를 못 알아봄)는 사용자가 직접 조작하면 되는 불편입니다.
- 그래서 B는 **애매하면 거부하거나 묻는 쪽**을 고릅니다. 다만 설계 F8의 "아무것도 안 눌러서 안전성만 달성한 설계는 합격시키지 않음"에 따라, 실제 닫기 인식률도 측정해서 함께 보고해야 합니다(검증 계획 C13).

## 7. 남은 위험 (B만으로는 못 막음)

1. **완벽한 위장 버튼:** 라벨이 정확히 "닫기"인데 누르면 설치·이동하는 버튼. 처음 본 팝업은 사용자 확인, A 게이트의 팝업 소속·F0 검증, 1회 실행으로 줄이지만 완전히 막지는 못합니다.
2. **화면 확인과 클릭 사이의 변화(TOCTOU):** Android는 화면 검사와 클릭을 원자적으로 묶어주지 않습니다(PR #10 문서). A 게이트의 직전 재검사로 줄입니다.
3. **이미지로만 된 결정 화면:** 접근성 트리에 글자가 없고 OCR도 실패하면 결정 신호를 못 볼 수 있습니다. 이 경우 후보도 없어서 누르지 않습니다(안전 쪽).
4. **다른 언어 팝업:** 한국어·영어만 판정해서 다른 언어는 후보가 없습니다(안전 쪽, 잘못 거부).
5. **조립 실수:** 정책을 화면마다 새로 만들면 세션 기록(1회·앱 이탈·1분 3회)이 사라집니다. 시계가 A와 다르면 5초 기한이 틀립니다. A가 누르지 않은 결과를 넘기지 않으면 그 팝업이 30초 뒤 세션 내내 묻기로 바뀝니다(안전 쪽). B 코드로는 막을 수 없어 [연결 안내](INTEGRATION.md)에 조건으로 적었습니다.
6. **사전 기반 분류의 한계:** 사기·결정 문구는 키워드 사전으로 찾습니다. 실제 앱 문구로 다듬지 않았고, 사전 밖 표현은 쿠폰·광고로 보일 수 있습니다(T17·T18).

## 8. 남은 일

- 개인정보 정적 점검(로그·파일·네트워크·Android import 금지)을 자동 시험으로 고정 (T15)
- 평가 정답의 교차 검토(D)와 실제 앱 분류 정확도 측정 (T16, C04·A03). D 정답지에 COUPON 종류와 프리셋별 기대값 추가
- androidTest(한글 옆 영어 경계 등)를 CI에서도 실행할지 A와 논의 (T18)
- A의 F9 연결 PR에서 `F9PolicyBridge` 어댑터와 결과 전달을 B가 리뷰 (T21·T22, [연결 안내](INTEGRATION.md) 4절)
- 기억 규칙 자동 반복 계약(반복 허락, `INCOMPLETE_EVIDENCE` 처리) (T10·T13)
- AGENTS.md 6장 2번 개정 (팀, T9)
- 실기기·TalkBack 검증 (D QA와 함께, C03·C05·C08)
