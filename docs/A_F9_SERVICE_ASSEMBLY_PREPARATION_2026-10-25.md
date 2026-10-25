# A F9 실제 서비스 조립 준비 — 2026-10-25

기준: fetch한 최신 main `406605cacd6731020cf9fdfc38a83486c7d4aee9`, #53 HEAD `c04ffb9737dfc5c4b04951a684df9e9721e84fb4`. #53은 이 main을 이미 포함한다. 새 detached worktree `C:\Users\qlwk7\.codex\worktrees\f9-service-assembly-prep\close_for_you_a_nonexecuting_20261022`에서만 수정했다. 이전 14개 분리본·#42·#29 원본은 수정하지 않았다. stage·commit·push·merge·PR 수정은 하지 않았다.

운영 자동 닫기·자동 SKIP·F12 자동 반복·REVALIDATING·제목 낭독은 OFF다. 서비스에 수집/판단 경로를 설치한 것과 운영 자동 클릭 활성화는 구분한다. 기존 `A_F9_MAIN_4241_PR_PREPARATION_2026-10-25.md`는 #53 생성 당시의 과거 기록이며, 현재 조립 상태는 이 문서를 따른다.

## 연결한 경로

`CloseForYouAccessibilityService`의 실제 `CollectionResult.Ready`/F1 감지 → private `F9CollectedSource` → B `DecisionCandidateWiring.source/extractor` → A `F9EligibilityAdapter` → B `DecisionActionPolicy` → 요청별 `F9PolicyBridge` → 같은 세션의 `F9AutoCloser` → B `onActionResult` → title 없는 `HandlingReport` → C `SpeechHandlingListener`.

- 실제 서비스의 연결/재연결 시 F9 연결을 생성하고, 기존 연결의 closer를 먼저 닫은 뒤 서비스 scope를 취소한다. 정책은 F9 서비스 세션당 하나이며 `SystemClock.uptimeMillis()`를 사용하는 A clock을 B 생성자에 전달한다. `decide`를 승인 후 다시 호출하지 않는다.
- 원본 수집 시작 시각을 snapshot에 기록한다. A Runtime과 private source의 만료도 그 시각 +5초다. OCR 완료/승인 시각/cleanup 대기 후 새 5초를 부여하지 않는다.
- 별도 application-window의 완전한 native 트리만 적격 후보로 지원한다. fresh Android F0 reader 결과와 원본 package/window/bounds/windows/nodes를 비교하고, 팝업 범위·전체 트리·원본 문구 보존·B 분류 검사·모든 대상의 팝업 내부 포함을 각각 확인해야 다섯 조건을 발급한다. `EligibilityCheck.entries`를 운영 경로에서 일괄 허가로 넣지 않는다. 부분/OCR 요약을 완전한 결정 문구 검사로 승격하지 않는다.
- private source에 보관하기 전 기존 OCR 파이프라인과 같은 **전체 트리 문구 결합 보호 검사**도 수행한다. 노드 사이에 나뉜 민감 문구를 먼저 실패 재현한 뒤 수정했다. 보호 앱·민감/불확실 문구는 B 입력으로 발행하지 않는다. 기존 공개 OCR 파이프라인의 필터는 그대로다.
- B 승인 실행에는 요청별 완료 소유권을 묶는다. F9의 초기 gate 거부·취소도 해당 승인을 소유한 bridge가 B에 정확히 한 번 전달한다. 다른 호출의 중복 사전 거부는 B 정책을 다시 조회하거나 진행 중 pending을 해제하지 않는다. B가 결과를 받은 뒤 C 보고를 만든다.
- C의 현재 `autoCloseConsentSource.current()`와 `autoCloseKinds`를 읽는다. 앱별 수집 허용/제외, pause, 사용자 조작, 해당 **분류가 현재 허용 범위에 있는지**, 음성 안내 준비 여부를 클릭 직전 다시 검사한다. 동의/프리셋 설정 변경은 기존 증거와 revision을 무효화한다. F12의 exact-rule per-app toggle을 최초 자동 닫기 동의로 사용하지 않는다.
- 독립 `F9ServiceExecutionFeature.enabled=false`를 추가 안전 관문으로 유지한다. C live=false와 함께 B에 전달되는 운영 autoCloseKinds를 비우고 최종 실행 gate도 차단한다. 자동 SKIP은 B allowAutoSkip=false + A CLOSE-only, F12 반복은 matchingRule=null/PRESET-only, REVALIDATING은 기존 false를 유지한다.
- 자동 결과 음성은 #51 C 리스너 하나만 사용한다. title=null, 직접 F9ResultFeedback 호출 없음, WAIT 운영 키 null, 새 팝업/제거/재연결/종료의 기록 초기화가 연결돼 있다. 과거 팝업의 시도 결과와 현재 화면 안내를 구분해 늦은 WAIT/ASK/ANNOUNCE는 버린다.

## #29 선택 이식과 현재 범위

