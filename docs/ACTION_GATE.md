# A 행동 게이트 — 비실행 1차

기준: `feature/a-action-gate`, PR #8/#9가 병합된 `04fcd27`. 실제 클릭·서비스 연결·운영 화면 재검증을 구현한 문서가 아닙니다. 공통 계약/B/C/D/기존 Mock/운영 서비스는 변경하지 않습니다. F5 유지, F6 제외, F10/F12/F13 필수 요구도 그대로입니다.

## 현재 구현의 경계

`accessibility/action`은 A 내부 타입만 사용합니다. `NonExecutingActionExecutor`는 기존 `ActionExecutor`를 구현하지만 앱/서비스 어디에도 주입하지 않습니다. 기본 `ActionGateContextSource`는 항상 null을 반환해 `UNREGISTERED_REQUEST`로 거부합니다. 실제 화면/사용자 선택/보호 검사를 공급하는 Android 제공자는 **미구현**입니다.

증거 출처 enum에는 UNAVAILABLE과 SYNTHETIC_TEST만 있고 Android 검증 완료 값은 없습니다. 모든 `GateEvaluation`에 ANDROID_REVALIDATION_NOT_IMPLEMENTED를 남깁니다. `syntheticChecksSatisfied`는 JVM 합성 조건을 모두 만족했는지만 나타내며 `productionReady`는 항상 false입니다. 합성 PASSED를 운영 검증 완료로 해석하거나 실제 서비스에 연결하면 안 됩니다.

`execute()`의 정상 반환은 언제나 NOT_EXECUTED / NOT_IMPLEMENTED / attempted=false / semanticSafetyConfirmedByUser=false입니다. 제공자 예외도 이 결과로 끝나고 메시지/stack trace는 기록하지 않습니다. 코루틴 취소는 CancellationException을 전파하며 성공/미시도 결과로 위장하지 않습니다. 실제 행동 API/Android action port/제스처/Back/좌표 fallback은 존재하지 않습니다.

## 요청을 신뢰하지 않는 검사

공통 ActionRequest에는 decision과 candidate뿐입니다. A 소유 등록 문맥은 요청 인자에서 만들어서는 안 됩니다. 제공자 `read()`는 ActionRequest를 받지 않고, A가 이미 소유한 임시 문맥을 조회하는 형태입니다. 문맥의 출처·사용자 승인·전체 보호 검사를 실제로 입증하는 생산자는 이번에 만들지 않았습니다. 패키지가 별도 보안 경계나 Kotlin `internal`이 같은 앱 코드의 위조 방지 장치라고 주장하지 않습니다.

검증기는 다음을 독립적으로 검사합니다.

- ALLOW_ONCE이고 금지/확인 사유가 없어야 하며 decision.candidateId와 등록 후보가 일치해야 합니다. 요청 후보의 ID/path/viewId/라벨/bounds/근거/role 전체가 등록 후보와 동일해야 합니다.
- 요청/등록/현재 토큰의 모든 성분, 연결 상태, 서비스 세대가 맞아야 합니다. 새 UUID가 같거나 다르다는 사실만으로 팝업 신원을 승인하지 않습니다.
- 등록 당시의 원래 monotonic deadline을 사용합니다. TTL은 최대5초, `now >= expiresAt` 또는 시계 역행/잘못된 deadline은 거부합니다. 새 수집/검사 완료로 연장하지 않습니다. 실제 연결 때는 Runtime.begin과 같은 clock/원래 deadline을 공급해야 합니다. 기본 nanoTime clock을 Android uptime deadline과 혼용하면 안 됩니다.
- 수집 동의/앱 허용/일시정지/사용자 조작을 각각 검사합니다. 기본 설정은 거부입니다.
- 전체 수집 COMPLETE, F0-A/F0-B/F1/geometry/가림/팝업 소속/중요 결정 없음/버튼 의미가 모두 증명되어야 합니다. 기본 MISSING, BLOCKED, UNCERTAIN은 모두 거부입니다. INCOMPLETE_EVIDENCE는 입력에 허락으로 사용하지 않습니다.
- MALFORMED_NODE_BOUNDS, WINDOW_OUTSIDE_FRAME, INCOMPLETE, TIME_LIMIT, TARGET_MISSING은 각각 수집 부적격 상태입니다. 실제 수집기의 진단을 새 코드로 보충하거나 우회하는 구현은 없습니다. 원격 main에는 PR #7의 명시적 F1 진단이 없으므로 동등한 보호가 이미 연결됐다고 가정하지 않습니다.
- 합성 출처의 이번 버튼 직접 선택(CLOSE_ONCE/SKIP_ONCE), 동일 토큰·후보 및 의미 확인이 필요합니다. 기억 규칙/STOP_ASKING/단순 음성 문자열/READ/자동 반복은 허락이 아닙니다.
- E1은 DISMISS, E2는 CLICK 근거만 검사하고 다른 action으로 대체하지 않습니다. E3/E4는 거부합니다. 초기 라벨 집합은 B보다 좁습니다: 닫기/창 닫기/팝업 닫기/close/dismiss, 건너뛰기/스킵/skip. 맨 X/×, 오늘 하루 닫기, 확인/OK, 결제·동의·권한·설치 등은 거부합니다. E1도 라벨 없는 경우 거부합니다.
- 현재 노드는 등록 노드와 같고 앱/창/path/viewId/bounds/class/action이 결박되어야 합니다. enabled/visible이 아니거나 password/editable/inputType이 있으면 거부합니다. text와 description은 서로 충돌하면 안 됩니다.
- 현재 screen/window/popup/node 경계는 양의 면적과 정확한 포함 관계를 가져야 하며 등록 geometry와 같아야 합니다. popup subtree의 경로도 일치해야 합니다. 정렬·clamp·tolerance·이동·리사이즈는 하지 않습니다.

