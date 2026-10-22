> 이 문서는 이전 고정 커밋 기반 작업 기록입니다. 최신 main 기반 상태·B20 호환성·검증 결과는 [A_MAIN_NONEXECUTING_VALIDATION.md](A_MAIN_NONEXECUTING_VALIDATION.md)를 참조하세요.

# A F5 영상 표면 관측 신호 — 로컬 비실행 통합

2026-10-22. 시작 HEAD `b66db1f0b6604636a0c71951ee14c82317c6ec7e`, 브랜치 `local/a-integration-nonexecuting-20261022`. Android 실측·원격 push·PR 변경은 하지 않습니다.

## 확인한 기존 경로와 B20 경계

기존 실제 `CaptureOcrPipeline`의 일반 tree-only/OCR 결과와 `DecisionEvidence`는 `PopupAnalysis.videoSurfaceObserved`를 설정하지 않아 기본 false였습니다. true는 `MockPopupFixtures.video()`의 합성 입력에서만 설정됐습니다.

B #20은 읽기 전용으로 조회한 `d8b5d4f9c1a0df8980f9f92c44a2356b938fc082`를 기준으로 검토했습니다. `DecisionVideoAd.inspect()`는 이 필드를 `videoObserved`로 읽고, 광고 관련 tree 문구 분류·직접 읽은 countdown·명시적인 tree skip 버튼을 별도 신호로 제공합니다. 표면만으로 광고를 판정하지 않습니다. 이 브랜치는 기존 B19를 유지하며 B20 소스/분류기 변경은 가져오지 않았습니다. C의 안내 흐름도 `DecisionVideoAd.inspect()`를 아직 호출하지 않으므로 B20 보고서→C 영상 전용 안내는 이번 완료 범위가 아닙니다. 다른 팀/공통 계약을 수정하지 않습니다.

## 실제 A 경로

`AndroidSnapshotCollector.capture()`는 기존 두 pass·CheckedNodeRead·geometry 거부·window/frame 전후 검사·정리 후에만 Ready(snapshot, frame)를 반환합니다. 수집기 코드는 바꾸지 않습니다. 서비스는 해당 하나의 Ready 안의 snapshot과 DetectionFrame, 해당 frame에서 실제 F1 detector가 반환한 assessment를 함께 `CaptureOcrPipeline.analyze(..., detectionFrame = result.frame, detectionAssessment = assessment)`에 전달합니다. 다른 수집의 outline이나 재수집으로 대체하지 않습니다.

pipeline은 기존 completeness/F0 preflight/민감·불명확 tree 검사·현재성 검사를 통과한 뒤 `VideoSurfaceObservation.observed()`를 호출합니다. 기존 decision summary 경로는 원본 노드/표면 신호를 추가하지 않고 false를 유지합니다. 일반 tree-only/캡처 거부 fallback과 성공 OCR 결과에는 같은 관측 boolean을 전달합니다. OCR privacy 차단은 기존대로 전체 analysis를 반환하지 않으며, 일반 tree fallback의 INCOMPLETE_EVIDENCE는 그대로입니다. OCR에서 decision summary로 바뀐 경우에도 표면 신호를 추가하지 않습니다.

helper는 다음을 요구합니다.

- 실제 POPUP_CANDIDATE/VALID assessment의 BOUNDED_WINDOW 또는 SEMANTIC_DIALOG 분기만 사용. OCR resolver의 window area만으로 F1 팝업 범위를 추정하지 않음. 일반 화면·불확실성·익명 pane은 false.
- COMPLETE snapshot, 기존 collector 상한1200개 이내의 노드와 같은 수의 outline.
- token window/structure digest, trigger, screen bounds 및 실제 target window bounds가 frame과 일치. target은 유일한 active APPLICATION이며 frame 안에 포함.
- node/outline path의 중복 없음, 모든 path와 원본 bounds 일치. path는 이 수집의 내부 대응값이며 출력·추가 보관하지 않음.
- 정확한 보고 className이 `android.view.SurfaceView`, `android.view.TextureView`, `android.widget.VideoView` 중 하나. 임의 substring/suffix, 텍스트나 contentDescription, reflection/클래스 로딩으로 추측하지 않음.
- 그 노드의 기존 outline `visible=true`, 양수 bounds가 기존 PopupRegionResolver가 확인한 팝업 영역 안에 그대로 포함. 창 내부 Dialog는 유일한 Dialog 노드 자체도 visible이고 그 자손 경로여야 함. 겹치는 배경 형제 노드·불명확한 full-screen/익명 pane·다중 Dialog·가려진 창은 신호를 설정하지 않음. 부분 경계 노출·빈 bounds도 신호를 설정하지 않고 clamp/보정하지 않음.

