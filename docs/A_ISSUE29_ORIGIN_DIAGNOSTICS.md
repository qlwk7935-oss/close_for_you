# A #29 P01 이벤트 관계 진단

`fix/issue29-overlay-cleanup-barrier`의 기존 미커밋 장벽을 유지하며 추가한 Debug 진단이다.
이벤트 무시 예외, 정책 허가, TTL 갱신, 클릭/제스처 실행을 추가하지 않는다.

## 로그와 해석

첫 거부 이벤트 한 건의 `A_OVERLAY_EVENT;VERSION=V5`에 다음 필드를 추가한다.

- `EVENT_ID_RELATION`: `CURRENT_C_ID_MATCH`, `CURRENT_TARGET_ID_MATCH`,
  `PREVIOUS_C_PENDING_ID_MATCH`, `PREVIOUS_C_ACKED_ID_MATCH`, `AMBIGUOUS_OR_REUSED`,
  `UNMATCHED`, `UNKNOWN`.
- `EVENT_TIME_RELATION`: `BEFORE_CURRENT_SHOW`, `AFTER_CURRENT_SHOW`, `UNKNOWN`.

ID 관계는 인과관계 확정값이 아니다. 현재 판단이 실제로 읽은 event ID/current token과
검증된 C 결합만 사용한다. 정책이 ID/current 읽기 전에 거부하면 추가 getter를 호출하지 않고
관계는 UNKNOWN이다. WINDOW_CLASS의 기존 의미와 조회 순서는 유지한다.

직전 C는 실제 OwnedOverlayBinding의 유일한 새 소유 창 결합이 성립했을 때만 기록한다.
기존 Binding과 별도인 Debug 보조 상태가 서비스 수명 안에서 직전 1건만 유지하며,
정리 완료 이후 바로 다음 표시까지만 비교 가능하다. 두 번째 후속 표시에는 이력이 이월되지 않는다.
정리 실패·소유 창 조회 예외·표시 실패·종료 시 비활성화하고 ID/세대/시각을 지운다.
서비스를 다시 연결하면 새 인스턴스이므로 과거 이력이 없다.

현재 C/대상과 직전 ID 충돌, 이전 ID가 표시 전 소유 목록에 존재, 검증된 소유 ID 중복,
ACK 후 이전 ID가 소유 창으로 다시 관측되는 경우는 AMBIGUOUS_OR_REUSED이다.
기존 WINDOW_CLASS 진단 조회에서 ACK한 ID가 다시 확인되어도 단순 이전 창 일치로 기록하지 않는다.
해당 조회가 UNKNOWN이면 ACK 관계도 UNKNOWN으로 낮춘다.
관측되지 않은 ID의 ABA 재사용은 배제할 수 없다. 이벤트에 C 세대가 없으므로
PREVIOUS_C_ACKED_ID_MATCH만으로 이전 C가 원인이라고 확정하거나 이벤트를 무시하면 안 된다.

`A_OVERLAY_CLEANUP;VERSION=V1;POINT=...`는 다음 단계의 고정 코드만 출력한다.

1. C_SHOWN: 실제 표시 성공 후 A의 표시 상태 등록.
2. C_BIND_CONFIRMED: 기존 정책이 새 단일 소유 C 창의 결합을 확정함.
3. C_RETIRE_INTENT: 검증된 C 정체성의 정리 의도가 장벽에 등록됨.
4. C_REMOVE_RETURNED: 기존 제거 경로가 HIDDEN 상태로 반환됨.
5. C_REMOVE_EVENT_HANDLED: 장벽 receipt에 대응하는 이벤트의 기존 서비스 처리가 끝남.
6. C_CLEANUP_CONFIRMED: 기존 장벽의 반환·이벤트 처리·창 부재 검사를 모두 통과함.

각 단계는 해당 정리/결합에서 한 번 출력한다. 실패한 단계의 성공 로그를 만들지 않는다.
직접 C 선택 종료와 A presenter.dismiss 모두 기존 장벽 진입점을 사용한다.
onDismissed 알림 단독으로 정리 성공이나 이전 ID 검증을 인정하지 않는다.

## 시간 기준

Android 어셈블리는 SystemClock.uptimeMillis를 사용한다.
프레임워크의 AccessibilityManager도 이벤트 전송 시 이 시간 기준을 사용한다
([AOSP 소스](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/view/accessibility/AccessibilityManager.java)).
표시 준비 시작과 실제 표시 등록 사이의 구간을 잡고, 구간보다 엄격하게 이전/이후인 경우만
분류한다. 구간 내부·경계·0·미래 시각·시계 역행·getter 예외는 UNKNOWN이다.
eventTime은 첫 거부 때만 읽고 callback 밖으로 이벤트를 보관하지 않는다.
전송 시각이 표시보다 앞섰다는 사실도 실제 화면 변화나 이벤트 원인을 증명하지 않는다.

## APK 식별과 실측

Debug 서비스 연결 로그에서 다음 조합을 확인한다.

`A_CHOICE_DEBUG;STATE=ENABLED;VERSION=V4;BUILD=ISSUE29_ORIGIN_V1;SOURCE_SHA256=<64자리 해시>`

app Gradle 설정은 main/debug Kotlin·XML 및 빌드 설정의 실제 파일 내용을 경로순으로 해시한다.
추적되지 않은 OverlayCleanupBarrier/OriginDiagnostic도 포함하며 Git HEAD를 사용하지 않는다.
해시는 Debug BuildConfig에만 포함된다. Gradle configuration cache는 이 프로젝트에서 켜지 않는다.
테스트 코드·문서·SDK 경로·로컬 설정·개인정보는 해시 입력이 아니다.