원본에서 `OverlayCleanupBarrier`, native lifetime, 진단 코드 및 자원 해제 부품/43개 시험을 읽어서 새 worktree에 선택 이식했다. 장벽 본체의 fail-closed 판정은 유지했다. `CollectionResourceSafety`의 사용하지 않는 collector-progress 종속 메서드만 제외했다. `ChoicePresentationHealth`는 원래 부품에서 분리했으며 불확실성은 같은 프로세스의 재연결로 초기화되지 않는다.

C 최신 `AccessibilityChoiceOverlay`에 #29 native 관측 입구·attach/first-draw/before-remove/detach·remove 반환 관측을 추가했다. 기존 REVALIDATING, windowNumber, Compose 세션, 음성/버튼 기능은 제거하거나 이전 C 버전으로 덮어쓰지 않았다. `F9ServiceConnection`은 실제 C overlay 객체에 native observer를 등록하고, 장벽에 retirement intent/반환/HIDDEN/실제 detach/전체 window-list 부재를 전달할 경로를 구성한다. timeout·unknown·실패 시 복구 허가를 만들지 않는다.

서비스는 이벤트 처리 전 receipt를 관찰하고 **정상 처리 후에만** processed를 호출한다. 실패는 장벽을 fail로 만든다. 장벽/창이 준비되지 않으면 수집과 실행을 거부한다. native 제거 확인 후에만 새 F0 수집을 요청한다. cleanup 전에 얻은 snapshot을 기다렸다 재사용하지 않는다. 일반 화면 이벤트는 클릭 전 토큰을 무효화하지만, 이미 클릭한 결과 관측을 무조건 취소하지 않는다. 사용자 조작·설정·서비스 종료는 별도로 실행을 차단한다.

**아직 전체 #29 서비스 조립을 대체한 것은 아니다.** 자동 그래프는 C 선택 창을 띄우지 않는다. 수동 PopupChoiceFlow를 실제 시작하려면 #29의 OwnedOverlayTracker/선택 표시 admission/지연·출처 불명 이벤트·표시 수명(30초)/P01→P12 재진입 경로를 최신 C와 추가로 연결해야 한다. 공유 batcher와 native observer는 준비돼 있지만, 이것만으로 전체 #29 및 TalkBack 통합 검증이 끝났다고 주장하지 않는다. 운영 OFF를 유지하는 이유 중 하나다.

## 수정·추가 파일 (#53 HEAD 대비 23개)

경로는 저장소 루트 기준이다. 원본 #29 전체나 다른 팀원의 브랜치를 merge하지 않았다.

```text
app/src/main/java/kr/closeforyou/accessibility/AccessibilityRuntime.kt
app/src/main/java/kr/closeforyou/accessibility/AndroidSnapshotCollector.kt
app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt
app/src/main/java/kr/closeforyou/accessibility/CollectionResourceSafety.kt
app/src/main/java/kr/closeforyou/accessibility/DiagnosticIsolation.kt
app/src/main/java/kr/closeforyou/accessibility/action/F9AutoCloser.kt
app/src/main/java/kr/closeforyou/accessibility/integration/ChoicePresentationHealth.kt
app/src/main/java/kr/closeforyou/accessibility/integration/F9CollectedSource.kt
app/src/main/java/kr/closeforyou/accessibility/integration/F9EligibilityAdapter.kt
app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceConnection.kt
app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceSession.kt
app/src/main/java/kr/closeforyou/accessibility/integration/OverlayCleanupBarrier.kt
app/src/main/java/kr/closeforyou/accessibility/integration/OverlayEventOriginDiagnostic.kt
app/src/main/java/kr/closeforyou/ui/voice/overlay/AccessibilityChoiceOverlay.kt
app/src/main/java/kr/closeforyou/ui/voice/overlay/OverlayNativeWindowLifetime.kt
app/src/test/java/kr/closeforyou/accessibility/integration/F9ServiceSessionTest.kt
app/src/test/java/kr/closeforyou/accessibility/integration/NativeOverlayCleanupTest.kt
app/src/test/java/kr/closeforyou/accessibility/integration/OverlayCleanupBarrierTest.kt
app/src/test/java/kr/closeforyou/accessibility/integration/PreBindingNativeIdentityTest.kt
app/src/androidTest/java/kr/closeforyou/accessibility/action/F9SyntheticClickInstrumentedTest.kt
app/src/androidTest/java/kr/closeforyou/accessibility/integration/F9RealServiceOffInstrumentedTest.kt
popup-test-app/src/main/java/kr/closeforyou/popuptest/PopupScenarioActivity.kt
docs/A_F9_SERVICE_ASSEMBLY_PREPARATION_2026-10-25.md
```

## 검증

환경: JBR 21.0.11, Android API 37 x86_64 emulator-5554. 계측 APK 컴파일과 실제 실행을 구분한다.

| 검사 | 최종 결과 |
| --- | --- |
| app assembleDebug / assembleRelease | 성공 |
| app Debug / Release 전체 JVM | 864 / 849 통과, 실패·오류·skip 0 |
| capture-test-host / popup-test-app Debug JVM | 55 / 19 통과 |
| F9 관련 JVM | 58 통과: 기존 46 + 서비스 세션/보호 12 |
| #29 선택 이식 장벽/native JVM | 43 통과: barrier 23 + native 11 + pre-binding 9 |
| API 37 실제 계측 | 10 통과: 기존 F9 4 + 새 조립 커널 3 + B Android 2 + 실제 서비스 OFF 1 |
| app lintDebug / lintRelease | 오류 0, 기존 Gradle 의존성 경고 각 9 |
| popup-test-app lintDebug | 오류 0, 기존 경고 2 |
| git diff --check | 통과 |
| LG 등 실기기 / 실제 TalkBack 음성·포커스 | 미실시 |

