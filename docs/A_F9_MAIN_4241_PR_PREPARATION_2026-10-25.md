# A F9 — e38aac51 최신 main 통합 준비·검증 (2026-10-25)

현재 기준은 `e38aac519982fad51e092140fe5ecad163c226b7`(#34·#50·#51 포함)이다. 기존 14개 F9 파일 집합만 새 worktree로 이식하고 #51 결과 경계를 정리했다. 파일명에 남은 4241은 이전 준비 문서 경로를 유지하기 위한 것이며, 이 문서의 현재 결과는 e38aac51 기준이다. 원래 4241 문서와 작업 파일은 이전 worktree 및 백업에 보존했다.

운영 자동 닫기·자동 SKIP·F12 자동 반복·REVALIDATING은 OFF다. 실제 서비스 조립은 하지 않았다. stage·커밋·push·merge·PR 수정은 하지 않았다.

## 보존·비교·작업 공간

- 이식 원본: `C:\Users\qlwk7\.codex\worktrees\f9-main-4241-prep\close_for_you_a_nonexecuting_20261022`, HEAD `4241a940`.
- 변경 전 백업: `C:\Users\qlwk7\AppData\Local\Temp\closeforyou-f9-e38-prep-7b0dfa151d4e4fab99845d76818da8f5`. 14개 사본·SHA-256 manifest·status·HEAD·tracked binary diff·index diff를 보관했다. 미추적 파일도 전부 포함한다.
- 기존 #42: `C:\Users\qlwk7\.codex\worktrees\f9-safe-action-executor\close_for_you_a_nonexecuting_20261022`, HEAD `7f0f542`, 변경 파일 73개를 읽기 전용으로 기준 기록했다.
- #29 원본: `C:\Users\qlwk7\StudioProjects\close_for_you_a_nonexecuting_20261022`, 이번 작업 시작 시 HEAD `f3a6f049`, 변경 파일 102개를 읽기 전용으로 기준 기록했다. 기존 #29 변경을 이식하지 않았다.
- 새 worktree: `C:\Users\qlwk7\.codex\worktrees\f9-main-e38-integration-prep\close_for_you_a_nonexecuting_20261022`, detached HEAD `e38aac51`. 코드 수정·빌드·검증은 여기에서만 수행했다.
- 기존 세 worktree의 HEAD·status·변경 파일 해시를 종료 시 다시 비교한다. fetch에 따른 공용 원격 ref 갱신 외에 기존 checkout의 작업 파일이나 index를 변경하지 않았다.
- `local.properties`는 SDK 경로를 위한 ignored 로컬 설정이며 최종 파일 목록에서 제외한다.

`origin/main`을 fetch하여 e38aac51임을 확인했고 GitHub #51의 병합 상태와 계약을 읽었다. 4241→e38의 변경은 C 구현·시험·문서 7개다. 새 main에 없는 F9 파일만 추가했고, D 파일은 upstream blob이 4241과 동일함을 확인한 뒤 F9 옵션을 적용했다. #34·#50·#51 코드를 덮어쓰거나 별도로 복제하지 않았다.

## 최신 main 대비 정확한 변경 파일: 14개

경로는 저장소 루트 기준이다. D 1개는 부분 수정, 나머지 13개는 main에 추가되는 파일이다. 이식 전후 경로 집합은 동일하다.

| 파일 | 역할 / 이번 정리 |
| --- | --- |
| `app/src/main/java/kr/closeforyou/accessibility/action/AndroidF9ScreenPort.kt` | native 노드 재조회·100ms F0·ACTION_CLICK·관측. 4241 준비본 그대로 |
| `app/src/main/java/kr/closeforyou/accessibility/action/F9AutoCloser.kt` | 단발 실행·원본 5초 TTL·5조건·화면/세션 검사·종료 결과 소유권. 4241 준비본 그대로 |
| `app/src/main/java/kr/closeforyou/accessibility/action/F9ContentSafety.kt` | 보호 앱·결정/사기 문구 차단. 4241 준비본 그대로 |
| `app/src/main/java/kr/closeforyou/accessibility/action/F9ResultFeedback.kt` | legacy formatter/호환성 시험용으로만 보존. 자동 결과 경로에서 호출하지 않음 |
| `app/src/main/java/kr/closeforyou/accessibility/action/F9ActionPolicyBridge.kt` | 실제 B 승인·결과를 같은 정책에 전달한 뒤 title 없는 HandlingReport 1개 생성 |
| `app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceConnection.kt` | 정책 1개·공유 배처 입력·C 보고 전달·동의 gate·WAIT 수명 포트. 운영 서비스 미설치 |
| `app/src/main/java/kr/closeforyou/accessibility/integration/F9FeedbackRouter.kt` | #51 SpeechHandlingListener만 사용, 제목 제거, WAIT null 키와 초기화 |
| `app/src/test/java/kr/closeforyou/accessibility/action/F9AutoCloserTest.kt` | 클릭/TTL/5조건/무효화/보호/취소/중복 회귀. 4241 준비본 그대로 |
| `app/src/test/java/kr/closeforyou/accessibility/action/F9ResultFeedbackTest.kt` | legacy 세 조건 판정 호환성 시험. 4241 준비본 그대로 |
| `app/src/test/java/kr/closeforyou/accessibility/action/F9ActionPolicyBridgeTest.kt` | 실제 B 상태·중복·결과 순서·미실행/실패/성공·취소·source/WAIT 보고 |
| `app/src/test/java/kr/closeforyou/accessibility/integration/F9FeedbackRouterTest.kt` | 실제 #51 C 리스너·리소스·공유 PopupChoiceFlow 배처·제목/WAIT/플래그 시험 |
| `app/src/androidTest/java/kr/closeforyou/accessibility/action/F9SyntheticClickInstrumentedTest.kt` | 실제 Android 클릭 + 실제 B bridge + 실제 C 배치, 명시적 합성 증거/가짜 announcer |
| `popup-test-app/src/main/java/kr/closeforyou/popuptest/PopupScenarioActivity.kt` | 명시적 P01 지연/잔존/배경 준비 후 표시 옵션. 기본 P01 및 최신 C09 동작 유지 |
| `docs/A_F9_MAIN_4241_PR_PREPARATION_2026-10-25.md` | 현재 e38 기준 보고서 |

## #51 충돌 해결 및 B/C 경계

1. 성공은 main의 `PressSuccess.isConfirmed` / `HandlingReport.closedConfirmed`를 사용한다. `attempted && MECHANICAL_SUCCESS && CLOSED_CONFIRMED` 세 조건을 모두 요구한다. C `PressSuccess`, `PressResultSpeech`, `AnnouncementBatcher`, `SpeechHandlingListener`, `PopupChoiceFlow`와 B 구현은 변경하지 않았다.
2. 이전 `F9FeedbackRouter`의 직접 F9ResultFeedback fallback을 제거했다. 자동 결과는 실제 #51 `SpeechHandlingListener` 하나만 담당한다. 보고가 없거나 미실행이면 C 계약대로 조용하며, C 오류 후 직접 안내로 fallback하거나 클릭/보고를 재시도하지 않는다. legacy formatter는 호환성 시험에만 남고 main 실행 연결/계측에서 호출하지 않는다.
3. `F9ActionPolicyBridge`가 동일 실행의 B decision/classification을 작은 임시 HandlingReport로 묶는다. EXECUTE는 B `onActionResult` 후 결과를 붙여 한 번 보낸다. WAIT/ASK/ANNOUNCE/DENY는 결정 직후 결과 없이 한 번 보낸다. USER_CHOICE/null source는 보고하지 않으며, 공통 HandlingReport도 PRESET·REMEMBERED_RULE만 허용한다. 호출자가 반환 결과를 B에 다시 보내면 안 된다.
4. 기존 단발 결과 전달 소유권과 `policyConsulted` 구분을 유지한다. B 조회 전 동시 중복 거부는 진행 중 승인을 해제하지 않는다. 승인 후 마지막 gate 거부·취소·실패·성공은 같은 B 인스턴스에 각각 한 번 전달된다. bridge의 pending 보고는 수신자 호출 전에 지운다. B 결과가 C 보고보다 먼저 도착하는 시험을 추가했다.
5. 첫 통합은 **항상 `title = null`**이다. B bridge 생성 시 null이고 C 경계에서도 다시 null로 만든다. 제목 낭독 스위치를 켜지 않는다. #50 제목 보호 코드는 main 그대로 유지한다.
6. `F9ServiceConnection`은 session 정책 하나를 생성/주입받아 재사용한다. execute마다 정책을 생성하지 않는다. 다른 choice 경로와 정책을 공유할 때는 서비스 소유 ActionPolicy를 주입해야 하며, 별도 사전 승인과 F9 내부 승인을 이중 호출하면 안 된다.
7. 배처는 연결 생성자의 **필수 입력**이다. 실제 조립부가 `SpeechHandlingListener.batchFlush`로 만든 배처 한 객체를 F9와 PopupChoiceFlow 모두에 넘겨야 한다. JVM에서 실제 PopupChoiceFlow의 사용자 선택 결과와 자동 결과를 같은 배처에 넣어 한 문장으로 묶이는 것을 확인했다. 배처 생성/취소 소유권은 조립부 하나에 있다.
8. WAIT 운영 identity는 미합의이므로 `waitKeyOf = { null }`을 명시해 C의 Mock `windowId + structureDigest` 기본값을 사용하지 않는다. 불확실한 WAIT는 매번 안내한다. 새 팝업 판정, 제거 확인, 재연결의 초기화 포트를 노출했다. 생성 시 SERVICE_RECONNECTED, 종료 시 clear를 호출한다. 실제 CLOSED_CONFIRMED 관측에도 제거 초기화를 한다(기계적 클릭 실패라도 제거 관측 자체는 별개다). 같은 팝업의 새 snapshot/UUID마다 NEW_POPUP을 호출하면 안 된다.
9. 보고는 서비스 scope로 정확한 immutable 값을 넘긴다. 대기 중인 비시도 WAIT/ASK/ANNOUNCE는 배달 시 현재 토큰이 아니면 버린다. 시도 결과는 이미 사라진 팝업의 과거 결과를 알리므로 C 계약대로 token 없는 안내가 가능하다. 종료된 router는 늦은 보고를 버린다.

C·B·공통 계약·실제 `CloseForYouAccessibilityService` 및 4241→e38 upstream 7개 파일은 모두 e38 기준 그대로다. C 변경을 별도로 중복 적용하지 않았다.

## 유지한 차단과 조사 결과

- 원본 PopupSnapshot 수집 시각 기준 4,999ms/5,000ms 경계, 다섯 AutoEligibility, 토큰·window·screenVersion·captureEpoch·서비스 세대·revision, 마지막 실제 노드 재조회, 단발 예약 및 LEFT_APP 세션 차단은 유지한다.
- 보호 앱/결제/동의/권한/비밀번호/사기·혼합 문구/불완전 범위/불명확·복수 노드/사용자 조작/화면 무효화는 거부한다. 좌표·제스처·Back·부모 우회가 없다. 실제 클릭은 별도 application-window의 명확한 native Button CLOSE만 지원한다.
- 자동 SKIP은 기본 B `allowAutoSkip=false`와 A CLOSE 전용 gate로 막는다. F12 REMEMBERED_RULE 보고는 계약상 받을 수 있지만 A의 실행 승인은 PRESET만 허용하므로 F12 자동 반복이 켜지지 않는다. `RememberChoiceFeature.enabled`, `RevalidatingFeature.enabled`, `PresetAutoCloseFeature.live`, `HandlingTitleSpeechFeature.enabled` 모두 OFF를 시험했다.
- C의 실제 `UiVoicePreferences.autoCloseConsentSource.current()`를 매 gate에서 읽는 연결은 가능하다. 그러나 live=false, 추가 `presetAndFirstSeenConsent` adapter 기본 false 및 기존 per-app 설정의 추가 AND를 유지했다. 수집/OCR/자동 닫기 동의를 서로 대체하지 않는다. 실제 첫 허용·앱 범위와 기존 F12 per-app toggle의 의미를 정리하고 동의/범위 변경을 revision에 연결하는 작업이 남았다.
- 실제 `CaptureOcrPipeline`과 `DecisionEvidence`는 INCOMPLETE_EVIDENCE를 유지하며 AutoEligibility를 발급하지 않는다. 특히 DecisionEvidence는 개인정보 최소화를 위해 결정 원문·대상 노드를 줄인다. 이것을 전체 결정 문구 검사 완료로 간주하면 안 된다.
- `DECISION_TEXT_SCOPED`는 A가 전체 팝업 결정 문구를 빠짐없이 수집했다는 사실과 B가 그 전체 문구를 검사했다는 사실 둘 다 필요하다. B의 `DecisionPopupScope`가 경고를 보존하는 것만으로 이 다섯 증거를 자동 생성할 수 없다. F0 영수증·정확한 scope·트리 완전성·텍스트 검사 범위·대상 포함 근거를 원본 토큰/시각에 묶는 실제 evidence adapter가 필요하다. 기본 evidence=null을 유지하고 허용 증거를 임의 생성하지 않았다.
- #29 Cleanup Barrier/native 창 제거 확인이 이 main에 연결되지 않았다. cleanupReady는 조립부의 실제 장벽을 받아야 하며 운영 코드에 true 상수를 넣지 않았다. #29 없이는 실행을 활성화할 수 없다.

## 검증 결과 — Mock/JVM·에뮬레이터·실기기 구분

환경: JBR 21.0.11, main e38aac51 + 위 14개. 실패/오류/skip 수는 최종 JVM XML 모두 0이다.

| 범위 | 결과 / 한계 |
| --- | --- |
| F9 JVM | 46 통과: executor 22 + B bridge 10 + legacy formatter 4 + 실제 C 연결 경계 10. Android 화면/announcer/선택 UI는 fake |
| app 전체 Debug / Release JVM | 809 / 794 통과. #51 C 회귀 포함 |
| capture-test-host / popup-test-app Debug JVM | 55 / 19 통과 |
| app Debug / Release 빌드 | 모두 성공 |
| app Debug AndroidTest / popup-test-app Debug APK | 성공 |
| app lintDebug / lintRelease | 오류 0, 각 경고 9: 변경하지 않은 Gradle 의존성 경고 |
| popup-test-app lintDebug | 오류 0, 경고 2: 변경하지 않은 문자열·ScenarioListActivity 경고 |
| API 37 x86_64 emulator-5554 | 최종 6개 통과: F9 4 + B Android 영어 경계/종류 2 |
| 실기기 / 외부 앱 / 실제 TalkBack | 이번 작업 미실시. 전체 계측 suite와 #29 독립 worktree 전체 회귀도 미실시 |
| diff 검사 | main 대비 14개 집합 유지, index 비어 있음, diff --check 및 최종 패치 역방향 check 확인 |

실패 재현과 수정:

- 기존 router가 C 보고 없이 직접 안내하던 시험을 먼저 실패 재현한 후 C 전용 경계로 수정했다.
- 첫 에뮬레이터 계측은 6개 중 P01 두 개가 클릭 전에 `F9_BASE_PAGE_SCOPE`로 실패했다. 고정 2초 후 팝업 표시가 초기 로딩과 경쟁했다. D의 명시적 시험 옵션에 배경 수집 후 SINGLE_TOP intent로 팝업을 한 번 표시하는 준비 단계를 추가했다. 기본 시나리오·권한·운영 실행기는 변경하지 않았다. 배경 준비 대기는 snapshot/실행 전이며 원본 popup/page TTL이나 개별 F0 100ms 예산을 늘리지 않는다.
- 수정된 AndroidTest/D APK를 다시 빌드하고 D JVM·관련 lint를 재실행한 뒤 계측 6개가 모두 통과했다. 실패 로그도 보관한다.

계측 P01 성공 경로는 실제 Android ACTION_CLICK true, 팝업 제거 관측 CLOSED_CONFIRMED, 실제 B bridge 결과 전달, title 없는 보고, #51 실제 C 리소스·배처의 한 번 안내 요청을 확인했다. announcer는 요청을 수집하는 fake이므로 실제 음성 재생/청취를 뜻하지 않는다. 남는 팝업은 STILL_PRESENT이며 성공이 아니다. 중복 거부, 정책 없는 운영형 실행 거부, P08 결제·P10 동의·P11 비밀번호·P18 혼합 광고 차단도 확인했다.

계측의 완전한 증거·동의는 **androidTest의 합성 fixture만** 공급한다. P01은 최신 B가 COUPON으로 분류하므로 합성 시험에만 ADVERTISEMENT/COUPON을 허용했다. 운영 adapter가 이 허가를 만들지는 않는다. 실제 B/C 코드의 사용과 운영 조립 완료를 구분한다.

로그: `%TEMP%\closeforyou-f9-e38-red.log`, `closeforyou-f9-e38-targeted.log`, `closeforyou-f9-e38-full.log`, `closeforyou-f9-e38-instrumentation.log`(초기 실패), `closeforyou-f9-e38-fixture-fix.log`, `closeforyou-f9-e38-instrumentation-fixed.log`(최종 성공). 원본 백업의 `prepared-on-main-e38`에 최종 파일/전체 패치/해시/검증 요약/로그를 별도 보관한다.

재현 (JDK/SDK 경로 설정 및 합성 APK 설치 후):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :capture-test-host:testDebugUnitTest :popup-test-app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :popup-test-app:assembleDebug :app:lintDebug :app:lintRelease :popup-test-app:lintDebug --console=plain
adb -s emulator-5554 shell am instrument -w -r -e f9Synthetic true -e class kr.closeforyou.accessibility.action.F9SyntheticClickInstrumentedTest,kr.closeforyou.decision.DecisionEnglishBoundaryInstrumentedTest,kr.closeforyou.decision.DecisionKindInstrumentedTest kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner
```

## 실제 서비스 조립에 남은 작업

1. #29 변경을 별도 검토/통합하여 native 제거 확인·cleanup fail-closed·화면/사용자 무효화·세대·재진입 순서를 실제로 제공해야 한다. 이 준비 작업은 원본 #29를 변경하거나 통째로 포함하지 않았다.
2. 위 다섯 사실을 검증하는 실제 B evidence adapter와 원본 TTL을 연결하고, 명시적 앱별 자동 허용/동의 adapter를 조립해야 한다. 정책은 서비스 세션당 하나이며 F9가 승인/결과 전달을 소유한다.
3. 조립부에서 실제 announcer·SpeechHandlingListener.batchFlush 기반 배처를 만들고 같은 배처를 PopupChoiceFlow/F9에 주입해야 한다. 내용 낭독·영상 설명 관련 adapter는 현재 새로 연결하지 않았다. 첫 통합은 title=null이다.
4. 실제 팝업 identity/제거 관측에서 WAIT lifecycle 포트를 호출해야 한다. 운영 WAIT 키 합의 전에는 null을 유지한다. 재연결에는 새 세션 연결/정책 소유권을 정리하고 종료에는 F9.close → shared batcher 취소 → 서비스 scope 취소 순서를 보장해야 한다. 늦은 보고·이벤트도 무효화해야 한다.
5. 실기기 TalkBack 동시 안내/포커스·음성 재생, cleanup 회귀와 실제 수명 취소 경쟁을 검증한 뒤 별도 승인된 기능 활성화 작업을 해야 한다. 지금 기능 플래그를 켜거나 운영 자동 실행을 완성했다고 표시할 수 없다.

이번 산출물은 최신 B/C 계약에 맞춘 F9 연결 포트와 검증된 합성 실행 경로다. 원격 #42는 갱신하지 않았다. 후속 Git 통합 권한이 생기면 원본 dirty worktree 밖에서 기존 #42 이력의 후손으로 최신 main을 통합하고, 이 14개만 명시적으로 적용·검증·stage하는 별도 절차가 필요하다. 이번에는 이 절차를 실행하지 않았다.
