# 합성 팝업 호스트의 독립 실행 복구

2026-10-17. `main`의 `50e1a0d`에서 `fix/android-test-runtime` 생성. 보고된 API 37 에뮬레이터의 `NoClassDefFoundError: kotlin.jvm.internal.Intrinsics`를 대상으로 합니다. 이 환경에는 기기·에뮬레이터·KVM이 없어 **Android 런타임 실행은 미검증**입니다.

## 원인과 변경

기존 `androidTest` APK의 DEX에는 SyntheticPopupActivity와 BundledKoreanOcrTestKt가 있지만 Intrinsics 정의는 없었습니다. 본 앱 APK에는 Intrinsics가 있습니다. Android application 계측 APK는 대상 앱과 공유하는 의존성이 패키징에서 제외될 수 있습니다. 계측 runner에서는 대상 앱의 클래스를 함께 로드하지만 `adb am start`로 `.test` 앱의 Activity를 독립 실행하면 그 대상 앱 classloader를 자동으로 공유하지 않습니다. 따라서 Gradle 컴파일/설치 성공이나 androidTestImplementation에 stdlib를 다시 선언한 사실만으로 독립 실행 성공을 보장할 수 없습니다.

합성 화면을 별도 **`:capture-test-host` application 모듈**로 옮겼습니다.

| APK | 패키지·역할 |
|---|---|
| 본 앱 | `kr.closeforyou` — 접근성·보호·OCR, production 소스 변경 없음 |
| 계측 APK | `kr.closeforyou.test` — AndroidJUnitRunner, 실제 OCR/캡처/호스트 실행 시험. 합성 Activity 없음 |
| 독립 호스트 | `kr.closeforyou.capturetest` — SyntheticPopupActivity와 독립 이미지 생성기, 자체 Kotlin stdlib 포함 |

호스트는 Android framework와 명시적 `implementation(kotlin("stdlib"))`만 사용합니다. 본 앱·JUnit·계측 helper에 의존하지 않습니다. release 변형은 비활성화하며 debug/testOnly APK만 생성합니다. 권한·계정·네트워크·저장 기능이 없고 백업/전송은 차단합니다. 운영 앱 APK에 호스트 클래스나 Activity를 넣지 않습니다.

`:app:assembleDebugAndroidTest`는 호스트의 assembleDebug도 빌드합니다. **`:app:installDebugAndroidTest`는 호스트 APK까지 설치하는 명령이 아닙니다.** 별도로 설치해야 합니다. 기존 `.test/SyntheticPopupActivity` 실행 명령은 더 이상 사용하지 않습니다. `.test` 패키지는 계측 runner 용도로 계속 사용하므로 삭제할 필요가 없습니다.

## Windows 재검증

