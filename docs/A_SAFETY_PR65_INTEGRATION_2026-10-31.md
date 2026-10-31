# A PR #65 — 최신 C 통합 코드와 수집 전 보호 병합

## 기준과 작업 상태

- 작업 위치: `/workspace/a-safety-guards`, 브랜치 `integration/a-safety-guards`.
- 기존 A HEAD: `f50d21fba16e323cdb32cf08ef128f1de1049ab9`.
- 공통 기준: `0b1510521f17d8dbfde338ee7e8c4da0e5dc1128`.
- fetch로 확인한 통합 브랜치: `193c6b06088644b76bab4b5a770c02b0bb36103b`.
- C 변경: `2f4145f`, `81749c3`, `68a5ac6`, `697d6b3`, `193c6b0`.
- `git merge --no-commit --no-ff origin/integration/popup-helper`로 로컬 병합.
  HEAD는 기존 A 커밋 그대로이며 MERGE_HEAD가 위 C 커밋이다. 충돌을 해결한 미커밋 병합 상태로 전달한다.
  커밋·푸시·PR 변경/병합·main 변경·기기 실행은 하지 않는다.

## 충돌 해결과 보존 근거

내용 충돌은 `SimpleCloseAccessibilityService.kt` 한 파일에서 발생했다.
`SimpleTreeCollector.kt`와 `SimpleCloseEngineFeature.kt`는 자동 병합 결과를 양쪽 기준과 대조했다.
한쪽 파일 전체를 선택하지 않고, C의 이벤트 기능을 A의 공유 예약 경계에 연결했다.

- 서비스의 `inspect(expectedPackage, trigger)`는 이벤트 package와 감지 원인을 별도로 받는다.
  actual root package 일치 및 최신 앱 설정/PackageManager 허용 검사는 기존 `Dispatchers.Default`
  수집 블록과 `SimpleCollectionGuard`에서 `walk()` 전에 실행한다.
- 이벤트에서는 busy/선택창/콘텐츠/포커스 제한과 순수 `isInScope()`만 수행한다. 앱 이름/패키지
  메타데이터 조회와 허용 캐시를 추가하지 않았다. `helpAllAppsWhenUnset` 삭제를 유지한다.
- 알려진 민감 앱, 이름으로만 식별되는 민감 앱, 조회 불가/식별 불명, 미선택/제외 앱은 기존 A 정책대로
  거부된다. 이벤트 당시의 허용 범위는 수집 권한이 아니며, 매 수집 직전 현재 설정을 다시 읽는다.
- `SimpleInspectionEvent`로 창/콘텐츠/TalkBack 접근성 포커스를 구분한다.
  포커스 이벤트는 prompt 또는 `jobs.inspecting`이면 무시한다. 그렇지 않으면 기존 300ms 제한하에
  debounce 없이 1회 검사한다. 진행 중인 첫 검사·worker 읽기·재확인 체인을 취소하지 않는다.
- 창 이벤트는 200ms 뒤 첫 검사, 이후 **각 직전 검사 종료 뒤** 200/500/1,000/2,000/3,000ms 재확인이다.
  수집 시간이 0인 가상 시계에서는 이벤트 이후 200/400/900/1,900/3,900/6,900ms에 읽는다.
  실제 기기에서 200ms 안에 감지가 완료된다는 보장은 아니다.
- 콘텐츠는 기존 1,500ms 제한 및 200ms debounce 후 단일 읽기를 유지한다. prompt 제한 2,000ms도 그대로다.
- `DETECTED_BY_*`, `DETECT_LATENCY_MS`, `DETECT_GAP_MS`와 검색 종료/쿨다운 초기화를 보존한다.
  지연 시작점은 예약 가능한 이벤트이며, 버린 이벤트는 검색 시작이나 새 화면 증거로 쓰지 않는다.
  재연결 때 포커스 스로틀과 진단 시각을 초기화하고 이전 scope/job 취소를 유지한다.
- `EngineJobs.busy`인 클릭/닫힘 확인/음성 작업은 어떤 이벤트 종류로도 취소하지 않는다.

C에서 들어온 28개 파일 중 위 공통 수정 3개를 제외한 **25개는 최신 C 커밋과 byte-identical**이다.
서비스의 `PopupRootFinder.find` 이후 분류·자동/수동 실행·클릭·닫힘 확인·포커스·TTS 본문도 C와 동일하다.
collector의 `read` 다음 포커스/실행 helper 역시 C와 동일하다.
A의 `SimpleAppAllowance`, `AndroidSimpleAppLookup`, `SimpleCollectionGuard`는 기존 A HEAD와 동일하다.

