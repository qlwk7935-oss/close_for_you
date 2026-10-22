> 이 문서는 이전 고정 커밋 기반 작업 기록입니다. 최신 main 기반 상태·B20 호환성·검증 결과는 [A_MAIN_NONEXECUTING_VALIDATION.md](A_MAIN_NONEXECUTING_VALIDATION.md)를 참조하세요.

# A → B → C 첫 비실행 조립 — 로컬 검토용 (2026-10-22)

최신 C18 `4e73bad`/D25 `44fa071` 반영과 현재 검증 결과는 [C/D 계약 반영](A_CD_CONTRACT_INTEGRATION.md)을 우선합니다. 아래 입력·검증 이력은 최초 조립 당시 기록입니다.

후속 A 오류 경계·선택 수명 수정과 C 계약 한계는 [A 통합 오류 경계](A_CHOICE_ERROR_BOUNDARY.md)에 기록합니다.

최종 기기 접근 확인·Windows 소스 전달·설치·UI 동의·A7 원본14개와 통합 P01/P13의 실행 순서 및 미검증 결과표는 [API37 최종 실행 인계](A_API37_FINAL_VALIDATION_RUNBOOK.md)를 따릅니다. 이번 환경에서는 Android 실측을 수행하지 못했으며, 아래 기존 JVM/CI 기록을 새 기기 성공으로 해석하지 않습니다.

기존 PR 브랜치를 수정하지 않은 별도 worktree `/workspace/a-integration-nonexecuting`, 로컬 브랜치 `local/a-integration-nonexecuting-20261022`입니다. 원격 push/PR 편집/PR 병합은 하지 않습니다. 원래 `/workspace/close_for_you`는 `feature/f1-popup-detection` / `5db1dbf`로 보존합니다.

## 입력 코드와 경계

| 입력 | 고정 SHA | 조합 |
|---|---|---|
| main | 4aa5a192d193b91899bc54aa31aab721d751f358 | Room·D QA 포함 그대로 사용 |
| A #7 | 5db1dbf080febdfc59fb774e431a73e3a41798d2 | 로컬 merge, 충돌 없음 |
| B #19 | 8cb2305b39f73daf40b7e7b5c5bb212f1a809a31 | 로컬 merge, B 코드 수정 없음 |
| C #18 | 17ce35e34a072a15bcbe86b27c3f5f0c7395180e | main UI/TTS·리소스·JVM·사용법53개 파일 그대로 복사 |
| A #10 | 5729ec13746ad3a218477df08d4bd48cfef71565 | 원래 action 패키지·시험·문서9개 그대로 복사 |

C debug 서비스/미리보기/Manifest/RECORD_AUDIO는 가져오지 않았습니다. 기억·마이크·포커스 이동을 생성하거나 켜지 않습니다. B #14(`a036d0e`)의 공용 라벨 규칙은 검토만 했고 공통 모델에 추가하지 않았습니다. B/C/기존 A 게이트의 보수적인 기존 라벨 정책을 유지합니다. 기존 PR #10 후속 읽기 준비 변경은 **다른 worktree**에만 있으며 여기에 섞지 않습니다.

## A가 작성한 운영 조립

