# A 독립 안전성 후속 — 2026-10-21

기준 `feature/f1-popup-detection` / `f1de8cd`. 시작 당시 미커밋10개와 JVM934개 검증 기록을 보존하고 이어서 작업했습니다. 초기 독립 구현은 미커밋으로 검토했고, 2026-10-22 최종 검토·검증 후 A 소유24개 파일만 기존 PR #7 브랜치에 반영합니다. PR은 병합하지 않습니다. B/C/D·공통 모델/포트·Manifest·gradle.properties·원본14개·기존 독립 Android 계측과 PR10 행동 게이트는 변경하지 않습니다.

## 이번에 운영 경로로 연결한 범위

### 보호/순회

대상 및 같은 앱 배경의 첫 보호 pass도 기존 `CheckedNodeRead.read`의 제한된 불변 복사·보호 검사를 재사용합니다. package/password/editable/inputType와9개 문자열 필드를 기존 순서대로 검사합니다. 숨김 자식을 제외하지 않으며 배경에 bounds/actions/outline getter를 추가하지 않습니다. 반환된 노드 문자열 wrapper는 보호 pass에서 즉시 버리고 보호 완료 인증으로 사용하지 않습니다. snapshot pass의 검사값/DTO·본문/구조/outline 지문 재사용은 그대로입니다.

childCount 음수는 빈 자식 목록으로 받아들이지 않고 거부합니다. snapshot/outline에 기록한 count, 실제 순회의 count와 자식 순회 후 count가 다르면 기존 MISSING_CHILD로 거부합니다. 자식1200/방문2400/깊이60·100ms는 그대로이고 추가 조회와 검사 비용도 기존 예산에서 소비합니다. 정상 count에서는 V1 필드/순서/salt/해시·토큰 형식을 유지합니다. 같은 개수의 자식 교체나 미관측 ABA를 탐지했다고 주장하지 않습니다.

### 취소/현재성

서비스 pending collection/evidence coroutine의 실제 Job을 `ScreenFreshness.checkActive`에 묶습니다. 취소/세대/연결·사용자 조작 상태를 검사하고 collector의 진입, 각 방문/서브트리 종료, 동일 시도 최종 검사, 결과 반환/정리 후에도 확인합니다. 새 이벤트를 **A가 수신하면** 기존 무효화 정책을 유지해 대기/분석/expiry 작업을 취소합니다. 예외를 삼키지 않습니다. 큐에 아직 전달되지 않은 Android 이벤트나 blocking Binder 호출 자체를 중단하지는 못합니다.

`isCurrent`는 current preferences의 수집 동의·일시정지·앱 허용도 다시 확인합니다. 정책 observer가 늦게 처리되더라도 철회된 collection 결과를 계속 쓰지 않게 합니다. 캡처 동의는 별도이며 tree 전용 경로를 자동으로 끄거나 캡처를 켜지 않습니다.

collector의 각 예산 checkpoint에서도 실제 대상 package gate를 재검사합니다. Runtime의 현재 토큰/발행/추출도 현재 서비스·세대·정책 guard를 직접 확인하므로, observer의 무효화가 아직 처리되지 않은 때의 정책 철회도 거부합니다. guard는 Runtime을 재호출하지 않으며 연결 종료 시 참조를 버립니다. 마지막 확인 이후의 물리적 화면 변화와의 원자성을 뜻하지 않습니다.

### Runtime 발행 수명

`AccessibilityRuntime`은 순수 Kotlin `LiveEvidenceState`를 실제로 소유합니다. `begin(snapshot)`이 이번 연결/현재 화면의 비공개 객체 identity permit을 발급하며, DTO enum/UUID/토큰만으로 이전 발행 작업을 재사용하지 않습니다. permit은 A 내부 작업 identity일 뿐 F0 receipt·사용자 승인·실행 허가가 아닙니다. 공통 SnapshotSource/EvidenceExtractor/PopupEvent 계약은 그대로입니다.

