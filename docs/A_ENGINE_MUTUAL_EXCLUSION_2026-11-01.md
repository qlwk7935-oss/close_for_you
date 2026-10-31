# A 엔진 동시 수집 방지 — 2026-11-01

## 범위와 기준

- 기준: `origin/integration/popup-helper`, `ed6c153410c75c2f2d0709f6be363080066d2c69`. fetch 결과 사용자 지정 SHA와 같았다.
- 작업: `/workspace/a-mutual-exclusion`, `integration/a-mutual-exclusion`. 커밋·푸시·PR·병합·기기 실행 없음.
- C가 시스템에서 활성화된 동안 A의 수집·분석·화면 캡처를 차단한다. C의 구현, 클릭, 설정 UI, TalkBack, F13은 수정하지 않는다.
- Debug와 Release 모두 적용하는 A 보호 기능이다. 새 로그, 공통 계약, 권한, UI를 추가하지 않는다.

## 활성화 판정과 Android 근거

정확한 C component는 `kr.closeforyou/kr.closeforyou.simpleclose.SimpleCloseAccessibilityService`이며 축약형 `kr.closeforyou/.simpleclose.SimpleCloseAccessibilityService`도 같은 대상으로 처리한다. A는 `kr.closeforyou/kr.closeforyou.accessibility.CloseForYouAccessibilityService`다. 화면의 앱 이름이나 서비스 label로 비교하지 않는다.

`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`의 콜론 구분 component 목록을 읽는다. 패키지와 전체 클래스가 모두 일치해야 C로 판정한다. 정확한 C 항목이 있으면 다른 항목이 잘못됐어도 A를 차단한다. C가 없고 모든 항목이 유효할 때만 C OFF로 판정한다. 빈 문자열은 빈 목록이며, null·조회 예외·잘못된 형식은 UNKNOWN으로 차단한다.

근거:

- [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES](https://developer.android.com/reference/android/provider/Settings.Secure#ENABLED_ACCESSIBILITY_SERVICES): 활성 접근성 제공자 목록. [AOSP Android 15 고정 태그의 Settings.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/provider/Settings.java)에서 `@Readable` 및 목록 계약을 확인했다.
- [Settings.Secure.getUriFor](https://developer.android.com/reference/android/provider/Settings.Secure#getUriFor(java.lang.String)), [ContentResolver.registerContentObserver](https://developer.android.com/reference/android/content/ContentResolver#registerContentObserver(android.net.Uri,boolean,android.database.ContentObserver)): 해당 설정 URI의 변경 통지를 등록한다. 설정을 쓰거나 서비스를 강제 활성화하지 않는다.
- [AccessibilityManager.getEnabledAccessibilityServiceList](https://developer.android.com/reference/android/view/accessibility/AccessibilityManager#getEnabledAccessibilityServiceList(int))도 검토했다. [같은 AOSP 태그 구현](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityManager.java)은 관리 서비스 부재 및 RemoteException 후에도 빈 목록을 반환할 수 있다. 빈 목록을 C OFF의 보조 증명으로 쓰지 않는다. 이번 구현은 이 API를 호출하지 않는다.
- [ContentObserver 고정 태그 구현](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/database/ContentObserver.java)은 지정 Handler에 통지를 전달한다. 설정 변경 순간과 A가 통지를 처리하는 순간은 같다고 보장되지 않는다.

**시스템 활성과 실제 바인딩은 다르다.** C가 설정에 있으면 아직 연결 중이거나 장애로 바인딩되지 않았어도 A는 중단한다. C 항목이 없다는 관측은 C의 native 창·작업 정리가 끝났다는 인증이 아니다. A가 비활성·미연결이면 시스템 설정을 바꾸거나 A를 시작하지 않는다. C UI의 활성 서비스 판정 코드는 참고만 했고 의존성을 만들지 않았다.

## 처리 순서와 차단 경계

1. A 연결 시 gate는 닫힌 상태다. 정확한 설정 URI의 observer를 등록하고 `Dispatchers.IO`에서 최초 설정을 읽는다. 이벤트마다 전체 설정 또는 PackageManager를 조회하지 않는다.
2. 설정 통지를 서비스 main에서 받으면 조회를 기다리지 않고 gate를 닫는다. 진행 중인 capture 예약, evidence/OCR coroutine, TTL 작업을 취소한다. detector 기준, geometry 기준, Runtime token·분석을 폐기하고 기존 Invalidated를 전달한다.
3. 설정 조회는 한 번에 하나만 실행한다. 조회 중 여러 통지는 합치고, 중간에 통지가 도착한 조회 결과는 폐기한 뒤 새로 확인한다. 주기적 polling·delay·화면 재수집은 추가하지 않는다.
4. A 이벤트 입구, debounce 후, F0 직전·직후, fresh 화면 검증 직전·직후, pipeline 현재성 검사, publish 직전에서 gate 및 기존 조건을 적용한다. C 활성 중에는 새 수집을 예약하지 않는다. touch 시작/종료 상태만 갱신하고 앱을 읽지 않는다.
5. 스크린샷 capability·요청 및 실제 Android API 호출 직전에도 같은 gate를 사용한다. 늦은 캡처/OCR 결과는 기존 현재성·취소 검사에서 거부하고 기존 buffer/frame 해제 경로를 유지한다.
6. C OFF를 확인해도 수집을 자동 시작하지 않는다. 이후 **새 접근성 이벤트**가 와야 하며, 최신 A 동의·일시정지·허용 앱·입력 상태 및 기존 F0 보호를 통과해야 한다. 과거 epoch, snapshot, token, 후보, 수집 시각을 복구하지 않는다.
7. 재연결·unbind·destroy에서 구독을 해제하고 이전 observer/callback/조회 결과를 무효화한다. 등록·해제·무효화 처리 실패는 같은 service instance에서 차단을 유지한다. 실패를 숨겨 재개하거나 설정을 강제로 초기화하지 않는다.

`AEngineExclusion`의 connection/revision은 **로컬 callback 현재성** 용도이며 Android native generation 증명이 아니다. 기존 5초 TTL, F0 보호 순회, capture 동의·허용 앱, B/F9 계약, 취소 전파는 유지한다. A에서 C로 실행 권한을 넘기는 코드나 외부 클릭 코드는 추가하지 않았다.

## 전환 및 조회 실패의 한계

- 통지가 A에 도달하기 전 또는 main thread의 동기 F0 조회 중에는 변경 인지가 늦을 수 있다. 이 구현은 시스템 설정 변경과 원격 수집 사이의 원자적 mutex가 아니다. 통지 처리 후에는 새 예약/게시를 막고 기존 결과를 무효화한다.
- 이미 시작한 Android screenshot IPC나 ML Kit native Task를 물리적으로 즉시 중단한다고 주장하지 않는다. coroutine 취소·후속 처리 차단·늦은 결과 폐기와 기존 자원 해제를 적용한다. 동기 호출은 원래 반환/정리 경계까지 진행할 수 있다.
- 설정 조회 실패/UNKNOWN이면 새 통지 또는 정상 재연결 전까지 A가 계속 차단될 수 있다. 자동 polling으로 재개하지 않는다. observer 등록·해제 실패 시 같은 인스턴스는 재개하지 않는다. unregister 자체가 실패하면 Android 측 해제 완료는 인증할 수 없으며, 닫힌 callback과 세대 검사로 후속 처리를 차단한다.
- C OFF 직후 C의 실제 unbind/cleanup 완료는 관측하지 않는다. 실제 기기의 통지·바인딩 순서와 빠른 ON/OFF 전환은 별도 실측이 필요하다.
- 권한 조회를 생략하는 허용 캐시는 없다. 저장하는 값은 observer로 무효화되는 엔진 gate뿐이며, 앱 동의·허용 목록은 기존 경로에서 계속 확인한다.

## 변경 파일

| 파일 | 변경 목적 |
|---|---|
| `app/src/main/java/kr/closeforyou/accessibility/EnabledEnginePolicy.kt` | 정확한 full/short component 비교, UNKNOWN 차단 |
| `app/src/main/java/kr/closeforyou/accessibility/AndroidEnabledEngineSource.kt` | 읽기 전용 설정 조회 및 observer 자원 관리 |
| `app/src/main/java/kr/closeforyou/accessibility/AEngineExclusion.kt` | 수명주기·변경 중 차단·IO 조회·오래된 callback 거부 |
| `app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt` | 실제 A 수집·분석·게시·종료 경계 연결 |
| `app/src/main/java/kr/closeforyou/accessibility/capture/AndroidScreenshotManager.kt` | Android 캡처 호출 직전 gate 재검사 |
| `app/src/test/java/kr/closeforyou/accessibility/EnabledEnginePolicyTest.kt` | component/실패 판정 회귀 |
| `app/src/test/java/kr/closeforyou/accessibility/AEngineExclusionTest.kt` | 취소·동의·재연결·실패·비동기 전환 회귀 |
| `app/src/test/java/kr/closeforyou/accessibility/capture/EngineExclusionPipelineTest.kt` | 기존 실제 evidence pipeline과 gate 결합 회귀 |
| `docs/A_ENGINE_MUTUAL_EXCLUSION_2026-11-01.md` | 근거·결과·실측 인계 |

## 로컬 검증

신규 20개 JVM 회귀는 A 단독, C 단독/동시 활성, UNKNOWN, 설정 조회 중 차단, 작업 취소·token 폐기, C OFF 후 동의/일시정지 재검사, 재연결/종료 구독 해제, 늦은 callback, IO 조회, OCR 취소/프레임 정리, 과거 snapshot 재사용 거부를 확인한다. 실제 서비스가 사용하는 `AEngineExclusion`, `ScreenFreshness`, `CaptureOcrPipeline`을 이용한다. Android ContentResolver·binder·service lifecycle 자체는 JVM fake port로 대체되므로 실기기 검증으로 간주하지 않는다.

첫 대상 실행: 신규 20개 + 기존 `CaptureOcrPipelineTest` 26개 = **46개 통과**.

최종 코드로 아래 작업을 실제 로컬 실행했다. 이전 작업의 테스트 수치를 재사용하지 않았다.

| 검사 | 결과 |
|---|---|
| Debug 전체 JVM | 1,092개 통과, 실패·오류·skip 0 |
| Release 전체 JVM | 1,077개 통과, 실패·오류·skip 0 |
| Debug/Release 앱 APK | 빌드 통과 |
| 앱 AndroidTest APK | 컴파일·빌드 통과; 실행하지 않음 |
| 기존 별도 capture-test-host 앱/driver APK | AndroidTest task 의존 빌드 통과; 실행하지 않음 |
| Debug/Release Lint | 오류 0, 경고 각각 2/3개 |
| APK/DEX 격리 | 기존 검사 스크립트 통과. Release에 debug probe/합성 구현 없음 |
| 새 보호 코드 패키징 | Debug/Release 모두 gate/source/policy 정의 존재, 신규 JVM 테스트 클래스 없음 |
| 권한 | manifest 변경 없음. 두 APK에 INTERNET·QUERY_ALL_PACKAGES·WRITE_SECURE_SETTINGS 없음 |
| 공백 | 일반 및 CRLF-aware `git diff --check` 통과, 신규 파일 후행 공백 검사 통과 |
| 보존 | 기존 11개 worktree의 HEAD/status 및 미커밋 보존 파일 SHA-256 일치 |

Lint 경고는 변경하지 않은 `OnboardingDesign.kt`의 `ModifierParameter`, `ic_launcher.xml`의 `UnusedResources`, Release `ui_ic_launcher.xml`의 `MonochromeLauncherIcon`이다. 기존 `AutoDecisionSpeech.kt` 타입 추론 및 기존 coroutine 테스트 opt-in 컴파일 경고도 남아 있다. 새 A 파일의 Lint 오류/경고는 없다. Debug의 기존 RECORD_AUDIO 및 합성 시험 signature permission, 공통 내부 receiver permission은 그대로다.

전체 Gradle 결과는 `BUILD SUCCESSFUL`(197 tasks: 174 executed, 23 up-to-date)이었다. 초기 패키징 검사 1회는 Release 빌드 완료 전에 실행해 APK 부재로 중단됐다. APK 생성 후 다시 실행한 검사는 모두 통과했다. Gradle의 `lintVitalReportRelease`/`lintVitalRelease` 중복 task는 SKIPPED였으며 명시적으로 요청한 **전체 `lintRelease`는 실행·통과**했다. 기기 계측과 실제 C ON/OFF 시험은 수행하지 않았다.

추가 P2 이상 결함은 이번 정적 검토·JVM 검증에서 확인하지 못했다. Android/OEM 통지 지연 및 이미 발행된 native 작업의 동작을 검증했다는 의미는 아니다.

```bash
JAVA_HOME=/workspace/jdk17 ANDROID_HOME=/workspace/android-sdk PATH=/workspace/jdk17/bin:$PATH \
./gradlew --offline --no-daemon --max-workers=2 --continue \
  :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  :app:lintDebug :app:lintRelease

python3 -B tools/verify_capture_test_apks.py \
  --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
git diff --check
```

## 재호에게 인계할 실기기 확인

이번 작업에서 기기·에뮬레이터 실행은 하지 않았다. 설정 UI에서 사용자가 직접 A/C를 전환하고, 각 조합에서 다음을 확인해야 한다.

| 조건 | 확인할 결과 |
|---|---|
| A ON / C OFF, 동의·허용 앱·재개 충족 | 이후 새 화면 이벤트에서 A 기존 수집 경로 동작 |
| A OFF / C ON | A 서비스가 자동 활성화되지 않음, C 기존 동작 |
| A ON / C ON | C 설정 관측 후 A 수집·캡처·후보 게시 중단, C 기존 동작 |
| A 분석/OCR 중 C ON | 기존 결과/token 무효화, 늦은 결과로 후보 게시하지 않음 |
| C OFF, A 살아 있음 | 새 이벤트부터만 재개, 이전 후보 재사용 없음 |
| C OFF, A 동의 없음/일시정지/대상 허용 해제 | 계속 차단 |
| 빠른 C ON/OFF, A OFF/ON 반복 | 이전 callback 결과가 새 연결을 허용하지 않음, 구독 중복/누수 없음 |

A 상태 UI의 `connected`는 서비스 바인딩 상태이므로 수집 허용 여부와 같지 않다. 새 개인정보 로그나 별도 UI는 추가하지 않았다. 실제 두 서비스 동작, native 캡처 반환 순서, OEM observer 전달 및 C 종료 지연은 **미검증**이다.