- `AccessibilityRuntime.integrationEvents`: 실제 replay0 SharedFlow의 `onSubscription`에서만 준비를 완료합니다. 서비스의 첫 수집은 그 구독을 기다립니다. detector/provider의 RESET 의미를 변경하거나 테스트 결과를 주입하지 않습니다.
- `ServiceChoiceIntegration`: 서비스 연결·재연결에 C overlay/announcer와 B 부품을 생성하고, 무효화 시 UI·음성을 즉시 닫아 중간 Flow 이벤트 누락에도 오래된 표시를 유지하지 않습니다. 종료 시 구독/세션·overlay·안내 history·TTS를 각각 정리합니다. 초기화 실패 후 만들어진 TTS도 정리합니다.
- `NonExecutingChoiceSession`: 실제 `DecisionCandidateWiring`/`DecisionPopupClassifier`/`DecisionChoiceHandler` → C `PopupChoiceFlow`/`AccessibilityChoiceOverlay` → 원래 A `NonExecutingActionExecutor`를 사용합니다. 완전한 현재 후보만 전달하고, 같은 token 중복은 재표시하지 않으며, 차단/불확실은 읽기/누르기 선택으로 승격하지 않습니다. 표시 직전/직후와 선택 callback의 token을 재확인합니다. 기억 store는 NONE, 자동 반복·focus 이동·voice input은 비활성입니다.
- `ReviewOnlyChoiceExecutor`: 기존 gate의 미구현 source를 그대로 사용합니다. 임의 ALLOW_ONCE도 정상 결과는 항상 NOT_EXECUTED/attempted=false입니다. 일반 예외 역시 미실행이고 실제 Job 취소는 전파합니다. Android 행동 port를 받지 않습니다. B 정책이 거부하면 BLOCKED/STALE로 안내할 수 있지만 attempted는 false이고 재시도/실행 성공 안내는 없습니다.
- `CurrentChoiceAnnouncer`: 현재 token/TTL만 말하거나 다시 듣습니다. 무효화/정지·종료에 공유 C history를 비우며 이전 팝업 문구를 재생하지 않습니다.
- `OfflineChoiceSpeechEngine`: C의 공개 SpeechEngine port를 구현하는 A privacy adapter입니다. C 기본 engine은 selected voice의 network 여부를 확인하지 않으므로 여기서는 쓰지 않습니다. 설치된 로컬 한국어 voice와 같은 selection을 확인한 때만 C TtsPopupAnnouncer가 발화하고, 실제 enqueue 직전에 A token/원래 TTL/Job을 확인합니다. 음성 선택을 확인 못하면 UNAVAILABLE이며 네트워크 voice/기본 voice fallback은 없습니다. API metadata를 속이는 악성 TTS 제공자를 방어한다는 보장은 아닙니다. 음성 재생 완료와 실제 청취도 다릅니다.

원문·좌표·창 ID·timestamp·token·fingerprint·예외 원문을 로그/DB/파일에 추가하지 않습니다. UI/음성에는 기존 F0를 통과한 임시 근거만 사용합니다. V1 지문/100ms·2400 방문/geometry 거부·원본14개·collector·provider는 수정하지 않았습니다.

## 자체 오버레이 이벤트

생성 전 소유 overlay 집합을 고정하고, C show 뒤 또는 지연된 실제 add 이벤트에서 새로 생긴 **유일한** 창을 결박합니다. 실제 window type/display/root package/root window/title을 확인하고 살아 있는 같은 소유 창의 content/focus/제한된 layout 이벤트만 제외할 수 있습니다. 패키지만으로 제외하지 않습니다. 기존 창/다중 창/재사용 ID/다른 token/소유권 미확인/해제 실패는 제외할 수 없습니다.

출처 불명 TOUCH/CLICK/SCROLL, 창 제거·활성/focus 전환 및 모호한 시스템 이벤트는 계속 무효화합니다. 따라서 TalkBack/global touch 이벤트에 소유 창이 없거나 C dismiss 뒤 제거 이벤트가 오면 선택/결과 음성이 취소될 수 있습니다. 이를 해결하려고 전체 앱 이벤트를 무시하거나 이전 선택을 새 token으로 옮기지 않습니다. 실제 Android의 event 순서·창 root 노출·자체 창의 정확한 lifecycle은 미검증이며, 신뢰 가능한 C 창 handle/제거 완료·선택 출처 계약이 필요합니다.

정적 검토상 C `AccessibilityChoiceOverlay.dismiss`는 view 참조를 지운 뒤 listener/removeView를 호출하므로, 해당 호출이 실패하면 A가 C의 private view를 직접 복구·제거할 수 없습니다. A는 다른 cleanup과 TTS 종료·이전 callback 거부를 계속 수행하고 세션을 실패로 닫습니다. **실제 창 제거 완료/실패 복구는 C 소유 수정·합의가 필요한 보류 항목**입니다. C 코드를 임의로 고치지 않았습니다.

## 앞선 검증·실측 기록

신규 공유 JVM20개는 실제 B/C flow + 실제 A LiveEvidenceState를 사용하되 Android 화면/음성 대신 합성 P01 형태·fake presenter/speech/시계를 사용합니다. 후보 연결·B 분류·선택·미실행 결과·중복/연속 팝업·차단·늦은 evidence·TTL/철회/종료/재연결·구독·자체 창 결박·history와 cleanup·로컬 voice 거부를 검사합니다. Android Dialog/Binder/TTS 실제 성공의 증거가 아닙니다.