- 동일 token의 snapshot/detection/analysis와 COMPLETE snapshot, 원래 snapshot 필드를 결박합니다. 기존 DecisionEvidence의 노드 전체 삭제 요약만 해당 고정 issue에서 허용합니다.
- permit에는 실제 evidence Job의 취소 상태도 결박합니다. 정상 완료한 Job은 이미 발행한 분석을 TTL까지 유지하고, 취소한 Job은 늦은 commit/flow 전달을 거부합니다. 실제 `deliver` 경계에서 시간·현재 정책·취소를 다시 확인하고 그 직후 Candidate를 emit합니다. 단순 분석 저장 완료를 이벤트 발행 성공으로 세지 않습니다. Flow 전달/스케줄링 및 소비자의 화면과 원자적으로 결합된다는 뜻은 아니며 소비자는 수신 후에도 현재 토큰/TTL을 확인해야 합니다.
- 같은 화면 세대에서 새 UUID로 begin을 반복하거나, 같은 permit으로 publish를 반복해 TTL/발행을 연장하지 않습니다. 실제 popup의 장기 동일성을 증명하거나 A 행동 게이트의 실행 기록을 대체하지 않습니다.
- 최초 snapshot의 monotonic capture 시각부터5000ms 미만이어야 begin/publish/extract할 수 있습니다. 정확히5000ms부터 거부합니다. publish 직전 시간을 다시 읽고, 서비스 expiry timer도 이 원래 만료 시각을 사용합니다.
- 토큰 교체/차단/무효화/연결 종료/재연결 시 내부 원문 참조를 버립니다. 오래된 permit의 완료나 차단으로 새 연결의 분석을 덮거나 버릴 수 없습니다. extract는 실제 발행한 snapshot과 현재 TTL에 맞는 결과만 반환합니다.
- 분석 작업의 finally는 발행 실패/취소 시 자기 permit의 중간 상태를 폐기합니다. 이전 작업의 늦은 finally는 새 작업의 분석을 지우지 못하며, 정상 발행 성공을 즉시 무효화하지 않습니다. 결정 요약을 발행한 뒤에는 원래 노드 snapshot 대신 실제로 발행한 redacted snapshot만 보유합니다.
- Runtime의 상태 변경과 flow 발행은 같은 monitor에서 직렬화합니다. replay0/버퍼1/소비자의 Invalidated·Blocked·TTL 후 폐기 계약은 유지합니다. 소비자가 이미 보유한 immutable DTO를 물리적으로 소거하거나 화면 변화와 원자적으로 결합하지는 못합니다.

### 캡처/OCR

Pipeline은 TRUNCATED snapshot을 거부하고, 모든 조기 tree/요약/오류 fallback 및 frame close 이후에도 Job/토큰 현재성을 확인합니다. 취소를 close 오류로 바꾸지 않고 최초 privacy Blocked 사유도 보존합니다. 정상 결과의 frame 정리가 실패하면 성공으로 반환하지 않습니다.

OCR 진행 중 동의/앱 허용/서비스 capability/확대 상태를 기존 정책대로 재검사합니다. 진행 중 작업의 budget permit에 스스로 BUSY가 되지 않도록 사용량 검사와 진행 중 권한 검사를 분리했습니다. 캡처 동의가 철회되면 OCR을 폐기하고, 최신 F0 검사/현재성을 통과한 트리만 기존 불완전 근거로 제공합니다. collection 동의/현재성도 잃으면 후보를 발행하지 않습니다. INCOMPLETE_EVIDENCE는 자동 승인이나 F0 우회가 아닙니다.

Android screenshot callback은 software/cropped/wrapped bitmap·hardware buffer의 정리를 독립적으로 시도하며, 실패한 callback의 pixels를 넘기지 않습니다. 실제 buffer.close 정상 반환 때만 source released를 기록합니다. pixel erase/recycle 또는 source buffer 정리 성공이 확인되지 않으면 메모리 permit을 **격리해 재사용하지 않습니다**. 재시도로 회복하거나 slot을 늘리지 않습니다. 이 경우 캡처 기능이 프로세스 재시작 전까지 이용 불가일 수 있으며 안전을 위해 유지합니다. 트리 전용 분석/보호는 계속 별도 판단합니다. native 메모리를 실제로 해제/소거했다는 보장은 Android 검증이 필요합니다.