frame/assessment가 없는 기존 호출, 노드가 노출되지 않은 WebView/Compose/커스텀 subclass, 소유/geometry/대응 근거 부족은 false입니다. **false는 영상 부재 증명이 아닙니다.** helper가 true를 반환해도 해당 AccessibilityNodeInfo가 보고한 class·가시성·경계를 관측했다는 뜻뿐입니다. SurfaceView/TextureView는 카메라·게임·일반 렌더링에도 쓰이고, VideoView도 재생 중이 아닐 수 있습니다. 앱이 보고하는 접근성 className은 물리적 Android View 타입 인증도 아닙니다. 실제 영상 재생·움직임·광고·프레임 내용·음성·스크린샷·시청 상태를 확인하지 않습니다.

node class와 outline visible은 한 capture의 순차 getter 관측이지 원자적인 동일 frame 검증은 아닙니다. Runtime의 기존 토큰·현재성·원래5초 TTL 검사 이후에만 publish/extract/deliver되고 B19의 candidate attachment는 `copy(closeCandidates=...)`로 이 boolean을 유지합니다. 이 필드는 F0 완료 증명, 후보 생성 신호, 실행 허가 또는 TTL 갱신에 쓰이지 않습니다.

추가 Android getter·IPC·표면 탐색·전체 트리 pass·수집 예산/timeout 변경은 없습니다. helper의 비교는 최대1200개 DTO에 대한 선형 순회와 경로 prefix 비교·임시 map/set이며 수집 종료 이후 pipeline에서 실행합니다. 앱 데이터는 기존 수명 밖에 보관하거나 로그/파일/DB로 출력하지 않습니다. 원시 node/frame DTO는 새 객체에 저장하지 않고 반환값은 boolean뿐입니다.

## F11 확인과 실행 안전성

현재 비실행 범위의 기존 방어를 검토했고 중복 구현하지 않았습니다.

- `LiveEvidenceState`의 permit identity, 연결별 clear, 단일 version begin/publish/deliver, 원래 capture 기준5초 TTL, 취소/policy 재검사.
- 서비스의 ScreenFreshness·pending/evidence/expiry 취소·새 이벤트/연결 세대 무효화.
- `NonExecutingChoiceSession`의 current token/completeness/signal/TTL 검증과 lastDelivered 중복 차단.
- GuardedChoicePresenter의 A lease·C generation·한 번 소비·오래된/중복 callback 거부, 실패 quarantine 및 독립 자원 정리.
- ReviewOnlyChoiceExecutor의 NOT_EXECUTED/attempted=false, F9 productionReady=false. 실제 Android action port·자동 재시도·기억 규칙/마이크 활성화 없음.

기존 `NonExecutingChoiceIntegrationTest`의 중복 candidate/callback, 화면 변경, TTL, 늦은 evidence, 종료/재연결, TTS 경쟁과 `LiveEvidenceStateTest`의 중복 publish/deliver/기존 세대 결과 거부 회귀를 전체 JVM에서 재실행합니다. 이것은 현재 비실행 세션 보호의 확인이며 실제 반복 자동 실행·Android 이벤트 원자성 또는 완전한 F11 제품 검증이 아닙니다.

## 로컬 검증

