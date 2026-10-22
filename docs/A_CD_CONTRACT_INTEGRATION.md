> 이 문서는 이전 고정 커밋 기반 작업 기록입니다. 최신 main 기반 상태·B20 호환성·검증 결과는 [A_MAIN_NONEXECUTING_VALIDATION.md](A_MAIN_NONEXECUTING_VALIDATION.md)를 참조하세요.

# A 로컬 비실행 통합 — C18/D25 계약 반영

2026-10-22(한국 시간). 작업 브랜치 `local/a-integration-nonexecuting-20261022`, 시작 HEAD `6be4304f7e8c797f5a2b550a35a117676f3c6ce4`입니다. 기존 A7/F9 브랜치와 원격 PR은 변경하지 않으며 Android 실측은 수행하지 않습니다.

## 정확한 입력과 원본 보존

| 입력 | 고정 커밋 | 임시 반영 범위 |
|---|---|---|
| C #18 | `4e73bad6987b086be2f0021a66ea9570d0a2fa14` | 기존17ce35e 대비 main UI/TTS 계약·리소스·JVM·C 문서14개를 바이트 그대로 복사 |
| D #25 | `44fa0714f0ad1c0e523ff65c4ccdfb159bcc730d` | 기존main4aa5a19 대비 RuleStorage/DAO/repository·JVM·Room 계측 소스·D 문서6개를 바이트 그대로 복사 |
| 기존 B #19 | `8cb2305b39f73daf40b7e7b5c5bb212f1a809a31` | 변경 없음 |
| 기존 A7 runtime | `5db1dbf080febdfc59fb774e431a73e3a41798d2` | collector/정책/원본14개 변경 없음. 별도F1 worktree의 P3 회귀2cc7654는 여기 섞지 않음 |
| 기존 A 비실행 gate | `5729ec13746ad3a218477df08d4bd48cfef71565` | 기존 조립 소스 유지. 별도F9 e777344 reader는 여기 연결하지 않음 |

C debug 서비스·preview·debug Manifest·마이크 권한은 가져오지 않았습니다. D build 설정·schema·다른 main 변경도 합치지 않았습니다. 위20개는 다른 담당자의 의존 소스이며 A가 새로 작성한 변경으로 취급하지 않습니다. 원본 blob과의 바이트 비교를 수행하고 A 어댑터와 별도 로컬 커밋으로 구분합니다. main 병합·원격 push·PR 생성/편집/병합은 하지 않습니다.

## C present/answer/window 계약에 대한 A 방어

- GuardedChoicePresenter가 present 결과를 직접 처리합니다. SHOWN인 동시에 SHOWING·양수의 새 C generation이 확인된 경우에만 A prompt lease를 활성화합니다. FAILED/BLOCKED 또는 잘못된 상태/세대에서는 A 세션을 폐기하고 프로세스 수명의 ChoicePresentationHealth를 실패로 고정합니다.
- PromptAnswer는 같은 A lease·현재 token·원래5초 TTL·비상호작용 상태·해당 C generation에서만 받습니다. Picked의 candidate ID/role/flags는 실제 표시한 선택 집합과 일치해야 합니다. READ_CONTENT는 동일 SHOWING 창에서만, terminal 선택은 C가 HIDDEN/세대0을 보고한 경우에만 전달합니다. terminal 선택은 전달 전에 소비합니다.
- Lost·REMOVE_FAILED와 비활성 기능인 Move는 B 검토/포커스/행동으로 전달하지 않고 세션을 폐기합니다. dismiss가 예외 없이 반환해도 REMOVE_FAILED가 남으면 A에서는 정리 실패입니다. C가 나중에 stuck 창을 제거하더라도 A 실패 상태를 자동 해제하지 않습니다. C의 dismiss 재호출은 오버레이 자원 정리이며 대상 앱 클릭/닫기 재시도가 아닙니다.
- NonExecutingChoiceSession의 C ChoiceHandler 브리지는 기존2인자와 새3인자(origin)를 명시적으로 구현합니다. 둘 다 동일한 B 정책→A ReviewOnlyChoiceExecutor로 갑니다. ChoiceOrigin.TAP/VOICE는 C 메타데이터일 뿐 실제 tap/음성 출처 인증·행동 허가가 아닙니다. VOICE 메타데이터를 처리해도 마이크/음성 입력 객체를 생성하지 않습니다.
- 기존2인자 어댑터의 이전 컴파일 산출물이 C의3인자 interface 호출을 구현하지 않아 AbstractMethodError가 난 경로를 로컬 회귀에서 확인했습니다. 명시적 브리지로 새 계약에 맞추었으며, C/B 원본 또는 공통 UserChoice 계약을 고치지 않았습니다. 이전 A 회귀의 취소·실패·TTL·중복·오래된 callback 조건은 유지합니다.
- OwnedOverlayBinding은 기존 새 창 집합/유일성·현재 token 검사에 C generation 일치를 추가합니다. 실제 Android type/display/root package/root window ID/고정 title 검사는 유지합니다. C의 ChoiceOverlayIdentity는 REMOVE_FAILED도 자신의 창으로 식별할 수 있지만 A mayTrack은 SHOWING·양수 generation만 허용합니다. 따라서 C 계약보다 보수적이며, 패키지 단독 제외·대상 앱 이벤트 무시·touch/click/scroll/창 제거 무시는 추가하지 않습니다. C OwnOverlayWindowTracker로 A의 기존 검사를 대체하지 않습니다.