라벨과 DISMISS가 실제 앱의 행동 의미를 보증하지는 않습니다. 전체 트리/숨김 자식/같은 앱 배경의 민감 보호와 문맥 의미 검증은 실제 A 수집기가 별도로 제공해야 합니다. target 하나의 password=false로 전체 F0-A를 대신할 수 없습니다. F0-A가 F0-B를 대신하지도 않습니다. F0-B의 운영상 적용 여부/미수행/불확실/실패를 어떻게 결박할지도 합의가 필요하며 이번에는 MISSING을 통과시키지 않습니다.

## 중복·동시성: 검토 예약과 실행 시도는 다름

`ActionAttemptLedger`는 크기가 고정된 **단일 합성 episode 검토 장부**입니다. 소유한 객체와 동일한 episode이고 연속성 근거가 있어야 예약됩니다. 외부 객체, 다른 장부의 episode, 새 UUID/토큰으로 새 팝업을 등록하는 API는 없습니다. 다른 팝업/프로세스 재시작을 입증하는 운영 identity 제공자는 미구현이며 지원을 주장하지 않습니다.

AVAILABLE → RESERVED → COMPLETED / FAILED / CANCELLED 전이만 허용합니다. 동시 요청은 synchronized 예약에서 하나만 진입하고, 종료한 검토를 다시 열지 않습니다. 연속성 상실도 되돌리지 않습니다. 일반 토큰 무효화/TTL/새 후보를 이유로 reset/evict하는 API가 없습니다. 실제 장부의 범위/수명은 A 서비스가 중앙 소유해야 하며 요청마다 새 장부를 만드는 연결은 금지입니다.

`COMPLETED`는 합성 검토 완료일 뿐입니다. 장부의 attempted와 결과의 attempted는 항상 false이며 API 호출·실제 시도·성공 카운터는 기록하지 않습니다. 실제 실행 장부의 attempt-consume 경계는 후속 합의/구현 대상입니다. 현재 코드를 실제 시도 최대1회 보장의 완료 구현으로 홍보하지 않습니다.

검토기는 첫 문맥 검사를 통과한 뒤 예약하고, 중단될 수 있는 제공자를 다시 읽어 문맥·원래 TTL·취소를 재검사합니다. null/변경/철회/실패이면 완료로 승격하지 않습니다. 제공자 검사 뒤 장부의 연속성도 동기화된 완료 연산으로 다시 확인합니다. 예약 이후 예외/취소 시 finally로 FAILED/CANCELLED가 되며 재검토 예약도 열리지 않습니다. 처음부터 거부된 요청은 예약하지 않습니다. 재시도 루프는 없습니다.

## 개인정보와 수명

어떤 파일/DB/로그/네트워크에도 원문·좌표·지문·실제 ID를 쓰지 않습니다. private node/geometry/registration/context/episode의 toString은 redacted이며 오류는 고정 enum만 반환합니다. 원문을 담는 요청/문맥 전체를 출력하면 안 됩니다. 합성 문맥 참조는 호출 동안만 지역적으로 보유하며 executor/ledger는 이를 캐시하지 않습니다. 제공자 구현 시 세대 무효화·동의 철회·구독 해제·TTL에 모든 참조를 폐기해야 합니다. JVM 참조 해제는 문자열 메모리의 물리적 zeroization 보장이 아닙니다.

캡처/OCR/ROI/PR #6의 큰 버퍼 거부·동의·원래 수집 시간/메모리 제한은 수정하지 않습니다. F1 baseline14개/UNOBSERVED/정답도 수정하거나 통과했다고 보고하지 않습니다.

## JVM 검증과 실제 Android 전제

