# P01: C 소유권 거부와 대상 팝업 변경을 분리하는 표시 재검사

기준은 `58cce703e613b27e383d94fdf2b09b81ad915e61`, worktree는 `/workspace/a-samsung-p01`, 브랜치는 `fix/a-samsung-p01-lifetime-20261028`이다. P23을 포함한 다른 9개 worktree의 HEAD/status/기존 미커밋 파일 해시를 작업 전후 대조한다. 커밋·푸시·PR·병합은 하지 않는다.

## 실측과 코드로 확인된 원인

사용자 삼성 실측에서 ATTEMPT 6은 SHOWN 뒤 WINDOW_STATE_INVALIDATED/STOP_REQUESTED였다. ATTEMPT 7은 새 SHOWN과 SPEECH READY 이후 CONTENT 이벤트의 NOT_OWNED/CHECK=PACKAGE/PACKAGE=MISMATCH, CONTENT_INVALIDATED, OVERLAY_DISMISS_STARTED, STOP_REQUESTED, SPEECH CANCELLED가 이어졌다. 다른 ATTEMPT를 동일 이벤트처럼 결합하지 않는다.

직접 경로는 `ManualPopupConnection.shouldIgnore(false) → CloseForYouAccessibilityService.invalidate → ManualPopupConnection.invalidate → dismissOverlay → C onDismissed → PromptAnnouncementListener.onDismissed/announcer.stop`이다. C의 실제 remove 실패나 TTS 초기화 실패로 이번 취소를 설명하지 않는다. TTS CANCELLED는 선택창 제거에 따른 실제 취소 전파와 일치한다.

**C 소유 이벤트가 아니라는 사실은 대상 팝업 변경의 증거가 아니다.** 원본 앱 자신의 정상 콘텐츠 알림도 C의 package 검사에는 불일치한다. 기존 서비스는 그 거부 뒤 대상 팝업을 확인하지 않고 즉시 선택창을 제거했다. 제공 로그만으로 해당 CONTENT 이벤트가 원본 앱/시스템/다른 앱 중 어디에서 왔는지, 실제 팝업이 바뀌었는지까지 확정할 수는 없다.

`AccessibilityChoiceOverlay`와 C 소스는 읽기 전용으로 대조했다. C는 NOT_FOCUSABLE 접근성 overlay를 생성하며 A는 native lifetime·generation·live 소유 창/root를 확인한다. 이번 수정은 C의 창·콜백·음성 정책이나 공통 계약을 변경하지 않는다.

## 새 실제 경로

1. 기존 `shouldIgnore`가 현재 C native 창 소유권을 증명하면 기존 처리 그대로다. PACKAGE=MISMATCH나 오래된 이벤트를 OWNED로 바꾸지 않는다.
2. 증명하지 못한 **CONTENT 또는 WINDOW_STATE** 이벤트에서, 현재 수동 선택창이 유효하고 건강하며 사용자 조작/선택 실행 중이 아니고 별도 NEW_WINDOW 팝업인 경우만 재검사를 시도한다. 스크롤·클릭·텍스트 편집·외부 포커스·창 제거/집합 변경은 새 예외에 넣지 않는다.
3. `reviewManualDisplay`의 첫 콜백은 실제 서비스 `invalidate(clearBaseline=false, permissionOrInteraction=true, cause=DISPLAY_REVIEW_PENDING)`다. pending 수집·evidence·TTL 작업과 Runtime token을 폐기하며 F9 session의 raw evidence/page/revision도 무효화한다. 이전 실행 허가를 보존하지 않는다.
4. `DISPLAY_REVIEW_PENDING`은 기존 30초 C 표시 의도만 임시 보존한다. callback·TTS job을 취소하거나 표시 lease를 재시작하지 않는다. 처리는 Main에서 동기적으로 이어지며 별도 유예 시간이나 새 비동기 작업이 없다.
5. A는 같은 native C owner/generation/attached live ID, 표시 lease, before/live 소유 창 집합을 검사 전후 확인한다. 이벤트의 package/window/time을 믿는 대신 **현재 C 창 자체**를 증명한다. 이 증명이 불가능하면 제거한다.
6. `AndroidF9ScreenPort.verifyDisplayedPopup`는 그 원래 APPLICATION 창의 root를 창 핸들로 직접 얻고 **package/window 소유권을 먼저** 확인한 뒤 기존 reader의 전체 보호 트리·자식 순회·한도·정리·frame/window endpoint 검사를 수행한다. 다른 앱 root 내용이나 event.source는 읽지 않는다. 같은 앱 배경 root가 있으면 기존 reader의 보호 검사도 수행한다.
7. 이 읽기에 한해 이미 증명한 정확한 C 창 하나가 위에 있는 것을 허용한다. C가 active/input focus를 소유할 수 있으므로 대상 창과 C 창의 역할을 분리한다. 다른 active/focused 앱·시스템 창, IME, 다른 overlay, 중복 ID, 잘못된 layer, 가림/geometry/보호 오류는 거부한다. 단순 package 예외가 아니다.
8. target/frame 및 **전체 원래 노드의 경로·문구·description·class·ID·bounds·actions·enabled/clickable**를 원본과 대조한다. 정확한 C 창만 제외하고 기존 창 집합도 비교한다. 원래 대상 창의 active/focused 플래그만 C에 이전될 수 있다. 이외의 창·bounds·layer·보호 정보 변경은 허용하지 않는다. 이 비교는 실행용 snapshot을 정규화하거나 새 토큰을 만들지 않는다.
9. SAME이면 실행 증거는 폐기된 그대로 두고 표시와 현재 TTS만 유지한다. CHANGED/UNAVAILABLE, 조회 예외·취소·해제 실패·시간 초과이면 기존 revoke/dismiss/stop/cleanup 경로를 실행한다. 취소/원래 예외는 정리 예외로 성공 처리하지 않는다.