C 기능은 새로 재설계하지 않고 최신 코드를 반영했다: F3 여러 버튼 이름 보존, F4/F9 포커스 대상 및
fallback, F6 이미지 광고 판단, F7 시연 UNKNOWN 제한 규칙/금지 조건, F8 설정 동의, F11 TalkBack
결과 배너 및 TTS fallback. F12·공통 모델·B 분류·D·기존 A 엔진 정책은 별도로 수정하지 않았다.

## 회귀와 검증

포커스를 일반 창 이벤트처럼 예약하는 중간 통합 상태에서 신규 회귀 7개 실패를 먼저 확인했다.
포커스 전용 분기 연결 뒤 동일 회귀가 통과했다. 단순 타이머 mock만이 아니라 실제 서비스와 공유하는
`SimpleInspectionEvents` → `EngineJobs` → worker → `SimpleCollectionGuard` → `SimpleAppAllowance`를 사용한다.
Android PackageManager/포커스/TTS의 실제 실행 시험은 아니며 서비스와의 연결은 정적 대조했다.

- `SimpleInspectionEventsTest`: 기존 15개 유지·새 시각 반영, 신규 9개, 총 24개.
- 즉시 포커스 검사, 첫 debounce/재확인/worker 중 포커스, 300ms 제한, prompt 중 거부,
  포커스 예약 뒤 action 시작, 최신 설정/root/메타데이터 재검사, trigger·검색 종료, 재연결을 검증한다.
- 기존 action 확인·안내가 창/콘텐츠/포커스 이벤트에 취소되지 않는지 검증한다.
- A 민감 앱/미선택/ALL/CHOSEN/제외/조회 실패/순회 전 guard와 C의 이미지·분류·포커스·버튼 이름·프리셋
  회귀를 함께 실행: **215개, 실패/오류/skip 0**.

최종 전체 검증(이번 재실행):

| 검사 | 결과 |
|---|---|
| Debug 전체 JVM | **1,069개 통과**, 실패/오류/skip 0 |
| Release 전체 JVM | **1,054개 통과**, 실패/오류/skip 0 |
| Debug/Release APK | 통과 |
| AndroidTest APK | 빌드 통과, 변경 없는 host/driver/test 산출물은 Gradle UP-TO-DATE 재사용; 계측 실행 없음 |
| Debug/Release Lint | 오류 0; 기존 경고 Debug 2개, Release 3개 |
| APK/DEX | host/driver/앱 격리, Release Debug probe 부재, 두 앱의 A guard/예약 경계/C 결과 배너 class_def 확인 |
| 권한 | INTERNET·QUERY_ALL_PACKAGES 없음, 신규 권한 없음; Release debuggable 아님 |
| 공백 | 일반 및 CRLF-aware working/staged diff 검사 통과 |
| 다른 작업 보존 | 실제 다른 10개 worktree의 HEAD/status/미커밋 파일 해시 일치, 삼성 보호 문서 포함 |

전체 Gradle 결과 `BUILD SUCCESSFUL`, 197 tasks 중 40 executed / 157 up-to-date.
Debug/Release JVM은 둘 다 이번 최종 소스로 재실행했다. 이전 실행 결과를 이번 재실행으로 대체하지 않았다.
Lint 경고는 `ModifierParameter`, `UnusedResources`, Release `MonochromeLauncherIcon`이며 오류가 아니다.
C의 `DebugSimpleLog`는 기존 debuggable 검사로 Release에서 출력하지 않는다. main 소스의 고정 로그
코드/문자열 자체가 Release DEX에서 제거됐다고 주장하지 않는다. 새 개인정보 로그는 없다.

이번 통합 변경에 대한 코드 대조와 로컬 회귀에서 **새 P2 이상 회귀는 발견하지 못했다**.
이는 기존 C 시연 정책 전체 또는 기기 동작의 안전성을 증명한 결과가 아니다.
충돌 0개, 31개 변경 파일을 index에 반영한 **미커밋 병합 상태**로 남겼다.
HEAD는 `f50d21fba16e323cdb32cf08ef128f1de1049ab9`이며 커밋/푸시를 하지 않아 원격 PR #65는 갱신되지 않았다.

```bash
JAVA_HOME=/workspace/jdk17 ANDROID_HOME=/workspace/android-sdk PATH=/workspace/jdk17/bin:$PATH \
./gradlew --offline --no-daemon --max-workers=2 --continue \
  :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  :app:lintDebug :app:lintRelease
```

