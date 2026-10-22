# F1 관측 F0 보호 순회 기록 — collector 지역 상태

기준은 `feature/f1-popup-detection` / `e6b1a91`입니다. 이 단계는 실제 collector가 관측한 보호 트리의 **기존 순회가 반환했다는 사실을 일시적으로 기록**합니다. 화면 전체의 F0 완전성·개인정보 부재·원자성·현재성·TTL·최종 클릭 안전성의 증명이 아닙니다.

## 소유권과 범위

`AndroidSnapshotCollector.capture()`가 매번 별도 `ObservedProtectionPass`를 생성합니다. owner, root ticket, pass 완료 타입은 collector 내부 private 일반 클래스이며 copy/외부 factory/UUID/합성 receipt로 생성하는 경로가 없습니다. Kotlin 접근 제한이 reflection이나 같은 프로세스의 악성 코드를 차단하는 보안 경계라는 뜻은 아닙니다.

- 이미 읽은 `WindowSnapshot`의 application 창 ID만 owner에 기록합니다. 새 framework getter를 호출하지 않습니다.
- 실제 target 및 다른 application root 열거 지점에서 TARGET / SAME_APP_BACKGROUND / EXCLUDED_FOREIGN을 등록합니다. 분류는 실제 처음 읽은 package와 기존 외부 창 차단식을 통과한 경로에서만 만듭니다.
- 해제용 `roots`는 그대로입니다. 기존 지역 실행 목록 `rootGuards`에는 guard와 root에 대응하는 빈 ticket 참조만 더했습니다. 이 실행 목록은 기존과 같이 capture 지역이며 Android 객체를 포함하지만 **관측 증거 payload가 아닙니다**.
- owner는 창 ID·역할·ticket·반환 여부만 보유합니다. ticket/완료 객체에는 instance payload가 없습니다. node/window·본문·좌표·지문·토큰·예외·callback을 owner나 완료 payload에 보관하지 않습니다.
- 창 열거 완료 후 scope를 고정합니다. 관측 application ID 집합과 등록된 전체 root ID 집합(제외 타사 포함)이 일치하고 target이 정확히1개여야 합니다. 누락·중복·교체·32 초과·잘못된 상태 순서는 기록을 폐기합니다.

## 완료와 폐기 위치

상태는 REGISTERING → SCOPE_FIXED → PASS_RECORDED 또는 DISCARDED입니다. root별 완료는 `ProtectionRootGuard.inspect()`의 **보호 callback 안에서 실제 `walk()` 정상 반환 직후**만 기록합니다. guard의 정상 반환 자체로 완료를 기록하지 않습니다. 허용된 타사 배경 창은 callback을 실행하지 않으며 완료로 세지 않습니다.

모든 등록 보호 root가 같은 owner에서 발급된 ticket으로 정확히1회 완료되어야 `finishPass()`가 private `ObservedF0Completion`을 생성합니다. 다른 owner/미발급/null ticket, 중복 완료, 빠진 보호 root 또는 역행은 기록을 폐기합니다. 제외 타사 ticket을 완료시키는 것도 거부합니다. 관측하지 못한 실제 창·자식까지 발견하거나, caller가 구성한 합성 모델을 인증하는 기능은 없습니다.

실패·취소·조기 return·정상 종료 모두 `capture()`의 기존 finally에 들어가며, 그 첫 동작으로 owner를 폐기합니다. root/window의 기존 해제 순서보다 먼저 completion/entries/관측 창 집합을 비웁니다. 따라서 framework 정리에서 예외가 발생해도 이 owner의 증거 참조는 먼저 폐기됩니다. ticket은 빈 객체이며 원래 지역 실행 목록도 호출 종료와 함께 수명이 끝납니다. JVM heap의 즉시 물리적 소거를 보장하지 않습니다.

## 기존 동작과 분리

관측 기록 메서드는 수집 허가를 반환하거나 기존 Ready/Blocked의 조건을 바꾸지 않습니다. 새 scope 모순이 감지되면 **기록만 미발급/폐기**하며 기존 수집 결과는 그대로 유지합니다. 기록 미발급을 Ready 차단 조건으로 만드는 단계는 승인하지 않았습니다. 기록을 반환 결과에 넣거나 전역 상태/관측 provider/서비스에 공개하지 않습니다.

