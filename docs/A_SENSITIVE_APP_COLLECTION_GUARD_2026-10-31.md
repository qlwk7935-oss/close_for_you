# A: 통합 앱 민감 앱 수집 전 차단

- 기준: `origin/integration/popup-helper` = `0b1510521f17d8dbfde338ee7e8c4da0e5dc1128` (fetch 후 동일).
- 작업: `/workspace/a-safety-guards`, `integration/a-safety-guards`.
- 사용자 승인 범위: 단순 엔진의 앱 허용/전체 트리 수집 시작 경계. 실제 기기 효과 미검증.

## 원인과 변경

기존 `SimpleAppAllowance`는 앱 선택 설정만 검사하여 `SensitiveAppRules`를 적용하지 않았다.
미선택 상태에서도 `helpAllAppsWhenUnset=true`가 전체 앱을 허용했다. 서비스가 이벤트 패키지를
검사한 뒤 debounce/재확인을 거치는 동안 활성 앱이 바뀌어도 `SimpleTreeCollector.read()`는
새 root의 텍스트·자식을 먼저 순회했다.

1. 기존 `HelpApps.kt`의 `SensitiveAppRules`를 변경 없이 재사용한다. 알려진 package 패턴은 먼저
   거부하고, 나머지는 실제 PackageManager 조회의 package/label을 확인하여 이름 규칙도 적용한다.
2. ALL/CHOSEN/기존 명시적 A 허용 목록 모두 민감 앱·사용자 제외·자체 앱·시스템/권한/입력기 제외가
   우선이다. 미선택은 전체 허용하지 않으며 기존 명시적 A 허용 목록만 인정한다.
   `pendingHelpPackages`만 있고 모드가 미선택인 경우도 전체/개별 허용으로 승격하지 않는다.
3. `AndroidSimpleAppLookup`는 현재 메타데이터를 조회하며 picker 목록을 캐시하거나 실패 시
   이벤트 문자열로 앱 이름을 만들어내지 않는다. 누락/예외/비활성 앱/package 불일치/빈 이름/
   class/package 문자열로의 이름 fallback은 거부한다. 실제 선언된 nonLocalizedLabel 또는 labelRes
   리소스만 사용하며 이를 읽을 수 없으면 거부한다. 예외 메시지를 기록하지 않는다.
4. 서비스가 받은 이벤트 패키지를 지연 작업에 고정 전달한다. 수집 시 실제 root 패키지가 같고
   현재 설정/패키지 정보로 다시 허용돼야 window/geometry/전체 노드 순회를 시작한다.
   root 패키지가 다른 허용 앱이더라도 묵시적으로 새 대상에 결합하지 않는다.
5. `SimpleCollectionGuard`는 Android collector와 JVM 테스트가 공유한다. 검사 전에는 root 획득과
   package getter만 사용한다. 거부/예외에도 획득한 root를 해제하며 텍스트·description·child·action을
   읽는 `collect`는 실행하지 않는다. 취소는 전파한다. 실패 표본을 허용 표본으로 바꾸지 않는다.

| 모드 | 비민감·식별 성공 앱 | 민감/식별 불명/사용자 제외 |
|---|---|---|
| ALL | 기존 시스템/입력기/자체 앱 제외 후 허용 | 차단 |
| CHOSEN | 기존 A 허용 또는 C pending 선택에 포함돼야 허용 | 선택돼도 차단 |
| 미선택 | 기존 명시적 A 허용에 포함된 앱만 허용 | 차단 |

이번 변경은 별도 동의 UI를 추가하거나 기존 앱 선택 이외의 동의 계약을 새로 정의하지 않는다.

## Android 11+ 패키지 공개 범위

main Manifest에 이미 `ACTION_MAIN` + `CATEGORY_LAUNCHER` queries가 있어 Chrome 같은 일반
launcher 앱을 조회할 수 있는 범위가 선언돼 있다. API 33 이상은 `ApplicationInfoFlags.of(0)`,
이전 API는 기존 int flags API를 사용한다. `QUERY_ALL_PACKAGES`와 INTERNET을 추가하지 않는다.

패키지 공개 범위/프로필/설치 상태 때문에 조회가 실패하면, 앱이 설치돼 보이더라도 허용하지 않는다.
설정에 저장된 package라는 사실만으로 조회 실패를 우회하지 않는다. 실제 PackageManager/OEM
동작과 정상 Chrome 흐름은 기기에서 별도 확인해야 하며 JVM fixture는 그 성공 증거가 아니다.

