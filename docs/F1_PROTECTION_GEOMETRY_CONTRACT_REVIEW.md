# PR #7: 보호 검사와 geometry 완전성 계약 검토

결론: **현재 운영 차단을 유지합니다.** 독립 증거·projection·지문 codec은 Android 수집기에 연결하지 않았습니다. `9922c80` 이후 기존 보호 pass root 소유권 검사(0-C)와 `e6b1a91` 이후 collector 지역 관측 기록(0-F)을 적용합니다. 기록은 기존 반환 경로와 독립적입니다. CollectionAttempt/receipt 연결·새 반환 경로·부분 F1 관측은 여전히 후속 설계이며, 두 pass·예산·지문 규격·F1/원래14개 정답은 유지합니다. 이후 절의 독립 모델 구현 기록은 당시 범위를 설명합니다.

## 0-F. collector 지역 관측 F0 순회 기록 — e6b1a91 이후

[관측 보호 순회 기록](F1_OBSERVED_PROTECTION_PASS.md)을 추가합니다. capture마다 private owner가 실제 window/root 열거에서 target/동일 앱 배경/제외 타사를 등록하며, 보호 callback의 실제 walk 정상 반환 직후에만 root 완료를 기록합니다. 제외 guard의 정상 반환은 완료가 아닙니다. 전체 보호 root가 정확히1회 완료된 경우 private pass 기록을 만들고, 정상 종료를 포함한 모든 finally에서 framework 해제 전에 폐기합니다.

추가 getter·deadline 검사·세 번째 Android 전체 순회 없이 기존 snapshot/DTO/지문/Ready/Blocked/catch/진단/해제 순서를 유지합니다. 새 기록의 실패는 기존 수집 결과와 독립적이며, 합성 CollectionAttempt/projection/행동/캡처/OCR로 연결하지 않습니다. 전체 F0/현재성/TTL/최종 클릭 안전성의 증명이 아닙니다. 취소 예외를 일반 Blocked로 처리하는 기존 동작과 실제 Job 취소 관측 문제는 후속으로 남깁니다.

## 0-E. 미연결 합성 코드 P2 보강 — a341dd6 이후

