# A Debug 수집/evidence 종료 진단 — V2

P01에서 확인된 최초 collector Ready / F1 POPUP_DETECTED 이후의
`EvidenceOutcome.Blocked`와 로그 부재의 종료 경로를 구분하기 위한 로컬 진단이다. 원인을 수정하거나
후보 발행·C 표시의 성공 기준을 바꾸지 않는다.

## 출력 범위

Debug source set에만 `A_EvidenceBlock` Logcat sink가 있다. 연결 초기화가 성공할 때마다
고정 코드 `A_FLOW_DEBUG;STATE=ENABLED;VERSION=V2`를 한 번 출력한다. Release의 동일 port는
진단 객체를 만들지 않고 출력하지 않는다. 실제 capture Job과 evidence Job마다 별도 객체를 쓰며
원인과 실제 Blocked의 reason/issue를 고정 enum으로만 보관한다. DTO, Android 객체,
본문, 패키지명, 창 ID, 좌표, 시각, 토큰, 지문, 예외는 보관하거나 출력하지 않는다.

실제 서비스와 JVM이 같은 `launchDiagnosed`를 쓴다. 기존 launch의 scope/parent/start를
유지하고 Job 완료 콜백에 보고를 등록한다. 시작 전에 취소된 Job과 정상 반환·취소 재전파·
예외·finally 실패도 작업당 최대 한 줄로 보고한다. capture와 evidence는 서로 다른 Job이며
여러 Job이 있으면 여러 줄이 나올 수 있다. 보고를 위해 추가 작업 Job이나 재시도는 만들지 않는다.
`diagnosedCleanup`은 기존 finally 본문을 그대로 한 번 호출한다. 정리 예외를 잡거나 바꾸지
않으며, 완료 콜백은 그 본문 이후에 실행된다. 정리 시작 후 반환하지 못하면 CLEANUP=FAILED다.
CLEANUP은 evidence finally 본문에 대한 표식이다. COMPLETED는 그 본문이 반환했다는 뜻이며
abandon이 실제 호출됐거나 모든 framework 객체 해제가 증명됐다는 뜻이 아니다. capture의
collector 내부 해제 상태를 이 표식으로 대신하지 않는다.

출력 sink만 비중단 예외 경계로 격리한다. sink가 던진 일반 예외·CancellationException은
실제 작업의 완료/취소 원인을 바꾸지 않는다. 실제 작업의 취소는 기존 코드대로 전파한다.
원래 코드의 body/finally 예외 우선순위는 그대로다. VM 치명 오류나 process 강제 종료까지
보고를 보장하지 않는다. 로그 쓰기 실패는 재시도하지 않는다.

차단 상세는 별도 줄로 중복 출력하지 않고 종료 줄에 합친다. 실제 내부 Blocked 결과를
만드는 분기에서 reason/issue를 먼저 기록한다. 최종 현재성 검사까지 통과해 pipeline이
Blocked를 반환하면 BLOCK_RESULT=RETURNED, 그 전에 취소되면 CREATED로 남는다.
END=EVIDENCE_CANCELLED와 BLOCK_RESULT=CREATED를 동시에 기록할 수 있으며 이를 정상
Blocked 반환이나 후보 발행으로 해석하지 않는다. 초기 collector Blocked는 TASK=CAPTURE로
구분하고 실제 EvidenceIssue가 없으므로 ISSUE=NONE이다.

| SOURCE | 의미 |
|---|---|
| INITIAL_COLLECTION_FAILED | 최초 collector가 Blocked 반환. 재검증 실패와 별도 경계 |
| RECOLLECTION_FAILED | 기존 verifyScreen의 실제 재수집이 Blocked. 기존 collector 진단에서 phase/operation/finding enum만 복사 |
| SNAPSHOT_MISMATCH | 두 수집의 기존 비교에서 불일치. CHECK는 최초 실패 항목 |
| TEXT_PROTECTION | 기존 pipeline의 tree 또는 OCR 텍스트 보호 검사에서 차단 |
| INCOMPLETE_SNAPSHOT | pipeline에 불완전 snapshot이 전달됨 |
| CURRENT_SCREEN_UNAVAILABLE | 기존 verifyScreen 입구의 현재성 검사에서 거부 |
| UNSPECIFIED | 출처 표식 없이 Blocked가 반환됨. 원인을 추정하지 않음 |