## D cleanUp의 서비스 수명 연결

CloseForYouAccessibilityService는 각 연결의 새 serviceScope Job에 ServiceRuleCleanup.start를 한 번 호출합니다. 재초기화 시작과 disconnect에서는 먼저 close하여 이전 작업의 결과 소유권을 폐기합니다. 같은 Job의 중복 start와 실패 후 재시도는 거부합니다. 실제 Android onServiceConnected가 다시 오면 기존 서비스 설계처럼 새 scope/세대로 처리합니다.

실제 작업은 Dispatchers.IO 자식 코루틴에서 applicationContext로 RuleStorage.cleanUp을 호출하고 반환 개수는 버립니다. 서비스 초기화는 Room을 기다리지 않습니다. 일반 저장소 예외는 고정 FAILED 상태로만 끝나고 서비스 Job/다른 자식을 취소하거나 예외 원문을 출력하지 않습니다. 취소는 해당 자식에서 전파합니다. 이전 연결의 늦은 완료·예외·취소는 비공개 operation identity가 다르면 새 연결 상태를 바꾸지 못합니다. 완료 전에 dispatch가 취소되는 경우도 처리합니다. 기록하는 것은 enum뿐이며 룰 ID·지문·화면 내용·정리 개수·예외 객체를 보관/발행하지 않습니다.

이 연결은 만료/손상 행 삭제 유지보수뿐입니다. 룰 저장·조회·지문 결합·실행 실패 기록·자동 기억을 연결하지 않았습니다. C flow의 RememberedPopupStore.NONE, autoClose=false, 마이크/포커스 비활성 및 A gate의 NOT_EXECUTED/attempted=false는 그대로입니다. F9 productionReady=false이고 새 read-only probe도 가져오지 않았습니다.

취소는 비협력적 DB/native 작업의 강제 중단/이미 실행된 삭제 rollback을 뜻하지 않습니다. D cleanUp은 두 삭제 작업이므로 부분 완료가 남을 수 있습니다. D가 만료·손상 행을 조회에서 항상 거부한다는 계약에 따라 안전성은 정리 성공에 의존하지 않습니다. DB singleton을 A 서비스 종료 때 닫아 다른 사용자를 방해하지 않습니다. DB schema/Room SQL 실제 실행은 Android 미검증입니다.

## JVM 회귀와 로컬 검증

기존 A 오류 경계 회귀24개를 삭제하지 않고 새 present/answer API와 창 세대를 제공하는 fake port로 이식했습니다. 전체 기존 A 대상52개도 유지합니다. fake는 C처럼 현재 세대만 창 상태를 바꾸며, 이전 callback을 A에 전달하는 fault 주입은 별도로 유지합니다. C 실제 keeper도 fake Android ops와 A GuardedChoicePresenter에 연결해 다음을 검사합니다.

- 새 C keeper/A 경계10개: SHOWN·생성/add FAILED·기존 stuck BLOCKED·실제 keeper의 Lost·REMOVE_FAILED 뒤 C 제거 성공과 A quarantine·READ/terminal 중복·stale generation·Move 거부·잘못된 window state·C identity와 더 엄격한 A generation/상태 검사.
- 실제 A→B→C flow 추가4개: 반환 FAILED/BLOCKED, Lost 이후 재연결 거부, VOICE도 동일한 비실행 검토, 창 세대 불일치 거부.
- 서비스 유지보수9개: 비동기 정상1회·중복/완료 후 재호출·예외와 서비스 sibling 생존·취소·dispatch 전/후 종료·parent 취소·잘못된 scope·이전 세대의 늦은 완료/예외·새 연결 교체.

A 신규23개, 대상75개가 통과했습니다. C 추가29개·D 추가6개는 복사한 의존 코드의 회귀이며 A 신규 수와 구분합니다. fake 창·Room callback·코루틴 시계는 실제 Android 사용자 입력이나 WindowManager/Room/TTS의 성공 증명이 아닙니다. 이번 전체 재실행은 JVM 1,409개(앱960·capture-test-host436·popup-test-app13), 실패/오류/skip0입니다. 시작1,351개 대비 A23개·C29개·D6개를 구분합니다. JDK17에서 다음을 실제 실행했습니다.

```bash
JAVA_HOME=/workspace/jdk17 ./gradlew --offline --no-daemon \
  :app:testDebugUnitTest --rerun :capture-test-host:testDebugUnitTest --rerun \
  :popup-test-app:testDebugUnitTest --rerun \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest \
  :popup-test-app:assembleDebug \
  :app:lintDebug :app:lintRelease :capture-test-host:lintDebug
```

