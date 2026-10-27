# Debug 합성 지연 팝업: 실제 서비스 → B WAIT → 새 F0 → F9

기준은 `b3b87b3c1286c985936b9fa09a32c13726a46b2b`, 별도 worktree는 `/workspace/a-debug-delayed-auto`, 브랜치는 `feat/a-debug-delayed-auto-close-20261028`이다. 기존 7개 worktree 및 미커밋 변경을 보존한다. 커밋·푸시·PR 변경은 하지 않는다.

## 구현과 실행 범위

일반·Release의 `F9ServiceExecutionFeature.enabled=false`, `PresetAutoCloseFeature.live=false`, 자동 SKIP OFF, 기억 규칙 자동 실행 OFF는 유지한다. Debug에만 별도 개발 UI가 있다. **이 UI에서 명시적으로 확인한 한 시험에 한해 실제 ACTION_CLICK이 가능**하다. 실수로 비실행 통합과 혼동하지 않는다.

실행 범위는 정확히 `kr.closeforyou.popuptest`다. 다른 패키지·접두어 일치는 허용하지 않는다. 접근성 서비스, 수집 동의, 허용 앱, 분석 시작, C의 온보딩 완료·자동 닫기 스위치·확인된 광고/쿠폰 프리셋이 매 검사에서 필요하다. UI/intent/provider로 이 설정을 대신 쓰지 않는다. C의 기존 `autoCloseConsentNow(live=true)` 순수 계약을 Debug 범위에서만 사용하며, 광고·쿠폰 동의 범위를 새로 만들어 주지 않는다.

개발 UI는 intent extra로 켤 수 없다. 켜기 버튼과 확인 창을 거친 프로세스 메모리 티켓만 사용한다. 결과·실행 거부·대기 취소·서비스 종료 뒤에는 다시 UI에서 켜야 한다. 처음 수집조차 차단돼 대기에 진입하지 못한 경우에는 아래 끄기 UI로 시험을 종료한다. 티켓 재발급 시 이전 실행 결과가 새 시험을 끄거나 그 통계에 합쳐지지 않는다. Release에는 활성화 Activity·arm API·새 진단 로거가 없다.

## 실제 경로