`CHECK=PACKAGE/WINDOW/STRUCTURE/CONTENT/FRAME/WINDOW_SET`은 기존 OR 비교의
순서와 단락 평가를 유지한다. 최초 불일치 뒤의 항목은 추가 평가하지 않는다.
`WINDOW_SET`은 기존처럼 bounds뿐 아니라 active/focused/layer 등을 포함한 집합 비교다.
CHECK=NONE은 일치가 입증됐다는 뜻이 아니라 불일치 항목 표식이 없다는 뜻이다.
새 재수집·접근성 getter·OCR·IPC·polling·시계 조회는 없다.

## 종료 코드

| END | 실제 경계 |
|---|---|
| INTEGRATION_UNAVAILABLE / CAPTURE_STALE | 기존 수집 전 조기 return |
| CAPTURE_BLOCKED | 최초 collector Blocked 경로 |
| F1_NOT_CANDIDATE | F1이 후보를 반환하지 않음. 실제 DISPOSITION/DETECTION_REASON도 기록 |
| EVIDENCE_SCHEDULED | capture가 별도 evidence 작업을 시작함. 발행 성공이 아님 |
| BEGIN_REJECTED | 기존 Runtime.begin이 null 반환 |
| EVIDENCE_BLOCKED | pipeline이 최종 현재성 검사를 통과해 Blocked 반환 |
| READY_STALE | Ready 후 기존 현재성 검사가 false라 발행하지 않음 |
| PUBLISH_REJECTED / PUBLISHED | 기존 Runtime.publish 반환 boolean |
| CAPTURE_CANCELLED / EVIDENCE_CANCELLED | 실제 Job이 취소로 종료 |
| CAPTURE_EXCEPTION / EVIDENCE_EXCEPTION | 실제 Job 예외 또는 기존 evidence catch의 일반 예외 처리 |
| UNCLASSIFIED | 종료 표식에 도달하지 않은 정상 종료. 성공으로 추정하지 않음 |

OUTCOME은 취소/예외 이전에 관측한 반환 경계를 보존한다. END는 실제 Job 종료를 우선하며
OUTCOME=PUBLISHED 뒤 cleanup이 실패하면 END=EVIDENCE_EXCEPTION/CLEANUP=FAILED다.
LAST_STAGE는 마지막 코드 경계이며 정확한 취소 원인이나 시각을 뜻하지 않는다.

다음은 **형식 예시이며 실제 관측 결과가 아니다.**

```text
A_FLOW_DEBUG;STATE=ENABLED;VERSION=V2
A_FLOW_END;TASK=EVIDENCE;END=BEGIN_REJECTED;OUTCOME=BEGIN_REJECTED;LAST_STAGE=BEGIN;CLEANUP=NONE;BLOCK_RESULT=NONE;DISPOSITION=NONE;DETECTION_REASON=NONE;SOURCE=UNSPECIFIED;CHECK=NONE;REASON=NONE;ISSUE=NONE;COLLECTION_PHASE=NONE;OPERATION=NONE;FINDING=NONE
A_EVIDENCE_BLOCK;TASK=EVIDENCE;END=EVIDENCE_CANCELLED;OUTCOME=UNCLASSIFIED;LAST_STAGE=CLEANUP;CLEANUP=COMPLETED;BLOCK_RESULT=CREATED;DISPOSITION=NONE;DETECTION_REASON=NONE;SOURCE=SNAPSHOT_MISMATCH;CHECK=CONTENT;REASON=UNREADABLE_TREE;ISSUE=PRIVACY_BLOCKED;COLLECTION_PHASE=NONE;OPERATION=NONE;FINDING=NONE
```

## 향후 한 번의 P01 관측에서 확인하는 방법

