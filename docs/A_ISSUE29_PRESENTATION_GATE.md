# #29 P01 표시 전 현재성 경계

기준: fix/issue29-overlay-cleanup-barrier, HEAD 4f67ee6 및 기존 미커밋 20개 파일.
이번 변경은 그 위에 추가한다. 기존 장벽·V2/Origin 진단·PR·main을 변경/되돌리지 않는다.

## 최신 실측의 판단

UNMATCHED / BEFORE_CURRENT_SHOW / NOT_IN_LIST는 현재 표시 전 전송된 이벤트라는 단서다.
어떤 창/세대의 이벤트인지, 현재 F0 시작 전인지, 최신 수집에 이미 반영됐는지는 증명하지 않는다.
NO_NEW_WINDOW는 owned 창 후보 차집합이 비었다는 진단이며 화면 현재성 증거가 아니다.
현재 Android 이벤트에는 검증된 C 세대가 없고, 사라진 ID의 출처/미관측 ABA 재사용을
배제할 수 없다. 새 이벤트 무시 조건은 만들지 않았다. 이벤트 시각이나 Debug enum은
실제 shouldIgnore/invalidate 결정의 입력으로 사용하지 않는다.

요청 5의 대안으로, Candidate 발행과 실제 C 추가 사이에 취소 가능한 표시 전 검증을 구현한다.
표시 전 대기 중 처리된 이벤트는 원래대로 토큰을 무효화하며 C가 아직 없어 잠깐 떴다
사라지는 현상을 피한다. 새로운 관측 요청은 기존 이벤트/정리 경로의 F0부터 시작한다.
이미 무효화된 Candidate를 재사용하거나 재시도하지 않는다.

## 기존 시간/현재성 계약

- AndroidSnapshotCollector의 F0 보호 1차/스냅샷 2차 수집은 동기적이다. 창/루트/프레임을
  시작과 끝에서 검사하고 모든 Android 객체를 해제한 뒤 Ready를 반환한다.
- capturedAtUptimeMillis는 두 번째 트리 순회 뒤 DTO 생성 시점이며 마지막 창 검사보다 앞이다.
  **F0 수집 시작 시각이 아니다.** 이를 이벤트가 수집 이전임을 증명하는 시각으로 쓰지 않는다.
- 모든 관련 시간은 Android uptimeMillis다. ScreenFreshness epoch, Runtime의 정확한 토큰,
  수집/증거 Job의 취소 상태, 정책/사용자 조작, CleanupBarrier 허가가 별도로 검사된다.
- OCR 전후 기존 verifyScreen은 새 collector/F0와 package/window/structure/content/frame/window-set
  비교를 수행한다. 일부 tree/decision/partial 경로는 비동기 OCR을 거치지 않는다.
- Runtime은 begin 때 원래 capturedAt + 5초를 고정한다. 현재 토큰 검사는 TTL·동의·취소를 포함한다.
  기존 C 표시 직전에는 currentToken/사용자 조작과 cleanup gate가 있었으나 새 F0/화면 비교는 없었다.

## 추가 경로

1. 성공적인 증거 발행 직전에 A의 ChoicePresentationGate에 원래 비교 기준을 등록한다.
   nodes를 항상 제거한 DTO 메타데이터만 1건 보관한다. 캡처 시각·토큰·화면/창 기준은 갱신하지 않는다.
   발행 실패/취소는 해당 ticket만 폐기한다. 오래된 ticket은 다음 기준을 지울 수 없다.
2. 기존 OverlayCleanupBarrier.awaitPresentation을 먼저 통과한다. 같은 토큰 후속 C도 동일하다.
3. Handler.post 한 번을 취소 가능하게 기다리고 원래 토큰/동의/조작/허가/TTL을 다시 검사한다.
4. C가 없는 상태에서 기존 verifyScreen으로 새 collector/F0와 전체 기존 비교를 수행한다.
   불일치·F0 차단·조회 실패는 표시를 거절하며, 여전히 해당 토큰이 현재이면 무효화한다.
5. F0 수집/해제가 큐에 남긴 이벤트도 C 추가 전에 처리할 기회를 주도록 다시 Handler.post한다.
6. 같은 baseline ticket·정확한 현재 토큰·cleanup revision·동의·사용자 조작·원래 TTL을 재검사한다.
   이후 C의 기존 현재성/표시 검사까지 suspension을 추가하지 않는다.

고정 시간 delay나 debounce 연장, Android 큐 소진 판정, 창 목록 부재를 이용한 허가가 아니다.
터치·스크롤·현재 대상 변경·동의 철회·이전 창/혼합/불명 이벤트는 기존 무효화 경로를 유지한다.
정리 대기가 필요하면 기존 장벽이 옛 prompt를 폐기하고 새 관측을 요구한다.
표시 실패(BLOCKED)나 정리 실패로 위장하지 않으므로 C를 한 번도 추가하지 않은 후보의 폐기가
알 수 없는 C 창 정리 실패로 격상되지 않는다. 직접 선택 callback/OCR/TTS/클릭 게이트도 바꾸지 않는다.

## 한계와 위험

