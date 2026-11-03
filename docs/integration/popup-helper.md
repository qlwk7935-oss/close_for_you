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

- 검증(F1~F8 한 빌드): 5개 Gradle 작업 통과, 단위 시험 debug 1024개·release 1009개 실패 0, lintDebug 오류 0(경고 12, 이전과 같음), 병합 매니페스트 INTERNET 0·새 권한 없음.
- **F7 — 시연 모드 6-0 완화: 전부 자동 닫기에서 안전 조건을 모두 만족한 판단 불가 팝업 닫기 (재호 결정 10/31).**
  - 조건(모두): 프리셋 "전부 자동 닫기" + 자동 닫기 스위치 ON(처음 설정 완료), B 분류 UNKNOWN, 금지어 버튼(`SimpleLabelReject.FORBIDDEN`: 결제·구매·동의·권한·구독·삭제·설치·앱에서·열기…) 0개, 검증된 버튼이 라벨 있는 닫기 정확히 1개(라벨 없는 X는 후보가 아님, 6-3), 사기 의심 아님, USER_DECISION 아님. 클릭 직전 재확인·1회 클릭은 그대로.
  - "광고·쿠폰 닫기"·"광고만 닫기"는 UNKNOWN을 계속 묻는다. 결과 문장 "팝업을 닫았어요."(`ui_auto_obj_other`). 로그 `DECIDED_AUTO_CLOSE_UNKNOWN`.
  - 파일: `simpleclose/SimpleCloseDecider.kt`(`DemoUnknownAutoClose.ENABLED = true`로 끔/켬), 서비스(금지어 수 전달·로그), `SimpleTrace.kt`.
  - 문구: "전부 자동 닫기"를 골랐을 때만 프리셋 아래 고정 문장을 `ui_preset_decision_fixed_close_all`("…알 수 없는 팝업은 위험한 버튼이 없고 닫는 버튼이 하나뿐일 때만 닫고, 그 밖에는 물어봐요")로, 자동 닫기 확인 창에 `ui_preset_consent_unknown_all` 한 줄 추가. 다른 프리셋 문구는 그대로("…알 수 없는 팝업도 물어봐요").
  - AGENTS 6장 2번은 고치지 않음(6-0 우선).
  - 시험: `SimpleCloseDeciderTest` 세 프리셋 × (조건 충족 / 금지어 / 닫기 2개 / 사기) 매트릭스 + 건너뛰기만 있음·버튼 없음·결정·스위치 꺼짐·동의 꺼짐·11번가 시트, `PresetStorageAndTextTest` 문구 선택. 기존 시험 `unknownIsAskedEvenWhenEveryKindIsInThePreset`는 규칙이 바뀌어 "스위치 끄면 모든 프리셋에서 묻기"로 바꿈.
- **F8 — 화면 설명과 실제 동작 맞추기 (10/31).** `PresetAutoCloseFeature.live`(C)가 false라 자동 닫기 스위치 아래·확인 창에 "자동 닫기는 곧 켜질 예정이에요. 지금은 닫기 전에 물어봐요"가 보였는데, 단순 엔진은 실제로 바로 닫았다. → `live`를 `SimpleCloseEngineFeature.enabled`를 따르게 바꿈(지금 true). 단순 엔진을 끄면 다시 false(그 문구가 돌아옴).
  - 같이 바뀌는 것: ① 그 두 문구가 사라짐 ② `UiVoicePreferences.autoCloseConsentSource`(A F9용 동의 내보내기)가 스위치·프리셋대로 종류를 내보냄 — main 코드에서 읽는 곳 없음(A 엔진은 아직 안 읽음). 바뀌지 않는 것: A 수집 동의 대기, 처음 설정 완료 문구, 프리셋 선택만으로는 아무것도 안 닫힘(처음 설정 완료 + 스위치 필요).
  - 시험: `SimpleCloseDeciderTest.screensAndEngineAgreeOnWhatClosesWithoutAsking` — 세 프리셋 × 스위치 켬/끔 × 광고·쿠폰·공지에서 "화면이 내보내는 종류 = 엔진이 묻지 않고 닫는 종류 = 스위치 켬 + 프리셋 줄" 고정, 확인 창에 "준비 중" 줄 없음. 기존 "아직 live 아님" 시험 3곳은 `live = false`를 명시하거나 "live = 단순 엔진 켜짐"으로 바꿈.
- **실측 10/31 22:30 (697d6b3, LG, 메인 세션) → origin push됨.** 통과: F1, F2, F3, F6·F7(옥션 `AD_HINT_IMAGE_DIALOG → CLASSIFIED_AD →` 자동 닫기 → "광고를 닫았어요"), F8, 서비스 켜기 복귀 포커스. 남음: F9~F11.

### 8-2. F9~F11 (10/31 밤, JVM 시험만, 실기기 미검증)

| # | 증상(LG) | 고친 것 | 시험 |
|---|---|---|---|
| F9 | 11번가 닫은 뒤 `FOCUS_TARGET_OVERLAPS_OLD_POPUP → FOCUS_MOVE_FAILED`, TalkBack이 팝업 잔여 노드를 다시 읽음 | 닫힌 직후 1차(페이지 항목만) → 실패하면 0.3초 뒤(`FOCUS_RETRY`, DOM이 돌아올 시간) 대체 단계로 **반드시 어딘가로**: ① 예전 팝업 영역 **위쪽** 첫 항목(`ABOVE_OLD_POPUP`) ② WebView 자체(이름 없어도, `WEBVIEW_ROOT`) ③ WebView 밖 첫 이름 있는 항목 = 크롬 툴바(`TOOLBAR_ITEM`, 주소 입력칸 제외, 예전 팝업 영역 제외). `PageFocusRules`·`SimpleTreeCollector.focusPageItem` | `PopupMemoryTest` 5개(모두 겹칠 때 위쪽 → WebView → 툴바, 1차는 이유만, 좋은 항목이 항상 우선, 위쪽 판정) |
| F10 | `DETECT_LATENCY_MS` 11번가 43296·옥션 5309, 우리 창 전에 TalkBack이 팝업을 두 번 읽음 | ① 서비스 설정에 `typeViewAccessibilityFocused` 추가: TalkBack이 도와줄 앱 안 노드에 포커스하면 디바운스 없이 즉시 1회 읽기(300ms 스로틀, 재확인 체인이 돌고 있으면 끊지 않음). ② 로그 분리: `DETECTED_BY_{WINDOW_EVENT,CONTENT_EVENT,RECHECK,TALKBACK_FOCUS}` + `DETECT_GAP_MS`(팝업 없음을 본 마지막 읽기 → 감지; 팝업이 실제로 뜬 뒤 걸린 시간의 상한) + 기존 `DETECT_LATENCY_MS`(첫 이벤트 → 감지, 페이지 로드부터라 과대) | 실기기 |
| F11 | 옥션 자동 닫기 뒤 "광고를 닫았어요"(우리 TTS)가 TalkBack 낭독과 겹쳐 안 들림 | ① TalkBack 켜짐이면 결과 문장을 **TalkBack 큐로**: 작은 접근성 오버레이 카드(`TalkBackResultBanner`, 위쪽, 4초, 포커스 안 받음)에 polite live region으로 문장을 넣어 TalkBack이 자기 순서대로 읽게 함(`RESULT_VIA_TALKBACK`). 창을 못 띄우거나 TalkBack 꺼짐이면 기존 TTS(`RESULT_VIA_TTS`). `announceForAccessibility` 안 씀. 화면에도 글자가 보임(저시력). ② 자동 닫기: 누르기 전에 TalkBack 포커스가 팝업 안이었을 때만(`FOCUS_WAS_IN_POPUP`) 누르기 전·후 포커스 이동, 밖이면(`FOCUS_WAS_OUTSIDE/UNKNOWN`) 안 옮김 → 페이지 제목 낭독이 줄어듦. 사용자 선택 경로는 우리 창이 사라지므로 항상 옮김. 순서: 포커스 이동 → 결과 문장(polite라 TalkBack이 포커스 항목을 읽은 뒤 말함; 결과를 먼저 내고 포커스를 옮기면 포커스 낭독이 결과를 끊음) | 실기기(live region이 접근성 오버레이 창에서 TalkBack에 읽히는지가 핵심) |

- **실측 10/31 23:0x (193c6b0, LG) → origin push됨.** ✅ F10: 11번가 `DETECTED_BY_CONTENT_EVENT`, TalkBack이 팝업을 1번만 읽고 우리 창. ✅ F11: 옥션 `DECIDED_AUTO_CLOSE → FOCUS_WAS_OUTSIDE → CLICKED → CLOSED_CONFIRMED → RESULT_VIA_TALKBACK`, "TalkBack 다 끝나고 '광고를 닫았어요' 들림". 🔺 F9: 11번가 사용자 누르기 `CLOSED_CONFIRMED 07.954 → FOCUS_RETRY → 08.279 FOCUS_TARGET_WEBVIEW_ROOT·FOCUS_MOVED_TO_PAGE → 08.337 RESULT_VIA_TALKBACK` — 닫은 뒤 TalkBack이 팝업 설명을 다시 읽기 시작하다 끊김, "닫았어요"는 안 들림.

### 8-3. F12 (10/31 밤, JVM 시험만, 실기기 미검증)

| 고친 것 | 파일 | 시험 |
|---|---|---|
| ① 누르기 직전 포커스를 **닫힌 뒤에도 남는 곳**으로 바로: 예전 팝업 위쪽 항목(가장 위) → WebView 자체 → 툴바. 팝업 옆·아래 항목은 그때는 안 고름(`PageFocusRules.pick(beforeClick = true)`), 로그 `FOCUS_PRE_CLICK_{ABOVE_OLD_POPUP,WEBVIEW_ROOT,TOOLBAR_ITEM,NONE}` | `PopupMemory.kt`, `SimpleTreeCollector.kt`, 서비스 | `PopupMemoryTest` 3개 |
| ② 닫힌 뒤: TalkBack 포커스가 이미 예전 팝업 밖이면 **다시 옮기지 않음**(`FOCUS_KEPT_ON_PAGE`, 또 옮기면 한 번 더 읽음). 안이거나 모르면 **0.3초 재시도 없이** 즉시 모든 대체 단계로 이동. `FOCUS_RETRY` 삭제 | 서비스 | 실기기 |
| ③ 결과 카드: 포커스를 옮긴 경로(사용자 누르기, 자동 닫기 중 포커스가 팝업 안이던 경우)는 0.6초 뒤 카드(`RESULT_AFTER_FOCUS_MILLIS`) → TalkBack이 포커스 항목을 읽은 다음 "닫았어요". 카드가 실제로 화면에 붙었는지 `RESULT_BANNER_SHOWN/NOT_SHOWN` | `TalkBackResultBanner.kt`, 서비스, `SimpleCloseEngineFeature.kt` | 실기기 |

- 검증(F1~F12 한 빌드): 5개 Gradle 작업 통과, 단위 시험 debug 1031개·release 1016개 실패 0, lintDebug 오류 0(경고 12), INTERNET 0·새 권한 없음.

### 8-4. 우진 PR #65 병합 (`origin/integration/a-safety-guards` 0261fe2·f50d21f, 병합 커밋, 리베이스 X)

- 가져온 것(A 안전 장치, **동작 그대로**): 민감 앱(은행·결제 등) 트리 수집 전 차단(`SimpleAppAllowance.isInScope`·`SensitiveAppRules`), 실제 활성 창 패키지가 이벤트 패키지와 같을 때만 수집(`SimpleCollectionGuard`), 앱 정보 조회 실패·라벨 없음이면 차단(`AndroidSimpleAppLookup`, 백그라운드에서), 이벤트 스케줄링 분리(`SimpleInspectionEvents`), `helpAllAppsWhenUnset` 제거(도와줄 앱을 안 골랐으면 A 허용 목록만).
- 병합 기준: PR 브랜치는 0b15105 기준이라 3-way 병합. 충돌은 `SimpleCloseAccessibilityService.kt` 2곳뿐.
  - 이벤트 처리: 우진의 `inspectionEvents.onEvent(...)` 구조를 그대로 쓰고, F10의 TalkBack 포커스 즉시 읽기는 `SimpleInspectionEvents.onTalkBackFocus`로 옮김(같은 `appInScope` 검사, 이미 읽는 중·누르는 중·선택 창이면 안 함, 300ms 스로틀). F5/F10 로그(`DETECTED_BY_*`, `DETECT_LATENCY_MS`)는 선택 매개변수 `onTrigger`·`onSearchEnded`로 연결(기본값은 아무것도 안 함).
  - `inspect(expectedPackage)`: 우진 서명 그대로, 트리거는 서비스 필드로 받음.
  - F9~F12 포커스·결과 카드 순서는 충돌 없이 그대로 남음.
