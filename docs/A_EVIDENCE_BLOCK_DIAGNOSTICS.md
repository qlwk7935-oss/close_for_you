# A Debug evidence 차단 진단

P01에서 확인된 최초 collector Ready / F1 POPUP_DETECTED 이후의
`EvidenceOutcome.Blocked`를 구분하기 위한 로컬 진단이다. 원인을 수정하거나
후보 발행·C 표시의 성공 기준을 바꾸지 않는다.

## 출력 범위

Debug source set에만 `A_EvidenceBlock` Logcat sink가 있다. Release의 동일 port는
진단 객체를 만들지 않고 출력하지 않는다. 기존 evidence 작업마다 별도 객체를 쓰며
원인과 실제 Blocked의 reason/issue를 고정 enum으로만 보관한다. DTO, Android 객체,
본문, 패키지명, 창 ID, 좌표, 시각, 토큰, 지문, 예외는 보관하거나 출력하지 않는다.

실제 Blocked를 받은 작업만 기존 block/abandon 처리 뒤 최대 한 줄을 출력한다.
일반적인 로그 출력 실패는 원래 차단을 바꾸지 않으며, 취소는 성공으로 바꾸지 않는다.
취소·예외로 해당 출력 지점까지 도달하지 못할 수 있으므로 로그 부재는 성공 증거가 아니다.
출력 줄은 evidence의 반환 결과이며, 오래된 작업의 Runtime.block 수락까지 증명하지 않는다.

| SOURCE | 의미 |
|---|---|
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

다음은 **형식 예시이며 실제 관측 결과가 아니다.**

```text
A_EVIDENCE_BLOCK;SOURCE=RECOLLECTION_FAILED;CHECK=NONE;REASON=UNREADABLE_TREE;ISSUE=PRIVACY_BLOCKED;COLLECTION_PHASE=SNAPSHOT_PASS;OPERATION=TREE_BUDGET;FINDING=TIME_LIMIT
A_EVIDENCE_BLOCK;SOURCE=SNAPSHOT_MISMATCH;CHECK=WINDOW_SET;REASON=UNREADABLE_TREE;ISSUE=PRIVACY_BLOCKED;COLLECTION_PHASE=NONE;OPERATION=NONE;FINDING=NONE
A_EVIDENCE_BLOCK;SOURCE=TEXT_PROTECTION;CHECK=NONE;REASON=SENSITIVE_CONTENT;ISSUE=PRIVACY_BLOCKED;COLLECTION_PHASE=NONE;OPERATION=NONE;FINDING=NONE
```

## 향후 한 번의 P01 관측에서 확인하는 방법

이 작업에서는 Android 시험을 실행하거나 추가 수동 재시험을 요구하지 않는다.
향후 승인된 P01 한 번을 관측할 때, 변경한 Debug APK의 동일 서비스 process를 대상으로
Android Studio Logcat에서 `tag:A_EvidenceBlock`으로 필터링한다. 원문을 표시할 수 있는
광범위 로그·객체 debugger dump는 수집하지 않는다. 기본 정보 header까지 제외한 고정
코드 줄만 터미널에서 보려면 Windows PowerShell에서 다음 필터를 사용할 수 있다.

```powershell
& $adbPath -s $serial logcat -v raw -s 'A_EvidenceBlock:I' '*:S' |
    Select-String '^A_EVIDENCE_BLOCK;'
```

Logcat 연결 직후 출력되는 과거 버퍼 줄은 제외하고, 향후 해당 P01 관측 중 새로
도착한 줄만 확인한다. 관측 시작 이전의 기록을 이번 결과로 재사용하지 않는다.
별도 창 조작·P01 버튼 누르기·
접근성 클릭·제스처·자동 반복 없이 해당 한 번의 관측 구간만 확인한다.
SOURCE로 세 원인을 나누고, SNAPSHOT_MISMATCH는 CHECK,
RECOLLECTION_FAILED는 REASON 및 COLLECTION_PHASE/OPERATION/FINDING을 확인한다.
고정 enum만으로 미분류된 하위 원인은 미확정으로 남긴다.

여러 줄은 서로 다른 evidence 작업일 수 있다. 식별자를 출력하지 않으므로 다른 줄의
원인·사유를 합치거나 특정 P01 ATTEMPT와 원자적으로 연결됐다고 주장하지 않는다.
진단은 B/C 전달 성공·F0 완전성·화면 현재성·TTL·행동 안전성을 인증하지 않는다.
로그 쓰기 비용은 0이 아니지만 기존 수집/비교 및 차단·폐기 이후에만 출력한다.

NOT_EXECUTED / attempted=false / productionReady=false, F0 정책·수집 예산·기존
거부 조건·원본 14개·Dialog/레이아웃 시험·provider 보안 계약은 그대로 유지한다.
JVM 합성 회귀는 Android Binder·실제 P01 차단 원인·Logcat 실측을 대신하지 않는다.