이 작업에서는 Android 시험을 실행하거나 추가 수동 재시험을 요구하지 않는다.
향후 승인된 P01 한 번을 관측할 때, 변경한 Debug APK의 동일 서비스 process를 대상으로
Android Studio Logcat에서 `tag:A_EvidenceBlock`으로 필터링한다. 원문을 표시할 수 있는
광범위 로그·객체 debugger dump는 수집하지 않는다. 기본 정보 header까지 제외한 고정
코드 줄만 터미널에서 보려면 Windows PowerShell에서 다음 필터를 사용할 수 있다.

```powershell
& $adbPath -s $serial logcat -v raw -s 'A_EvidenceBlock:I' '*:S' |
    Select-String '^A_(EVIDENCE_BLOCK|FLOW_END|FLOW_DEBUG);'
```

Logcat 연결 직후 출력되는 과거 버퍼 줄은 제외하고, 향후 해당 P01 관측 중 새로
도착한 줄만 확인한다. 관측 시작 이전의 기록을 이번 결과로 재사용하지 않는다.
별도 창 조작·P01 버튼 누르기·
접근성 클릭·제스처·자동 반복 없이 해당 한 번의 관측 구간만 확인한다.
이전의 A_EVIDENCE_BLOCK 전용 필터는 새 종료/활성 표식을 숨기므로 V2에서는 위처럼
세 접두사를 모두 본다. SOURCE로 세 원인을 나누고, SNAPSHOT_MISMATCH는 CHECK,
RECOLLECTION_FAILED는 REASON 및 COLLECTION_PHASE/OPERATION/FINDING을 확인한다.
고정 enum만으로 미분류된 하위 원인은 미확정으로 남긴다.

여러 줄은 서로 다른 capture/evidence 작업일 수 있다. 식별자를 출력하지 않으므로 다른 줄의
원인·사유를 합치거나 특정 P01 ATTEMPT와 원자적으로 연결됐다고 주장하지 않는다.
진단은 B/C 전달 성공·F0 완전성·화면 현재성·TTL·행동 안전성을 인증하지 않는다.
PUBLISHED는 C의 실제 표시·발화·사용자 선택 성공을 뜻하지 않는다.
로그 쓰기 비용은 0이 아니지만 작업 보고는 Job이 끝난 뒤에만 출력한다. 기존 100ms 예산,
180ms debounce, 5초 TTL과 취소·발행 조건은 바꾸지 않는다.

## 여전히 로그가 없을 수 있는 경우

- 수집을 예약하기 전의 이벤트 제외, 알림, 동의/일시정지/패키지 거부, 터치 중 return,
  확인된 자체 오버레이 이벤트 제외는 작업이 생성되지 않으므로 작업 종료 줄이 없다.
- 연결 초기화 실패, 다른/이전 APK 또는 Release 실행, 필터/Logcat 버퍼 유실·sink 실패,
  process 종료·치명 오류는 출력 부재로 서로 구별할 수 없다.
- framework/Binder가 반환하지 않으면 Job도 아직 완료되지 않아 종료 줄이 없다.
  비협력적 호출에 강제 중단을 추가하지 않는다.
- 활성 표식은 연결 시점의 한 번뿐이다. 늦게 로그를 보기 시작했다는 이유로 그 표식 부재를
  Release 또는 서비스 미연결로 확정하지 않는다. 로그 부재는 성공 증거가 아니다.

## JVM 경계

실제 서비스가 사용하는 launch/완료 콜백/cleanup helper로 시작 전 취소, 대기 취소,
collector 차단, F1 비후보, begin 거부, 실제 pipeline의 내부 Blocked 생성 후 최종 현재성
취소, 정상 Blocked 반환, 실제 LiveEvidenceState의 발행 성공·거부, Ready stale,
예외·cleanup 실패·sink 실패를 검사한다. Android 서비스 자체, 실제 getter/Binder,
P01 이벤트·C 창·Logcat 전달 성공을 JVM에서 재현했다고 주장하지 않는다.

NOT_EXECUTED / attempted=false / productionReady=false, F0 정책·수집 예산·기존
거부 조건·원본 14개·Dialog/레이아웃 시험·provider 보안 계약은 그대로 유지한다.
JVM 합성 회귀는 Android Binder·실제 P01 차단 원인·Logcat 실측을 대신하지 않는다.
