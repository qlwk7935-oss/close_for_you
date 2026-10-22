# A 비실행 통합의 오버레이 오류·선택 수명 경계

최신 C18 `4e73bad`의 keeper/present/answer 반영은 [C/D 계약 후속](A_CD_CONTRACT_INTEGRATION.md)을 따릅니다. 이 문서의 C private handle 문제와 1,351개 검증은 이전 C17ce35e/통합6be4304 기준 이력입니다.

2026-10-22(한국 시간). 기준은 통합 로컬 브랜치의 코드 `759ba4e`와 인계 문서 `7078608`입니다. C #18 원본은 읽기 전용으로 대조했으며 C/B/D·공통 계약·Manifest·gradle.properties·A7/F9 원격 브랜치를 변경하지 않습니다. 이번에는 Android 실측을 수행하지 않습니다.

## 확인한 A 문제와 보완

1. 이전 A callback은 token/일회 전달만 검사했습니다. 같은 token으로 새 프롬프트가 표시되거나, 외부 dismiss가 Runtime 무효화보다 먼저 실행될 때 해당 프롬프트의 수명·표시 옵션까지 확인하지 못했습니다. `GuardedChoicePresenter`는 정상 반환한 show마다 비공개 lease를 만들고, 교체·dismiss·동기 invalidate·close 전에 폐기합니다. 부분 show 도중의 callback, 이전 lease, 다른 candidate ID/role/flags, 표시하지 않은 선택, 사용자 상호작용 중인 선택은 전달하지 않습니다. terminal choice는 전달 전에 한 번 소비합니다. READ_CONTENT는 정상 프롬프트에서 반복 가능하지만 callback 재진입은 허용하지 않습니다. 완료 뒤 다시 들어온 동일 READ 요청이 새 사용자 요청인지 지연된 중복인지는 C 입력 일련번호/출처 계약 없이 인증할 수 없습니다.
2. 이전 `failed`는 ServiceChoiceIntegration 인스턴스에만 있었습니다. C 창 제거가 실패해도 재연결로 새 인스턴스를 만들면 창이 정리됐다고 증명하지 않은 채 새 표시를 시도할 수 있었습니다. 이번에는 실제 service factory가 프로세스 수명의 동일 `ChoicePresentationHealth`를 공유합니다. 표시/제거 또는 최종 cleanup 실패는 단일 boolean으로 고정하며 재연결·새 token으로 해제하지 않습니다. A는 이를 성공적인 창 제거로 기록하지 않습니다. reset API는 없고 프로세스 재시작 전에는 새 안내/선택을 거부합니다. 이는 가용성 손실을 감수하는 차단이며, 재시작 자체가 이전 창 제거의 실측 증명이라는 뜻도 아닙니다.
3. C PromptAnnouncementListener와 PopupChoiceFlow에 원래의 CurrentChoiceAnnouncer를 직접 주었으므로 동기 stop 실패를 A controls 경계가 처리하지 못했습니다. 이제 listener/flow/controls가 같은 FailureBoundChoiceControls를 사용하여 일반 오류는 세션 폐기와 독립 cleanup으로 이어집니다. 정상적인 이전 Job 취소는 오류로 재분류하지 않고 전파하며, 취소된 이전 작업의 늦은 오류가 새 음성 작업을 닫지 않게 먼저 Job 상태를 확인합니다. 활성 Job에서 delegate가 던진 취소는 세션을 거부한 뒤 전파합니다.
4. C collectLatest 내부의 취소가 해당 child만 종료하는 경우에도 닫힌 A 세션의 구독이 살아 있지 않도록, A session.close가 자신의 run Job도 취소합니다. finally에서는 그 소유권을 먼저 내려놓고 NonCancellable 경계로 독립 자원 정리를 수행합니다. 이미 실패한 presentation의 finally dismiss에서 추가 일반 오류가 나도 sticky 실패는 남기되 원래 취소/오류를 불필요하게 바꾸지 않습니다. Error 전체를 일반 성공으로 삼키지 않습니다.

## 기존 방어와 그대로인 동작

