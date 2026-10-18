# F1 CollectionAttempt — 합성 계약 검토 전용

기준 `feature/f1-popup-detection` / `00c7223`. 이번 구현은 실제 Android 수집기 연결이 아닙니다. AndroidSnapshotCollector, 서비스/Runtime, F0/F1, 두 pass·시간/방문 예산, 지문 codec/규격, 캡처/OCR/행동 실행, 공통 계약/BCD/PR10 및 원래14개 시나리오를 변경하지 않습니다.

## 생성 권한과 의미

`CollectionAttempt` 생성자는 private이며 유일한 factory는 `syntheticReview()`입니다. 서비스 세션/UUID/완료 상태/PRODUCTION 모드를 인자로 받지 않습니다. 모든 단계에서 `productionReady=false`입니다. 이 factory를 호출할 수 있어도 실제 F0 완료 증거나 운영 결과를 만들 수 없습니다.

- `ProtectionRootEvidence`: window/role/보호·순회 상태만 가진 **미인증 입력**입니다. data class/copy가 가능하지만 owner receipt가 아닙니다. supplied COMPLETE 값을 실제 Android F0로 해석하지 않습니다.
- `SyntheticProtectionReceipt`: owner의 private 일반 클래스만 생성합니다. 완료된 **합성 관측 검토**를 나타내며 실제 보호 증명이 아닙니다. owner identity 참조와 발급 map의 동일 객체를 모두 검사합니다. 같은 root ID·같은 값·같은 토큰은 owner identity를 대체하지 못합니다.
- `SyntheticReviewCompletion`: owner의 private 일반 클래스만 생성합니다. payload getter/copy/serialization factory가 없는 opaque handle입니다. 다른 attempt handle 또는 재사용은 거부합니다.
- 운영용 완료 타입은 이번에 제공하지 않습니다. `consumeSyntheticReview()`는 `REVIEW_CONSUMED` 또는 `REJECTED` enum만 반환합니다. PopupSnapshot/DetectionFrame/CollectionResult.Ready/실행 권한을 반환하지 않습니다.
- 순수 projection의 기존 `ProjectionOutcome.Complete`는 `StructuralMatch`로 변경했습니다. 이는 공급된 입력의 구조 검증과 DTO 변환 결과이며 `productionReady=false`입니다. 직접 구성 가능한 structural DTO wrapper를 receipt나 운영 완료로 받아들이는 API는 없습니다.

이것은 Kotlin 접근 제한과 객체 소유권에 따른 오용 방지입니다. `internal`만으로 다른 팀 코드를 격리하거나 같은 프로세스의 reflection/악성 코드를 방어한다고 주장하지 않습니다. 합성 scope/본문/토큰·지문은 여전히 외부 fixture 입력입니다. 동일한 raw fixture를 새 attempt에 다시 제출하는 것까지 실제 화면 출처로 인증하거나 탐지하지 않습니다. 서로 다른 owner가 발급한 receipt/완료 handle 혼합은 거부하지만, raw 입력의 실제 Android 출처 결박은 P1 미해결입니다.

## 정방향 상태 전이

| 상태 | 진입 조건 |
|---|---|
| STARTED | 합성 factory 호출 |
| SCOPE_FIXED | 비어 있지 않은 최대32 root, 창 ID 중복 없음, target 정확히1개. roster를 제한된 크기로 복사 |
| PROTECTION_REVIEWED | 등록한 target/같은 앱 배경의 모든 합성 보호·순회 관측이 완료. 발급한 receipt를 빠짐/중복 없이 같은 owner에게 제출 |
| TARGET_RECORDED | 해당 target의 전체 노드 기록과 metadata가 순수 projection 보호 상태·순회·topology·geometry·상한 검사를 통과 |
| FINAL_REVIEWED | owner 내부 기록이 존재하고 단계 순서가 유효. 합성 완료 handle 발급 |
| CONSUMED | 동일 owner의 정확한 완료 handle을1회 소비. 결과는 고정 enum만 |
| DISCARDED | 순서/범위/소유권/불완전/검증 실패, 취소·무효화·폐기 |

실패 시 잔여 단계를 재시도하지 않으며 DISCARDED에서 완료 상태로 복귀할 수 없습니다. CONSUMED도 재사용/재시작하지 않습니다. 최초 실패와 최초 projection 거부 사유를 보존하고 후속 오류가 덮어쓰지 않습니다. 상태 전이는 monitor로 직렬화하며 중복 소비 경쟁에서 성공은 최대1회입니다. 입력 읽기 뒤 상태를 다시 검사해 reentrant 무효화 후 진행하지 않습니다.

`cancel()`/`invalidate()`는 합성 driver가 호출하는 상태 전이입니다. 실제 coroutine Job·접근성 이벤트·서비스 종료를 관측하지 않습니다. 입력 복사/projection에서 CancellationException을 받으면 폐기 후 고정 메시지의 새 CancellationException으로 전파합니다. 입력 예외의 메시지·cause·suppressed는 보존하지 않으며 민감한 원본을 진단에 전달하지 않습니다. Android 이벤트와 상태 변경의 원자성, 실제 화면 현재성, 세션 또는5초 TTL을 보장하지 않습니다.