개인정보가 없는 시험 에뮬레이터 한 대만 연결합니다. Android Studio의 Gradle JDK와 Terminal의 JAVA_HOME을 완전한 JDK 17로 맞추고 프로젝트 루트 PowerShell에서 실행합니다. SDK 경로가 다르면 SDK Manager에 표시된 경로로 바꿉니다.

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat --no-daemon :capture-test-host:lintDebug
$androidSdk = "$env:LOCALAPPDATA\Android\Sdk"
$adbPath = Join-Path $androidSdk "platform-tools\adb.exe"
& $adbPath devices
```

각 명령의 성공을 확인한 뒤 설치합니다. `-t`는 testOnly APK에 필요합니다.

```powershell
& $adbPath install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath install -r -t .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adbPath install -r -t .\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk
& $adbPath shell am force-stop kr.closeforyou.capturetest
& $adbPath shell am start -W -n kr.closeforyou.capturetest/kr.closeforyou.capturetest.SyntheticPopupActivity
& $adbPath shell pidof kr.closeforyou.capturetest
```

호스트 UI만 확인할 때는 호스트 APK 하나만 설치해도 됩니다. 본 앱이나 계측 runner 없이 별도 프로세스에서 시작하는 구조입니다. Android Studio에서도 실행 모듈을 `capture-test-host`로 선택해 Run할 수 있습니다.

합격 조건: `am start` 성공 후 프로세스가 유지되고 한국어 점검 공지 이미지와 닫기 버튼이 보이며, Intrinsics/ClassNotFoundException 강제 종료가 없어야 합니다. `Status: ok`만으로는 이후 강제 종료를 배제할 수 없습니다. 기본 공지는 합성 날짜·시간만 포함합니다. 재시험은 호스트만 force-stop 후 다시 시작합니다.

API 35를 기준 시험으로, 사용자가 오류를 보고한 API 37에서도 같은 절차를 반복합니다. compile/target SDK는 기존대로 35이고 이번 수정에서 37로 올리지 않았습니다. API 37 동작 성공을 주장하지 않습니다.

### DEX 패키징 검사 (기기 불필요)

Python 3.9 이상이 있다면 다음 명령을 실행합니다(`python` 대신 Windows의 `py`도 가능).

```powershell
python .\tools\verify_capture_test_apks.py
```

검사는 문자열 검색 대신 DEX의 실제 class_def 테이블을 읽습니다. 호스트 APK에 Intrinsics·Unit·KotlinVersion·CollectionsKt·DefaultConstructorMarker와 Activity/이미지 helper 정의가 있어야 통과합니다. 호스트의 타입 참조에 본 앱/계측/JUnit 클래스가 없고, 본 앱과 계측 APK에 합성 호스트가 없는지도 검사합니다. 기존의 문제가 있던 test APK를 호스트로 지정하면 Intrinsics 누락으로 실패하는 것을 확인했습니다.

이 검사는 독립 로딩을 위한 패키징 조건을 검증할 뿐 ART 실행 성공의 증명이 아닙니다. 계측 APK 자체에는 stdlib가 중복 패키징되지 않을 수 있으며 대상 본 앱의 런타임을 사용합니다.

### 독립 프로세스 및 계측 classloader 시험

수집 동의와 캡처 허락은 꺼둔 상태로 먼저 실행합니다.

```powershell
& $adbPath shell am instrument -w -e class kr.closeforyou.accessibility.capture.SyntheticHostLaunchTest -e syntheticHost true kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner
```

두 시험이 있습니다. 하나는 계측 classloader에서 `Class.forName(Intrinsics)`를 호출합니다. 다른 하나는 shell로 독립 호스트를 시작하고 호스트 창의 클릭 가능한 `닫기` 버튼을 기다립니다. 호스트를 계측 프로세스 내부에서 생성하는 ActivityScenario 방식을 사용하지 않습니다. `syntheticHost=true`가 없으면 외부 호스트 시작 시험은 skip됩니다. 명시적으로 켰는데 APK가 없거나 충돌하면 실패해야 합니다. 두 시험 모두 기기에서 실제로 실행한 결과는 아직 없습니다.

### 기존 실제 OCR·서비스 캡처 시험

번들 모델 시험은 그대로입니다.

```powershell
& $adbPath shell am instrument -w -e class kr.closeforyou.accessibility.capture.BundledKoreanOcrTest kr.closeforyou.test/androidx.test.runner.AndroidJUnitRunner
```

서비스 연결·실제 캡처 시험은 이제 **합성 호스트를 대상으로 하는 외부 계측 드라이버**에서 실행합니다. 본 앱 대상 am instrument가 서비스 프로세스를 재시작할 수 있어 기존 ServiceCaptureOptInTest 실행 명령을 폐기했습니다. APK 4개 설치, 연결만 검사하는 명령, 사용자 UI에서의 동의/허용 설정은 [SERVICE_CAPTURE_TEST.md](SERVICE_CAPTURE_TEST.md)를 따릅니다. 시험은 사용자 설정을 변경하지 않습니다. `kr.closeforyou.capturetest`만 분석/캡처 허용하며 실제 사용자 화면은 시험하지 않습니다.

앱 설정 화면은 OCR 본문이 아닌 최근 OCR 영역 개수만 표시합니다. 정확한 본문·좌표는 계측 assertion 또는 디버거로 확인합니다. 처음 모델 실행/느린 에뮬레이터에서 3초 OCR 제한·5초 토큰 수명으로 실패할 수 있으며, 런타임 로딩 복구와 OCR 통합 성공은 따로 기록합니다. 실제 메시지·은행·결제 화면이나 개인정보 이미지는 시험하지 않습니다.

## 검증 기록과 한계

최종 빌드·Lint·패키징 결과는 [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md)에 기록합니다. CI는 호스트/계측 APK 빌드와 DEX 검사를 수행합니다. 연결된 기기가 없어 계측은 본 앱 대상 5개와 외부 드라이버 1개로 나뉘며, 이 환경에서는 컴파일 확인만 했고 실제 실행은 0개입니다. API 35/37의 호스트 표시·프로세스 유지·실제 한국어 OCR·접근성 캡처·TalkBack은 Windows 재검증이 필요합니다. 자동 병합하지 않습니다.


2026-10-17 후속 사용자 보고: Windows API 37에서 합성 호스트 실행과 BundledKoreanOcrTest 3개 통과를 확인했다고 전달받았습니다. 서비스 연결 대기의 10초 시간 초과는 후속 [서비스 연결 시험 수정](SERVICE_CAPTURE_TEST.md)의 대상이며, 이 클라우드에서 기기 실행을 했다는 뜻은 아닙니다.