CollectionAttempt.syntheticReview, CollectedTreeEvidence, CollectionProjection, B 판단, A 행동 게이트와 연결하지 않습니다. 실제 클릭·캡처·OCR 연결도 없습니다.

기존 F0 필드/순서, ProtectionRootGuard 정책, walk/inspect, 두 pass의 공유2400 방문·100ms, snapshot/DTO/V1 지문/salt/token/최초 실패 진단과 catch는 유지합니다. 새로운 deadline 검사·추가 root 재조회·제3의 전체 Android 순회는 없습니다. 창 DTO 최대32개를 살펴보는 bounded 기록과 root당 완료 기록 비용만 추가됩니다. 실제 성능·100ms 실행 보장은 계측하지 않았습니다.

## 취소와 정리 예외의 남은 한계

기존 catch(Exception)은 CancellationException도 Blocked로 처리합니다. 이번에는 이를 변경하지 않았으며, 해당 경우에도 finally에서 owner가 폐기되도록 했습니다. 고정 오류 코드 외 새 로그/진단/예외 메시지를 만들지 않습니다. 실제 Job 취소를 관측하는 검사나 세션/설정 무효화 callback도 추가하지 않습니다. 서비스 호출자 검증 후 취소 전파 경계를 별도 승인해야 합니다.

기존 walk의 child finally 또는 capture의 framework 해제 자체가 예외를 던지면 원래 실패/취소가 가려지거나 뒤의 해제가 실행되지 않을 가능성은 남습니다. 이번에는 그 순서와 예외 처리를 바꾸지 않습니다. CollectionProgress의 최초 위치 기록은 유지합니다. 추가한 owner 폐기는 capture의 finally에서 capture 수준 root/window 해제보다 먼저 실행됩니다. walk의 child finally는 순회 도중 capture의 finally에 진입하기 전에 실행되므로, owner 폐기가 child finally보다 먼저라는 뜻은 아닙니다. 정리 예외로 바뀐 최종 예외까지 원래 실패와 같다고 주장하지 않습니다.

기존 시간 제한은 방문 시점에 검사합니다. 마지막 getter/정리 중 지연이나 창 열거 전체 시간을 새로 판정하지 않습니다. 기록은 기존 예산 검사를 포함한 순회 반환 사실이며 전체 capture가100ms 안에 끝났다는 증거가 아닙니다.

## 회귀의 범위

`ObservedProtectionPassTest`는 private owner를 **JVM 시험에서만 reflection으로 접근**합니다. 운영 공개 factory·안전 증명 API·test hook을 추가하지 않습니다. 실제 scalar ProtectionRootGuard/CollectionProtectionCheck와 합성 callback을 사용해 제외 창/숨김 민감 자식/소유권 실패·한도/예외·취소·최초 진단을 검사합니다. 실제 Android walk/getChild/서비스/객체 recycle/Ready 발행을 실행한 시험이 아닙니다.

별도 소스 비교로 기존 blocked/snapshot/Ready/catch, inspect/walk/예산/지문 코드 및 framework getter 수/해제 순서 보존을 확인합니다. 이것도 실제 Android 동작 동등성·시간 비용의 계측 증명은 아닙니다.

다음 실제 Android 검증에서는 합성 target+동일 앱 배경, 숨김 입력/민감 자식, 창 전환·서비스 종료, 실제 해제/실패 후 미발급을 확인해야 합니다. 현재 private 기록을 외부 관측하는 경로가 없으므로, 발급 자체의 직접 계측에는 별도 debugger 또는 안전한 테스트 전용 관측 설계가 필요합니다. 원래14개 시나리오와 기대 결과를 바꿔 이를 대신하지 않습니다.

## 미해결 P1과 F1 상태

1. 실제 화면 전체의 F0/숨김·배경 coverage와 관측 사이 root/자식 변경의 완전한 탐지.
2. 실제 검사 값·대상 기록·salt/토큰/구조·본문·outline 지문의 동일 시도 결합.
3. 서비스 세션·정책 철회·실제 Job 취소·화면 현재성·TTL.

Scroll 역전 Rect, 별도 Window/frame 불일치, TIME_LIMIT/TARGET_MISSING, 기존14개 UNOBSERVED=14는 미해결입니다. 이 기록이 생겼다는 이유로 snapshot geometry 거부를 통과시키거나 F1 감지 정확도/캡처/OCR 성공을 주장하지 않습니다. Android 계측은 이번 환경에서 실행하지 않습니다.
