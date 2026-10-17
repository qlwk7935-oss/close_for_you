# PR #6: 모서리 알파 불일치의 합성 Dialog 통제 실험

기존 `fix/dialog-capture-coordinates` / PR #6에서 계속 작업합니다. [불일치 진단](SYNTHETIC_CONTENT_DIAGNOSTICS.md)의 후속입니다. **프로덕션 캡처 정책을 바꾸지 않습니다.**

## 확인된 결과와 현재 한계

사용자의 API 37 결과에서 DEFAULT/ZERO 모두 LANDMARKS=ALIGNED, CONTENT_SCAN=COMPLETE, 실제 window API/검증된 화면/버퍼 해제를 확인했습니다. DEFAULT는 CORNER_ALPHA_PARTIAL, ZERO는 CORNER_ALPHA_TRANSPARENT와 CORNER_ALPHA_PARTIAL만 보고했고 일반 EDGE/INTERIOR 범주는 없었습니다. SOURCE_COLOR/COPY_COLOR는 모두 SRGB이며 elevation은 실제 YES/NO로 달랐습니다.

이는 네 기준점이 정수 이동 대응에 일치하고 등록 콘텐츠 모서리에서 알파가 달랐다는 근거입니다. 전체 콘텐츠 검증은 실패했고 실제 원인은 미확정입니다. elevation만으로는 두 실패의 원인을 분리하지 못했습니다. rounded background/outline clipping/알파 합성과 일관되지만 기기 내부 경로를 확정한 것은 아닙니다.

이후 사용자가 Windows Android Studio / Medium Phone API 37.0에서 신규 통제 실험을 실제 실행했습니다. 개발 환경에서 직접 재실행한 결과는 아닙니다. 아래 표는 사용자가 제공한 실측이며, API 35 결과와 설치 APK의 정확한 커밋 해시는 별도로 확인하지 않았습니다.

| 모드 | 사용자 API 37 실측 |
|---|---|
| ZERO_ELEVATION | MAPPING_NOT_VERIFIED |
| ROUND_CLIPPED | MAPPING_NOT_VERIFIED |
| ROUND_UNCLIPPED | PASSED |
| SQUARE_CLIPPED | PASSED |
| SQUARE_UNCLIPPED | PASSED |

SERVICE_CONNECTION_READY, OBSERVATIONS=COMPLETE, SQUARE_CONTROLS=PASSED 및 **OK (1 test)**를 보고했습니다. CLIPPED_RADIUS_CONTRAST/ROUND_CLIP_CONTRAST/THEME_TO_SQUARE_CONTRAST는 CORNER_ALPHA_FAILURE_REMOVED, SQUARE_CLIP_CONTRAST는 NO_CONTENT_FAILURE, PRODUCTION_POLICY는 UNCHANGED였습니다.