- 실제 Runtime currentToken·LiveEvidenceState의 서비스 연결 수명·원래 캡처 시각부터 5초 TTL·정책 철회 검사를 재사용합니다. token을 새 선택으로 옮기거나 TTL을 연장하지 않습니다.
- ServiceChoiceIntegration.invalidate는 Runtime이 갱신되기 전 A callback을 동기 revoke한 뒤 C dismiss와 음성 stop을 각각 시도합니다. 실패하면 job 종료를 기다리지 않고 A 세션도 즉시 닫습니다. 실제 window 제거 성공을 추정하지 않습니다.
- 기존 OwnedOverlayTracker/Binding/Policy는 그대로입니다. 새 유일한 소유 창·window type/display/root package/root window/title·현재 token이 확인된 제한된 이벤트만 제외합니다. 패키지 단독 제외, 출처 불명 touch/click/scroll 또는 창 제거 무시는 추가하지 않습니다. 실패 격리 상태에서는 자체 창 이벤트도 제외하지 않습니다.
- replay=0 구독 장벽, 중복 후보 거부, B 정책·candidate 재검사, 이전 서비스 인스턴스의 closed 상태와 동기 source 검사는 유지합니다.
- ReviewOnlyChoiceExecutor에는 Android action port가 없고 gate 반환/일반 오류와 무관하게 NOT_EXECUTED/attempted=false입니다. B가 먼저 거부하면 BLOCKED/STALE의 attempted=false가 될 수 있습니다. 기억·마이크·자동 반복·포커스 이동을 켜지 않습니다. F9 productionReady=false는 그대로입니다.
- 로그/IPC/DB/파일에 원문·좌표·패키지·window ID·token·지문·예외 원문을 추가하지 않습니다. 새 장기 상태는 실패 boolean뿐입니다. callback 비교용 token/candidate는 기존 임시 모델이며 실제 사용자 입력 출처를 인증하는 값이 아닙니다.

## C에서 반드시 해결·합의할 계약

- C dismiss는 현재 view를 null로 지운 뒤 listener, removeView, owner.destroy를 호출합니다. listener/removeView가 실패하면 private View/owner handle 복구와 각각의 나머지 정리가 보장되지 않습니다. C는 제거 확인 전 소유권을 버리지 않는 상태 전이, 독립 cleanup, 실패 시 복구/거부 계약을 제공해야 합니다. A는 C의 private View를 회수하거나 창 제거 완료를 인증할 수 없습니다.
- C show는 addView 전후의 lifecycle/controller/scope/ComposeView 및 listener 실패를 정리하는 계약이 필요합니다. A는 자신의 show 호출에서 돌아온 오류를 격리할 뿐, 부분적으로 생성된 C 객체를 복구할 수 없습니다.
- **C onOption은 A onChoice를 호출하기 전에 C dismiss를 직접 호출합니다.** 그 내부 removeView/Compose/플랫폼 callback의 예외가 A port 바깥에서 발생하면 A try/catch로 모든 UI 스레드 오류를 막을 수 없습니다. 이번 A 보완으로 C 내부 오류 전부나 프로세스 생존을 보장한다고 주장하지 않습니다.
- C callback에 신뢰 가능한 prompt 인스턴스/서비스 연결 세대·선택 출처·실제 창 수명 및 제거 결과를 결합하는 계약이 필요합니다. A의 lease/표시 옵션 검사는 전달 일관성 검사이며 실제 사용자의 tap/음성/신뢰 가능한 창 소유권 인증이 아닙니다. 실행 권한을 만들지 않습니다.

## 회귀와 실제 검증 범위

새 JVM 회귀는 실제 A GuardedChoicePresenter/ChoicePresentationHealth, 실제 NonExecutingChoiceSession→B→C flow, 실제 C listener와 A 음성 경계를 사용합니다. framework addView/removeView의 예외는 fake presenter의 fault로 주입하며 실제 Android에서 재현했다고 표현하지 않습니다. 기존 테스트의 이전 음성 취소·완료 경쟁도 새 outer boundary를 함께 통과하도록 보강합니다. 사용자 상호작용 회귀는 B까지 전달해 거부하는 이전 기대에서 A callback 단계에서 먼저 거부하는 기대와 미발행 결과 확인으로 변경했습니다. B의 별도 정책 회귀는 변경하지 않습니다.