- 우진 시험 `SimpleInspectionEventsTest`의 시간 값(300ms 디바운스, 0.5/1/2/3초)은 F5에서 200ms·0.2/0.5/1/2/3초로 바뀐 상태라, 숫자를 `SimpleCloseEngineFeature` 상수에서 계산하도록만 바꿈(검사하는 안전 조건은 그대로). F10 시험 3개 추가(포커스 즉시 읽기, 스로틀·체인 안 끊음, 민감 앱·선택 창이면 안 읽음).
- ⚠️ 동작 변화: 도와줄 앱을 고르지 않은 상태(처음 설정에서 [나중에 고르기])면 이제 크롬도 `SKIPPED_NOT_ALLOWED_APP`. 시연 기기는 처음 설정에서 "모든 앱" 또는 크롬을 골라 둘 것.
- 검증(F1~F12 + #65): 5개 Gradle 작업 통과, 단위 시험 debug 1066개·release 1051개 실패 0, lintDebug 오류 0(경고 12), INTERNET 0·새 권한 없음.
- #65 갱신(ab75a32) 재병합: `SimpleInspectionEvents`·시험과 서비스의 이벤트 분기·`inspect(expectedPackage, trigger)`·`resetConnection()`은 우진 버전(위의 `onTalkBackFocus`·`onTrigger`·`onSearchEnded`는 `onEvent(…, TALKBACK_FOCUS)`·trigger 매개변수·`onSearchExhausted`로 대체), F12(누르기 전 포커스, `FOCUS_KEPT_ON_PAGE`, 결과 카드 0.6초 뒤)는 우리 버전 유지.

### 8-5. F13 — TalkBack이 말을 너무 많이 함 (11/1 실측 `ed6c153`, JVM 시험만, 실기기 미검증)

실측(11번가 공지·묻기): `DETECT_LATENCY_MS=6419`, `DETECT_GAP_MS=7921` → 묻기 → 누르기 전 `FOCUS_PRE_CLICK_ABOVE_OLD_POPUP` → 닫힘 → `FOCUS_TARGET_WEBVIEW_ROOT`(또 이동) → 결과. 들린 것: TalkBack → 우리 TTS → TalkBack, 닫은 뒤 팝업 글 2번 → "공지를 닫았어요". 옥션(쿠폰·자동)은 정상, 그대로 둠.

- **감지 6.4초의 원인 (코드 분석):** `DETECT_GAP_MS`(7.9초)가 지연(6.4초)보다 길다 = 첫 이벤트 뒤 6.4초 동안 "팝업 없음"으로 끝난 읽기가 한 번도 없었다. 크롬은 약 0.1초마다 내용 변경 이벤트를 보내는데, 그때마다 `scheduleInspection`이 기다리던 읽기를 취소하고 200ms 디바운스를 다시 걸었다. 페이지가 잠잠해질 때까지 읽기가 한 번도 돌지 못했다(또 내용 이벤트가 창 이벤트의 재확인 체인도 끊음). `SKIPPED_NOT_ALLOWED_APP`·#65 패키지 검사는 원인이 아님: 거부되면 `TREE_UNAVAILABLE`이 찍히고 이번 실측 로그 순서에는 없었다(다음 실측에서 `DETECT_TREE_UNAVAILABLE` 숫자로 확인).
  - 고침(`SimpleInspectionEvents`, 대기만 줄임, 안전 검사 그대로): 같은 앱의 첫 읽기가 이미 기다리는 중이면 내용 이벤트는 합침(`EVENT_COALESCED`), 창 이벤트는 다시 걸되 처음 건 시점부터 최대 `FIRST_READ_MAX_WAIT_MILLIS`(400ms)까지만. 다른 앱 이벤트는 합치지 않음. 모든 읽기는 여전히 `appInScope`와 작업 스레드의 전체 검사(#65)를 거침.
  - 단계 로그(숫자만): 감지 때 `DETECT_READS`(이번 탐색의 읽기 수), `DETECT_TREE_UNAVAILABLE`(가드가 거부한 수), `TREE_READ_MS`(마지막 읽기 시간).
- **포커스는 한 번만:** 사용자가 [닫기]를 고르면 우리 창을 **없애기 전에** 포커스를 페이지로 옮긴다(`AccessibilityChoiceOverlay(beforeClose=…)` → `focusPageBeforeOurWindowGoes`). 우리 창이 포커스를 가진 채 사라지면 TalkBack이 팝업으로 돌아가 읽었다(재낭독 1번의 유력 원인, 실기기 확인 필요). 그 뒤 누르기 전 이동은 하지 않는다.
  - 누르기 전 대상(`PageFocusRules.plan(beforeClick)`): 크롬 툴바(웹뷰 밖, DOM이 바뀌어도 남음) → 팝업 위쪽 제목 → 팝업 위쪽 항목. 팝업의 잔여(같은 글·같은 자리·팝업 영역·dim)는 제외, **WEBVIEW_ROOT는 쓰지 않음**.
  - 닫은 뒤: 포커스가 팝업 밖에 살아 있으면 그대로(`FOCUS_KEPT_ON_PAGE`). 확인은 모든 창에서(`AccessibilityService.findFocus`) → 활성 창 순. 사라졌을 때(`FOCUS_AFTER_CLOSE_LOST`)나 팝업 안일 때(`FOCUS_AFTER_CLOSE_IN_POPUP`)만 한 번: 페이지 맨 위 항목 → 툴바 → WEBVIEW_ROOT(마지막 수단).
  - 결과 카드는 닫은 뒤 실제로 옮겼을 때만 0.6초 기다리고, 아니면 바로 TalkBack 큐로.
- **목소리 하나로 (시연 모드 6-0, 안내 맡기기 ON일 때):** 선택 창이 뜨면 포커스를 첫 항목으로 가져오고(기존), 그 항목을 요약 + 공지 앞부분(`PROMPT_EXCERPT_CHARS` 50자, 팝업 원문 그대로, 공백에서 자름) + "어떻게 할까요? 다음 항목에서 고를 수 있어요."를 합친 한 노드로 만든다. TalkBack 혼자 읽고, **우리 TTS는 말하지 않으며 버튼으로 다시 옮기지 않는다**(`TalkBackTakeoverFeature.ONE_VOICE`). 첫 항목에 포커스를 못 주면 예전처럼 TTS. 팝업 원문을 TTS로 읽는 프롬프트(A 경로)는 예전 3단계 그대로.
- TalkBack을 끄거나 멈추는 코드는 없음(보안 설정 변경 X). 화면 글자·패키지는 로그에 없음(숫자·enum만).
- 검증: 5개 Gradle 작업 통과, 단위 시험 debug 1077개·release 1062개 실패 0, lintDebug 오류 0(경고 12), INTERNET 0·새 권한 없음. 시험 추가: 포커스 계획 4, 이벤트 합치기 4, 발췌·한 목소리 4.
- 위험: ① 툴바 첫 항목이 "홈"·"주소창" 등 무엇일지 기기마다 다름(TalkBack이 그 이름을 한 번 읽음). ② 우리 창이 사라진 뒤 TalkBack이 스스로 포커스를 옮기는지는 실기기 확인 필요. ③ 한 목소리 모드에서는 버튼으로 자동 이동하지 않으므로 사용자가 한 번 오른쪽으로 쓸어야 버튼. ④ 첫 읽기를 최대 0.4초에 강제로 돌리므로 애니메이션 중인 시트를 일찍 읽을 수 있음 → 재확인 체인이 다시 잡음.

### 8-6. F14 — F13 실측(11/1 01:37, `5ce2c10`) 뒤 (JVM 시험만, 실기기 미검증)

실측: 11번가 `DETECTED_BY_CONTENT_EVENT`, `DETECT_LATENCY_MS=6108`, `DETECT_READS=1`, `DETECT_TREE_UNAVAILABLE=0`, **`TREE_READ_MS=1755`**, 사이에 `EVENT_COALESCED`가 약 2초마다. 닫은 뒤 `FOCUS_PRE_CLICK_TOOLBAR_ITEM`·`MOVED`였는데도 `FOCUS_AFTER_CLOSE_IN_POPUP` → 툴바로 또 이동. 들린 것: TalkBack이 팝업 2번 + 우리 창 1번, 닫힌 뒤 팝업 또 → 결과. 옥션(`TREE_READ_MS=989`, 지연 1.2초)은 정상, 그대로 둠.

- **감지 6.1초의 진짜 원인 (코드로 확인):** 한 번 읽는 데 1.75초가 걸리는데 내용 이벤트 제한은 1.5초이고, 그 기준 시각은 읽기를 **시작한** 때다. 그래서 읽기 시작 1.5초 뒤 온 내용 이벤트가 아직 돌고 있는 읽기를 취소했다. 취소된 읽기의 순회(작업 스레드)는 멈추지 않아 다음 읽기와 겹쳐 더 느려졌다. 취소된 읽기는 `DETECT_READS`에 세지지 않아 1로 찍혔다. F13의 합치기는 "기다리는 중"만 막고 "읽는 중"은 막지 못했다.
  - 고침(`SimpleInspectionEvents`): 같은 앱 읽기가 **진행 중이면 이벤트로 취소하지 않는다**(합침, `EVENT_COALESCED`). 그 읽기가 팝업을 못 찾았을 때만, 그동안 온 이벤트가 내용 변경이면 한 번 더 읽고, 창 이벤트면 재확인 체인으로 잇는다. 다른 앱 이벤트는 예전처럼 교체한다. 안전 검사(`appInScope`, 작업 스레드의 #65 전체 검사)는 매 읽기 그대로다.
- **화면 읽기 비용:** 자식 노드 하나가 바인더 호출 1번이고, 긴 쇼핑 페이지는 노드 대부분이 화면 아래에 있다. **보이지 않고 화면 밖에 완전히 있는 하위 트리는 내려가지 않는다**(`TreeWalkRules.descend`).
  - 수집하는 노드는 예전과 같다(원래 보이는 노드만 수집). 금지 버튼 판단에 쓰던 노드는 빠지지 않는다. 단, 화면 밖 컨테이너 안에 든 고정 위치 요소는 예외이고, 모달 라이브러리는 보통 body 바로 아래에 둔다.
  - 크기 0인 래퍼(포털)는 계속 내려간다.
  - 로그: `TREE_VISITED`, `TREE_SKIPPED_OFFSCREEN`, `TREE_READ_MS`. 목표는 0.3~0.5초이며 실기기 확인이 필요하다.
  - 팝업 루트를 먼저 찾고 그 하위만 수집하는 방식은 하지 않았다. 루트 판단(dim 덮개 등)에 페이지 노드도 필요하기 때문이다. 이번 숫자를 보고 다음에 결정한다.
- **닫힌 뒤 포커스가 팝업으로 돌아가는 것:**
  - 근거: F13에서 이미 우리 창을 없애기 **전에** 툴바로 옮겼고(`FOCUS_PRE_CLICK_MOVED`) 그 순서가 로그로 확인됐다. 그런데도 닫힌 뒤 팝업 영역에 포커스가 있었다. 따라서 "우리 창이 닫히며 덮어썼다"보다 **팝업이 닫힐 때 웹 페이지·크롬이 스스로 입력 포커스를 되돌리고 TalkBack이 그것을 따라간 것**이 더 유력하다(모달 라이브러리는 닫힐 때 포커스를 이전 요소로 복원하는 것이 일반적). 이는 TalkBack 내부 동작이라 공식 문서로 확정할 수 없으므로 **실기기 확인 필요**.
  - 고침: 닫힘 확인 뒤 **0.3초(`FOCUS_SETTLE_MILLIS`) 기다렸다가** 포커스 위치를 보고, 필요할 때만 한 번 옮긴다. 우리 이동이 마지막이 되도록 하기 위해서다.
  - 증거 로그: 누른 뒤 우리가 하지 않은 TalkBack 포커스 이동 수 `FOCUS_MOVES_BY_OTHERS=n`. 우리 동작의 메아리 0.25초는 빼고 센다. n>0이면 페이지가 옮긴 것이다.
  - "우리 창을 마지막에 닫기"는 하지 않았다. 우리 창은 포커스를 받지 않는 오버레이(`FLAG_NOT_FOCUSABLE`)라서, 열어 둬도 크롬의 입력 포커스 이동은 그대로 일어나고 TalkBack도 그대로 따라간다고 봤다(실기기 확인 필요).
- **같은 내용 반복 줄이기:** 묻기 직전 TalkBack 포커스가 팝업 안이면(`FOCUS_WAS_IN_POPUP`, TalkBack이 이미 읽은 것) 첫 항목은 "공지로 보이는 팝업이에요. 닫을까요?"만. 본문 앞부분은 넣지 않는다(누를 버튼이 있을 때). 포커스가 밖이거나 모를 때만 F13처럼 앞부분과 긴 질문을 넣는다. 문장 `ui_choice_takeover_question_short`.
- **F13에서 되돌린 것 / 남긴 것:**
  - TalkBack이 이미 읽었을 때 붙던 공지 앞부분은 반복이라 이번에 뺐다.
  - 한 목소리(우리 TTS 없음)는 `PROMPT_OR_DENY_NOTE` 없음으로 효과가 확인돼 유지한다.
  - 우리 창 닫기 전 포커스 이동은 로그상 동작했고, 그것만으로 재낭독을 막지 못한 원인은 위와 같아 유지한다. 툴바 이름 낭독이 거슬리면 `FOCUS_PAGE_BEFORE_CLICK = false` 한 줄로 끌 수 있다.
  - 대기 합치기(F13)는 이번 수정의 바탕이라 유지한다.
- 검증: 5개 Gradle 작업 통과, 단위 시험 debug 1084개·release 1069개 실패 0, lintDebug 오류 0(경고 12), INTERNET 0·새 권한 없음. 시험 추가: 읽는 중 보호 3, 순회 규칙 4.

### 8-7. 우진 PR #66 병합 (`origin/integration/a-mutual-exclusion` 82313c3, 병합 커밋, 리베이스 X)

- A 영역만 바뀜(`accessibility/AEngineExclusion`·`AndroidEnabledEngineSource`·`EnabledEnginePolicy`, A 서비스·캡처 관문, 시험 3개, `docs/A_ENGINE_MUTUAL_EXCLUSION_2026-11-01.md`). 충돌 없음. C 파일은 건드리지 않았다.
- 동작: C(`SimpleCloseAccessibilityService`)가 시스템 설정에서 켜져 있으면 A는 수집·분석·캡처를 멈춘다. C를 끄면 다음 새 이벤트부터 A가 다시 수집한다(자동 재시작 없음). 설정 조회 실패는 A 차단 쪽. **A는 이 동작에 logcat을 새로 남기지 않는다**(우진 문서: "새 로그 추가 없음") → 실기기 확인은 A 상태 화면과 A가 팝업을 잡는지로 본다.

### 8-8. F15 — F14 실측(11/1 02:53, `01f0f7d`) 뒤 (JVM 시험만, 실기기 미검증)

실측: 11번가 `DETECT_LATENCY_MS=2305`(6.1초에서), `TREE_READ_MS=2043`, `TREE_VISITED=1424`, **`TREE_SKIPPED_OFFSCREEN=0`**. 닫은 뒤 `FOCUS_MOVES_BY_OTHERS=2` → `FOCUS_AFTER_CLOSE_LOST` → 툴바. 들린 것: 우리 창 전에 TalkBack이 팝업 2번, 닫힌 뒤 팝업 또. 결론: 닫힌 뒤 재낭독은 페이지(크롬)가 스스로 포커스를 옮긴 것. 옥션(`TREE_READ_MS=716`, 지연 987)은 정상.

- **`TREE_SKIPPED_OFFSCREEN=0`인 이유(추정):** 크롬은 화면 밖 웹 노드도 `isVisibleToUser=true`로 보고하는 것으로 보인다(10/30 덤프의 높이 0 푸터 링크가 "보임"으로 수집된 것이 근거). F14 규칙은 "안 보임 **그리고** 화면 밖"일 때만 건너뛰므로 크롬에서는 한 번도 해당하지 않았다. 크롬 내부 동작이라 공식 문서로는 확정 못 함.
- **F15-1 팝업 하위 트리만 읽기 (`PopupProbe`, `READ_POPUP_SUBTREE_FIRST`):**
  - 먼저 작은 탐색: **마지막 자식부터**(웹 모달은 보통 페이지 끝에 붙고 위에 그려짐) 내려가며 Dialog 노드(크롬은 `role="dialog"`를 AlertDialog로 내보냄)를 찾으면 즉시 멈춘다. 크기가 있고 화면 밖에 완전히 있는 노드는 들어가지 않는다(보임 여부와 무관, 탐색 실패는 시간만 듦). 최대 `PROBE_MAX_VISITED`=200노드(LG 크롬 1노드 약 1.4ms).
  - Dialog가 없으면 dim 덮개(클릭 가능·글자 없음·화면 85% 이상·루트 아님)의 부모를 쓰되, 탐색이 상한 없이 끝까지 돈 경우만(Dialog가 이기므로).
  - 찾은 경로의 하위 트리만 예전과 같은 방식으로 수집하고, **그 하위 트리만으로 `PopupRootFinder`가 루트를 내야** 쓴다. 못 내거나 탐색이 못 찾으면 예전처럼 전체를 읽는다(`READ_FULL_AFTER_PROBE`). 활성 창이 대화상자 창이면 탐색 없이 전체(작음).
  - 안전 판단: 버튼 찾기(`CloseButtonFinder`)·B 분류에 넘기는 글(`SimplePopupAnalysis`)·이미지 광고 힌트·기억(`PopupMemory`)은 원래도 **팝업 루트 안 노드만** 썼다. 그래서 하위 트리만 읽어도 결과가 같다. 시험(`PopupProbeTest`)으로 6개 덤프(11번가·옥션·올리브영·유튜브·크롬 권한·"팝업 밖에 결제·닫기 버튼이 있는 페이지")에서 루트·후보·금지 수·글·지문·B 분류·사기 판단·힌트가 전체 읽기와 같음을 확인. #65 패키지·민감 앱 검사는 `SimpleCollectionGuard`에서 읽기 전에 그대로 돈다.
  - 로그: `READ_POPUP_SUBTREE`/`READ_FULL`/`READ_FULL_AFTER_PROBE`, `TREE_PROBE_VISITED`, `TREE_VISITED`(탐색+수집 합), `TREE_READ_MS`.
  - 한계: 모달이 페이지 앞쪽에 있으면(앞쪽 형제가 화면 안에 많으면) 200노드 안에 못 찾고 전체를 읽어 **오히려 약 0.3초 늘어난다**. 팝업 없는 페이지의 읽기(재확인 체인)도 탐색분만큼 늘어난다. 실측에서 `READ_FULL_AFTER_PROBE`가 많으면 상한·순서를 조정.
- **F15-2 닫힌 뒤 포커스 되찾기 (시연 모드 6-0, 스위치 `FOCUS_RECLAIM_AFTER_CLOSE`):**
  - 누른 순간(`CLICKED`)부터 닫힘 확인 뒤 `FOCUS_RECLAIM_WINDOW_MILLIS`(1.5초)까지 TalkBack 포커스 이벤트를 지켜본다. 페이지가 포커스를 **옛 팝업 영역·글자·자리 안**이나 **사라진 노드**(노드 없음·안 보임·크기 0)로 옮기면 그 자리에서(지연 없이) 우리가 정한 페이지 대상(누르기 전 옮긴 툴바/맨 위, 닫힌 뒤 옮긴 대상)으로 되돌린다. 그 밖의 페이지 항목으로 옮긴 것은 그대로 둔다(`FocusReclaimRules`).
  - 상한 `FOCUS_RECLAIM_MAX`=3회. 사용자 터치·제스처 시작 이벤트(`typeTouchInteractionStart`·`typeGestureDetectionStart`·`typeTouchExplorationGestureStart`, 서비스 설정 xml에 추가, 좌표·내용은 안 읽음)가 오면 즉시 멈춘다.
  - 0.3초 대기(F14)는 스위치가 켜져 있으면 쓰지 않는다. 결과 문장은 마지막 되찾기 뒤 0.6초 조용해지면(`FOCUS_RECLAIM_QUIET_MILLIS`) TalkBack 큐(결과 카드)로 보낸다.
  - 대상 노드는 우리가 포커스를 준 노드의 복사본을 서비스 안에서만 최대 몇 초 들고 있다가 버린다(다른 모듈로 넘기지 않음).
  - 로그: `FOCUS_RECLAIMED=n`(끝날 때), `FOCUS_RECLAIM_STOPPED_BY_USER`, `FOCUS_RECLAIM_LIMIT`, `FOCUS_RECLAIM_NO_TARGET`.
  - 한계: TalkBack이 이미 말하기 시작한 한두 음절은 들릴 수 있다. 1.5초 뒤 페이지가 옮기면 못 잡는다. 결과 카드 뒤에 되찾기가 일어나면 카드 낭독이 끊길 수 있다. 끄려면 스위치 한 줄.
- 검증(F13~F15 + #66): 5개 Gradle 작업 통과, 단위 시험 debug 1115개·release 1100개 실패 0, lintDebug 오류 0(경고 12), INTERNET 0·새 권한 없음. 시험 추가: 탐색·하위 트리 안전 동일성 8(`PopupProbeTest`), 되찾기 규칙 3(`PopupMemoryTest`).

### 8-9. F16~F19 — 재호 로드맵 11/1 (강력 모드, 말 줄이기, 영상 광고, 설정 안내) (JVM 시험만, 실기기 미검증)

기준 `9101572`(F15 + #66, push됨). 기능마다 커밋. 안전 결정은 그대로: 이름 없는 X는 처음에 묻는다(6-3), 결제·동의·구독·삭제·권한 버튼이 같이 있으면 묻는다, 민감 앱(#65) 그대로, 그런 버튼 자체는 절대 안 누른다. 사기 의심은 경고만. core 계약(`core/model`)은 바꾸지 않았다.

- **F16 강력 모드 ("전부 닫기" + "자동 닫기" 스위치일 때, 시연 모드 6-0 = F7 확장, 스위치 `STRONG_CLOSE_ALL`):**
  - 분류와 상관없이(광고·쿠폰·공지·판단 불가) 닫는다. 조건: 위험 버튼 0개(`SimpleLabelRules.isRisky` = 계약의 결정 단어 + 주문/order), 이름 있는 닫기 1개 이상, 사기 의심 아님, USER_DECISION 아님, 누르기 직전 재확인. 닫기가 여럿이면 가장 명확한 것(`StrongCloseRules`: "닫기"/"Close" → "○○ 닫기" → "오늘 하루 보지 않기"·"모바일 웹으로 볼래요" → "괜찮아요"·"다음에", 같으면 읽는 순서 첫 번째).
  - "앱에서 열기"·"설치"·"쿠폰 받기" 같은 이동 단어 버튼은 절대 누르지 않지만, 그것 때문에 묻지는 않는다(F7은 금지 단어 하나라도 있으면 물었다). 다른 프리셋(광고만 닫기 등)은 그대로.
  - **모든 프리셋 공통(안전 쪽으로 정함):** 위험 버튼이 하나라도 있으면 묻는다(`BLOCKED_RISKY` → `DECIDED_ASK`). 예전에는 광고로 분류되면 위험 버튼이 있어도 닫기 1개면 자동이었다.
  - 팝업 찾기 넓히기(`FIND_FRONT_LAYERS`, `FIND_APP_POPUP_WINDOWS`):
    - **앞 레이어 / 아래 시트**(`FRONT_LAYER`/`BOTTOM_SHEET`): 대화상자 표시도 dim 노드도 없는 웹 레이어·aria-modal 상자(크롬이 표시 안 한 것)·하단 시트. 조건 모두: 앞쪽 형제(페이지 본문)가 그 상자의 절반 이상을 덮음(= 위에 그려짐), 크기 화면 18~96%(시트는 전체 폭·아래 끝 붙음·10% 이상), 안에 이름 있는 닫기, 메뉴 아님(옆 서랍·위 드롭다운·클릭 12개 이상), 쿠키 띠 아님("쿠키"/"cookie"). 본문 구역은 겹치지 않고, 고정 바·떠 있는 버튼은 작아서 안 걸린다.
    - **앱 자체 팝업 창**(`APP_WINDOW`): 포커스를 안 가져간 Dialog·PopupWindow(같은 앱의 TYPE_APPLICATION 창이 활성 창 위 레이어, 화면보다 작음)를 그 창의 트리로 읽는다. 재확인·클릭·닫힘 확인도 그 창 id로(`PopupRoot.windowId`).
    - F15 탐색(`PopupProbe`)도 앞 레이어를 찾는다(`ProbeHitKind.LAYER`): 끝 자식부터 내려가다 앞쪽 형제가 큰 상자 밑에 깔린 것을 보면 **그 형제(페이지) 안으로 들어가기 전에** 멈추고, 그 상자 하위 트리 + 밑의 형제 노드 1개만 읽는다(파인더가 "위에 그려짐"을 확인하도록). dim의 카드면 예전처럼 dim 부모.
  - 닫기 이름 넓히기(`demoLabelRulesV3`, 이 엔진 규칙만, 계약 v1은 그대로): "오늘 하루 보지 않기", "오늘 그만 보기", "다시 보지 않기", "N일간 보지 않기", "괜찮아요", "다음에", "나중에", "그냥 볼게요", "웹으로 계속", "아니요, 괜찮습니다", "No thanks", "Not now"(Close·Skip·모바일 웹으로 볼래요는 원래 됨). **정확히 그 문구 전체일 때만**("오늘 하루 무료 체험"은 여전히 금지). "확인"/"OK"는 제외, "앱에서 열기"/"설치"/"받기"는 절대 닫기 아님.
  - **"×" 한 글자는 이름이 아니라 모양으로 본다(안전 쪽으로 정함):** 이름 없는 X와 같이 자동으로 안 누르고, 사용자에게 묻는다.
  - 못 닫으면 반드시 묻기: 팝업을 찾고 자동으로 못 닫으면(위험 버튼, 애매한 버튼, 이름 없는 X, 닫기 없음) 선택 창. 누를 이름 있는 버튼이 없고 X 모양 버튼(작고 거의 정사각형, 이름 없음 또는 X 기호뿐)이 **딱 1개**면 "'X 모양 닫기' 버튼 눌러 주기"를 맨 위에 넣는다(`X_BUTTON_OFFERED`, `OFFER_X_BUTTON`). 사용자 탭으로만 1회 누른다. 위험 버튼이 있거나 X 모양이 2개 이상이면 넣지 않는다.
  - 시험용 앱: `:popup-test-app`은 D(동준) 모듈이라(정답지·`AnswerKeyPipelineTest` 연결) 이번에는 고치지 않았다. 대신 같은 유형을 JVM 트리로 만들어 기대 동작을 시험했다(`StrongCloseTest`: 덮개, 시트, 표시 없는 큰 레이어, 네이티브 Dialog 창, 이름 없는 X, "×", X 2개, 위험+닫기, "앱에서 열기/모바일 웹", 오탐 4종(본문·고정 바·서랍·드롭다운·링크 많은 상자·쿠키 띠), 탐색 동일성). 기존 P01·P02·P03·P04·P10·P13·P16·P17로 실기기 확인 가능. 시트·표시 없는 레이어·앱 열기 시트는 D에게 추가 요청.
  - 로그: `DETECTED_FRONT_LAYER`/`DETECTED_BOTTOM_SHEET`/`DETECTED_APP_WINDOW`, `READ_APP_WINDOW`, `BLOCKED_RISKY`, `DECIDED_AUTO_CLOSE_STRONG`, `X_BUTTON_OFFERED`.
  - 한계: 앞 레이어는 이름 있는 닫기가 있어야 찾는다(이름 없는 X만 있는 웹 레이어는 못 찾음). 화면 위 15% 안에서 시작해 아래 끝까지 안 가는 큰 상자는 드롭다운으로 보고 버린다(위쪽에 붙은 큰 모달을 놓칠 수 있음). 앱 창 읽기는 크롬 밖 앱에서만 의미 있다(실기기 미검증).
- **F17 TalkBack 말 줄이기 (시연 모드 6-0):**
  - 우리 창 첫 포커스: 기다리는 로직은 없었다(한 목소리 모드는 창이 창 목록에 오르면 바로 첫 항목에 포커스). 다만 10회 × 100ms로 다시 시도해 첫 시도 실패 시 0.1초씩 늦었다 → **40회 × 25ms**(같은 1초 상한)로 바꿔 창이 목록에 오르는 즉시 가져오게 했다(`TalkBackTakeoverFeature`).
  - 되찾기 감시 시작: 사용자가 우리 창의 [닫기]를 고른 순간(우리 창이 사라지기 직전)부터 켠다. 자동 닫기는 누르기 직전. 우리 창을 고른 두 번 탭의 터치 이벤트가 늦게 와서 감시를 바로 끄지 않도록 시작 후 0.8초 터치는 무시(`FOCUS_RECLAIM_TOUCH_GRACE_MILLIS`). 되돌릴 대상이 아직 없으면 그 자리에서 페이지 대상을 잡는다(`FOCUS_RECLAIM_TARGET_TAKEN`).
  - **결과 카드가 포커스를 가져간다**(`RESULT_BANNER_TAKES_FOCUS`): 닫힘 확인 뒤(포커스를 돌봐야 할 때만: 사용자 누름, 또는 포커스가 팝업 안이었을 때) 페이지 항목 대신 결과 카드(`TalkBackResultBanner`)에 접근성 포커스를 준다 → TalkBack이 하던 말을 끊고 "쿠폰을 닫았어요"만 읽는 것이 목표. 이때는 live region을 쓰지 않는다(두 번 읽힘 방지). 포커스가 거부되면 live region으로 말한다(`RESULT_BANNER_FOCUS_FAILED`). 되찾기 감시는 이 카드로 되돌린다. 카드가 사라질 때(6초) 아직 포커스가 카드에 있으면 먼저 페이지 위쪽(툴바)으로 옮긴다(옛 팝업 자리로 떨어지지 않게).
  - 누르기 전 이동은 꼭 필요할 때만: 자동 닫기는 포커스가 팝업 안일 때만(원래대로), 사용자 누름은 우리 창이 포커스를 가진 채 사라지므로 그대로 1번.
  - 옥션 예상: 누르기 전 툴바(1) → 카드 "쿠폰을 닫았어요"(2). 예전 3번(툴바·제목·되찾기)에서 줄어듦. 포커스가 팝업 밖이었으면 카드는 포커스 없이 TalkBack 큐로(예전과 같음).
  - 로그: `RESULT_BANNER_FOCUSED`/`RESULT_BANNER_FOCUS_FAILED`, `FOCUS_RECLAIM_TARGET_TAKEN`. 되돌리기: `RESULT_BANNER_TAKES_FOCUS = false`(F15 방식).
  - 한계: 우리 창 밖의 우리 뷰에서 요청한 접근성 포커스를 TalkBack이 따라가는지는 실기기 확인 필요. 카드가 포커스를 가진 6초 동안 사용자가 오른쪽으로 쓸면 다음 창으로 넘어간다.
- **F18 영상 광고 건너뛰기 (F5, `VIDEO_AD_SKIP`, 실기기 미검증):**
  - 화면에 이름이 분명한 건너뛰기 버튼("건너뛰기", "광고 건너뛰기", "Skip", "Skip ad", "Skip ads")이 **딱 1개** 있고, 이름 자체나 화면 어딘가에 광고 표시("광고", "Ad", "Sponsored", "스폰서")나 skip_ad 뷰 id가 있을 때만 누른다(`VideoAdSkip`). 광고 표시가 없는 튜토리얼 "건너뛰기"는 안 누른다. 위험 단어가 섞인 이름, 꺼진 버튼, 화면 밖 버튼은 안 누른다.
  - 그 노드 자체만 1회 클릭(조상 노드·플레이어로 올라가지 않음). 구독·구매·링크 등 다른 버튼은 후보가 아니다. 같은 버튼은 10초 동안 다시 안 누른다(재시도 없음).
  - 광고를 닫는 프리셋(사용자 동의가 살아 있는 `autoCloseKinds`에 광고 포함)에서만. 아니면 `VIDEO_SKIP_NOT_IN_PRESET`, 아무것도 안 함. 도와줄 앱 범위·#65는 읽기 전에 그대로.
  - 지연 광고: "5초 후 건너뛰기" 같은 카운트다운이 보이면 `VIDEO_SKIP_WAITING`으로 읽기를 "못 찾음"으로 끝내 재확인 체인(0.2·0.5·1·2·3초)과 플레이어의 화면 변경 이벤트(1.5초에 1번)로 다시 읽는다. 자체 폴링 없음.
  - 버튼이 사라진 것을 확인한 뒤 "광고를 건너뛰었어요" 1번(`ui_video_ad_skipped`), TalkBack 큐(포커스 안 옮김, 영상 보는 중이므로) 또는 TTS.
  - 로그: `VIDEO_SKIP_FOUND` → `CLICKED` → `VIDEO_SKIPPED` → `RESULT_VIA_TALKBACK`/`_TTS`, 못 하면 `NOT_CLOSED`.
- **F19 설정 안내:** 도와줄 앱을 아직 안 골랐으면(#65 이후 방식 없음·앱 0개 = 엔진이 아무것도 안 함) 설정 맨 위에 "아직 도와줄 앱을 고르지 않아서 지금은 어떤 앱의 팝업도 찾지 않아요." + [도와줄 앱 고르기](`HelpAppsStatusPlan.unset`).
- **시연 모드(6-0) 완화 범위 정리 (최종 발표 뒤 배포 시 모두 끔):** F4·F13 누르기 전 포커스 이동, F6 이미지 대화상자 광고 힌트, F7 판단 불가 자동 닫기, F9·F14 닫힌 뒤 포커스 이동, F15 되찾기, **F16 강력 모드·앞 레이어/시트/앱 창 찾기·닫기 이름 v3·X 모양 버튼 묻기**, **F17 결과 카드 포커스·첫 포커스 빠르게**, **F18 영상 광고 자동 건너뛰기**, TalkBack 안내 맡기기(`TalkBackTakeoverFeature.DEFAULT_ON`). 스위치는 모두 `SimpleCloseEngineFeature` 상수 한 줄. 끝까지 지키는 것: 결제·구매·동의·권한·구독·삭제 버튼 안 누름, 사기 의심 경고만, 화면 글자·패키지 로그 금지, 확인 안 된 동작 보고 금지.
- 검증(F16~F19): 5개 Gradle 작업 통과, 단위 시험 debug 1143개·release 1128개 실패 0, lintDebug 오류 0(경고 14, 새 2개는 결과 카드 포커스 `AccessibilityFocus` = F17 의도), INTERNET 0·새 권한·새 라이브러리 없음. 시험 추가: `StrongCloseTest`, `VideoAdSkipTest`, 결정·라벨 시험 갱신, `HelpAppsStatusPlanTest` 1.

### 8-10. F20 — F16~F19 실측(11/1 06:22~06:29, `6d797fe`) 뒤 (JVM 시험만, 실기기 미검증)

실측: 옥션·올리브영 앱 시트(`DETECTED_BOTTOM_SHEET` 0.46초 → `DECIDED_AUTO_CLOSE_STRONG` → 닫힘, TalkBack 흐름 완벽)·크롬 링크 안 대화상자는 정상(회귀 시험 `F20RegressionTest`로 고정). 문제 셋.

- **11번가 시트 — 거짓 "닫힘"과 다시 누르기 (문제 1):**
  - 분석: `CLICKED` 0.27초 뒤 `CLOSED_CONFIRMED`는 시트 노드가 그 경로·크기에서 잠깐 사라진 것(움직임·다시 그림)이었다. 0.4초 뒤 같은 시트가 **다른 경로·크기**로 다시 잡혔고, 쿨다운 키(`coarseKey` = 경로+크기)가 달라 쿨다운을 그대로 지나 **한 번 더 눌렀다**(사실상 재시도). 그 사이 결과 카드가 포커스를 가져가고 되찾기도 했다. 이어서 `NOT_CLOSED` 안내(`CfySpeech RESULT len=22`)가 나왔다. 그 뒤 3초마다 `SAME_POPUP_COOLDOWN`만 찍혔다(그때 우리는 포커스·낭독을 건드리지 않았다). TalkBack 반복 낭독은 시트가 그대로 남은 상태에서 거짓 성공 뒤 카드·되찾기·두 번째 누름이 포커스를 흔든 결과로 본다.
  - (a) 강력 모드 선택 = 선택 창이 맨 먼저 보여 주는 버튼. 후보를 명확한 순서(`clarity`: "닫기"·"○○ 닫기"(레이어/시트 자체 닫기) → 배경·"오늘 하루 보지 않기"·"모바일 웹으로 볼래요" → "괜찮아요")로, 같으면 읽는 순서로 정렬해(`CloseButtonFinder`) 선택 창과 강력 모드가 같은 1번을 쓴다. 11번가는 "앱설치 안내 레이어 닫기"(시트 안, 앱 열기 버튼은 후보 아님). 실측에서 이 버튼이 실제로 안 닫는 것으로 나오면 (c)로 묻게 된다.
  - (b) 엄격한 닫힘 확인(`STRICT_CLOSE_CHECK`): 루트가 사라져도 0.6초 뒤 다시 읽어 **같은 팝업**(옛 자리와 겹치고 같은 글자/자리 노드: `PopupMemory.reappeared`)이 있으면 `CLOSE_REAPPEARED` → 닫힘 아님. 결과 카드·카드 포커스·되찾기 마무리는 진짜 닫혔을 때만.
  - (c) 실패 처리: 자동으로 누른 뒤 안 닫히면(`NOT_CLOSED`·재출현) 다시 누르지 않고, 카드·포커스 이동·되찾기 없이 **바로 선택 창**(`AUTO_CLOSE_FAILED_ASKED`): 첫 줄 "대신 닫지 못했어요."(`ui_choice_summary_auto_failed`), 실패한 버튼은 누르기 선택지에서 빼고 [버튼으로 이동]으로만, 다른 닫기 버튼·[내용 다시 읽기]·[그대로 두기]. 그 팝업(글자 지문 기준, 경로·크기와 무관)은 이번 실행 동안 자동으로 다시 누르지 않는다(`AUTO_CLOSE_SKIPPED_AFTER_FAIL` → 묻기). 자동 누르기가 진행 중인 같은 팝업이 다시 잡혀도 아무것도 안 한다(`pressing`). [그대로 두기]를 고른 팝업은 남아 있는 동안 다시 묻거나 누르지 않는다(`SAME_POPUP_KEPT`).
  - (d) 탐색이 시트를 못 찾은 이유: 시트 **뒤**(마지막 자식 쪽)에 높이 0 바닥글이 있고 그 안에 높이 0 링크 수백 개가 있었다(10/30 덤프의 높이 0 "로그인"·"당첨자공지"). 탐색은 끝 자식부터 들어가므로 바닥글을 다 돌다 200노드 상한에 걸렸다(`READ_FULL_AFTER_PROBE`, 2.3초). 보완: 크기 0인 하위 트리는 크기 있는 형제를 다 본 뒤에 들어간다(크기 0 포장 안의 모달도 여전히 찾음). 로그 `PROBE_HIT_DIALOG`/`_LAYER`/`_DIM_PARENT`/`PROBE_MISS_CAPPED`/`PROBE_MISS`로 왜 전체를 읽었는지 바로 보인다.
- **결정 팝업도 묻기 (문제 2, 재호 "못 닫으면 반드시 묻기"):** `DECIDED_DENY_DECISION` → 안내만 하던 것을 선택 창으로(`DECISION_ASKED`). 대신 누르기 선택지 없음. 결제·동의·권한 등 위험 버튼은 [버튼으로 이동]만(최대 3개, `ButtonScan.riskButtons`), [내용 다시 읽기], [그대로 두기]. [버튼으로 이동]: TalkBack 켜짐 → 그 버튼에 접근성 포커스만(`MOVED_TO_BUTTON`, 누르지 않음), 꺼짐 → TTS "'허용' 버튼은 화면 아래쪽에 있어요."(`MOVE_POSITION_SPOKEN`). 위험 버튼이 있어 묻는 일반 팝업에도 같은 [버튼으로 이동]이 붙는다. TalkBack이 꺼져 있으면 설정과 상관없이 선택 창을 우리 TTS로 읽는다. 사기 의심은 그대로 경고만.
- **유튜브 진단 (문제 3, 실기기 미검증):**
  - 이벤트: 유튜브 앱 이벤트가 오면 5초에 1번 `VIDEO_EVENT_SEEN`, 도와줄 앱 범위 밖이면 `VIDEO_APP_NOT_ALLOWED`(패키지 원값은 안 남김).
  - 읽을 때(광고 표시·건너뛰기 비슷한 노드가 있거나 유튜브 앱일 때만): `VIDEO_AD_MARKER_FOUND`/`_MISSING`, 그리고 `VIDEO_SKIP_FOUND` / `VIDEO_SKIP_WAITING` / `VIDEO_SKIP_NO_CANDIDATE`(광고는 있는데 건너뛰기 노드 없음) / `VIDEO_SKIP_LABEL_UNNAMED`(skip_ad 뷰 id만 있고 이름·클릭 없음) / `VIDEO_SKIP_NOT_CLICKABLE` / `VIDEO_SKIP_OFFSCREEN` / `VIDEO_SKIP_NO_AD_MARKER` / `VIDEO_SKIP_SEVERAL` / `VIDEO_SKIP_NOT_IN_PRESET`.
  - 찾기 보강: 글자 대신 contentDescription이나 **뷰 id만**(`skip_ad_button` 등, `FLAG_REPORT_VIEW_IDS` 켜짐)으로 나오는 클릭 가능한 건너뛰기도 후보. 유튜브 앱은 F15 하위 트리 탐색 없이 항상 전체를 읽는다(건너뛰기가 팝업 밖에 있어 하위 트리만 읽으면 못 봄). 유튜브 웹(크롬)도 같은 경로.
  - 점검: 이벤트 필터(`WINDOW_EVENTS`)와 `SimpleAppAllowance`에 유튜브를 막는 규칙은 없다(민감 앱·시스템 목록 아님). 도와줄 앱이 "고른 앱"이고 유튜브가 빠져 있으면 `VIDEO_APP_NOT_ALLOWED`로 보인다.
- 검증(F20): 5개 Gradle 작업 통과, 단위 시험 debug 1154개·release 1139개 실패 0, lintDebug 오류 0(경고 14, F19와 같음), INTERNET 0·새 권한·새 라이브러리 없음. 시험 추가 `F20RegressionTest` 11.

### 8-11. F21 — F20 실측(11/1 06:48~06:53, `8dd26e4`) 뒤 (JVM 시험만, 실기기 미검증)

실측: 옥션(0.29초), 올리브영 앱 덮개(`DETECTED_DIM_OVERLAY` → STRONG, "완벽"), 유튜브(`VIDEO_AD_MARKER_MISSING` 몇 번 뒤 `VIDEO_SKIP_FOUND` → `VIDEO_SKIPPED`) 성공. 11번가는 첫 자동 닫기 실패 → 실패 선택 창 → 두 번째 선택 창에서 "모바일 웹으로 볼래요"로 닫힘, 결과까지 2.9초, 닫힌 뒤 팝업 재낭독 2번.

- **로그 해석:** 실패 선택 창의 "'닫기' 버튼 눌러 주기"는 실패한 버튼이 아니라 **"모바일 웹으로 볼래요"**였다(버튼이 하나뿐이면 긴 이름을 역할 이름 '닫기'로 줄여 보여 주는 10/31 규칙). 실제로 실패한 버튼은 시트 안 **"앱설치 안내 레이어 닫기"**(실패 창에서 [버튼으로 이동]으로 보인 것). 즉 이 X는 접근성 클릭으로 시트를 닫지 못하고, "모바일 웹으로 볼래요"는 닫는다. 그 선택이 `RECHECK_TARGET_GONE`이 된 것은 첫 클릭 뒤 시트 트리가 바뀌어 같은 경로·이름이 아니었기 때문(안전 재확인이 정상 동작).
- **결과 지연 원인:** F20 엄격 재확인이 0.6초 기다린 뒤 **페이지 전체를 다시 읽었다**(팝업이 사라지면 탐색이 못 찾아 전체 읽기, 11번가 약 2.3초, 올리브영 약 1.3초). `CLOSED_CONFIRMED`는 재확인 전에 찍혀 그 시간이 결과까지의 지연으로 보였다. 우리 창 닫기·TalkBack 대기·STOP을 기다린 것은 아니다.
- 고침:
  1. 라벨: 닫기 동사로 끝나면 앞의 "설치"·"다운로드"는 닫는 대상의 이름으로 본다("앱설치 안내 레이어 닫기", "다운로드 안내 닫기"는 닫기, 원래도 v2로 닫기였음). "설치하고/설치 후/설치 및 닫기"처럼 실행 의미면 금지, "설치하기"·"앱 설치"·"앱에서 열기"·"다운로드"는 그대로 금지. core `ButtonLabelRules`는 안 바꿈.
  2. 순서: 앱 설치 시트의 "웹으로 볼래요/볼게요/계속" 답을 맨 앞에(실측에서 이것만 시트를 닫음), 그다음 "닫기"·"○○ 닫기", 그다음 배경·"오늘 하루 보지 않기", 마지막 "괜찮아요"(`clarity`). 선택 창 첫 버튼과 강력 모드가 누르는 버튼은 계속 같다.
  3. 루트 우선순위: 탐색이 앞 레이어를 보면 멈추지 않고 기록만 하고 계속 찾는다. 상한 안에서 Dialog가 나오면 Dialog가 이긴다(파인더는 원래 Dialog 우선). 11번가는 `PROBE_HIT_DIALOG` → 하위 트리 읽기(0.5초 이하 목표).
  4. 실패한 버튼 빼기: 같은 팝업(글자 지문)에서 눌러도 안 닫힌 버튼은 그 실행 동안 선택지에서 아예 뺀다(이동 선택지로도 안 넣음). 사용자가 고른 버튼이 안 닫았을 때도 같음: 안내만 하지 않고 바로 실패 선택 창(`USER_PRESS_FAILED_ASKED`)에 다음 버튼을 "눌러 주기"로. 실패 선택 창은 버튼이 하나여도 버튼 자기 이름을 그대로 읽는다.
  5. 결과 지연: 엄격 재확인을 **탐색만**으로(팝업을 못 찾으면 전체 읽기 없이 "닫힘"), 대기 600 → 350ms. `CLOSED_CONFIRMED` → 결과 0.7초 이하 목표, `CLOSE_RECHECK_MS`로 확인. 재확인 중에는 우리 쪽 낭독을 시작하지 않는다(코드 확인: TTS·카드는 재확인 뒤에만, 선택 창은 이미 닫혀 말을 멈춤). 되찾기 감시는 CLICKED(사용자 누름은 우리 창이 사라질 때)부터 결과 카드 포커스 뒤 1.5초까지 그대로 켜져 있다(터치 시 중단).
- 한계: 탐색이 팝업을 못 찾는 사이트(200노드 안에 없음)에서는 재확인이 "닫힘"으로 본다(F19 이전과 같은 수준). 실패한 버튼 기억은 메모리에만, 서비스가 끝나면 지움.
- 검증(F21): 5개 Gradle 작업 통과, 단위 시험 debug 1158개·release 1143개 실패 0, lintDebug 오류 0(경고 14), INTERNET 0·새 권한·새 라이브러리 없음. 시험 추가 `F21RegressionTest` 4, 순서 시험 갱신.

### 8-12. F22 1단계 — 앱 팝업·이름 없는 X 기억·영상 오탐 (재호 앱 실측 11/1 06:57~07:03, `8dd26e4`) (JVM 시험만, 실기기 미검증)

- **A. 앱 팝업 창이 5노드만 보인 이유(분석):** F16 앱 창 읽기는 활성 창 위의 같은 앱 TYPE_APPLICATION 창 중 **맨 위 것**을 무조건 팝업으로 읽었다. 5노드짜리는 떠 있는 작은 버튼·말풍선 같은 작은 창으로 보인다. 그 창을 팝업으로 잡아 누를 것이 없었고(`CANDIDATE_NONE`), 진짜 팝업이 있는 본 창은 읽지 않았다. 21초 동안 19번 읽은 것도 이 때문이다.
  - 고침: 앱 창은 그 자체로 팝업일 때만 쓴다(`AppWindowRules`: 화면 10% 이상, 팝업 루트가 있고, 이름 있는 닫기·건너뛰기, X 모양 버튼, 위험 버튼 중 하나가 있음). 아니면 `APP_WINDOW_IGNORED`를 남기고 본 창을 예전처럼 읽는다. 위 창이 여럿이면 위에서부터 차례로 본다.
  - 앱 창 안이 WebView라 자식이 안 보이는 경우는 이 수정으로 풀리지 않는다(실측 로그로 확인 필요).
- **A. 11번가 앱 가운데 모달(`MODAL_CARD`):** 어두운 배경 + 흰 상자 + 이름 없는 X + "혜택 받으러 가기", 로그는 `NO_POPUP_ROOT`뿐이었다. dim·앞 레이어 규칙은 둘 다 **이름 있는 닫기**를 요구해서 못 찾았다. 새 규칙에서 상자 조건은 모두 만족해야 한다.
  - 크기: 화면 8~70%, 가운데(중심이 화면 중심에서 10% 안), 양옆 끝에 붙지 않음, 상자 자체는 클릭 불가.
  - 덮개 위: 읽는 순서상 앞에 있고 조상이 아닌 노드가 상자를 다 덮고 화면 60% 이상.
  - 제외: 클릭 12개 미만, "쿠키" 없음.
  - 닫기 근거: 이름 있는 닫기, 또는 **위쪽 모서리의 작고 거의 정사각형인 이름 없는(또는 X 기호뿐인) 클릭 노드가 딱 1개**.
  - X는 절대 자동으로 누르지 않는다(6-3). 선택 창에 "'X 모양 닫기' 버튼 눌러 주기"로 묻는다. "혜택 받으러 가기"는 후보가 아니다. 아래쪽 작은 띠 배너는 화면 8% 미만이라 팝업이 아니다(유지).
- **A. 범위 진단:**
  - 옥션 앱(`com.ebay.kr.auction`)·11번가 앱·쿠팡 앱은 #65 민감 앱 규칙(패키지·앱 이름 키워드)에 **걸리지 않는다**(시험 `scopeMissReasons`).
  - `SKIPPED_NOT_ALLOWED_APP` 대신 사유 코드 `SKIPPED_SCOPE_OWN_APP/SYSTEM/INPUT_METHOD/USER_EXCLUDED/SENSITIVE/NOT_CHOSEN/NO_MODE_NOT_ALLOWED/NO_PACKAGE`를 남긴다.
  - `TREE_UNAVAILABLE` 뒤에는 `TREE_UNAVAILABLE_OTHER_APP/NO_ROOT/SENSITIVE/LABEL_UNKNOWN/NOT_ALLOWED`를 남긴다. 패키지 원값은 남기지 않는다.
- **B. 이름 없는 X + 기억 (AGENTS 6-3·6-4, `REMEMBER_X_ALWAYS`):** 이전에는 simpleclose에 기억이 연결돼 있지 않았다(선택 창의 기억 옵션은 꺼져 있었고, D의 Room 저장소는 Mock).
  - 선택지: X를 묻는 선택 창에 "'X 모양 닫기' 버튼 눌러 주기"와 "앞으로 항상 닫기('X 모양 닫기')"를 같이 낸다(`REMEMBER_OFFERED`).
  - 저장: 앞으로 항상을 고르면(REMEMBER_EXACT_POPUP + buttonMeaningConfirmed + repeatAutomationExplicitlyAllowed) 한 번 누르고, **엄격 닫힘 확인을 통과했을 때만** 저장한다(`REMEMBERED_SAVED`).
  - 저장 내용: C 자체 SharedPreferences(`ui_simple_remembered_popups`, 최대 50개)에 앱·팝업 글자·구조의 해시, 루트 자리, 버튼 경로와 자리만 둔다. 글자·패키지 원문은 저장하지 않는다.
  - 다음 만남: 같은 앱·글자·구조, 같은 루트 자리, 같은 경로·자리의 버튼이면 클릭 직전 재확인 뒤 자동으로 닫는다(`REMEMBERED_AUTO_CLOSE`). 결정 팝업·사기 의심·위험 버튼이 있는 팝업·실패한 팝업은 제외하고, 실패하면 다시 누르지 않고 묻는다(F20). Room(D)은 바꾸지 않았다. 목록 보기·해제는 2단계 E에서 한다.
- **C. 영상 오탐:** 영상 광고 경로는 유튜브 앱, 주소창이 youtube.com인 브라우저(주소는 메모리에서만 봄), skip_ad 뷰 id가 있는 화면에서만 돈다(`VideoAdSkip.isVideoContext`). 쇼핑 앱 배너의 "AD"로는 돌지 않는다.
- 검증(F22 1단계): 5개 Gradle 작업 통과, 단위 시험 debug 1165개·release 1150개 실패 0, lintDebug 오류 0(경고 14), INTERNET 0·새 권한·새 라이브러리 없음. 시험 추가 `F22RegressionTest` 7, `StrongCloseTest` 1개 기대 변경(이름 없는 X만 있는 가운데 상자 → `MODAL_CARD`, 묻기만).
- 커밋: 서비스 파일 한 곳을 세 기능이 함께 고쳐 1단계를 커밋 1개로 묶었다.

### 8-13. F22 2단계 — 감지·재확인 속도, 브라우저 말 줄이기, 미연결 부품 (총괄 자동 확인 `d3b0685` 뒤) (JVM 시험만, 실기기 미검증)

자동 확인(adb, 20초): 옥션 웹 통과, 단 `CLOSE_RECHECK_MS=625`(F21도 626), `DETECT_LATENCY_MS=4461`(`DETECTED_BY_RECHECK`, F21도 4538). 11번가·옥션·쿠팡·올리브영 앱과 11번가 웹은 오늘 이미 "오늘 하루 보지 않기"로 팝업이 안 떠 판정 불가(1단계 A·B 실기기 미검증). 쇼핑 앱 `VIDEO_*` 0줄, `SKIPPED_SCOPE_SENSITIVE` 0.

- **재확인 500ms 이하:** 기다림 350 → 220ms(`CLOSE_RECHECK_MILLIS`). 재확인 읽기는 이미 하위 트리 탐색 1번만 한다(F21). 남은 시간은 탐색 자체(옥션 약 0.27초)다.
- **첫 감지 4.5초:** 페이지가 뜬 뒤 팝업은 조금 늦게 나온다. 그사이 재확인 체인의 다시 읽기(0.2·0.7·1.7·3.7초)가 팝업이 없을 때마다 **페이지 전체를 읽었다**(0.7~2초). 그래서 다음 읽기가 늦고 드물었다.
  - 고침: 팝업을 못 찾은 재확인·화면 변경 읽기는 탐색만 하고 끝낸다(싸다, 약 0.3초). 창 이벤트 읽기, TalkBack 포커스 읽기, 한 번의 찾기에서 3번째마다(`WHOLE_READ_EVERY`)는 예전처럼 전체를 읽는다(탐색 200노드 밖 팝업 대비).
  - 대신 촘촘하게: 재확인 체인 200·400·600·800·1000·1500·2000·3000ms, 화면 변경으로 읽는 간격 1.5 → 0.7초.
  - 목표: 팝업이 뜬 뒤 1초 안팎에 감지(실기기 확인 필요).
- **D. 브라우저 말 줄이기("올리브영 수준"):** 자동 닫기에서 포커스가 팝업 안일 때, 누르기 전에 툴바로 옮기던 것을 결과 카드 포커스가 켜져 있으면 하지 않는다(`AUTO_PRE_CLICK_MOVE_WITH_BANNER=false`). TalkBack이 툴바 이름을 읽은 뒤 결과를 말하던 것이 결과 한 마디로 줄어드는 것이 목표다. 되찾기 감시는 그대로 누를 때부터 켜져 있고, 페이지가 포커스를 옛 팝업으로 끌면 그 자리에서 페이지 대상을 잡는다(F17). 사용자 누름은 우리 창이 포커스를 가진 채 사라지므로 그대로 둔다.
- **E. 미연결 부품 점검 (C 범위):**

| 항목 | simpleclose 경로 상태 | 이번 처리 |
|---|---|---|
| F10 다시 듣기·중지 | 선택 창의 [다시 듣기]·[그만 말하기] 연결돼 있음(`PromptControlsSupport`). 결과 뒤에는 결과 카드가 TalkBack 포커스를 가져 사용자가 다시 읽을 수 있음 | 없음 |
| F12 기억 목록·해제 | D의 규칙 목록·해제는 설정에 있음(Room, D). 엔진 자체 기억(F22-1)은 설정에 없었음 | 설정 기억 영역에 "팝업처리도우미가 '앞으로 항상 닫기'로 기억한 팝업: N개" + [기억한 팝업 모두 해제] 연결. 해시만 저장해 이름별 목록은 없음 |
| F13 음성 명령 | 선택 창 말하기 버튼 연결돼 있음(`PromptControlsSupport.create`, 말하기 버튼 누를 때만, `VoicePolicy`: 닫기·건너뛰기·읽기·그대로 두기만 바로, "항상"은 화면 탭 확정, 결제·구매·구독·삭제·권한·동의·로그인 거절). 결제·권한 버튼은 선택 창의 [버튼으로 이동](F20)으로 위치 안내 | 없음. 음성 "이동"은 아직 [버튼으로 이동]에 연결 안 됨(아래 남은 것) |
| 앱별 자동 닫기(기본 꺼짐) | `PerAppAutoCloseGateFeature=false`(10/30 시연 모드 결정: 앱별 스위치 없음, 프리셋과 기억 팝업만) | 없음(결정대로) |
| 처음 설정 F1~F8 잔여 | 첫 실행 화면은 [서비스 켜기] 위 설명 문구를 이미 뺐는데(LG 10/31) 설정 화면에는 남아 있었음 | 설정에서도 빼고 쓰지 않게 된 문자열 삭제 |
| release RECORD_AUDIO | release 매니페스트에 없음(음성 명령은 debug 빌드만) | 새 권한이라 넣지 않음, 보고만 |

- **다른 역할 쪽 빈 곳(고치지 않음, 누가 무엇을):**
  - D(동준): Room 규칙 저장소와 엔진 기억 통합 여부, 시험용 앱에 시트·표시 없는 레이어·앱 열기 시트·가운데 모달(X만) 유형 추가.
  - A(우진): 앱 팝업 창 안 WebView 자식이 접근성 트리에 안 나오는 경우가 있는지 확인(F22-1 분석).
  - B(준영): 11번가 시트 "앱설치 안내 레이어 닫기"가 접근성 클릭으로 안 닫히는 이유(웹 요소 구조).
- 남은 것: 음성 "○○ 버튼으로 이동"을 [버튼으로 이동] 선택지에 연결, 결과 뒤 [다시 듣기] 단추(카드 포커스로 대신 중), 기억 팝업을 앱 이름별로 보여 주기(저장 형식 변경 필요).
- 검증(F22 2단계): 5개 Gradle 작업 통과, 단위 시험 debug 1165개·release 1150개 실패 0, lintDebug 오류 0(경고 14), INTERNET 0·새 권한·새 라이브러리 없음. 감지 일정 시험은 상수에서 시각을 계산하도록 바꿈.

### 8-14. F23 — 감지 편차 진단, 음성 이동, 정답지 대조, 유튜브 웹 (총괄 자동 확인 `ebd83ea` 뒤) (JVM 시험만, 실기기 미검증)

자동 확인: 옥션 웹 1회차 `DETECT_LATENCY_MS=1689`·`CLOSE_RECHECK_MS=304`, 2회차 `DETECT_LATENCY_MS=4058`(`DETECTED_BY_RECHECK`)·`CLOSE_RECHECK_MS=308`. 앱 3개는 팝업이 안 떠 판정 불가, 영상 코드 0줄, 오탐 0.

- **안전 관련 변경(맨 위 보고):**
  - 강력 모드에서 "구매·주문" 버튼이 같이 있는 팝업도 이제 묻지 않고 "닫기"를 누른다(정답지 L04~L06·L13 CLOSE_OK, 재호 F16 목록 "결제·동의·구독·삭제·권한"에 구매 없음). 구매·주문 버튼 자체는 여전히 절대 누르지 않고 [버튼으로 이동]으로만 보인다(`SimpleLabelRules.isDecision`).
  - 묻게 하는 버튼(`isRisky`)은 결제·동의·구독·삭제·권한·허용·가입·로그인·계정·개인정보·승인·송금·이체·해지·탈퇴·체험(영어 포함)이다. B가 결정 팝업(USER_DECISION)으로 보면 여전히 묻는다(L03·L17).
- **1. 감지 편차:** 원인은 기기 로그 없이 확정하지 못했다. 가능한 원인은 셋이다. ① 팝업이 페이지보다 늦게 뜸(페이지 탓, `DETECT_LATENCY_MS`는 첫 이벤트부터 잼). ② 재확인 체인 사이 전체 읽기 비용. ③ 로드 중 트리 없음. 다음 실측에서 바로 가를 수 있게 읽기마다 숫자를 남긴다.
  - 읽기마다: `READ_AT_MS`(첫 이벤트부터 이 읽기 시작까지), `READ_PROBE_COST_MS`/`READ_WHOLE_COST_MS`(이 읽기의 비용과 종류).
  - 감지 때: `DETECT_FIRST_READ_MS`, `DETECT_WHOLE_READS`.
  - F22-2의 촘촘한 체인과 탐색만 읽기는 그대로 둔다(1회차 1.7초).
- **2. 음성 "○○ 버튼으로 이동":** "허용 버튼으로 이동", "동의 버튼으로 가", "결제 버튼 어디야", "○○ 버튼 위치"를 알아듣는다(`VoiceUtterance.MoveTo`).
  - 동작: 선택 창의 [버튼으로 이동] 중 이름이 맞는 것 하나를 고른다. 이동 선택지가 하나뿐이면 그것을 고르고, 여럿인데 이름이 없으면 "화면에서 골라 주세요"라고 한다.
  - 이동은 포커스 이동(TalkBack 켜짐)이나 위치 안내(꺼짐)만 하고 아무것도 누르지 않는다. 그래서 결제·권한 버튼도 이름으로 부를 수 있다(CLAUDE.local 3-1 위치 안내까지).
  - "허용해", "결제 눌러"처럼 누르는 말은 그대로 거절한다. "이동하지 마"는 이동이 아니다. 들은 문장은 메모리에서 이번 비교에만 쓰고 남기지 않는다.
- **3. 정답지 대조:** 사이트별 표는 노트 `answer-key-compare-F23-2026-11-01.md`에 있다. 고친 것은 위의 구매·주문 하나다. 보고만 한 것은 셋이다.
  - "오늘 하루 보지 않기": 정답지는 누르지 않는 대상(P25)인데 재호 F16에서 닫기에 넣었다. "닫기"가 있으면 항상 "닫기"를 먼저 누른다.
  - 구독 있는 전면 광고(L12): 정답지는 닫기, 우리는 묻기(안전 결정 유지).
  - 작은 띠 배너(L01·L02): 팝업으로 보지 않는다.
- **4. 유튜브 웹:** m.youtube.com에서는 건너뛰기가 팝업 밖에 있다. 그래서 탐색만 하는 읽기에서는 못 보고, 주소창이 스크롤로 숨으면 유튜브인지도 몰랐다.
  - 고침: 영상 맥락에 "이름 자체가 광고 건너뛰기"인 클릭 노드("광고 건너뛰기", "Skip ad")를 더했다.
  - 고침: 브라우저에서 유튜브를 한 번 보면 그 브라우저의 읽기는 1분 동안 탐색 없이 전체로 한다.
  - 주소창이 없을 때 그냥 "건너뛰기"만으로는 유튜브로 보지 않는다(시험).
- 검증(F23): 5개 Gradle 작업 통과, 단위 시험 debug 1173개·release 1158개 실패 0, lintDebug 오류 0(경고 14), INTERNET 0·새 권한·새 라이브러리 없음. 시험 추가 `F23RegressionTest` 8, 라벨 시험 갱신.

### 8-15. F24 — 읽기 예산(팝업 없는 페이지에서 쉬지 않고 읽던 회귀), 구매 스위치 (총괄 자동 확인 `ef731f0` 뒤) (JVM 시험만, 실기기 미검증)

자동 확인: 옥션 웹 3회 통과(감지 1.7·3.7·4.2초는 팝업이 늦게 뜬 것, `READ_AT_MS`가 0.2~0.9초 간격으로 촘촘했음). **회귀:** G마켓 웹(팝업 없음) 25초에 읽기 25번·`CONTENT_READ_THROTTLED` 45번, 예스24 웹 전체 읽기 3.6초 뒤 0.2초마다 탐색 읽기, 옥션은 닫은 뒤에도 0.2초마다 읽기.

- **원인:** 화면 변경 읽기 고리에 있었다(F13부터 있던 구조가 F22-2의 촘촘한 간격과 겹쳐 드러남). 읽는 동안 화면이 또 바뀌면(캐러셀·애니메이션) 0.2초 뒤 한 번 더 읽고, 그 읽기 중에 또 바뀌면 또 읽는다. 끝나지 않았다. JVM 시뮬레이션(50ms마다 바뀌는 페이지, 읽기 0.3초)으로 같은 현상을 재현했다: 60초 동안 120번, **60%를 읽기에 씀**.
- **1. 읽기 예산(`ReadBudget`):** 팝업을 못 찾은 찾기가 이어질수록 화면 변경 읽기 간격을 0.2 → 0.4 → 0.8 → 1.6 → 3초(상한)로 늘린다. 위 고리 안에서도 같은 간격을 기다린다.
  - 처음으로 되돌리는 것: 창 전환·새 창(`WINDOW`), TalkBack 포커스 이동, 팝업을 찾았을 때. 화면 변경만 반복(캐러셀)되면 되돌리지 않는다.
  - 창 이벤트의 재확인 체인이 도는 동안 오는 화면 변경은 체인의 다음 읽기가 맡는다(체인을 갈아치우지 않음).
  - 시뮬레이션: 같은 페이지에서 **10% 이하**(시험 `ReadBudgetTest`).
  - 진단: 10초마다 `READ_DUTY_PCT=`(그 10초 중 읽기에 쓴 비율).
- **2. 닫은 직후:** 닫힘이 확인되면 예산을 3단계(1.6초)부터 시작한다(`backOff`). 되찾기(1.5초)와 엄격 재확인은 그대로다.
- **3. 전체 읽기 상한:** 전체 읽기가 1초를 넘은 페이지(예스24 3.6초)는 1분 동안 전체 읽기를 창 전환 때와 9번째 읽기마다만 한다(평소엔 3번째마다, TalkBack 포커스 때도). 팝업은 대부분 탐색(끝 자식부터, 크기 0 하위 트리는 나중에)으로 찾는다. 탐색 200노드 밖의 팝업은 창 전환이나 9번째 읽기에서 찾는다.
- **4. 유지:** F23 감지 속도와 재확인(약 300ms)은 바꾸지 않았다(재확인 체인 간격 그대로, 첫 읽기 그대로).
- **구매 스위치 `ASK_WHEN_PURCHASE_BUTTON`(재호 결정 대기):** 기본값 true는 F16~F22처럼 "구매·주문" 버튼이 같이 있으면 묻는다. false는 F23처럼 결제·동의·구독·삭제·권한·허용·로그인 등만 묻고, 구매·주문만 있으면 닫기를 누른다(정답지 L04~L06·L13). 어느 쪽이든 구매·주문 버튼 자체는 누르지 않는다. "오늘 하루 보지 않기"와 L12 구독도 재호 결정 대기다.
- 커밋: 엔진 상수 파일 하나를 두 기능이 같이 고쳐 F24를 커밋 1개로 묶었다.
- 검증(F24): 5개 Gradle 작업 통과, 단위 시험 debug 1179개·release 1164개 실패 0, lintDebug 오류 0(경고 14), INTERNET 0·새 권한·새 라이브러리 없음. 시험 추가 `ReadBudgetTest` 6. 예산 때문에 기다림이 0.2초에서 0.4초가 된 기존 시험 1개 갱신, F23 시험은 스위치를 명시.

### 8-16. F25 — 전체 읽기 상한 (총괄 자동 확인 `338aeb1` 뒤) (JVM 시험만, 실기기 미검증)

자동 확인: 옥션 웹 통과(`READ_DUTY_PCT` 3·7·4), 예스24 10·13. G마켓은 **전체 읽기 한 번에 6.1초**(`READ_WHOLE_COST_MS=6126`, `READ_DUTY_PCT=40`). F24의 "느린 페이지" 규칙은 느린 읽기가 **끝난 뒤** 다음 전체 읽기를 줄일 뿐이라, 첫 6초는 막지 못했다.

- **전체 읽기 상한(`WholeReadCap`):** 전체 읽기(앞에서부터 깊이 우선)는 노드 1500개 또는 600ms를 넘으면 멈추고 `READ_WHOLE_ABORTED`를 남긴다. 그때까지 읽은 부분으로 판단한다. 그 페이지는 10초 동안 어떤 계기로도 전체를 다시 읽지 않는다. 탐색(probe)은 매번 그대로 한다.
- **안전:** 멈춘 지점이 팝업 한가운데면 아직 안 읽은 결제·동의 버튼 때문에 물어야 할 팝업을 자동으로 닫을 수 있다. 그래서 읽은 부분에서 팝업 루트가 보이면 **그 팝업의 하위 트리만 끝까지 다시 읽어** 채운다(`WholeReadCap.complete`).
- **큰 페이지에서도 팝업을 찾는 근거:**
  - 탐색은 끝 자식부터 본다. 웹 팝업은 대개 페이지 끝에 붙고 위에 그려지므로 200노드 안에서 찾는다. 화면 밖과 크기 0 하위 트리는 나중에 본다.
  - 상한이 걸린 전체 읽기는 페이지 앞부분(앞쪽 DOM의 모달, 위쪽 포털)을 맡는다.
  - 시험: 노드 3000개 페이지 끝의 대화상자는 탐색 10노드 안에 찾음, 앞부분 대화상자는 상한 안의 전체 읽기로 찾음, 상한에 잘린 팝업은 다시 읽어 결제 버튼까지 보고 계속 묻기.
  - 둘 다 놓치는 경우: 아주 큰 페이지 **한가운데**에 대화상자 표시 없이 있는 모달. 로그에 `PROBE_MISS_CAPPED`와 `READ_WHOLE_ABORTED`가 같이 나온다.
- 검증(F25): 5개 Gradle 작업 통과, 단위 시험 debug 1183개·release 1168개 실패 0, lintDebug 오류 0(경고 14), INTERNET 0·새 권한·새 라이브러리 없음. 시험 추가 `WholeReadCapTest` 4.

### 8-17. F26 — 예산과 감지 속도 같이 지키기, 모든 경로에 읽기 상한 (총괄 자동 확인 `f33c2e7` 뒤) (JVM 시험만, 실기기 미검증)

자동 확인:
- 옥션 웹 통과(감지 1.8초, 재확인 299ms, `READ_DUTY_PCT` 4).
- G마켓 전체 읽기 1044ms + `READ_WHOLE_ABORTED`(6.1초에서 개선), `READ_DUTY_PCT` 21. 예스24 8·16.
- **올리브영 앱(회귀):** 팝업은 닫혔지만 감지 17.9초, 마지막 빈 읽기 뒤 3.2초(`DETECT_GAP_MS`), 앱에서 전체 읽기 2193ms 미중단, `CLOSE_RECHECK_MS=905`.

- **원인:**
  1. F24 예산 상한 3초. 팝업이 뜬 뒤 다음 읽기까지 최대 3초를 기다렸다. 앱의 dim 덮개 추가는 창 이벤트 없이 화면 변경으로만 와서 예산이 리셋되지 않았다.
  2. F25 상한은 "전체 걸어 다니기"에만 걸렸다. 앱 위 창 읽기와 탐색 뒤 하위 트리 읽기에는 없었다.
  3. 상한 600ms가 걸어 다니기 시작부터 셌다. 탐색(최대 약 0.3초)은 빠져 있었고, 시계를 16노드마다만 봤다.
  4. 재확인 읽기도 같은 600ms 상한을 썼다.
- **고침:**
  - 예산: 상한 3초 → **1초**. 대신 쉬는 동안의 간격은 마지막 읽기 비용의 6배 이상(최대 3초)이다. 0.1초짜리 읽기는 1초마다(약 9%), 0.3초짜리는 1.8초마다(약 14%) 읽는다.
  - **팝업이 생겼을 가능성이 큰 변화:** 이때는 예산을 리셋하고 바로 읽는다.
    - 창이 바뀜(`TYPE_WINDOW_STATE_CHANGED`·`TYPE_WINDOWS_CHANGED`, 원래대로), 창 일부가 나타남(`CONTENT_CHANGE_TYPE_PANE_APPEARED`, 창 이벤트처럼 재확인까지 함).
    - 화면 60% 이상의 목록 아닌 큰 컨테이너가 바뀜(`SUBTREE`): `CONTENT_LIKELY_POPUP`. 출처 크기는 0.3초에 한 번만 본다(바인더 1회).
    - WebView·RecyclerView·ListView·ScrollView·ViewPager의 변화(스크롤·캐러셀)는 리셋하지 않는다.
  - **읽기 상한 하나:** 한 번의 읽기에서 앱 위 창·팝업 하위 트리·전체 트리 모두 같은 마감(읽기 시작 + 600ms, 탐색 포함)을 쓴다. 시계는 4노드마다 본다. 마감을 넘긴 "팝업 하위 트리"는 팝업이 아니라 페이지로 보고 버린다. 잘린 팝업을 다시 읽는 안전 보완(F25)은 유지한다.
  - **재확인:** 재확인 읽기는 마감 250ms(탐색 포함). 220ms 기다림을 더해 500ms 이하 목표.
- 목표: 올리브영 앱 `DETECT_GAP_MS` ≤ 1000(덮개 추가가 `CONTENT_LIKELY_POPUP`로 오면 0.2초 안팎), 팝업 없는 페이지 `READ_DUTY_PCT` ≤ 15, `READ_WHOLE_COST_MS` ≤ 약 650.
- 한계: 팝업이 목록·웹뷰 안에서만 바뀌는 방식으로 나타나면 리셋 없이 예산 간격(최대 1~3초)으로 잡힌다.
- 검증(F26): 5개 Gradle 작업 통과, 단위 시험 debug 1185개·release 1170개 실패 0, lintDebug 오류 0(경고 14), INTERNET 0·새 권한·새 라이브러리 없음. 예산 시험 갱신·추가(1초 상한, 비용 비례, 팝업 가능성 리셋, 목록·웹뷰 제외).

### 8-18. F27 — F21~F26 자체 검토 (별도 검토 에이전트 + 직접 확인) (JVM 시험만, 실기기 미검증)

F26 자동 확인: 옥션 웹 감지 1.3초·재확인 308ms·`READ_DUTY_PCT` 7. G마켓 전체 읽기 629ms 뒤 중단, `READ_DUTY_PCT` 17. 예스24 648ms 뒤 중단, 18·11. 올리브영 앱 `DETECT_GAP_MS` 1588(3172에서), 재확인 734ms(905에서), 닫힘.

찾아서 고친 것(심한 순):
1. **(높음) 엄격 재확인이 탐색만 해서, 움직인 팝업을 "닫힘"으로 보고 다시 누를 수 있었다.** 전체 읽기로만 찾은 팝업(탐색이 못 보는 것)이 클릭 뒤 움직이면 재확인도 못 보고 "닫힘"이 된다. 그러면 결과를 말하고, 쿨다운 키(경로·크기)도 바뀌어 다음 읽기에서 같은 버튼을 다시 누른다(재시도). "앞으로 항상"이면 거짓 성공으로 기억까지 했다.
   - 고침: 전체 읽기로 찾은 팝업은 재확인도 전체 읽기로 한다(`SimplePopup.foundByProbe`).
   - 고침: 닫힘 뒤 5초 안에 같은 팝업(글자 지문)이 다시 보이면 실패로 본다(`CLOSE_REAPPEARED_LATE`). 자동으로 다시 누르지 않고, 쓴 버튼은 선택지에서 빼고 "대신 닫지 못했어요."로 묻고, 그 팝업의 기억 규칙은 지운다.
2. 영상 건너뛰기가 눌러도 안 사라지면 10초마다 다시 눌렀다. → 그 버튼은 이번 실행 동안 다시 누르지 않는다(`VIDEO_SKIP_FAILED_BEFORE`).
3. 기억 규칙("앞으로 항상")으로 눌렀는데 안 닫혀도 규칙이 남아 서비스가 다시 켜질 때마다 한 번씩 눌렀다. → 실패하면 규칙을 지운다(`REMEMBERED_RULE_DROPPED`).
4. 누른 버튼이 앱 밖으로 보냈을 때(`LEFT_APP`, 예: 스토어) 실패로 기억하지 않아, 돌아오면 또 눌렀다. → 자동·사용자 누름 모두 그 버튼을 실패로 기억한다(조용히).
5. 누르기 직전 확인은 이름·보임·켜짐만 봤다. 그 자리 노드가 클릭 불가로 바뀌면 클릭이 위로 올라가 부모(광고 카드 링크 등)를 누를 수 있었다. → 확인할 때 클릭 가능도 요구하고, 클릭은 확인한 그 노드 자체만 한다(위로 올라가지 않음).
6. 자동 닫기의 "누르는 중" 표시(`pressing`)가 작업이 시작되지 않거나 누르기 전에 취소되면 남아서, 그 팝업을 이번 실행 내내 무시할 수 있었다. → 어떻게 끝나든 지우고, 시작이 거절되면 바로 지운다. `onInterrupt`에서도 지운다.
7. 트리 깊이 64를 넘는 부분은 말없이 잘렸다. 팝업의 위험 버튼이 그 아래 있으면 못 보고 자동으로 닫을 수 있었다. → 팝업 안이 잘렸으면 자동으로 닫지 않고 묻는다(`DEPTH_CUT_ASKED`).

점검해서 문제없던 것:
- 앱 창 id 전달.
- 결정 버튼은 [버튼으로 이동]만 되고 누르기·"앞으로 항상" 선택지는 아님. 이름 없는 X도 마찬가지(누르기 아님).
- 음성 이동은 이동만 함.
- 기억 규칙 일치 조건(앱·종류·글자·구조 해시·자리·버튼).
- 사기 의심·결정 팝업에 자동 경로 없음.
- 잘린 팝업 다시 읽기.
- 예산이 영원히 읽기를 멈추지 않음.
- 로그에는 코드와 숫자만 남음.
- `shutdown` 정리.

**올리브영 재확인 734ms:**
- 원인: 재확인 읽기의 마감 250ms가 탐색에는 걸리지 않았다(최대 200노드 약 0.28초). 재확인 때도 앱 위 창을 모두 걸어 다녔다.
- 고침: 탐색도 읽기 마감을 지킨다(`ProbeTree.expired`). 재확인은 팝업이 앱 창일 때만 위 창을 본다.
- 위험 판단: 마감에 걸려 탐색이 멈추면 "닫힘"으로 볼 수 있다. 다만 다시 나타난 팝업은 끝 자식 쪽이라 먼저 보이고, 위 1번(5초 안 재출현)이 한 번 더 막는다.
- 검증(F27): 5개 Gradle 작업 통과, 단위 시험 debug 1186개·release 1171개 실패 0, lintDebug 오류 0(경고 14), INTERNET 0·새 권한·새 라이브러리 없음. 시험 추가: 탐색 마감 1. 1·2·3·4·6·7은 서비스 경로라 단위 시험이 없고, 실기기 확인이 필요하다.

### 8-19. F28 — 준영 PR #69 병합과 연결 3개, 선택 창 앱 이탈 (JVM 시험만, 실기기 미검증)

병합(`2a816d1`): `feature/b-demo-rules` 29ec54d. 충돌 2곳(CloseButtonFinder, 서비스)은 F21~F27 동작을 살리고 B 추가분(`promoBuyCount`, `roleOfExactPhrase`, 새 금지·위험 단어)을 얹었다. B의 새 금지어 "다운"이 F21 "다운로드 안내 닫기"(닫는 대상 이름)를 막아서, "○○ 닫기" 꼴에서만 명사 "다운로드"를 빼고 검사한다.

1. **구매 홍보 규칙 켬** (`DecisionDemoRules.PROMO_PURCHASE = true`, 재호 승인).
   - `ASK_WHEN_PURCHASE_BUTTON`은 true 그대로다. 구매 버튼은 여전히 묻기 근거다.
   - 예외는 B의 네 조건이 모두 맞을 때뿐이다(`PromoPurchaseGate`): 버튼 이름 전체가 구매 권유, 다른 글자에 홍보 근거, 본문에 결정 권유 없음, 결제 흔적 없음. 이때 그 구매 권유 버튼만 묻기 근거에서 빠진다(`PROMO_PURCHASE_NOT_ASKED`).
   - B가 직접 광고·쿠폰으로 본 팝업만 해당한다. 이미지 대화상자 힌트로 광고가 된 팝업은 해당하지 않는다.
   - 다른 위험 버튼(결제·동의·구독·권한·회원가입·예약·알림 켜기…)이 하나라도 있으면 그대로 묻는다. 구매 버튼은 누를 후보가 아니다(강력 모드가 누르는 건 닫기뿐).
   - "앞으로 항상" 기억 경로와 X 모양 닫기 제안은 지금처럼 위험 버튼이 0개일 때만 된다(완화하지 않음).
2. **민감 화면 건너뛰기** (`SensitiveScreen`): 트리를 읽은 직후, 영상 건너뛰기보다 먼저 검사한다. 걸리면 그 화면은 판단·누르기·우리 안내를 모두 하지 않는다. 로그는 `SKIPPED_SCOPE_SENSITIVE_*` 코드만 남기고 주소·글자는 남기지 않는다.
   - B의 `BrowserSensitivity`: 주소, 로그인 양식, 결제 흔적 2개 이상, 개인정보.
   - 팝업만 읽은 경우(하위 트리·앱 창)에는 주소창이 노드에 없다. 그래서 브라우저 8종에서만 주소창을 view id로 따로 찾아 메모리에서 비교한다. 엄격 재확인에서는 찾지 않는다(비용).
   - 보이는(크기 > 0, 화면 안) `isPassword` 칸이 있으면 어느 앱이든 건너뛴다. 그 칸의 글자는 노드에 복사하지도 않는다.
   - 쇼핑몰 홈(바닥글 로그인·개인정보처리방침, 크롬 주소창 입력칸, 검색칸, 대표번호)은 막히지 않는다(시험).
3. **영상 건너뛰기 좁히기** (`VideoAdSkip.scan(youtube)`):
   - 유튜브 앱과 youtube.com(주소창에서 본 뒤 1분)은 지금대로 둔다.
   - 그 밖에서는 광고 표시가 건너뛰기의 이름·view id나 같은 부모 아래에 있을 때만 누른다(`VIDEO_SKIP_AD_MARK_NOT_NEAR`).
   - "광고성"은 광고 표시가 아니다(어디서나).
   - 누르기 전 화면 글자에 B의 결정·사기 단어가 있으면 누르지 않는다(`VIDEO_SKIP_DENIED_BY_B`, 유튜브 밖만).
4. **선택 창 앱 이탈** (LG 11/1 14:29 올리브영): 선택 창이 떠 있는 동안 앱 전환 이벤트가 오면 0.15초 모아서 화면의 앱 창 패키지를 본다. 우리 앱 자신의 화면도 다른 앱으로 센다.
   - 팝업의 앱 창이 하나도 없으면 선택 창을 닫고 진행 중인 분석을 버린다(`CHOICE_DISMISSED_APP_LEFT`, AGENTS 6-7).
   - 우리 선택 창은 접근성 오버레이라 앱 창으로 치지 않는다.
   - TalkBack 포커스는 옮기지 않는다.
   - 창 정보를 모르면 아무것도 하지 않는다.
   - 같은 화면의 '결제 혜택 총정리…' 배너를 USER_DECISION으로 본 오분류는 B(준영)에게 넘겼다(여기서 고치지 않음).

- 위험:
  - 팝업 안에 이메일·휴대전화 번호가 보이면(예: 문의 메일 주소) 개인정보로 보고 건너뛴다. 안전 쪽 오탐이다.
  - 주소창 view id 찾기의 비용은 실기기에서 확인해야 한다(`READ_PROBE_COST_MS`로 확인).
  - 유튜브 밖 영상 광고에서 B 결정 단어(예: "구독")가 화면 어디든 있으면 건너뛰지 않는다.
- 검증(F28): 5개 Gradle 작업 통과, 단위 시험 debug 1237개·release 1222개 실패 0(건너뜀 1), lintDebug 오류 0(경고 14), INTERNET 0·새 권한·새 라이브러리 없음. 시험 추가: `F28RegressionTest` 12개(정답지 L04~L06·L13 형태, 결제 흔적 6개 반례, 다른 위험 버튼 4개, 민감 화면·쇼핑몰 홈, 쿠팡 튜토리얼·회원가입 광고성 반례, 앱 이탈). B의 `DecisionDemoRulesTest.shipsOff`는 켬에 맞춰 `shipsOnForTheDemo`로 바꿨다.

### 8-20. F29 — 닫힌 시트를 "다시 나타남"으로 본 거짓 실패 (JVM 시험만, 실기기 미검증)

- **증상**(LG 11/1, c750177, 11번가 웹 시크릿): 시트는 실제로 닫혔다. 그런데 `CLICKED → CLOSED_CONFIRMED → CLOSE_RECHECK_MS=238 → CLOSE_REAPPEARED → NOT_CLOSED → AUTO_CLOSE_FAILED_ASKED`로 "대신 닫지 못했어요" 선택 창이 떴다.
- **원인**(로그와 코드로 추정, 덤프 없음): 엄격 재확인 대기가 F20 600ms → F21 350ms → F22-2 220ms로 짧아졌다. 220ms 뒤에는 시트가 아직 내려가는 중이라, 같은 글자·겹치는 자리로 "다시 나타남"이 된다. 처음 `CLOSED_CONFIRMED`도 뿌리 노드 자리가 바뀐 것(움직임)으로 났을 수 있다.
- 강력 모드가 누르는 버튼은 #69 병합 뒤에도 "모바일 웹으로 볼래요"다(시험으로 확인).
- **고침**(`RecheckRules`):
  - 다시 찾은 팝업은 화면 안에 크기가 있는 노드만 센다. 화면 밖으로 내려간 것, 높이 0으로 접힌 잔여 노드는 닫힘으로 본다.
  - 누르기 전과 똑같은 자리(뿌리·글자 노드 모두)면 바로 실패다(F20 11번가, 옥션처럼 다시 뜸).
  - 다른 자리면 움직이는 중으로 보고 220ms마다 다시 읽는다(`CLOSE_RECHECK_MOVING`). 사라지면 닫힘, 같은 자리에 두 번 멈춰 있으면 실패다.
  - 1.2초가 지나도 움직이면 실패로 본다(`CLOSE_RECHECK_STILL_MOVING`). 거짓 "닫힘"은 만들지 않는다.
  - `CLOSE_RECHECK_READS`에 읽은 횟수를 남긴다.
- **위험:** 시트가 내려가는 동안에는 결과 한 마디가 그만큼(최대 약 1.2초) 늦어진다. 제자리에서 투명해지며 사라지는 팝업(자리 변화 없음)은 여전히 첫 읽기에서 실패로 볼 수 있다. 그런 사이트가 나오면 덤프가 필요하다.
- 검증(F29): 5개 Gradle 작업 통과, 단위 시험 debug 1243개·release 1228개 실패 0(건너뜀 1), lintDebug 오류 0(경고 14), INTERNET 0. 시험 추가: `F29RegressionTest` 6개.

### 8-21. F30 — 시험 앱 `Dialog`(P04)를 못 잡음 (JVM 시험만, 실기기 미검증)

- **증상**(LG 11/1 15:37, e33955d): 시험 앱 P04(어두운 배경 위 흰 대화상자, 글자 없는 X). `NO_POPUP_ROOT`가 192번 났고 `DETECTED_*`는 0번, 선택 창도 없었다.
- **패키지 문제가 아님:** 우리 앱 제외는 `packageName == ownPackage` 정확히 같을 때만이라 `kr.closeforyou.popuptest`는 제외되지 않는다.
- **원인(추정, 창 덤프 없음):** Android 11부터 터치 모달 창(어둡게 덮고 모든 터치를 가져가는 `Dialog`)은 창 영역을 화면 전체로 알릴 수 있다. 그런데 "작은 대화상자 창"인지를 창 영역 96% 미만으로만 판단했다. 그래서 두 경로가 모두 이 창을 버렸다:
  - 활성 창 경로(`DIALOG_WINDOW`)
  - 앱 창 경로(F16 `APP_WINDOW`)
  - 창 안 노드에는 Dialog 클래스도 덮개도 없어서 `NO_POPUP_ROOT`가 됐다.
- **고침**(`WindowBoundsRules`): 창 영역과 창 뿌리 뷰 영역 중 작은 쪽으로 판단한다. 두 경로 모두 같다. 창은 화면 전체인데 뿌리 뷰로 판단한 경우는 `WINDOW_BOUNDS_FROM_ROOT`로 남긴다(원인 확인용).
- `TREE_UNAVAILABLE_OTHER_APP` 12번은 앞 앱이 바뀌는 순간의 읽기로 보인다. 이 앱 탓인지는 로그만으로 알 수 없다.
- **위험:** 뿌리 뷰가 화면보다 작은 일반 활동 창(창 모드 등)이 같은 앱의 더 큰 창 위에 있으면 대화상자 창으로 볼 수 있다. 이 경우에도 누르는 것은 닫기 후보뿐이고, 결정·위험 버튼 규칙은 그대로다.
- 검증(F30): 5개 Gradle 작업 통과, 단위 시험 debug 1248개·release 1233개 실패 0(건너뜀 1), lintDebug 오류 0(경고 14), INTERNET 0. 시험 추가: `F30RegressionTest` 5개(창 영역 규칙, P01·P04·P05·P06 Dialog 창, W02·W03 웹).

### 8-22. F31 — B 새 커밋 병합, 누르기 직전 B 재판단·연속 팝업 연결 (JVM 시험만, 실기기 미검증)

- **병합**(`1fa7861`, 자동, 충돌 없음):
  - B 6afa87f: "결제 혜택·결제 할인"을 결정 근거에서 뺀다(B_DEMO_RULES 1-7, 올리브영 서랍 배너 오분류). B의 `isRisky` 변경은 우리 `isDecision`에 들어갔다. F24 이후 `isRisky`가 부르는 곳이라 묻기 근거와 [버튼으로 이동] 모두 같은 기준이다.
  - B 6e8b7a6: `OverlayExecutionRules.kt`(`PrePressRecheck`, `ChainGuard`).
- **누르기 직전 B 재판단**(`PRE_PRESS_RECHECK`, B 6-1):
  - 자동 누르기만 해당한다(B 문서·`PrePressBlock` 설명대로). 사용자가 고른 누르기에는 적용하지 않는다.
  - `pressAndConfirm`의 자리·버튼 확인(stillThere) 바로 뒤에서 팝업 범위만 다시 읽는다(마감 250ms).
  - 다시 만든 분석으로 B가 판단한다. 구매 홍보 수는 결정 때와 같은 문(`PromoPurchaseGate`)을 거친다.
  - 아래면 누르지 않는다(`PRE_PRESS_RECHECK_STOPPED` + `PRE_PRESS_STOPPED_*`):
    - 결정·사기로 바뀜
    - 위험 버튼이 새로 생김
    - 지금 처리 방식이 닫지 않는 종류로 바뀜. 강력 모드와 기억 규칙은 결정·사기 말고 모두 닫으므로 해당 없음.
    - 다시 못 읽음(`_UNREAD`: 사라짐·마감 넘김·깊이 잘림)
  - 멈춘 뒤에는 지금 모습으로 묻는다. 결정이면 [버튼으로 이동]만 있다. 사기 의심이면 묻지 않는다.
  - 비용은 `PRE_PRESS_RECHECK_MS`로 남는다. 통과하면 `PRE_PRESS_RECHECK_PASSED`.
- **연속 팝업**(`CHAIN_GUARD`, B 6-2): 자동 닫기로 정해진 직후 `check`, 누른 뒤 `pressed`를 부른다(사람 누름은 횟수에 안 셈).
  - 한 앱에서 1분에 3번을 넘으면 묻는다(`CHAIN_GUARD_ASKED_RATE`).
  - 우리가 누른 뒤 1.5초 안에 **다른** 팝업이 뜨면 묻는다(`CHAIN_GUARD_ASKED_NEW_POPUP_AFTER_PRESS`).
  - 숫자는 `SimpleCloseEngineFeature.CHAIN_MAX_AUTO`·`CHAIN_WINDOW_MILLIS`·`CHAIN_NEW_POPUP_AFTER_PRESS_MILLIS`에 있다.
  - "다른 팝업"은 글자 지문(창 id + 글자 요약)으로 가린다. 움직이거나 다시 그려진 같은 팝업은 다른 팝업이 아니다.
- **위험:**
  - 11번가처럼 시트를 닫은 뒤 쿠폰 팝업이 1.5초 안에 뜨는 사이트에서는 두 번째가 묻기로 바뀐다. LG에서 보고 숫자를 정해야 한다.
  - 팝업 범위가 큰 화면(덮개 안 내용이 많음)에서 재판단 읽기가 250ms를 넘으면 `_UNREAD`로 안 누르고 묻는다.
  - 누르기까지 재판단 읽기만큼 늦어진다.
- 검증(F31): 5개 Gradle 작업 통과, 단위 시험 debug 1262개·release 1247개 실패 0(건너뜀 1), lintDebug 오류 0(경고 14), INTERNET 0. 시험 추가: `F31RegressionTest` 4개. B 시험 `OverlayExecutionRulesTest`·`DecisionPaymentBenefitTest`도 통과.

### 8-23. F32 — F30이 LG에서 안 먹힘: 활동 창이 창 목록에서 빠짐 (JVM 시험만, 실기기 미검증)

- **증상**(LG 16시, 2e5537e): 시험 앱 P04가 여전히 `NO_POPUP_ROOT`였다. `WINDOW_BOUNDS_FROM_ROOT`, `APP_WINDOW_*`는 0줄이었다.
- dumpsys 결과: 대화상자는 TYPE_APPLICATION 996×637, DIM_BEHIND, 터치 모달(NOT_TOUCH_MODAL 없음)이다. 그 아래에 같은 Activity의 BASE_APPLICATION 창(1080×2460)이 있다.
- **원인(추정, 로그와 맞음):** Android는 위 창들의 터치 영역에 완전히 가려진 창을 `getWindows()`에서 뺀다. 터치 모달 대화상자의 터치 영역은 화면 전체라서, 그 아래 활동 창이 목록에 아예 없다.
  - F30까지의 "대화상자 창" 조건은 "같은 앱의 더 넓은 창이 목록에 있음"을 요구했다. 그래서 활성 창(대화상자)이 대화상자 창으로 인정되지 않았다.
  - F30 진단 코드는 인정된 뒤에만 찍혀서 0줄이었다.
  - 앱 창 경로도 활성 창 위에 다른 창이 없어서 돌지 않았다(`APP_WINDOW_*` 0).
- **고침**(`WindowBoundsRules.activeBounded`): 활성 창이 앱 창이고 보이는 부분이 화면보다 작을 때, 아래 둘 중 하나면 대화상자 창으로 본다.
  - 창 영역이 뿌리 뷰보다 1.2배 넘게 큼(터치 모달)
  - 같은 앱의 더 넓은 창이 목록에 있음(전과 같음)
  - 화면 분할·창 모드 앱(창 = 뿌리, 같은 앱의 더 넓은 창 없음)은 그대로 일반 창이다.
- 한 트리로 합쳐 오는 경우는 기존 `MODAL_CARD`가 잡는다(시험). 범위는 "모든 앱"에서 시험 앱이 통과하고, 제외는 `kr.closeforyou`와 정확히 같을 때만이다(시험).
- **진단:** 읽을 때마다 창 목록을 코드 한 줄로 만들고, 바뀔 때만 남긴다. 예: `WINDOWS app=1 [S?:wPrN A*S:wFrP] active=BOUNDED above=-`.
  - 같은 앱 창 수
  - 위에서부터 창 종류(A 앱, I 키보드, S 시스템, O 우리 오버레이, X 기타)와 활성 표시 `*`
  - 같은 앱 S / 다른 앱 O / 모름 ?
  - 창·뿌리 크기 등급(F 전체, P 일부, Z 0, N 없음)
  - 활성 창이 대화상자 창이 아닌 이유(`active=NOT_LISTED|NOT_APPLICATION|NOT_BOUNDED|NO_WIDER_SAME_APP_WINDOW`)
  - 위 앱 창을 버린 이유(`above=NO_ROOT|OTHER_PACKAGE|NOT_BOUNDED|NOT_POPUP|CUT`)
  - 패키지·글자·좌표는 남기지 않는다.
- 뿌리 노드는 앱 창에서만 가져온다(읽기마다 binder 호출을 늘리지 않으려고).
- 검증(F32): 5개 Gradle 작업 통과, 단위 시험 debug 1268개·release 1253개 실패 0(건너뜀 1), lintDebug 오류 0(경고 14), INTERNET 0. 시험 추가: `F32RegressionTest` 6개(창 목록 모양 3가지, 일반 창, 진단 줄, 합쳐진 트리, 범위).
- **F32-2: 11번가 앱 첫 화면 팝업**(LG 16:33, 감지 0): `AdsOneDayPopupActivity`는 창 플래그로 뒤를 어둡게 하는 투명 Activity라서 어두운 노드도, 덮는 레이어도 없다.
  - **새 규칙 `DIM_WINDOW`**(`DETECTED_DIM_WINDOW`): 화면만 한 창의 보이는 내용이 가운데 한 덩어리(화면의 5~60%)이고 나머지가 비어 있을 때 팝업으로 본다. 아래를 모두 만족해야 한다.
    - 누를 것이 1~11개
    - 입력칸·쿠키 없음
    - 이름 있는 닫기가 있거나, 이름 없는 작은 X가 딱 1개(11번가는 카드 아래 둥근 X)
  - P04도 대화상자 decor가 화면 전체로 올 때 이 규칙으로 잡힌다(시험).
  - 일반 앱 화면·스플래시·가운데 로그인 양식은 잡히지 않는다(시험).
  - **(e) Activity 이름 힌트**: `TYPE_WINDOW_STATE_CHANGED`의 클래스 이름이 …Popup…/…Dialog…/…Ad(s)…Activity면 그 앱의 읽기 10초 동안 닫기 조건 없이도 `DIM_WINDOW`로 본다(`POPUP_ACTIVITY_HINT`). 이름은 비교만 하고 남기지 않는다.
  - **결정 팝업의 X:** B는 "로그인하러 가기"가 있어서 이 팝업을 결정으로 본다. 그래서 F20 정책대로 [버튼으로 이동]만 준다. 대신 이름 없는 X도 [X 모양 닫기 버튼으로 이동]으로 준다(`X_BUTTON_MOVE_OFFERED`). TalkBack에서 사용자가 직접 두 번 탭하면 닫힌다. 우리가 누르는 선택지로 줄지는 재호·준영이 정할 일이다.
  - 위험: 크롬·일반 앱에서 내용이 가운데만 있고 이름 없는 작은 버튼이 1개인 화면을 팝업으로 볼 수 있다. 그래도 누를 수 있는 것은 이름 있는 닫기뿐이고, X는 사용자 탭으로만 눌린다.
  - 검증(F32-2): 5개 Gradle 작업 통과, 단위 시험 debug 1272개·release 1257개 실패 0(건너뜀 1), lintDebug 오류 0(경고 14), INTERNET 0. 시험 추가: `F32DimWindowTest` 4개.

### 8-24. F33 — 대화상자가 제 크기로 오고 활동 창은 목록에 없음 (JVM 시험만, 실기기 미검증)

- **증상**(LG, fbc54ae): `WINDOWS app=1 [S?:wPrN A*S:wPrP] active=NO_WIDER_SAME_APP_WINDOW above=-`, `NO_POPUP_ROOT`. 시험 앱 P04 선택 창이 뜨지 않았다.
  - 읽는 법: 위에서부터 창이 둘이다.
    - `S?:wPrN`: 시스템 창. 다른 앱인지 모름(`?`, 앱 창이 아니라 뿌리를 안 읽음). 창 영역은 화면 일부(`wP`), 뿌리 없음(`rN`).
    - `A*S:wPrP`: 활성(`*`) 앱 창, 같은 앱(`S`). 창 영역·뿌리 뷰 모두 화면 일부(`wP`, `rP`).
  - 활동 창(BASE)은 목록에 없고, 대화상자 창은 화면 전체로 부풀지도 않았다. 그래서 F32의 두 조건(뿌리보다 1.2배 큼 / 같은 앱의 더 넓은 창)이 모두 실패했다.
- **고침**(`WindowBoundsRules.floating`): 활성 앱 창의 보이는 부분이 화면의 10~85%이고, 가운데가 화면 가운데에서 가로 10%·세로 20% 안이면 대화상자 창으로 본다. 아래는 계속 제외된다(시험).
  - 화면 분할 반쪽(위·아래·왼쪽)
  - PiP(모서리, 작음)
  - 위쪽 배너 창, 아래 시트 창
  - 키보드(앱 창 아님)
- 검증(F33): 5개 Gradle 작업 통과, 단위 시험 debug 1274개·release 1259개 실패 0(건너뜀 1), lintDebug 오류 0(경고 14), INTERNET 0. 시험 추가 2개(LG 줄 그대로의 창 목록, 대화상자가 아닌 창 6가지). F32 시험 하나는 "F32는 거부"를 적었던 것이라 F33 동작으로 고쳤다.
- **F33-2: 11번가 앱 `DIM_WINDOW` 거짓 실패**(LG, fbc54ae): 팝업은 바로 닫혔는데 `CLICKED → NOT_CLOSED → AUTO_CLOSE_FAILED_ASKED`였다. `CLOSED_CONFIRMED`·`CLOSE_RECHECK_MS`는 없었다.
  - **원인(코드로 확인):** `DIM_WINDOW` 뿌리는 팝업 Activity 안의 화면만 한 노드다. 닫힘 확인은 그 경로를 그때그때의 활성 창에서 찾았다. 닫힌 뒤 활성 창은 11번가 메인 화면인데, 같은 경로에 같은 화면 크기 노드가 있어서 1.5초 내내 "그대로 있음"이 됐다.
  - **고침**(`PopupRootFinder.bindWindow`): 창 자체가 팝업인 경우(`DIM_WINDOW`, `DIALOG_WINDOW`)는 그 창 id에 묶는다. 닫힘 확인·누르기·재확인 모두 그 창에서 찾는다. 창이 사라지면 닫힘이다.
  - 활성 창이 잠깐 없을 때(Activity 전환 중)도 바로 실패로 보지 않는다. 마감까지 다시 본다.
  - 실패 사유를 `NOT_CLOSED` 뒤에 남긴다: `NOT_CLOSED_STILL_THERE`, `_NO_TREE`, `_REAPPEARED`, `_STILL_MOVING`.
  - 누른 버튼 종류를 `CLICKED` 뒤에 남긴다(`CLICKED_LABELED_CLOSE`, `_STAY_ON_WEB`, `_DIM_CLOSE`, `_HIDE_FOR_A_WHILE`, `_POLITE_NO`, `_SKIP`, `_UNLABELED_X`, `_OTHER`). 이름은 남기지 않는다.
  - **로그인 버튼(정책은 바꾸지 않음):** 이름이 "로그인하러 가기"면 위험 버튼이다. 묻기 근거가 되고 [버튼으로 이동]만 된다(시험).
    - LG 로그에는 `BLOCKED_RISKY`가 없고 `UNLABELED_SKIPPED`·`CLASSIFIED_UNKNOWN`이 있었다. 그래서 그 버튼은 이름 없는 이미지였을 가능성이 크다.
    - 이름 없는 버튼은 우리가 누르지 않는다. 넓은 모양이라 X로 제안되지도 않는다(시험).
    - 확인용 숫자를 남긴다: `POPUP_TEXTS`, `BUTTONS_RISKY`, `BUTTONS_UNLABELED`, `BUTTONS_FORBIDDEN`.
  - 검증(F33-2): 5개 Gradle 작업 통과, 단위 시험 debug 1277개·release 1262개 실패 0(건너뜀 1), lintDebug 오류 0(경고 14), INTERNET 0. 시험 추가: `F33ClosedCheckTest` 3개.

### 8-25. F34 — 시험 앱 32종 정답지 불일치 (JVM 시험만, 실기기 미검증)

- **측정**(총괄, LG, e5823f1, 정답지 `preset_close_all` 열): 처리 16/32, 분류 23/25 일치. 불일치를 아래처럼 고쳤다. 원인은 재현 앱 코드(`PopupScenarioActivity`)와 에뮬레이터 덤프로 확인했다.
- **공통 원인 하나:** 우리 서비스는 `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`를 쓰지 않는다. 그래서 눌리지도 않고 글자도 없는 레이아웃(LinearLayout 카드·배너)은 트리에 없다. 그 안의 글자·버튼은 가장 가까운 남은 노드에 바로 붙는다. 에뮬레이터 `uiautomator dump`에는 레이아웃이 다 보이므로 덤프와 실기기 트리가 다르다.
- **F34-1 감지:**
  - P14(일반 화면 전환이 `DIM_WINDOW`): 시험 앱 Activity 이름 `PopupScenarioActivity`가 F32 이름 힌트에 걸려, 모든 시나리오 화면에서 닫기 없이도 팝업이 됐다.
    - 이름 힌트는 `…PopupActivity`·`…DialogActivity`·`…Ad(s)Activity`로 끝날 때만 켜진다.
    - `DIM_WINDOW`는 내용 덩어리가 화면 위 8% 아래에서 시작하고, 세로 가운데가 화면 가운데 ±20% 안일 때만 잡는다(일반 화면은 맨 위에서 시작).
  - P02(같은 창 덮개가 NONE): 클릭 가능한 덮개가 카드 글자·버튼을 바로 들고 있다. 이것을 `DIM_OVERLAY`로 잡는다. 조건은 모두 만족해야 한다.
    - 덮개가 가로 전체이고 화면의 70% 이상이다.
    - 든 것이 가운데 한 덩어리이고 덮개의 60% 이하다.
    - 이름 있는 닫기가 있고, 클릭 12개 미만, 입력칸·쿠키가 없다.
    - 덮개 앞에 페이지 내용이 있다.
    - 덮개 자신은 버튼으로 세지 않는다. 카드 글자 "가입"으로 이름이 붙어 위험 버튼이 되던 것을 막는다.
  - P03(하단 배너가 NONE): 배너 노드가 있으면 `BOTTOM_BANNER`로 잡는다(아래 가장자리 가로 전체 띠, 화면 3~10%, 이름 있는 닫기와 광고·설치·혜택 같은 말).
    - **배너 레이아웃이 트리에 없으면 여전히 못 잡는다.** 뿌리로 삼을 노드가 없기 때문이다. 시험 앱 쪽 대안(D)이나 플래그 대안(비추천)은 노트 표에 적었다.
  - P16(유튜브 밖 영상 광고가 NONE): 영상 표면·광고 표시·(건너뛰기 또는 카운트다운)을 한 덮개가 모두 들고 있으면 `VIDEO_COVER`다.
    - 카운트다운 중에는 기다린다. 건너뛰기가 나오면 **묻는다**(선택 창의 누르기 = 건너뛰기).
    - 유튜브 밖에서 스스로 건너뛰지 않는다(B 4-1 그대로). F28 좁히기는 의도대로 유지한다.
    - "건너뛸 수 있어요"(뛰 아닌 뛸) 카운트다운도 읽는다.
- **F34-2 처리·분류:**
  - P21·P22 사기 의심: 선택 창을 띄운다. "사기일 수 있는 팝업이에요. 대신 누르지 않아요. 다른 버튼은 누르지 마세요."
    - 선택지는 [닫기 버튼으로 이동]·내용 다시 읽기·그대로 두기뿐이다. 누르기 선택지는 없다. "경품 받기" 같은 버튼은 보여 주지 않는다.
    - 선택 창을 띄울 수 없으면 예전 경고 음성을 낸다.
  - P23 늦은 닫기: 누를 것이 전혀 없고, 닫기가 있었다면 프리셋이 닫았을 팝업은 최대 5초 기다린다(`LateCloseWait`).
    - 닫기가 생기면 자동으로 닫는다. 5초가 지나면 한 번 더 보고 묻는다.
    - 결정·사기·위험 버튼·이름 없는 버튼·X가 있으면 기다리지 않는다.
  - P19 가짜 닫기: 트리로는 진짜 닫기와 구별할 근거가 없다(재현 앱 메모도 "첫 1회는 알려진 한계").
    - 설치 광고 말(다운로드·설치·앱 받기…)이 있는데 버튼이 그 "닫기" 하나뿐이면 묻는다. B가 검토할 추정 규칙이다.
    - 자동 누름이 앱 밖으로 나가면 그 앱은 이번 실행 동안 자동으로 누르지 않는다. 기존 F27 동작(실패로 기록, 기억 안 함)은 그대로다.
  - 분류:
    - 이미지 광고 힌트를 영상 덮개를 뺀 모든 뿌리에 적용한다.
    - B가 UNKNOWN이면 B 자신의 넓은 단어 목록(`DecisionKeywords.isCoupon`·`isPromotion`·`isStrongNotice`, 읽기만)으로 쿠폰 > 광고 > 공지를 정한다(`WordKindHint`).
    - 결정 단어·사기·위험 버튼이 있으면 정하지 않는다. B 분류기는 고치지 않았다.
  - 연속 자동 닫기: 1분 3번 그대로다. F34-2에서 "앞으로 항상 닫기" 팝업을 제한에서 뺐다가 F34-3에서 되돌렸다(총괄 측정: 22초 간격이면 정답과 일치, 측정 간격 문제였음).
- 정책 의견(P13·P24, 연속 닫기 근거)과 B에 넘길 제안은 노트 `integration-popup-helper-F34-2026-11-01.md`에 있다.
- 검증(F34): 두 커밋 모두 5개 Gradle 작업 통과. F34-1 단위 시험 debug 1285개·release 1270개, F34-2 debug 1291개·release 1276개, 실패 0(건너뜀 1). lintDebug 오류 0(경고 14), INTERNET 0. 시험 추가: `F34DetectionTest` 8개, `F34HandlingTest` 6개.

### 8-26. F35 — 준영 PR #70(b-review-f33) 병합과 연결 (JVM 시험만, 실기기 미검증)

- **병합:** `origin/feature/b-review-f33`(1ba1da9)을 F34 위에 병합했다. 충돌은 `VideoAdSkip.kt` 한 곳이었다. 양쪽이 같은 자리에 함수를 하나씩 더한 것이라 둘 다 살렸다(F34 `cover`, B `waitSeconds`).
  - 8-1 `PopupNeighbors`: 모달 카드일 때만 옆 칸을 안전 검사에 넣는다. 누를 후보는 아니다.
  - 8-2: 글자 없는 이미지 팝업의 X에는 "앞으로 항상"을 주지 않는다.
  - 8-3: 자동 닫기 스위치를 끄면 기억한 팝업도 누르지 않는다. 기억은 그대로 둔다.
  - 위 세 가지는 재호가 승인했다.
- **옆 칸 읽힘 확인(8-1 미확인 항목):** 모달 카드를 찾으면 그 읽기에 카드의 형제 칸이 들어 있었는지 남긴다.
  - 들어 있으면 `NEIGHBORS_CHECKED`(안전 검사에 넣은 옆 칸 수)를 숫자 줄로 남긴다.
  - 없으면 `NEIGHBORS_UNREAD`다. 빠른 읽기가 카드에서 멈춘 것이다.
- **영상 대기 안내(8-4 연결):** 유튜브 광고가 카운트다운 중이면, 화면에서 읽은 초(`VideoAdSkip.waitSeconds`)로 "광고를 n초 뒤에 건너뛸게요."를 광고 하나에 한 번만 말한다(`VIDEO_SKIP_WAIT_ANNOUNCED`).
  - 프리셋이 광고를 닫을 때만 말한다. 우리가 대신 건너뛸 때뿐이다.
  - TalkBack이 켜져 있으면 결과 카드로 TalkBack 큐에 넣고 포커스는 가져오지 않는다. 꺼져 있으면 우리 TTS로 말한다.
  - 같은 앱·창은 30초 안에 다시 말하지 않는다. 숫자를 못 읽으면 말하지 않는다.
  - 유튜브 밖 영상 덮개(F34)는 묻는 경로라 말하지 않는다.
- **로그인 홍보 팝업(8-5 B안, 재호 결정):** `DecisionDemoRules.PROMO_LOGIN`(구매 홍보 `PROMO_PURCHASE` 옆, 켜짐). 아래 네 조건을 모두 만족하면 그 로그인 버튼은 B 결정 판단에서도, C 묻기 근거에서도 빠진다(`PROMO_LOGIN_NOT_ASKED`). 닫기만 자동으로 누른다.
  1. 버튼 이름 전체가 로그인 권유다("로그인하러 가기", "로그인하고 혜택 받기"…). "로그인하고 구매하기"·"로그인 후 결제"는 아니다.
  2. 다른 글자에 홍보 근거(쿠폰·할인·혜택…)가 있다.
  3. 본문이 결정을 권하지 않는다.
  4. 결제·동의·비밀번호·약관 흔적이 없다.
  - 로그인 버튼은 어떤 경우에도 누를 후보가 아니다. [버튼으로 이동]으로만 남는다.
  - "로그인이 필요한 서비스입니다"처럼 홍보 근거가 없으면 계속 묻는다(반례 시험).
  - 구매 규칙과 같은 판정 함수를 함께 쓰도록 B 코드를 최소로 나눴다(`isPromoCallToAction`). B의 기존 시험은 바꾸지 않고 모두 통과했다. 8-5에 적힌 "할인+로그인하러 가기+닫기 → DenyDecision" 시험은 저장소에 없어서 새 시험(`F35Test`)으로 새 결정을 넣었다.
- 검증(F35): 5개 Gradle 작업 통과, 단위 시험 debug 1301개·release 1286개 실패 0, lintDebug 오류 0(경고 14), INTERNET 0. 시험 추가: `BReviewF33Test` 6개(B), `F35Test` 5개.

### 8-27. F36 — P03 배너가 LG에서 여전히 0, P16 카운트다운 뒤 결과 없음 (JVM 시험만, 실기기 미검증)

- **측정**(총괄, LG, edcf10f, 간격 22초): 처리 27/32(기준선 22). 실제 앱 회귀 문제 없음.
- **P03 원인:** F34에서 예상한 대로 배너 LinearLayout이 서비스 트리에 없다. 글자·"설치"·"닫기"가 창 뿌리 아래에 페이지와 나란히 붙는다. 그래서 F34-1 `BOTTOM_BANNER`(배너 노드가 있을 때)는 탈 수 없었다. 재현 앱은 같은 창 아래에 고정한 배너다(별도 창 아님).
- **P03 고침**(`PopupRootFinder.flatBanner`): 화면 아래 20% 안의 이름 있는 닫기와 같은 줄에 있는 노드들을 배너로 본다. 조건은 모두 만족해야 한다.
  - 2개 이상, 가로 60% 이상, 높이 15% 이하
  - 그 아래에 다른 내용이 없다(맨 아래)
  - 클릭 4개 이하(탭 바는 5개 이상이고 닫기가 없다)
  - 광고·설치·혜택 같은 말이 있다
  - 묶는 노드가 없으므로 `PopupRoot.members`로 그 줄을 들고 다닌다. 뿌리 경로·크기는 닫기 버튼 것을 써서, 누르기·닫힘 확인은 그 버튼을 본다.
  - 못 잡으면 이유를 남긴다: `BANNER_MISS_ROW_ALONE`, `_TOO_NARROW`, `_TOO_TALL`, `_NOT_BOTTOM_MOST`, `_TOO_MANY_BUTTONS`, `_NO_PROMOTION`. 잡으면 `BANNER_FLAT_FOUND`.
  - 시험: 탭 바, 메뉴 줄(버튼 5개), 스낵바("저장했어요 [닫기]"), 아래에 내용이 더 있는 줄, 닫기 혼자는 배너가 아니다.
  - 한계: 누르기 직전 재판단은 닫기 버튼 노드만 다시 읽는다. 강력 모드(전부 닫기)에서는 통과하지만, 다른 프리셋에서는 종류가 바뀐 것으로 보고 묻게 된다.
- **P16:** 로그만으로는 건너뛰기가 나온 뒤 왜 감지가 없었는지 확정하지 못했다. 두 가지를 넣었다.
  - 진단: `VIDEO_COVER_WAITING`·`VIDEO_COVER_SHOWN`을 남긴다. 덮개가 아니면 이유를 남긴다: `VIDEO_COVER_MISS_NO_AD_MARK`, `_SEVERAL_SKIPS`, `_NO_SKIP_OR_COUNTDOWN`, `_NO_HOLDER`.
  - 다시 읽기: 카운트다운 중인 덮개는 1초 뒤 창 이벤트로 다시 읽는다(`VIDEO_COVER_REREAD`, 광고 하나에 최대 15초). 찾은 것 없이 읽을수록 다시 그리기 읽기 간격이 늘어나서, 건너뛰기가 나온 순간을 놓칠 수 있었기 때문이다.
- P02(정답지 안에서 kind·int1·preset이 엇갈림), P13·P24는 고치지 않았다. 노트 표에 준영·동준용 메모로 적었다.
- 검증(F36): 5개 Gradle 작업 통과, 단위 시험 debug 1304개·release 1289개 실패 0, lintDebug 오류 0(경고 14), INTERNET 0. 시험 추가 `F36Test` 3개. F34 시험 하나는 "평평한 배너는 못 잡음"을 적었던 것이라 F36 동작으로 고쳤다.

### 8-28. F37 — G마켓 웹 말풍선을 배너로 보고 실패 뒤 선택 창 (JVM 시험만, 실기기 미검증)

- **측정**(총괄, LG, 83fa3e8): 재현 앱 29/32(남은 P02·P13·P24는 정답지·정책). 실제 앱 11번가 앱·올리브영·옥션 웹은 닫힘, 7곳 오탐 0.
- **문제:** G마켓 웹 홈의 탭 바 바로 위에 떠 있는 말풍선 "매일 출첵하고 혜택 받아요! ×"(폭 약 45%, 높이 약 4%)를 배너로 봤다. 접근성 클릭으로 닫히지 않았고, 실패 뒤 선택 창이 떠 사용자를 방해했다.
- **고침 1 — 조용한 실패:** 하단 배너(`BOTTOM_BANNER`) 자동 닫기가 실패하면 기록만 한다(`BANNER_CLOSE_FAILED_QUIET`).
  - 다시 누르지 않는다(F20 실패 기록).
  - 선택 창·결과 안내를 내지 않고, 화면에 있는 동안 그대로 둔다.
  - 배너는 내용을 가리지 않기 때문이다.
- **고침 2 — 말풍선은 배너가 아님:**
  - 평평한 배너: 닫기를 감싸는 상자(고정 하단 영역 등)는 줄에 넣지 않는다. 그래서 반쪽 말풍선이 화면 폭 전체로 보이지 않고 `BANNER_MISS_TOO_NARROW`가 된다. 아래에 탭 바가 있으면 `NOT_BOTTOM_MOST`도 걸린다.
  - 상자 배너: 상자 안 내용이 화면 폭 60% 이상이고 상자 아래에 다른 내용이 없어야 한다(`bannerContentWide`).
  - 재현 앱 P03 형태(아래 가장자리에 붙은 가로 전체 배너)는 그대로 잡는다(시험).
- **조사 — 웹 ×가 접근성 클릭으로 안 닫힐 때(결론만, 구현 안 함):**
  - 크롬은 `ACTION_CLICK`을 그 요소의 합성 click 이벤트로 바꾼다. 페이지가 click이 아니라 `touchstart`·`pointerdown`에만 반응하면 닫히지 않는다.
  - TalkBack 두 번 탭도 같은 `ACTION_CLICK`이라 결과가 같다.
  - 남은 방법은 모두 안전 규칙에 걸리거나 엉뚱한 동작이 된다.
    - 그 자리 터치 제스처(`dispatchGesture`)는 좌표 탭이라 금지다(6-6).
    - 뒤로 가기(`GLOBAL_ACTION_BACK`)는 웹에서 페이지를 떠난다.
    - 부모 노드 클릭은 광고 링크일 수 있다(F27).
    - 웹 요소에는 `ACTION_DISMISS`가 없다.
  - **결론:** 대안 없음. 기록하고 조용히 둔다. 사용자가 원하면 스스로 닫는다.
- 검증(F37): 5개 Gradle 작업 통과, 단위 시험 debug 1305개·release 1290개 실패 0, lintDebug 오류 0(경고 14), INTERNET 0. 시험 추가: `F37Test` 1개(평평한 말풍선, 상자 말풍선, 상자 배너).

### 8-29. F38 — 음성 명령 결과 문구를 앱 TTS로도 말함 (JVM 시험만, 실기기 미검증)

- **측정**(우진, 삼성 SM-F966N, 70a5caf debug): P04에서 [말하기] 뒤 "허용해" → 선택 창에 "그 버튼은 대신 누를 수 없어요"가 표시만 되고 소리가 없었다. TalkBack을 켜도 꺼도 같았다.
- **원인:** 음성 시도 뒤 고정 문구(`VoiceOutcome.Say`)는 상태 줄 글자만 바꾸고 liveRegion에 맡겼다. TalkBack이 꺼져 있으면 liveRegion은 아무 소리도 내지 않는다. 켜져 있을 때 안 읽힌 이유는 아직 모른다(실기기 확인 필요).
- **고침:** `PromptControlsController.say()`가 문구를 화면에 보이고, 우리 TTS(`PopupAnnouncer`, `AnnouncementKind.ACTION_RESULT`)로 한 번 말한다.
  - 대상: Say 결과 전부(`ui_voice_refused`·`pick_on_screen`·`not_understood`·`not_here`·`ui_choice_cannot_press`·`ui_voice_unavailable`)와 [말하기] 누름 직후 문구(`listen_cancelled`·`unavailable`·`permission_hint`).
  - 말하지 않는 것: "듣고 있어요"(마이크에 들어감), 선택(Choose, 누르기 경로가 결과를 말함), 조용히 끝남(Quiet), 들은 말.
  - 말할 문구는 문자열 리소스뿐이다. 들은 말·화면 글자는 말하지도 로그에 남기지도 않는다.
- **두 번 읽기 막기 — 방식:** TTS로 말한 줄은 liveRegion을 끈다(`PromptControlsState.messageSpoken`). TalkBack 켜짐 여부로 나누지 않고 언제나 TTS 한 길만 쓴다.
  - 이유 1: 삼성에서 TalkBack을 켜도 liveRegion이 들리지 않았으므로, liveRegion에 기대는 쪽은 믿을 수 없다.
  - 이유 2: 첫 안내·결과 안내가 이미 같은 TTS로 나가므로 목소리·속도가 같다.
  - 이유 3: TalkBack 상태를 읽는 분기가 없어 시험·설명이 단순하다. 글자는 화면에 남아 TalkBack 사용자가 스와이프로 다시 읽을 수 있다.
  - 같은 선택 창의 다른 상태 줄("다시 확인했어요" 등 음성 시도가 아닌 것)은 그대로 liveRegion이다.
- **남은 점:** 이 문구도 안내 기록에 남으므로, 바로 뒤 [다시 듣기]는 팝업 안내가 아니라 이 문구를 다시 말한다.
- **"닫아"로 닫힌 경우 로그 확인:** 음성 선택은 탭과 같은 경로다. `adb logcat -s CfySimple`에서 `ASKED` → `USER_CHOSE_PRESS` → `CLICKED_LABELED_CLOSE` → `CLOSED_CONFIRMED` 순서면 정상이다. 로그만으로 음성과 탭은 구분되지 않는다.
- 검증(F38): 5개 Gradle 작업 통과, 단위 시험 debug 1310개·release 1295개 실패 0, lintDebug 오류 0(경고 14), INTERNET 0. 시험 추가 5개(`PromptControlsControllerTest`의 `f38*`).

### 8-30. F39 — TalkBack 켜고 음성 "닫아" 뒤 결과 대신 본문을 읽음 (JVM 시험만, 실기기 미검증)

- **측정**(우진, 삼성 SM-F966N, 661153e, TalkBack 켬, P04 [말하기] "닫아"): 팝업은 닫히는데 "닫았어요" 대신 P04 본문을 읽었다(여러 번 같음). TalkBack을 끄면 정상이었다. LG에서 손으로 [닫기]를 탭했을 때도 정상이었다.
  - 로그: `FOCUS_PRE_CLICK_NONE` → `FOCUS_PRE_CLICK_FAILED` → `RESULT_VIA_TALKBACK` → `RESULT_BANNER_SHOWN` → `RESULT_BANNER_FOCUSED` → `FOCUS_RECLAIMED=0`
- **코드로 본 원인:**
  - 음성 선택과 탭 선택은 서비스에서 같은 길을 탄다. 다른 것은 `ChoiceOrigin`뿐이다: `onAnswer` → `focusPageBeforeOurWindowGoes` → `pressAndConfirm` → `speakClosedThenFocusPage`.
  - `FOCUS_PRE_CLICK_NONE/FAILED`: P04에는 팝업 위에 초점을 둘 곳(툴바·제목·위 항목)이 없다. 그래서 누르기 전 초점 이동이 안 됐고, 우리 창이 없어질 때 TalkBack이 초점을 스스로 고른다.
  - `RESULT_BANNER_FOCUSED`: 결과 카드는 초점을 잡았다. 그 **뒤에** TalkBack이나 앱이 초점을 본문으로 옮겼고, TalkBack은 새 초점(본문)을 읽느라 카드 문장을 끊었다.
  - `FOCUS_RECLAIMED=0`: 초점 되찾기(F15)는 "닫힌 팝업 안이나 없어진 노드로 간 초점"만 되찾는다(`FocusReclaimRules`). 본문은 페이지 위라 `LEAVE`로 그대로 뒀다.
  - 탭과 결과가 달랐던 이유는 로그만으로 정할 수 없다. 기기(LG·삼성)와 TalkBack 버전도 달랐다. 음성 경로에서는 [말하기]를 누른 뒤 몇 초 동안 터치가 없고, 인식이 끝난 뒤 TalkBack이 초점을 다시 정하는 시점이 늦을 수 있다(추정). **삼성 탭 시험으로 가려야 한다.**
- **고침:**
  - 결과 카드가 초점을 쥐고 있는 동안에는 우리가 옮기지 않은 페이지 초점 이동도 카드로 되돌린다(`ReclaimVerdict.RECLAIM_TO_CARD`, `FOCUS_RECLAIM_PAGE_TO_CARD`).
  - 지켜보는 시간은 카드가 초점을 잡은 뒤 3초다(`FOCUS_RECLAIM_CARD_WINDOW_MILLIS`, 페이지만 볼 때는 1.5초).
  - 최대 3번이고, 사람이 화면을 만지거나 제스처를 하면 바로 끝난다. 사람의 탐색은 막지 않는다.
  - TTS 대안은 쓰지 않았다. TalkBack이 본문을 읽는 중에 TTS로 말하면 두 목소리가 겹친다. 카드로 되돌리면 TalkBack 한 목소리로 "닫았어요"를 읽는다.
- **진단 코드(글자 없음):**
  - `USER_CHOSE_BY_VOICE` / `USER_CHOSE_BY_TAP`: 고른 방법
  - 지켜보는 동안 남이 옮긴 초점: `FOCUS_OTHER_INTO_POPUP` / `FOCUS_OTHER_ON_PAGE`
  - `FOCUS_RECLAIM_PAGE_TO_CARD` / `FOCUS_RECLAIM_CARD_REFUSED`
- **기대 로그(삼성, TalkBack 켬, 음성):** `USER_CHOSE_PRESS` → `USER_CHOSE_BY_VOICE` → … → `RESULT_BANNER_FOCUSED` → `FOCUS_OTHER_ON_PAGE` → `FOCUS_RECLAIM_PAGE_TO_CARD` → `FOCUS_RECLAIMED=1`. "닫았어요"가 들려야 한다.
  - `FOCUS_OTHER_ON_PAGE`가 없는데도 본문을 읽으면, 초점은 그대로이고 TalkBack 쪽 읽기 순서 문제다. 그 로그를 보내 주기.
- 검증(F39): 5개 Gradle 작업 통과, 단위 시험 debug 1311개·release 1296개 실패 0, lintDebug 오류 0(경고 14), INTERNET 0. 시험 추가 1개(`PopupMemoryTest.f39…`).

### 8-31. 준영 PR #71 병합 + 정답지 P13·P24 (JVM 시험만, 실기기 미검증)

- **병합:** `origin` `refs/pull/71/head`(`f701b1a`, 661153e 기준). 충돌 없음(F39는 `SimpleCloseDecider`를 건드리지 않음).
  - B 9-1: "신규 가입 이벤트"·"회원가입 혜택"·"첫 가입 쿠폰" 같은 가입 혜택 이름은 결정이 아니라 혜택 언급이다(`DecisionKeywords.memberBenefit`). 그래서 P02는 쿠폰이 된다. "가입하기"·"회원가입"·동의·정기 결제가 함께 있으면 결정 그대로다.
  - B 9-2: `WordKindHint`는 "알 수 없는/확인되지 않은" 문구가 있으면 `DENIED`다.
  - 정답지 P02 `int1_kind`=COUPON(csv, `ScenarioCatalog`).
- **팀 결정(11/2) — P13·P24 프리셋:** `preset_close_all`·`preset_close_ads_and_coupons`·`preset_close_ads_only` = CLOSE·CLOSE·ANNOUNCE(P01과 같음).
  - 프리셋 목표는 앱 허용 버튼에서 계산하므로, 두 시나리오의 `correct_target`·`app_allowed_target`을 웹 버튼 `data-id="btn_close"`로 정했다. `forbidden_targets`는 `btn_ad_open`이다.
  - P24 `int1`(USER_DECISION, A_SUMMARY)은 A 회귀 시험이라 그대로 둔다.
  - 바꾼 파일: `docs/templates/popup_answer_key.csv`, `popup-test-app/.../ScenarioCatalog.kt`, `ScenarioCatalogTest.kt`(P13 목표, P24 허용 버튼). **동준 파일이다(팀 결정으로 반영).** `POPUP_TEST_APP.md` 설명은 동준이 맞춘다.
- 검증: 5개 Gradle 작업 + `:popup-test-app:testDebugUnitTest` 통과. 단위 시험 debug 1314개·release 1299개·재현 앱 26개 실패 0, lintDebug 오류 0(경고 14), INTERNET 0.

### 8-32. F40 — "광고만 닫기"에서 "오늘 하루 보지 않기"만 있으면 묻기 (JVM 시험만, 실기기 미검증)

- **요청(총괄 11/2):** "광고만 닫기" 프리셋에서 닫기 버튼 없이 "오늘 하루 보지 않기"만 있으면 자동으로 누르지 않고 묻는다. 다른 프리셋은 지금처럼 닫기 다음 순서로 누른다.
- **고침:** `HideTodaySafePreset.asks(autoCloseKinds, pick)`
  - 조건 1: 사람이 동의한 자동 닫기 종류가 "광고만 닫기"와 같다.
  - 조건 2: 고른 버튼이 `PressKind.HIDE_FOR_A_WHILE`이다("오늘 하루 보지 않기", "7일간 보지 않기", "다시 보지 않기" 등).
  - 두 조건이 맞으면 묻는다(`HIDE_TODAY_ASKED_SAFE_PRESET`). 선택 창에는 그 버튼이 그대로 나와서, 사람이 고르면 누른다.
  - "앞으로 항상 닫기"로 사람이 직접 고른 기억 규칙은 제외한다(그대로 누름).
  - "광고와 쿠폰 닫기"는 그대로 누른다. "전부 닫기"(강력 모드)는 닫기 → 오늘 하루 순서 그대로다.
- **이유:** 이 버튼은 닫기에 더해 "한동안 안 보기"라는 설정을 바꾼다. 가장 조심스러운 프리셋에서는 사람이 정하게 한다.
- 검증(F40): 5개 Gradle 작업 통과, 단위 시험 debug 1317개·release 1302개 실패 0, lintDebug 오류 0(경고 14), INTERNET 0. 시험 추가 `F40Test` 3개.

### 8-33. F41 — 결과 카드 [방금 닫은 내용 듣기] (JVM 시험만, 실기기 미검증)

- **무엇:** 닫았다는 결과 카드 아래에 [방금 닫은 내용 듣기] 버튼을 둔다. 누르면 닫은 팝업의 글자를 우리 TTS로 읽는다. 선택 창의 [내용 다시 읽기]와 같은 문장이다(`PromptSpeech.content`).
  - TalkBack 켬: 카드 문장에 초점이 가고, 오른쪽으로 한 번 쓸면 버튼이다. TalkBack 라벨은 버튼 글자 그대로 "방금 닫은 내용 듣기, 버튼"이다.
  - TalkBack 끔: 결과 문장은 전처럼 TTS로 말한다. 다시 들을 글자가 있을 때만 카드가 화면에 뜬다. live region은 TalkBack 없이 말하지 않으므로 두 번 말하지 않는다.
- **메모리만 쓴다(`LastClosedContent`):** 저장·로그를 하지 않는다. `toString`도 가린다.
  - 지우는 때: 카드가 사라질 때(시간 초과·교체·`dismiss`, `TalkBackResultBanner.onGone`), 다음 팝업을 처리할 때(선택 창을 띄울 때·자동으로 누르기 전), 서비스가 끝날 때.
  - 다음 팝업이 와서 지운 뒤 카드 버튼을 누르면 "방금 닫은 내용은 지웠어요."라고 말한다.
- **글자 없는 팝업:** 버튼을 숨긴다(`PromptSpeech.contentOrNull`이 null).
- 같이 바뀐 것: `lastClosedMemory`(카드가 사라질 때 초점을 둘 곳 계산용)를 TalkBack 초점 경로만이 아니라 모든 결과 경로에서 채운다.
- 진단 코드(글자 없음): `RESULT_HEAR_CLOSED_OFFERED` / `_READ` / `_GONE`
- 문자열: `ui_result_hear_closed`, `ui_result_hear_closed_gone`(`ui_voice_strings.xml`)
- 검증(F41): 5개 Gradle 작업 통과, 단위 시험 debug 1320개·release 1305개 실패 0, lintDebug 오류 0(경고 14), INTERNET 0. 시험 추가 `F41Test` 3개.
  - 카드 화면·TalkBack 이동은 JVM으로 시험할 수 없다. 실기기 확인이 필요하다.

### 8-34. 동준 PR #73 병합 (JVM 시험만, 실기기 미검증)

- `refs/pull/73/head`(648d959, b9827d3 위)를 충돌 없이 병합했다(`#72` 대체).
- 재현 앱에 P33 아래에서 올라오는 시트, P34 화면 전체를 덮는 큰 레이어(Dialog 표시 없음), P35 "앱에서 열기·모바일 웹" 시트, 시험 페이지 W09~W11이 생겼다. P13·P24는 우리 버전(`btn_close`) 그대로다.
- 재현 앱 단위 시험 27개와 앱 `AnswerKeyPipelineTest`(P33~P35 포함) 통과.

### 8-35. F42 — 결과 카드 표시 시간 (준영 제안, 팀 결정 11/2) (JVM 시험만, 실기기 미검증)

- **문제:** F41 카드는 초점이 있으면 6초, 없으면 4초 뒤에 사라졌다. TalkBack으로 결과를 듣고 → 쓸어서 [방금 닫은 내용 듣기]로 가서 → 두 번 누르기에는 빠듯했다. 카드가 사라지면 내용도 지워진다.
- **바꾼 것(버튼이 있는 카드만, `ResultCardClock`):** 0.5초마다 카드를 확인한다.
  - TalkBack 접근성 초점이 카드 안(문장이나 버튼)에 있으면 사라지지 않는다. 최대 15초(사용자 설정 시간이 더 길면 그 시간).
  - 초점이 카드 안에 있다가 밖으로 나가면 2초 뒤에 사라진다(확인 간격 때문에 최대 2.5초). 다시 들어오면 다시 유지한다.
  - 초점이 한 번도 안 들어왔으면(TalkBack 끔 포함) `AccessibilityManager.getRecommendedTimeoutMillis(기존 시간, FLAG_CONTENT_TEXT | FLAG_CONTENT_CONTROLS)` 뒤에 사라진다. 사용자 설정이 없으면 기존 시간 그대로이고, 기존보다 짧아지지 않는다.
  - 다음 팝업을 처리하면(선택 창을 띄울 때·자동으로 누르기 전) 버튼 있는 카드는 바로 사라진다. 이때는 초점을 페이지로 먼저 옮기지 않는다(다음 선택 창·누르기가 초점을 맡는다). 새 결과 카드는 전처럼 이전 카드를 바로 교체한다.
- 버튼이 없는 카드는 전과 같다(4초 / 초점이 있으면 6초).
- 진단(글자 없음): `RESULT_CARD_TIMEOUT_MS=<첫 시간 ms>`, `RESULT_CARD_HELD_FOCUS`(시간이 지났지만 초점이 있어 유지), `RESULT_CARD_FOCUS_LEFT`, `RESULT_CARD_HOLD_MAX`(15초까지 초점이 있어 사라짐), `RESULT_CARD_TIMED_OUT`, `RESULT_CARD_REPLACED_BY_NEXT`
- 실기기 확인 필요: 오버레이 창 안 View의 `isAccessibilityFocused`가 TalkBack 초점을 그대로 보여 주는지(LG Android 12).

### 8-36. F43 — P33 아래 시트를 못 찾음 (JVM 시험만, 실기기 미검증)

- **증상**(준영 갤럭시 SM-M446K Android 16, c279ee3): P33이 TalkBack을 켜도 꺼도 `NO_POPUP_ROOT`. 진단 줄 `WINDOWS app=1 [... A*S:wPrP] active=NO_WIDER_SAME_APP_WINDOW`.
- **원인(코드로 확인):** `WindowBoundsRules.activeBounded`는 활성 앱 창을 아래 셋 중 하나일 때만 팝업 창으로 본다.
  - 창이 보이는 부분보다 1.2배 이상 크다(F30, 터치를 다 받는 Dialog).
  - 화면 가운데 떠 있다(F33 `floating`: 가운데에서 가로 10%·세로 20% 안).
  - 같은 앱의 더 큰 창이 함께 보인다(F12).
  - P33 시트는 창과 뿌리가 같은 크기라 첫째가 아니다. 아래에 붙어 가운데가 아래로 치우치니 둘째도 아니다. 뒤 활동 창은 목록에 없어서 셋째도 아니다. 그래서 `NO_WIDER_SAME_APP_WINDOW`로 일반 창이 되고, 그 안에서 팝업 뿌리를 찾지 못했다. 실제 앱의 `BottomSheetDialog`도 같은 경로다.
- **고침(`WindowBoundsRules.bottomSheet`):** 위 셋에 해당하지 않으면, 활성 앱 창이 아래 시트 모양인지 본다.
  - 시트 모양: 폭이 화면의 90% 이상, 아래 끝이 화면 아래에서 8% 안(내비게이션 바 자리), 높이가 화면의 45% 이하(50:50 분할 화면 반쪽은 이보다 높다), 넓이가 화면의 5% 이상.
  - 분할 화면 구분(`BOTTOM_SHEET_REJECTED_SPLIT`): 분할 화면 구분선 창(`TYPE_SPLIT_SCREEN_DIVIDER`, 진단 줄 `D`)이 목록에 있거나, 다른 앱 창(다른 앱이든 같은 앱의 다른 창이든)이 시트 위에 있으면(그 창의 아래 끝 ≤ 시트 위 끝 + 8%) 분할 화면으로 보고 받지 않는다. 분할 화면에서는 위쪽 앱이 화면에 보이므로 목록에 나온다. Dialog 시트 뒤의 활동 창은 목록에 없거나 화면 전체라서 "위에 있는 창"이 아니다.
  - 같이 바꾼 것: F12의 "같은 앱의 더 큰 창"은 활성 창 아래에 깔려 있어야(활성 창 넓이의 절반 이상 겹침) 인정한다. 같은 앱을 위·아래 두 창으로 띄운 분할 화면에서 위 창이 더 크다는 이유만으로 아래 창을 팝업으로 보던 구멍을 막는다. Dialog와 그 활동 창은 늘 겹치므로 기존 사례는 그대로다.
- 진단(글자 없음): `BOTTOM_SHEET_WINDOW_ACCEPTED`, `BOTTOM_SHEET_REJECTED_SPLIT`, 진단 줄 `active=BOTTOM_SHEET_SPLIT`, 창 종류 `D`(분할 구분선).
- 고정한 기존 판정(`F43Test`, `F32RegressionTest`): P14 화면 전체 활동(NOT_BOUNDED), P15 토스트(시스템 창), 토스트 크기 앱 창, 아래 얇은 띠, 위 배너 창, 바닥에서 뜬 창, 50:50 분할 반쪽, F33 가운데 Dialog, F30 터치를 다 받는 Dialog, F12 활동 위 Dialog, 키보드. F33 시험의 "아래 시트 창만 있을 때 거절" 한 줄만 F43 뜻대로 "받음"으로 바꿨다. F29(닫힘 재확인) 시험은 노드 단위라 그대로 통과한다.
- 검증(F43): 5개 Gradle 작업 통과, 단위 시험 debug 1330개·release 1315개 실패 0(`F43Test` 4개 추가), lintDebug 오류 0(경고 14), INTERNET 0.
- 위험: 시트 창이 닫히는 애니메이션 동안 목록에 남으면 엄격 재확인이 잠깐 "다시 나타남"으로 볼 수 있다(F33 가운데 Dialog와 같은 조건, LG P04에서는 문제 없었음). 실기기 확인 필요.

### 8-37. F44 — 결과 카드 초점 되찾기·유지가 갤럭시에서 실패 (JVM 시험만, 실기기 미검증)

- **증상**(우진 SM-F966N, c279ee3, TalkBack 켬, P04 [닫기] 직접 탭, 2회): `RESULT_BANNER_FOCUSED` → 0.9초 뒤 `FOCUS_OTHER_ON_PAGE` → `FOCUS_RECLAIM_CARD_REFUSED` → `FOCUS_RECLAIMED=0` → 10초 `RESULT_CARD_HELD_FOCUS` → 15초 `RESULT_CARD_HOLD_MAX`. 사용자는 "닫았어요" 대신 P04 본문을 들었다.
- **원인:**
  - 되찾기(`refocus`)와 유지 판단(`focusInside`) 둘 다 우리 프로세스 View의 접근성 초점 표시(`isAccessibilityFocused`)를 봤다.
  - 로그로 보면, 페이지가 초점을 가져간 뒤에도 이 표시가 남아 있었다. 그래서 `View.performAccessibilityAction(ACTION_ACCESSIBILITY_FOCUS)`가 "이미 초점"으로 false를 냈고, 유지 판단은 계속 "카드 안"이라고 했다.
  - AOSP `AccessibilityWindowManager`는 초점 창이 바뀌면(`TYPE_VIEW_ACCESSIBILITY_FOCUSED`, `mAccessibilityFocusedWindowId != windowId`) 이전 창에 `clearAccessibilityFocusLocked`를 부른다. 이 호출은 핸들러로 미뤄 실행되고, 그 창 연결의 `clearAccessibilityFocus()`를 부른다.
  - 따라서 순정 코드라면 표시가 지워져야 한다. 갤럭시에서 이 해제가 우리 오버레이 창에 닿지 않았거나 늦은 것은 **추정**이다. 제조사 소스로 확인하지 못했다.
- **고침(`CardFocusRules`, `TalkBackResultBanner`):**
  - 초점이 카드에 있는지는 시스템에 묻는다. `findFocus(FOCUS_ACCESSIBILITY)`로 얻은 노드의 창이 카드의 접근성 오버레이 창인지 본다.
  - 카드 창 찾기: `getWindows()`의 `TYPE_ACCESSIBILITY_OVERLAY` 창 중 카드 View 화면 경계와 80% 이상 서로 겹치는 창. 우리 선택 창은 크기가 달라 걸리지 않는다.
  - 창 id는 같음·다름·모름으로만 비교하고 로그에 남기지 않는다.
  - 되찾기: 카드 창 뿌리(문장만 있으면 뿌리, F41 버튼이 있으면 첫 자식)의 노드에 서비스 쪽 `performAction(ACTION_ACCESSIBILITY_FOCUS)`를 한다. 거절되면(남은 표시) 그 노드의 초점을 지우고(`ACTION_CLEAR_ACCESSIBILITY_FOCUS`) 한 번만 다시 요청한다. 초점 이동이지 누르기가 아니다.
  - 카드 창을 못 찾을 때만 예전 View 경로(지운 뒤 요청)를 쓴다.
  - 노드는 그 호출 안에서 찾고 바로 놓는다(AGENTS 5장).
  - 유지 판단: 시스템이 "카드 안"이라고 할 때만 유지한다. 페이지로 나가면 F42 규칙대로 `RESULT_CARD_FOCUS_LEFT`가 찍히고 2초 뒤 사라진다. 카드 창을 못 찾을 때만 View 표시를 쓴다(15초 상한 그대로).
  - 사라질 때 페이지로 초점을 옮기는 F17 판단도 같은 시스템 기준이다.
- 진단(글자 없음): `FOCUS_RECLAIM_CARD_BY_NODE`, `FOCUS_RECLAIM_CARD_AFTER_CLEAR`, `FOCUS_RECLAIM_CARD_BY_VIEW`, (기존) `FOCUS_RECLAIM_CARD_REFUSED`, `RESULT_CARD_WINDOW_UNKNOWN`(카드마다 1회).
- 검증(F44): 5개 Gradle 작업 통과, 단위 시험 debug 1335개·release 1320개 실패 0(`F44Test` 5개 추가), lintDebug 오류 0(경고 15: 대체 View 경로의 `AccessibilityFocus` 경고 1개 추가, 시연 모드 6-0 범위), INTERNET 0.
- 위험: 서비스가 0.5초마다 창 목록과 초점을 묻는다(카드가 떠 있는 최대 15초 동안 호출 2회씩). 실기기 확인 필요.
- 실측(11/2 밤, 총괄 전달): 우진 SM-F966N `ac66631`에서 직접 닫기·음성 닫기·내용 듣기 각 5/5 통과. `FOCUS_RECLAIM_CARD_AFTER_CLEAR`가 반복해서 나와 "View 표시가 남는다"는 추정을 뒷받침한다(LG에서는 0회). LG도 통과.

### 8-38. F45 — [방금 닫은 내용 듣기]를 누르면 카드 시간을 다시 센다 (JVM 시험만, 실기기 미검증)

- **증상**(우진 SM-F966N, `ac66631`): `RESULT_HEAR_CLOSED_READ` 직후 `RESULT_CARD_HOLD_MAX`. `ResultCardClock`이 카드가 뜬 때부터 15초를 세서, 늦게 누르면 긴 내용을 읽는 도중 카드가 사라질 수 있다. 사라질 때 F17이 초점을 페이지로 옮긴다. 낭독이 실제로 끊기는지는 실기기에서 확인하지 못했다.
- **고침:**
  - 누르는 순간 `ResultCardClock.restartOnRead`로 시계를 다시 시작한다.
  - 유지 상한 `readHoldMillis` = 15초 + 글자 수 × 200ms ÷ 말 빠르기. 최대 60초이고, 카드 자체 시간(F42의 `getRecommendedTimeoutMillis` 값)보다 짧지 않다.
  - 200ms는 1배속 한국어 TTS가 띄어쓰기 포함 초당 약 5글자를 읽는다는 **추정**이다(측정하지 않음). 말 빠르기는 우리 설정(`SpeechRates.resolve`, 시스템 TTS 빠르기 따름)이며, 0.5배보다 느리면 0.5배로 친다.
  - 예: 100글자, 1배속이면 35초.
  - 다시 시작한 뒤에도 초점이 카드 밖으로 나가면 2초 뒤 사라진다(F42 그대로).
  - TalkBack이 꺼져 초점이 없으면 상한까지 남는다.
  - 다시 누르면 다시 센다.
  - 낭독 중인지는 묻지 않고 시간만 본다.
  - 글자 수만 쓰고 글자는 로그에 남기지 않는다.
- 진단: `RESULT_CARD_RESTARTED_ON_READ`, `RESULT_CARD_READ_HOLD_MS=<ms>`.
- 검증(F45): 5개 Gradle 작업 통과, 단위 시험 debug 1340개·release 1325개 실패 0(`F45Test` 5개 추가), lintDebug 오류 0(경고 15, 그대로), INTERNET 0.
- 한계(F45): 60초가 넘는 긴 내용(1배속 약 225글자 이상)은 상한에서 카드가 사라진다. 낭독은 우리 TTS가 계속하지만, F17 초점 이동 때 TalkBack이 페이지 항목을 말하며 겹칠 수 있다(실기기 확인 필요).

### 8-39. F46 — 선택 창 첫 초점이 갤럭시에서 안 옴: 진단 줄 + 비우고 다시 요청 (JVM 시험만, 실기기 미검증)

- **증상**(우진 SM-F966N, `ac66631`, TalkBack 켬, [선택 창이 뜨면 안내를 맡기기] 켬, 11번가 앱 동의 포함 팝업 2회): `CLASSIFIED_DECISION` → `BLOCKED_RISKY` → `DECISION_ASKED` → `ASKED`. 선택 창은 뜨지만 초점이 오지 않았다. LG P04는 정상.
- **코드 확인:**
  - 결정 팝업(`DECISION_ASKED`)도 일반 묻기(P04 `DECIDED_ASK`)와 같은 `ask()` → 같은 `takeOver` 분기를 탄다.
  - 둘 다 `READ_POPUP_TEXT_ON_PROMPT = false`라 `popupText`가 없어서 `oneVoice`이다. `FIRST_ITEM`을 25ms 간격으로 최대 40번 시도하고, 성공하면 TalkBack만 읽는다.
  - 결정 팝업은 `cannotPressForUser`와 첫 줄 문장만 다르다. 초점 경로의 차이는 코드에서 찾지 못했다.
- **원인 후보(추정, 실기기 확인 전):**
  1. 갤럭시에서 View 초점 표시가 안 지워져 FOCUS가 거절됨(F44에서 결과 카드로 확인된 현상, `FOCUS_RECLAIM_CARD_AFTER_CLEAR` 반복). 선택 창도 같은 Compose 노드 경로라 가능성이 높다. 고침에 넣었다.
  2. 창 제목 비교 실패(`NO_OWN_WINDOW`). `ChoiceOverlayIdentity`는 창 제목이 `ui_choice_pane_title`과 같아야 우리 창으로 본다. 제목은 `LayoutParams.title`로만 준다. 이것이 `AccessibilityWindowInfo.getTitle()`에 그대로 나오는지는 확인하지 못했다(**추측**, LG에서는 맞음). 새 진단 줄의 `own=`·`titleNull=`·`titleOther=`로 구분한다.
  3. 실제 앱(11번가)에서는 TalkBack이 팝업을 읽으며 스스로 초점을 옮긴다. 우리 요청이 성공(`OK`)해도 그 뒤 TalkBack이 가져갈 수 있다. 진단 줄이 `result=OK`인데 초점이 없으면 이 경우다.
- **고침(`PromptFocusDiagnosis.kt`, `AccessibilityChoiceOverlay.WindowFocus`):**
  - 시도마다 결과를 `PromptFocusOutcome`으로 남긴다.
    - 옮김: `OK`, `AFTER_CLEAR`, `ALREADY_FOCUSED`
    - 못 옮김: `NOT_SHOWING`, `NO_OWN_WINDOW`, `MANY_OWN_WINDOWS`, `NO_TARGET_NODE`, `NOT_VISIBLE`, `REFUSED`, `ERROR`
  - FOCUS가 거절되면 방법을 바꾼다.
    - 시스템 초점(`findFocus`)이 이미 그 노드에 있으면 그대로 둔다(`ALREADY_FOCUSED`).
    - 아니면 그 노드의 초점을 지우고(`ACTION_CLEAR_ACCESSIBILITY_FOCUS`) 한 번 더 요청한다(`AFTER_CLEAR`). 초점 이동이지 누르기가 아니다.
    - `CHOICE_BUTTON` 이동도 같은 경로를 쓴다.
  - 초점 이동 한 번(최대 40번 시도)이 끝나면 Debug 빌드에서만 `CfySimple`에 한 줄을 남긴다. 화면 글자·창 제목·id는 없다.
    - 형식: `PROMPT_FOCUS target=FIRST_ITEM result=<마지막> attempts=<n> tries=[<결과:횟수>…] own=<우리 앱 오버레이 창 수> titleNull=<제목 없음> titleOther=<다른 제목>`
    - 창 수 셋은 우리 창을 못 찾은 시도가 있을 때만 붙는다.
- 공용 계약(core/model·contract)은 바꾸지 않았다. `PromptFocus`에 기본 구현이 있는 `finished`를 더한 것은 C 패키지 안의 인터페이스다.
- 실측(11/3, 총괄 전달, `76245ff`): 우진 11번가 결정 팝업 3회 모두 `PROMPT_FOCUS target=FIRST_ITEM result=OK`(tries `NO_OWN_WINDOW:2`, `OK:1`). 그런데 초점은 1/3만 선택 창에 남았다. 원인 후보 3("요청은 성공했는데 뒤이어 초점을 가져감")에 해당하며 F47에서 다룬다.
- 검증(F46): 5개 Gradle 작업 통과, 단위 시험 debug 1345개·release 1330개 실패 0(`F46Test` 5개 추가), lintDebug 오류 0(경고 15, 그대로), INTERNET 0.

### 8-40. F47 — 되찾기 유예: 사용자 스와이프는 되돌리지 않고, 선택 창 첫 초점은 지킨다 (JVM 시험만, 실기기 미검증)

- **증상 1**(우진 SM-F966N, `76245ff`, TalkBack 켬): 결과 카드에서 한 손가락 스와이프로 페이지로 나가려 하면 되돌아왔다.
  - 로그: `FOCUS_OTHER_ON_PAGE` 25.131 → `FOCUS_RECLAIM_CARD_AFTER_CLEAR`·`FOCUS_RECLAIM_PAGE_TO_CARD` 25.134 → `FOCUS_RECLAIM_STOPPED_BY_USER` 25.348.
  - 스와이프의 터치 이벤트가 초점 이동보다 약 214ms 늦게 왔는데, 되찾기는 3ms 만에 했다.
- **증상 2**(같은 기기, 11번가 결정 팝업): `PROMPT_FOCUS result=OK`였는데 2/3은 첫 줄로 온 직후 초점이 원래 팝업으로 돌아갔다.
- **고침(`ReclaimDeferral`, 순수):** 우리가 하지 않은 초점 이동을 보면 바로 되찾지 않고 `DEFER_MILLIS` 400ms 기다린다(`FOCUS_RECLAIM_DEFERRED`).
  - 그사이에 사용자의 터치·제스처 이벤트가 오면 사용자 이동으로 보고 되찾지 않는다(`FOCUS_RECLAIM_SKIPPED_USER_MOVE`, 감시 종료). 이동 전 `USER_BEFORE_MILLIS` 500ms 안에 온 이벤트도 같다. 이벤트 종류는 `TYPE_TOUCH_INTERACTION_START`, `TYPE_GESTURE_DETECTION_START`, `TYPE_TOUCH_EXPLORATION_GESTURE_START`이며, 서비스 설정이 받는 것은 이 셋뿐이다.
  - 그 뒤에 다른 초점 이동이 또 왔으면 그 이동의 유예가 정한다.
  - 초점이 이미 카드에 돌아와 있으면 아무것도 안 한다.
  - 최대 횟수(3번)는 그대로다.
  - 400ms의 근거: 실측 지연 214ms(한 번, 우리 서비스 `notificationTimeout` 100ms)의 약 2배. 페이지가 스스로 초점을 가져간 경우에는 TalkBack이 많이 읽기 전에 되찾을 수 있는 길이다.
- **F17 터치 유예(800ms) 확인:**
  - 감시를 시작한 직후 800ms 안의 터치는 [닫기]를 고른 두 번 탭으로 보고 무시한다. 새 규칙에서도 이 터치는 사용자 이동으로 세지 않는다(`userTouchAfterGrace`).
  - 실측 로그에서는 감시 시작(누른 순간)과 스와이프 사이가 카드 표시(0.9초 전)보다 길었으므로, 이 유예가 원인은 아니다(추정).
  - 다만 감시 시작 뒤 800ms 안의 진짜 스와이프는 지금도 무시된다(한계).
- **선택 창 첫 초점 지키기:** 첫 초점이 성공하면(`OK`·`AFTER_CLEAR`·`ALREADY_FOCUSED`, 창마다 한 번) 2초 동안 지킨다(`PROMPT_FOCUS_HOLD_STARTED`).
  - 다른 앱 쪽으로 초점이 가면 `PROMPT_FOCUS_LOST`를 남기고 같은 400ms 유예를 거친다. 사용자 조작이 없고 선택 창이 그대로면 첫 항목으로 되찾는다(`PROMPT_FOCUS_RECLAIMED`, 실패하면 `_RECLAIM_FAILED`). 최대 2번까지이고, 넘으면 `PROMPT_FOCUS_HOLD_LIMIT`.
  - 사용자 터치·제스처가 오면 즉시 끝난다(`PROMPT_FOCUS_HOLD_END_BY_USER`). 사용자가 고르거나 선택 창이 사라져도 끝난다.
  - TalkBack 안내 맡기기 설정이 꺼져 있으면 `PromptFocus.NONE`이라 시작하지 않는다.
- **바뀐 파일:**
  - 새 파일 `simpleclose/ReclaimDeferral.kt`
  - `SimpleCloseAccessibilityService.kt`(유예, `reclaimToCard`·`reclaimToTarget`으로 분리, `PromptHold`)
  - `TalkBackResultBanner.focusIsOnCard`
  - `AccessibilityChoiceOverlay`(`onFirstFocus`)
  - `PromptFocusTally.lastMoved`
  - `SimpleTrace`
  - 새 시험 `F47Test`
- 검증(F47): 5개 Gradle 작업 통과, 단위 시험 debug 1351개·release 1336개 실패 0(`F47Test` 6개 추가), lintDebug 오류 0(경고 15, 그대로), INTERNET 0.

### 8-41. F48 — 누르기 직전 재확인이 왜 멈췄는지 나눠 남기고, 버튼만 안 맞으면 실패를 알린다 (JVM 시험만, 원인 미확정)

- **증상**(동준 에뮬레이터 API 37, 76245ff, 시험 앱 P34 큰 레이어 #73 위쪽 여백 수정본, TalkBack 꺼짐 추정): `DETECTED_DIM_OVERLAY` → `CLASSIFIED_AD` → `BLOCKED_FORBIDDEN` → `CANDIDATE_FOUND` → `DECIDED_AUTO_CLOSE_STRONG` → `RECHECK_TARGET_GONE`. 그 뒤 안내 없이 끝났고(STOP len=0), 레이어는 그대로였다. 전날 b9827d3에서는 `CLICKED_LABELED_CLOSE`로 닫혔다.
- **원인: 확정하지 못했다.** 코드로 확인한 것:
  - 재확인(`pressAndConfirm`의 세 조건)과 `rootStillPresent`·`targetMatches`는 b9827d3과 76245ff 사이에 바뀌지 않았다.
  - F43은 P34에 닿지 않는다. P34 레이어는 액티비티 창 안에 있고, 그 창은 화면 전체라 `bounded`가 거짓이다. 그래서 F43이 고친 `activeJudgement`의 뒤쪽(같은 앱 더 큰 창·아래 시트)까지 가지 않는다.
  - P34는 `DIM_OVERLAY`라 창 id를 묶지 않는다(`bindWindow`). 세 조건 모두 그 순간의 활성 창에서 본다. 이것도 예전과 같다.
  - LG 벨벳은 ac66631(F43 이후)에서 P34 자동 닫힘을 통과했다(11/2 23:06~, TalkBack 끔).
  - F44~F47은 TalkBack 초점 쪽만 바꿨다. 자동 닫기 경로에서 새로 끼는 것은 F42의 `dismissForNextPopup`뿐인데, 카드가 없으면(TalkBack 끔) 아무것도 하지 않는다.
  - → 같은 코드에서 에뮬레이터 실행마다 결과가 다른 것으로 보인다(타이밍·화면 상태). 세 조건 중 무엇이 거짓이었는지는 로그에 없었다.
- **후보(추정, 확인 필요):**
  - (가) 화면이 바뀌는 순간이라 활성 창이 잠깐 없음 → `NO_ACTIVE_WINDOW`.
  - (나) 읽은 뒤 레이어 위치가 바뀜 → `ROOT_MOVED`. 예: #73 수정의 상태 표시줄 여백이 늦게 들어감, 창이 열리는 애니메이션 중에 읽음. `dt=`가 상태 표시줄 높이면 여백 쪽이다.
  - (다) 버튼이 아직 누를 수 없거나 이름이 다름 → `TARGET_*`.
- **바꾼 것:**
  - **재확인 자체는 그대로다.** 세 조건, 순서, 정확히 같은 경계, 같은 이름 모두 이전과 같다(AGENTS 6-5·6-7).
  - 실패하면 `RECHECK_TARGET_GONE`(기존 코드) 뒤에 원인을 남긴다.
    - 갈래 코드: `RECHECK_GONE_PACKAGE` / `_ROOT` / `_TARGET`.
    - Debug 한 줄: `RECHECK_MISS reason=…`. 이유는 `NO_ACTIVE_WINDOW`, `OTHER_APP`, `ROOT_UNREADABLE`, `ROOT_MISSING`, `ROOT_HIDDEN`, `ROOT_MOVED`, `ROOT_NOT_DIALOG`, `TARGET_MISSING`, `TARGET_HIDDEN`, `TARGET_DISABLED`, `TARGET_NOT_CLICKABLE`, `TARGET_NAME_CHANGED`, `TARGET_UNREADABLE`.
    - `ROOT_MOVED`면 네 변이 움직인 픽셀 `dl= dt= dr= db=`도 남긴다. 글자·id·패키지는 남기지 않는다.
  - **안내(C 결정):** 앱이 그대로 앞이고 팝업 루트도 그대로인데 버튼만 안 맞은 경우(`TARGET_*`)는 자동 닫기 실패와 같은 경로로 알린다(F20). `RECHECK_TARGET_FAILED_ASKED`를 남기고, 선택 창 첫 줄로 "대신 닫지 못했어요"를 말한다.
    - 누른 것이 없으므로 그 버튼은 선택지에 남긴다. 사람이 고르면 다시 재확인한다.
    - 이 팝업은 이 세션에서 다시 자동으로 누르지 않는다(`autoFailed`).
    - 아래 배너(F37)는 지금처럼 조용히 둔다.
  - 다른 앱으로 갔거나(`PACKAGE`) 팝업 루트가 사라지거나 바뀐 경우(`ROOT`)는 지금처럼 조용하다.
- **P34를 통과시키는 수정은 하지 않았다.** 원인을 모른 채 재확인을 다시 하거나 느슨하게 하면 6-5·6-7을 약하게 할 수 있다. 에뮬레이터에서 `RECHECK_MISS` 줄을 받은 뒤 정한다.
  - (가)라면: 활성 창이 없을 때만 잠깐 다시 보는 방안(세 조건은 그대로).
  - (나)라면: 새로 읽어 다시 판단하는 방안(옛 분석으로 누르지 않음).
- **바뀐 파일:**
  - 새 파일 `simpleclose/ClickRecheck.kt`(순수)
  - `SimpleTreeCollector`: `rootRecheck`·`targetMiss`. 기존 `rootStillPresent`·`targetMatches`는 이것을 감싸며 답이 같다.
  - `SimpleCloseAccessibilityService`: `pressAndConfirm`의 `onRecheckMiss`, 자동 경로의 안내 분기, `rememberAutoFailed`.
  - `SimpleTrace`: 코드 4개.
  - 새 시험 `F48Test`
- 동준 #73의 P34 시험 앱 수정(위쪽 여백)은 `5b701dd`(`origin/feature/d-new-popup-types-v2`)이고, integration에는 아직 없다(병합 안 함).

### 8-42. F49 — 크롬 웹 클릭이 페이지에 닿게(이벤트 1종 추가), 위아래로만 밀린 팝업은 다시 판단, 팝업이 그대로면 항상 묻기 (JVM 시험만, 에뮬레이터 미검증)

- **P13 웹 클릭(메인 세션 에뮬레이터 Pixel_8 API 37, WebView 145.0.7632.218, 7a3912c):**
  - 증상: 우리 서비스만 켰을 때 3/3 `CLICKED_LABELED_CLOSE` → `NOT_CLOSED_STILL_THERE`, 페이지 콘솔에 클릭이 안 찍힘. TalkBack이나 A 서비스(typeViewScrolled를 받음)를 같이 켜면 1/1 닫힘.
  - 근거(메인 세션이 확인한 Chromium 소스 `AccessibilityStateDelegateImpl`): 켜진 서비스의 이벤트 마스크에 VIEW_SELECTED·VIEW_SCROLLED·ANNOUNCEMENT 중 하나가 있어야 웹 접근성을 온전히 켠다. 우리 서비스 설정에는 셋 다 없었다.
  - 고침: `ui_simple_close_service.xml` `accessibilityEventTypes`에 `typeViewScrolled`만 더했다. 권한·capability는 그대로다(`canPerformGestures` false).
  - 이 이벤트는 `onAccessibilityEvent` 맨 앞에서 바로 버린다(`ServiceEventRules.dropAtOnce`). 트리 읽기나 재검사 예약은 없다.
  - LG(Android 12, 옛 WebView)는 이 판정 전 버전이라 됐던 것으로 본다(메인 세션 판단).
- **P34 위아래 밀림(동준 7a3912c ×5: 3/5 닫힘, 2/5 `RECHECK_GONE_ROOT`):**
  - 2/5의 `RECHECK_MISS`: `reason=ROOT_MOVED dl=0 dt=-53 dr=0 db=-53`, `dl=0 dt=16 dr=0 db=16`. 시험 앱 #73 insets가 첫 화면 직후 레이어를 민 것이다.
  - 고침: 크기가 같고 위아래로만 밀렸으면(`dl=dr=0`, `dt=db≠0`, 화면 높이의 25% 이하, `ClickRecheck.verticalShift`) 그 좌표로 누르지 않는다. `RECHECK_SHIFTED_REREAD`를 남기고 화면을 새로 읽어 감지·분류·후보·B 판정을 처음부터 다시 한다.
    - 새 판단이 같은 닫기 버튼(같은 이름, 루트 안 같은 위치·크기, `sameTarget`)을 고르면 `RECHECK_SHIFTED_SAME_TARGET`. 그 새 판단으로 재확인부터 다시 거쳐 누른다.
    - 다른 버튼이면 `RECHECK_SHIFTED_DIFFERENT`. 누르지 않고 실패 안내와 선택 창을 띄운다.
    - 다시 판단은 팝업당 1번이다(`shiftRereadDone`).
    - 다시 읽기가 3초 안에 쓰이지 않으면 `RECHECK_SHIFTED_REREAD_UNUSED`. 한 번 더 보고, 팝업이 그대로면 묻는다.
  - 누른 적이 없으므로 규칙 6과 부딪히지 않는다. 재확인(규칙 5)과 옛 분석 금지(규칙 7)는 그대로다.
- **APP-16(물음)과 APP-17(조용함)이 갈린 이유(코드로 찾음):**
  - 팝업 열쇠(`PopupRoot.key`)에 경계가 들어 있어서, 밀린 레이어는 새 팝업 열쇠가 된다.
  - 밀린 화면을 읽은 시점이 첫 누르기 작업 도중이면, 그 열쇠가 "본 것"(`seenAt`)으로 찍힌 뒤 `SAME_POPUP_COOLDOWN`으로 끝난다. 화면이 멈춰 있으면 이벤트가 더 오지 않아 조용히 끝난다(APP-17).
  - 작업이 끝난 뒤였으면 다시 처리됐다(APP-16). 다만 그 두 번째 처리가 어느 실패 경로로 "대신 닫지 못했어요"가 됐는지는 그 회차의 줄이 있어야 확정할 수 있다.
- **팝업이 그대로면 항상 묻기(C 결정, `ClickRecheck.afterMiss`):** 자동 누르기가 재확인에서 멈추면 다음 셋 중 하나다.
  - 다른 앱이 앞(`PACKAGE`) → 조용히.
  - 위아래 밀림 첫 번째 → 다시 판단.
  - 그 밖 → 루트가 안 맞은 경우는 화면을 다시 읽어(`popupStillShown`, `PopupMemory.reappearance`, 밀린 것도 같은 팝업) `RECHECK_POPUP_STILL_SHOWN` / `_GONE` / `_UNKNOWN`을 남긴다.
    - 그대로면 실패 안내와 선택 창(`RECHECK_ROOT_FAILED_ASKED`)이다. 버튼만 안 맞은 경우는 `RECHECK_TARGET_FAILED_ASKED`.
    - 사라졌거나 모르면 조용히.
    - 아래 배너는 지금처럼 조용히(F37).
  - 다시 판단 뒤에 또 멈춘 경우도 같은 규칙으로 묻는다.
  - 사람이 직접 고른 누르기의 STALE 안내(`speakOwnPress`)는 그대로다.
- **바뀐 파일:**
  - `res/xml/ui_simple_close_service.xml`
  - 새 파일 `simpleclose/ServiceEventRules.kt`
  - `ClickRecheck.kt`: `verticalShift`, `afterMiss`, `sameTarget`, `MissStep`, `ShiftReread`. F48의 `saysFailure`는 `afterMiss`로 바뀜.
  - `SimpleCloseAccessibilityService.kt`
  - `SimpleTrace`: 코드 9개
  - `F48Test`(안내 시험 1개를 새 규칙으로)
  - 새 시험 `F49Test`

### 8-43. F50 — 창이 멈춘 뒤 판단, 창으로 "팝업 그대로" 판단, 감지 없음 진단 (JVM 시험만, 에뮬레이터 미검증)

- **증상**(메인 세션 에뮬레이터 Pixel_8 API 37, c0dbf75, 시험 앱 1f990c7, TalkBack 끔). 서비스는 매 회 다시 시작했다.
  - **P01 6회:** 닫힘 2, 물음 3, 조용히 끝 1.
    - 물음 3회: `READ_WHOLE_ABORTED` → `DETECTED_DIALOG_WINDOW` → `POPUP_TEXTS=3` → `CANDIDATE_NONE` → `DECIDED_ASK`. 닫힌 회차는 `POPUP_TEXTS=4`, `CANDIDATE_FOUND`였다. 창이 열리는 애니메이션 중에 읽어서 닫기 버튼이 아직 트리에 없었다.
    - 조용히 끝 1회: `RECHECK_MISS reason=ROOT_MOVED dl=-42 dt=821 dr=-42 db=821` → `RECHECK_POPUP_GONE`. 그런데 대화상자 창은 화면에 그대로 있었다.
  - **P34 8회:** 3회는 13초 동안 `DETECTED_*`가 없었다(읽기 9번).
- **1. 멈춘 뒤 판단(`SettleRules`, `SETTLE_BEFORE_JUDGING`):**
  - 팝업 루트를 처음 봤거나, 직전 읽기와 비교해 경계나 루트 안 노드 수가 바뀌었으면 판단(분류·후보·결정)을 미룬다. `SETTLE_WAIT`를 남기고 150ms 뒤 다시 읽는다(창 이벤트로).
  - 두 번 연속 같으면 판단한다(`SETTLE_STABLE`). 처음 본 지 1초가 지났거나 5번 읽어도 계속 바뀌면 그때 상태로 판단한다(`SETTLE_LIMIT`, 지금까지의 규칙).
  - 기다리는 동안에는 누르지도 말하지도 않는다.
  - 값의 근거: Android 기본 애니메이션 시간 `config_shortAnimTime` 200ms, `config_mediumAnimTime` 400ms, `config_longAnimTime` 500ms.
    - 150ms마다 읽으면 들어오는 창을 적어도 한 번은 움직이는 중에, 그다음은 멈춘 뒤에 본다.
    - 상한 1초는 가장 긴 값의 2배다.
  - 같은 팝업인지는 창·종류·경로로 본다(경계는 넣지 않음). 2초 동안 안 보이면 새로 본 것으로 친다.
  - 정상 경로 비용: 새 팝업마다 다시 읽기 한 번(150ms + 읽기 한 번). `SETTLE_MS`·`SETTLE_READS`로 남긴다.
  - "후보 없음이면 한 번 더 읽기"는 따로 넣지 않았다. 닫기 버튼이 늦게 생기면 노드 수가 바뀌므로 settle이 다시 읽는다.
- **2. 팝업 그대로 판단(`popupStillShown`):**
  - 자기 창이 있는 팝업(`PopupRoot.windowId`, Dialog 창 등): 그 창이 아직 창 목록에 있고 그 앱이 앞이면 그대로다(`StillShownRules.byWindow`). 경계는 보지 않는다.
  - 페이지 창 안 팝업: 다시 읽어서 팝업 루트 안에 우리 글자가 있으면 그대로다(`PopupMemory.foundAnywhere`, 위치는 비교 안 함).
  - 원인: 예전 판단은 첫 읽기 자리(애니메이션 중, 821px 아래)와 겹치는지를 봤다. 그래서 멈춘 대화상자를 "사라짐"으로 봤다.
- **다시 판단 대상 넓힘(`ClickRecheck.sameSizeMove`):** 크기가 같은 이동이면 좌우·멀리 움직여도 F49 다시 판단(새로 읽어 처음부터, 팝업당 1번)을 한다. 25% 상한은 뺐다.
  - 근거: 옛 좌표로 누르지 않고, 새 판단과 그 판단의 재확인이 정한다. 그래서 이동 거리는 안전과 상관없다.
  - 크기가 바뀐 경우는 여전히 넣지 않는다(다른 노드거나 아직 열리는 중). 그대로면 묻는다.
- **3. 감지 없음 진단:** `NO_POPUP_ROOT` 때 Debug 한 줄을 남긴다. 직전 줄과 다를 때만 남긴다.
  - 형식: `NO_ROOT mode= probe= nodes= shown= clickable= bigBlank= namedClose= aborted= wholeOffMs=`.
  - 글자·id·패키지는 없고, 개수와 코드뿐이다.
  - 코드로 보이는 후보(추정): 전체 읽기가 600ms 상한에서 끊기면(`READ_WHOLE_ABORTED`) 10초 동안 전체 읽기를 하지 않는다. 그동안 probe가 레이어를 못 찾으면 읽기마다 노드 0개로 끝날 수 있다. 이 줄의 `wholeOffMs`와 `nodes=0`, `probe=MISS`로 확인할 수 있다. 원인이 확인되지 않아 고치지는 않았다.
- **바뀐 파일:**
  - 새 파일 `simpleclose/PopupSettle.kt`(`SettleRules`, `StillShownRules`, `NoRootLine`)
  - `PopupMemory.foundAnywhere`
  - `ClickRecheck.sameSizeMove`(F49의 `verticalShift` 대신)
  - `SimpleTreeCollector.windowListed`
  - `SimpleCloseEngineFeature.SETTLE_BEFORE_JUDGING`
  - `SimpleCloseAccessibilityService`
  - `SimpleTrace`(코드 3개)
  - `F49Test`(이동 시험 2개를 새 규칙으로)
  - 새 시험 `F50Test`

### 8-44. F51 — 읽기 한도에 걸린 큰 페이지에서 뒤쪽부터 한 번 더 읽기 (JVM 시험만, 실기기·에뮬레이터 미검증)

- **증상**(준영 갤럭시 SM-M446K Android 16, 11/4, 예스24 웹): 노드 약 2,600개, 팝업은 걸음 순서로 약 2,500번째. 전체 읽기가 `WholeReadCap`(1,500개·0.6초)에서 멈춰 `NO_ROOT … aborted=true`.
  - 화면 전체를 덮는 빈 상자 "닫기" 문제는 준영이 B 쪽에서 따로 고친다. F51은 읽기만 다룬다.
- **원인(코드로 봄):**
  - 전체 읽기는 앞에서부터(첫 자식 먼저) 걷는다. 1,500개에서 멈추면 페이지 앞부분만 남는다.
  - `WholeReadCap.complete`는 **읽은 부분에서 찾은 팝업**만 다시 통째로 읽는다(잘린 결제·동의 버튼 보완). 읽은 부분에 팝업이 없으면 아무것도 하지 않는다.
  - 프로브는 뒤에서부터 걷지만 200개까지이고, Dialog·어두운 배경·앞 레이어만 찾으며 노드를 남기지 않는다. 예스24 팝업은 이 셋에 걸리지 않았다(`PopupRootFinder`의 다른 규칙이 필요한 모양).
  - 그 뒤 10초 동안은 프로브만 읽는다(`ABORTED_RETRY_MILLIS`). 그래서 팝업까지 가는 읽기가 없다.
- **고침(`TailRead`, `TAIL_READ_AFTER_CAP`):** 전체 읽기가 한도에서 멈췄고 읽은 부분에 팝업이 없을 때만, 뒤쪽부터 한 번 더 짧게 읽는다.
  - 마지막 자식 먼저, 크기 0인 묶음은 크기 있는 형제 뒤에, 화면 밖은 들어가지 않는다(프로브와 같은 순서). 읽은 노드는 남긴다.
  - 앞에서 읽은 부분과 합친다(경로마다 한 번, 걸음 순서로). `PopupRootFinder`가 모든 규칙으로 판단한다.
  - 찾은 팝업은 그 하위 트리를 다시 통째로 읽는다. 다 못 읽으면 쓰지 않는다(`SUBTREE_CUT`, F25 안전: 결제·동의 버튼이 빠진 팝업으로 판단하지 않음).
  - 팝업이 앞쪽에 있어 읽은 부분에서 찾은 경우는 지금까지와 같다(보조 읽기 안 함).
- **상한(숫자):**
  - 보조 읽기 노드 400개, 시간 300ms(`TailRead.MAX_NODES`, `MAX_MILLIS`). 팝업 하위 트리 다시 읽기 200ms(`SUBTREE_MAX_MILLIS`).
  - 한 번 읽기의 최악: 0.6초 + 0.3초 + 0.2초 = **1.1초**. 그런 페이지에서 10초에 한 번 이하(전체 읽기가 그 주기라서). F25 이전은 매번 6.1초였다.
  - 끝에서 400개 안에 팝업이 없으면 못 찾는다(`READ_TAIL_ABORTED`). 페이지 중간 깊숙한 팝업은 여전히 놓칠 수 있다.
- **로그(글자 없음):** `READ_TAIL_STARTED` → `READ_TAIL_FOUND`/`NONE`/`ABORTED`/`SUBTREE_CUT`, `READ_TAIL_MS`, Debug 한 줄 `TAIL_READ result= nodes= visited= ms=`.
- **바뀐 파일:**
  - 새 파일 `simpleclose/TailRead.kt`(`TailRead`, `TailTree`, `TailLook`, `TailWalk`, `TailOutcome`, `TailReadInfo`)
  - `SimpleTreeCollector`(보조 읽기 호출, `liveTail`, `TreeRead.tail`)
  - `SimpleCloseEngineFeature.TAIL_READ_AFTER_CAP`
  - `SimpleCloseAccessibilityService`(로그)
  - `SimpleTrace`(코드 5개)
  - 새 시험 `F51Test`

- 잔여 노드 쪽 이벤트 무시: TalkBack이 무엇을 읽을지는 앱이 막을 수 없다. 대신 포커스를 잔여 노드 밖으로 옮기는 것(F9)이 할 수 있는 전부. 웹 페이지가 닫힌 팝업을 DOM에 `aria-hidden` 없이 남기는 한, 사용자가 스와이프로 다시 만날 수 있다(한계로 기록).
- 선택 창 PROMPT도 TalkBack 큐로? 가능은 하다: 선택 창 자체(이미 접근성 오버레이)에 paneTitle이 있고 TalkBack이 창을 읽으므로, "안내 맡기기" ON이면 우리 TTS PROMPT를 끄고 paneTitle/첫 버튼 포커스 낭독에 맡기는 방식. 단, 지금 PROMPT는 TalkBack이 팝업을 읽는 도중 끊고 들어가야 시연이 빠르므로 polite로 바꾸면 늦어질 수 있다. F11 배너가 LG에서 잘 읽히면 같은 방식으로 옮길지 정하자(의견, 미구현).
- 검증(F1~F11 한 빌드): 5개 Gradle 작업 통과, 단위 시험 debug 1028개·release 1013개 실패 0, lintDebug 오류 0(경고 12), 병합 매니페스트 INTERNET 0·새 권한 없음.
- 위험: F10 포커스 이벤트로 TalkBack 사용자가 페이지를 스와이프할 때마다 0.3초에 최대 1회 트리를 읽는다(메인 스레드 밖). F11 배너 창이 TalkBack에 안 읽히면 결과 문장이 안 들림 → 로그 `RESULT_VIA_TALKBACK`인데 안 들리면 그 한계(다음 수정: TTS로 되돌리고 TalkBack이 조용해질 때까지 대기). F7로 "전부 자동 닫기"에서는 B가 판단하지 못한 팝업도 닫힌다(닫기 버튼 1개·금지어 0개일 때). F6 완화로 이미지로 그린 공지·안내도 광고로 닫힐 수 있다(엔진 규칙, B 판단 아님). F4 누르기 전 포커스 이동이 TalkBack 낭독을 한 번 더 부를 수 있다 — 체감 나쁘면 `FOCUS_PAGE_BEFORE_CLICK = false`.