1. 실제 접근성 이벤트 → 기존 180ms debounce → `AndroidSnapshotCollector.capture`의 전체 F0 두 pass/100ms/2,400회 한도 → 기존 geometry 거부.
2. `PopupCandidateDetector.detect`의 실제 별도 bounded APPLICATION 창 → 기존 evidence pipeline의 재수집·현재성 확인.
3. `F9ServiceConnection.collected`가 실행 동의와 Debug 범위를 확인한다. 활성화된 개발 시험은 C 선택창을 동시에 띄우지 않고, 먼저 실제 `F9CollectedSource → DecisionCandidateWiring → F9EligibilityAdapter → B policy`를 처리한다. C 소비자 구독은 최초 발행 전에 준비된다. 개발 시험이 아닌 기존 수동 경로는 유지한다.
4. **B가 실제 `WAIT / WAITING_FOR_TARGET`를 반환해야만** `DelayedPopupWatch`가 시작된다. 같은 화면에 이미 버튼이 있는 최초 후보는 B가 EXECUTE하더라도 추가 대기 증거 guard에서 클릭 0회로 차단한다.
5. 대기는 최초 최대 30초의 팝업 identity 비교용 상태다. 실행 토큰이나 F0 완료 인증을 저장하지 않는다. 이벤트는 기존대로 이전 token/작업/Runtime을 먼저 무효화한다. 이 30초가 자동 실행 증거의 **기존 5초 TTL을 연장하지 않는다**.
6. 같은 창·패키지의 콘텐츠 이벤트, 또는 같은 창의 순수 bounds 변경은 **새 전체 수집 요청만** 허용한다. 패키지가 없는 순수 bounds 이벤트도 실행 승인 자체가 아니다. 터치·스크롤·포커스·다른 창·패키지·설정·동의·서비스/구성 변경은 대기를 취소한다. TalkBack 탐색을 자동 포커스로 추정하여 무시하지 않는다.
7. 새 F0 token/epoch, 같은 앱·창 집합·frame, 같은 기존 노드/부모 경로/순서/문구·속성을 확인한다. 대기 시작 때 실제 F9가 읽은 배경 페이지 지문도 새 F9 읽기와 비교한다. 배경이 숨겨졌다면 기존 독립 page proof만 쓰며, 클릭 직전 F9의 원래 5초 검사를 그대로 통과해야 한다. 기존 노드 geometry 이동과 popup 창 resize는 새 F0의 유효한 bounds에서만 비교한다. 노드 생략·삭제·문구/역할 교체·여러 신규 노드·잘못된 geometry는 거부한다. 기존 부모 끝에 추가된 **라벨 있는 native Button 하나**만 이번 구현의 새 대상이 될 수 있다. 라벨 없는 X, ImageButton, 클릭 가능한 TextView는 이 자동 경로에서 지원하지 않는다.
8. `reobserveBoundedApplicationWindow`는 이 fresh tree 검증 뒤 실제 현재 frame의 같은 inset 검사를 수행한다. 기존 B 계약의 `NEW_WINDOW`는 여기서 **현재 별도 창의 popup scope 재확인**을 나타낸다. 새 창이 다시 생성됐다는 주장이나 과거 감지 결과 복사가 아니다. 보통 detect가 동일 창의 콘텐츠 변경에 새 창 신호를 내지 않는 문제를 이 한정 경로에서 처리한다. 일반 F1 판단 조건은 바꾸지 않는다.
9. 새 전체 텍스트·버튼으로 B가 다시 분류/결정한다. 유효한 새 F0 없이 이전 분석으로 실행하지 않는다. B의 PRESET/EXECUTE, A의 다섯 eligibility 조건, 새 token·5초 TTL·설정·cleanup, 클릭 직전 전체 재탐색/동일 대상 검증을 모두 통과해야 기존 `F9ServiceSession → F9AutoCloser`가 **ACTION_CLICK 최대 한 번** 호출한다. 좌표 클릭·fallback·재시도는 없다.
10. 실제 클릭 반환값과 닫힘은 다르다. 기존 F9가 동일 앱의 변경되지 않은 배경 페이지로 돌아왔고 popup 창이 사라졌음을 확인할 때만 `CLOSED_CONFIRMED`다. 여전히 팝업이 있으면 `STILL_PRESENT`, 검증 부족/취소는 `UNKNOWN`이며 성공 안내를 하지 않는다. 클릭 이후 일반 창 제거 이벤트도 토큰을 무효화하지만 이미 호출된 클릭의 닫힘 관측만 허용한다. 사용자/동의/서비스 변경은 이 관측도 중단한다.

`ManualPopupConnection`·C native lifetime·`OverlayCleanupBarrier`·LG CONTENT_INVALIDATED/소유권/cleanup 안전 조건은 수정하지 않는다. cleanup이 불확실하면 실행하지 않는다. C 창을 동시에 보이게 하여 F0를 우회하지 않는다.

## 실제 기기에서 막힐 수 있는 안전 조건

- Android가 배경 앱 창을 숨길 때 F9는 독립적으로 읽어 둔 배경 proof를 요구한다. 이 proof도 원래 5초를 유지한다. 갱신을 위해 과거 snapshot 시각을 복사하거나 popup을 배경으로 간주하지 않는다.
- 자동 포커스와 사용자 탐색의 구분은 새로 추정하지 않는다. TalkBack ON에서 포커스 이벤트가 대기를 취소할 수 있으며, 이는 기기 확인 전 해결됐다고 주장하지 않는다.
- 현재 엄격한 append-one-button 비교와 다른 구조의 앱은 실행하지 않는다. event가 안 오거나 F0/geometry가 차단되면 버튼이 보이더라도 클릭하지 않는다. 미관측 ABA·window ID 재사용을 포함해 별도 시점의 Android 수집을 원자적이라고 주장하지 않는다.
- 비협력적인 Android/Binder 호출을 강제로 중단하거나 단속적 수집 사이의 연속 현재성을 입증하지 못한다.
- 기존 `WINDOW_OUTSIDE_FRAME`, 숨김 Scroll 자식 `REVERSED_Y`, 원본 F1 14개 `UNOBSERVED`는 해결한 것이 아니다. 보호 기준·bounds·timeout을 완화하지 않는다.

## 합성 앱 변경과 P31 범위

