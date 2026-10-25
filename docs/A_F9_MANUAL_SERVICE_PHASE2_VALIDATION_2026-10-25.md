# A F9 실제 서비스 수동 통합 2차 검증 — 2026-10-25

## 범위와 결론

기준은 main `abe5d2be939186424ba773b2e13897d0d25750f7`와 #53 HEAD `c04ffb9737dfc5c4b04951a684df9e9721e84fb4`이다. 별도 worktree `f9-manual-service-phase2`의 detached HEAD에서만 수정했다. Git stage·커밋·푸시·PR·병합은 하지 않았다.

API 37 합성 앱에서 실제 서비스의 수집 → B 분류 → C native 선택창 → 명시적 닫기 선택 → A 안전 게이트 → 실제 ACTION_CLICK → CLOSED_CONFIRMED 경로를 확인했다. 시연 검증 범위는 **수집을 허용한 합성 앱의 별도 창/native E2 Button**이다. 일반 앱 전체, 실기기, 실제 TalkBack 조작까지 검증한 결과가 아니다.

운영 자동 닫기, 자동 SKIP, F12 자동 반복, REVALIDATING은 모두 OFF다. 이번 작업은 자동 동작 활성화가 아니다.

## 실제 연결

- `CloseForYouAccessibilityService`: 실제 수집/개인정보 보호, 원본 수집 시각의 5초 만료, 화면·사용자 조작·설정·서비스 수명 무효화. 수집은 Cleanup Barrier의 immutable permit을 기다리고 세대/현재성을 다시 검사한다. 마지막 SCREEN 요청을 취소한 focus 이벤트는 이전 요청만 새 F0로 이어받으며 단독 focus가 수집 허가가 되지 않는다.
- `ManualPopupConnection`: 실제 A 공개 증거를 B DecisionCandidateWiring/분류기로 보내고 C PopupChoiceFlow를 실행한다. C native 창은 이 경로 하나만 만든다. 일반 페이지 전체 전환은 수동 팝업으로 표시하지 않는다.
- `ChoicePresentationGate`: node-free 원본 비교 기준, 원본 TTL, cleanup permit, 현재 토큰을 유지한다. 두 Main turn 사이의 새 F0/동일 화면 확인 후에만 표시한다. 변하지 않은 중단 팝업은 최초 표시와 새 검증을 거친 복구 한 번까지만 허용한다. 다음 팝업 수집 자체는 막지 않는다.
- `OwnedOverlayEventPolicy`: 표시 전 owned 집합과 표시 후 유일한 새 C 창, 토큰·C generation·live 소유권을 확인한다. 패키지나 시간만으로 이벤트를 제외하지 않는다. 불명확한 mask, WCH 128, touch/click, 다른 앱·실제 대상 변경을 일반 무효화로 처리한다.
- `OverlayCleanupBarrier`/`OverlayNativeWindowLifetime`: 제거 의도/일치하는 native 창 ID·generation, remove 반환/HIDDEN, 실제 detach, 전체 창 목록의 부재를 확인한다. UNKNOWN·충돌·실패는 fail-closed이며 성공으로 해제하지 않는다.
- C의 generation 검사를 통과한 명시적 선택을 동기적으로 등록한다. C 창 제거 이벤트는 A 공개/비공개 수집 토큰을 정상 무효화한다. 선택은 실행 허가가 아니며, 실제 native 제거 확인 후 원래 앱·창·본문/구조·영역이 그대로인지 새 F0로 확인해야 한다. 이때 로컬 실행 토큰의 event epoch만 맞추고 **capturedAtUptimeMillis는 그대로 둔다**.
- `F9ServiceSession.executeUser`: 원래 수집의 실제 PopupDetection을 전달한다. NEW_WINDOW를 임의 생성하거나 불완전한 검사를 다섯 적격 조건으로 승격하지 않는다. 세션당 하나의 `DecisionActionPolicy(allowAutoSkip=false, nowUptimeMillis=SystemClock.uptimeMillis)`가 USER_CHOICE를 승인하고 per-request F9PolicyBridge가 그 승인만 실행한다.
- `F9AutoCloser`/`AndroidF9ScreenPort`: 실제 node·역할·라벨·description·bounds·표시·활성 상태와 전체 안전 화면을 다시 확인하고 실제 Button의 ACTION_CLICK 한 번만 호출한다. 좌표/뒤로 가기/부모/제스처 대체가 없다. 동시 중복·오래된 callback·원본 TTL·서비스 종료·보호 화면을 거부한다.
- `AndroidF9ScreenPort`: 결과 관측에서도 현재 앱의 수집 동의/앱 제외를 확인한다. 보호되거나 수집을 허용하지 않은 앱은 본문을 순회하지 않고 이탈 판정용 identity-only/불완전 scene만 반환한다.
- 결과 소유자는 session의 승인 요청별 atomic completion 하나다. 승인 후 미실행·실패·성공·취소 결과를 B onActionResult에 한 번 전달한다. 다른 진행 중 요청을 중복 거부 요청이 해제하지 않는다. 정책을 거치기 전 거부/동시 중복이 다른 승인을 소비하지 않는다. B 계약에 따라 decide 이후 A가 반환한 차단 결과도 한 번 전달한다.
- 성공 조건은 기존 C `PressSuccess`의 **attempted && MECHANICAL_SUCCESS && CLOSED_CONFIRMED**를 그대로 사용한다. 클릭 반환 true만으로 성공하지 않는다.

