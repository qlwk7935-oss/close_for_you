# A PR #10 — 실행 없는 재탐색 준비 (2026-10-22)

`local/a-action-reader-prep-20261022`, 기준 PR #10 `5729ec1`. 서비스·collector·공통 계약·B/C/D·Manifest·gradle.properties를 변경하지 않습니다. PR #7의 후속 수집기나 이번 B/C 통합 변경을 이 worktree로 가져오지 않았습니다. 최초 준비 단계에서는 로컬 파일로만 보존했으며, 이번 최종 검토는 검증을 통과한 A 소유4개 파일만 기존 Draft PR #10에 반영하는 범위입니다. 다른 의존 PR을 이 브랜치에 병합하지 않습니다.

## 실제 코드와 좁은 의미

- `ReadOnlyTargetProbe.kt`: 한 owner에서 읽기 시도1회, 현재 세션·토큰 전 성분·원래 capture 시각부터5초·정책·사용자 조작을 검사합니다. 경로는 canonical root `0`과 최대60개 자식 index, 최대1200 노드/32창/필드4096자, 읽기 예산100ms입니다. 등록 참조는 요청 인자가 아니라 A의 `TargetProbeSource.reference()`에서 받습니다. 기본 Android source는 참조 없음/상태 거부입니다.
- `AndroidTargetProbeSource.kt`: 실제 `rootInActiveWindow`, `getChild`, 창 목록·frame·bounds·properties를 읽는 동기 adapter입니다. 노드의 package/window를 확인한 다음에만 자식 수나 데이터 getter를 호출합니다. 원본 CharSequence는 제한된 불변 복사 뒤 검사하며, target의 기존9개 보호 문자열/입력 플래그를 검사합니다. 양수 원시 경계·창 포함·동작 근거·라벨·가시/활성 상태를 비교하고 전후 창/frame을 확인합니다. 임의 보정/삭제/재수집/재시도는 없습니다.
- 실제 서비스에 연결하거나 `ActionGateContext`/`GateChecks.PASSED`/receipt를 만들지 않습니다. 반환은 고정 거부 enum 집합뿐이고 노드·원문·좌표·토큰을 반환/출력하지 않습니다. `productionReady=false`입니다. ActionExecutor/클릭/제스처/포커스/뒤로 가기 경로가 없습니다.

모든 결과는 `EXECUTION_DISABLED`, `WHOLE_F0_UNPROVEN`, `POPUP_IDENTITY_UNPROVEN`, `USER_ORIGIN_UNPROVEN`을 유지합니다. 비교가 일치해도 실제 F0 완료·팝업 소속/재등장 identity·신뢰 가능한 사용자 선택·구조/본문 지문 현재성을 입증하지 못합니다. 새 UUID/새 owner는 실제 새 팝업의 증거가 아닙니다. 단일 owner 읽기 제한은 Android 실행 시도 장부를 대체하지 않습니다.

## 자원·취소

한 경로의 노드를 최대61개 보유한 뒤 역순으로 각 해제를 시도합니다. 첫 검사 실패는 일반 해제 실패로 덮지 않고, 취소를 정상 반환으로 바꾸지 않습니다. 정리 뒤 실제 Job/정책·토큰·TTL·읽기 예산을 다시 확인합니다. 창 목록 역시 모든 해제를 독립적으로 시도하며 정리 실패를 비교 일치로 반환하지 않습니다. getter/Binder·recycle의 실제 오류, native 중단과 물리적 소거를 JVM에서 재현했다고 주장하지 않습니다. fatal VM Error의 복구/모든 정리는 보장하지 않습니다.

## 앞선 로컬 검증 기록

공유 probe의 JVM18개가 기본 거부, root/child 소유권 선행(getter0회), 숨김/누락/민감/크기 초과, 가변 문자열 비교, 원래 TTL 경계, 세션/정책/토큰 변경, 전후 창 변화, malformed 원시 경계, 취소와 모든 해제, 중복 읽기·비노출을 검사합니다. 실측 source가 실행된 것은 아닙니다.