JDK17에서 신규22개를 포함한 전체 JVM **1431개**(앱982·capture-test-host436·popup-test-app13), 실패/오류/skip0을 실제 확인했습니다. 신규 회귀는 production helper와 실제 PopupCandidateDetector를 함께 사용합니다. 알려진3개 class, 숨김/빈/부분 경계, frame/token 구조 대응, 중복/교체 path, 없는/거부/미지원 F1 판정, 동일 창 Dialog 배경 형제 배제(전체 화면 Activity와 inset Activity 모두), 실제 자손 포함, 불명확/다중 Dialog·occlusion·노드 상한, 캡처 OFF tree-only·TREE_SUFFICIENT·OCR 성공/실패, summary 비노출·F0 차단·취소·TTL·중복 발행/전달과 B19 attachment 후 신호 보존을 검사합니다. 기존 F11 회귀도 함께 재실행했습니다.

초기 신규 취소 회귀1개는 coroutine stack-trace recovery가 원래 CancellationException을 cause로 보존하는 경우를 고려하지 않은 assertSame 때문에 실패했습니다. 기존 pipeline의 취소 처리나 성공 기준은 바꾸지 않고, 원래 취소 객체 또는 그 객체를 직접 cause로 보존한 취소인지 검사하도록 해당 assertion만 수정했습니다. 최종 결과는 위1431개 통과입니다.

최종 실행 명령:

```bash
JAVA_HOME=/workspace/jdk17 ./gradlew --offline --no-daemon \
  :app:testDebugUnitTest --rerun :capture-test-host:testDebugUnitTest --rerun \
  :popup-test-app:testDebugUnitTest --rerun \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest \
  :popup-test-app:assembleDebug \
  :app:lintDebug :app:lintRelease :capture-test-host:lintDebug
```

BUILD SUCCESSFUL. 앱 Debug/Release, 앱·호스트 AndroidTest APK, 호스트/D 합성 앱 Debug 빌드가 통과했습니다. 앱 Debug/Release·호스트 Debug Lint 모두0건입니다. 최종 Gradle 로그에 신규 컴파일 경고/오류가 없습니다. `verify_capture_test_apks.py --release-apk ...`와 추가 DEX class_def 검사도 통과했습니다. helper는 앱 Debug/Release에만, 신규 JVM test는 모든 APK에서 제외됩니다. 기존 host/driver 격리를 유지하며 B20 DecisionVideoAd와 별도 F9 ReadOnlyTargetProbe는 이 APK들에 없습니다. Debug/Release에 RECORD_AUDIO/INTERNET 권한도 없습니다.

`git diff --check`와 신규 파일 공백 검사를 통과했습니다. B/C/D 원본·공통 모델·collector·F9 gate·Manifest·gradle.properties·원본/기존 독립 Android 계측은 시작 HEAD와 동일합니다. 기존 C/D 고정 의존20개 파일은 각 원본 커밋과 바이트가 같습니다. Android Studio·에뮬레이터·실기기 시험은 실행하지 않았습니다.

변경 파일은 다음5개이며 A 로컬 통합 브랜치에만 커밋합니다. 원격 push·PR 생성/수정/병합은 하지 않습니다.

- `app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt`
- `app/src/main/java/kr/closeforyou/accessibility/capture/CaptureOcrPipeline.kt`
- `app/src/main/java/kr/closeforyou/accessibility/capture/VideoSurfaceObservation.kt`
- `app/src/test/java/kr/closeforyou/accessibility/capture/VideoSurfaceObservationTest.kt`
- `docs/A_VIDEO_SURFACE_OBSERVATION.md`

## 남은 검증/합의

- B20은 이 로컬 브랜치에 포함되지 않았습니다. 해당 보고서와 C 안내를 연결하는 변경·표면 신호로 무엇을 말할지의 표현 합의가 남습니다. 이 boolean을 실제 재생 인증으로 취급하면 안 됩니다.
- 실제 SDK/앱별 접근성 className, 가시성, SurfaceView/TextureView/VideoView 노출 여부와 WebView/Compose/custom class 누락은 Android 미검증입니다. Android 검증 전 F5 탐지율/광고 분류 정확도 성공으로 보고하지 않습니다.
- 원본14개 UNOBSERVED, Scroll/Window 문제, F0 완전성·현재성·TTL·토큰/지문 결합의 기기 수준 미입증, C 선택 출처와 실제 창 제거 비원자성은 유지됩니다.