2026-10-22 최종 로컬 검증: JVM **1,319개**(앱870·독립 호스트436·D 팝업 앱13), 실패·오류·skip0. 앱 Debug/Release, 앱·호스트 AndroidTest APK, 호스트·D 팝업 앱 Debug 빌드 성공. 앱 Debug/Release 및 호스트 Debug Lint 이슈0. 저장소 패키징 검사와 추가 DEX class_def 검사를 통과했습니다. 실제 B/C·원래 비실행 게이트·A 조립은 앱 APK에만 있고, PR #10의 신규 read-only probe와 JVM 시험은 없습니다. 앱 Debug/Release에 RECORD_AUDIO/INTERNET 권한이 없습니다.

조립 기준 커밋 `83e6908` 이후 A 변경은 Runtime/서비스2개, integration Kotlin4개, JVM1개, 이 문서1개입니다. 조립 때 가져온 C/원래 A10의62개 파일은 각각 고정 source blob과 바이트가 같습니다. collector·provider·공통 모델·B/C/D·Manifest·gradle.properties·기존 계측은 변경하지 않았습니다. 추적 diff 및 신규 파일 공백 검사도 통과했습니다. 원래 작업 트리는 깨끗하고 기존 원격 HEAD들은 유지됩니다. 새 A 구현은 이 임시 worktree의 미커밋 변경으로 보존합니다.

초기 main KSP가 offline cache에 없어 첫 compile이 실패했습니다. 코드·설정 변경 없이 온라인 의존성 해석을 완료한 뒤 기준 조립 compile은 성공했습니다. 최종 검증은 offline로 다시 실행했습니다. 초기 compile에서 나온 C 기존 시험의 ExperimentalCoroutinesApi opt-in 경고는 그대로 두며 A가 C 시험을 수정하지 않습니다.