JVM의 화면/노드/announcer는 fake다. 새 조립 커널 계측에서는 실제 Android reader, 실제 B wiring/classifier/policy, **새 A eligibility adapter**를 사용한다. 동의·실행 허용 입력만 androidTest fixture가 공급하며 운영 플래그를 켜지 않는다. 기존 #53 계측 일부는 여전히 합성 eligibility fixture를 쓰므로 새 adapter 검증과 구분한다.

- P01 실제 ACTION_CLICK·CLOSED_CONFIRMED, 같은 팝업 중복 거부, true 반환이지만 남는 팝업 STILL_PRESENT를 확인했다. 성공은 attempted + MECHANICAL_SUCCESS + CLOSED_CONFIRMED 세 조건뿐이다.
- 원본 TTL 4,999/5,000ms, 화면·사용자 조작·cleanup·종료 경쟁, 승인 직후 동의 철회/분류 허용 범위 축소/안내 준비 상실, 보호 앱·결정·사기·문구 누락·노드 불일치를 JVM으로 검사했다.
- 실제 P08 결제/P10 동의/P11 비밀번호/P18 혼합 광고는 Android F0 reader가 거부한다.
- 연속 팝업은 JVM에서 서로 다른 두 안전 팝업을 같은 정책으로 처리하고, Android P12에서는 첫 광고 시도 후 **결제 문구가 든 다음 공지에서 거부**함을 확인했다. 팝업 교체는 CLOSED_CONFIRMED가 아니며 동의 단계까지 자동 진행하지 않는다.
- P12의 일반 시나리오 제목 자체가 ‘동의’를 포함해 F0가 준비 화면부터 차단했다. 명시적 F9 deferred 시험 모드에서만 제어 화면 제목을 P12 F9로 중립화했다. 실제 광고/공지/동의 본문과 버튼, 기본 P12는 그대로다. 시험의 처음 잘못된 기대/실패 로그도 남겼으며 F0를 약화하지 않았다.
- 실제 AccessibilityService OFF 계측에서는 실제 수집→B 결정 count 증가와 자동 시도 0을 확인했다. 에뮬레이터에서 시험 앱만 임시 분석 허용하고, OCR/운영 자동화는 OFF로 유지했다. 서비스 활성 설정과 기존 SharedPreferences 전체를 정확히 복구해 비교했다. 첫 계측의 shell 인용 문제는 구조화한 Android Settings API로 수정했다.
- C 문장 생성/묶음 요청은 시험하지만 실제 음성 재생·사용자 청취·TalkBack 중복/포커스를 검증한 것은 아니다. 실제 native C 창을 띄웠다 제거하는 전체 서비스 회귀는 이번 계측에 포함되지 않는다.

최종 로그는 `%TEMP%/closeforyou-f9-service-final-checks.log`, `%TEMP%/closeforyou-f9-service-final-proof.log`다. 보호 실패 재현은 `closeforyou-f9-service-privacy-red.log`, 초기 서비스/P12 계측 실패 및 수정 이력은 같은 f9-service 접두사 로그에 남겼다. 전체 패치·파일 사본·해시·검증 요약은 작업 시작 시 보호 기록 폴더의 `prepared-service-assembly`에 보관한다.

## 남은 미연결·미검증

1. 수동 PopupChoiceFlow 실제 실행과 #29 전체 owned-event/presentation/display lifetime 조립. 기존 #29의 모든 미커밋 변경을 이식/검증했다고 주장하지 않는다. C overlay의 새 native hook은 JVM 부품 시험과 빌드를 통과했으나 실기기 native 창·TalkBack ON/OFF 회귀가 필요하다.
2. 별도 window의 완전한 tree 범위에 한정한 DECISION_TEXT_SCOPED 발급 근거와 요청별 결과 소유권에 대한 B/C 담당 검토. 운영 활성화 전에 지연·오래된 이벤트·window ID 재사용·사용자 경합의 실제 기기 통합을 추가 검증해야 한다.
3. 같은 창/WebView/canvas/전체 화면·OCR-only 자동 실행은 지원하지 않으며 fail-closed다. 지원 확대는 별도 근거/구현이 필요하다.
4. 운영 feature activation은 이번 범위가 아니다. 모든 운영 플래그는 계속 OFF이며 GitHub #53은 사용자가 관리한다.

보존 기준은 `C:\Users\qlwk7\AppData\Local\Temp\closeforyou-f9-service-prep-e71ef18783d94b189cadf5a303793a78`에 있다. 종료 시 #29 102개·#42 73개·이전 e38 분리본 14개 변경 파일의 해시, status와 HEAD가 시작 기록과 같은지 다시 확인한다.