수집 자체와 native 소유권 전후 확인을 포함한 재검사에는 100ms 제한을 둔다. 기존 collector와 F9 reader의 100ms/노드 한도도 늘리지 않는다. 비협력적인 Binder/framework 호출을 강제로 끊는 보장은 없으며, 마감 뒤의 결과는 표시 유지로 받지 않는다. 이벤트마다 이 전체 읽기가 필요하므로 비용이 0이 아니며, 부하나 OEM 조회 불가에서는 계속 fail-closed로 제거될 수 있다.

## 표시 유지와 클릭 권한은 별개

새 메서드의 외부 반환값은 SAME/CHANGED/UNAVAILABLE뿐이다. `F9Scene`, Ready, SnapshotToken 또는 AutoEligibility를 이 표시 경로에서 발행하지 않는다. `DisplayRead`는 private 호출 인자이며 전역 'overlay 허용' 상태가 아니다. 일반 `read()`/`press()`는 계속 이 인자를 전달하지 않아 기존 overlay 거부와 클릭 전 검사를 유지한다.

사용자가 닫기를 선택하면 C 창 제거/실제 cleanup 확인 → interaction 종료 → **새 실제 F0 수집** → 원본 전체 팝업/선택 대상 일치 → 새 token/수집 시각 → B USER_CHOICE 재판정 → F9 클릭 직전 읽기·현재성 검사 → 최대 1회 실행 순서를 그대로 거친다. SAME 표시 결과를 실행 증거로 사용하지 않는다. 원본 시각을 복사해 실행 TTL을 갱신하지 않는다.

30초 표시 lease와 새 실행 증거의 5초 TTL은 그대로다. 동의 철회·일시정지·서비스 종료·구성 변경·확인된 외부 조작은 계속 무효화한다. 구매·결제·동의·권한·사기 의심·모호한 버튼 금지는 F0/B/F9에서 유지된다. 운영 자동 닫기·자동 SKIP·자동 반복은 OFF다.

## Debug 해석

기존 A_ManualPrompt 고정 enum 로그를 사용한다. 예시는 **기대 형식이지 새로운 실측이 아니다**.

```text
A_MANUAL_PROMPT;ATTEMPT=7;POINT=DISPLAY_REVIEW_STARTED;CAUSE=NONE
A_MANUAL_PROMPT;ATTEMPT=7;POINT=INVALIDATED;CAUSE=DISPLAY_REVIEW_PENDING
A_MANUAL_PROMPT;ATTEMPT=7;POINT=DISPLAY_REVIEW_FINISHED;CAUSE=TARGET_RECONFIRMED
A_MANUAL_PROMPT;ATTEMPT=7;POINT=DISPLAY_RETAINED;CAUSE=TARGET_RECONFIRMED
```