D 영역 변경은 시험 자극에 한정한다. 기존 P23 문구·기본 즉시 Dialog 표시·3초 지연·정답은 그대로다. 선택적인 `f9_defer_popup=true`/`f9_show_deferred_popup=true`는 실제 일반 배경을 먼저 수집하도록 하는 순서 제어다. 실제 Android Button을 추가하는 기존 함수와 listener를 재사용한다. provider 결과·B 판정·접근성 이벤트를 주입하지 않는다.

별도 Debug `ADelayedAdActivity`는 구매/결제/동의 문구가 없는 실제 native Dialog에 3초 뒤 Button을 추가한다. P31 대체 정답이 아니며, 기본 scenario catalog를 바꾸지 않는다. 새 광고 장면의 listener는 고정 코드만 기록한다.

**기준 커밋의 재현 앱에는 #59 P31이 없다.** #59 소스를 현재 A 브랜치에 임의로 섞지 않는다. P31 검증은 #59가 포함된 별도 APK에서 수행하고 APK 버전을 구분한다. 이 브랜치의 재현 APK를 설치하면 기존 #59 APK가 덮어써질 수 있다. `지금 구매하기`가 있는 P31은 기존 Privacy/F9ContentSafety/B 검사에서 차단돼야 한다. 그 차단을 해제하거나 구매 문구를 삭제하지 않는다. 버튼 구조가 ImageButton이면 기존 자동 native Button 제한에서도 차단될 수 있다.

## 삼성 실기기 PowerShell 절차