API37 기기는 연결되지 않았으며 이번 버전은 미실행입니다. 다음은 사용자가 동의·서비스 연결·D 테스트 앱만 허용한 뒤 직접 확인할 최소 절차입니다. 권한을 강제 부여하지 않습니다.

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :popup-test-app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r -t popup-test-app\build\outputs\apk\debug\popup-test-app-debug.apk
adb shell am start -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01
```

별도 capture/OCR 동의는 OFF로 두고, 실제 후보→C 안내/선택→미실행 결과, TalkBack 이벤트, TTL 만료/창 닫힘·앱 전환·동의 철회·서비스 재연결·로컬 TTS 미지원과 자원 실패를 확인해야 합니다. 마이크·기억 기능은 켜지 않습니다. 원본14개 UNOBSERVED, Scroll/Window, F0 완전성·현재성/토큰의 원자적 결합·최종 행동 안전성은 계속 미입증입니다. 이 조립 APK를 검증된 release라고 배포하지 않습니다.

## 최종 독립 검토와 의존성

첫 검토20개 이후, 이전 음성 작업의 늦은 완료/취소가 새 안내의 history·음성을 정리할 수 있는 경계를 보강했습니다. A announcer와 실제 offline engine에 같은 identity-only `SpeechOperationOwner` 계약을 사용하여 이전 작업이 새 작업의 소유권을 지우거나 stop하지 못하게 합니다. TTS audio attributes/완료 listener 등록이 실패해도 READY로 승격하지 않습니다. 실제 TTS provider의 반환값·callback 순서는 Android 미검증입니다.

첫 구독 장벽은 replay0의 구독 전 유실을 막습니다. 기존 DROP_OLDEST 한 칸 buffer는 모든 중간 팝업/이벤트 전달을 보장하지 않습니다. 서비스의 동기 UI/음성 무효화와 매 선택의 현재 token 검사를 함께 유지하며, 누락된 이벤트를 성공 증거로 취급하지 않습니다. 이벤트 전체 보존을 위해 raw 화면 큐를 만들거나 기존 Flow 계약을 바꾸지 않습니다.

작업 시작 확인에서 main `4aa5a19`, A7 `5db1dbf`, A10 `5729ec1`, B14 `a036d0e`, B19 `8cb2305`, C18 `17ce35e`는 앞선 고정값과 같고 관련 PR은 아직 미병합이었습니다. B22 덤프 시험은 별도 시험일 뿐 A 서비스 증거로 가져오지 않았습니다. P02/P03 후보0, P04 X 거부, P13 W01 중요 결정 거부를 고치려고 B/C·라벨 계약을 변경하지 않습니다.

main 대비 diff에는 미병합 A7/B19 및 선택적으로 복사한 C18/기존 A10 의존 코드가 포함됩니다. 따라서 이것을 A 전용 PR로 발행하지 않습니다. 원본 의존 코드는 조립 기준 `83e6908`까지, A가 새로 쓴8개 파일은 그 뒤 별도 로컬 커밋으로 분리합니다. 깨끗한 A-only main diff와 재현 가능한 dependency base를 확보하지 못한 상태에서 Draft PR을 만드는 우회도 하지 않습니다. C의 private overlay 제거 실패 복구·신뢰 가능한 선택 출처, B14 전환·D episode/rule 계약은 팀 합의 보류입니다. 실제 실행·기억·마이크는 계속 비활성입니다.

## 이번 최종 검증과 종료 상태

이번 회귀는 **28개**로, 앞선20개에8개를 추가했습니다. 늦은 음성 완료/취소·새 작업 소유권, 실제 DROP_OLDEST buffer에서 덮인 무효화 뒤 오래된 선택 거부, 음성/정리 예외의 원문 비노출, 실제 listener 자식 실패 경계, C 자체 scope의 repeat/동기 stop port 실패 및 취소를 검사합니다. 실제 C 구현을 수정하지 않고 A가 주입하는 포트만 실패 시 세션을 닫도록 합니다. listener에는 별도 서비스 자식 Job을 두고 close에서 취소합니다. 단, 앞서 기록한 C private View의 제거 완료/실패 복구를 입증하거나 보수적인 자체 창 무효화를 완화한 것은 아닙니다.

JDK17 최종 고정 소스 재실행: **JVM1,327개(앱878·호스트436·D 팝업 앱13), 실패·오류·skip0**. 세 JVM 작업을 `--rerun`으로 실제 재실행했습니다. 앱 Debug/Release, 앱·호스트 AndroidTest APK, 호스트·D 팝업 앱 Debug 빌드 성공. 앱 Debug/Release·호스트 Debug Lint 이슈0, 저장소 패키징·추가 DEX 격리·권한·공백 검사 통과입니다. 새 listenerJob/controls 포트의 실제 컴파일·APK 반영도 확인했습니다.

```sh
JAVA_HOME=/workspace/jdk17 ./gradlew --offline --no-daemon :app:testDebugUnitTest --rerun :capture-test-host:testDebugUnitTest --rerun :popup-test-app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest :popup-test-app:assembleDebug :app:lintDebug :app:lintRelease :capture-test-host:lintDebug
python3 tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
```

보완 도중 파일이 변경된 이전 실행에서 Release Lint 위치가 소스와 불일치하는 오류가 나왔으므로 그 결과를 최종 결과로 사용하지 않았습니다. 이후 변경을 멈추고 전체 추적/신규 파일의 실행 전후 SHA-256 일치까지 검사한 위 실행이 성공했습니다. 문서의 이번 결과는 앞선1,319개/중간 실행과 구분합니다.

A 소유 최종8개 파일:

- `app/src/main/java/kr/closeforyou/accessibility/AccessibilityRuntime.kt`
- `app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/NonExecutingChoiceSession.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/OwnedOverlayEventPolicy.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ServiceChoiceIntegration.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/OfflineChoiceSpeechEngine.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/NonExecutingChoiceIntegrationTest.kt`
- `docs/LOCAL_NONEXECUTING_INTEGRATION.md`

이 A 변경은 로컬 커밋으로만 보존합니다. 통합 브랜치의 push/PR 생성/기존 PR 편집/merge는 하지 않습니다. 별도 worktree의 F9 준비만 `e77734428614719d5dc5f02f9cd10226d02d22c2`를 기존 Draft PR #10에 일반 push하고, [Android build #180](https://github.com/qlwk7935-oss/close_for_you/actions/runs/39645010702)의 success를 확인했습니다. 이 F9 추가 소스를 통합 worktree에 섞지 않았습니다.

이번 A 서비스/Runtime/collector/capture/OCR/비실행 통합/읽기 준비 점검에서 구체적으로 확인한 A 단독 수정은 완료했습니다. 미연결 실제 F0 receipt나 실행 허가를 만들어 완료 처리하지 않습니다. 남는 것은 미병합 의존 PR, C의 창 수명·선택 출처와 B/D 계약, Android 기기/Binder/TTS/실제 화면 검증입니다. 원본14개 UNOBSERVED와 Scroll/Window 문제, 화면·TTL·지문의 원자적 결합은 계속 미입증입니다.
