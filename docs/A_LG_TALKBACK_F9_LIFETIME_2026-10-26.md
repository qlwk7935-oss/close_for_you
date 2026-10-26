# LG 벨벳 TalkBack / 수동 선택 표시 수명 보강

## 기준과 증거 수준

- 작업 기준: `fix/a-pr58-lg-check`, `771049b11f7a2c021a9296241492399a1894339d`.
- 별도 작업: `fix/a-lg-talkback-lifetime-20261026`, `/workspace/a-lg-talkback`.
- #59 위젯 비교 기준: `d1ec9e949781ba3d16185783b6a0bc2c61dac54d`. 해당 소스를 적용하거나 수정하지 않았다.
- 사용자 LG 벨벳 Android 12 실측: TalkBack ON에서 선택창 미표시, OFF에서 약 1초 후 표시 후 약 5초에 소멸, 오클릭 없음. 이번 환경에는 연결된 Android 기기가 없으며 LG 재실측은 하지 않았다.
- 확인된 코드 결함: 수집 이후 evidence 또는 표시 전 admission이 진행 중일 때 원본 팝업의 포커스가 오면 작업은 취소되지만, 기존 `pendingCapture`만 확인하는 복구 조건 때문에 새 검토로 이어지지 않을 수 있었다.
- 미확정: LG의 첫 포커스가 실제 자동 포커스였는지, 이것이 ON 미표시의 유일한 원인인지, OFF에서 실제로 어떤 호출자가 창을 제거했는지는 아직 확인되지 않았다. 특히 기준 코드도 순수 원본 TTL 만료에서는 이미 표시한 수동 창을 유지하므로, 약 5초라는 관측만으로 TTL 원인이라고 단정하지 않는다.

## 첫 포커스 처리

`PendingFocusReview`를 서비스의 실제 이벤트 경계에 연결했다. 수집 / evidence / 표시 전 admission 중 하나가 이미 진행 중인 경우에만 포커스 이벤트 이후 검토를 이어간다.

1. 원본 포커스를 자동 또는 사용자 포커스로 인증하지 않는다. 이벤트를 무시하지 않고 기존 epoch, 실행 권한, evidence 및 admission을 무효화한다.
2. 진행 중이던 검토에 한해 detector의 이전 비교 상태도 폐기하고, 기존 debounce와 동의·허용 앱·터치 조건을 거쳐 새 전체 F0/F1 수집을 요청한다. 이전 Candidate나 F1 신호를 복사하지 않는다.
3. 새 수집에서도 실제 bounded popup 등의 기존 F1 조건을 만족해야 한다. 전체 화면이나 차단 화면을 후보로 승격하지 않는다.
4. 단독 포커스는 새 검토를 시작하지 않는다. 터치·스크롤·클릭·내용 변경은 이 복구 조건에 들어가지 않는다. 실제 사용자 탐색도 무효화되며, 진행 중이던 검토만 새 수집으로 이어질 수 있다.
5. 표시 후 C 내부 포커스/클릭의 기존 native 소유권·generation·live 창 검사는 그대로다. 검증에 실패한 이벤트는 자체 이벤트로 추정하지 않는다. 계속되는 이벤트로 안정된 새 수집을 얻지 못하면 표시하지 않는다.

## admission, 표시 수명, 실행 증거

| 경계 | 한도 / 의미 |
| --- | --- |
| 표시 전 `ChoicePresentationGate.live()` | 원본 수집으로부터 5초 미만, 현재 토큰·cleanup permit·새 F0 비교 필요 |
| 표시 후 `ManualPromptLifetime` / `ChoiceDisplayLease` | 실제 SHOWN 시점부터 기존 30초. 실행 권한이 아닌 표시/명시적 선택 의도만 유지 |
| 원본 Runtime / 자동 실행 증거 | 기존 5초 TTL. 만료 시 반드시 무효화 |
| 수동 선택 실행 | C 실제 제거 확인 → 새 F0 수집 → 원본과 같은 팝업·버튼·안전 화면 확인 → 새 토큰/수집 시각으로 B USER_CHOICE 재판정 → F9 클릭 직전 재확인 → 최대 1회 |