빌드된 Debug APK의 SHA-256과 generated Debug BuildConfig의 SOURCE_SHA256을 함께 보관한다.
설치한 APK의 연결 로그 해시가 빌드 기록과 같고 V5 필드가 있는지 확인한다.
최종 APK 해시는 전송한 파일 자체가 같은지 확인하는 용도다.
Release에는 새 로그 태그·포맷·빌드 식별 필드와 진단 인스턴스를 만들지 않는다.
minify=false이므로 공용 enum/helper의 클래스 이름이 Release에 남는 것과 로그 포함은 구분한다.

실측은 이전 C가 없는 첫 표시와, 이전 C의 CLEANUP_CONFIRMED 뒤 다음 P01 표시를 나눈다.
동일 서비스 수명의 단계 순서와 첫 거부 관계/시각을 비교하고, 합성 앱의 수동 화면 전환과
A의 비실행 구간을 분리한다. 원시 event dump, 창 목록/제목, package, ID, token, 세대 숫자,
화면 텍스트, 예외 메시지를 수집하지 않는다.

## 검증 범위

Origin JVM 회귀는 직전/현재 ID 일치·불일치, pending/ACK, 중복·재사용·충돌, 재연결,
이력 범위, 첫 거부 제한, 시간 불확실성, 조회·해제, sink 예외/재진입을 다룬다.
Debug 관찰자와 Release null 포트에서 기존 policy getter 순서·결과·무효화 횟수를 비교한다.
실제 Android 이벤트 큐·OEM·TalkBack 순서는 JVM으로 입증하지 못한다.
장벽 완료 이후의 상태/중복 이벤트는 여전히 기존 정책에 따라 무효화된다.

전체 Release JVM의 기존 Debug 전용 테스트 의존성 문제는 별도 보고한다.
관련 Release 실행은 ignored init script가 공용 테스트 bytecode와 Release production/runtime을
연결하며 Debug production 클래스를 classpath에 넣지 않는다. 전체 Release 테스트 통과로 해석하지 않는다.

## 이번 작업의 변경 파일

기존 미커밋 10개 중 아래 3개에는 진단 연결만 추가했으며 나머지 7개는 시작 시 파일 해시와 동일하다.
브랜치와 HEAD 4f67ee6, 기존 인덱스의 빈 상태를 유지했다. 원본 10개는 ignored build/tmp에 보관해 비교했다.

- 기존 장벽 파일에 추가: main/.../integration/OwnedOverlayEventPolicy.kt,
  ServiceChoiceIntegration.kt, OverlayCleanupBarrier.kt.
- 진단 변경: main/.../integration/OverlayEventDiagnostic.kt,
  debug/.../integration/ChoiceFlowLog.kt, release/.../integration/ChoiceFlowLog.kt.
- 신규 진단 상태: main/.../integration/OverlayEventOriginDiagnostic.kt.
- 빌드 식별: app/build.gradle.kts.
- 기존 sink 인자 확장만 반영한 JVM 테스트: OverlayEventDiagnosticTest.kt,
  OverlayEventCauseTest.kt, OwnedOverlayWindowReadTest.kt.
- 신규 회귀: OverlayEventOriginDiagnosticTest.kt (19개).
- 문서: docs/A_ISSUE29_ORIGIN_DIAGNOSTICS.md.

이번 작업에서 수정/추가한 파일은 13개이며, 이전 작업까지 합친 Git 변경 목록은 20개다.
기존 C 영역 PopupChoiceFlow.kt와 공통 계약에는 이번 추가 변경을 하지 않았다.

## 2026-10-23 로컬 검증 결과

- Debug 전체 JVM 1,228개: 실패/오류/skip 0.
- Release production/runtime 대상 관련 JVM 222개: 실패/오류/skip 0.
  Release 전용 실행에서는 observer/sink factory가 null임도 검사했다.
- assembleDebug, assembleRelease, lintDebug, lintRelease 성공.
  Lint 각각 오류 0, 기존 GradleDependency 경고 9개.
- 전체 testReleaseUnitTest는 기존 DetectionTestSessionTest.kt와 F1SyntheticMatrixTest.kt의
  Debug 전용 타입 참조 때문에 compileReleaseUnitTestKotlin에서 실패했다. 해당 테스트는 수정하지 않았다.
- git diff --check 통과. 커밋·스테이징·push·PR·merge·브랜치 변경·stash·reset 없음.
- Debug APK의 DEX에 V5 관계/시각 필드, 정리 로그, 빌드 식별 문자열과 아래 소스 해시가 있음을 검사했다.
  Release DEX에는 해당 신규 로그 마커가 없고 Release BuildConfig에도 소스 해시 필드가 없다.
- 별도 PowerShell 구현으로 현재 소스 해시를 다시 계산해 generated Debug BuildConfig 값과 대조했다.
  원시 ID·패키지·토큰·화면 텍스트를 새 로그 sink에 전달하는 인자는 없으며 enum만 전달한다.
- 실제 설치·P01 재현·TalkBack·OEM 이벤트 순서는 미검증이다. 이 변경은 원인 진단이며 조기 소멸 해결이 아니다.

빌드 식별: ISSUE29_ORIGIN_V1

소스 SHA-256: `c245960c496e343fb42c556c8dcc3196e903fcebff2b0f8e3bf8a89595b5a956`

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

Debug APK SHA-256: `005d6e2bdba82e6d258feafd3ece4c4357f6262b2f33a731a2c17bef1851b597`

Release APK SHA-256: `6fe726463e94bce75fd43aeab51263ca10faaea3815fd2ef20b0a1b4da986d79`

이 값은 위 최종 검증 APK에 대한 기록이다. 이후 소스 수정/빌드는 새 해시로 확인해야 한다.
ignored 검증 기록은 build/tmp/issue29-origin-artifacts.json과 issue29-origin-final-validation.log에 있다.
