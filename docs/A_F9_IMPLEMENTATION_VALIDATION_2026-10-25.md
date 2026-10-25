# A F9 단발 실행기 — main 계약 호환 및 Draft PR 검증

## 범위와 판정

합성 P01에서 **실제 ACTION_CLICK과 CLOSED_CONFIRMED를 검증한 A 실행기**다. B 자동 정책·C 최초 팝업 자동 닫기 동의·실제 서비스 연결이 없으므로 **운영 자동 실행은 OFF**다. 이 PR을 운영 F9 전체 완성으로 해석하지 않는다.

기준 main은 `8c6326199e0b208ce56d6763352ee75f41c77cb8`이다. 이미 병합된 [#37](https://github.com/qlwk7935-oss/close_for_you/pull/37)과 [#38](https://github.com/qlwk7935-oss/close_for_you/pull/38)을 포함한다. 다른 팀원 브랜치를 병합하지 않았다.

기존 `fix/issue29-overlay-cleanup-barrier` 작업 폴더는 그대로 두고 별도 managed worktree의 `codex/f9-safe-action-executor`에서 준비했다. 시작 시 원본 변경 파일 102개를 백업·해시 기록했다. 원본의 #29 변경과 기존 F9 변경을 이번 worktree의 파일·검증 결과와 구분한다.

## PR 포함 파일 — 정확한 15개

경로는 저장소 루트 기준이다. 아래 파일만 개별 stage한다.

| 파일 | 이유 |
|---|---|
| `app/src/main/java/kr/closeforyou/accessibility/action/F9AutoCloser.kt` | 원본 5초 TTL, PRESET/EXECUTE 허가, AutoEligibility, 세션 단발 장부, 취소/종료, 클릭 후 관측 |
| `app/src/main/java/kr/closeforyou/accessibility/action/AndroidF9ScreenPort.kt` | 실제 창·트리·라벨·대상 재획득, F0 검사, native Button ACTION_CLICK 1회, 자원 해제 |
| `app/src/main/java/kr/closeforyou/accessibility/action/F9ContentSafety.kt` | 기존 PrivacyPolicy 재사용 및 결정/사기 문구의 추가 거부 검사 |
| `app/src/main/java/kr/closeforyou/accessibility/action/F9ResultFeedback.kt` | 공용 ActionResult/ClickOutcome에 따른 A→C 안내 포트 |
| `app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceConnection.kt` | 실제 설정·서비스 수명·Cleanup·정책을 연결할 포트. 기본 정책 unavailable, 최초 동의/프리셋 false |
| `app/src/main/java/kr/closeforyou/ui/voice/PressResultSpeech.kt` | 실제 시도 + CLOSED_CONFIRMED에서만 성공 안내 허용 |
| `app/src/main/java/kr/closeforyou/ui/voice/AnnouncementBatcher.kt` | 기계적 성공만으로 닫기/스킵 성공 집계 금지 |
| `app/src/test/java/kr/closeforyou/accessibility/action/F9AutoCloserTest.kt` | 단발/TTL/현재성/정책 출처/보호/취소/결과 검증 18개 |
| `app/src/test/java/kr/closeforyou/accessibility/action/F9ResultFeedbackTest.kt` | 결과 변환·확인 문구·기계적 반환과 관측 결과 분리 3개 |
| `app/src/test/java/kr/closeforyou/ui/voice/PressResultSpeechTest.kt` | outcome 없는 결과의 성공 안내 금지 |
| `app/src/test/java/kr/closeforyou/ui/voice/AnnouncementBatcherTest.kt` | 미확인 결과의 성공 집계 금지 |
| `app/src/test/java/kr/closeforyou/ui/voice/integration/PopupChoiceFlowTest.kt` | 합성 성공 결과에 명시적 outcome 부여, 미확인 클릭의 F12 저장/성공 안내 금지 |
| `app/src/androidTest/java/kr/closeforyou/accessibility/action/F9SyntheticClickInstrumentedTest.kt` | P01 실제 클릭·제거/남음, 운영 기본 OFF, 위험 합성 화면 거부 4개 |
| `popup-test-app/src/main/java/kr/closeforyou/popuptest/PopupScenarioActivity.kt` | P01 명시적 시험 옵션: 표시 지연, 클릭 후 팝업 유지. 기본 시나리오 유지 |
| `docs/A_F9_IMPLEMENTATION_VALIDATION_2026-10-25.md` | 이 범위·의존성·검증 보고 |

### 제외한 파일과 의존성 처리

- #29 Cleanup Barrier, native 창 수명, 오버레이, 수집기, 서비스, 이벤트/세대 무효화, QA 지원 코드·문서를 가져오지 않았다.
- 원본의 `PopupChoiceFlow.kt` 수정도 가져오지 않았다. main의 기존 흐름은 공용 ActionResult를 소비하므로 C의 결과 해석 두 파일만 수정했다. 새로운 boolean 닫힘 확인 우회 입력은 추가하지 않았다.
- #29의 `GateClock`을 복사하지 않고 F9 파일 안의 독립적인 `F9Clock`으로 분리했다.
- F9의 임시 eligibility/outcome 타입을 제거하고 **#38의 AutoEligibility, EligibilityCheck, ClickOutcome, ActionResult를 직접 사용**한다.
- 공용 core 계약, B 분류기/정책, C 동의 설정, Gradle, 서비스/매니페스트를 수정하지 않았다.

C 수정은 기계적 반환만으로 성공을 안내하거나 F12 반복 규칙을 저장하지 않도록 하는 필수 결과 처리다. D 수정은 모달 뒤 창이 숨겨지는 Android에서 사전 본문을 독립적으로 읽고, 반환 true이지만 팝업이 남는 상황을 실제로 검증하기 위한 시험 앱 옵션이다.

## 실행과 안전 조건

실제 실행 지점은 `AndroidF9ScreenPort.press()`의 `target.performAction(AccessibilityNodeInfo.ACTION_CLICK)` 한 곳이다. Main 스레드에서 전체 읽기와 동기식 최종 검증 후 정확히 한 번 호출한다.

- 분석 동의, 앱 허용, 별도 자동 동의, 광고 프리셋, 일시정지·상호작용·Cleanup 상태를 최종 시점에 확인한다. 기본값은 전부 실행을 허용하지 않는다.
- 토큰 전체(windowId/screenVersion/captureEpoch 포함), 서비스 수명, 정책 세대와 원본 수집 시각이 일치해야 한다.
- **TTL은 PopupSnapshot.capturedAtUptimeMillis부터 5,000ms 미만**이다. AutoEligibility의 capturedAt도 원본과 정확히 같아야 한다. 발급·재읽기로 TTL을 연장하지 않는다.
- B 승인에는 ALLOW_ONCE, 사유 없음, 동일 토큰/후보, **source=PRESET, next=EXECUTE**가 필요하다. legacy/user-choice/remembered-rule 결정을 최초 팝업 자동 승인으로 사용하지 않는다.
- #38의 다섯 검사 전부가 필요하다. A/B의 전체 팝업 결정 문구 검사 근거를 임의 발급하는 운영 구현은 없다.
- 읽기마다 PrivacyPolicy와 추가 결정/사기 검사, 입력칸·비밀번호 검사, 창/노드/깊이/글자 수 상한, **100ms 예산**을 적용한다.
- 현재 실행 지원 대상은 별도 application 창의 E2 라벨 있는 native Button 닫기다. 실제 노드의 경로·ID·역할·text·contentDescription·bounds·actions·표시·활성·클릭·입력 상태를 비교한다. 복수 닫기/스킵 후보는 거부한다.
- 좌표·제스처·Back·부모 우회·ACTION_DISMISS·실패 후 대체 클릭은 없다. 같은 창/WebView/라벨 없는 X/영상 자동 스킵은 이 실행 경로로 활성화하지 않는다.
- 남아 있는 접근성 오버레이, 불확실한 창/가림 상태, 변경 화면은 거부한다. native Cleanup의 외부 연결을 true로 강제하는 운영 조립은 없다.
- 노드/창 핸들은 동기식 스캔 안에서만 소유한다. 해제 실패 시 포트의 후속 사용과 성공 확인을 차단한다. 원본 화면/텍스트/앱 이름/토큰을 일반 로그에 기록하지 않는다.

모달 뒤 창이 목록에서 숨겨지면 독립적으로 읽은 사전 본문 `F9PageProof`가 필요하다. 이것도 5초 범위를 넘지 못하며, 결과 비교 근거일 뿐 현재 화면의 F0/DECISION_TEXT_SCOPED를 대신하지 않는다.

## 단발 제어와 공용 결과

서비스 세대·앱·구조/본문 지문의 원자적 장부와 동시 실행 제한을 사용한다. 같은 팝업을 새 popupInstanceId로 제출해도 재실행하지 않는다. 취소/실패 뒤 자동 재예약을 하지 않는다. 256개 상한에 도달하면 기록을 지워 재시도시키지 않고 차단한다.

ACTION_CLICK 호출 직전에 attempted를 설정한다. 반환 false/예외도 호출 후라면 실제 시도다. 클릭 전 취소는 실행하지 않고, 클릭 후 취소는 UNKNOWN을 한 번 보고하고 취소를 전파한다. B 결과 포트에는 실제 시도만 공용 ActionResult로 전달한다.

제한된 클릭 후 관측으로 아래를 구분한다. 재클릭은 없다.

| 결과 | 근거 |
|---|---|
| CLOSED_CONFIRMED | 같은 앱, 팝업 창 제거, 사전 본문 창/지문 복귀, 같은 화면, 새 창 없음 |
| STILL_PRESENT | 같은 팝업 창과 노드 구성이 실제로 남아 있음 |
| LEFT_APP | 다른 전면 앱 또는 확인된 기존 본문 변경. 그 앱의 세션 추가 자동 처리 차단 |
| UNKNOWN | 읽기 실패·취소·수명/동의 변화·새 창 정체 불명 등 증거 부족 |

공용 결과의 mechanical status와 outcome을 분리한다. C는 `attempted=true`와 `outcome=CLOSED_CONFIRMED`를 확인하며, 기계적 반환만으로 “닫았어요”를 사용하지 않는다. semanticSafetyConfirmedByUser는 자동으로 true가 되지 않는다.

## 검증 방법과 결과

JDK 21을 각 Gradle 명령 환경에서 지정했다. 프로젝트/JAVA_HOME 영구 설정은 변경하지 않았다.

호환성 시험을 먼저 추가한 뒤 main C의 status-only 성공 해석에서 실패 2개를 재현했다. 공용 outcome을 직접 해석하도록 수정한 후 통과했다. 원본 폴더에서 수행한 과거 결과를 이 PR의 결과로 승계하지 않고 worktree에서 다시 실행했다.

| 항목 | 이번 worktree 결과 |
|---|---|
| app assembleDebug / assembleRelease | 통과, Release unsigned APK |
| app assembleDebugAndroidTest / popup-test-app assembleDebug | 통과 |
| app testDebugUnitTest | 496 통과, 실패·오류 0 |
| app testReleaseUnitTest | 490 통과, 실패·오류 0 |
| capture-test-host testDebugUnitTest | 55 통과, 실패·오류 0 |
| popup-test-app testDebugUnitTest | 18 통과, 실패·오류 0 |
| app lintDebug / lintRelease | 각각 오류 0, 기존 의존성 버전 안내 경고 9개 |
| popup-test-app lintDebug | 오류 0, 경고 2개 |
| API 37 전체 app 계측 | 앞선 실행 19/19 통과. 최종 APK 재실행 18/19 통과, 기존 OCR 비트맵 해제 확인 1개 실패. F9 4개는 모두 통과 |
| API 37 TalkBack ON, F9 별도 시험 | 실제 touch exploration=1, 접근성 서비스 억제 없이 4/4 통과 |
| OCR 실패 분리 재실행 | 변경하지 않은 BundledKoreanOcrTest 3/3 통과. 실패한 전체 회차도 검증 제한으로 유지 |

Release JVM에서 과거 원본 폴더의 Debug 전용 #29 QA 의존성 오류가 나타나지 않는다. 그 QA 코드를 이 PR에 복사하거나 시험에서 제외한 결과가 아니라, 최신 main에서 독립적으로 빌드한 결과다.

전체 계측의 실패는 기존 `BundledKoreanOcrTest.kt:32`의 `bitmap.isRecycled` 즉시 확인이다. OCR 코드/시험과 프레임 수명 구현은 이 PR에서 변경하지 않았다. callback의 continuation 재개와 native lease 해제 순서에 따른 경쟁 가능성은 후속 조사 대상이며, 이번 실행만으로 근본 원인을 확정하지 않는다. 계측 전체가 항상 통과한다고 주장하지 않는다.

합성 계측은 별도 `popup-test-app`만 대상으로 한다. `androidTest`의 SyntheticPolicy만 시험 허가를 제공하며, main은 이를 기본적으로 거부한다. 실제 B 자동 정책의 운영 연결 시험이 아니다.

- P01: ACTION_CLICK 반환 true, 팝업 제거/동일 본문 복귀 → CLOSED_CONFIRMED, 중복 요청 거부.
- P01 유지 옵션: ACTION_CLICK 반환 true, 실제 팝업 유지 → STILL_PRESENT, 성공 문구 없음, 재시도 없음.
- 정책 없는 기본 실행기: 시험 동의가 모두 true여도 POLICY_UNAVAILABLE, attempted=false, 팝업 유지.
- P08/P10/P11/P18: 결제·동의·비밀번호·결제가 섞인 광고를 실제 트리 읽기에서 거부.
- 보호 앱/사기 의심·사용자 조작·취소 경쟁·토큰/세대/노드/TTL·LEFT_APP 잠금은 JVM에서도 검증한다.

TalkBack 시험은 접근성 서비스를 억제하지 않는 UiAutomation 옵션으로 TalkBack만 활성화한 격리 시험이다. 설정은 finally에서 원래 서비스 목록·활성 상태·터치 탐색 값으로 복원한다. 사람의 실제 청취·탐색, C overlay 전체 조립, 실기기는 미검증이다.

원본 폴더 재확인: 변경 파일 **102/102 SHA-256 일치**, 추가/삭제 없음, `git status --porcelain=v1` 일치, 원본 브랜치 `fix/issue29-overlay-cleanup-barrier` 유지. #29 파일을 stage/commit하지 않는다.

로컬 검증 로그는 TEMP의 `closeforyou-f9-pr-red.log`, `closeforyou-f9-pr-validation.log`, `closeforyou-f9-pr-device.log`, `closeforyou-f9-pr-talkback-device.log`, `closeforyou-f9-pr-ocr-isolation.log`다. GitHub에는 원본 화면·로그 대신 이 재현 명령과 수치만 기록한다.

재현 명령(합성 전용 에뮬레이터, 별도 popup-test-app/capture-test-host APK 설치 후):

```text
gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :capture-test-host:testDebugUnitTest :popup-test-app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :popup-test-app:assembleDebug :app:lintDebug :app:lintRelease :popup-test-app:lintDebug
adb shell am instrument -w -r -e f9Synthetic true -e syntheticHost true kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e f9Synthetic true -e f9KeepServices true -e class kr.closeforyou.accessibility.action.F9SyntheticClickInstrumentedTest kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner
```

## 운영 활성화 전 남은 B·C·A 연결

1. B의 자동 PRESET/EXECUTE 정책, 전체 결정/사기 문구 검사, 실제 onActionResult·반복/연쇄 제한을 연결한다. `DecisionActionPolicy`의 기존 사용자 선택 허가를 자동 허가로 재해석하지 않는다.
2. C HandlingPreset과 앱별 **최초 팝업 자동 닫기 명시적 동의**를 연결한다. 기존 F12 per-app 토글만으로 동의를 간주하지 않는다. 기본 OFF·즉시 철회/일시정지/앱 제외를 유지한다.
3. A의 원본 F1 증거, 현재 화면 전체 근거, 사전 본문 증거, 수명/사용자/정책 무효화, 실제 Cleanup Barrier/native 제거 상태를 서비스 조립에 연결한다. 이 PR에서는 새 실행기를 서비스 이벤트에 설치하지 않는다.
4. 연결 후 API 37 TalkBack OFF/ON에서 A→B→F9→C와 C overlay 제거·P01→P12 재진입을 통합 검증한다. 별도 #29 수정은 그 리뷰 범위에서 처리한다.

현재 Draft PR은 안전 실행기와 공용 계약 호환성을 리뷰하는 범위다. 운영 자동 실행을 활성화하거나 자동 병합하지 않는다.