실제 수동 driver의 표시 무효화 순서를 `applyManualInvalidation`으로 분리하고 같은 함수를 실제 C Flow 회귀에서 호출한다. 원본 TTL 만료나 기존 출처 불명 글로벌 터치에서는 건강한 표시 lease만 유지한다. 글로벌 터치의 출처를 C라고 인증하지 않으며, Runtime 및 자동 권한은 항상 폐기한다. 선택 이후에는 터치 종료와 새 F0 검증을 요구한다.

실제 대상 변경·확인된 외부 상호작용·동의 철회·서비스 종료·configuration 변경·건강성 실패·30초 표시 만료에서는 기존 무효화/제거를 유지한다. 제거와 음성 중단은 각각 시도한다. C의 `onDismissed` 콜백만으로 실제 창 제거 완료를 주장하지 않는다. 기존 `OverlayCleanupBarrier`의 native detach 및 창 부재 확인을 통과해야 실행 전 재수집으로 진행한다.

## Debug 원인 로그

태그: `A_ManualPrompt`. 고정 enum과 서비스 내부 표시 시도 순번만 출력한다.

```text
A_MANUAL_PROMPT;ATTEMPT=1;POINT=OFFERED;CAUSE=NONE
A_MANUAL_PROMPT;ATTEMPT=1;POINT=SHOWN;CAUSE=NONE
A_MANUAL_PROMPT;ATTEMPT=1;POINT=INVALIDATED;CAUSE=ORIGINAL_EVIDENCE_EXPIRED
A_MANUAL_PROMPT;ATTEMPT=1;POINT=DISPLAY_RETAINED;CAUSE=ORIGINAL_EVIDENCE_EXPIRED
```

위는 형식 예시이며 LG 관측 결과가 아니다. 순번 0은 첫 offer 이전 경계이고 순번은 화면 식별자·수집 ATTEMPT·서비스 간 연결 키가 아니다. 취소된 이전 작업의 후속 종료 로그가 인접할 수 있으므로 서로 다른 수집의 원자적 인과관계로 해석하지 않는다. 같은 표시 시도의 동일 point/cause 쌍은 한 번만 기록한다. 예외나 취소가 로거에서 발생해도 정책에는 영향을 주지 않는다.

- 표시 경계: `OFFERED`, `OFFER_REJECTED`, `ADMISSION_REQUESTED`, `ADMISSION_ACCEPTED`, `ADMISSION_REJECTED`, `PRESENT_REQUESTED`, `SHOWN`, `PRESENT_REJECTED`.
- 무효화/정리 경계: `INVALIDATED`, `DISPLAY_RETAINED`, `RESULT_RETAINED`, `DISMISS_REQUESTED`, `OVERLAY_DISMISS_STARTED`, `OVERLAY_REMOVED`, `CLEANUP_FAILED`, `FLOW_CANCELLED`, `FLOW_FAILED`, `SERVICE_CLOSED`.
- 재검토 경계: `FOCUS_REVIEW`, `REVIEW_REJECTED`.
- 주요 원인: `FOCUS_INVALIDATED_REVIEW_REQUESTED`, `FOCUS_INVALIDATED_NO_REVIEW`, `PRESENTATION_GATE_REJECTED`, `ORIGINAL_EVIDENCE_EXPIRED`, `DISPLAY_LEASE_EXPIRED`, `UNATTRIBUTED_TOUCH`, `TOUCH_STARTED`, `TOUCH_ENDED`, `SCROLL_INVALIDATED`, `CLICK_INVALIDATED`, `CONTENT_INVALIDATED`, `WINDOW_STATE_INVALIDATED`, `WINDOW_SET_INVALIDATED`, `INTERACTION_INVALIDATED`, `SCREEN_EVENT_INVALIDATED`, `POLICY_CHANGED`, `SERVICE_STOPPED`, `CONFIGURATION_CHANGED`, `HEALTH_FAILED`, `CLEANUP_FAILED`, `COLLECTION_BLOCKED`, `F1_NOT_CANDIDATE`, `EVIDENCE_BLOCKED`, `EVIDENCE_CANCELLED`, `EVIDENCE_FAILED`, `POLICY_BLOCKED`, `PACKAGE_BLOCKED`, `OFFER_UNAVAILABLE`, `UNKNOWN`.