두 번의 post가 Binder/전체 Android 이벤트 큐를 비웠음을 증명하지 않는다. 그 뒤 전달된
불명확한 과거 이벤트도 계속 invalidate(EVENT)한다. 최신 실측의 137ms 지연이 이 경계에서
항상 흡수되거나 실기기 P01 수명이 늘어난다고 주장하지 않는다.
추가 F0는 메인 스레드 수집 비용을 늘리고 원래 5초 예산을 소비한다. 추가 스크린샷/OCR/클릭은 없다.
검사 실패는 안전 종료하며 무조건 재관측/재표시하는 루프를 만들지 않는다.
노출되지 않은 픽셀 변화나 미관측 ID ABA를 새로 증명하는 기능도 아니다. 기존 이벤트 무효화를
그대로 두므로 화면 동일성 검사만으로 이벤트를 무시할 때 생길 추가 누락 위험을 도입하지 않는다.

## 회귀 검증

- ChoicePresentationGateTest: post 이전/이후 취소, 수집 중/수집 후 실제 변화,
  토큰/창/본문/구조/프레임/창 목록 비교, F0/조회 실패, TTL, 동의·조작·정리 허가,
  옛 ticket/토큰의 다음 팝업 간섭 차단, node-free 보관 및 고정 redacted 문자열.
- OverlayCleanupIntegrationTest의 전체 harness에 실제 Gate를 연결했다. P01 큐 대기 중 및
  최종 F0 중의 불명 이벤트는 기존 무효화 후 C를 만들지 않음을 검사한다. 새 수집/독립 토큰의
  P01은 원래 TTL까지 유지되며 실제 대상/불명 이벤트는 표시 이후에도 무효화한다.
- 기존 P12 광고→공지→동의의 수동 진행, 이전 C·ID 재사용·혼합 이벤트·직접 종료·후속 동일 토큰,
  이전 callback/추출·동의·조작·TTL 회귀도 새 표시 경계와 함께 실행한다.
- Gate는 Debug 포트에 의존하지 않는 공용 A 구현이다. 같은 테스트를 Release production/runtime에도 실행한다.
  전체 Release JVM의 기존 Debug 전용 의존성 컴파일 문제와는 구분한다.
- JVM은 Android Binder/실제 Handler/OEM/TalkBack 실측을 대체하지 않는다. 실제 설치·P01 재현은 미검증이다.

## 이번 변경 파일

- app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt
- app/src/main/java/kr/closeforyou/accessibility/integration/ServiceChoiceIntegration.kt
- app/src/main/java/kr/closeforyou/accessibility/integration/ChoicePresentationGate.kt (신규)
- app/src/test/java/kr/closeforyou/accessibility/integration/OverlayCleanupIntegrationTest.kt
- app/src/test/java/kr/closeforyou/accessibility/integration/ChoicePresentationGateTest.kt (신규)
- docs/A_ISSUE29_PRESENTATION_GATE.md (신규)

원시 창 ID/세대/패키지/토큰/화면 텍스트/예외 메시지 로그를 추가하지 않는다.
기존 Debug SOURCE_SHA256은 새 미커밋 Kotlin 파일까지 포함해 APK를 구별한다.

## 2026-10-23 최종 로컬 검증

| 항목 | 결과 |
|---|---|
| Debug 전체 JVM | 1,245개, 실패/오류/skip 0 |
| Release production/runtime 관련 JVM | 239개, 실패/오류/skip 0 |
| Debug/Release assemble | 성공 |
| Debug/Release Lint | 성공, 각각 오류 0 / 기존 GradleDependency 경고 9 |
| 전체 Release JVM | 기존 DetectionTestSessionTest.kt / F1SyntheticMatrixTest.kt의 Debug 전용 타입 참조로 compileReleaseUnitTestKotlin 실패 |

Release 관련 실행은 기존 ignored build/tmp/issue29/release-tests.gradle에 Gate 테스트를 포함했다.
공용 테스트 bytecode를 Release production/runtime과 연결하며 Debug production 클래스는 제외한다.
전체 Release 테스트 통과가 아니다. Gate 13개 및 cleanup 통합 10개는 두 구현에서 동일한 결과다.

시작 시 기존 미커밋 20개 파일을 ignored build/tmp/issue29-presentation-baseline-20261023214635에
복사하고 SHA-256을 비교했다. 17개는 바이트 단위 동일하며, 나머지 3개에는 위 연결/회귀를 추가했다.
현재 Git 변경 파일은 23개다. 이번 추가/수정은 위 6개 파일이며 인덱스는 비어 있다.
diff --check 통과. 브랜치·HEAD 유지, commit/push/PR/merge/stash/reset 없음.

새 Gate가 Debug와 Release DEX에 모두 있고, 새 진단 로그 마커는 Debug에만 있음을 검사했다.
PowerShell로 파일 바이트 기반 소스 해시를 별도 재계산해 generated Debug BuildConfig 및 APK와 대조했다.

- Debug SOURCE_SHA256: `7b1d320846a919c97eb2b5f7e5b9473c388c74eba4bbee965ff0626f4c661be8`
- Debug APK: app/build/outputs/apk/debug/app-debug.apk
- Debug APK SHA-256: `97ee7a0d4faad49a5ae1064fb815ea3c44c347b00abb57ed1a94d4b67019a021`
- Release APK SHA-256: `54fda8aa919f7c02504d3a0aa96f75563599ae78cb7ac25f7740c9075506504b`
- 검증 기록: ignored build/tmp/issue29-presentation-artifacts.json,
  issue29-presentation-validation.log, issue29-presentation-release-full.log.

실제 기기/에뮬레이터 설치, P01/P12 실측, TalkBack 및 OEM 이벤트 순서 검증은 하지 않았다.
화면이 안정된 합성 P01의 남은 원래 TTL을 검증했을 뿐, 제공된 실측 순서의 완전한 해결을 주장하지 않는다.