BUILD SUCCESSFUL. 앱 Debug/Release·앱/호스트 AndroidTest APK·호스트/D 합성 앱 Debug 빌드가 성공했고, 앱 Debug/Release 및 호스트 Debug Lint 이슈는 모두0입니다. C 기존 테스트의 ExperimentalCoroutinesApi opt-in 경고는 컴파일 경고이며 원본을 고쳐 숨기지 않았습니다.

`tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk`와 추가 DEX class 정의 검사를 통과했습니다. A ServiceRuleCleanup·C OverlayWindowKeeper·D RuleCleanUpResult는 앱 APK에만 있고, 새 JVM 테스트/기존 F9 read-only probe는 APK들에 없습니다. 앱 Debug/Release에 RECORD_AUDIO/INTERNET 권한이 없음을 확인했습니다. Manifest·gradle.properties·collector·runtime·speech engine은 시작 HEAD와 바이트가 같고, 공통 모델/B/F9/기존 계측/D 합성 앱은 diff가 없습니다. 복사한20개 원본 blob·공백 검사도 통과했습니다. Android 계측은 실행하지 않았습니다.

## 남은 계약과 실제 검증

C의 새 keeper는 종전 private handle 조기 폐기 문제를 보완하고 HIDDEN/REMOVE_FAILED와 Lost를 제공합니다. 그러나 A는 이 보고가 실제 Android 창·동일 frame과 원자적으로 결합됐다고 주장하지 않습니다. title/root 노출·removeView/NOT_ATTACHED·blank/View.GONE·TalkBack·자체 오버레이 이벤트 순서는 실측이 필요합니다. C가 내부 stop/destroy 등 RuntimeException을 삼키는 세부 정리 결과를 windowState만으로 모두 확인할 수도 없습니다. C generation과 ChoiceOrigin은 실제 사용자 선택 출처 인증 또는 다른 서비스 인스턴스의 권한이 아닙니다.

A는 실패 후 C가 자체 복구했다는 이유만으로 자동 재허가하지 않습니다. 프로세스 수명의 quarantine 해제/사용자 안내·실제 선택 출처·공통 계약 이동·B14 라벨 전환·팝업 identity/룰 결합은 후속 합의 범위입니다. 저장소 cleanUp은 행동 허가와 무관하며 B/C/D 공통 계약을 변경하지 않습니다. 실제 클릭·제스처·자동 반복·OCR·캡처 활성화 및 사용자 설정 변경은 없습니다. 원본14개 UNOBSERVED, Scroll/Window 및 F0 완전성·현재성·TTL·토큰/지문 결합의 기기 수준 미입증 상태는 유지합니다. 이번에는 Android Studio·에뮬레이터·실기기 시험을 실행하지 않습니다.

## 변경 파일 구분

다음 의존 소스는 각 고정 커밋의 원본 그대로입니다.

### 4e73bad6987b086be2f0021a66ea9570d0a2fa14

- `app/src/main/java/kr/closeforyou/ui/voice/README.md`
- `app/src/main/java/kr/closeforyou/ui/voice/integration/PopupChoiceFlow.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/overlay/AccessibilityChoiceOverlay.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/overlay/ChoicePromptPresenter.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/overlay/OverlayWindowKeeper.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/overlay/PromptControls.kt`
- `app/src/main/java/kr/closeforyou/ui/voice/settings/UiVoiceSettingsScreen.kt`
- `app/src/main/res/values/ui_voice_strings.xml`
- `app/src/test/java/kr/closeforyou/ui/voice/integration/PopupChoiceFlowTest.kt`
- `app/src/test/java/kr/closeforyou/ui/voice/overlay/ChoiceOverlayIdentityTest.kt`
- `app/src/test/java/kr/closeforyou/ui/voice/overlay/OverlayWindowKeeperTest.kt`
- `app/src/test/java/kr/closeforyou/ui/voice/overlay/PromptControlsControllerTest.kt`
- `docs/c-notes/overlay-identity-contract.md`
- `docs/c-notes/presenter-usage-for-a.md`

### 44fa0714f0ad1c0e523ff65c4ccdfb159bcc730d

- `app/src/androidTest/java/kr/closeforyou/data/rules/PopupRuleDatabaseTest.kt`
- `app/src/main/java/kr/closeforyou/data/RuleStorage.kt`
- `app/src/main/java/kr/closeforyou/data/rules/PopupRuleDao.kt`
- `app/src/main/java/kr/closeforyou/data/rules/RoomPopupRuleRepository.kt`
- `app/src/test/java/kr/closeforyou/data/rules/RoomPopupRuleRepositoryTest.kt`
- `docs/F12_RULE_STORAGE.md`

### A 어댑터·회귀·문서

- `app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ChoicePresentationBoundary.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/NonExecutingChoiceSession.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/OwnedOverlayEventPolicy.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ServiceChoiceIntegration.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ServiceRuleCleanup.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/ChoicePresentationBoundaryTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/NonExecutingChoiceIntegrationTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/PinnedChoiceContractTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/ServiceRuleCleanupTest.kt`
- `docs/A_CD_CONTRACT_INTEGRATION.md`
- `docs/A_CHOICE_ERROR_BOUNDARY.md`
- `docs/LOCAL_NONEXECUTING_INTEGRATION.md`
