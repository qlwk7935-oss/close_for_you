# A: 수집 전 민감 앱 차단의 이벤트 비용 보완

- 작업: `integration/a-safety-guards`, `/workspace/a-safety-guards`.
- 기준 HEAD: `0261fe29e785647ade8fb7435598173709c9a877`.
- 범위: C 작성 `simpleclose` 엔진의 이벤트 예약 및 수집 허용 경계. 사용자 요청으로 A가 보완.
- 커밋·푸시·PR·병합·기기 실행 없음. 실제 기기 성능 및 Chrome 동작은 미검증.

## 확인된 문제

기존 순서는 이벤트 package → `allowsApp()` → `screenVersion` 증가 → busy/prompt/content 제한이었다.
`allowsApp()`는 `getApplicationInfo()`와 앱 이름 리소스 조회를 동기 실행하므로, 결국 무시할 이벤트도
메인 스레드에서 조회했다. 실제 지연 시간이나 ANR 발생 여부를 실측한 것은 아니다.

기존 예약 코드를 동일한 순서로 `SimpleInspectionEvents`에 분리하여 서비스에 연결한 뒤, busy·prompt
제한·content 제한 세 회귀가 모두 실패함을 확인했다. 각각 버릴 이벤트인데 허용 검사 콜백이 1회
실행됐다. 이 콜백의 당시 서비스 연결은 전체 `allowsApp`이었다.

## 변경

1. 이벤트 기본 조건(null/서비스 상태/종류/자체 package)은 기존 서비스에서 확인한다.
2. 공유 예약 경계에서 busy, 기존 2,000ms prompt 제한, 기존 1,500ms content 제한을 먼저 확인한다.
   여기서 버린 이벤트는 앱 범위 검사도 실행하지 않고 진행 중인 inspection/action을 취소하지 않는다.
3. 남은 이벤트에는 `SimpleAppAllowance.isInScope()`만 호출한다. 이 함수는 패키지 형식, 자체/시스템/
   입력기/사용자 제외, 알려진 민감 package, ALL/CHOSEN/기존 명시적 허용 목록만 검사한다.
   PackageManager와 앱 이름 조회는 없으며 **수집 허가가 아니다**.
4. 기존 300ms debounce와 window 재확인 간격 500/1,000/2,000/3,000ms, content 단일 읽기를 유지한다.
   예약 후에도 action/prompt 상태를 다시 확인한다. action의 닫힘 확인/TTS 작업을 inspection으로 취소하지 않는다.
5. 실제 수집은 기존 `withContext(Dispatchers.Default)`에서 수행한다. `SimpleCollectionGuard`는
   event/root package가 같은지 확인하고, 현재 설정으로 전체 `allowsApp()` 검사를 통과해야 순회한다.
   매 수집의 실제 root에 대해 현재 앱 정보·이름을 조회한다. 허용 결과·앱 메타데이터 캐시는 추가하지 않았다.

`SimpleCollectionGuard`, `SimpleTreeCollector`, Android 메타데이터 조회 구현은 변경하지 않았다.
이벤트 이후 앱 교체, 제외/선택 설정 변경, 조회 실패, 이름으로 식별되는 민감 앱은 수집 전 차단된다.
이벤트 범위를 통과했지만 이름 검사에서 거부되는 앱은 예약까지는 가능하며, 표본을 발행하거나 노드를
순회할 권한은 얻지 않는다. 읽기 실패의 기존 bounded 재확인 동작도 유지하며 성공까지 무한 반복하지 않는다.

`screenVersion`은 이제 예약 경계를 통과한 이벤트에서만 증가한다. 버린 이벤트에 대한 불필요한 증가를
하지 않으며, 이 값은 민감 앱 허용 증거가 아니다. 기존 결과/token을 재사용하거나 시각·TTL을 갱신하는
코드는 추가하지 않았다. 이 단순 엔진의 기존 token 생성·분류·클릭·닫힘 확인·음성 함수는 변경하지 않았다.

## 변경 파일

- `app/src/main/java/kr/closeforyou/simpleclose/SimpleAppAllowance.kt`: 무조회 범위 검사와 전체 허용 검사 분리.
- `app/src/main/java/kr/closeforyou/simpleclose/SimpleCloseAccessibilityService.kt`: 이벤트 연결 및 백그라운드 전체 검사 유지.
- `app/src/main/java/kr/closeforyou/simpleclose/SimpleInspectionEvents.kt` (신규): 서비스/JVM이 공유하는 기존 예약 흐름.
- `app/src/test/java/kr/closeforyou/simpleclose/SimpleInspectionEventsTest.kt` (신규): 버릴 이벤트의 조회 차단·예약·현재성 회귀.
- 이 문서.