ML Kit Task 완료 callback은 recognizer·bitmap lease 정리 이후에만 결과를 전달하며, 정리 실패를 OCR 성공으로 바꾸지 않습니다. native Task가 아직 실행 중이면 취소/timeout이어도 lease를 임의로 풀지 않습니다. Task 완료 전 메모리 해제는 use-after-free 위험이 있어 구현하지 않습니다. 실제 Task가 완료되지 않을 때의 강제 종료/프로세스 수명까지 보장하지 않습니다.

2026-10-22 최종 리뷰에서 제출/리스너 등록 예외가 native 완료를 뜻하지 않는데도 lease를 풀 수 있던 경계를 수정했습니다. `CollectionTaskLease`를 실제 제출 직전에 표시하고 실제 Task 완료 callback에서만 해제합니다. 제출 전 오류·취소는 해제할 수 있지만, 제출 이후 예외는 다음 정상 입력이나 취소만으로 lease를 풀지 않습니다. 프로세스 공용 단일 strong owner가 아직 끝나지 않은 lease를 유지하고 추가 OCR은 거부합니다. 완료 callback을 받을 수 없는 장애라면 프로세스 종료 전까지 bitmap/permit이 격리될 수 있습니다. 이는 즉시 소거·강제 종료의 보장이 아닙니다. 정상 완료 때는 참조를 버립니다. 취소의 recognizer close와 완료 callback의 close를 직렬화해, 다른 스레드가 아직 진행 중인 close를 완료로 오인하지 않게 합니다. 해당 공유 lease의 제출 전/후 예외·중복 완료·정리 실패/재제출 거부·비노출7개를 JVM으로 검사하며 실제 ML Kit 장애 재현을 뜻하지 않습니다.

## 보류 및 팀 경계

합성 CollectionAttempt/Projection을 실제 보호 receipt나 Ready/Candidate에 연결하지 않았습니다. 위 전후 scalar·childCount 검사와 관측 보호 pass 기록만으로 전체 원격 트리 coverage·자식 identity·배경 상태·동일 시점 원자성·토큰/지문의 Android 출처를 증명할 수 없기 때문입니다. malformed 노드는 계속 전체 차단하며 노드 삭제·clamp·부분 Ready·timeout 증가·자동 재수집을 추가하지 않습니다. Scroll/Window 문제와 원본14개 UNOBSERVED는 여전히 미해결입니다.

B 분류/닫기 후보와 C 오버레이·안내 소비자는 연결하지 않습니다. 자체 오버레이 이벤트를 예외로 인정하는 정책도 만들지 않습니다. 실제 B/C 구현 반영 및 수명/사용자 선택 계약 합의 뒤 별도 통합이 필요합니다. D의 실제 반복 규칙·팝업 동일성 기록과 실행 허가는 별도 합의 사항입니다. 실제 클릭/제스처/닫기/자동 반복 호출은 없습니다.

## 검증 범위

가짜 시계와 합성 DTO로 실제 Runtime 소유 상태를 테스트하며, 실제 Pipeline의 fake frame/OCR/동의·현재성 콜백도 테스트합니다. 실제 서비스에서 사용하는 ScreenFreshness/Job 검사와 childCount·cleanup/pixel permit 함수를 동일하게 호출합니다. 기존 보호/geometry/golden 회귀를 재사용하고 같은 의미의 시험을 반복 생성하지 않습니다. JVM이 Android Binder·ML Kit Task·Bitmap/HardwareBuffer의 실제 장애를 재현한다고 주장하지 않습니다.

실제 API37 계측은 이번 환경에서 미실행입니다. F0 보호 우선순위, Prepared Dialog·LAYOUT_CHANGE 및 원본14개를 새 APK로 재시험해야 합니다. 특히 취소/정책 철회/서비스 재연결·TTL, window 전환/회전, native Task 지연과 정리 실패, 추가 getter 비용에 의한100ms 거부를 실제 Android에서 검증해야 합니다. 기존 사용자 로그를 이번 버전의 성공으로 재사용하지 않습니다.

## 로컬 검증 결과

