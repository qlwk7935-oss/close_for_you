# PR #6 정리: API 37 합성 렌더링 실측과 미해결 캡처 범위

2026-10-17 기록. 브랜치 `fix/dialog-capture-coordinates`, 기존 PR #6의 검토 기준입니다. **이번 정리 커밋은 문서만 변경합니다.** main 병합·새 PR 생성·프로덕션 캡처 정책 변경은 수행하지 않습니다.

## 실측의 출처와 성공 범위

사용자가 Windows Android Studio / Medium Phone API 37.0 에뮬레이터에서 `ServiceSyntheticRenderControlsOptInTest`를 실행해 아래 결과를 제공했습니다. `e023659`에 추가된 시험의 결과이며, 설치 APK의 정확한 커밋 해시는 별도로 확인하지 않았습니다. 개발 환경에서 재실행한 결과가 아닙니다. API 35의 같은 실험 결과는 아직 없습니다.

| 모드 | 사용자 보고 결과 | 의미 |
|---|---|---|
| ZERO_ELEVATION | MAPPING_NOT_VERIFIED | 기존 테마 Dialog의 전체 콘텐츠 검증 실패 유지 |
| ROUND_CLIPPED | MAPPING_NOT_VERIFIED | 둥근 배경 + outline clipping에서 실패 유지 |
| ROUND_UNCLIPPED | PASSED | 같은 둥근 통제 배경에서 clipping 해제 시 엄격한 전체 검사 성공 |
| SQUARE_CLIPPED | PASSED | 불투명 사각형 통제 배경 + clipping에서 엄격한 전체 검사 성공 |
| SQUARE_UNCLIPPED | PASSED | 같은 사각형 통제 배경에서 clipping 해제 시에도 성공 |

보고된 연결·종합 코드는 다음과 같습니다. 실제 이미지·좌표·RGB·OCR 원문은 기록하지 않습니다.

```text
SERVICE_CONNECTION_READY
OBSERVATIONS=COMPLETE
SQUARE_CONTROLS=PASSED
CLIPPED_RADIUS_CONTRAST=CORNER_ALPHA_FAILURE_REMOVED
ROUND_CLIP_CONTRAST=CORNER_ALPHA_FAILURE_REMOVED
SQUARE_CLIP_CONTRAST=NO_CONTENT_FAILURE
THEME_TO_SQUARE_CONTRAST=CORNER_ALPHA_FAILURE_REMOVED
PRODUCTION_POLICY=UNCHANGED
OK (1 test)
```

소스의 `RenderControlMatrix.complete`와 각 모드의 `passed` 판정에 따르면 이 종합 결과는 실제 window 콜백·검증된 화면·ARM·버퍼 해제·세션 정리·기준점 정렬·전체 스캔, 네 통제의 구성 및 상대 layout 동등성 조건을 요구합니다. 성공 모드는 알파 255/색 채널 차이 1 이하의 기존 허용 조건으로 모든 콘텐츠 픽셀을 검사합니다. 실패를 무시하거나 색 허용 오차를 늘린 결과가 아닙니다.

**이 합성 fixture에서는 둥근 배경과 outline clipping의 조합이 모서리 알파 불일치에 관여한다는 통제 실험 근거를 얻었습니다.** ROUND의 clip 한 요인 비교 및 CLIPPED의 radius 한 요인 비교가 모두 실패 제거를 관측했습니다. 기본 테마 → 사각형 비교는 배경·inset/layout 등 여러 조건도 달라 특정 한 요인의 단독 증명이 아닙니다. API 37 내부의 정확한 합성/Surface 경로나 모든 제조사·일반 Dialog의 원인을 확정한 것은 아닙니다. elevation 단독 영향도 기존 INCONCLUSIVE에서 변경하지 않습니다.

새 클래스의 OK는 관측 조건과 사각형 두 통제의 성공 범위입니다. ZERO와 ROUND_CLIPPED는 여전히 실패입니다. 이전 `ServiceSyntheticPatternOptInTest`의 DEFAULT/ZERO 실패 기록도 보존하며 이번 결과로 재실행·성공 처리하지 않습니다. 이 시험은 OCR_MATCH, OCR 정확도, F0-B 통과 또는 일반 앱 OCR 성공을 검사하지 않습니다.

## 두 문제의 분리

| 문제 | 확인된 사실 | 남은 한계와 현재 처리 |
|---|---|---|
| 합성 콘텐츠 모서리 알파 | 기존 DEFAULT/ZERO의 기준점은 정렬됐지만 전체 검사에서 모서리 알파만 불일치. 이번 round/square × clip 통제에서 실패 제거 관측 | 해당 합성 렌더링 조건에 대한 근거. 기본 Dialog의 전체 검사 실패는 계속 보존 |
| 일반 Dialog의 더 큰 HardwareBuffer | 앞선 실제 버퍼 시험에서 양 축 LARGER, DISPLAY_SIZED=NO, SWAPPED=NO, UNPROVEN_WINDOW. 공개 결과 metadata에는 buffer-to-screen 원점/변환이 없음 | 그림자/surface inset/compat scaling/회전 등의 실제 원인·좌표 대응 미확정. 크기 불일치는 계속 REGION_UNCERTAIN |