## 회귀 근거

- 수정 전 3개 실패 → 수정 후 같은 3개 통과.
- 신규 회귀 총 15개. 기존 허용/수집 guard/EngineJobs와 함께 대상 Debug 회귀 39개 통과.
- window 읽기 시각은 가상 시계 기준 300/800/1,800/3,800/6,800ms. content는 300ms에 한 번.
- 제한된 content 및 알려진 민감 package 이벤트가 진행 중인 Chrome 재확인 체인을 취소하지 않는다.
- 새 window 이벤트의 기존 pending 작업 교체, debounce 중 prompt 발생, action 확인·안내 유지,
  취소/연결 교체 후 오래된 작업 폐기를 확인한다.
- 이벤트 이후 root 교체·모드 변경·제외 추가·앱 이름 보호·조회 실패에서 순회가 차단된다.
  첫 수집이 허용됐어도 다음 재확인에서 설정 변경을 다시 검사한다.
- JVM worker dispatcher에서 메타데이터 콜백이 이벤트 스레드와 다른 스레드에서 실행되는지 검증한다.
  이는 Android PackageManager/OEM의 실제 성능 시험이 아니다. 서비스의 실제 연결은 기존
  `Dispatchers.Default` 수집 블록과 공유 guard를 정적으로 대조했다.

수정 전 로그: `/workspace/a-safety-event-cost-red.log`.
대상 회귀 로그: `/workspace/a-safety-event-cost-targeted.log`.
전체 검증 로그: `/workspace/a-safety-event-cost-full.log`.

## 최종 로컬 검증

- Debug 전체 JVM **1,038개**, Release 전체 JVM **1,023개** 재실행 통과. 실패/오류/skip 모두 0.
- Debug/Release APK 및 AndroidTest APK 빌드 통과. Gradle에서 변경 없는 host/driver 산출물 등은
  UP-TO-DATE로 재사용했고, 앱 Debug/Release JVM 작업은 실제 재실행됐다.
- 앱 Debug/Release Lint 통과. 오류 0, 기존 미수정 UI/리소스 경고 Debug 2개/Release 3개.
- `tools/verify_capture_test_apks.py` 통과: host/driver/앱 DEX 격리, Release Debug probe 부재.
  실제 두 APK에 새 예약 경계와 기존 collection guard class_def가 포함됨을 확인했다.
- 실제 APK에 INTERNET/QUERY_ALL_PACKAGES 없음. Manifest 및 기존 권한 목록 변경 없음.
- 일반/CRLF-aware diff 검사, 신규 파일 후행 공백/EOF 검사 통과.
- 기존 10개 worktree의 HEAD/status/미커밋 파일 해시 및 삼성 보호 문서 보존 확인.
- 커밋/푸시/PR/병합/Android 계측 실행/실기기/에뮬레이터 실행 없음.

```bash
JAVA_HOME=/workspace/jdk17 ANDROID_HOME=/workspace/android-sdk PATH=/workspace/jdk17/bin:$PATH \
./gradlew --offline --no-daemon --max-workers=2 --continue \
  :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  :app:lintDebug :app:lintRelease
```

## 한계 및 보존 범위

- 이벤트 단계의 PackageManager 호출은 제거했지만, 실제 수집 직전의 조회 비용은 남는다.
  백그라운드 동기 Android 호출을 coroutine 취소가 즉시 중단한다고 주장하지 않는다.
- 스케줄 범위만 통과한 이벤트도 debounce 작업을 교체할 수 있다. 이름만으로 민감성을 식별할 수 있는
  경우의 최종 거부는 worker에서 일어난다. 이는 메인 스레드의 동기 이름 검사를 유지하거나 허용 캐시를
  도입하지 않고 개인정보 보호를 유지하기 위한 구분이며, 실제 이벤트 폭주/기기 감지 지연은 미검증이다.
- 앱 단위 차단과 웹페이지 민감 화면 판별은 별개다. 기존 키워드 규칙의 누락/오차단 및 원격 화면의
  수집 도중 변경 가능성은 이번 비용 수정으로 해소되지 않는다.
- 운영 A 엔진/F0/B/F9/TTL, B 분류, C UI/TalkBack, 공통 계약, 권한, 실제 클릭/닫힘 확인은 변경하지 않았다.
- 다른 10개 기존 worktree와 삼성 P01 보호 문서를 보존한다. 두 서비스 동시 실행 방지는 별도 작업이다.