이전 독립 구현의 JDK17·offline Gradle 기록은 앱556개/호스트436개, 합계992개 통과였습니다. 2026-10-22 최종 리뷰에서 공유 native lease7개를 더해 전체 JVM **999개(앱563/호스트436), 실패·오류·skip0**을 다시 확인했습니다. 최초934개에 추가한 기존58개는 LiveEvidenceState36개, ScreenFreshness/childCount10개, pixel release3개와 Pipeline9개이며, 이번 최종 회귀를 합해65개입니다. 선행 작업의 자원 정리28개·window/frame40개와 CheckedNodeRead 및 V1 golden 지문 회귀를 유지합니다. Android 객체의 실시간 동등성 증명은 아닙니다.

앱 Debug/Release, 앱/호스트 AndroidTest 및 호스트 Debug APK 빌드와 앱 Debug/Release·호스트 Debug Lint가 성공했습니다. Lint XML 이슈는 모두0입니다. `tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk` 및 DEX 정의 직접 검사로 host/driver/운영 APK 격리를 확인했습니다. JVM 전용 신규 회귀 클래스는 APK에 포함되지 않습니다. 보호·수명 helper는 의도한 A 운영 APK에만 정의됩니다.

시작 시 파일 해시와 비교해 B/C/D·공통 계약·모든 Manifest·gradle.properties·기존 Android 계측을 변경하지 않았습니다. 기존10개 작업을 보존했고 자원 정리 helper에는 공통 pixel release와 native Task lease 함수를 추가했습니다. tracked/untracked 공백 검사와 `git diff --check`를 수행합니다. 아래 목록은 커밋 전 상태입니다. 연결된 Android 기기는0개이며 계측은 미실행입니다.

최신 main `4aa5a19`와 PR #7의 기존 HEAD `f1de8cd`를 별도로 조회했습니다. 공통 조상 이후 main의 B/D·빌드 변경은 이번 A 운영 소스 변경과 경로가 겹치지 않으며 main을 합치지 않았습니다. 검증은 F1 브랜치 기준이고 최신 main과의 통합은 PR CI에서 별도 확인합니다.

## 누적 변경 파일 전체

기존 미커밋 작업을 포함해24개입니다. `M`은 HEAD 대비 tracked 수정, `??`는 untracked이며 staging하지 않았습니다.

```text
M  app/src/main/java/kr/closeforyou/accessibility/AccessibilityRuntime.kt
M  app/src/main/java/kr/closeforyou/accessibility/AndroidScreenGeometry.kt
M  app/src/main/java/kr/closeforyou/accessibility/AndroidSnapshotCollector.kt
M  app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt
M  app/src/main/java/kr/closeforyou/accessibility/ScreenFreshness.kt
M  app/src/main/java/kr/closeforyou/accessibility/capture/AndroidScreenshotManager.kt
M  app/src/main/java/kr/closeforyou/accessibility/capture/CaptureOcrPipeline.kt
M  app/src/main/java/kr/closeforyou/accessibility/capture/KoreanMlKitOcr.kt
M  app/src/test/java/kr/closeforyou/accessibility/CheckedNodeReadTest.kt
M  app/src/test/java/kr/closeforyou/accessibility/ObservedProtectionPassTest.kt
M  app/src/test/java/kr/closeforyou/accessibility/capture/CaptureOcrPipelineTest.kt
M  docs/DEVELOPMENT_STATUS.md
M  docs/F1_PROTECTION_GEOMETRY_CONTRACT_REVIEW.md
?? app/src/main/java/kr/closeforyou/accessibility/CollectionChildCountCheck.kt
?? app/src/main/java/kr/closeforyou/accessibility/CollectionResourceSafety.kt
?? app/src/main/java/kr/closeforyou/accessibility/CollectionWindowFrameCheck.kt
?? app/src/main/java/kr/closeforyou/accessibility/LiveEvidenceState.kt
?? app/src/test/java/kr/closeforyou/accessibility/CollectionContinuityTest.kt
?? app/src/test/java/kr/closeforyou/accessibility/CollectionResourceSafetyTest.kt
?? app/src/test/java/kr/closeforyou/accessibility/CollectionWindowFrameCheckTest.kt
?? app/src/test/java/kr/closeforyou/accessibility/LiveEvidenceStateTest.kt
?? app/src/test/java/kr/closeforyou/accessibility/capture/CaptureResourceLifetimeTest.kt
?? docs/A_INDEPENDENT_SAFETY_COMPLETION.md
?? docs/F0_COLLECTION_RESOURCE_SAFETY.md
```