참고: [Android package visibility](https://developer.android.com/training/package-visibility),
[필요한 공개 범위 선언](https://developer.android.com/training/package-visibility/declaring).

## 변경 파일과 소유 영역

`simpleclose`는 기존 C 작성 엔진이다. 이번 사용자 지시로 아래 수집 보호 파일만 A가 보완했다.
C UI/TalkBack/분류·프리셋·클릭 및 닫힘 확인 함수는 변경하지 않는다.

- `app/src/main/java/kr/closeforyou/simpleclose/SimpleAppAllowance.kt`: 민감 앱·식별 불명 차단, 선택 범위.
- `app/src/main/java/kr/closeforyou/simpleclose/AndroidSimpleAppLookup.kt` (신규): 현재 Android 앱 정보 조회.
- `app/src/main/java/kr/closeforyou/simpleclose/SimpleCollectionGuard.kt` (신규): 순회 전 package 결합/허용 검사.
- `app/src/main/java/kr/closeforyou/simpleclose/SimpleTreeCollector.kt`: 전체 트리 read를 위 검사에 연결.
- `app/src/main/java/kr/closeforyou/simpleclose/SimpleCloseAccessibilityService.kt`: 이벤트 package 전달 및 실제 조회 연결.
- `app/src/main/java/kr/closeforyou/simpleclose/SimpleCloseEngineFeature.kt`: 암묵적 전체 앱 허용 스위치 제거.
- `app/src/test/java/kr/closeforyou/simpleclose/SimpleAppAllowanceTest.kt`: 모드·민감 앱·식별 실패 회귀.
- `app/src/test/java/kr/closeforyou/simpleclose/SimpleCollectionGuardTest.kt` (신규): 실제 공유 수집 경계 회귀.
- 이 문서.

## 검증 범위

수정 전 새 회귀 3개가 실패했다: 선택된 은행 앱, ALL 모드 비밀번호 관리자, 미선택 암묵적 전체 허용.
로그: `/workspace/a-safety-guards-red.log`. 수정 후 대상/전체 결과는 아래 최종 검증 기록에 구분한다.

회귀는 알려진 금융·결제·증권·비밀번호·인증 앱, 이름에만 민감 단서가 있는 앱, ALL/CHOSEN/미선택,
사용자 제외, 정상 Chrome, 조회 불가/예외/잘못된 결합을 검증한다. 공유 수집 경계에서 거부 시 순회 0회,
root 해제, 허용 이벤트 뒤 실제 앱 변경/조회 실패/설정 변경, 예외·취소를 검증한다.
추가 로그 호출이 없고 새 실패 경로는 원문/식별자/예외 내용을 출력하지 않는다.

## 최종 로컬 검증 (2026-10-31)

- 수정 전 신규 실패 재현: 3개 실패. 수정 후 관련 회귀 50개 통과
  (`SimpleAppAllowanceTest` 12, `SimpleCollectionGuardTest` 8, 기존 `HelpAppsTest` 30).
- 최종 소스 변경 후 앱 Debug JVM **1,023개**, Release JVM **1,008개** 재실행 통과.
  두 variant 모두 실패/오류/skip 0. 이번 작업의 신규 회귀는 17개이며 공통 회귀가 두 variant에서 실행된다.
- Debug/Release APK 빌드, AndroidTest APK 빌드 통과. 독립 capture-test-host와 driver APK도
  기존 AndroidTest 빌드 의존성으로 생성했다. **Android 계측 실행은 하지 않았다.**
- 앱 Debug/Release Lint 통과: 오류 0. 기존 미수정 UI/리소스 경고는 Debug 2개, Release 3개
  (`ModifierParameter`, `UnusedResources`, Release의 `MonochromeLauncherIcon`).
- `tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk`
  통과: host/driver/target DEX 격리 및 Release의 Debug probe 부재.
  별도로 두 APK의 실제 class_def에서 새 수집 guard와 SensitiveAppRules 포함을 확인했다.
- 실제 APK permission dump에 INTERNET/QUERY_ALL_PACKAGES 없음. Manifest 소스 변경 없음.
  Debug의 기존 RECORD_AUDIO 및 AndroidX 내부 receiver 권한은 그대로이며 신규 권한을 추가하지 않았다.
- `git diff --check`, CRLF-aware diff 검사 및 신규 파일 후행 공백 검사 통과.
  stage/커밋/푸시/PR/병합/기기 실행 없음.
- 기존 10개 worktree의 HEAD·status·미커밋 파일 SHA-256을 시작 상태와 비교해 보존을 확인했다.
  삼성 P01의 구조 검토 문서도 포함하여 변경하지 않았다.

실행 명령:

```bash
JAVA_HOME=/workspace/jdk17 ANDROID_HOME=/workspace/android-sdk PATH=/workspace/jdk17/bin:$PATH \
./gradlew --offline --no-daemon --max-workers=2 --continue \
  :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  :app:lintDebug :app:lintRelease
```

최종 실행 로그는 `/workspace/a-safety-guards-final.log`, 수정 전 실패 로그는
`/workspace/a-safety-guards-red.log`에 보존했다. 최종 빌드는 성공했고, 이전 결과를 새 재실행으로
바꿔 적지 않았다. Android PackageManager/패키지 공개 범위/OEM의 실제 동작과 실기기 효과는 미검증이다.

## 남은 범위와 한계

- 앱 단위 차단이다. **Chrome 내부의 은행·결제·로그인 웹페이지**까지 앱 이름으로 구분하지 못한다.
  웹페이지 내부 민감 화면 보호는 별도 작업이며 기존 F0/B/F9 정책을 변경하지 않았다.
- 기존 SensitiveAppRules는 고정 키워드 규칙이므로 미등록/위장/현지화된 앱의 누락과 오차단이 가능하다.
  PackageManager 정보 조회 성공은 비민감함이나 앱 진위의 완전한 증명이 아니다.
- 활성 root의 사전 결합 검사이며 원격 화면 전체를 원자적으로 잠그지 않는다. 수집 도중 교체의
  완전한 방지, 기존 클릭 전 개별 노드 조회/닫힘 확인/페이지 포커스 이동의 재설계는 이번 범위가 아니다.
  해당 함수와 실제 클릭 횟수/결과 정책은 변경하지 않았다.
- 두 접근성 서비스 동시 실행 방지는 다음 별도 작업이다. 이번 변경으로 해결됐다고 보고하지 않는다.
- 앱 메타데이터를 이벤트 및 수집 경계에서 다시 조회하는 비용이 있다. 기기 성능 개선/무영향을 주장하지 않는다.
- 실제 Android·삼성·LG·에뮬레이터 실행, 실제 클릭·닫힘 효과 확인은 수행하지 않는다.
