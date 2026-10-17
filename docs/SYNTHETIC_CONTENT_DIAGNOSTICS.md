# PR #6: 합성 콘텐츠 불일치 진단과 두 모드 관측

최신 후속: [사각형·라운드 배경과 outline clipping 통제 실험](SYNTHETIC_RENDER_CONTROLS.md). 기존 DEFAULT/ZERO의 실패는 보존하고 통제 성공과 분리합니다.

기존 `fix/dialog-capture-coordinates` / PR #6에서 계속 작업합니다. [버퍼 좌표 대응 계측](SYNTHETIC_BUFFER_MAPPING.md)의 후속이며 프로덕션 캡처 정책 변경이나 main 병합이 아닙니다.

## 실제 결과로 확인한 범위

사용자가 Windows Android Studio API 37에서 실행한 기존 계측은 SERVICE_CONNECTION_READY, DEFAULT_ELEVATION, ARM=PATTERN_ARMED, PATTERN=CONTENT_NOT_SYNTHETIC, ELEVATED=YES, BUFFER_RELEASED=true, API=WINDOW, FRAME=VERIFIED였습니다. 기존 코드상 기준점 색/크기/유일성, 동일 정수 이동량, decor의 버퍼 포함 검사 이후 **전체 콘텐츠 픽셀 비교에서 적어도 하나가 불일치**했습니다. 좌표 대응 전체나 OCR 성공이 입증된 것은 아닙니다.

기존 검사는 첫 불일치 픽셀에서 반환했으므로 모서리인지 내부인지, 알파인지 색상인지 알 수 없었습니다. DECOR_BUFFER/OUTER=UNKNOWN은 그 이후 단계를 실행하지 않은 결과입니다. 기존 테스트의 모드 내부 assertion 때문에 ZERO_ELEVATION은 실행되지 않아 elevation 비교 근거가 없었습니다. 이번 수정 계측은 이 개발 환경의 실제 기기에서 실행하지 않았습니다.

## 원인 조사: 확인한 코드와 가설 구분

| 후보 | 공식 API/AOSP에서 확인한 경로 | 이번 기기 결과의 한계 |
|---|---|---|
| rounded corner / clipping | Android 15 dialog_background_material은 inset과 dialogCornerRadius를 가진 shape. Material 테마에 windowClipToOutline 설정이 있으며 PhoneWindow가 decor.setClipToOutline에 전달 | 자체 콘텐츠 사각형의 모든 픽셀이 항상 불투명 흰색이라는 보장은 없음. 실제 API 37 테마/클리핑 위치는 아직 미확정 |
| decor/ancestor foreground | View의 onDraw 이후 onDrawForeground가 실행되고 DecorView도 foreground 경계를 관리 | 합성 View가 흰색을 그린 뒤 다른 그리기 단계가 덮을 수 있음. foreground 존재만으로 실제 가림을 증명할 수 없음 |
| 하드웨어 렌더링 | outline clipping은 RenderNode 경로와 관련됨. 합성 Paint의 antiAlias=false는 해당 색상 사각형의 Paint 설정 | 부모 outline/레이어 경계/합성을 끄지 않으므로 경계의 알파 변화나 혼합을 배제하지 못함 |
| 색상 변환·읽기 변환 | Bitmap.getPixels는 sRGB의 non-premultiplied ARGB ColorInt를 반환 | 반환 값은 원본 HardwareBuffer의 raw 채널이 아님. 기준점 통과는 일부 색의 일치이며 흰색·알파·모든 픽셀의 일치를 보장하지 않음 |
| surface inset / shadow | 앞선 AOSP 조사에서 z와 surface inset/버퍼 크기 증가 경로 확인 | 큰 버퍼의 이유와 **콘텐츠 내부 불일치의 이유는 별개**일 수 있음. 버퍼가 크다는 이유만으로 그림자 원인 확정 불가 |

