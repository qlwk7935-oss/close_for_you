# A 이전 C 창 정리 장벽 — #29

기준: `4f67ee6`, 로컬 `fix/issue29-overlay-cleanup-barrier`. PR #35·#36과 main은 변경하지 않는다.

## 상태와 안전 경계

`OverlayCleanupBarrier`는 `OwnedOverlayBinding`과 별개인 A의 서비스 수명 상태다.
정상 상태에서 관측 허가를 발급하고, C 정리 의도가 발생하면 기존 허가를 폐기한다.
정리 중에는 새로운 F0 수집을 기다리며, 이미 시작한 수집·OCR의 begin/publish 경계에서도
동일 허가가 여전히 유효한지 검사한다. 기존 snapshot/Candidate를 대기 뒤 재사용하지 않는다.

`CleanupOverlayListener`는 C keeper의 `onDismissed`를 감싸므로 A dismiss와 C 버튼의 직접
제거 경로를 모두 관측한다. 이 통지는 제거 의도일 뿐 성공이 아니다. 통지 시 정상적인
현재 선택 callback을 revoke하지 않는다. 원래의 token/generation/제안 집합 검사 뒤에만
선택이 전달된다. 실패·Lost의 기존 차단은 유지한다.

다음 조건이 모두 충족되어야 정리 한 건을 완료한다.

1. 같은 C 세대에서 A가 결합한 창 ID가 알려져 있고, 원래 대상 창과 다르다.
2. 제거 반환 후 C 상태가 HIDDEN이며 제거 실패가 없다.
3. 그 창에 대한 순수 REMOVED 이벤트를 실제 수신한다. 혼합/재등장/불명 이벤트는 차단한다.
4. 원래 서비스 이벤트 처리가 정상 반환한다. 해당 이벤트도 원래대로 invalidate(EVENT)한다.
5. 이후 별도 메인 스레드 차례에 C가 HIDDEN이고, 정상적인 전체 창 목록 조회에서 그 창이 없다.
   루트로 걸러낸 owned 목록에서 빠졌다는 사실은 부재 증거로 쓰지 않는다.
   빈/과대/중복/잘못된 ID 목록, 조회·해제 예외는 UNKNOWN이다. 모든 획득 창은 해제를 시도한다.

대기는 취소 가능하며 한 대기자의 취소가 공유 완료 상태를 취소하지 않는다. 대기 예산 초과는
성공이 아니라 안전 실패다. 불확실한 정리는 기존 프로세스 수명의 ChoicePresentationHealth를
실패로 고정하므로 재연결/새 token/뒤늦은 이벤트만으로 재허가하지 않는다.
정리 예산은 정리 시작 시각부터 계산하며, 연속 이벤트에 의한 대기자 취소/교체로 갱신되지 않는다.

## 후속 선택창과 원래 TTL

동일 token의 NOT_EXECUTED 후속 창도 `PopupChoiceFlow`의 비동기 준비 경계를 거친다.
정리를 기다린 prompt는 폐기하고, BLOCKED/표시 실패로 반환하지 않는다. READ_CONTENT로
같은 창을 계속 쓰는 경우는 창 재표시가 아니다. 모든 suspension 뒤 현재 token을 검사한다.

다음 서비스 수집은 정리 완료 후 현재 epoch에서 F0부터 새로 시작한다. 기다리는 사이 다른
이벤트가 epoch를 바꾸면 그 수집 요청은 폐기한다. F1의 기존 중복 방지 기준을 초기화하지 않는다.
제거 이벤트가 자체 앱 패키지로 감지 제외됐어도 새 관측을 명시적으로 예약한다. 이때 원래의
대기 중 수집을 먼저 취소하고, 그 다음 장벽 대기자를 깨워 오래된 요청의 선발행 경쟁을 막는다.
5초 TTL은 원래 capturedAt 기준이다. 사용자 조작·화면 변경·동의 철회·민감 보호·실행 전 검사는
유지하며 실제 클릭/제스처/자동 닫기는 연결하지 않는다.

## 증명하지 않는 것과 가용성 손실