`SHOWN`은 C API의 성공 반환이다. 실제 픽셀 표시나 TalkBack 음성 재생을 증명하지 않는다. `DISPLAY_RETAINED`는 A의 유지 분기이며 물리적 창 존재 보장이 아니다. `OVERLAY_DISMISS_STARTED`는 제거 시작, `OVERLAY_REMOVED`는 기존 cleanup 증거 확인이다. 이벤트 원인은 처리한 분기만 나타내며 실제 사용자/자동 이벤트 출처를 인증하지 않는다. 원문·패키지·좌표·창 ID·토큰·지문·예외 원문은 추가하지 않는다. Release 로거는 `null`을 반환하며 새 로그 문자열도 Release APK에 없다.

## #59 수동 닫기 위젯

`android.widget.Button`의 기존 경로 외에 정확한 `android.widget.ImageButton`, `android.widget.TextView`만 **B가 승인한 명시적 수동 USER_CHOICE**에서 허용한다. 자동 경로의 클래스 허용 범위는 넓히지 않았다.

클래스만으로 허용하지 않는다. 기존 안전한 정확한 라벨, 단일 닫기 후보, enabled/clickable/visible/action, 원본과 새 F0의 동일성, 전체 F0, B 재판정과 F9 최종 확인을 모두 유지한다. 라벨 없는 X, 구매·결제·동의·권한·쿠폰·오늘 하루 보지 않기·확인, 여러 후보, 변경된 버튼, 사용자 정의 클래스는 허용 근거가 아니다. 구매 문구의 `PrivacyPolicy` 분류는 수정하지 않아 #59 화면이 별도로 차단될 수 있다. 실제 #59 Android 성공은 미검증이다.

자동 실행 `F9ServiceExecutionFeature.enabled=false`, `PresetAutoCloseFeature.live=false`, 자동 SKIP OFF, 기억/자동 반복 OFF를 유지했다. 수동 경로의 기존 승인된 ACTION_CLICK 외에 새 실행·재시도·좌표 클릭은 없다.

## C 재호 UI 인계

- 현재 이름은 `res/values/strings.xml`의 `app_name=닫아줄게`, Manifest의 앱/서비스 label은 이 리소스를 참조한다. 확정 이름 `팝업처리도우미`로의 리소스 변경은 C의 이름/아이콘 변경과 함께 적용하도록 인계한다. package/applicationId/provider 권한·저장소 키를 이름과 함께 바꿀 이유는 없다.
- 현재 아이콘 참조는 `@drawable/ic_launcher`. 새 아이콘은 제작하거나 교체하지 않았다.
- C의 `MoveToButtonFeature` 기본 OFF를 유지하고 A 수동 Flow에서도 `moveToButtonEnabled=false`를 명시했다. 최초 질문 UI와 설정 저장/공개 계약은 C 담당이다. A가 임의로 설정을 추가하거나 포커스 이동을 활성화하지 않았다.
- C/B/D 원본, 공통 모델, Manifest, gradle.properties는 이번 수정 범위에 없다.

## 로컬 검증

신규 JVM 10개: 실제 C `PopupChoiceFlow`/B wiring/A guard와 공유 표시 무효화의 6초 유지·음성 미중단·중복 콜백·외부 무효화·표시 만료/취소·불명 터치·포커스 검토 재개·새 F1 재발견·로거 실패 회귀 7개, 실제 B 정책/F9 session을 통한 #59 위젯 허용·위험/복수/변경 대상 거부 3개. Android 창·TTS·접근성 getter 자체는 JVM에서 fake이며 LG TalkBack을 재현했다고 주장하지 않는다.