## 배경 보호와 대상 기록 분리

`CollectedTreeEvidence`는 보호 root 관측 목록과 `target: CollectedRootEvidence`를 따로 보관합니다.

- 보호 범위는 target + 같은 앱 배경 root입니다. 순수 gate는 선언 목록/관측 목록/target/token의 관계를 검사합니다. 실제 창 목록 누락을 발견하는 reader는 아닙니다.
- 배경 관측에는 nodes/body/actions/geometry/outline 필드가 없습니다. 배경 geometry getter를 새로 요구하거나 빈 노드/가짜 경계로 채우지 않습니다.
- 대상에는 원래 preorder/부모·자식/childCount/본문/actions/outline/원시 geometry의 모든 기록을 보존합니다. hidden 상태는 보호 검사 면제가 아닙니다.
- 배경의 보호·순회 미완료는 대상 geometry가 정상이어도 거부합니다. 보호 관측이 모두 완료되어도 target의 malformed/누락 geometry는 거부합니다.
- 기존 prototype에서 배경의 개별 노드 본문 길이·topology·geometry를 직접 검사하던 계약은 없어졌습니다. 실제 배경 F0를 생략해도 된다는 뜻이 아닙니다. 후속 reader가 모든 숨김/배경 자식과 password/editable/inputType 및 모든 보호 필드를 검사한 경우에만 실제 증거를 발급해야 합니다. 이번 합성 receipt로 그 의무를 충족했다고 주장하지 않습니다.

상한은 창/보호 root32, 실제 보존 target 노드2400, 깊이60, 자식1200, 경로301, 네 보존 필드4096, target 본문+설명32768을 유지합니다. 배경 노드 자체를 더는 보존하지 않으므로2400은 target 기록에 적용합니다. 이전 target+background 기록 합산 시험을2400/2401 target 기록과 payload 없는 background 시험으로 바꿨습니다. 이것은 두 pass의 실제 방문 수 `2T+B`나100ms를 보장하는 모델이 아닙니다. 운영 collector의 상수와 검사는 그대로입니다.

## 데이터 수명과 진단

owner는 완료 결과의 DTO 참조를 외부로 내보내지 않습니다. 소비·거부·취소·무효화 시 scope/receipt map/projected DTO/완료 handle 참조를 해제합니다. 호출자가 이미 가진 fixture/receipt 참조나 JVM heap을 즉시 소거한다는 보장은 없습니다. 불변 raw 문자열의 물리적 소거도 보장하지 않습니다.

owner/key/receipt/완료/관측/구조 결과 wrapper의 문자열 표현은 redacted 또는 고정 상태 코드입니다. 원문·좌표·노드 경로·토큰·지문·salt를 로그/파일/DB/IPC로 출력하지 않습니다. 공통 DTO를 꺼내 직접 toString하는 기존 위험은 그대로이며 공통 DTO 자체는 수정하지 않습니다.

## 회귀와 다음 연결 조건

JVM은 상태 순서, scope 누락/중복/변경, 다른 attempt receipt/완료 혼합, 중복/동시 소비, 모든 비최종 단계의 취소·무효화, 최초 실패, 불완전 target, 보호/geometry 독립성, malformed/상한/필드 보존, 제한된 입력 복사·재진입·예외, 참조 해제 및 문자열/출력 비노출을 검사합니다. 기존 보호/상한 회귀는 새로운 구조 성공 의미에 맞게 갱신합니다. 최초 targeted 시험에서는 필드 형태 검사에 컴파일러의 정적 필드까지 포함해 실패했고, 실제 instance 필드만 검사하도록 시험을 수정했습니다. 생산 보호 조건을 완화해 맞춘 변경이 아닙니다.

실제 reader 연결 전 필수:

1. 실제 창 열거에서 보호 범위를 확정하고 모든 숨김/같은 앱 배경 자식을 검사한 사실에서만 실제 receipt 발급. 합성 factory를 생산 factory로 이름만 바꾸지 않음.
2. 검사한 scalar와 DTO/지문 값 일치, childCount/순회 일관성, 같은 시도의 salt/token/지문/정책 revision/서비스 session 결박.
3. 실제 무효화/취소/TTL과 전후 root/window/frame 변화 검사. 이벤트 지연과 원자적 화면 부재의 한계 명시.
4. 기존 두 pass만 사용하고 공유100ms/2400 방문 안에서 비용 실측. 세 번째 Android 전체 순회 금지.
5. 정상 fixture의 기존 DTO/지문 byte·최초 진단 동등성, 새 거부 조건 구분, 실제 서비스에서 실패 시 후보/캡처/OCR 호출0·객체 해제 계측.

이번에는 Android 계측을 실행하지 않습니다. Scroll malformed/Window-frame/TIME_LIMIT/TARGET_MISSING 및 원래14개 UNOBSERVED는 미해결입니다. 합성 계약 통과는 실제 F0 완료·F1 정확도·캡처/OCR 성공이 아닙니다. 운영 호출 경로에 연결하지 않았으므로 같은 기기 결과를 반복해 성공으로 확인할 단계가 아닙니다.
