# 최신 main 기반 A 비실행 통합 검증

## 기준과 보존

2026-10-22. 원격 `git fetch origin` 후 main은 요청값 `54b6bfdec79029ca441d483f91f20ef6d7aa4141`과 일치했습니다. 그 커밋에서 별도 worktree `/workspace/a-main-nonexecuting`, 로컬 브랜치 `local/a-main-nonexecuting-20261022`를 만들었습니다. main과 기존 F1/F9/통합 브랜치를 이동하거나 수정하지 않습니다. 원격 push·PR 생성/편집/병합과 Android 실측은 하지 않습니다.

반영한 A 입력은 다음과 같습니다. 원래 커밋을 모두 cherry-pick하거나 merge한 것이 아니라 검토한 A 소스의 최종 내용을 선별 반영했습니다. 파일별 출처는 마지막 목록에 기록합니다.

| A 입력 | 실제 원본 커밋 | 범위 |
|---|---|---|
| F1 #7 | `5db1dbf080febdfc59fb774e431a73e3a41798d2` | F0 보호/CheckedNodeRead/자원 정리·취소/window-frame/Runtime·OCR 현재성·기존 F1 계측 |
| F1 P3 | `2cc76548dbe1bb898e9fb6166aa3d13886ba26ff` | 실제 driver 순서의 cleanup 후 보고 취소 JVM 회귀·문서 |
| F9 #10 | `e77734428614719d5dc5f02f9cd10226d02d22c2` | d7652d7·5729ec1의 비실행 gate와 e777344의 읽기 전용 probe 준비까지. probe는 서비스에 미연결 |
| A 최초 통합 | `759ba4e` | A→B→C 구독 장벽·세션·비실행 executor·오프라인 TTS |
| A 오류 경계 | `6be4304` | 표시/제거 실패·오래된 선택 차단 |
| A C/D 어댑터 | `b66db1f0b6604636a0c71951ee14c82317c6ec7e` | C present/answer/generation, 서비스 연결별 D cleanup |
| A F5 신호 | `7c288cd1976bd6977a082115da39a14725131206` | 같은 수집의 snapshot/frame/F1 assessment에 한정한 표면 관측 |

main에는 B19·B20·B23·B24, C18, D25·D26·D27 등이 이미 반영돼 있습니다. 기존 의존 소스 복사 커밋 `1d94bfd`와 조립 커밋83e6908을 적용하지 않았습니다. main의 B/C/D·공통 모델·앱 build 설정/의존성·D 합성 앱·기존 C debug source를 바이트 그대로 유지합니다.

## 겹치는 파일 처리

Git merge 명령이나 강제 conflict resolution을 사용하지 않았습니다. main의 A 소스는 F1 분기 후 바뀌지 않았음을 확인한 뒤 검증된 A 최종 소스로 반영했습니다.

- `app/src/debug/AndroidManifest.xml`: main의 C preview Activity·오버레이 시험 서비스·기존 debug RECORD_AUDIO 선언과 기존 signature permission/capture provider를 유지하고, F1의 signature 권한 보호 `SyntheticDetectionTestProvider`만 추가했습니다. 이 provider는 debug 전용이며 기존 session/host ownership 검사를 유지합니다.
- `app/src/main/res/xml{,-v31}/accessibility_service_config.xml`: F1의 notification 관측 항목을 복원합니다. notification은 기존 F1 서비스처럼 payload를 읽지 않고 수집을 시작하지 않습니다. gesture capability는 false입니다.
- host Manifest는 기존 F1의 비공개 합성 Activity 등록 그대로입니다. 원본14개 화면·기대값·이전 계측 코드는 고치지 않았습니다.
- main의 `DEVELOPMENT_STATUS.md` 내용은 덮어쓰지 않고 이 문서 링크만 추가했습니다. 이전 A 고정 커밋 문서3개에는 과거 기록임을 표시합니다. 기존 B/C/D 실측표와 보고서는 변경하지 않았습니다.

## 안전성 점검

