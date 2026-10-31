# A 개발자 화면: 엔진 상태와 실제 F0 시도 횟수

## 범위

- 기준: `origin/integration/popup-helper`, `8dd26e435d30c284d426af2fe8927dc09acc1070`.
- 작업: `/workspace/a-engine-status`, `integration/a-engine-status`. 기존 worktree와 보호 문서는 보존한다.
- 기존 C 설정 화면의 **개발자 화면 열기**가 여는 A `MainActivity.CoreSetupScreen`만 표시를 보완한다. C 서비스·오버레이·설정 화면·TalkBack 구현은 변경하지 않는다.
- Debug/Release의 기존 개발자 화면에서 확인하는 기능이다. 일반 로그, 영구 저장, 새로운 권한, Android 조회, 화면 수집, 폴링은 추가하지 않는다.

## 표시 의미

| 표시 | 실제 근거 |
|---|---|
| C에게 양보 중 | A 서비스가 연결되어 있고, 기존 `EnabledEnginePolicy`와 `AEngineExclusion`이 C의 정확한 component 활성화를 확인하여 A gate를 닫음 |
| 수집 중 | A 연결, 최신 C OFF 관측, 수집 동의·재개·허용 앱 존재, 사용자 조작 중 아님. **새 이벤트 대기 포함**. 특정 앱의 수집 성공이나 OCR 성공을 의미하지 않음 |
| 일시정지 | A 미연결, 엔진 조회 대기/실패/UNKNOWN, 동의 없음, 일시정지 설정, 허용 앱 없음, 사용자 조작 중 |

`AEngineExclusion`이 기존 조회 결과의 connection/revision 검증을 마친 뒤 관찰자에게 알린다. 변경 통지·중단 시 UNKNOWN을 알리므로 이전 C OFF 관측으로 수집 중 표시를 유지하지 않는다. UI에서 설정 스위치만으로 엔진 상태를 합성하지 않는다. 관찰자 예외는 gate의 허용/취소/정리 결과를 변경하지 않는다.

`A 수집 시도: N회 (이번 A 서비스 연결 이후)`는 A 서비스의 두 `collector.capture()` 호출 경계에서 증가한다.

1. 이벤트 debounce와 기존 현재성/동의/gate 검사 후 최초 F0.
2. 캡처·OCR 전후 `verifyScreen()`의 실제 재검증 F0.

수집 결과가 Blocked이거나 예외·취소가 나도 호출했다면 한 번 센다. 예약만 했거나 기존 사전 검사에서 반환하면 세지 않는다. 재검증을 포함하므로 이벤트 수·팝업 수·완료 분석 수와 같지 않다. OCR 영역이 0개여도 F0를 호출했다면 증가한다. 기존 `최근 완료된 분석에서 읽은 이미지 글자 영역`과 별도 표시한다.

횟수는 C ON/OFF, 개발자 화면 재진입, Activity 재생성으로 초기화하지 않는다. A가 실제 새로 연결되면 0으로 초기화한다. A 해제 뒤에는 마지막 연결의 횟수를 남기며 상태는 일시정지다. 프로세스 종료 시 소멸하며 시간 구간별 통계나 영구 이력은 아니다.

## 안전성 및 한계

- 상태와 카운터는 관찰 전용이다. 수집·스크린샷·OCR·게시 조건에서 참조하지 않으며 token, epoch, timestamp, TTL, 취소 순서를 변경하지 않는다.
- 앱별 개인정보·민감 앱 차단 및 실제 root 확인은 기존 F0 경로가 수행한다. 수집 중 표시가 모든 앱의 수집 허용이라는 뜻은 아니다. C의 기존 `SimpleCollectionGuard`도 수정하지 않는다.
- C 활성 판정은 시스템 설정 관측이며 C 실제 바인딩·화면 렌더링·native 작업 종료의 인증이 아니다. 설정 통지 지연과 이미 진행 중인 동기 Android 호출의 반환 지연은 기존 한계로 남는다.
- C OFF 확인은 수집 가능 상태로 전환할 뿐, 새 수집을 자동 실행하지 않는다. 새 허용 이벤트 이후 실제 호출 시에만 횟수가 늘어야 한다.
- 횟수로 캡처 완료·OCR 완료·클릭 성공을 주장할 수 없다. 추가 외부 클릭·화면 조작 경로가 없다.

