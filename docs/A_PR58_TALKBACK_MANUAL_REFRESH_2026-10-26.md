# PR #58 LG TalkBack 수동 선택 수명·새 F0 재검증

기준: `feat/a-f9-manual-service-phase2-review-20261026`,
`fb86683639ed7273a3b600e8ba228c5e8325e812`.
수정 작업: 별도 worktree `/workspace/a-pr58-talkback`,
`fix/a-pr58-talkback-manual-refresh-20261026`. 기존 네 worktree를 변경하지 않는다.

## 원인과 변경

기존 서비스는 출처 없는 touch/exploration START를 C 내부 조작으로 구분하기 전에
선택창까지 무효화했다. 기존 수동 처리도 원본 시각 + 5초 안에서만 제거를 기다린 뒤,
원본 DTO의 epoch만 바꿔 F9에 전달했다. 원래 TTL을 늘리지는 않았지만 TalkBack으로
읽고 선택하기에는 실행 증거 수명과 프롬프트 수명이 결합되어 있었다.

- C focus/click은 **이번 native View의 attach/add 성공, 소유 session·generation·창 ID,
  표시 이전 owned 집합, 별도로 열거한 유일한 live overlay 소유권, 이번 표시 이후의
  이벤트 시각**을 모두 확인해야 내부 bookkeeping으로 처리한다. 이전 표시 이벤트나
  재사용 ID, 알 수 없는 소유권은 면제하지 않는다. 새 native 검사가 실패하면 기존
  title 기반 focus 검사로 다시 통과시키지 않는다. 시간만으로 이벤트를 무시하지 않는다.
- 출처 없는 global touch는 `windowId < 0 && package == null`인 touch/exploration
  START/END만 해당한다. 내부 C 터치라고 인증하지 않는다. 기존 Runtime·private source·
  epoch·실행 권한은 여전히 무효화하고 표시/선택 의도만 유지한다. 대상 ID나 package를
  가진 외부 이벤트, scroll, 화면/정책/동의/서비스 변경은 기존 표시 무효화를 유지한다.
- TalkBack exploration과 touch의 활성 상태를 각각 보관한다. 둘 중 하나만 END가
  왔다고 userInteracting을 false로 바꾸지 않는다. 명시적 선택 의도 수신과 실행은
  별개다. 실제 실행은 양쪽 종료와 native cleanup이 확인된 뒤에만 준비한다.
- `ManualPromptLifetime`은 기존 표시 상한 30초만 관리한다. 이 토큰은 C flow의
  프롬프트/일회 응답에만 쓰며 Runtime currentToken·AutoEligibility에 제공하지 않는다.
  원본 5초 만료는 C flow를 취소하거나 expireEvidence로 버튼을 끄지 않는다. 버튼은
  **새 화면 확인 요청**일 뿐 오래된 분석을 누를 권한이 아니다. 새로운 외부 무효화나
  30초 상한은 응답을 폐기하고 창·음성을 정리한다. 실제 중지 버튼은 계속 작동한다.

## 수동 실행의 실제 경로

`GuardedChoicePresenter`의 generation·lease·표시 옵션 검사를 통과한 CLOSE_ONCE만:

1. C HIDDEN/remove 반환 + `OverlayCleanupBarrier`의 exact native detach·창 부재 확인.
2. touch와 exploration 종료 대기. 제거/조작 대기는 최대 5초이며 재시작하지 않는다.
3. 서비스 epoch를 전진하고 **실제 AndroidSnapshotCollector.capture를 1회 새로 호출**.
   기존 전체 보호 pass·동일 앱 배경 보호·snapshot pass·100ms 한도를 그대로 사용한다.
   해당 동기 호출 전후에 취소/동의/대상 허용/epoch/사용자 조작을 확인한다.
4. 새 결과가 COMPLETE이고 package/window·원본 지문·frame·창 집합·전체 NodeSnapshot이
   원본과 일치하는지 검사한다. 원래 표시된 후보와 새 B 후보의 target도 일치해야 한다.
   민감/불확실/사기 텍스트를 재검사한다. 원본 시각·토큰을 복사하여 새 수집으로
   만드는 경로는 제거했다. 새 수집 timestamp와 새 epoch/token은 collector가 발급한다.
5. 새 tree에서 `DecisionCandidateAttacher`를 다시 실행한다. detection scope의 전달은
   전체 원본/현재 비교를 통과한 경우에만 가능하며 NEW_WINDOW를 임의로 만들지 않는다.
   `F9ServiceSession.executeUser`가 새 F0 scene과 새 analysis로 실제 B 정책을 재실행한다.
6. B의 USER_CHOICE/EXECUTE 및 새 AutoEligibility와 F9의 현재 상태·전체 화면·실제 target
   재검사가 모두 성공할 때만 기존 `AndroidF9ScreenPort.press`의 ACTION_CLICK 1회가 가능하다.
   재시도/좌표/제스처/부모 클릭/닫기 대체는 없다. 기계적 반환과 CLOSED_CONFIRMED는 별개다.

자동 실행의 원본 5초 TTL은 변경하지 않는다. 수동 실행 역시 **새로 실제 수집한 증거의
5초 TTL**을 AutoEligibility·B·F9의 승인/최종 클릭 경계에서 그대로 검사한다.
`F9ServiceExecutionFeature.enabled=false`, `PresetAutoCloseFeature.live=false`,
`allowAutoSkip=false`, 기억 저장/자동 반복 OFF를 유지한다.

## 다른 담당자 영역

C의 `OverlayNativeWindowLifetime.kt`에 읽기 전용 `liveWindow(owner, generation)`만 추가한다.
기존 native callback들이 확인한 add/attach/미제거 상태를 A에서 조회하기 위한 것이다.
새 Android getter나 C 표시/제거/선택 동작, B/D 및 공통 계약은 변경하지 않는다.

