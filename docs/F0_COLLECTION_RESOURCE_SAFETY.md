# Collector 취소·자원 정리 경계 — 2026-10-21

이 문서는 초기 자원 정리 구현·검증 당시의 기록입니다. 실제 Job/정책 checkpoint와 window/frame 전후 비교, Runtime TTL 및 native OCR 수명에 대한 최종 후속은 [A 독립 안전성 후속](A_INDEPENDENT_SAFETY_COMPLETION.md)을 따릅니다. 아래의 당시 미연결/미커밋 설명을 최신 상태와 구분합니다.

기준은 `feature/f1-popup-detection`의 `f1de8cd`입니다. 과거 문서의 취소 삼킴/정리 예외 P2 기록에 대한 후속 변경이며, 운영 F1·geometry 문제의 해결 또는 실행 허가가 아닙니다.

## 변경

- `AndroidSnapshotCollector.capture`의 일반 Exception 처리 앞에서 CancellationException을 재전파합니다. 창 나이·선택적 좌표 진단·malformed 노드 가시성 진단의 예외 fallback에서도 취소를 재전파합니다. 호출 중인 `AndroidScreenGeometry.read` 역시 취소 시 캐시를 비우고 재전파하며, 일반 오류의 기존 UNAVAILABLE 처리는 유지합니다.
- capture와 child 방문 모두 실제 `CollectionResourceSafety.run`을 사용합니다. body의 취소는 동일 객체로 보존하고 일반 해제 오류로 덮지 않습니다. body의 일반 오류/보호 guard도 일반 해제 오류로 덮지 않습니다. cleanup 자체의 취소는 body가 이미 취소 또는 fatal Error인 경우를 제외하고 전파하며 Blocked로 바꾸지 않습니다. 원래 예외를 새 예외의 cause/suppressed나 진단에 붙이지 않습니다.
- child 정상 방문 후 해제만 실패하면 고정 `COLLECTION_CLEANUP_FAILED`가 기존 일반 차단 경계로 전달됩니다. `CHILD_RELEASE`의 첫 operation/예외 종류 기록은 유지합니다. 방문 자체가 먼저 실패하면 같은 공유 `visitChild` 경계가 cleanup 위치를 새 진단으로 기록하지 않습니다. 따라서 원래 progress 기록이 없던 guard에도 해제 위치를 섞지 않습니다. 자식 실패를 만난 순회는 계속 진행하거나 부분 Ready로 만들지 않습니다. 이미 획득한 조상 child 및 capture root/window의 정리는 계속 시도합니다.
- capture 종료 시 관측 owner를 먼저 폐기하고 root, window 순으로 각 해제를 독립적으로 시도합니다. 일반 해제 오류는 고정 실패로 축약하며 하나의 일반 오류/취소가 다음 객체의 해제를 막지 않습니다. 마지막에 지역 root/guard/window 목록을 비웁니다. 이 순서는 child의 중첩 cleanup보다 앞선다는 의미가 아닙니다.
- 기존 Blocked 결과는 정리 실패가 겹쳐도 그대로 반환해 원래 사유/진단을 보존합니다. 수집 body가 Ready였는데 정리에 실패하면 Ready를 버리고 기존 UNREADABLE_TREE/COLLECTION_FAILED로 차단합니다. 이 경우 root/window 해제 전용 operation enum을 새로 만들거나 실제 실패 원인을 추측하지 않습니다.

두 pass·숨김/동일 앱 배경 보호·package 정책·CheckedNodeRead·2400 방문/100ms·geometry 거부·salt/V1 지문·DTO·Service/Runtime·공통 계약·원본14개·행동/캡처/OCR 경로는 변경하지 않습니다. 원문/좌표/ID/지문/토큰·예외 원문을 출력/저장하는 경로를 추가하지 않습니다.

## 보장 범위와 남은 한계

JVM 회귀는 운영 capture/child와 같은 정리 함수 및 batch에 합성 callback 오류·취소를 넣는 시험입니다. 실제 Android recycle 예외나 기기 서비스 전체를 재현한 결과가 아닙니다. 코루틴 launch 호출자에서 전달된 CancellationException으로 Job이 취소되고 다음 발행 작업에 도달하지 않는 경우를 시험합니다.

이 변경은 **전달된 취소 예외**의 전파를 보완합니다. 동기 collector는 Job을 직접 조회하지 않으며 실제 Job 취소만으로 협력하지 않는 Binder/getter를 강제 중단하거나 100ms를 hard deadline으로 보장하지 않습니다. fatal VM Error는 일반 해제 오류로 복구하지 않으며 모든 자원 정리 완료를 보장하지 않습니다. 원래 Android 예외의 메시지는 고정 진단에 포함하지 않지만, 호출자는 재전파된 예외를 직접 출력해서는 안 됩니다.

실제 보호 범위 전체의 완전성, 원자적 화면/현재성·TTL·토큰/지문 결합, 실제 Android 취소/수명 검증은 여전히 필요합니다. Scroll malformed Rect와 Window/frame 불일치, 원본14개 UNOBSERVED는 미해결입니다. API37 수정 버전은 미실행이며 기존 Dialog/Layout 사용자 실측을 이번 버전의 성공으로 재사용하지 않습니다. 커밋·푸시·병합은 하지 않습니다.

## 이번 로컬 검증

신규 공유 경계 회귀28개를 포함한 JVM894개(앱458/호스트436)가 실패·오류·skip0으로 통과했습니다. 기존866개 기록과 구분합니다. 양쪽 Debug/AndroidTest APK·앱 Release 빌드, 앱 Debug/Release 및 호스트 Debug Lint(각 이슈0), 기존 패키징 검사와 신규 helper의 앱 Debug/Release 전용 DEX 정의 검사를 통과했습니다. JVM 테스트 클래스는 APK에 포함되지 않습니다. diff 및 신규 미추적 파일의 공백 검사도 수행했습니다.

정상 반환·조기 차단·최초 진단·일반/취소 해제 오류의 우선순위, 여러 자원의 독립 해제, 정리 실패 시 Ready 거부, malformed/예산 guard 유지, 고정 문자열·무출력을 공유 경계에서 확인했습니다. 기존 CheckedNodeRead/ObservedProtectionPass 회귀는 오래된 취소 설명과 시험 이름만 정정했으며 기존 기대값을 완화하지 않았습니다. Android framework 객체의 실제 release 오류·서비스 Job.cancel·기기상의 성능/수명은 미검증입니다.