이 장벽은 관측한 제거 이벤트 한 건의 처리 완료만 확인한다. yield, HIDDEN, 창 목록 부재,
시간초과는 Android의 전체 이벤트 큐가 비었다는 증명이 아니다. 완료 뒤 도착한 중복/다른
이벤트도 기존대로 무효화한다. 따라서 추가 지연 이벤트로 새 창이 안전 종료되는 경우는 남는다.

미관측 ID ABA 재사용, 제거 이벤트의 누락·합쳐짐·역순, 첫 결합 전 제거, 새 창이 같은 ID로
보이는 경우의 완전한 원인 식별은 보장하지 않는다. 확인할 수 없으면 계속 표시하지 않는다.
API 35/37 실제 창 추가·제거/콜백 순서와 TalkBack은 기기 검증이 필요하다.

새 원시 ID/패키지/텍스트/토큰/예외 메시지 로그는 추가하지 않는다. 정리 기록은 창 ID 한 개와
opaque 수명 정보만 내부에 제한적으로 보관하며 완료·실패·종료 시 폐기한다. Android 객체는 보관하지 않는다.

## JVM 및 P12 검증 범위

- 장벽: 지연 이벤트, 수신과 처리의 구분, 취소·누락·timeout, 혼합 마스크, ID 충돌·재사용,
  조회·해제 실패, 오래된 receipt, 동일 token 후속 창 폐기, 재연결 실패 고정.
- 실제 C keeper와 A guarded session/LiveEvidenceState를 연결한 합성 시험: 직접 종료 callback,
  A dismiss, P12 독립 token/세대, 옛 callback·추출 완료, TTL·동의·조작, 중복 지연 이벤트.
- 실제 OCR pipeline에 정리 시작을 주입해 늦은 OCR 결과 폐기와 frame 해제를 확인한다.
  기존 TTS 소유권·늦은 완료/취소 회귀도 유지한다.
- P12 진행은 시험자의 수동 합성 팝업 진행이다. C의 누르기 선택은 NOT_EXECUTED이며,
  시험자 진행을 A의 클릭 성공으로 집계하지 않는다. 셋째 동의 화면의 누르기 제안은 0개다.

JVM의 창 목록/이벤트 순서는 합성 포트이며 실제 Android 삭제/큐 원자성이나 F0 통과를 입증하지 않는다.
전체 Release JVM은 기존 Debug 전용 DetectionTestSession/F1 합성 테스트 의존성 문제로 별도 실패한다.
관련 Release 검증은 공용 테스트 bytecode를 Release 구현·런타임에 연결해 별도로 수행한다.

## 이번 로컬 검증 결과

- Debug 전체 JVM: 1,209개, 실패/오류/skip 0.
- Release 구현·런타임 대상 관련 JVM: 203개, 실패/오류/skip 0. 전체 Release 테스트 통과가 아니다.
- Debug/Release assemble 및 lint 성공. Lint 각각 오류 0, 기존 의존성 버전 경고 9개.
- APK에 양쪽 변형의 cleanup 컴포넌트를 확인했고, 기존 Overlay 진단 로그 문자열은 Debug에만 있다.
- 전체 `:app:testReleaseUnitTest`는 기존 `DetectionTestSessionTest`와 `F1SyntheticMatrixTest`의
  Debug 전용 타입 참조로 `compileReleaseUnitTestKotlin`에서 실패했다. 해당 파일/빌드 설정은 고치지 않았다.
- 관련 Release 검증은 로컬 ignored init script `build/tmp/issue29/release-tests.gradle`의 별도 Test 작업을
  사용했다. 공용 Debug 컴파일 테스트와 Release production 클래스/런타임 의존성을 연결하며
  Debug production 클래스는 넣지 않는다. 이 init script는 커밋 대상이 아니다.
- 실제 기기/에뮬레이터/API 35·37/TalkBack 시험은 시행하지 않았다. 합성 P12는 시험자 진행 모델이다.
- 커밋·push·PR 생성/편집·병합 없음. 원격 PR #35·#36과 main은 변경하지 않았다.