- 앱 Debug 973 / Release 958, 호스트 55, 합성 앱 26: 총 **2,012 variant 실행**, 실패/오류/skip 0. 동일 앱 회귀의 Debug/Release 중복 실행을 포함한다.
- 앱 Debug/Release, 앱/호스트 AndroidTest APK, 합성 앱 Debug 빌드 성공.
- 앱 Debug/Release 및 호스트 Lint 오류/경고 0. 합성 앱은 기존 `TypographyFractions`, `SetTextI18n` 경고 2건, 오류 0.
- 기존 패키징/DEX 격리 통과. 새 JVM 테스트가 운영 APK에 없고 Release 새 로그 문자열이 없으며 Release 로거 bytecode가 `null`만 반환함을 확인했다.
- 연결 Android 기기 없음. LG 및 다른 실제 Android 계측은 미실행. 원본 F1 14개 UNOBSERVED, Scroll/Window 문제를 해결했다고 주장하지 않는다.

## LG 벨벳 재실측 절차

개인정보 없는 재현 앱에서만 진행한다. 앱/재현 앱 설치 후 수집 동의·허용 앱·분석 시작·접근성·TalkBack은 사용자가 UI에서 직접 설정한다. 동의/secure settings를 adb로 강제하지 않는다. 자동 기능은 모두 OFF로 확인한다.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
# $serial은 사용자가 확인한 LG 기기 값. 이 패치를 적용해 빌드한 APK 사용.
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial install -r .\popup-test-app\build\outputs\apk\debug\popup-test-app-debug.apk
& $adb -s $serial logcat -v brief -s A_ManualPrompt:I '*:S'
```

1. TalkBack OFF/ON 각각 P01을 열고 손대지 않은 상태에서 실제 창과 음성을 확인한다. ON에서는 첫 자동 포커스 및 C 내부 포커스 이동도 확인한다. `SHOWN`만으로 화면 성공을 대신하지 않는다.
2. C 실제 표시 후 6초 이상 기다린다. 원본 TTL의 `INVALIDATED` 이후 `DISPLAY_RETAINED`인지, 아니면 다른 원인/제거/취소/건강성 코드인지 구분한다. 예상치 못한 조기 소멸이면 해당 고정 코드만 전달한다.
3. 6초 이후 명시적으로 C의 안전한 닫기를 한 번 선택한다. 창 제거 확인 후 새 수집/B/F9가 승인한 경우에만 대상 버튼 한 번, 불확실하면 0회여야 한다. 연속 탭으로 두 번째 실행이 생기지 않는지 확인한다.
4. 별도 실행에서 선택 전에 외부 앱 터치·스크롤·내용 변경·팝업 교체·동의 철회·서비스 재연결을 시험한다. 이전 선택으로 오클릭하면 실패다. 출처 불명 터치는 창 유지 가능하나 과거 실행 권한을 보존하지 않는다.
5. P02/P03/사기·구매·동의·권한 화면에서는 기존 안전 거부를 확인한다. P12 다음 팝업은 새 토큰이며 이전 선택으로 누르지 않아야 한다. #59 위젯은 라벨 있는 단일 안전 닫기만 수동 허용, 나머지는 0회다.
6. 30초 표시 만료와 서비스 종료 시 창/음성이 정리되는지 확인한다. native 제거 실패는 성공으로 기록하지 않는다.

기존 AndroidTest의 `F9ManualServiceInstrumentedTest#explicitChoiceSixSecondsAfterShowRecollectsBeforeOneP01Click` 및 다른 수동 회귀는 이번에 컴파일만 했다. 이 기존 harness는 secure accessibility settings/합성 앱 동의를 임시 변경·복구하고 UiAutomation으로 C 선택을 누르므로 실제 사람의 TalkBack 조작과 다르다. LG 사용자 설정을 보존하는 위 수동 절차를 우선하며 해당 harness를 무조건 실행하도록 요청하지 않는다.