[합성 계약의 P2 보강](F1_COLLECTION_ATTEMPT.md#합성-코드-p2-보강--a341dd6-이후)만 적용합니다. target 입력 조회부터 projection/상태 기록까지 동일 예외 경계로 보호하고 실패·취소는 폐기합니다. identity 문자열은 내용 검사 전에 지문128/package256/popup128자 상한으로 검사하며 초과 시 IDENTITY_LENGTH_LIMIT로 전체 거부합니다. 실제 V1 지문64자리와 기존 합성 label은 그대로 보존합니다.

실제 F0/배경 coverage, 같은 시도의 검사 값·토큰/salt/지문 결합, 세션/정책/취소/현재성/TTL의 P1은 미해결입니다. AndroidSnapshotCollector/서비스/Runtime/운영 F0/F1·두 pass/예산/진단·공통 계약/BCD/PR10·원래14개·캡처/OCR/클릭 경로는 수정하거나 연결하지 않습니다. 새로운 projection의 거부/진단을 운영 collector와 동등하다고 주장하지 않습니다.

## 0-D. CollectionAttempt 합성 계약과 배경/대상 분리 — 00c7223 이후

[CollectionAttempt 계약](F1_COLLECTION_ATTEMPT.md)을 구현했습니다. owner의 private 일반 receipt/완료 handle, 정방향 상태·동일 owner·1회 소비·최초 실패·폐기를 JVM에서 검증합니다. 유일한 factory는 합성 검토용이고 모든 상태에서 productionReady=false이며 소비는 고정 enum만 반환합니다. 실제 Android reader/보호 완료/현재성/세션/TTL은 미연결입니다.

보호 root의 미인증 관측과 target 전체 노드 기록을 분리했습니다. 배경 geometry/actions/outline/body는 요구하지 않습니다. 순수 projection의 Complete는 StructuralMatch로 바꿔 실제 완료와 구별합니다. 이전0/0-A절의 모든 root 노드 보존·배경 geometry 검사·Complete 명칭은 **이전 prototype 기록**이며 현재 계약은 연결된 문서를 따릅니다. 창/root32·보존 target2400·깊이/자식/필드/본문/경로 상한은 유지하지만 배경 실제 F0 검사·방문 예산은 미래 reader가 증명해야 합니다. 이는 운영 보호 완화가 아니며 기존 collector/service/Runtime·F0/F1·원래14개는00c7223에서 미변경입니다.

## 0-C. 등록한 보호 root의 검사 생략 방지 — 9922c80 이후

기존 `roots`는 해제 소유 목록이었으며 정책상 제외 가능한 타사 root도 포함했습니다. 보호 pass는 package를 다시 읽어 대상 package와 같을 때만 walk하므로, 최초 동일 앱이던 root가 null/다른 package로 읽히면 검사를 건너뛸 수 있었습니다. 이를 실제 기기에서 재현했다고 주장하지 않습니다.

`ProtectionRootGuard`는 열거 당시의 보호 대상 여부와 기대 window를 이번 capture 호출 안에서만 기록합니다. **F0 완료 receipt·CollectionAttempt·현재성 인증이 아닙니다.** Android 객체나 콜백을 보유하지 않고, collector의 지역 목록이 기존 root 참조와 guard를 묶습니다. 모든 root/window는 기존 소유 목록과 finally에서 해제합니다.

| 경우 | 처리 |
|---|---|
| 대상 root | 최초 package와 window ID에 고정. 창 선택/대상 창 제외에도 최초 ID 사용. 다른 root/창으로 대체하지 않음 |
| 최초 동일 앱 배경 root | 보호 대상으로 고정. 재조회 package null/불일치는 UNREADABLE_TREE / PACKAGE_MISMATCH로 전체 거부 |
| 보호 root window 소속 불일치 | UNREADABLE_TREE / ROOT_WINDOW_MISMATCH / ROOT_WINDOW_READ로 거부 |
| 보호/제외 root package getter 실패 | 기존 generic 예외 처리로 UNREADABLE_TREE / COLLECTION_FAILED. NODE_PACKAGE_READ와 고정 예외 종류만 보존 |
| 보호 root window getter 실패 | 같은 예외 처리, ROOT_WINDOW_READ로 구분 |
| 비활성·비포커스 타사 앱 + capture isolation 꺼짐 | 기존 열거 조건 그대로 제외 가능. 같은 타사/null/다른 타사로 재조회되면 기존 skip 유지. 이 제외는 보호 완료 인증이 아님 |
| 타사 앱 + active/focused 또는 capture isolation 켜짐 | 기존 FOREIGN_APPLICATION 차단 그대로. 새 guard 등록 전에 종료 |
| 제외했던 root가 보호 pass에서 대상 package로 변경 | 보호 범위 변경이므로 PROTECTION_SCOPE_CHANGED로 전체 거부. 추가·대체·정상 완료하지 않음 |
| 보호 root의 walk 내부 package 재조회가 null | 보호/snapshot 두 pass 모두 PACKAGE_MISMATCH로 거부. 비root 자식의 기존 package-null 정책은 바꾸지 않음 |

기존 최초 실패의 phase/tree/field/operation·reason을 유지하며 새 finding 2개와 operation 1개만 추가했습니다. host의 고정 코드 allowlist도 이 enum만 허용하도록 확장했습니다. package·window ID·좌표·본문·지문·salt·예외 메시지를 진단으로 보내지 않습니다. 기존 CollectionResult.Blocked 처리로 종료하므로 실패한 시도는 snapshot 생성/Ready와 F1 assess에 도달하지 않습니다. Ready/Blocked 타입이나 서비스 분기는 바꾸지 않았습니다.

전체 보호 pass와 대상 snapshot pass, 공유2400 방문/100ms·나머지 상수, 숨김/배경의 모든 자식과 모든 기존 F0 필드 검사는 유지합니다. 기존 walk/예산/지문 계산/DTO 생성/해제 코드와 외부 앱 열거 차단식은 그대로입니다. 새 root window getter 비용은 기존 공유 시간 예산 안에 들어가며 예산을 재설정하지 않습니다. 검사 추가가 실제100ms 이하 실행이나 원자적 화면을 보장하지는 않습니다.

**JVM 범위:** 신규 guard 시험19개는 정상 대상/배경, package 변경/null/예외, window 변경/예외, 제외 root 유지/범위 변경, root의 두 번째 package 읽기, 합성 숨김 민감 자식에 대한 기존 F0 실패 전파, 최초 진단·후속 단계 중단·고정 출력 표현을 검사합니다. host formatter 시험1개를 추가했습니다. scalar getter/순회 callback 시험이며 실제 Android collector, CollectionResult.Ready 미발행, framework 객체 recycle 또는 F1 이벤트 발행을 기기에서 검증한 것은 아닙니다. 실제 서비스의 실패→Blocked→detector.clear 경로는 소스 검토로 확인했습니다.

**Android 계측 미검증:** 동일 앱 background/target의 소유권 변경·package null·getter 오류, 실제 root/window 해제 및 후보 미발행은 추가 기기 시험이 필요합니다. 공개 API로 결정론적 package 변경을 만들 수 없는 경우에는 해당 fault를 합성 reader 시험으로만 표시하고 기기 재현으로 보고하지 않습니다. 기존 Scroll/MINIMAL 비교와14개 F1 시험은 계속 별도로 실행하며, baseline 차단이나 UNOBSERVED를 성공으로 바꾸지 않습니다. 이 수정으로 Scroll 역전 Rect/Window-frame 문제 또는 F1 정확도를 해결했다고 주장하지 않습니다.

**남은 신뢰 경계:** root 재조회 사이의 변경·배경 내용 변경·새 창 등장, snapshot 보호 후 다시 읽은 본문/childCount, 토큰/지문/세션/TTL 결합·취소는 이번 범위 밖입니다. 실제 CollectionAttempt/보호 receipt, 신규 projection/codec 연결은 하지 않았습니다. 보호 완료 플래그를 채워 이 문제를 대신하지 않습니다.

## 0-A. 독립 게이트 상한·할당 보강 — 8496608 이후

운영 수집기에는 여전히 연결하지 않습니다. 아래 한도 대응을 **수정 전에 확인**하고, 원래 collector의 검사 순서·상수·두 pass를 바꾸지 않은 채 신규 A 내부 변환기에만 적용했습니다.

| 항목 | 기존 AndroidSnapshotCollector | 신규 순수 게이트에 적용한 범위 |
|---|---|---|
| 창 | 전체 목록 최대32 | metadata 창·선언 보호 root·기록 root 각각 최대32. 큰 목록을 순회/복사하기 전에 검사 |
| 노드 | 보호/대상 snapshot 두 pass 공유 방문 최대2400 | 모든 root의 **보존 노드 합계** 최대2400. 덧셈 전 잔여량 비교로 overflow 방지. 실제 중복 방문 수를 재현하는 한도는 아님 |
| 깊이 | root=0, 최대60 | 원래 preorder의 열린 부모 수로 깊이 계산, 60 허용/61 거부 |
| 자식 | 노드당 최대1200 | 원래 childCount 최대1200. 누락·음수·잘못된 topology는 계속 거부 |
| 필드 | 보호 검사 각 필드 최대4096 UTF-16 코드 단위 | 모델에 보존되는 text/description/viewId/className 각각4096. 숨김·배경도 동일. 나머지 보호 필드는 모델에 없으므로 실제 reader가 검사해야 함 |
| 본문 합계 | 대상 snapshot의 text+description 최대32768 | 대상 root에만 동일 합산, Long 사용. 기존에 없는 배경 합계32768 제한은 추가하지 않음 |
| 경로 | `0` 아래 ordinal0..1199, 최대60단계에서 생성 | 최대 `1 + 60*(1+4) = 301`자. parentPath도 검사. 실제 ordinal/부모/preorder는 따로 검증 |
| 시간/취소 | 기존 공유100ms·노드 방문 시 검사 및 서비스 무효화 | **미구현/동등성 미입증**. 크기 상한은 100ms 보증·코루틴 취소 검사가 아님 |

노드당1200은 기존 **자식 상한**이고, 두 pass 공유2400은 **방문 상한**입니다. 보호 pass와 snapshot pass가 시간적으로 다를 수 있어 신규 모델의 target 노드 수에 임의의1200 제한을 넣거나, `전체 보존 노드 + target 노드`를 실제 방문 수라고 단정하지 않았습니다. 합성 기록2400개가 게이트를 통과해도 그 forest를 기존 두 pass가 실제 예산 내 수집했다는 뜻은 아닙니다. 새 상한은 필요조건 일부이며 새로운 완료 인증이 아닙니다. 이 차이와 배경 합계 제한의 부재를 유지하고 불필요한 추가 거부 정책을 만들지 않았습니다.

`completePreorder()`는 자식별 Expected/full-path 문자열을 한꺼번에 생성하지 않습니다. 원래 노드 참조와 다음 자식 ordinal을 가진 **열린 부모 stack만 O(depth)**로 유지합니다. 경로는 substring/연결 없이 prefix·slash·최대4자리 canonical 숫자를 직접 비교합니다. 누락/중복/순서/부모 오류는 계속 전체 거부합니다. 숫자 선행0·부호·비숫자·과도한 segment도 거부합니다. 노드/path 길이 검사는 이 작업 전에 적용합니다.

geometry 집계는 전체 노드 flatMap 복사를 없애고 중첩 순회로 수행합니다. UNKNOWN/빈 root가 먼저 나와도 뒤의 malformed가 있으면 INCOMPLETE인 기존 우선순위를 유지합니다. DTO 생성은 persistent builder로 한 번씩 수행하고 노드/outline의 동일 bounds를 재사용합니다. 문자열 원문은 복사·정규화하지 않고 기존 참조/필드를 보존합니다. 이 상한은 이미 만들어진 입력 문자열의 할당을 막거나 전체 heap 사용량을 보증하지 않습니다. token·지문 metadata의 출처/인증/수명 문제도 해결하지 않습니다.

새 모델/완료 wrapper는 redacted, 거부는 고정 enum만 반환하며 원문을 담은 예외를 만들지 않습니다. 신규 시험은 메모리 스트림에서 stdout/stderr 무출력과 합성 sentinel의 문자열 비노출, 한도 경계값·기존 snapshot/frame 전체 필드 보존을 확인합니다. **기존 DTO를 꺼내 직접 문자열로 출력하면 여전히 노출될 수 있습니다.** 공통 DTO는 수정하지 않았으며 이 한계까지 해결했다고 주장하지 않습니다. 새 코드에 로그/파일/DB/행동 API 호출을 추가하지 않았습니다.

P1(실제 전체 F0 및 배경 coverage, 동일 시도 token/구조·본문·outline 지문 결합, 실제 서비스 세션·화면 무효화·TTL)은 그대로 미해결입니다. 완료 enum이나 새 가짜 지문으로 해결하지 않았습니다. 실제 Android collector와 DTO/지문 byte 동등성 및 추가 비용 검증도 후속입니다. 원래14개 baseline·정답·관측과 Scroll/Window 거부를 변경하지 않습니다.

## 0. 1단계 구현 — 운영과 연결하지 않은 구조 검증

신규 파일은 `accessibility/CollectedTreeEvidence.kt`, `accessibility/CollectionProjection.kt`와 대응 JVM 테스트 두 개입니다. 기존 `core/model`·`core/contract`, B/C/D, 운영 service/Runtime/collector, PR #10 행동 게이트를 변경하지 않았습니다.

| 내부 표현 | 구현 및 거부 조건 |
|---|---|
| 보호 완료 | 관측 전체·각 대상/배경 root·각 노드의 `ProtectionCompletion`. 기본 UNKNOWN이며 전부 COMPLETED_WITHOUT_BLOCK이어야 함. 숨김 여부는 예외가 아님 |
| 순회 완료 | 관측 전체·각 root의 `TreeCompletion`, 개별 `NodeReadCompletion`. UNKNOWN/실패/누락 자식/시간·개수·깊이 한도/취소는 거부 |
| geometry 완료 | `CollectedRect` 원래 네 성분에서 계산. 역전 X/Y/양축, 누락 rect 또는 Int extent overflow는 불완전. 가시성으로 바꾸지 않음 |
| 원래 기록 | immutable 전체 노드, 원래 path/부모/형제 순서/childCount, 본문·설명·ID·class·actions·pane/가시성/scroll·outline 지문 보존. malformed 부모의 자식도 삭제하지 않음 |
| 보호 root 범위 | 선언된 전체 보호 창 집합과 기록된 root 집합이 정확히 일치하고 target 하나가 원래 token 창과 같아야 함. 누락/중복/추가 root 거부 |
| topology | 각 root의 원래 preorder와 childCount에 따른 모든 자식, parent/path/ordinal을 확인. 자식 삭제·재정렬·고아·중복·부적절 childCount 거부 |
| DTO 투영 | 위 전체 조건과 metadata/창 관계 검사를 통과한 경우에만 모든 target 노드로 PopupSnapshot과 DetectionFrame 생성. 하나라도 실패하면 고정 `ProjectionRejection`만 반환, 두 DTO 모두 없음 |

geometry COMPLETE는 **기록된 노드 경계가 V1 DTO로 표현 가능**하다는 뜻입니다. V1과 같이 순서가 맞는 0 크기/음수 위치의 노드 성분은 그대로 보존하며, 이를 화면 안의 버튼이나 팝업으로 인정하지 않습니다. 창/논리 frame은 별도로 zero-origin·양수 크기·정확한 포함 관계를 검사합니다. margin/tolerance/clamp/좌표 이동은 없습니다. 정상 Dialog와 malformed 노드가 섞인 경우도 전체 거부합니다. 배경 root의 불완전 geometry도 이번 보수적 게이트에서는 거부합니다. 이는 기존 collector의 background 처리와 동등하다고 주장하지 않습니다.

**신뢰 경계:** 이 순수 변환기는 공급된 완료 표시·root 범위·token·지문이 실제 Android 화면에서 정직하게 수집됐는지 입증할 수 없습니다. 보호 검사를 실행하거나 누락된 실제 창/노드를 발견하는 reader가 아닙니다. 합성 fixture가 채운 완료 값은 합성 시험의 입력일 뿐입니다. 향후 A 소유 reader가 모든 숨김/배경 노드와 password/editable/inputType 및 모든 기존 텍스트 필드의 F0 검사를 실제로 완료해야 합니다. 필드/getter/자식 실패·민감/불확실 결과를 완료로 채우면 안 됩니다. F0-B·동의·앱별 허용·세션/TTL·화면 변경·가림·ROI·버퍼 대응의 실제 검증도 이 gate 밖이며 아직 연결되지 않았습니다.

결과 `ProjectionOutcome.Complete`는 기존 `CollectionResult.Ready`가 아니며 Candidate/보호 허가/실행 허가가 아닙니다. detector/pipeline/B/C/D/행동 API를 호출하지 않습니다. 모델과 gate의 호출 지점은 신규 JVM 시험뿐입니다. 원래 지문과 token은 바꾸거나 새로 만들지 않고 전달하지만 **지문을 재계산·인증하지 않으며 V1 byte 동등성도 검증하지 않았습니다**. malformed 기록은 기존 V1 지문과 호환된다고 주장하지 않고 외부로 전달하지 않습니다.

민감 raw 성분·본문·노드 경로·지문은 A 내부 RAM 값이며 새 모델/metadata/완료 wrapper의 `toString()`은 모두 고정 redacted입니다. 거부는 enum만 포함하고 입력을 예외 메시지로 만들지 않습니다. 로그/DB/파일/IPC 출력 코드는 없습니다. 추출한 기존 DTO(특히 DetectionFrame/WindowSnapshot)의 기존 문자열 표현까지 안전하게 바꾼 것은 아니므로 DTO를 로깅해서는 안 됩니다. 실제 Android 취소/무효화/짧은 수명·참조 폐기 owner는 다음 연결 단계의 필수 조건이지 이번 모델이 자동 보장하는 기능이 아닙니다.

JVM 회귀는 역전 축·가시/비가시·보호/geometry 독립성, 전체 거부·원래 기록 보존, 보호/순회/노드 실패·한도, root/topology 누락, 창 관계 거부 및 문자열 비노출을 검사합니다. **합성 통과는 Android 수집 성공이나 기존 collector와의 완전한 동등성 입증이 아닙니다.** Scroll의 MALFORMED_NODE_BOUNDS, 별도 WINDOW_OUTSIDE_FRAME, TIME_LIMIT/TARGET_MISSING 및 원래14개 UNOBSERVED는 여전히 미해결입니다. 이 커밋 설치만으로 baseline 복구를 기대하지 않습니다.

## 1. 이번 실제 API 37 결과가 확인한 것

사용자 제공 `2b407da` 실측이며 개발 환경에서 직접 재실행한 결과가 아닙니다.

| 시험/구간 | 실제 결과 | 해석 한계 |
|---|---|---|
| 독립 비교 전체 | OK (1 test) | 대조 관측 완료. F1 정확도/운영 수집 복구 아님 |
| ORIGINAL_SCROLL / OBSERVED_STABLE | MALFORMED_ONLY, REVERSED_Y/NOT_VISIBLE/SCROLL_ANCESTOR/DESCENDANT, bounds age OLDER | 역전 노드 거부는 시작 직후에만 생기는 현상이 아님 |
| MINIMAL / LAUNCH_WINDOW | READY_ONLY, FRAME=MIXED. LEFT 초과 후 VALID/BASELINE_ONLY | 같은 fixture에서도 시도별 창 관계가 달랐음. 애니메이션 원인 확정 아님 |
| MINIMAL / OBSERVED_STABLE | READY_ONLY, VALID_ONLY, NO_POPUP/ORDINARY_CONTENT_CHANGE | 이 대조 화면/구간의 완전 수집 및 비후보 관측. 일반 앱 보증 아님 |
| MINIMAL native | SCROLLER=NO, CLIPPED_DESCENDANT=NO, MALFORMED_NATIVE=NO | 대조 화면이 의도한 조건을 충족함 |
| 독립 UiAutomation | 두 fixture 모두 관측 시 창/논리 화면 일치 | 실제 서비스와 다른 샘플. 서비스 실패를 취소하거나 그 시도가 정상이라고 해석할 수 없음 |
| 독립 비교 결론 | MALFORMED_ISOLATED_TO_SCROLL, SCROLL_FRAME=UNOBSERVED, MINIMAL_FRAME=VALID_ONLY | Scroll의 안정 구간은 snapshot 거부로 F1 frame 판정까지 못 갔음. UNOBSERVED는 VALID가 아님 |
| 원래 F1 14개 | FAILURES, UNOBSERVED=14, DETECTION_SUCCESS=0, OCR=NOT_TESTED/ACTIONS=NONE | baseline에서 멈춰 감지율/미탐/오탐 평가 불가 |

원래 시험의 지속적인 malformed, 다른 시도의 WINDOW_OUTSIDE_FRAME(RIGHT 또는 RIGHT,BOTTOM), 일부 TIME_LIMIT 및 TARGET_MISSING은 각각 별도 사실입니다. LEFT와 RIGHT/BOTTOM을 한 화면의 이동량/변환으로 합치지 않습니다.

## 2. 현재 코드에서 정확히 막힌 위치

- `core/model/PopupModels.kt`: `ScreenBounds`는 성분 순서를 요구합니다. `NodeSnapshot.bounds`는 필수이며 `SnapshotCompleteness`에는 COMPLETE/TRUNCATED만 있습니다. '트리는 모두 읽었지만 특정 geometry를 표현할 수 없음'을 담을 필드가 없습니다.
- `AndroidSnapshotCollector`: 전체 보호 pass를 먼저 수행하고 snapshot pass의 해당 노드도 다시 inspect합니다. 이후 malformed Rect에서 전체 결과를 거부합니다. 따라서 이 실패가 전체 보호 순회를 건너뛰었다는 뜻은 아니지만, 두 pass가 같은 순간의 원자적 화면이라는 뜻도 아닙니다.
- `CollectionResult.Ready`는 `PopupSnapshot`과 `DetectionFrame`을 함께 요구합니다. malformed를 그대로 보존한 NodeSnapshot을 현재 생성할 수 없습니다.
- `PopupCandidateDetector`: 구조 지문, 원래 path/조상, scrollable 조상, 의미 있는 Dialog/pane와 underlay를 사용합니다. 현재 frame에는 '제외된 geometry가 있음'이라는 coverage가 없고, bounded-window 분기는 outline 평가보다 먼저 후보를 만들 수 있습니다. 부분 frame을 그대로 넣으면 안전한 불확실성 처리가 보장되지 않습니다.
- `CloseForYouAccessibilityService`: Ready의 평가가 후보이면 Runtime.begin 후 pipeline을 호출하고, 완료 결과를 공통 Candidate로 발행합니다. 보호 전용 결과를 Ready로 위장하면 이 기존 연결 경로에 들어갑니다.
- `EvidencePolicy`는 COMPLETE가 아니면 거부합니다. 그러나 `CaptureOcrPipeline.partial()`도 접근성 노드 정보로 Ready 분석을 만들 수 있으므로, 'TRUNCATED/INCOMPLETE_EVIDENCE를 붙여 넘기면 모든 downstream이 알아서 막는다'는 설계는 충분하지 않습니다. 불완전 관측은 기존 pipeline **호출 이전**에 차단해야 합니다.
- 원래 14개 baseline은 실제 collection 결과를 요구하고, 하나라도 block이면 실패합니다. 현재 관측 bridge는 full Ready 이후의 assessed만 기록합니다. 부분 결과를 새 assessed로 세어 기존 baseline을 통과시키는 것은 기존 의미를 바꾸므로 이번에는 하지 않습니다.

숨김 노드를 삭제하면 원래 childCount와 DTO 목록/경로/지문이 어긋날 수 있고, 부모 geometry가 잘못됐어도 자식 버튼/팝업 의미가 존재할 수 있습니다. 경계를 정렬하거나 0 크기로 채우는 것은 원시 증거를 바꿉니다. 현재 화면·기존 COMPLETE·노드 보존·geometry 불완전 시 비승격을 모두 유지하면서 이 Scroll baseline을 기존 Ready로 만드는 수정은 입증되지 않았습니다.

## 3. A 내부 분리 설계안 — 공개 계약 변경 없이 가능한 범위

다음은 **후속 연결을 포함한 설계안**입니다. 0절의 순수 모델/변환만 구현했으며, 수집 pass/수명/지문 재계산 및 F1 연결은 미구현입니다. 합의·회귀 전에 collector에 연결하지 않았습니다.

```text
PrivateObservation  // A만 소유. PopupSnapshot/PopupAnalysis/ActionRequest 타입이 아님
  epoch, originalTarget, transientLifetime
  treeCoverage: COMPLETE | INCOMPLETE
  protectionChecks: COMPLETED_WITHOUT_BLOCK | INCOMPLETE
  geometryCoverage: COMPLETE | INCOMPLETE
  nodes: 모든 NodeRecord, 원래 순서 그대로
  privateStructureDigest, privateContentDigest, fingerprintSchema

NodeRecord
  originalPath, parentPath, siblingOrdinal, originalChildCount
  viewId/class, clickable/enabled, 원래 action/팝업 의미/가시성/scroll 근거
  원래 text/description (A의 짧은 RAM 수명, 외부 전달/저장/출력 금지)
  geometry: Valid(ScreenBounds) | Unusable(originalRawRect, axisShape)
```

RawRect는 순서 제약을 강요하지 않는 A 전용 불변 네 성분입니다. 화면 원문·원시 좌표·private digest·실제 ID를 진단/IPC/파일/DB에 전달하지 않습니다. Android node는 원래 순회 수명 안에서 해제합니다. geometry unusable 노드도 NodeRecord에서 삭제하지 않고 모든 자식을 계속 보호 검사해야 합니다. 패스워드/입력/민감/불확실 텍스트를 발견하거나 필드/자식/시간·개수 제한 검사에 실패하면 전체 폐기하며 '보호 완료'를 발급하지 않습니다. 가시성은 geometry의 사용 근거 중 하나일 뿐 보호 검사 면제가 아닙니다.

COMPLETED_WITHOUT_BLOCK은 검사들이 완료됐다는 뜻이며 개인정보 없음의 보증/실행 권한이 아닙니다. 화면 변경/터치/만료/미확인 자식/수집 실패 시 전체 관측·지문·파생 결과를 폐기합니다. 같은 앱 배경 트리의 보호도 그대로 포함합니다.

### identity·지문 호환

- 현재 사용 중인 collection-local path/viewId/class 기반 identity와 원래 부모·자식 순서/childCount를 그대로 보존합니다. 삭제·재번호 매김·잘못된 부모 경계를 이유로 자식 생략을 허용하지 않습니다. identity가 전역적으로 안정됐다고 주장하지 않습니다.
- V1으로 변환 가능한 완전 관측에서는 구조/본문 지문의 필드 순서·null/length 인코딩·salt·본문 기여를 기존과 동일하게 유지하고 byte-level 동등성을 시험해야 합니다.
- unusable 원시 성분을 해시할 때도 버리거나 정렬하지 않습니다. V1이 만들 수 없던 관측에 기존과 호환되는 지문이 있다고 주장하지 않습니다. 별도 schema/namespace가 필요한지 합의하고, 그 private 지문으로 D 규칙을 조회/저장하거나 B의 동일 팝업 검증을 통과시키지 않습니다.

### 변환·분석 게이트

| 경계 | 허용 조건/실패 동작 |
|---|---|
| PrivateObservation → V1 PopupSnapshot | 전체 노드/보호 검사/geometry가 모두 완전하고 현재 세대·대상·창 검증을 통과한 경우만. 기존 V1을 그대로 생성. 그 외 기존 Blocked 유지 |
| PrivateObservation → F1 내부 분석 | tree/protection incomplete이면 분석 금지. geometry incomplete이면 bounded-window/pane/underlay 분기 **이전**에 불확실로 종료하고 baseline 폐기. confirmed popup/NO_POPUP 확정 금지 |
| 불완전 관측 → B/C/D | 노드·원문·버튼 후보·지문·실행 가능한 토큰 전달 금지. 합의된 고정 상태 코드만 검토 가능 |
| 불완전 관측 → capture/OCR/pipeline | 호출 금지. F0-A 완료만으로 F0-B/ROI/버퍼 좌표/동의 검증을 대체하지 않음 |
| 불완전 관측 → 사용자 명령/규칙/실행 | 모두 금지. B의 선택/기억 결과로 승격할 수 없고 A의 최종 실제 화면 재검증도 그대로 필요 |

이 분리는 정보를 잃지 않고 거부 이유를 표현하는 설계입니다. **geometry가 여전히 불완전한 Scroll 화면을 팝업 탐지/정상 baseline으로 지원 완료하는 해법은 아닙니다.** 새 관측 상태를 기존 baseline의 성공으로 해석하지 않습니다.

## 4. 후속 연결·팀 합의 항목 — 공통 계약 변경 없음

| 항목 | 구체적인 합의 내용/담당 영향 |
|---|---|
| A 내부 중간 표현 | 승인된 1단계 순수 표현/변환만 구현. 실제 reader/짧은 수명·폐기/두 pass와의 결합, V1 지문 byte 동등성은 후속 검증. public V1을 유지하며 BCD 코드를 변경하지 않음 |
| 비공개 F1 coverage | 부분 geometry를 항상 불확실로 끝내고 Candidate로 발행하지 않는 상태 모델. 현재 DetectionFrame/Assessment에는 이를 표현하는 필드/사유가 없으므로 A 내부 변경과 회귀 필요 |
| C의 고정 불확실 상태 | 필요하다면 기존 PARTIAL_INFORMATION/token 없음 경로를 재사용할 수 있는지 C와 합의. 원문/부분 snapshot을 보내거나 음성 UI를 A가 대신 구현하지 않음 |
| D 진단/지문 | 보호 차단과 geometry 미확인을 구분할 고정 코드가 필요하면 SafeDiagnostics enum 변경을 D와 합의. private 지문은 저장/규칙 조회하지 않음. 기존 RuleKey 호환성을 임의로 변경하지 않음 |
| B의 입력 계약 | 현재 incomplete observation은 아예 전달하지 않는 안을 우선함. 향후 별도 read-only 타입이 필요해도 Candidate/CloseCandidate와 혼용 금지 및 미확인 시 deny를 B와 합의. NodeSnapshot.bounds를 nullable로 바꾸는 단독 변경은 금지 |
| QA 관측/완전성 | protection-complete/geometry-incomplete의 별도 관측 지표가 필요하면 A/D가 버전·실패 의미 합의. 원래 14개 시험을 이 상태로 통과시키지 않음. 기존 COMPLETE/정답 정의 변경은 이 작업의 승인 범위가 아님 |

공통 모델/계약 변경 없이 가능한 비공개 분리와, 공개 상태/지문/수집 완전성 의미를 바꾸는 합의 사항을 구별합니다. 0절 이외의 예시 타입/경로는 구현 완료를 뜻하지 않습니다.

## 5. 구현 전 필요한 회귀와 허용 기준

| 시험 | 반드시 유지할 결과 |
|---|---|
| 숨긴 암호/입력/민감 텍스트 + 역전 경계 | geometry 분석보다 보호 검사. 전체 거부, 원문/IR/분석 외부 전달 없음 |
| malformed 부모 + 유효한 자식 버튼 / 가려진 팝업 의미 노드 | 자식·action·의미·토폴로지 보존. partial geometry이면 F1 불확실 및 모든 downstream 금지 |
| 정상 Dialog와 malformed 노드 동시 존재 | 정상 Dialog를 놓치지 않았다고 주장하거나 window shortcut으로 popup 확정하지 않음. 전체 incomplete를 명시 |
| 모든 geometry 유효한 V1 fixture | 기존 snapshot/구조·본문 지문 필드 byte 동등성, 기존 분류/후보·규칙/토큰 계약 보존 |
| hidden/visible flag 변화, path 재사용, 순회 중 자식/본문/창 변경 | 원래 identity·세대/본문 검증 유지, 불일치 폐기. 재시도/좌표 fallback 없음 |
| missing child/getter 실패/node·depth·time 한도 | protection incomplete, 전체 폐기. 임계값 확대 없음 |
| incomplete IR에 사용자 명령/기억 규칙 적용 | Candidate/B 후보·캡처/OCR·실행 호출 횟수0. 단순 NO_POPUP으로 승격하지 않음 |
| 취소/터치/설정 철회/연결 해제/만료 | private 원문/원시 경계·모든 파생 참조 폐기, stale 결과 발행 없음 |

1단계 합성 JVM과 별도로 실제 Android API35/37의 원격 노드·가상 자식·민감 필드(합성)·창 전환·한도/취소 회귀가 필요합니다. 현재 실측은 native clipping 현상과 안전 거부를 확인했지만, 새 모델을 연결한 Android reader/adapter와 V1 지문 동등성·수명/시점 검증은 구현·시험하지 않았습니다.

## 6. 별개 창 geometry: 같은 시도와 같은 시점은 다름

현재 collector의 읽기 순서는 root → service.windows/WindowSnapshot → window metadata → AndroidScreenGeometry.read → 두 tree pass → DetectionFrame 구성입니다. WindowSnapshot과 frame은 한 capture 호출에 속하지만 Android가 원자적 화면 한 장으로 묶어 제공한 값은 아닙니다. ScreenGeometry.read의 before/after logical size/rotation 교차 검사는 그 frame 읽기 자체의 일관성만 검사하며, 앞에서 얻은 window bounds의 시점까지 보증하지 않습니다.

UiAutomation/host 샘플은 다른 연결·시점입니다. MINIMAL 시작 시도의 LEFT 초과와 안정 구간의 유효성, Scroll의 별도 RIGHT/RIGHT,BOTTOM 초과는 실제로 달랐던 관측입니다. 이 차이는 읽기 시점/창 상태/캐시·region/좌표 변환 경로 중 어디에서든 발생할 가능성이 있지만 **현재 코드와 출력만으로 어느 원인인지 확정하지 못합니다**. 나중 샘플이 일치했다는 이유로 서비스의 이전 좌표를 정상이라고 취소할 수 없습니다. 마지막 bounds 변경 timestamp도 진행 중 animation을 증명하지 않습니다.

필요한 후속은 같은 서비스 capture 안에서의 bounded 전후 일관성 조사입니다. 재시도 없이 동일 대상/동일 display의 window/frame을 다시 읽고, 기존 package/system/가림/화면 세대 검사를 함께 유지해야 합니다. 값이 다르면 현재 시도를 폐기해야 합니다. 고정 코드는 WINDOW_READ_CHANGED/UNCHANGED/UNAVAILABLE, FRAME_READ_CHANGED/UNCHANGED/UNAVAILABLE, TARGET_CHANGED/SAME/UNAVAILABLE처럼 관계만 후보로 삼습니다. UNCHANGED도 원자성/ROI/픽셀 대응 보증은 아닙니다. 이는 **미구현 진단 설계**이며 추가 framework 호출 비용까지 기존 시간 예산 안에서 검증하기 전 패치하지 않습니다.

동일 시도에서 변동이 입증돼도 '기다렸다가 괜찮아짐'을 운영 승인 조건으로 바꾸지 않습니다. 반복해서 같은 outside가 관측되면 여전히 미지원 관계입니다. tolerance/clamp/좌표 이동/전체 화면 캡처 우회/PR #6 버퍼 거부 해제는 허용하지 않습니다.

## 7. TIME_LIMIT와 TARGET_MISSING

- TIME_LIMIT는 TraversalBudget.visit가 두 pass에 공유되는 기존 100ms deadline을 넘은 별도 수집입니다. 같은 앱 배경 보호 순회도 예산을 사용합니다. 노드 수2400/깊이60/자식1200 상한 및 기존 검사 순서도 그대로입니다. 이 코드만으로 특정 getter·hash·Rect가 시간을 소모한 원인이라고 말할 수 없습니다. 초과 시 partial 결과를 성공 처리하지 않습니다.
- TARGET_MISSING은 root의 창 ID가 그때의 service.windows 목록에 없는 별도 창 목록 거부입니다. malformed/성능 문제의 원인으로 합치지 않습니다. 재시도나 다른 창 대체를 추가하지 않습니다.
- 후속 회귀는 fake monotonic clock으로 원래 `>` 임계 경계/두 pass 누적/상한 순서를 검증하고, 독립 합성 Android 시험에서 같은 시도의 STEP/OP/CAUSE로 한도 초과와 목록 불일치를 관측하는 방식입니다. 새로운 clock/collector 구현을 이번에 추가하거나 한도를 늘리지 않았습니다.

## 8. 현재 실행·미검증 범위

사용자의 독립 비교 OK와 14개 회귀 실패를 모두 사실로 보존합니다. 1단계는 운영과 연결하지 않은 순수 Kotlin 코드이며 새 Android 계측을 실행하지 않았습니다. 새 기기 결과나 추가 운영 개선을 주장하지 않습니다. 같은 운영 경로를 반복 실행하면 해결될 것이라는 재시험을 요구하지 않습니다.

필요할 때 기존 결과를 최소 재현할 Windows 명령(프로젝트 터미널, 빌드/3개 APK 설치 절차는 F1_BASELINE_ISOLATION.md):

```powershell
git switch feature/f1-popup-detection
git pull --ff-only origin feature/f1-popup-detection
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "emulator-5554" # 실제 API37 대상
# UI: 실제 서비스/수집 동의/host 단독 허용/분석 시작, 캡처 동의 OFF/캡처 목록 없음
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceBaselineIsolationOptInTest -e f1Isolation true -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceDetectionScenariosOptInTest -e f1Detection true -e captureOcrService true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

독립 probe는 관측 완료, 원래14개는 원래 정답/실패를 계속 의미합니다. TIME_LIMIT/TARGET_MISSING/노드/창 관계는 CASE/PHASE/ATTEMPT별로 따로 해석합니다. 전체 Logcat·이미지·실제 좌표·사용자 화면은 필요하지 않습니다. 두 문제가 해결되기 전 F1 정확도 검증 완료를 보고하지 않습니다.
