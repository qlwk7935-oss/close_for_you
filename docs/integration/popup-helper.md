# 통합 브랜치 `integration/popup-helper` — 팝업처리도우미

- 만든 사람: C(재호) + Claude, 2026-10-31. 기준: origin/main `365a784`(#62까지).
- 근거: AGENTS.md 6-0 시연 모드, 9장(“A가 닫기를 성공시키면 C가 모든 기술을 합치고 직접 테스트”).
- 상태: 빌드·JVM 시험 통과(5장). `0b15105` LG 벨벳 실측 10/31 21:00(5-1장), origin에 push됨. 수정 목록 F1~F6은 8-1에서 고침(JVM 시험만, **실기기 미검증**, push 전).

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
| 실기기 | 5-1장 (`0b15105` debug APK, LG 벨벳 Android 12, 메인 세션 실측). release APK는 기기에서 안 돌림 |

### 5-1. 실측 10/31 21:00 (`0b15105` debug APK, LG 벨벳)

| # | 항목 | 결과 |
|---|---|---|
| 1 | 홈 아이콘·이름 "팝업처리도우미" | ✅ |
| 2 | 아이콘 누르면 처음 설정이 바로 뜸 | ✅ |
| 3 | 2단계 [설정 열기] 갔다 오면 [다음]에 포커스 | ✅ (설명 문구는 F1) |
| 4 | 3단계 자동 닫기 스위치 | ⚠️ 켜면 포커스가 맨 위로 튐 (F2) |
| 5 | [사용 시작] → 메인 설정, 다시 열면 메인 바로 | ✅ |
| 6 | 크롬 11번가 선택 창 → 닫힘 | ⚠️ 닫힘은 됨. 버튼 2개가 둘 다 "'닫기' 버튼 눌러 주기"(F3, 회귀), 닫힌 뒤 재낭독(F4), 우리 창 전에 TalkBack이 팝업 2번 낭독(F5) |
| 7 | 옥션 | ⚠️ 자동 닫기 안 됨: `CLASSIFIED_UNKNOWN → ASK`, `AD_HINT_IMAGE_DIALOG` 없음(F6). 사용자 선택으로는 닫힘(`CLICKED` → 0.69초 뒤 `CLOSED_CONFIRMED`) |
| 8 | 메인 설정 맨 아래 [개발자 화면 열기] → A 화면 → 뒤로 가기 | ✅ |

11번가 로그(F4): `CLICKED` 02.371 → `CLOSED_CONFIRMED` .643 → `FOCUS_MOVED_TO_PAGE` .670 → RESULT .677 — 순서·속도는 목표대로인데, 체감은 "TalkBack이 말하다 '닫았어요'가 끼고 → TalkBack이 팝업 내용을 다시 읽음".

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

## 8. 수정 목록 (10/31 21:00 실측에서) — 8-1에서 고침, 실기기 미검증

| # | 크기 | 내용 | 고칠 방향 |
|---|---|---|---|
| F1 | 작음 | 처음 설정 2단계 [서비스 켜기] 위 설명 문구가 중복 | 문구 삭제(버튼 누르면 새 안내 창이 설명함) |
| F2 | 작음 | 처음 설정 3단계 "자동 닫기" 스위치를 켜면 TalkBack 포커스가 맨 위 "5단계 중 3단계"로 튐 | 2·5단계처럼 포커스를 자동 닫기 스위치에 유지/복귀 |
| F3 | **큼, 회귀** | 11번가 선택 창 버튼 2개가 모두 "'닫기' 버튼 눌러 주기". 3446d36 단독 빌드에선 "앱설치 안내 레이어 닫기"·"모바일 웹으로 볼래요"로 구분됨 → `c/natural-reading`의 `ButtonNameDisplay`(긴 닫기 이름 → "닫기")가 병합되며 같은 이름이 됨 | 닫기 후보가 2개 이상이거나 줄인 이름이 겹치면 줄이지 않고 원래 이름(또는 구분되는 이름). 단위 시험 추가 |
| F4 | 중 | 11번가 닫은 뒤 TalkBack 재낭독 여전(로그 순서·속도는 목표대로) | 원인 구분용 debug 로그: 포커스 대상 종류 enum(팝업 노드와 겹침 여부 / WebView 루트 / 헤더 등, 글자 없음). 포커스 대상이 팝업 잔여 DOM인지, TalkBack의 창 변화 자체 낭독인지 |
| F5 | 중 | 11번가: 우리 창이 뜨기 전에 TalkBack이 팝업을 2번 읽음(감지 20:57:59.175) | debug 로그: 첫 창 이벤트 수신 → `DETECTED` 차이 ms. TalkBack 낭독 시작 시각과 비교 |
| F6 | 중, 시연 후보 | 옥션 `CLASSIFIED_UNKNOWN → ASK`, 이미지 광고 힌트 미충족. 사용자: "옥션은 TalkBack이 말을 많이 안 하고 딱 좋음, 인식만 되면 시연 예시" | 힌트 조건 중 어디서 빠졌는지 enum 로그 추가 → 조건 조정 |

### 8-1. 고친 내용 (F1~F6 한 빌드, 시연 모드 완화 포함)

| # | 파일 | 고친 것 | 시험 |
|---|---|---|---|
| F1 | `ui/voice/settings/UiVoiceOnboardingScreen.kt` | 2단계 [서비스 켜기] 위 "어떻게 켜는지" 문구 삭제(설정 화면 쪽 문구는 그대로) | 실기기 |
| F2 | `settings/AutoCloseConsentSection.kt`, `settings/OnboardingDesign.kt` | 자동 닫기 확인 창(켜기·범위 넓히기)이 닫히면 TalkBack 포커스를 자동 닫기 스위치로 되돌림: 0.5초 뒤 1회, 0.7초 뒤 잃었으면 1회 더. TalkBack 켜져 있을 때만, 접근성 포커스만. 처음 설정 3단계와 설정 화면 공통. `SwitchCard`에 `tag` 매개변수 추가 | 실기기(시간 값 확인 필요) |
| F3 | `ui/voice/choice/ButtonNameDisplay.kt`, `choice/ChoicePromptContent.kt` | 누를 버튼이 **2개 이상이면 줄이지 않고 원래 이름**. 1개일 때만 긴 닫기 이름 → "닫기". [내용 다시 읽기]의 "원래 이름은 …" 줄도 같은 규칙 | `ButtonNameDisplayTest` 3개 추가(11번가 두 이름 유지, 단독은 "닫기", 원래 이름 줄 없음) |
| F4 | `simpleclose/PopupMemory.kt`(+`FocusTargetKind`·`PageFocusRules`), `SimpleTreeCollector.kt`, 서비스 | ① 닫힌 뒤 포커스 대상: 예전 팝업 루트 **영역과 겹치는** 노드도 제외(루트가 화면 70% 이상인 덮개면 영역 대신 글자·bounds만), 남은 것 중 **화면 가장 위쪽** 항목, 없으면 이름 있는 WebView 자체. ② 로그 `FOCUS_TARGET_{OVERLAPS_OLD_POPUP,WEBVIEW_ROOT,PAGE_HEADING,PAGE_ITEM,NONE}`. ③ `FOCUS_PAGE_BEFORE_CLICK`: 누르기 직전(대상 재확인 **전**)에도 같은 규칙으로 페이지 항목에 포커스, 로그 `FOCUS_PRE_CLICK_MOVED/FAILED`. 재확인 → 클릭 사이에는 아무것도 없음 | `PopupMemoryTest` 4개 추가 |
| F5 | `SimpleCloseEngineFeature.kt`, 서비스 | 로그 `DETECT_LATENCY_MS=<숫자>`(도와줄 앱 첫 이벤트 → DETECTED; 찾지 못하고 끝난 검색·같은 팝업 쿨다운에서는 초기화). 디바운스 300 → 200ms, 재확인 0.5/1/2/3초 → 0.2/0.5/1/2/3초(체인 총 6.8초 → 6.9초, 상한 유지) | 실기기(로그 시각을 TalkBack 낭독 시작과 비교) |
| F6 | `simpleclose/ImageDialogAdHint.kt`, 서비스 | `check()`가 처음 실패한 조건을 돌려줌 → 로그 `AD_HINT_MISS_{SCAM,NOT_DIALOG_ROOT,NO_VERIFIED_CLOSE,IMAGE_TOO_SMALL,TOO_MUCH_TEXT}`(B가 이미 분류한 경우 NOT_UNKNOWN은 안 찍음). 완화: 이미지 40% → **25%**, 이미지 자체가 클릭 가능하지 않아도 됨(링크 안 `<img>`), 루트 종류에 덮개(DIM_OVERLAY)도 포함, 이미지의 대체 글은 본문 글자 수에서 뺌. 결정·사기·B가 분류한 팝업은 계속 제외 | `ImageDialogAdHintTest` 4개 추가 |

- 검증: 5개 Gradle 작업 통과, 단위 시험 debug 1018개·release 1003개 실패 0, lintDebug 오류 0(경고 12, 이전과 같음), 병합 매니페스트 INTERNET 0·새 권한 없음.
- 위험: F6 완화로 이미지로 그린 공지·안내도 광고로 닫힐 수 있다(엔진 규칙, B 판단 아님). F4 누르기 전 포커스 이동이 TalkBack 낭독을 한 번 더 부를 수 있다 — 체감 나쁘면 `FOCUS_PAGE_BEFORE_CLICK = false`.