Rounded corner/ancestor clipping은 유력한 조사 후보입니다. 그러나 기존 결과에는 불일치 분포가 없어 **원인을 확정하지 않습니다**. elevation을 0으로 바꾸는 것과 outline clipping을 해제하는 것은 다른 설정입니다. 두 모드에서 같은 콘텐츠 실패가 발생해도 clipping 가설을 자동 배제할 수 없습니다. clipping·배경·Paint·하드웨어 가속·Dialog 스타일은 이번 수정에서 바꾸지 않습니다.

## 새 진단: 값이나 좌표 대신 고정 범주

PatternMapping은 첫 불일치에서 중단하는 대신 기존 500ms 검사 예산 안에서 콘텐츠 전체를 검사하여 최대 12개 고정 범주의 존재 여부를 bit mask로 모읍니다. 픽셀별 객체/목록/개수/최초 위치는 만들거나 반환하지 않습니다. 결과 코드는 정해진 순서로 출력하며 발견 순서도 내보내지 않습니다.

| 필드 | 고정 코드와 의미 |
|---|---|
| LANDMARKS | NOT_VERIFIED / ALIGNED. ALIGNED는 네 기준점의 일치만 뜻하며 전체 매핑의 성공 아님 |
| CONTENT_SCAN | NOT_RUN / COMPLETE / INTERRUPTED. COMPLETE도 불일치가 있으면 실패 |
| MISMATCH | 아래 위치 × 알파/색상 종류의 조합. 예: CORNER_ALPHA_PARTIAL, INTERIOR_OPAQUE_OTHER. 없으면 NONE |
| 위치 | CORNER / EDGE / INTERIOR: **등록된 합성 콘텐츠 View** 사각형 기준. Dialog 창의 실제 곡률/반경/클립 영역을 판정하는 코드가 아님 |
| 알파/색상 종류 | ALPHA_TRANSPARENT / ALPHA_PARTIAL / OPAQUE_NEAR_WHITE / OPAQUE_OTHER |
| SOURCE_COLOR / COPY_COLOR | 원본 ScreenshotResult ColorSpace / 임시 CPU Bitmap ColorSpace의 SRGB / OTHER / UNKNOWN. 실제 색 공간 이름·채널 값은 출력하지 않음 |
| RENDER | ARM 시점의 자체 View 메타데이터: HARDWARE/SOFTWARE, CONTENT_OUTLINE, ANCESTOR_OUTLINE, DECOR_OUTLINE, ANCESTOR_CLIP_CHILDREN, ANCESTOR_CLIP_PADDING, CONTENT_FOREGROUND, ANCESTOR_FOREGROUND |
| MODE_RESULT / CLEANUP | 모드별 필수 검증 결과 / 세션·소유 Activity 정리 결과. BUFFER_RELEASED는 실제 HardwareBuffer.close 이후에만 true |

BLOCK/EVIDENCE/DETAIL은 같은 실제 callback의 기존 진단 snapshot이며 후속 최종 OCR 발행 상태가 아닙니다.

진단용 위치 띠는 min(32, 콘텐츠 너비/4, 높이/4) 픽셀입니다. CORNER는 두 축 띠의 교차, EDGE는 한 축 띠, 나머지는 INTERIOR입니다. 어느 모서리인지, 실제 위치·치수·여백·RGB·픽셀 수는 반환하지 않습니다. 이 띠는 **제외하거나 크롭할 영역이 아닙니다**.

알파 0은 TRANSPARENT, 1~254는 PARTIAL입니다. 불투명 불일치 중 세 채널이 모두 240 이상이고 최대/최소 차이가 4 이하이면 진단용 NEAR_WHITE, 나머지는 OTHER입니다. 이 숫자는 합성 분류의 고정 정의이며 실제 측정값은 출력하지 않습니다. **허용 기준은 종전 그대로: 알파 255, 기대 색과 채널별 차이 1 이하**입니다. NEAR_WHITE라는 이름도 허용이나 오차 확대의 의미가 아닙니다.