## C 결과·음성·수명

수동 결과의 안내는 PopupChoiceFlow가 맡는다. 자동 PRESET/REMEMBERED_RULE 결과는 SpeechHandlingListener만 사용하며 HandlingReport.title=null, WAIT 운영 key=null이다. USER_CHOICE를 HandlingReport로 보내지 않는다. F9ResultFeedback은 기존 파일/시험을 보존했으나 서비스에서 호출하지 않는다.

실제 수동 flow와 자동 listener는 같은 AnnouncementBatcher를 공유한다. 새 팝업·제거·재연결은 WAIT 기억을 초기화한다. 원본 5초 만료 후 원본 분석/대답 callback/내용 반복을 폐기하고 버튼·음성 입력·반복을 비활성화한다. 검증된 C 창만 표시 전용 lease로 원래 표시 시점부터 최대 30초 남길 수 있다. 이미 재생 중인 안내는 TTL만으로 끊지 않으며 명시적 말하기 중지는 계속 작동한다. 만료 창 제거로 같은 팝업을 자동 재표시하거나 TTL을 갱신하지 않는다.

실제 화면·보호된 시스템 이벤트·사용자 조작·동의 철회는 표시 전용 lease도 해제한다. 30초는 반드시 보장되는 표시 시간이 아닌 상한이다. 최종 API 37 회차의 TTL 시험은 실제 보호 시스템 이벤트에 의해 조기 종료된 분기(`REAL_PROTECTED_EXTERNAL_EVENT`)였다. 별도 통합 회차에서 `THIRTY_SECOND_NATIVE_DEADLINE`도 확인했으며, JVM 가상 시각 시험에서 원래 표시 시각 29,999/30,000ms 경계를 확인했다. 시스템 이벤트를 무시하도록 바꿔 시험을 맞추지 않았다.

종료 시 실행기부터 닫고 C flow/수집/subscriber/설정 observer를 취소한다. native 창 제거, PromptAnnouncementListener, 지연 callback, 공유 batcher, 안내 기록, TTS 및 capture capability를 정리한다. 불확실한 native 종료의 sticky 차단을 재연결로 강제 초기화하지 않는다. 깨끗하게 정리된 서비스의 실제 재연결은 통과했다.

## 검증 결과