- 수정 전 실패 로그: `/workspace/a-safety-pr65-red.log`.
- 대상 회귀 로그: `/workspace/a-safety-pr65-targeted.log`.
- 전체 검증 로그: `/workspace/a-safety-pr65-full.log`.

## 변경 파일 (기존 A HEAD 대비)

C 변경 28개 + A 공유 이벤트 처리기/회귀 2개 + 이 결과 문서 1개 = 총 31개.
C 소유 코드는 사용자 요청 범위에서 병합했고 추가 수정을 이벤트/수집 연결에 한정했다.

- `app/src/main/java/kr/closeforyou/simpleclose/EngineJobs.kt`
- `app/src/main/java/kr/closeforyou/simpleclose/ImageDialogAdHint.kt`
- `app/src/main/java/kr/closeforyou/simpleclose/PopupMemory.kt`
- `app/src/main/java/kr/closeforyou/simpleclose/SimpleCloseAccessibilityService.kt`
- `app/src/main/java/kr/closeforyou/simpleclose/SimpleCloseDecider.kt`
- `app/src/main/java/kr/closeforyou/simpleclose/SimpleCloseEngineFeature.kt`
- `app/src/main/java/kr/closeforyou/simpleclose/SimpleInspectionEvents.kt`
- `app/src/main/java/kr/closeforyou/simpleclose/SimpleTrace.kt`
- `app/src/main/java/kr/closeforyou/simpleclose/SimpleTreeCollector.kt`
- `app/src/main/java/kr/closeforyou/simpleclose/TalkBackResultBanner.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/choice/ButtonNameDisplay.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/choice/ChoicePromptContent.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/settings/AutoCloseConsent.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/settings/AutoCloseConsentSection.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/settings/HandlingPreset.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/settings/HandlingPresetSection.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/settings/OnboardingDesign.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/settings/UiVoiceOnboardingScreen.kt`
- `app/src/main/res/values/ui_voice_strings.xml`
- `app/src/main/res/xml/ui_simple_close_service.xml`
- `app/src/test/java/kr/closeforyou/simpleclose/ImageDialogAdHintTest.kt`
- `app/src/test/java/kr/closeforyou/simpleclose/PopupMemoryTest.kt`
- `app/src/test/java/kr/closeforyou/simpleclose/SimpleCloseDeciderTest.kt`
- `app/src/test/java/kr/closeforyou/simpleclose/SimpleInspectionEventsTest.kt`
- `app/src/test/java/kr/closeforyou/ui/voice/choice/ButtonNameDisplayTest.kt`
- `app/src/test/java/kr/closeforyou/ui/voice/settings/AutoCloseConsentTest.kt`
- `app/src/test/java/kr/closeforyou/ui/voice/settings/HandlingPresetTest.kt`
- `app/src/test/java/kr/closeforyou/ui/voice/settings/PresetStorageAndTextTest.kt`
- `docs/c-notes/simple-close-engine.md`
- `docs/integration/popup-helper.md`
- `docs/A_SAFETY_PR65_INTEGRATION_2026-10-31.md`

## 남은 위험과 실측 범위

- **Android·삼성·LG·에뮬레이터 실행 없음.** C 문서의 이전 LG 결과를 이번 병합 실측으로 간주하지 않는다.
  특히 F9~F11은 C 문서에서도 JVM 검증 상태이며, 포커스 복귀/TalkBack live region 음성은 실제 기기 확인 필요.
- 앱 단위 민감 앱 보호다. Chrome 내부 결제·은행·인증 웹페이지 보호까지 해결하지 않는다.
  키워드 규칙 누락/오차단, Android 11+ package visibility 때문에 정상 앱이 조회 불가로 거부되는 위험이 남는다.
- PackageManager는 worker에서 매번 확인한다. 허용 캐시는 없지만 조회 비용은 남으며 Android 동기 호출을
  coroutine 취소가 즉시 중단하는 것은 아니다. 실제 감지 지연·이벤트 폭주 비용은 미검증이다.
- C의 F6/F7 시연 정책은 그대로다. 이미지 공지의 광고 오인 가능성 및 제한된 UNKNOWN 자동 닫기 허용을
  이번 A 변경에서 새 안전 보증으로 승격하지 않는다. 금지어/사기/중요 결정·클릭 재검사 조건을 유지한다.
- 원격 화면의 원자적 잠금, 수집 중 앱 교체 완전 방지, 두 접근성 서비스 동시 활성화 방지는 별도 문제다.
- 이번 로컬 통합 변경에서 발견되는 P2 이상 회귀는 검증 결과와 함께 보고한다. 기기 동작 성공을 주장하지 않는다.