- F0 target/동일 앱 배경 root·숨김 자식 검사, 두 pass·공유2400방문/100ms 제한, malformed Rect의 전체 차단을 유지합니다. CheckedNodeRead의 불변 검사값만 DTO/지문에 재사용하며 salt/V1 인코딩/원본 golden은 그대로입니다.
- 정상 walk 반환에 한정한 ObservedProtectionPass는 capture에서 폐기되는 내부 관측입니다. 합성 CollectionAttempt/Projection 완료를 실제 F0/Ready 권한으로 연결하지 않습니다.
- 수집 시작/완료의 소유 package/window/display/frame·회전·창 목록 불일치 또는 읽기 실패·timeout·취소는 기존 fail-closed를 유지합니다. 경계를 보정하거나 누락 노드를 생략하지 않습니다. endpoint 일치가 원자성/미관측 ABA를 입증하지는 않습니다.
- 서비스 이벤트 무효화는 debounce 이전, 현재성 검사는 수집 및 OCR/발행 경계에서 수행합니다. Runtime의 operation identity·단일 발행/전달·원래 capture 기준5초 TTL은 그대로입니다. 서비스 해제/재연결·정책 철회·취소 후 이전 결과를 폐기합니다.
- replay=0의 최초 Candidate 이전 C 구독 장벽을 유지합니다. 같은 토큰 중복 Candidate/선택, 늦은 추출·TTS 완료, 이전 lease/서비스 세대의 callback은 거부합니다.
- C `FAILED/BLOCKED/Lost/REMOVE_FAILED` 또는 숨김 상태/세대 불일치는 A quarantine·세션 폐기로 끝납니다. 자체 오버레이 식별은 package 단독 제외가 아니며 창 metadata·새 창 집합·현재 token·양수 generation을 모두 요구합니다. 불확실한 실제 화면/사용자 이벤트를 무시하지 않습니다. ChoiceOrigin은 사용자 출처 인증이 아닙니다.
- D RuleStorage.cleanUp은 각 service Job에1회 IO 자식으로 실행합니다. 일반 예외는 enum 상태로 격리하고 취소/close는 결과 소유권부터 폐기합니다. 이전 세대의 늦은 완료/오류가 새 세대에 영향을 주지 않습니다. 비협력적 DB 작업 중단/이미 실행된 삭제 rollback은 보장하지 않습니다. 룰 저장/조회/자동 실행은 연결하지 않습니다.
- 현재 B 정책은 choice/token/후보 ID·role/label을 검증하며 A의 ReviewOnlyChoiceExecutor→F9 gate는 항상 NOT_EXECUTED/attempted=false입니다. productionReady는 상수false이고 실제 Android action port가 없습니다. 읽기 전용 probe는 포함하되 서비스에서 호출하거나 실행 허가로 쓰지 않습니다.

## F5와 최신 B20 호환성

A가 이미 검사한 snapshot className·원본 bounds·outline visible·실제 F1 assessment를 함께 사용합니다. 정확한 SurfaceView/TextureView/VideoView 보고 class만, 검증된 팝업 범위에서 관측합니다. 창 내부 Dialog의 배경 형제/다른 path·불명확한 pane·숨김/불완전/가려짐은 신호를 설정하지 않습니다. 추가 Android getter·재수집·pixel/audio 분석은 없습니다. false는 영상 부재 증명이 아니며 true도 실제 재생·움직임·광고 인증이 아닙니다.

이번에는 main의 **실제** DecisionVideoAd/DecisionCandidateAttacher/DecisionActionPolicy/DecisionChoiceHandler와 A pipeline·F1 detector·비실행 gate를 연결한 JVM 회귀4개를 추가했습니다. 기존22개 표면 회귀의 neutral 신호에도 B20 보고서를 대조했습니다.

- tree 광고 문구·직접 읽은 skip countdown·명시적 tree skip 버튼을 B20이 별개로 읽음. 표면 단독은 광고가 아님.
- 숨긴 표면은 광고 문구가 있어도 videoAd로 승격하지 않음. 읽지 않은 countdown은 추정하지 않음.
- A decision summary에서는 원본 노드/표면을 전달하지 않고 중요 결정 deny 유지.
- 현재 token의 명시적 skip도 NOT_EXECUTED/attempted=false, F9 productionReady=false. 토큰 변경 뒤 같은 선택은 stale로 거부.