## 변경 파일

1. `app/src/main/java/kr/closeforyou/accessibility/AEngineActivity.kt`: 상태·연결별 시도 횟수 관찰.
2. `app/src/main/java/kr/closeforyou/accessibility/AEngineExclusion.kt`: 이미 검증한 엔진 관측 결과 전달.
3. `app/src/main/java/kr/closeforyou/accessibility/AccessibilityRuntime.kt`: 프로세스 내 관찰 인스턴스 제공.
4. `app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt`: 연결/중단/설정/입력 상태와 두 F0 진입점 연결.
5. `app/src/main/java/kr/closeforyou/app/MainActivity.kt`: A 개발자 화면의 상태 한 줄·횟수·정확한 의미 표시.
6. `app/src/test/java/kr/closeforyou/accessibility/AEngineActivityTest.kt`: 실제 exclusion controller와 관찰 경계 회귀.
7. `docs/A_ENGINE_DEVELOPER_STATUS.md`: 이 문서.

## 로컬 검증

최종 코드로 다음을 로컬에서 실행했다. 이전 작업의 테스트 결과를 재사용하지 않았다. JVM은 Android 설정 통지 및 LG 실제 수집을 검증한 결과가 아니다.

| 검사 | 결과 |
|---|---|
| 신규 `AEngineActivityTest` | 15개, Debug/Release 모두 통과 |
| 기존 exclusion/policy/pipeline + 신규 관찰 회귀 | 최종 전체 실행에 포함된 35개, 두 빌드 모두 통과 |
| Debug 전체 JVM | 1,169개, 실패·오류·skip 0 |
| Release 전체 JVM | 1,154개, 실패·오류·skip 0 |
| Debug/Release 앱 APK | 빌드 통과 |
| 앱 및 기존 독립 host AndroidTest APK | 컴파일·빌드 통과, 기기 실행 없음 |
| Debug/Release Lint | 오류 0, 경고 각각 4/5개. 변경 파일의 경고 없음 |
| APK/DEX | 기존 격리 스크립트 통과. 관찰 클래스는 양쪽 앱에 포함, JVM 테스트 클래스는 미포함 |
| 권한 | 매니페스트 변경 없음. 두 APK에 INTERNET·QUERY_ALL_PACKAGES·WRITE_SECURE_SETTINGS 없음 |
| 공백 | 일반 및 CRLF-aware diff 검사, 신규 파일 후행 공백 검사 통과 |
| 보존 | 기존 12개 worktree의 HEAD/status 및 미커밋 파일 해시 동일 |

Lint 경고는 수정하지 않은 `OnboardingDesign.kt`, `ic_launcher.xml`, `TalkBackResultBanner.kt`와 Release `ui_ic_launcher.xml`에서 발생했다. 기존 `AutoDecisionSpeech.kt` 타입 추론 및 coroutine 테스트 opt-in 컴파일 경고도 남아 있다.

첫 대상 실행은 신규 14개 + 기존 20개 = 34개 통과였다. 이후 실제 `CaptureOcrPipeline`을 사용하는 OCR 0개/수집 횟수 회귀 1개를 추가한 최종 전체 실행이 위 수치다. 해당 회귀의 Android 수집·스크린샷은 모의 경계이며 실제 기기 실행으로 해석하지 않는다.

```bash
JAVA_HOME=/workspace/jdk17 ANDROID_HOME=/workspace/android-sdk PATH=/workspace/jdk17/bin:$PATH \
./gradlew --offline --no-daemon --max-workers=2 --continue \
  :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  :app:lintDebug :app:lintRelease

python3 -B tools/verify_capture_test_apks.py \
  --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
git diff --check
git -c core.whitespace=cr-at-eol diff --check
```