## 추가 LG 보고: CONTENT_INVALIDATED 및 선택 후 원본 팝업 유지

이 절의 수정 기준은 위 작업을 커밋한 `8da31f23f9fbec1ec07d052f85bd1b107b670a0b`다. 앞 절의 2,012회 검증은 이전 실행 기록이며, 아래 결과와 구분한다. 추가 사용자 보고는 C 버튼 탭과 결과 상태는 보이지만 원본 팝업이 닫히지 않았고 마지막에 `OFFER_REJECTED / OFFER_UNAVAILABLE`이 있었다는 것이다. 이 보고만으로 선택이 A guard를 통과했는지, 실제 ACTION_CLICK을 시도했는지, 어느 단계에서 멈췄는지는 확정할 수 없다.

### 음성 안내 중 콘텐츠 이벤트

기존 native 소유권 경로의 대상에 `TYPE_WINDOW_CONTENT_CHANGED`를 추가했다. 표시 중인 C 창의 native owner/generation, 이벤트 시각의 현재 표시 수명, 정확한 이벤트 package/window 일치, 표시 전 창 집합과의 구별, 독립적으로 확인한 단일 live C 창이 모두 맞는 경우만 자체 콘텐츠 이벤트로 처리한다. 이때 기존 포커스/클릭과 같은 검증 함수를 사용한다. 실패했을 때 title 기반의 더 약한 경로로 fallback하지 않는다.

이는 C의 실제 콘텐츠 변경 때문에 외부 화면 변경으로 처리되는 경로를 좁힌 것이다. LG에서 관측한 CONTENT_INVALIDATED가 실제 C 창에서 발생했다는 증거는 아직 없으므로 LG 원인을 확정하거나 해결됐다고 주장하지 않는다. 대상 앱의 콘텐츠 변경, null/불일치 package, 다른 창, retired/native 실패, ID 재사용, generation 불일치, 불확실한 live 창 집합은 계속 무효화한다. 단순히 C가 보이거나 TalkBack이 말하고 있다는 이유로 무시하지 않는다. 새 진단 원인은 `DISPLAY_RETAINED / OWNED_CONTENT_CONFIRMED`이다. 자동 실행 권한을 만들지 않는다.

### OFFER 거부의 우선순위

실제 `offer()`가 호출하는 공유 `manualOfferRejection`은 기존 short-circuit 순서를 유지한다. 여러 조건이 참이면 아래에서 **가장 먼저 참인 한 사유**를 기록하며 이후 getter는 호출하지 않는다.

| 순위 | 조건 | `OFFER_REJECTED`의 CAUSE |
| --- | --- | --- |
| 1 | closed | OFFER_CLOSED |
| 2 | health.failed | OFFER_HEALTH_FAILED |
| 3 | completedKey == key | OFFER_COMPLETED_KEY |
| 4 | !cleanup.ready | OFFER_CLEANUP_NOT_READY |
| 5 | overlay.isShowing | OFFER_OVERLAY_SHOWING |
| 6 | selected != null | OFFER_SELECTION_PENDING |

기존 Runtime의 `OFFER_UNAVAILABLE` 상태 표기는 유지한다. 이는 새 창 요청의 거절이며 기존 선택의 클릭 실패를 뜻하지 않는다. 특히 `completedKey`는 닫기 성공 여부와 별개로 기존 선택을 처리하며 설정하던 값이다. 이미 처리한 동일 팝업의 재요청 차단을 삭제하거나 자동으로 재시도하지 않는다.

### 수동 실행 진단

동일한 `A_ManualPrompt` 태그에 `A_MANUAL_ACTION`을 추가했다. 선택 접수 때 현재 표시 시도 번호를 고정한 별도 진단 객체를 생성한다. 다음 OFFER가 시작돼도 이전 비동기 실행의 번호가 바뀌지 않는다. enum 코드만 보관하며 진단을 성공 조건으로 사용하지 않는다.