| 검증 | 최종 결과 |
|---|---|
| app assembleDebug / assembleRelease | PASS |
| app testDebugUnitTest | 931 / 0 실패 |
| app testReleaseUnitTest | 916 / 0 실패 |
| capture-test-host JVM | 55 / 0 실패 |
| popup-test-app JVM | 19 / 0 실패 |
| JVM 총합 | 1,921 / 0 실패 |
| app lintDebug / lintRelease | 오류 0, 경고 각각 9 (GradleDependency) |
| popup-test-app lintDebug | 오류 0, 경고 2 |
| app assembleDebugAndroidTest / popup-test-app assembleDebug | PASS |
| 실제 A 서비스 + C native 선택창 계측 | 6 / 0 실패 |
| F9 실제 Android 실행기 + B Android 계약 계측 | 10 / 0 실패 |
| 실제 서비스 운영 자동 실행 OFF 계측 | 1 / 0 실패 |
| API 37 계측 총합 | 17 / 0 실패 |
| git diff --check | PASS |
| 실기기/TalkBack ON·OFF/사용자의 실제 청취 | 미검증 |

최종 전체 Gradle 로그: `%TEMP%/f9-phase2-final-build4.log`. 마지막 계측 소스 변경은 시험 준비의 geometry read 대기뿐이며 `f9-phase2-final-instrument2.log`에서 AndroidTest APK와 lintDebug를 다시 통과했다. 최종 계측 로그는 `%TEMP%/f9-phase2-final-service6.txt`, `f9-phase2-final-kernel10-retest.txt`, `f9-phase2-final-service-off.txt`이다. 빌드 산출물/로그를 소스 변경에 포함하지 않았다.

### 실제 Android/서비스 관측

- P01: C의 라벨 있는 선택을 AccessibilityNodeInfo.ACTION_CLICK으로 선택하고, **원래 앱 버튼은 F9가 실제 ACTION_CLICK**했다. MECHANICAL_SUCCESS + CLOSED_CONFIRMED와 실제 팝업 부재, 추가 클릭 없음 확인.
- 잔존 P01: 반환 true여도 성공으로 분류/안내하지 않으며 추가 시도 없음. 실제 서비스에서는 후속 focus/현재성 무효화로 UNKNOWN일 수도 있다. 격리된 실제 Android 실행기와 JVM에서는 STILL_PRESENT를 별도로 확인했다.
- P12: 첫 광고 클릭 후 다음 공지를 새로 수집한다. 다음 결제/결정 공지는 IMPORTANT_DECISION으로 거부하고 C에 대행 클릭 선택을 표시하지 않는다. 다음 팝업으로 바뀐 것 자체를 CLOSED_CONFIRMED로 처리하지 않는다.
- 원본 TTL 만료 뒤 실행/내용 반복 없음, native 표시 상한/외부 무효화, 동의 철회 뒤 토큰·창 제거 및 클릭 없음 확인.
- 정상 서비스 재연결: 이전 증거 없음, 새 수집의 수동 선택만 표시, 자동 시도 없음 확인.
- 실제 reader의 P08 결제/P10 동의/P11 비밀번호/P18 광고+중요 문구 차단, 정책 없는 운영 OFF, 미동의 앱 identity-only 경계 확인.
- Android 계측의 C 조작은 UiAutomation의 native 접근성 트리를 통한 명시적 선택이다. 실제 사람이 손으로 누르거나 TalkBack 두 번 탭한 시험으로 보고하지 않는다.

### JVM/Mock 회귀

실제 B 정책/후보 wiring과 가짜 screen port로 단발·동시 중복·원본 4,999/5,000ms·토큰/세대·정책 revision·보호 앱·결제/동의·사기 의심·노드 불일치·사용자 조작/취소 경쟁·LEFT_APP 이후 중지·B 결과 1회 소유권·USER_CHOICE 자동 report 금지·다섯 조건 증거/진짜 detection 경계를 확인했다. native cleanup/owned-event/presentation admission의 실패·unknown·오래된 ID 회귀는 가짜 창/노드 DTO로 시험했으며 이를 실기기 증명으로 표시하지 않는다. 관련 A/F9/cleanup/presentation JVM 묶음 167개가 전체 결과에 포함된다.