Gradle 결과: `BUILD SUCCESSFUL` (197 tasks, 176 executed, 21 up-to-date). 최종 검증 후 소스·테스트 추가 수정은 없으며 이 결과 문서만 보완했다.

## LG에서 확인할 항목 — 이번 작업에서는 미실행

설정은 사용자가 UI에서 직접 조작한다. 개발자 화면에서 A 동의·허용 앱·재개가 충족되고 A 서비스가 연결된 상태를 전제로 한다.

1. C ON: 개발자 화면에서 `C에게 양보 중`과 횟수 N 확인. 허용 앱에 새 이벤트가 발생한 뒤 돌아와도 N이 증가하지 않는지 확인.
2. C OFF: A 연결을 유지한 채 `수집 중` 확인. 허용 앱에서 새 화면 이벤트 후 돌아와 횟수가 증가했는지 확인. 이미지 글자 영역이 0개여도 수집 시도 증가 여부를 별도로 확인.
3. C 다시 ON: `C에게 양보 중`, 이후 새 이벤트에도 횟수 불변 확인.
4. C OFF 상태에서 동의 철회·일시정지·허용 앱 제거 시 `일시정지` 확인. 수집 성공을 표시만으로 판정하지 않는다.
5. A 서비스 OFF 시 `일시정지`, A 재연결 시 횟수 초기화와 C 활성 상태 재확인 확인.

실제 C ON/OFF 통지 지연, TalkBack 조작 중 상태 전환, LG 화면 표시와 수집 횟수는 실기기 미검증이다. 최초 구현·로컬 검증 단계에서는 커밋·푸시·병합·기기 실행을 하지 않았다.

## 최종 코드 리뷰 및 전달

별도 요청으로 최종 리뷰 후 이 문서에 열거한 7개 파일만 작업 브랜치에 커밋·일반 푸시하고, 통합 브랜치를 대상으로 PR을 준비한다. 병합과 기기 실행은 하지 않는다.

- P0/P1/P2/P3에 해당하는 신규 결함은 이번 정적 리뷰에서 발견하지 못했다. 이는 기기에서의 동작을 인증한 결과가 아니다.
- C 활성화/UNKNOWN을 관측하면 기존 gate가 먼저 닫히고 예약·evidence·TTL 작업과 token/기준 후보를 무효화한다. 최초 F0 및 재검증 F0의 앞뒤 현재성 검사, 비동기 결과 게시 전 검사는 그대로다. C OFF는 최신 기존 조건을 만족하는 새 이벤트만 수집 가능하게 한다.
- 모든 관찰 상태 쓰기는 실제 서비스 main 경로다. ContentObserver는 main Handler를 사용하고, IO 설정 읽기가 끝나면 service-main coroutine으로 돌아와 connection/revision을 확인한 후 알린다. F0와 evidence preflight도 service-main에서 호출된다. 이 호출 경로에서 카운터 갱신 사이의 suspension이나 병렬 쓰기를 발견하지 못했다.
- 두 F0 진입점에서 실제 collector 호출을 정확히 한 번 감싼다. 결과·예외·취소는 그대로 전달하고, 예약·관찰 상태 전환만으로는 횟수가 늘지 않는다. 연결 때 초기화, 해제 때 상태 중지, C 전환 때 횟수 보존을 확인했다.
- 기존 `PrivacyPolicy`, collector의 실제 root 패키지 검사, `SimpleCollectionGuard`, C 서비스·오버레이·공통 계약·매니페스트는 변경하지 않았다.
- 최종 리뷰 시작 때 7개 파일의 SHA-256이 검증 기록과 모두 일치했다. **소스·테스트 추가 수정과 테스트 재실행 없이** 위 Debug 1,169개/Release 1,154개 및 빌드·Lint·APK/DEX 결과를 재사용했다. 리뷰 결과 기록을 위해 이 문서만 보완했으며 공백 검사는 다시 수행한다.
- 설정 통지가 도착하기 전 또는 이미 실행 중인 동기 Android 호출 중에 C 전환을 원자적으로 감지한다는 보장은 없다. 실제 LG C ON→OFF→ON과 A 재연결 시험은 여전히 필요하다.