B20은 이제 main에 포함되어 있으므로 이전 A_VIDEO_SURFACE_OBSERVATION의 "미포함"은 과거 기록입니다. 다만 C의 영상 전용 보고/안내는 아직 DecisionVideoAd.inspect()를 production 흐름에서 소비하지 않습니다. 현재 일반 분류/선택 안내를 영상 재생 안내 완성으로 보고하지 않습니다. 합성 JVM 통과는 Android 영상 탐지나 광고 정확도 실측이 아닙니다.

## 이번 로컬 검증

최초 새 worktree의 Gradle 실행은 SDK 경로가 없어 task 의존성 계산 전에 실패했습니다. local.properties/gradle.properties를 바꾸지 않고 명령의 ANDROID_HOME으로 기존 SDK를 지정했습니다. 이후 대상 JVM175개(앱84·host91), 실패/오류/skip0을 확인했습니다. C 기존 테스트의 ExperimentalCoroutinesApi opt-in 경고는 코드 오류가 아니며 B/C 원본을 고쳐 숨기지 않았습니다.

최종 전체 JVM은 **1513개**(앱1052·capture-test-host443·popup-test-app18), 실패/오류/skip0입니다. 신규 main 호환성 회귀4개를 추가했고 기존 A/F1 P3·F9·B/C/D 회귀를 재실행했습니다. Python QA 지표 회귀9개도 통과했습니다. Android 계측은 하나도 실행하지 않았습니다.

```bash
ANDROID_HOME=/workspace/android-sdk JAVA_HOME=/workspace/jdk17 ./gradlew --offline --no-daemon \
  :app:testDebugUnitTest --rerun :capture-test-host:testDebugUnitTest --rerun \
  :popup-test-app:testDebugUnitTest --rerun \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest \
  :popup-test-app:assembleDebug \
  :app:lintDebug :app:lintRelease :capture-test-host:lintDebug :popup-test-app:lintDebug
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 python3 tools/verify_capture_test_apks.py \
  --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
```

BUILD SUCCESSFUL. 앱 Debug/Release·호스트/D 합성 앱 Debug·앱/호스트 AndroidTest APK가 빌드됐습니다. 합성 앱 release는 기존 D 설정에서 비활성이고 AndroidTest 소스도 없으므로 그 APK를 검증했다고 보고하지 않습니다. Gradle의 별도 lintVitalRelease 생략은 기존 설정이며 명시적 app:lintRelease 검증은 완료했습니다. JVM skip은0입니다.

앱 Debug/Release·호스트 Debug Lint는0건입니다. D 합성 앱 Lint는 오류0·경고2건: `TypographyFractions` (`strings.xml:66`), `SetTextI18n` (`ScenarioListActivity.kt:53`). main 원본 소스를 바꾸거나 suppression을 추가하지 않았습니다. C 기존 테스트의 opt-in 컴파일 경고도 대상 재컴파일에서 확인했으며 오류로 오인하거나 숨기지 않았습니다.

패키징 검사와 추가 class_def 검사는 앱/호스트/D/AndroidTest APK 격리를 통과했습니다. 새 A runtime·B20·F9 probe/gate는 앱 Debug/Release에만 있고 JVM 테스트는 APK들에 없습니다. Release에 debug 시험 provider·C preview/test service 클래스가 없습니다. debug Manifest에서 A provider 하나만 제거하면 기존 main Manifest 구조와 정확히 같습니다. main/debug permission 선언과 APK를 대조했습니다. Debug RECORD_AUDIO는 main의 기존 C 시험 권한이며 A가 추가한 권한은 없습니다. Release RECORD_AUDIO와 모든 앱 APK의 INTERNET 권한은 없습니다. A 서비스는 마이크 입력을 생성하지 않습니다.

