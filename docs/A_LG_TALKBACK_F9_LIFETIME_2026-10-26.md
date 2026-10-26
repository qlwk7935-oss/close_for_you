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
