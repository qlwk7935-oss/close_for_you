# C 화면 테마·대비 점검 (2026-10-19 저녁, Mock 기준)

## 무엇을 바꿨나
- C 코드에는 원래 **하드코딩 색이 없었다** (`Color(...)` 0곳). 대신 모든 C 창이 `MaterialTheme { }`(인자 없음)를 써서 **항상 라이트**였다.
- `ui/voice/UiVoiceTheme.kt` 추가: `isSystemInDarkTheme()`에 따라 Material 기본 `lightColorScheme()` / `darkColorScheme()`만 고른다. **C 전용 색은 만들지 않았다** (A 조건).
- 적용한 곳 (C가 창을 직접 가진 곳만):
  - 선택 창 오버레이 `overlay/AccessibilityChoiceOverlay.kt` (ComposeView)
  - 디버그 미리보기 `debug/ChoicePreviewActivity.kt`
  - `ChoicePromptContent`·설정 화면의 `@Preview`
- **설정 화면 본문(`UiVoiceSettingsRoute`)은 테마를 직접 감싸지 않는다.** A의 `MainActivity` 안에서 A의 테마를 따른다. A의 `MainActivity`는 지금 인자 없는 `MaterialTheme`라 라이트 고정 → 앱 공통 테마는 통합 때 A와 함께 맞춘다 (A 파일은 고치지 않음).
- 앱 공통 테마가 생기면 `UiVoiceTheme`를 그걸로 바꾼다 (`REPLACE-WITH-APP-THEME`).

## 대비 (JVM 시험 `UiVoiceThemeContrastTest`, 기준 4.5:1)

Compose BOM 2025.04.01의 Material3 기본 색으로 계산. 선택 창은 `tonalElevation 6dp`가 섞인 배경으로 계산.

| 짝 | 라이트 | 다크 |
|---|---|---|
| 본문 / 화면 | 16.23 | 14.35 |
| 본문 / 선택 창 | 13.95 | 11.70 |
| 누르기 버튼 글자 / 버튼 | 6.44 | 7.71 |
| 외곽선 버튼 글자 / 선택 창 | 5.26 | 8.89 |
| 외곽선 버튼 글자 / 화면 | 6.12 | 10.91 |
| 보조 글자 / 화면 | 8.88 | 10.91 |

모두 4.5:1 이상. 가장 낮은 곳은 **라이트의 외곽선 버튼 글자 / 선택 창 5.26**.

## 확인하지 못한 것 (실기기 확인 필요)
- 오버레이 `ComposeView(service)`는 접근성 서비스 Context의 설정(uiMode)을 읽는다. **창을 띄울 때의 다크 여부**는 따를 것으로 보지만, 창이 떠 있는 동안 다크 모드를 바꾸면 바로 바뀌는지는 모른다.
- 제조사 테마(갤럭시 One UI, LG)와 "고대비 글꼴", 색 반전에서의 모습.
- 동적 색(Android 12+ 배경화면 색)은 쓰지 않았다. 쓸지는 앱 공통 테마 때 A와 정한다.