| STEP | 의미 |
| --- | --- |
| CHOICE_ACCEPTED / PASSED | C의 generation/currentness guard를 통과한 CLOSE_ONCE 접수. 탭 감지만으로 기록하지 않음 |
| OVERLAY_REMOVAL / WAITING, PASSED | 기존 cleanup permit 대기 및 확인. 이것은 C 창 제거이며 원본 팝업 닫힘이 아님 |
| INTERACTION_END / WAITING, PASSED | 기존 터치/탐색 종료 대기 |
| MANUAL_REFRESH / STARTED, COMPLETED, REJECTED | 실제 기존 새 F0 호출. COMPLETED는 반환 및 현재성 checkpoint 통과이며 전체 실행 승인이 아님 |
| SNAPSHOT_MATCH / PASSED, REJECTED | 기존 freshness/completeness, `EvidenceSnapshotComparison.firstMismatch`, 전체 nodes 비교 |
| TARGET_MATCH / PASSED, REJECTED | 기존 선택 대상 및 새 트리에서 B가 재구성한 동일 후보 비교 |
| SESSION_ENTRY / PASSED, REJECTED | 서비스/현재성/중복 실행 보호. 거절 사유는 SafetyReason enum |
| B_DECISION / STARTED, APPROVED, REJECTED | 실제 B 판정. NEXT와 기존 판정 사유의 첫 SafetyReason을 기록 |
| F9_VALIDATION / STARTED, PASSED, REJECTED | 실제 F9 상태·화면·후보·승인 검사. F9Block enum으로 거절 구분 |
| CLICK_ATTEMPT / ATTEMPTED, NOT_ATTEMPTED | 기존 F9Press/결과가 보고한 실제 시도 여부. 클릭 API 호출 전 추측으로 ATTEMPTED를 기록하지 않음 |
| CLICK_RESULT / COMPLETED | 클릭 반환 여부와 기존 원본 팝업 관측 결과. DELIVERY_TRUE만으로 성공이라고 하지 않음 |
| MANUAL_END / COMPLETED, CANCELLED, FAILED, REJECTED | 상위 수동 작업의 종료 경계. COMPLETED 자체가 닫힘 성공을 뜻하지 않음 |

실제 진행하지 않은 단계는 PASSED로 채우지 않는다. timeout은 당시 대기 단계의 `TIMED_OUT`, 예외·취소는 해당 단계의 `FAILED/CANCELLED`로 남기며 기존 전파를 유지한다. 검사 실패를 가리기 위해 예외 원문을 저장하거나 출력하지 않는다. 첫 단계가 중단되면 뒤 단계 로그가 없는 것이 정상이다.

아래는 **형식 예시이며 실측 결과가 아니다**. 실제 출력에는 사용하지 않는 enum 필드도 NONE으로 포함한다.

```text
A_MANUAL_PROMPT;ATTEMPT=1;POINT=OFFER_REJECTED;CAUSE=OFFER_COMPLETED_KEY
A_MANUAL_ACTION;ATTEMPT=1;STEP=OVERLAY_REMOVAL;STATE=TIMED_OUT;REASON=NONE;SAFETY=NONE;F9=NONE;MISMATCH=NONE;OUTCOME=NONE;NEXT=NONE
A_MANUAL_ACTION;ATTEMPT=1;STEP=SNAPSHOT_MATCH;STATE=REJECTED;REASON=NONE;SAFETY=NONE;F9=NONE;MISMATCH=CONTENT;OUTCOME=NONE;NEXT=NONE
A_MANUAL_ACTION;ATTEMPT=1;STEP=B_DECISION;STATE=REJECTED;REASON=NONE;SAFETY=USER_INTERACTION;F9=NONE;MISMATCH=NONE;OUTCOME=NONE;NEXT=WAIT
A_MANUAL_ACTION;ATTEMPT=1;STEP=F9_VALIDATION;STATE=REJECTED;REASON=NONE;SAFETY=NONE;F9=SCREEN_UNVERIFIED;MISMATCH=NONE;OUTCOME=NONE;NEXT=NONE
A_MANUAL_ACTION;ATTEMPT=1;STEP=CLICK_RESULT;STATE=COMPLETED;REASON=DELIVERY_TRUE;SAFETY=NONE;F9=NONE;MISMATCH=NONE;OUTCOME=STILL_PRESENT;NEXT=NONE
```