A production 소스에 performAction/dispatchGesture/performGlobalAction 호출이 없고, 원래 F0/geometry/100ms/원본14개 코드와 읽기 전용 gate는 고정 A 원본과 같습니다. B/C/D·공통 계약·앱 main Manifest·build/gradle.properties는 main과 같습니다. 공유 debug Manifest는 A provider만 추가했고 host Manifest는 원본 F1 등록을 복원했습니다. 공백 검사와 미추적 파일 검사도 통과했습니다. 기존 worktree3개를 변경하지 않았습니다. 이번 범위에서 추가 필수 코드 수정 없이 비실행 조건을 유지할 수 있었으며, Android 동작의 안전성 인증으로 확대하지 않습니다.

## 미완료·실측 범위

- Android 에뮬레이터·실기기·UI 활성화·실제 접근성 수집/선택/클릭 시험은 이번에 실행하지 않습니다. 클릭 호출0은 소스 구조·JVM 비실행 결과로 검토하며 기기 측 계측 수치로 주장하지 않습니다.
- 원본14개 UNOBSERVED 및 Scroll REVERSED_Y/Window-frame 문제는 미해결입니다. 기존 Prepared Dialog와 LayoutChange의 사용자 제공 API37 성공 기록을 이번 main 기준 APK의 성공으로 승계하지 않습니다.
- 물리적 화면의 F0 완전성·현재성·원자성·popup identity·토큰/지문/TTL 결합의 Android 증명, C 선택 출처 인증과 실제 창 제거·TalkBack 순서는 남습니다. 실패 quarantine의 사용자 복구·영상 전용 C 안내 등은 합의/실측이 필요합니다.
- 자동 클릭·제스처·자동 닫기·자동 반복·기억 저장은 OFF입니다. A 서비스는 마이크 입력 객체를 생성하지 않습니다. main에서 이미 제공하는 C debug preview의 마이크 permission/수동 시험 기능을 A가 새로 켜거나 호출하지 않습니다. Release에는 이 debug permission/컴포넌트를 넣지 않습니다.

## 실제 변경 파일143개

출처는 A 소스 최종 내용 기준입니다. 140개 A 입력 파일 중 아래4개 적응 파일을 제외한136개는 지정 A 커밋과 바이트를 대조했습니다. 공유 Manifest·개발 상태 링크·이 문서는 별도 합성 변경입니다.