이번 코드는 아직 로컬이며 원격에 없다. **이 worktree의 변경 파일이 적용된 Windows 소스**에서 빌드한다. 개인정보 없는 테스트 프로필과 합성 앱만 사용한다. 실제 폰과 에뮬레이터 serial을 혼동하지 않는다.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
$serial = "<삼성 테스트폰 serial>"
.\gradlew.bat --no-daemon :app:assembleDebug :popup-test-app:assembleDebug :app:assembleDebugAndroidTest
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial install -r .\popup-test-app\build\outputs\apk\debug\popup-test-app-debug.apk
& $adb -s $serial install -r .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
```

폰의 UI에서 접근성 서비스를 직접 활성화한다. 수집 동의, 합성 앱 허용, 분석 시작을 설정한다. C 설정에서 온보딩 완료·광고/쿠폰 프리셋·자동 닫기 스위치에 직접 동의한다. 캡처/OCR 동의는 필요하지 않으며 자동 SKIP·기억 자동 실행은 켜지 않는다. `settings put secure`나 권한 adopt로 우회하지 않는다. 기기의 다른 접근성/창 이벤트는 안전 취소를 만들 수 있다.

별도 PowerShell 창에서 고정 코드 태그만 수집한다. 전체 logcat, uiautomator dump, screenshot은 수집하지 않는다.

```powershell
& $adb -s $serial logcat -v brief -s A_DelayedAuto:I A_DelayedFixture:I A_ManualPrompt:I '*:S'
```

실제 서비스 opt-in 계측은 사람의 기존 설정을 검사한 뒤 **개발 UI의 켜기/확인 버튼만** 조작한다. target 닫기 Button은 계측이 누르지 않고 실제 F9가 누른다. 미설정 상태이면 실패하고 임의로 동의를 쓰지 않는다. 테스트는 각각 독립적으로 실행한다.

```powershell
& $adb -s $serial shell am instrument -w -e delayedAutoSynthetic true -e class kr.closeforyou.accessibility.integration.DelayedAutoServiceInstrumentedTest#p23RealDelayedCouponIsPressedOnceAndClosureIsObserved kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s $serial shell am instrument -w -e delayedAutoSynthetic true -e class kr.closeforyou.accessibility.integration.DelayedAutoServiceInstrumentedTest#independentSafeDelayedAdIsPressedOnceAndClosureIsObserved kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner
```

수동 확인은 아래와 같다. 같은 시험을 반복하려면 UI에서 명시적으로 다시 켠다. 재활성화는 기존 F9의 중복 실행 이력을 지우지 않으므로 같은 세션에서 같은 popup은 다시 차단될 수 있다. 자동 반복하지 않는다.

```powershell
& $adb -s $serial shell am start -n kr.closeforyou/.accessibility.testing.DelayedAutoCloseActivity
# 폰에서 실제 개발 시험 활성화 + 확인. 다음 명령은 동의 설정을 변경하지 않는다.
& $adb -s $serial shell am start -W -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P23 --ez f9_defer_popup true
# 같은 RUN에서 PAGE_VERIFIED를 실제 관측하면 즉시 다음 명령. 오래 기다렸다면 proof 만료 차단이 정상이다.
& $adb -s $serial shell am start -W -f 0x20000000 -n kr.closeforyou.popuptest/.PopupScenarioActivity --ez f9_show_deferred_popup true
```

별도 안전 광고는 개발 시험을 다시 활성화한 뒤 `ADelayedAdActivity`를 배경으로 시작하고, PAGE_VERIFIED 후 `--ez show_delayed true`를 singleTop으로 전달한다.

```powershell
& $adb -s $serial shell am start -W -n kr.closeforyou.popuptest/.ADelayedAdActivity
& $adb -s $serial shell am start -W -f 0x20000000 -n kr.closeforyou.popuptest/.ADelayedAdActivity --ez show_delayed true
```

P31은 #59 APK의 버전과 장면 존재를 먼저 확인하고 그 APK를 별도로 설치한 경우에만 실행한다. 이 브랜치의 재현 APK에서 아래 명령을 보내면 P31이 없으므로 그 결과를 P31 관측으로 기록하면 안 된다.

```powershell
# $pr59Apk는 #59에서 직접 빌드한 Debug APK 경로. 개인정보 없는 합성 앱만 설치한다.
& $adb -s $serial install -r $pr59Apk
& $adb -s $serial shell am start -n kr.closeforyou/.accessibility.testing.DelayedAutoCloseActivity
# 사람의 개발 시험 활성화/확인 후 장면 시작. 구매 버튼은 누르지 않는다.
& $adb -s $serial shell am start -W -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario P31
```

관측된 B 결정의 `REASON`/F9 `BLOCK`, 또는 그 이전의 실제 수집/분석 차단을 구분한다. `CLOSED_CONFIRMED`가 없으면 닫힘 성공을 기록하지 않는다. B에 도달하지 않은 차단을 B 거절이라고 단정하지 않는다. P31 관측 후에는 개발 UI의 끄기 버튼으로 종료하고, 안전 광고/P23을 다시 시험할 경우 이 브랜치 재현 APK와 버전을 다시 구분한다.

기대 순서는 `ARMED → PAGE_VERIFIED → CANDIDATE → B_DECISION(NEXT=WAIT) → WAITING_FOR_TARGET → RECOLLECT_REQUESTED → REOBSERVED → B_DECISION(NEXT=EXECUTE) → F9_RESULT(ATTEMPTED=YES;OUTCOME=CLOSED_CONFIRMED)`이다. 이는 **기대값이지 이번 실측 로그가 아니다**. 버튼 생성 전 실제 클릭 0회, 이후 1회, C 선택창 동시 표시 없음과 실제 원본 popup 소멸을 함께 확인한다. 로그의 기계적 클릭만으로 성공하지 않는다. TalkBack ON/OFF, 대기 중 외부 터치·스크롤·팝업 교체, 동의 철회, 원본 popup 유지 오류를 각각 확인한다. 거부 사유는 기존 B/F9 enum을 그대로 보고한다.

기존 LG 문제는 `A_ManualPrompt`의 `A_MANUAL_OWNERSHIP`/`A_MANUAL_CLEANUP` 고정 enum을 유지한다. 실제 C를 표시하는 수동 P01과 이 자동 대기를 별도 시험하여 CONTENT_INVALIDATED/CLEANUP_FAILED 회귀를 확인한다. C 소유권 불명확을 임의 OWNED로 바꾸지 않는다.

## 변경 파일

총 20개(기존 수정 6개, 신규 14개), 모두 unstaged다.

- `app/src/androidTest/java/kr/closeforyou/accessibility/integration/DelayedAutoServiceInstrumentedTest.kt`
- `app/src/debug/AndroidManifest.xml`
- `app/src/debug/java/kr/closeforyou/accessibility/integration/DebugDelayedAutoClose.kt`
- `app/src/debug/java/kr/closeforyou/accessibility/testing/DelayedAutoCloseActivity.kt`
- `app/src/debug/res/values/a_delayed_auto_ids.xml`
- `app/src/debug/res/values/a_delayed_auto_strings.xml`
- `app/src/main/java/kr/closeforyou/accessibility/CloseForYouAccessibilityService.kt`
- `app/src/main/java/kr/closeforyou/accessibility/PopupCandidateDetector.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/DelayedPopupWatch.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceConnection.kt`
- `app/src/main/java/kr/closeforyou/accessibility/integration/F9ServiceSession.kt`
- `app/src/release/java/kr/closeforyou/accessibility/integration/DebugDelayedAutoClose.kt`
- `app/src/test/java/kr/closeforyou/accessibility/integration/DelayedAutoFlowTest.kt`
- `app/src/testDebug/java/kr/closeforyou/accessibility/integration/DebugDelayedAutoCloseTest.kt`
- `app/src/testRelease/java/kr/closeforyou/accessibility/integration/DelayedAutoReleaseTest.kt`
- `docs/A_DEBUG_DELAYED_AUTO_CLOSE_2026-10-28.md`
- `popup-test-app/src/debug/AndroidManifest.xml`
- `popup-test-app/src/debug/java/kr/closeforyou/popuptest/ADelayedAdActivity.kt`
- `popup-test-app/src/debug/res/values/a_delayed_ad_strings.xml`
- `popup-test-app/src/main/java/kr/closeforyou/popuptest/PopupScenarioActivity.kt`

B/C 정책·공통 DTO·main Manifest·gradle.properties·기존 원본 14개 계측은 수정하지 않는다. D 시험 자극의 변경 이유와 범위는 위 별도 절에 기록했다.

## 검증 기록

JVM은 실제 F1 재관측·B wiring/classifier/policy·F9ServiceSession·F9AutoCloser와 fake Android screen port/가짜 시계를 함께 사용한다. 성공 enum만 변환하는 별도 모형이 아니다. 실제 Android recycle/이벤트/클릭 성공을 JVM으로 입증했다고 주장하지 않는다. 신규 JVM 메서드는 27개(공유 흐름 21, Debug 범위/티켓/동의 5, Release OFF 1)이며 Debug/Release에서 48건 실행된다.

| 이번 실제 로컬 실행 | 결과 |
| --- | --- |
| 앱 Debug JVM | 1,046 통과 |
| 앱 Release JVM | 1,027 통과 |
| 호스트 Debug JVM | 55 통과 |
| 재현 앱 Debug JVM | 26 통과 |
| 실행 합계(Debug/Release 공통 회귀 중복 포함) | **2,154 / 실패·오류·skip 0** |
| 앱 Debug/Release, 앱·호스트 AndroidTest 및 재현 앱 Debug/AndroidTest APK 빌드 | 통과 |
| 앱 Debug/Release·호스트 Lint | 이슈 0 |
| 재현 앱 Debug Lint | 오류 0, 기존 TypographyFractions/SetTextI18n 경고 2 |
| 기존 DEX/패키징 격리 및 새 Debug/Release 격리 | 통과 |

재현 앱은 기존 Gradle 설정에서 Release variant를 만들지 않는다. 존재하지 않는 `:popup-test-app:assembleRelease`를 최초 검증 명령에 넣어 task 조회가 실패했으며, 설정을 바꾸지 않고 지원되는 Debug variant와 본 앱 Release로 검증했다. 신규 회귀의 초기 fake port 가시 경로 누락과 대기 identity의 배경 페이지 비교 누락을 수정한 뒤 위 전체 검증이 통과했다. 마지막 AndroidTest 예외 메시지 정규화 후 APK/Lint도 별도로 재실행하여 통과했다. 기존 7개 worktree의 HEAD·status·기존 미커밋 파일 해시 동일성과 main Manifest/gradle.properties 바이트 동일성도 확인했다.

Release APK의 실제 DEX에 개발 Activity와 `A_DELAYED_AUTO;`/`A_DelayedAuto` 문자열이 없다. Release variant JVM은 arm API 부재와 모든 패키지 거부를 검사한다. manifest의 인터넷 권한은 없으며 기존 Debug 마이크 권한을 새로 추가하거나 사용하지 않았다. 공백 검사는 기존 두 소스의 CRLF를 `git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff --check`로 인식하고 미추적 파일도 별도로 검사한다.

현재 `adb devices -l` 결과는 연결 기기 없음이다. 삼성/LG 실기기 및 에뮬레이터 end-to-end는 **미실행**이며, 이번 변경의 완료를 실제 기기 성공으로 해석하지 않는다.