CORNER_ALPHA_*는 clipping/합성과 일관될 수 있으나 원인을 확정하지 않습니다. INTERIOR_OPAQUE_OTHER는 중앙 내용의 덮임/다른 프레임 등을 추가 조사할 단서입니다. OPAQUE_NEAR_WHITE는 색상/혼합 차이의 단서이며 변환 원인의 증명은 아닙니다. RENDER의 clipChildren/clipPadding은 일반적인 기본값일 수 있고 존재 코드만으로 실제 잘림을 단정하지 않습니다. SOURCE_COLOR와 COPY_COLOR가 SRGB여도 전체 렌더링의 무손실 증명은 아닙니다.

전체 스캔 완료 후 불일치가 하나라도 있으면 CONTENT_NOT_SYNTHETIC, LANDMARKS=ALIGNED, CONTENT_SCAN=COMPLETE이며 DECOR_BUFFER/OUTER는 UNKNOWN을 유지합니다. 시간 예산/취소로 중단하면 INSPECTION_INTERRUPTED와 CONTENT_SCAN=INTERRUPTED로 구분하고 부분 범주만 남깁니다. 토큰/동의가 유효하지 않으면 기존 STALE_OR_NOT_ALLOWED 처리로 진단을 폐기합니다. 미완료·모서리 불일치를 성공으로 승격하지 않습니다.

## 두 모드를 끝까지 관측한 뒤 판정

- DEFAULT와 ZERO는 각각 별도 명시적 debug 세션·새 Activity/Dialog입니다. 모드 내 필수 검증을 고정 결과 코드로 수집하고 END/Activity.finish를 각각 finally에서 시도합니다. 한 정리 실패가 다른 정리 시도를 막지 않습니다.
- DEFAULT의 콘텐츠 검증 실패, 모드 timeout, 예상하지 못한 일반 예외/AssertionError도 고정 실패 코드로 남기고 기존 간격 3초 후 ZERO를 시도합니다. 실제 연결/공통 opt-in의 선행 검사가 실패하면 시험을 시작하지 않습니다. 명시적 전체 취소는 즉시 전파하며 다음 창을 열지 않습니다.
- 두 SYNTHETIC_PATTERN_OBSERVED에 MODE, MODE_RESULT, 진단을 각각 출력합니다. 그 다음 SYNTHETIC_PATTERN_COMPARISON에 DEFAULT_RESULT, ZERO_RESULT, ELEVATION_EFFECT를 출력한 후 **최종 assertion을 한 번** 실행합니다. 한 모드라도 필수 검증/정리에 실패하면 JUnit은 계속 실패합니다.
- 한 모드라도 미검증/실패이거나 elevation 대비가 없으면 비교는 INCONCLUSIVE입니다. UNKNOWN=UNKNOWN을 NO_RELATION_CHANGE로 보고하지 않습니다. 두 모드 모두 검증되고 기본 z>0/zero z=0이 관측된 경우에만 기존 알려진 geometry 범주를 비교합니다.
- NO_RELATION_CHANGE는 같은 **범주**라는 뜻이며 실제 버퍼 크기나 inset 수치가 같다는 뜻이 아닙니다. CONSISTENT_WITH_ELEVATION_INSETS도 가설과의 일관성일 뿐 범용 변환/원인 확정이 아닙니다.

프로덕션 캡처의 큰 버퍼 거부, F0-A/F0-B, 동의/허용 앱, 민감/보안/시스템 창·가림, 최신 토큰, 1초 캡처/3초 OCR timeout, 캡처 예산, 메모리 제한 및 해제 코드는 변경하지 않습니다. 전체 픽셀 검증 제거, tolerance 확대, clipping 해제, 임의 크롭/resize/display fallback/가짜 OCR은 없습니다. B·C·D 공통 계약도 유지합니다.

## Windows 재시험: API 35와 API 37 각각

개인정보가 없는 전용 에뮬레이터에서만 실행합니다. 실제 메시지·은행·계정·결제 화면을 열거나 raw 이미지/노드/Logcat을 저장하지 않습니다.