네 기준점과 자체 decor/콘텐츠 경계를 아는 합성 호스트에서는 **그 실행의** 정수 이동 대응을 검사할 수 있습니다. 일반 타사 앱에는 그 기준점과 소유 View 정보가 없습니다. 합성 통제에서 전체 검사까지 통과했어도 얻은 이동량·여백·스타일을 타사 앱에 적용할 수 없습니다. 모서리 실패 제거는 더 큰 버퍼 자체의 원인이나 범용 원점을 알아낸 것이 아닙니다.

## 현재 프로덕션 동작

`AndroidScreenshotManager.onSuccess`는 실제 반환 버퍼의 width/height/layers/format 및 메모리 한도를 검사합니다. debug 합성 검사는 같은 콜백을 관측하지만 그 결과로 프레임을 승인하지 않습니다. `CaptureBufferPolicy.assess(...).failure`가 있으면 정상 ROI 복사·OCR 이전에 실패하며, finally에서 버퍼를 해제합니다. release의 합성 검사 hook은 no-op입니다.

기존 exact-size 경로는 그대로입니다. **EXACT_SIZE라는 이름은 크기가 같다는 코드이지 임의의 창에서 좌표 변환이 완벽히 입증됐다는 뜻은 아닙니다.** 더 큰/더 작은/뒤바뀐/혼합 불일치는 계속 거부합니다. API 34+ window 실패를 전체 디스플레이 캡처로 대체하지 않습니다.

F0-A/F0-B·보안/시스템/입력/다른 앱 창 보호·가림 검사·별도 캡처 동의·앱별 허용·최신 토큰·취소·예산·실제 버퍼 메모리 검사·Bitmap/HardwareBuffer 해제를 유지합니다. 불확실한 OCR은 전달하지 않으며 실패 시 최신 F0-A로 안전성을 재확인한 노드 근거만 유지할 수 있습니다. F0-A 통과는 이미지에 개인정보가 없다는 보장이 아닙니다. B의 분류/행동 허가는 별도 책임이며 통제 실험 성공으로 자동 실행을 허가하지 않습니다.

## 공개 API로 검토할 수 있는 후속 방법

아래는 **후속 설계 후보와 필요한 증거**이며 이번 PR에서 구현하거나 지원을 약속한 기능이 아닙니다.

| 방법 | 공개 API로 확인할 수 있는 범위 | 아직 입증할 수 없거나 추가 검증할 부분 |
|---|---|---|
| 안전한 접근성 노드 우선 | 노드 텍스트/화면 경계를 불변 모델로 추출. 현재 안전한 노드 근거 경로 유지 | 노드가 없는 이미지 글자는 대체하지 못함. 캡처 실패를 OCR 성공으로 표시하지 않음 |
| 자체/협력 테스트 앱의 소유 View 경계와 패턴 | 소유 View의 위치·크기, 배경/outline 설정을 공개 API로 확인하고 실제 window 버퍼와 비교 가능. 이번 실측은 그 제한된 근거 | 합성 기준점이 없는 타사 창으로 일반화 불가. 다음 단계는 검증된 테스트 조건에서 실제 한국어 캡처→OCR→F0-B→발행까지 별도 시험하며 프로덕션 거부를 우회하지 않음 |
| 소유 Window의 PixelCopy | 호출 앱이 보유한 Window와 명시적 영역으로 자체 콘텐츠를 얻는 API가 존재 | 접근성 서비스가 임의 타사 Window 객체를 얻는 API가 아님. 운영 앱 OCR의 대체 경로가 아니며, 자체 호스트에서 사용해도 takeScreenshotOfWindow 통합 성공의 증거가 아님 |
| 현재 exact-size window 경로의 제한된 지원 검증 | 실제 버퍼 metadata와 접근성 경계·화면 frame을 검사하는 기존 경로 사용 가능 | 크기 일치만으로 일반 변환을 증명하지 못함. API/기기/회전/inset/호환 모드별 좌표·ROI 격리 및 전체 OCR 회귀가 필요 |
| 별도로 설계하는 사용자 요청형 MediaProjection | 사용자 동의 기반 캡처 API가 존재하며 캡처 크기 변경 등을 관측할 수 있음 | 자체 버퍼의 크기·스케일/letterbox·선택된 영역과 접근성 화면 좌표 대응은 별도 입증 필요. 동의/서비스 생명주기·가림·민감 화면·예산 설계도 필요. 현재 미구현이며 window 실패의 자동 fallback으로 도입하지 않음 |
| API 30~33의 기존 display 캡처 | 공개 takeScreenshot 경로가 이미 있음 | 팝업 밖 민감 정보가 섞일 수 있음. 신뢰할 화면 좌표·ROI와 분석 전 보호를 별도 검증해야 하며 API 37 window 문제의 우회책이 아님 |