이번 JDK17 최종 실행은 JVM **1,351개**(앱902·호스트436·D13), 실패·오류·skip0입니다. 신규24개(GuardedChoicePresenter/C listener 직접 경계17개 + 실제 A→B→C flow7개), 대상 합계52개가 포함됩니다. 앞선1,327개 기록과 구분합니다. 부분 show·동기 dismiss 재진입·제거 실패 뒤 새 Candidate/동일 token 재연결·허용하지 않은 candidate/flags·중복 terminal 선택·현재 화면/사용자 상호작용·4,999ms/5,000ms 경계·show 취소+cleanup 실패·일반/취소된 TTS의 늦은 오류를 검증합니다.

앱 Debug/Release·앱/호스트 AndroidTest APK·호스트/D Debug 빌드, 앱 Debug/Release·호스트 Debug Lint가 통과했고 Lint 이슈는 각각0입니다. 저장소 패키징 검사 및 DEX class_def 격리에서 새 A 경계는 앱 Debug/Release에만, 신규 JVM 테스트와 F9 read-only probe는 APK에 없음을 확인했습니다. 본 앱 Debug/Release에 RECORD_AUDIO/INTERNET 권한은 없고 A integration 소스에 Android 행동 API/일반 로그 호출은 없습니다. 기존 보호·원본14개·100ms 예산·B/C/D·공통 모델·Manifest·gradle.properties·service/runtime·OwnedOverlayEventPolicy·OfflineChoiceSpeechEngine의 기존 소스는 기준7078608과 같습니다. 추적/신규 파일 공백 검사도 통과했습니다.

```sh
JAVA_HOME=/workspace/jdk17 ./gradlew --offline --no-daemon :app:testDebugUnitTest --rerun :capture-test-host:testDebugUnitTest --rerun :popup-test-app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :capture-test-host:assembleDebug :capture-test-host:assembleDebugAndroidTest :popup-test-app:assembleDebug :app:lintDebug :app:lintRelease :capture-test-host:lintDebug
python3 tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
```

실제 기기 시험·Android 호출 counter는 이번에 측정하지 않습니다. 검증된 A 변경은 통합 로컬 브랜치에만 커밋하며 B/C 미병합 의존성 때문에 원격 push/PR 생성/기존 PR 수정은 하지 않습니다.

## Android Studio에서 확인할 항목

전용 합성 화면과 사용자 UI 동의로 P01을 열어 C 표시·로컬 TTS·현재 선택·비실행 안내를 관측합니다. 빠른 popup 교체·READ 반복·중복 전달·5초 경계·화면 전환·서비스 OFF/ON 및 TalkBack 탐색을 각각 확인해야 합니다. 창 생성/제거 실패를 실제로 재현하려면 C 소유 복구 계약/시험 방법이 필요하며 운영 화면에서 임의 WindowManager 조작이나 실패 주입을 하지 않습니다. 차단 이후 프로세스 수명의 UI 거부와 재연결 시 과거 선택 미재사용을 확인합니다. JVM/소스 검색만으로 Android 실제 클릭 횟수·음성 청취·창 제거 완료를 측정했다고 하지 않습니다.

A7 원본14개 UNOBSERVED, Scroll malformed Rect, 별개 Window/frame 문제 및 실제 F0 완전성·popup identity·현재성·TTL의 기기 수준 검증·선택 출처는 여전히 미입증입니다. 추가적인 A 단독 필수 결함이 확인되면 수정해야 하지만, C의 private 자원 복구와 입력 출처 계약은 A가 임의로 대체하지 않습니다. 기존 API37 인계 ZIP은 이전 고정 코드(759ba4e)용이며 이번 패치가 포함된 새 APK라고 해석하지 않습니다.