기존 logger의 `POINT/CAUSE`에서 DISPLAY_REVIEW_STARTED/FINISHED, TARGET_RECONFIRMED/TARGET_CHANGED/TARGET_REVIEW_UNAVAILABLE 코드를 본다. OWNERSHIP의 NOT_OWNED/PACKAGE=MISMATCH가 계속 있어도 TARGET_RECONFIRMED는 **현재 원본 팝업의 독립 비교 성공**을 뜻하며 해당 이벤트가 C 자체였다는 주장이 아니다. 일치하면 STOP_REQUESTED/SPEECH CANCELLED를 새로 호출하지 않는다. 변경/불확실이면 기존 CONTENT_INVALIDATED 또는 WINDOW_STATE_INVALIDATED 제거와 음성 취소가 정상이다.

로그에는 화면 원문·원본 package·실제 window ID·좌표·token·fingerprint·예외 메시지를 추가하지 않는다. 기존 Release factory는 null이므로 새 Debug 로그는 Release에서 출력되지 않는다. 판단 조건은 Debug/Release에서 동일하다.

기존 trace는 offer별 동일 POINT/CAUSE를 중복 제거한다. TARGET_RECONFIRMED 한 행이 이후 모든 이벤트의 검증을 뜻하거나 인접한 OWNERSHIP 행과 원자적으로 결합된 증거는 아니다. 실제 코드는 해당하는 각 이벤트에서 retire/read를 다시 수행한다. 로그가 중복 제거돼도 이후 CHANGED/UNAVAILABLE은 다른 cause로 기록하고 제거한다.

## 회귀와 로컬 검증

`ManualDisplayReviewTest` 20개는 실제 reader/driver가 사용하는 창 선택·전체 비교·retire/read/remove 순서와 취소/정리 계약을 검사한다. 원래 화면 소멸/교체, 버튼/문구/경로/geometry 변경, 잘못된 native 창, 타 앱/시스템 active 창, IME/다중 overlay, 보호 문구, raw 실행 증거 폐기, 기존 F9의 overlay 중 클릭 0회, 6초 후에도 새 F0가 필요한 조건을 포함한다.

`ManualPromptSpeechTest` 2개를 추가하여 실제 C listener와 실제 TtsPopupAnnouncer에 fake engine을 연결한다. 재검사 SAME일 때 6초 이후 진행 중 안내가 취소되지 않고, CHANGED일 때 여전히 취소되는지를 같은 공유 review 함수로 검사한다. 표시/실행 분리·수동 새 F0/B/F9의 기존 회귀도 전체 suite로 유지한다. 단순 성공 enum만 별도 모형에서 테스트하는 것으로 끝내지 않는다. Android framework root/재활용/실제 발화·클릭 성공을 JVM으로 입증하지 않는다.

초기 연결 과정의 reader 메서드 미연결 컴파일 오류는 실제 메서드를 연결하여 수정했으며, 성공한 후속 검증과 구분한다. 최종 로컬 실행은 다음과 같다.

| 검증 | 결과 |
| --- | --- |
| 신규 공유 JVM 회귀 | 22개 메서드, Debug/Release에서 44건 실행 |
| 앱 Debug JVM | 1,072 통과, 실제 실행 |
| 앱 Release JVM | 1,057 통과, 실제 실행 |
| 호스트 Debug JVM | 55개 기존 통과 결과 확인, task UP-TO-DATE |
| 합성 앱 Debug JVM | 26개 기존 통과 결과 확인, task UP-TO-DATE |
| 앱 실제 JVM 실행 합계 | 2,129건, 실패·오류·skip 0 |
| 호스트/합성 앱을 포함한 결과 합계 | 2,210건, variant 중복 및 기존 결과 재사용 포함 |
| 앱 Debug/Release·AndroidTest APK 및 합성 앱 Debug | 통과 |
| 앱 Debug/Release·호스트 Debug Lint | 이슈 0 |
| APK 패키징·DEX 격리 | 통과 |
| Manual Debug 로그 문자열의 Release 제외 | 통과 |
| 다른 9개 worktree 보존 | HEAD/status/기존 미커밋 파일 해시 일치 |

최종 명령은 JDK 17에서 `BUILD SUCCESSFUL in 3m 52s`였다. 앱의 두 JVM task는 실제 실행됐으며 기기 계측은 컴파일/패키징까지만 확인했다. 전체 명령과 결과 XML은 로컬 검증 기록으로 대조했다.

```bash
JAVA_HOME=/workspace/jdk17 ANDROID_HOME=/workspace/android-sdk ./gradlew --offline --no-daemon --max-workers=1 \
  :app:testDebugUnitTest :app:testReleaseUnitTest :capture-test-host:testDebugUnitTest :popup-test-app:testDebugUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :popup-test-app:assembleDebug \
  :app:lintDebug :app:lintRelease :capture-test-host:lintDebug
```