2026-10-22 최종 로컬 검증: JVM **250개**(앱195·호스트55), 실패·오류·skip0. 앱 Debug/Release 및 앱·호스트 AndroidTest APK/호스트 Debug 빌드 성공. 앱 Debug/Release·호스트 Debug Lint 이슈0. 해당 브랜치의 패키징 검사와 추가 DEX class_def 검사 통과: 신규 probe는 앱 Debug/Release에만 있고, B19/C18 통합과 신규 JVM 시험은 없습니다. 마이크/INTERNET 권한 추가도 없습니다. 추적 파일 diff는 없으며 신규 Kotlin3개와 이 문서만 미추적 상태로 보존합니다. 신규 파일 공백 검사 통과. 원래 PR #10 브랜치·원격 HEAD는 그대로이며 push/PR 편집/병합은 없습니다. 연결된 Android 기기는0개로 실제 Android 계측은 미실행입니다.

다음 연결 전에는 실제 서비스 소유 등록·세션/state producer, 전체 target/같은 앱 배경·숨김 F0, 노드/부모/팝업 identity와 같은 시도 지문 결박, C의 선택 출처 및 자체 창 lifecycle, 중앙 실행 시도 수명에 대한 합의와 Android 회귀가 필요합니다. 그 전까지 source 기본 거부와 비실행 게이트를 유지합니다. 원본14개 UNOBSERVED, Scroll/Window 문제·실기기 미검증은 그대로입니다.

## 최종 독립 검토와 보강

기존18개 회귀에2개를 추가했습니다. `ProbeNode.sameHandle`을 실제 Android adapter가 구현하여 서로 다른 wrapper가 **동일 Java framework 객체**를 가리키는 경우 경로 재진입을 거부하고 원 소유자만1회 해제합니다. 같은 화면 노드를 나타내더라도 별도로 취득한 다른 객체는 각각 해제합니다. 원격 node identity/부모 관계를 완전히 입증한다는 의미가 아닙니다. 두 번째 회귀는 정리 후 마지막 clock 조회 안에서 실제 coroutine Job을 취소하고, 비교 결과 대신 취소가 전파되며 소유 노드가 모두 해제되고 재조회가 거부되는지 검사합니다.

새20개 회귀는 실제 공유 probe를 사용하지만 framework의 실제 recycle 예외나 Android 화면 변경을 재현한 것은 아닙니다. 이 검토에서도 whole-F0·popup identity·사용자 선택 출처의 고정 거부를 제거하지 않았고 실행 권한은 생성하지 않습니다. 기존 gate의 ledger/완료 조건/TTL 규격과 productionReady=false는 그대로입니다.

이번 최종 재실행은 **JVM252개(앱197·호스트55), 실패·오류·skip0**입니다. 앞선250개와 구분합니다. JDK17에서 앱 Debug/Release, 앱·호스트 AndroidTest APK/호스트 Debug, 앱 Debug/Release·호스트 Debug Lint를 모두 다시 실행해 성공했습니다. Lint 이슈0, 해당 브랜치 패키징 및 DEX 격리 검사 통과입니다. 운영 probe2개는 앱에만 존재하고 신규 JVM 시험은 모든 APK에 포함되지 않으며 B19/C18 통합 코드도 없습니다. 원래 gate·서비스·collector·공통 계약·Manifest·gradle.properties는 바꾸지 않았습니다. 소스·진단의 원문/좌표/토큰/지문 출력과 Android 행동 API 호출은 추가하지 않았습니다. 기기는 연결되지 않아 이번 버전의 Android/API37 계측은 미실행입니다.

```sh
JAVA_HOME=/workspace/jdk17 ./gradlew --offline --no-daemon :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest :app:lintDebug :app:lintRelease :capture-test-host:lintDebug
python3 tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
```

검증 후 이 문서·probe2개·JVM1개만 A 전용 커밋으로 처리합니다. 원래 PR10이 아직 같은 HEAD인 경우에만 일반 push를 허용하며, 충돌·원격 이동·권한 오류를 우회하지 않습니다. Draft를 유지하고 main/다른 PR을 병합하지 않습니다. 원격 CI는 별도 PR 기록에서 확인하며 로컬 결과를 CI 성공으로 간주하지 않습니다.