### 실패 재현 후 수정

수동 lease가 무효여도 raw 토큰으로 돌아갈 수 있던 경로를 회귀 시험으로 재현하고 막았다. 마지막 focus가 수집을 취소한 경우, FrameLayout 루트의 별도 Dialog가 same-window로 잘못 전달된 경우, C 제거 후 선택 coroutine의 결과 관측이 취소되는 경우를 실제 계측에서 확인하고 수정했다. 동의 철회 시험의 창 준비 실패는 native teardown 대기를 보완한 뒤 재검증했다. 보호 시스템 이벤트로 C 창이 조기 제거되는 현상은 #29의 정상 무효화이므로 허용 예외를 추가하지 않았다.

## 시연 범위와 남은 검증/연결

- 시연 가능: API 37 합성 P01의 수동 C 선택 → 실제 단발 닫기/확인, 잔존 시 미확인 안내, P12 다음 결정 화면의 안전 거부, 5초 만료, 동의 철회, 정상 재연결.
- 계측은 팝업 원본 수집 전에 기존 debug preview로 Compose를 준비했다. 최초 UI 초기화가 5초를 소모하면 실행을 거부한다. cold-start 상태에서 항상 시연된다고 보장하지 않는다.
- 이번에 완성한 수동 native E2/별도 창 조립에는 남은 B/C 필수 연결이 없다. 실제 B 정책, A 적격 증거, C 동의/앱 제외, 결과 소유권, 공유 배처가 연결되어 있다. 운영 자동 경로는 독립 feature gate로 의도적으로 막아 두었다.
- 실제 기기의 TalkBack ON/OFF, 일반 터치/두 번 탭, 포커스 유지/복귀, paneTitle+TTS 중복, 결과 음성의 실제 재생/청취를 따로 확인해야 한다. 빠른 탐색·WCH 128·열린 C 창에서 강제 서비스 재연결/종료 시 안전 차단과 사용성을 함께 확인해야 한다. 포커스 이벤트나 TTS 반환을 사람이 들은 증거로 해석하지 않는다.
- SKIP, E1 DISMISS/E3 무라벨/OCR/WebView 등 범위 확장, F12 자동 반복, REVALIDATING, 새 음성 명령 기능은 이번 경로에 추가/활성화하지 않았다. 기존 기능/계약 파일을 삭제하지 않았다.
- GitHub #53, 기존 #29/#42 브랜치와 원본 작업 폴더에는 이번 변경이 올라가지 않았다. GitHub 작업은 사용자 담당이며 본 결과는 로컬 검토용이다.

## 기존 작업 보존

백업: `C:/Users/qlwk7/AppData/Local/Temp/closeforyou-f9-manual-phase2-292426ddfdd64dc2857fe9ea4ea029a4`.

| 원본 작업 공간 | 변경/미추적 파일 SHA-256 | HEAD/status |
|---|---|---|
| 이전 f9-service-assembly-prep | 23개 전부 동일 | 동일 |
| #29 StudioProjects 원본 | 102개 전부 동일 | 동일 |
| #42 f9-safe-action-executor | 73개 전부 동일 | 동일 |

새 worktree index도 비어 있다. reset/clean/stash/브랜치 전환 없이 원본 세 작업 공간을 보존했다. 기존 두 F9 보고서는 이력 자료로 보존했으며 현재 결과는 본 문서를 기준으로 한다.

## 변경 파일

기준 main 대비 전체 파일과 이전 서비스 준비본 대비 이번 수정/추가를 구분한다. 아래는 저장소 상대 경로이며 빌드 결과·로그·비밀 설정은 포함하지 않는다.

### 이번 2차 수정 17개