기존 `tools/verify_capture_test_apks.py --release-apk app/build/outputs/apk/release/app-release-unsigned.apk`가 통과했다. 실제 APK DEX의 `A_MANUAL_PROMPT;`/`A_MANUAL_SPEECH;` 문자열은 Debug에 있고 Release에는 없다. 기존 CRLF 소스는 줄바꿈을 보존하여 `git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff --check`로 검사하고, 변경/신규 파일의 실제 후행 공백도 따로 확인하여 통과했다. 코드를 재포맷하여 통과시키거나 Git 설정을 변경하지 않았다. `adb devices -l`에는 연결 기기가 없으며 삼성/LG 실기기·에뮬레이터는 **미실행**이다.

## 삼성 재실측

이 수정은 로컬 미커밋이므로 현재 origin 브랜치를 받는 것만으로 적용되지 않는다. 수정본이 적용된 Windows 소스에서 다음을 실행한다. 접근성 서비스·수집 동의·시험 앱 허용·분석 시작은 사람이 기존 UI로 설정한다. 자동 실행 스위치를 새로 켜거나 adb로 동의를 쓰지 않는다.

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :popup-test-app:assembleDebug
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
$serial = "<삼성 테스트폰 serial>"
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
# 필요한 경우만 합성 앱 설치. #59 등 더 최신 재현 APK와 버전을 구분한다.
# & $adb -s $serial install -r .\popup-test-app\build\outputs\apk\debug\popup-test-app-debug.apk
& $adb -s $serial logcat -v brief -s A_ManualPrompt:I '*:S'
```

다른 PowerShell 창에서 같은 adb/serial을 설정하고 다음을 한 번 실행한다.

```powershell
& $adb -s $serial shell am start -W -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P01
```

TalkBack ON/OFF 각각 선택창과 안내를 6초 이상 관찰한다. 이벤트 소유권 거부 뒤 TARGET_RECONFIRMED이면 같은 창과 음성이 유지되는지 확인한다. TARGET_CHANGED/UNAVAILABLE이면 제거되는 것이 안전 동작이다. 이후 사람의 명시적인 C 닫기 선택에서 새 F0/SNAPSHOT_MATCH/TARGET_MATCH/B_DECISION/F9_VALIDATION을 확인한다. 실제 원본 팝업 소멸과 CLOSED_CONFIRMED가 없으면 닫기 성공으로 기록하지 않는다. adb로 원본 버튼·좌표를 누르지 않는다.

별도 음성 재생 중 원본 팝업 종료/교체·다른 앱으로 전환·외부 터치/스크롤·동의 철회를 확인하여 제거/실행 차단이 유지되는지 본다. Android 실행 환경에서 관측하지 않은 자동 포커스/외부 이벤트의 출처를 추정하지 않는다.

## 변경 범위와 한계

- `app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt`
- `app/src/main/java/kr/closeforyou/accessibility/action/AndroidF9ScreenPort.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceConnection.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ManualPopupConnection.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ManualPromptTrace.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/ManualDisplayReview.kt` (신규)
- `app/src/test/java/kr/closeforyou/accessibility/integration/ManualPromptSpeechTest.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/ManualDisplayReviewTest.kt` (신규)
- `docs/A_P01_TARGET_REVIEW_2026-10-29.md` (신규)

총 9개이며 모두 A 소유 코드·회귀·문서다. C/B/D·공통 계약·Manifest·gradle.properties·collector·자동 실행 기능·P23 브랜치는 수정하지 않는다. C 소유권/표시 상태가 불명확하거나 배경 root가 사라지는 OEM 동작, 다른 창 집합 변경, 100ms 내 읽기 불가, 원본 트리의 실제 변경이면 선택창이 여전히 닫힐 수 있다. 최초 native ID가 아직 확인되지 않은 SHOWN 직후도 예외로 통과시키지 않는다.

단속적 별도 읽기는 Android 상태의 원자적 동일성, 미관측 ABA나 창 ID 재사용의 완전 탐지를 보장하지 않는다. 이번 결과를 F0 완전성·원본 F1 14개·Scroll/Window 문제 해결로 확대하지 않는다. 실제 삼성폰에서 유지·음성 완료·새 F0 기반 수동 선택이 성공했는지는 로컬 검증과 별개다.