## 회귀 및 한계

JVM은 실제 Android driver와 공유하는 native-event 분기, 프롬프트 lease,
GuardedChoicePresenter, 제거→조작 종료→수집 순서, 새 수집/대상 비교를 검사한다.
실제 B 정책·AutoEligibility·F9ServiceSession·F9AutoCloser에 fake F0/press port를 연결해
6초 뒤 동일 팝업의 새 증거만 최대 1회 동작하고, 변경·거부·취소·새 TTL 만료에는 0회임을
검사한다. 원래 DTO 시각을 유지한 epoch 변경은 여전히 5초 이후 거부한다.
P01/P02/P03 모양의 합성 tree와 P12 다음 팝업, 결제/동의/권한/사기 반례를 포함한다.

Android 계측은 기존 TTL 시험의 의미를 표시/증거 분리에 맞게 바꾸고,
실제 서비스에서 C 표시 6초 후 새 F0를 거쳐 P01 단발 실행하는 회귀를 추가한다.
이는 원본 F1 14개 시험이나 정답 변경이 아니다. LG 실측/TalkBack 음성·포커스·실제
수동 두 번 탭·C 제거 이벤트 순서는 JVM에서 입증하지 않는다.

전체 F0 완전성, 미관측 ABA, 원자적인 팝업 identity를 증명하지 않는다. 특히 Android가
배경 page를 노출하지 않아 기존 F9 page proof의 5초 조건을 충족하지 못하면 계속 거부한다.
이를 위해 page proof를 갱신하거나 화면을 지어내지 않는다. native ID/root/title가 불명확하거나
제거 중인 ID에 충돌하는 이벤트가 오면 기존 cleanup sticky 차단도 유지한다.
따라서 모든 LG 이벤트 순서에서 창 유지·실행 성공을 보장한다고 보고하지 않는다.
화면 원문·좌표·창 ID·토큰·지문·예외 원문을 로그/파일로 추가 기록하지 않는다.

실행 환경에는 연결 Android 기기가 없다. LG 실기기 및 Android 계측 실행은 미검증이다.
로컬 검증의 최종 수치는 아래에 별도 기록한다.

## 최종 로컬 검증

- 신규 JVM 27개: ManualPromptLifetimeTest 14개, ManualChoiceRefreshTest 13개.
- 전체 JVM 1,044개: 앱 963, 독립 호스트 55, 합성 팝업 앱 26. 실패/오류/skip 모두 0.
- 앱 Debug/Release, 앱·호스트 AndroidTest APK, 호스트·합성 앱 Debug 빌드 성공.
- 앱 Debug/Release와 호스트 Debug Lint 이슈 0. 합성 앱은 기존
  TypographyFractions/SetTextI18n 경고 2개, 오류 0.
- 기존 패키징 검사 및 새 runtime 클래스의 앱 APK 전용 정의/JVM 시험 미포함 검사 통과.
- 새 판정/수명/재검증 클래스 4개의 Debug/Release javap opcode·분기·호출 대상 비교 일치.
  상수 풀 entry 번호와 Kotlin variant 모듈명만 정규화했으며 판단 상수/호출/분기를 제거하지 않았다.
- F9AutoCloser, AutoEligibility 공통 모델, OverlayCleanupBarrier, Manifest, gradle.properties는
  기준 커밋과 바이트가 같다. 기존 네 worktree의 HEAD와 clean 상태도 유지했다.
- 기존 Windows 서비스 파일의 CRLF를 보존했다. 공백 검사는
  `git -c core.whitespace=cr-at-eol diff --check` 및 신규 파일의 후행 공백 검사로 수행했다.

개발 중 collector의 기존 signature에 없는 checkpoint 인자를 제거하고 동기 호출 전후의
확인으로 교체했다. 추가한 guard 회귀의 표시 전 합성 토큰 설정 오류도 수정했다.
최종 명령은 아래 범위 전체에서 BUILD SUCCESSFUL(3분 11초)을 확인했다.

```sh
JAVA_HOME=/workspace/jdk17 ANDROID_HOME=/workspace/android-sdk ./gradlew --offline --no-daemon \
  :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :popup-test-app:testDebugUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  :capture-test-host:assembleDebugAndroidTest :popup-test-app:assembleDebug \
  :app:lintDebug :app:lintRelease :capture-test-host:lintDebug :popup-test-app:lintDebug
python3 tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk
```

### 변경 파일 11개

- app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt
- app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceConnection.kt
- app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceSession.kt (계약 설명 갱신만)
- app/src/main/java/kr/closeforyou/accessibility/integration/ManualPopupConnection.kt
- app/src/main/java/kr/closeforyou/accessibility/integration/ManualPromptLifetime.kt (신규)
- app/src/main/java/kr/closeforyou/accessibility/integration/ManualChoiceRefresh.kt (신규)
- app/src/main/java/kr/closeforyou/ui/voice/overlay/OverlayNativeWindowLifetime.kt
- app/src/test/java/kr/closeforyou/accessibility/integration/ManualPromptLifetimeTest.kt (신규)
- app/src/test/java/kr/closeforyou/accessibility/integration/ManualChoiceRefreshTest.kt (신규)
- app/src/androidTest/java/kr/closeforyou/accessibility/integration/F9ManualServiceInstrumentedTest.kt
- docs/A_PR58_TALKBACK_MANUAL_REFRESH_2026-10-26.md (신규)

커밋·푸시·PR 수정·병합은 하지 않았다. 이 결과를 LG 벨벳/TalkBack 성공으로 승격하지 않는다.