| 경로 | 출처/이번 처리 |
|---|---|
| `app/src/debug/AndroidManifest.xml` | 이번 통합 |
| `app/src/debug/java/kr/closeforyou/accessibility/DetectionObservationHook.kt` | 2cc7654 |
| `app/src/debug/java/kr/closeforyou/accessibility/testing/DetectionTestSession.kt` | 2cc7654 |
| `app/src/debug/java/kr/closeforyou/accessibility/testing/SyntheticDetectionTestProvider.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/AccessibilityRuntime.kt` | 7c288cd |
| `app/src/main/java/kr/closeforyou/accessibility/AndroidScreenGeometry.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/AndroidSnapshotCollector.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/BoundsDiagnostics.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/CheckedNodeRead.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt` | 7c288cd |
| `app/src/main/java/kr/closeforyou/accessibility/CollectedTreeEvidence.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/CollectionAttempt.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/CollectionChildCountCheck.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/CollectionProjection.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/CollectionProtectionDiagnostic.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/CollectionResourceSafety.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/CollectionWindowFrameCheck.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/LiveEvidenceState.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/PopupCandidateDetector.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/ProtectionRootGuard.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/ScreenFreshness.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/SnapshotFingerprintCodec.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/action/ActionAttemptLedger.kt` | e777344 |
| `app/src/main/java/kr/closeforyou/accessibility/action/ActionGateModels.kt` | e777344 |
| `app/src/main/java/kr/closeforyou/accessibility/action/ActionGateValidator.kt` | e777344 |
| `app/src/main/java/kr/closeforyou/accessibility/action/AndroidTargetProbeSource.kt` | e777344 |
| `app/src/main/java/kr/closeforyou/accessibility/action/NonExecutingActionExecutor.kt` | e777344 |
| `app/src/main/java/kr/closeforyou/accessibility/action/ReadOnlyTargetProbe.kt` | e777344 |
| `app/src/main/java/kr/closeforyou/accessibility/capture/AndroidScreenshotManager.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/capture/CaptureOcrPipeline.kt` | 7c288cd |
| `app/src/main/java/kr/closeforyou/accessibility/capture/KoreanMlKitOcr.kt` | 2cc7654 |
| `app/src/main/java/kr/closeforyou/accessibility/capture/VideoSurfaceObservation.kt` | 7c288cd |
| `app/src/main/java/kr/closeforyou/accessibility/integration/ChoicePresentationBoundary.kt` | 7c288cd |
| `app/src/main/java/kr/closeforyou/accessibility/integration/NonExecutingChoiceSession.kt` | 7c288cd |
| `app/src/main/java/kr/closeforyou/accessibility/integration/OfflineChoiceSpeechEngine.kt` | 7c288cd |
| `app/src/main/java/kr/closeforyou/accessibility/integration/OwnedOverlayEventPolicy.kt` | 7c288cd |
| `app/src/main/java/kr/closeforyou/accessibility/integration/ServiceChoiceIntegration.kt` | 7c288cd |
| `app/src/main/java/kr/closeforyou/accessibility/integration/ServiceRuleCleanup.kt` | 7c288cd |
| `app/src/main/res/xml-v31/accessibility_service_config.xml` | 2cc7654 |
| `app/src/main/res/xml/accessibility_service_config.xml` | 2cc7654 |
| `app/src/release/java/kr/closeforyou/accessibility/DetectionObservationHook.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/BoundsDiagnosticsTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/CheckedNodeReadTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/CollectedTreeEvidenceTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/CollectionAttemptTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/CollectionContinuityTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/CollectionProgressTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/CollectionProjectionLimitsTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/CollectionProjectionTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/CollectionProtectionDiagnosticTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/CollectionResourceSafetyTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/CollectionWindowFrameCheckTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/DetectionTestSessionTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/F1SyntheticMatrixTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/LiveEvidenceStateTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/ObservedProtectionPassTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/PopupCandidateDetectorTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/ProtectionRootGuardTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/SnapshotFingerprintCodecTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/action/ActionAttemptLedgerTest.kt` | e777344 |
| `app/src/test/java/kr/closeforyou/accessibility/action/ActionGateTestFixture.kt` | e777344 |
| `app/src/test/java/kr/closeforyou/accessibility/action/ActionGateValidatorTest.kt` | e777344 |
| `app/src/test/java/kr/closeforyou/accessibility/action/NonExecutingActionExecutorTest.kt` | e777344 |
| `app/src/test/java/kr/closeforyou/accessibility/action/ReadOnlyTargetProbeTest.kt` | e777344 |
| `app/src/test/java/kr/closeforyou/accessibility/capture/CaptureOcrPipelineTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/capture/CaptureResourceLifetimeTest.kt` | 2cc7654 |
| `app/src/test/java/kr/closeforyou/accessibility/capture/VideoSurfaceObservationTest.kt` | 7c288cd + 실제 main B20 호환성4개 |
| `app/src/test/java/kr/closeforyou/accessibility/integration/ChoicePresentationBoundaryTest.kt` | 7c288cd |
| `app/src/test/java/kr/closeforyou/accessibility/integration/NonExecutingChoiceIntegrationTest.kt` | 7c288cd |
| `app/src/test/java/kr/closeforyou/accessibility/integration/PinnedChoiceContractTest.kt` | 7c288cd |
| `app/src/test/java/kr/closeforyou/accessibility/integration/ServiceRuleCleanupTest.kt` | 7c288cd |
| `app/src/test/resources/snapshot_fingerprint_v1_golden.properties` | 2cc7654 |
| `capture-test-host/src/androidTest/java/kr/closeforyou/servicetest/LayoutChangeReader.kt` | 2cc7654 |
| `capture-test-host/src/androidTest/java/kr/closeforyou/servicetest/MinimalGeometryReader.kt` | 2cc7654 |
| `capture-test-host/src/androidTest/java/kr/closeforyou/servicetest/OwnedIsolationGeometry.kt` | 2cc7654 |
| `capture-test-host/src/androidTest/java/kr/closeforyou/servicetest/PreparedDialogReader.kt` | 2cc7654 |
| `capture-test-host/src/androidTest/java/kr/closeforyou/servicetest/ScrollClipReader.kt` | 2cc7654 |
| `capture-test-host/src/androidTest/java/kr/closeforyou/servicetest/ServiceBaselineIsolationOptInTest.kt` | 2cc7654 |
| `capture-test-host/src/androidTest/java/kr/closeforyou/servicetest/ServiceDetectionScenariosOptInTest.kt` | 2cc7654 |
| `capture-test-host/src/androidTest/java/kr/closeforyou/servicetest/ServiceLayoutChangeOptInTest.kt` | 2cc7654 |
| `capture-test-host/src/androidTest/java/kr/closeforyou/servicetest/ServiceMinimalDialogOptInTest.kt` | 2cc7654 |
| `capture-test-host/src/androidTest/java/kr/closeforyou/servicetest/ServiceMinimalGeometryOptInTest.kt` | 2cc7654 |
| `capture-test-host/src/androidTest/java/kr/closeforyou/servicetest/ServicePreparedMinimalDialogOptInTest.kt` | 2cc7654 |
| `capture-test-host/src/androidTest/java/kr/closeforyou/servicetest/ServiceProtectionPriorityOptInTest.kt` | 2cc7654 |
| `capture-test-host/src/androidTest/java/kr/closeforyou/servicetest/ServiceScrollClipOptInTest.kt` | 2cc7654 |
| `capture-test-host/src/androidTest/java/kr/closeforyou/servicetest/SyntheticBaselineBoundsTest.kt` | 2cc7654 |
| `capture-test-host/src/main/AndroidManifest.xml` | 2cc7654 |
| `capture-test-host/src/main/java/kr/closeforyou/capturetest/DetectionScenarioActivity.kt` | 2cc7654 |
| `capture-test-host/src/main/java/kr/closeforyou/capturetest/MinimalBaselineActivity.kt` | 2cc7654 |
| `capture-test-host/src/main/java/kr/closeforyou/capturetest/MinimalDialogActivity.kt` | 2cc7654 |
| `capture-test-host/src/main/java/kr/closeforyou/capturetest/ProtectionPriorityActivity.kt` | 2cc7654 |
| `capture-test-host/src/main/res/values/detection_strings.xml` | 2cc7654 |
| `capture-test-host/src/main/res/values/priority_ids.xml` | 2cc7654 |
| `capture-test-host/src/main/res/values/strings.xml` | 2cc7654 |
| `capture-test-host/src/test/java/kr/closeforyou/servicetest/BaselineIsolationTest.kt` | 2cc7654 |
| `capture-test-host/src/test/java/kr/closeforyou/servicetest/CollectionDiagnosticOutputTest.kt` | 2cc7654 |
| `capture-test-host/src/test/java/kr/closeforyou/servicetest/DetectionAttemptOutputTest.kt` | 2cc7654 |
| `capture-test-host/src/test/java/kr/closeforyou/servicetest/DetectionComparisonTest.kt` | 2cc7654 |
| `capture-test-host/src/test/java/kr/closeforyou/servicetest/LayoutChangeContractTest.kt` | 2cc7654 |
| `capture-test-host/src/test/java/kr/closeforyou/servicetest/MinimalDialogContractTest.kt` | 2cc7654 |
| `capture-test-host/src/test/java/kr/closeforyou/servicetest/MinimalGeometryContractTest.kt` | 2cc7654 |
| `capture-test-host/src/test/java/kr/closeforyou/servicetest/PreparedMinimalDialogContractTest.kt` | 2cc7654 |
| `capture-test-host/src/test/java/kr/closeforyou/servicetest/ProtectionPriorityContractTest.kt` | 2cc7654 |
| `capture-test-host/src/test/java/kr/closeforyou/servicetest/ScrollClipContractTest.kt` | 2cc7654 |
| `capture-test-host/src/testShared/java/kr/closeforyou/servicetest/BaselineIsolation.kt` | 2cc7654 |
| `capture-test-host/src/testShared/java/kr/closeforyou/servicetest/CollectionDiagnosticOutput.kt` | 2cc7654 |
| `capture-test-host/src/testShared/java/kr/closeforyou/servicetest/DetectionAttemptOutput.kt` | 2cc7654 |
| `capture-test-host/src/testShared/java/kr/closeforyou/servicetest/DetectionGeometryOutput.kt` | 2cc7654 |
| `capture-test-host/src/testShared/java/kr/closeforyou/servicetest/DetectionObservationReadiness.kt` | 2cc7654 |
| `capture-test-host/src/testShared/java/kr/closeforyou/servicetest/LayoutChangeContract.kt` | 2cc7654 |
| `capture-test-host/src/testShared/java/kr/closeforyou/servicetest/MinimalDialogContract.kt` | 2cc7654 |
| `capture-test-host/src/testShared/java/kr/closeforyou/servicetest/MinimalGeometryContract.kt` | 2cc7654 |
| `capture-test-host/src/testShared/java/kr/closeforyou/servicetest/PreparedMinimalDialogContract.kt` | 2cc7654 |
| `capture-test-host/src/testShared/java/kr/closeforyou/servicetest/ProtectionPriorityContract.kt` | 2cc7654 |
| `capture-test-host/src/testShared/java/kr/closeforyou/servicetest/ScrollClipContract.kt` | 2cc7654 |
| `docs/ACTION_GATE.md` | e777344 |
| `docs/ACTION_READ_PREPARATION.md` | e777344 |
| `docs/A_API37_FINAL_VALIDATION_RUNBOOK.md` | 7c288cd |
| `docs/A_CD_CONTRACT_INTEGRATION.md` | 7c288cd + 과거 기록 링크 |
| `docs/A_CHOICE_ERROR_BOUNDARY.md` | 7c288cd |
| `docs/A_INDEPENDENT_SAFETY_COMPLETION.md` | 2cc7654 |
| `docs/A_MAIN_NONEXECUTING_VALIDATION.md` | 이번 통합 |
| `docs/A_VIDEO_SURFACE_OBSERVATION.md` | 7c288cd + 과거 기록 링크 |
| `docs/DEVELOPMENT_STATUS.md` | 이번 통합 |
| `docs/F0_COLLECTION_RESOURCE_SAFETY.md` | 2cc7654 |
| `docs/F1_BASELINE_ISOLATION.md` | 2cc7654 |
| `docs/F1_BOUNDS_INVESTIGATION.md` | 2cc7654 |
| `docs/F1_COLLECTION_ATTEMPT.md` | 2cc7654 |
| `docs/F1_DETECTION_VALIDATION.md` | 2cc7654 |
| `docs/F1_LAYOUT_CHANGE_TEST.md` | 2cc7654 |
| `docs/F1_MINIMAL_DIALOG_TEST.md` | 2cc7654 |
| `docs/F1_MINIMAL_GEOMETRY_TEST.md` | 2cc7654 |
| `docs/F1_OBSERVED_PROTECTION_PASS.md` | 2cc7654 |
| `docs/F1_PREPARED_MINIMAL_DIALOG_TEST.md` | 2cc7654 |
| `docs/F1_PROTECTION_GEOMETRY_CONTRACT_REVIEW.md` | 2cc7654 |
| `docs/F1_PROTECTION_PRIORITY_TEST.md` | 2cc7654 |
| `docs/F1_SCROLL_CLIP_TEST.md` | 2cc7654 |
| `docs/LOCAL_NONEXECUTING_INTEGRATION.md` | 7c288cd + 과거 기록 링크 |
| `docs/SNAPSHOT_FINGERPRINT_V1.md` | 2cc7654 |
| `docs/TEAM_INTEGRATION.md` | 2cc7654 |
| `synthetic-test-support/src/main/java/kr/closeforyou/synthetictest/DetectionScenario.kt` | 2cc7654 |
| `tools/generate_snapshot_fingerprint_goldens.py` | 2cc7654 |
| `tools/verify_capture_test_apks.py` | 2cc7654 |