`ScreenshotResult`의 공개 metadata는 HardwareBuffer·ColorSpace·timestamp입니다. 타사 Surface의 원점·source crop·buffer-to-screen 행렬을 직접 제공하지 않으며, 공개 window bounds만으로 더 큰 버퍼의 대응을 확정할 수 없습니다. AOSP의 surfaceInsets 크기 증가 경로는 가능한 설명이지 사용자의 API 37 내부 동작에 대한 보증이 아닙니다. 정확한 매핑을 얻을 수 없는 환경은 지원 제한으로 남깁니다.

크기 차이의 절반을 여백으로 가정, elevation에서 padding 계산, 비율 추정 크롭/리사이즈, screen bounds로 강제 잘라내기, 숨겨진 surfaceInsets/reflection, 타사 앱 clipping 변경, 전체 window OCR로 ROI 보호 우회, OCR 신뢰도로 보호 승인 등은 현재 안전한 해결책이 아닙니다. 일반 사용자 이미지에 합성 패턴 탐색을 실행하지 않습니다.

## PR #6의 병합 검토 범위와 남은 TODO

**병합 검토 가능한 범위는 좌표·버퍼의 보수적 검증, 불일치 시 안전한 중단, 개인정보 없는 세션별 진단, debug 전용 합성 실험 및 회귀/패키징 검사와 문서입니다.** 이번 실측은 그중 합성 렌더링 통제 실험의 제한된 성공 근거입니다. 이 범위의 PR로 검토할 수 있으나 팀 리뷰를 대신하거나 자동 병합을 승인하지 않습니다.

| 남은 작업 | 완료로 인정할 증거 |
|---|---|
| 일반 타사 Dialog의 불일치 버퍼 좌표 대응 | 지원할 환경에서 신뢰할 원점/변환·ROI 격리 근거. 공개 API로 확보하지 못하면 REGION_UNCERTAIN 유지 |
| 실제 한국어 캡처/OCR 전체 통합 | 별도 `ServiceCaptureOptInTest`에서 실제 OCR_MATCH 및 OCR 영역 수 > 0, 최신 토큰·F0-A/F0-B·정리까지 성공. 현재 일반 합성 Dialog의 전체 통합 실패는 해결됐다고 주장하지 않음 |
| API 35 재현과 API 37 범위 확장 | 같은 통제 및 전체 경로를 버전별로 실행하고 성공/실패/차단 분리. 이번 API 37 한 번의 결과로 API 35 성공을 추정하지 않음 |
| 보안/민감/가림·회전/확대·취소/timeout·메모리·TalkBack | 개인정보 없는 합성 시나리오에서 기기 회귀. JVM/CI 성공만으로 기기 동작을 보증하지 않음 |
| B·C·D 후속 연동 | 기존 계약 유지. 분류/안전 행동, F10 음성 안내, F12 기억, F13 누르고 말하기 별도 구현·검증. F5 유지, F6 영상 줄거리 제외 |

프로젝트 완료 또는 범용 팝업 OCR 지원 완료를 뜻하는 PR로 병합해서는 안 됩니다. 기본 모드 전체 픽셀 시험과 일반 Dialog 전체 OCR 시험의 알려진 실패/미해결 상태를 릴리스·팀 연동 기록에 함께 남겨야 합니다. 전체 계측 suite 성공을 주장할 수 없습니다.

## 재검증과 근거

Windows 빌드·4개 APK 설치·원래 실패 시험·독립 통제 명령은 [통제 실험 절차](SYNTHETIC_RENDER_CONTROLS.md#windows-api-37-재검증)에 있습니다. 이번 정리는 런타임 코드를 바꾸지 않아 결과 변화나 추가 기기 성공을 주장하지 않습니다. 전용 합성 화면만 사용하고 개인 메시지·은행·실제 결제 화면을 시험하지 않습니다. 개발 환경의 최종 빌드/JVM/Lint/패키징 기록은 [개발 상태](DEVELOPMENT_STATUS.md#pr-6-정리-api-37-통제-실측과-병합-검토-범위)를 확인합니다.

- [takeScreenshotOfWindow 공개 계약](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)) / [ScreenshotResult metadata·버퍼 해제](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.ScreenshotResult)
- [Window.setClipToOutline](https://developer.android.com/reference/android/view/Window#setClipToOutline(boolean)) / [GradientDrawable.setCornerRadius](https://developer.android.com/reference/android/graphics/drawable/GradientDrawable#setCornerRadius(float)) / [PixelCopy](https://developer.android.com/reference/android/view/PixelCopy)
- [MediaProjection 공식 안내](https://developer.android.com/media/grow/media-projection)
- [AOSP Android 15 AccessibilityInteractionController](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/AccessibilityInteractionController.java) / [WindowLayout.computeSurfaceSize](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/WindowLayout.java)
- 상세 이전 조사: [버퍼 크기·공개 API/AOSP/CTS 한계](WINDOW_CAPTURE_BUFFER.md), [합성 좌표 대응](SYNTHETIC_BUFFER_MAPPING.md), [모서리 진단](SYNTHETIC_CONTENT_DIAGNOSTICS.md)