- `app/src/androidTest/java/kr/closeforyou/accessibility/action/F9SyntheticClickInstrumentedTest.kt`
- `app/src/main/java/kr/closeforyou/accessibility/AccessibilityRuntime.kt`
- `app/src/main/java/kr/closeforyou/accessibility/action/AndroidF9ScreenPort.kt`
- `app/src/main/java/kr/closeforyou/accessibility/action/F9AutoCloser.kt`
- `app/src/main/java/kr/closeforyou/accessibility/AndroidSnapshotCollector.kt`
- `app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceConnection.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceSession.kt`
- `app/src/main/java/kr/closeforyou/accessibility/PopupCandidateDetector.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/choice/ChoicePromptContent.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/overlay/AccessibilityChoiceOverlay.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/overlay/OverlayWindowKeeper.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/overlay/PromptControls.kt`
- `app/src/main/res/values/ui_voice_strings.xml`
- `app/src/test/java/kr/closeforyou/accessibility/integration/F9ServiceSessionTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/PopupCandidateDetectorTest.kt`
- `app/src/test/java/kr/closeforyou/ui/voice/overlay/PromptControlsControllerTest.kt`

### 이번 2차 추가 18개

- `app/src/androidTest/java/kr/closeforyou/accessibility/integration/F9ManualServiceInstrumentedTest.kt`
- `app/src/main/java/kr/closeforyou/accessibility/CaptureObservationInterval.kt`
- `app/src/main/java/kr/closeforyou/accessibility/EvidenceSnapshotComparison.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ChoiceDisplayLease.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ChoicePresentationBoundary.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ChoicePresentationGate.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ExpiredOverlayUpdatePolicy.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ManualPopupConnection.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/OverlayEventDiagnostic.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/OwnedOverlayEventPolicy.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/ChoicePresentationBoundaryTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/ChoicePresentationGateTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/ConservativeEventIntegrationTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/ExpiredOverlayUpdatePolicyTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/ManualChoiceLifetimeTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/OverlayEventCauseTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/OwnedOverlayWindowReadTest.kt`
- `docs/A_F9_MANUAL_SERVICE_PHASE2_VALIDATION_2026-10-25.md`

### #53/이전 준비본에서 그대로 보존한 23개

- `app/src/androidTest/java/kr/closeforyou/accessibility/integration/F9RealServiceOffInstrumentedTest.kt`
- `app/src/main/java/kr/closeforyou/accessibility/action/F9ActionPolicyBridge.kt`
- `app/src/main/java/kr/closeforyou/accessibility/action/F9ContentSafety.kt`
- `app/src/main/java/kr/closeforyou/accessibility/action/F9ResultFeedback.kt`
- `app/src/main/java/kr/closeforyou/accessibility/CollectionResourceSafety.kt`
- `app/src/main/java/kr/closeforyou/accessibility/DiagnosticIsolation.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ChoicePresentationHealth.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/F9CollectedSource.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/F9EligibilityAdapter.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/F9FeedbackRouter.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/OverlayCleanupBarrier.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/OverlayEventOriginDiagnostic.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/overlay/OverlayNativeWindowLifetime.kt`
- `app/src/test/java/kr/closeforyou/accessibility/action/F9ActionPolicyBridgeTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/action/F9AutoCloserTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/action/F9ResultFeedbackTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/F9FeedbackRouterTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/NativeOverlayCleanupTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/OverlayCleanupBarrierTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/PreBindingNativeIdentityTest.kt`
- `docs/A_F9_MAIN_4241_PR_PREPARATION_2026-10-25.md`
- `docs/A_F9_SERVICE_ASSEMBLY_PREPARATION_2026-10-25.md`
- `popup-test-app/src/main/java/kr/closeforyou/popuptest/PopupScenarioActivity.kt`

main 대비 전체 58개. 이 파일 목록은 stage 목록이 아니며 index는 비어 있다.