`SCREEN_UNVERIFIED`는 기존 F9 scene/target 비교의 거부이며 어느 하위 비교가 원인인지 이 코드만으로 확정하지 않는다. 예를 들어 배경 페이지를 현재 읽을 수 없고 기존 page proof의 5초 fallback 조건도 충족하지 못하면 계속 차단한다. 이를 우회하기 위해 원본 시각을 갱신하거나 불완전한 배경을 채우지 않는다.

진단은 기존 판정 결과만 사용하며 화면 수집·OCR·IPC·시계 조회를 추가하지 않는다. C 콘텐츠 소유권 검사는 기존 native/live-window 확인 경로를 사용한다. F9 최종 검사와 ACTION_CLICK 사이에는 진단 I/O를 넣지 않고 검사 결과를 지역 boolean에 기록한 뒤 press 반환 후 출력한다. 로그 sink의 예외·취소는 기존 처리를 바꾸지 않는다. Release 로거는 계속 null이고 두 로그 마커 모두 Release APK에 없다.

### 이번 로컬 검증 및 LG 재실측

신규 회귀 21개: 실제 공유 OFFER 우선순위, 선택 후 새 OFFER 거절과 실행 분리, 시도 번호 고정, cleanup timeout/예외/취소, 새 F0 미관측·snapshot/target 불일치, 실제 B 거절, F9 최종 거절, 관측된 원본 닫힘/유지 구분, 새 TTL/동의 철회, 클릭 후 취소, 최종 검사와 클릭 사이 진단 I/O 부재, 로거 실패, C content 소유권 성공/실패를 검증한다. 실제 Android getter·LG TalkBack·native 제거 성공을 JVM으로 입증한 것은 아니다.

- 앱 Debug 994 / Release 979, 호스트 55, 합성 앱 26: 총 2,054 variant 실행, 실패/오류/skip 0.
- Debug/Release 및 앱/호스트 AndroidTest APK 컴파일, 패키징/DEX 및 Release 로그 제거 확인 완료. 앱 Debug/Release·호스트 Lint 오류/경고 0, 합성 앱은 기존 TypographyFractions/SetTextI18n 경고 2건·오류 0이다. 변경 없는 일부 Gradle 작업은 UP-TO-DATE였으며 실제 Android 계측은 실행하지 않았다.
- 자동 닫기·자동 SKIP·F12 반복은 계속 OFF. 새 F0/B/F9/5초 실행 TTL·단일 클릭·실제 제거 확인은 유지한다.

LG에서는 위 설치/로그 명령을 그대로 사용하고 TalkBack OFF/ON 각각 **P01 → 실제 C 창 6초 유지 → 명시적 닫기 선택 1회 → C 제거와 원본 팝업 닫힘을 각각 확인**한다. 창이 사라지면 `INVALIDATED` 또는 `DISPLAY_RETAINED`의 원인을, 원본이 남으면 같은 선택 시도 번호의 `A_MANUAL_ACTION` 경계를 확인한다. `OFFER_REJECTED`는 별도 행으로 구분한다. DELIVERY_TRUE, C 제거 또는 결과 UI 유지 자체를 닫힘 성공으로 기록하지 않는다. 최초 실패 시 가짜 재시도 없이 종료하고 고정 코드만 전달한다. LG 미실측이므로 실제 실패 지점은 아직 미확정이다.