이 fixture의 **둥근 배경 × outline clipping** 조합이 모서리 알파 불일치에 관여한다는 통제 근거입니다. 기본/둥근 clipping 모드의 실패는 그대로이며, Android 내부 합성 경로의 확정이나 일반 Dialog 버퍼 원점의 증명은 아닙니다. 기존 elevation 단독 비교의 INCONCLUSIVE도 유지합니다. 합성 렌더링과 더 큰 버퍼의 문제 분리, 공개 API 후속 방안 및 병합 가능 범위는 [PR #6 정리](PR6_REVIEW_SCOPE.md)에 기록했습니다. 아래 비교표는 이 실측을 해석하는 규칙이며 다른 환경의 결과는 별도 관측이 필요합니다.

## 원래 시험 보존과 독립적인 성공 범위

`ServiceSyntheticPatternOptInTest`의 기존 DEFAULT_ELEVATION → ZERO_ELEVATION, 두 모드 모두 검증 요구, 실패 시 최종 JUnit 실패와 INCONCLUSIVE 비교는 유지합니다. 실 API 관측 함수만 `SyntheticPatternObservation`으로 옮겨 공유합니다. 원래 합성 한국어 공지의 일반 실행도 유지합니다.

새 `ServiceSyntheticRenderControlsOptInTest`는 별도 **사각형 통제 실험**입니다. 다음 다섯 개의 새 Activity/Dialog를 순서대로 관측합니다.

| 모드 | 배경 | outline clipping | 역할 |
|---|---|---|---|
| ZERO_ELEVATION | 기존 Android 테마 그대로 | 기존 설정 그대로 | 기존 실패를 같은 실험에서 별도로 기록하는 참조 |
| ROUND_CLIPPED | 명시적 둥근 사각형 | 켜짐 | 라운드·clip 조합 비교 |
| ROUND_UNCLIPPED | 위와 같은 둥근 사각형 | 꺼짐 | 같은 배경에서 clip 한 요인 비교 |
| SQUARE_CLIPPED | 명시적 불투명 사각형 | 켜짐 | 같은 clip에서 corner radius 한 요인 비교 |
| SQUARE_UNCLIPPED | 위와 같은 불투명 사각형 | 꺼짐 | 사각형 배경에서 clip 한 요인 비교 |

통제 모드는 모두 **Android 자체 Dialog**, 실제 `takeScreenshotOfWindow` 콜백, 새 window/surface를 사용합니다. public Window.setBackgroundDrawable와 Window.setClipToOutline, ViewOutlineProvider.BACKGROUND만 사용합니다. 숨겨진 surface 속성이나 reflection은 사용하지 않습니다.

네 통제 배경은 동일한 GradientDrawable RECTANGLE의 흰색 채움/alpha 255입니다. square는 cornerRadius=0으로 전체 사각형이 불투명합니다. round는 고정 **8 physical pixel** radius만 다릅니다. round의 채움색은 불투명해도 둥근 shape 밖의 모서리는 불투명 사각형이 아닙니다. 이 반경은 기존 색상 기준점에 영향을 피하기 위한 합성 통제값이며 실제 Dialog 테마 반경의 측정/복원값이 아닙니다.

모든 통제는 첫 show 전에 elevation/translationZ=0으로 설정합니다. 기존과 같은 window 크기, title, 패턴의 네 색상/위치 규칙/Paint/전체 콘텐츠 검증을 사용합니다. 부모의 clipChildren/clipToPadding, foreground, 하드웨어 가속은 바꾸지 않습니다. 다른 부모가 outline clipping을 설정했다면 이를 강제로 해제하지 않고 통제 구성 미검증으로 보고합니다. 반환 이미지를 수정하거나 크롭/resize/알파 채움하지 않습니다.

새 클래스의 `OK (1 test)`는 **두 SQUARE 모드의 엄격한 성공과 실험 관측 조건 충족만** 뜻합니다. ZERO/ROUND의 CONTENT_NOT_SYNTHETIC은 MODE_RESULT=MAPPING_NOT_VERIFIED로 그대로 남습니다. 새 성공으로 이전 실패를 성공 처리하지 않습니다. 두 클래스를 함께 실행하면 기존 실패는 여전히 suite 실패입니다. 이 구분을 위해 summary에 BASELINE_RESULT/BASELINE_MAPPING, 각 ROUND/SQUARE 결과, SQUARE_CONTROLS를 따로 출력합니다.

## 통제 조건 확인과 원인 비교

ARM 시점에 실제 자체 decor 배경 identity/shape/불투명 단색/반경, clip flag, outline provider, z=0, 콘텐츠·다른 부모의 outline 상태를 읽어 확인합니다. CONTROL_CONFIG=VERIFIED/UNVERIFIED/NOT_REQUESTED 및 CONTROL_ISSUE 고정 코드만 반환합니다. BACKGROUND_IDENTITY/SHAPE/OPACITY, CORNER_RADIUS, DECOR_OUTLINE, OUTLINE_PROVIDER, ELEVATION, CONTENT_OUTLINE, ANCESTOR_OUTLINE/UNCONFIRMED로 구성 실패를 구분합니다. 실제 측정값은 출력하지 않습니다.

또한 네 통제의 자체 decor/콘텐츠 크기와 상대 위치가 같은지 비교하여 CONTROL_LAYOUT=SAME/DIFFERENT/UNKNOWN만 출력합니다. 원점은 비교 대상에 넣지 않습니다. 내부의 synthetic signature는 실제 수치를 공개하지 않는 임시 객체이며 toString도 redacted입니다. 실험 종료/취소 finally에서 참조를 해제합니다. baseline 테마는 inset/layout이 다를 수 있어 이 동등성 비교에 넣지 않습니다.

모든 관측은 최신 토큰, 실제 window 콜백, 검증된 화면, ARM, native 버퍼 해제/세션 정리, 기준점 정렬 및 전체 스캔 완료를 요구합니다. 네 통제 구성과 상대 layout이 검증되지 않으면 비교는 INCONCLUSIVE이고 사각형 실험도 실패합니다. timeout/권한/설정/메모리/가림 실패를 rendering 차이로 해석하지 않습니다. 실패해도 나머지 모드를 관측하며 명시적 전체 취소는 즉시 전파합니다.

| summary 비교 | 비교한 조건 | 해석 가능한 조건부 결과 |
|---|---|---|
| CLIPPED_RADIUS_CONTRAST | ROUND_CLIPPED → SQUARE_CLIPPED | round의 모서리 알파 실패가 square의 전체 검증 성공으로 바뀌면 CORNER_ALPHA_FAILURE_REMOVED |
| ROUND_CLIP_CONTRAST | ROUND_CLIPPED → ROUND_UNCLIPPED | 같은 round 배경에서 clip 해제 후 전체 검증 성공이면 CORNER_ALPHA_FAILURE_REMOVED |
| SQUARE_CLIP_CONTRAST | SQUARE_CLIPPED → SQUARE_UNCLIPPED | 양쪽 전체 검증 성공이면 NO_CONTENT_FAILURE. 모든 Dialog에서 clip 영향이 없다는 뜻은 아님 |
| THEME_TO_SQUARE_CONTRAST | 기존 ZERO → SQUARE_CLIPPED | 테마의 모서리 알파 실패와 통제 사각형 성공의 차이. 기본 배경/반경/inset/layout 여러 조건이 달라 특정 한 요인의 단독 증명이 아님 |

두 첫 비교가 함께 CORNER_ALPHA_FAILURE_REMOVED이고 사각형 두 모드가 통과하면 **이 합성 fixture의 둥근 배경×outline clipping 조합**이 모서리 알파 실패를 만드는 근거가 강화됩니다. 기본 테마와 통제 배경의 세부 조건까지 같다는 보장은 없으므로 API 37 모든 Dialog의 실제 원인이나 범용 변환으로 일반화하지 않습니다.

배경이 사각형이고 clipping을 꺼도 실패한다면 MISMATCH/RENDER/SOURCE_COLOR/COPY_COLOR/CONTROL_ISSUE/ANCHOR_DECOR 등 고정 코드로 새 근거를 확인합니다. 자체 View의 이 두 조건만으로 설명되지 않을 수 있으며 system surface clipping/합성 등의 가능성을 조사해야 합니다. 관측되지 않은 원인을 확정하거나 허용 오차를 확대하지 않습니다. 비교 대상이 불완전하거나 모서리 알파 외 불일치라면 INCONCLUSIVE를 유지합니다.

## 좌표 대응과 전체 렌더링 검증 분리

새 ANCHOR_DECOR는 **기준점들이 같은 이동량에 정렬되고**, 그 이동량을 적용한 자체 decor가 실제 버퍼에 포함된 뒤의 EXACT/INSET_ALL/INSET_SOME 관계입니다. 실제 원점·offset·여백·치수는 반환하지 않습니다.

전체 콘텐츠가 CONTENT_NOT_SYNTHETIC이면 ANCHOR_DECOR가 알려져도 최종 DECOR_BUFFER는 UNKNOWN이며 MODE_RESULT는 계속 실패입니다. 기존 알파 255/색 채널 차이 1 이하, 모든 콘텐츠 픽셀 검사, 500ms 예산을 유지합니다. ANCHOR_DECOR는 임시 진단이며 production crop이나 CaptureBufferPolicy에 전달하지 않습니다. 사각형의 실제 캡처가 성공해도 기존 Dialog의 큰 버퍼 거부를 완화하지 않습니다.

## Windows API 37 재검증

API 35도 같은 명령으로 따로 검증할 수 있습니다. 개인정보 없는 전용 에뮬레이터에서 실제 메시지·계정·은행·결제 앱을 열지 않습니다. 네 APK를 모두 업데이트해야 새 provider/호스트/driver 코드가 일치합니다.

```powershell
git switch fix/dialog-capture-coordinates
git pull --ff-only origin fix/dialog-capture-coordinates
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adbPath devices
$serial = "emulator-5554" # 실제 대상 serial 선택
& $adbPath -s $serial shell getprop ro.build.version.sdk
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk
```

설치 후 본 앱 UI에서 실제 접근성 서비스 연결, 수집/별도 캡처 동의, 분석·캡처 허용 앱 `kr.closeforyou.capturetest` 하나, **허용 앱 분석 시작**을 직접 확인합니다. 설정/동의/서비스 상태를 시험이 대신 바꾸지 않습니다.

```powershell
# 보존된 기존 시험: 현재 기본 모서리 불일치가 재현되면 계속 FAIL이어야 함
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceSyntheticPatternOptInTest -e captureOcrService true -e syntheticPattern true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner

# 기존 분당 6회 제한 유지. 앞 시험과 별개로 시간 기반 회복을 기다림
Start-Sleep -Seconds 60

# 새 독립 통제: ZERO 참조 + 네 통제, 다섯 번의 실제 window 캡처 관측
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceSyntheticRenderControlsOptInTest -e captureOcrService true -e syntheticPattern true -e renderControls true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

기존 모드당 20초 결과 대기와 모드 사이 3초 간격은 그대로입니다. 새 실험은 정상적으로 다섯 MODE 결과를 수집합니다. RATE_LIMITED/BUSY면 버퍼를 억지로 얻지 않고 해당 모드 실패/INCONCLUSIVE로 남깁니다. 서비스 재시작/예산 초기화/timeout 확대/자동 재시도로 우회하지 않습니다. 이전 실행/다른 화면 이벤트가 예산을 사용했다면 기존 회복 정책에 따라 기다린 뒤 새 시험을 별도로 실행합니다.

기대 **구조**는 SERVICE_CONNECTION_READY → ZERO/ROUND_CLIPPED/ROUND_UNCLIPPED/SQUARE_CLIPPED/SQUARE_UNCLIPPED 관측 → SYNTHETIC_RENDER_CONTROL_COMPARISON입니다. 두 square의 MODE_RESULT=PASSED, CONTROL_CONFIG=VERIFIED, 실제 API=WINDOW/FRAME=VERIFIED/BUFFER_RELEASED=true, 전체 스캔 완료와 MISMATCH=NONE 등 필수 조건이 충족되어야 새 클래스만 OK (1 test)를 기대합니다. baseline 실패는 BASELINE_MAPPING=UNVERIFIED로 그대로 보고해야 합니다. 이 OK는 전체 한국어 OCR 성공이나 기존 시험 성공이 아닙니다. opt-in 누락의 assumption skip도 성공이 아닙니다.

종료 후 본 앱 분석을 일시정지하거나 동의를 철회합니다. 제출할 결과는 다섯 MODE와 비교의 고정 코드입니다. 원본 화면 이미지·실제 좌표·RGB·화면 원문·raw node/Logcat을 저장하거나 제출하지 않습니다.

## 안전·검증 범위

app/src/main과 app/src/release는 이번 수정에서 변경하지 않습니다. F0-A/F0-B, 개인정보/시스템·보안 창·가림 검사, 동의·허용 앱·토큰, 실제 버퍼/ROI 메모리 제한, budget, timeout, native/Bitmap 해제, B·C·D 인터페이스를 유지합니다. release에 합성 검사/통제 호스트 구현을 포함하지 않는 DEX 검사를 유지·보강합니다.

JVM은 anchor/최종 결과 분리, 사각형 성공과 baseline 실패 분리, 통제 구성/상대 layout/scan/해제 미검증의 보수적 판정, 모드 누락·예외·취소, 원래 두 모드 보존을 합성 데이터로 검증합니다. 개발 환경에서는 실제 Dialog/캡처 계측의 컴파일과 패키징을 확인했고 연결 기기가 없어 직접 실행하지 않았습니다. **사용자 API 37 통제 성공은 위 표의 범위로 확인했으며, API 35 통제·일반 앱 좌표 대응·한국어 전체 캡처/OCR은 여전히 미검증/미해결**입니다. 최종 빌드·테스트·Lint 결과는 [개발 상태](DEVELOPMENT_STATUS.md)에 기록합니다.

공개 API 근거: [Window.setBackgroundDrawable](https://developer.android.com/reference/android/view/Window#setBackgroundDrawable(android.graphics.drawable.Drawable)), [Window.setClipToOutline](https://developer.android.com/reference/android/view/Window#setClipToOutline(boolean)), [GradientDrawable.setCornerRadius](https://developer.android.com/reference/android/graphics/drawable/GradientDrawable#setCornerRadius(float)), [ViewOutlineProvider.BACKGROUND](https://developer.android.com/reference/android/view/ViewOutlineProvider#BACKGROUND). SDK 35의 공개 API와 AOSP PhoneWindow/DecorView 경로를 기준으로 구현하며 API 37 구현이 완전히 같다고 단정하지 않습니다.
