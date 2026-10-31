# 통합 브랜치 `integration/popup-helper` — 팝업처리도우미

- 만든 사람: C(재호) + Claude, 2026-10-31. 기준: origin/main `365a784`(#62까지).
- 근거: AGENTS.md 6-0 시연 모드, 9장(“A가 닫기를 성공시키면 C가 모든 기술을 합치고 직접 테스트”).
- 상태: **빌드·JVM 시험 통과(아래 5장). 이 브랜치 APK의 실기기 확인은 아직 안 함(메인 세션 LG 확인 예정).**

## 1. 목적

홈 화면 아이콘 **“팝업처리도우미”**를 누르면 바로 시연에 쓰는 앱이 되게 한다.

- 처음 실행 → C의 5단계 처음 설정(시연 영상의 그 화면) → 메인 설정 화면.
- 실제 팝업 처리는 **플랜 B “단순 닫기 엔진”**(`kr.closeforyou.simpleclose`)이 맡는다. 10/31 LG 벨벳에서 11번가(선택 창 → 닫힘 확인)·옥션(자동 닫기, 감지→닫힘 0.33초)을 성공한 엔진이다(debug 빌드 기준).
- 우진(A)의 엔진(`CloseForYouAccessibilityService`)과 화면은 **지우지도 숨기지도 않는다.** 그대로 빌드되고, 메인 설정 맨 아래 **[개발자 화면 열기]**로 A 화면이 열린다.
- 앞으로 재호와 우진 둘 다 이 브랜치에서 개발·강화한다(6장).

## 2. 무엇을 합쳤나

| 순서 | 가져온 것 | 커밋 | 내용 |
|---|---|---|---|
| 1 | origin/main | `365a784` | #60 시연 모드, #61 TalkBack 주도권, #62 처음 설정 출시 수준 |
| 2 | `c/natural-reading` | `6ab2f72`, `4471ed3` (병합 `4359ee8`) | 자연스러운 낭독(`SpeakableText`), 긴 닫기 이름 표시(`ButtonNameDisplay`), debug 낭독 시험 팝업 |
| 3 | `c/simple-close-engine` | `657577d` … `a946e25`, `3446d36` (병합 `045656c`) | 플랜 B 단순 닫기 엔진 전체 + “닫힌 직후 페이지 포커스 먼저” 수정 |
| 4 | 통합 커밋(이 문서와 함께) | — | 아래 3장 |

두 병합 모두 **충돌 없음**(서로 다른 파일).

## 3. 바뀐 파일과 이유

### A 영역 (우진 확인 필요)

| 파일 | 바뀐 것 | 이유 |
|---|---|---|
| `app/src/main/java/kr/closeforyou/app/MainActivity.kt` | `setContent`를 `UiVoiceAppRoot { MaterialTheme { CoreSetupScreen(...) } }`로 감쌈 (+ import 1줄) | onboarding-release.md 4-1. `CoreSetupScreen`·`isServiceEnabled` 등 **A 코드 본문은 한 줄도 안 바꿈**. A 화면은 [개발자 화면 열기]로 열리고 뒤로 가기로 돌아옴 |
| `app/src/main/AndroidManifest.xml` | ① 앱 아이콘 `@mipmap/ui_ic_launcher`(+roundIcon) ② `<queries>`에 LAUNCHER intent ③ MainActivity `configChanges` ④ A 서비스 label → `@string/core_service_label`(“닫아줄게”) ⑤ 단순 엔진 서비스 선언(debug에서 이동) | 4-3, 4-4, 4-6. ④는 `app_name`이 “팝업처리도우미”로 바뀌면 접근성 목록에 같은 이름이 두 개 생기므로 A 서비스 이름을 “닫아줄게”로 고정 |
| `app/src/main/res/values/strings.xml` | `app_name` = 팝업처리도우미, `core_service_label` = 닫아줄게 추가 | 4-2. 처음 설정 2단계 문구(“팝업처리도우미를 골라 켠 뒤”)와 실제 접근성 목록 이름을 맞춤 |

A 서비스(`CloseForYouAccessibilityService`)·`accessibility_service_config.xml`·`accessibility/` 코드·`CorePreferences`는 **변경 없음**.

### C 영역

| 파일 | 바뀐 것 |
|---|---|
| `simpleclose/SimpleCloseAccessibilityService.kt`, `simpleclose/SimpleTreeCollector.kt` | `app/src/debug` → `app/src/main` (내용 그대로). release에도 들어감 |
| `res/xml/ui_simple_close_service.xml` | debug → main (주석만 수정) |
| `app/src/debug/AndroidManifest.xml` | 단순 엔진 서비스 선언, LAUNCHER `<queries>` 제거(main으로 옮겼으므로) |
| `res/values/ui_voice_strings.xml` | `ui_simple_close_service_description` (debug에서 이동, “시연용”·“닫아줄게” 문구 뺌 — C 문장엔 “닫아줄게” 금지 시험), `ui_settings_section_developer` 추가, `ui_settings_open_core`(“개발자 화면 열기”)·도움말 문구 변경 |
| `debug/res/values/ui_voice_debug_strings.xml` | 단순 엔진 이름·설명 제거(서비스 이름은 이제 `app_name` = “팝업처리도우미”) |
| `ui/voice/settings/AccessibilityServiceStatus.kt` | “켜짐/꺼짐” 확인과 [설정 열기] 강조 대상 = `ENGINE_SERVICE_CLASS`(= 단순 엔진, `SimpleCloseEngineFeature.enabled`가 false면 A). 단순 엔진일 때 `collectionConsentGranted()` = true(단순 엔진은 “도와줄 앱” 대기 목록을 직접 읽어서 A 동의를 기다릴 것이 없음) |
| `ui/voice/settings/UiVoiceSettingsScreen.kt` | “지금 상태” = 단순 엔진 서비스 켜짐 여부, “도와줄 앱 준비 중(동의 필요)” 문구 안 뜨게, [개발자 화면 열기]를 페이지 **맨 아래** 새 절로 이동 |
| `ui/voice/settings/UiVoiceAppRoot.kt`, `simpleclose/SimpleCloseAccessibilityService.kt` | 주석만 (“debug 전용” → main 선언). 단순 엔진 로그 `CfySimple`·`CfySpeech`는 여전히 debuggable 빌드에서만 찍힘 |

## 4. 우진 엔진을 그대로 둔 방법 · 두 엔진 동시 켜기 금지

- 접근성 설정 목록에 두 서비스가 함께 보인다: **“팝업처리도우미”**(단순 엔진, 시연용) / **“닫아줄게”**(A 엔진).
- **둘 중 하나만 켠다.** 둘 다 켜면 창 이벤트를 둘이 받아 선택 창·안내·클릭이 겹친다(코드로 막지 않음 — 남은 문제).
- 앱의 처음 설정·설정 화면은 **단순 엔진만** 본다. A 엔진을 시험할 때는 [개발자 화면 열기] → A 화면의 “시스템 접근성 서비스: 켜짐/꺼짐”(A 서비스 기준, 기존 그대로)과 수집 동의·분석 시작을 쓴다.
- 앱 전체를 A 엔진 기준으로 되돌리려면 `SimpleCloseEngineFeature.enabled = false` 한 줄(단순 엔진 서비스는 아무것도 안 하고, 설정 화면이 다시 A 서비스를 봄).

## 5. 검증 결과

| 항목 | 결과 |
|---|---|
| `:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug` | **BUILD SUCCESSFUL**. 단위 시험 debug 1006개·release 991개 실패 0. lintDebug 오류 0(경고 12). 첫 실행에서 `OnboardingTextTest.appNameIsPopupHelperEverywhereInCStrings` 1개 실패 — 새로 넣은 C 문장에 “닫아줄게”를 써서(C 문장엔 그 이름 금지 규칙). 문장을 “A 엔진”·“다른 팝업 엔진 서비스”로 고쳐 통과 |
| 병합 매니페스트(debug·release) | INTERNET 없음(0건). 권한: release = `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`(androidx 기본, 기존과 같음)뿐, debug = 그것 + `RECORD_AUDIO`(기존 debug 전용). **새 권한 없음.** release 서비스: `CloseForYouAccessibilityService`(label 닫아줄게) + `SimpleCloseAccessibilityService`(label 팝업처리도우미). 아이콘 `@mipmap/ui_ic_launcher`. debug에는 기존 시험 서비스 `ChoiceOverlayTestService`와 “닫을까요? 미리보기” 런처 아이콘이 더 있음 |
| 실기기 | **미검증.** 단순 엔진의 11번가·옥션 성공은 `c/simple-close-engine` debug APK(a946e25 이전·이후) 기준 실측. 이 브랜치 APK(release 소스셋 이동·앱 진입 화면 변경)는 아직 기기에서 안 돌림 |

## 6. 이 브랜치에서 일하는 규칙 (초안 — 재호·우진 합의 필요)

1. 이 브랜치가 시연용 기준이다. 각자 작은 브랜치(`c/…`, `a/…`)를 이 브랜치에서 따고, 이 브랜치로 PR → 상대가 리뷰 → 병합.
2. 병합 조건: 위 5개 Gradle 작업 통과, 병합 매니페스트에 INTERNET·새 권한 없음.
3. 실기기 확인은 C(재호) 또는 A(우진)가 LG 벨벳에서, 결과는 PR에 “실측(기기·시각)” / “미검증”으로 구분해 적는다.
4. 단순 엔진(`simpleclose/`)은 C 소유, A 엔진(`accessibility/`)은 A 소유. 상대 영역 수정은 PR 설명에 이유와 영향 범위를 쓴다.
5. A 엔진의 좋은 부분(웹 요소 클릭, 연속 팝업 재감지 등)을 단순 엔진으로 옮길 때는 공용 계약(`core/model`, `core/contract`)을 바꾸지 말고 엔진 안에서 쓴다. 계약 변경은 합의 후 별도 PR.
6. 시연 모드(AGENTS 6-0) 끝까지 유지 항목은 이 브랜치에서도 그대로: 결제·구매·동의·권한·구독·삭제 버튼 안 누름, 사기 경고, 화면 글자·개인정보 로그 금지, INTERNET 금지, 미검증을 완료로 보고 금지.
7. main 병합 시점은 최종 발표 전 팀이 정한다.

## 7. 남은 문제

- 두 엔진 동시 켜기를 코드로 막지 않는다(안내 문구와 이 문서뿐).
- release에는 `RECORD_AUDIO`가 없다(debug 매니페스트에만). 설정의 음성 명령 마이크 허용은 release에서 동작하지 않는다 — 새 권한이라 이번에 넣지 않음(사용자 확인 필요).
- `configChanges`가 LG에서 설정 복귀 시 Activity 재생성을 실제로 막는지 미확인(4-6, 원인 구성 변경 미확인).
- 11번가 설치 시트는 B main에서 NOTICE → 프리셋 “광고만 닫기”에서는 묻는다. B `feature/decision-web-overlay` 병합 시 광고로 바뀜(B 판단).
- debug 빌드에는 “닫을까요? 미리보기” 아이콘과 시험 접근성 서비스가 하나씩 더 있다(debug 전용, release엔 없음).
- release APK는 서명 안 됨(`app-release-unsigned.apk`) — 시연 설치는 debug APK.
- release 빌드에서는 단순 엔진 로그가 안 찍힌다(debuggable 아닐 때 꺼짐) — 현장 문제 추적은 debug APK로.