```powershell
git switch fix/dialog-capture-coordinates
git pull --ff-only origin fix/dialog-capture-coordinates
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :capture-test-host:testDebugUnitTest :app:lintDebug :capture-test-host:lintDebug :app:assembleRelease
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adbPath devices
$serial = "emulator-5554" # 실제 대상 serial로 변경
& $adbPath -s $serial shell getprop ro.build.version.sdk
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adbPath -s $serial install -r -t .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\debug\capture-test-host-debug.apk
& $adbPath -s $serial install -r -t .\capture-test-host\build\outputs\apk\androidTest\debug\capture-test-host-debug-androidTest.apk
```

설치 후 본 앱 UI에서 접근성 서비스 연결, 수집/별도 캡처 동의, 분석·캡처 허용 앱 `kr.closeforyou.capturetest` 하나, **허용 앱 분석 시작**을 직접 확인합니다. capture budget 제한이 남아 있으면 기존 시간 정책에 따라 회복을 기다립니다. 서비스 재시작/설정 덮어쓰기로 예산이나 보호를 우회하지 않습니다.

```powershell
& $adbPath -s $serial shell am instrument -w -e class kr.closeforyou.servicetest.ServiceSyntheticPatternOptInTest -e captureOcrService true -e syntheticPattern true kr.closeforyou.capturetest.test/androidx.test.runner.AndroidJUnitRunner
```

기대 **출력 구조**는 SERVICE_CONNECTION_READY → DEFAULT 관측 → ZERO 관측 → 비교 → 최종 JUnit 판정입니다. 기존 DEFAULT CONTENT_NOT_SYNTHETIC이 재현되면 ZERO 관측까지 출력한 뒤 DEFAULT_RESULT=MAPPING_NOT_VERIFIED, ELEVATION_EFFECT=INCONCLUSIVE 및 FAILURES가 정상적인 엄격 판정입니다. 실제 성공으로 해석하지 않습니다. 두 모드가 모두 필수 검증을 통과한 경우에만 OK (1 test)를 기대합니다. opt-in 누락 시 assumption skip이므로 OK 문구만으로 성공 판정하지 않습니다. connectionOnly=true는 이 시험을 우회하지 못합니다.

계측 후 본 앱 분석을 일시정지하거나 동의를 철회합니다. 제출할 결과는 두 MODE의 고정 코드와 비교 코드입니다. 기존 실제 한국어 OCR 성공 여부는 [별도 전체 OCR 시험](SYNTHETIC_BUFFER_MAPPING.md)으로만 판단합니다.

## 검증 범위와 근거

JVM은 12종 불일치 범주, 첫/마지막 불일치 집계, 기존 tolerance 경계, 알파 불일치, 중단된 스캔과 모드별 실패/예외/취소·UNKNOWN 비교/최종 실패 보존을 합성 데이터로 검사합니다. 수정된 기기 계측은 컴파일/패키징만 확인할 수 있고 이 환경에서 실행하지 않습니다. 실제 원인·신규 계측 성공·한국어 OCR 성공은 미확정입니다. 최종 Gradle/JVM/Lint 결과는 [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md)에 기록합니다.

- [공식 그림자와 clipping 설명](https://developer.android.com/develop/ui/views/theming/shadows-clipping), [Bitmap.getPixels](https://developer.android.com/reference/android/graphics/Bitmap#getPixels(int[],int,int,int,int,int,int))
- [AOSP Android 15 Dialog 배경](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/res/res/drawable/dialog_background_material.xml), [Material 테마](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/res/res/values/themes_material.xml)
- [PhoneWindow](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/com/android/internal/policy/PhoneWindow.java), [DecorView](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/com/android/internal/policy/DecorView.java), [View의 outline/foreground](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/View.java)
- [Bitmap의 sRGB readback 계약](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/graphics/java/android/graphics/Bitmap.java)

안정된 Android 15 소스와 공개 API 경로를 조사한 것이며 사용자 API 37 시스템 이미지의 구현이 완전히 같다는 주장이나 CTS/기기 실행 결과가 아닙니다.