합성 전용 JVM 회귀는 임의 ALLOW/후보 변조, 토큰 모든 성분, 4999/5000ms와 overflow/역행, 세션/연결/동의/허용/터치, 모든 미완료 보호 상태, 입력/암호/가짜 닫기, geometry/소속, 동일 후보 등록의 악성 속성, 신규 UUID, 실제 다중 JVM 스레드 예약, 코루틴 동시 요청·중단 중 철회·만료·취소·예외·연속성 상실 및 비실행 결과를 검사합니다. 합성 상태의 PASSED는 실제 Android 테스트 결과가 아닙니다.

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease
python3 -B tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
```

Windows에서는 `./gradlew`를 `.\gradlew.bat`, `python3`를 설치한 Python 명령으로 바꿉니다. 이번 구현에 실제 서비스용 계측 진입점은 없습니다. 기존 F1 계측/캡처 성공을 이 게이트의 증거로 사용하지 않습니다.

실제 Android API35/37와 TalkBack에서는 연결 세대/프로세스, 전체 트리 재수집, hidden 입력/암호, F1/창 초과/가림/시스템·다른 앱, 노드 재탐색과 해제, 검증 직전 화면 변경, 설정 철회, C 선택 이벤트가 토큰을 무효화하는 흐름을 별도 계측해야 합니다. Android는 원격 앱 화면 검사와 행동의 원자성을 보증하지 않습니다. 모든 A 검증/예약/최종 검사와 이후 한 번의 action 경계는 후속 설계이며 이번에는 존재하지 않습니다.

## 팀 합의 및 후속 TODO

1. A/B: 등록 문맥과 ActionRequest 결합, 후보를 같은 분석/감지 토큰에 붙이는 위치. 공개 계약을 바꿔야 한다면 별도 합의. 지금은 요청 DTO만으로 승인 불가.
2. A/C: 실제 사용자 선택의 출처/receipt, 버튼 의미 확인, 터치·자체 안내 이벤트의 무효화 이후 재확인. 자체 UI 이벤트를 일괄 무시하거나 오래된 선택을 새 토큰으로 자동 이전하지 않음. F10 청취 보증과 재생 완료도 구분.
3. A/B/D: 같은 팝업 identity의 수명, 입증된 소멸/새 등장/동일 창 교체, 장부 중앙 소유·상한·세션/재시작 시 거부 정책. 현재 수집별 UUID로 최대1회를 주장할 수 없음. 지문을 DB에 저장해 우회하지 않음.
4. A/B/C: 앱별 자동 닫기 기본OFF 및 실행 이력 전달. 기억 규칙 자동 반복은 아직 비활성화. E3/E4/뒤로 가기/제스처는 이번 지원 대상 아님.
5. A/D: 필요 진단은 고정 코드만 합의. 외부 Diagnostics enum/DB/규칙을 이번에 수정하지 않음.
6. 실제 실행 시에는 action 호출 직전 시도를 소비하고 false/예외/timeout/취소에도 복구하지 않아야 함. 반환 true/팝업 소멸/사용자 의미 확인은 각각 별개이고 불명 결과를 재클릭 사유로 삼지 않음. 실제 노드 실행기와 결과 관측기는 미구현.

새 게이트를 운영 서비스에 연결하거나 B 정책/기존 Mock을 교체하는 작업은 이번 범위가 아닙니다. 새 PR 생성/main 병합도 하지 않습니다.

## 이번 브랜치 로컬 검증 결과

JDK17에서 위 전체 명령을 실행했습니다. 첫 전체 실행은 BUILD SUCCESSFUL (1분38초, 196 tasks: 66 executed / 130 up-to-date), 취소를 무시하는 제공자 회귀를 추가한 최종 실행은 BUILD SUCCESSFUL (43초, 196 tasks: 7 executed / 189 up-to-date)입니다. 최종 JVM **226개**(앱171/호스트55, 신규 게이트39개), 실패/오류/건너뜀0, 양쪽 debug Lint 이슈0입니다. Debug/Release·계측 APK 빌드와 기존 DEX/독립 Kotlin runtime/driver 격리/release probe 부재 패키징 검사도 통과했습니다.

초기 신규 회귀36개 중 연속성 상실 중 검토 완료를 허용하는 테스트1개가 실패했습니다. 제공자 재조회 뒤 장부의 연속성/예약 소유권을 동기화된 완료 연산에서 다시 검사하도록 수정했고 후속38개 및 최종39개가 모두 통과했습니다. 실패를 삭제하거나 정답을 완화하지 않았습니다.

연결 기기0대로 Android 실행은 미검증입니다. 실제 클릭/재수집/사용자 선택/팝업 identity를 구현·검증했다고 주장하지 않습니다. 현재 workflow는 이 브랜치 push를 대상으로 하지 않고 workflow_dispatch도 없습니다. 새 PR을 만들지 않으므로 이번 커밋의 GitHub Actions 자동 검증은 수행하지 않으며 workflow도 변경하지 않습니다. 로컬 빌드/테스트/Lint 결과와 CI를 구분합니다.
