# 처음 설정·설정 화면 출시 수준 — C 메모 (A 연결 지점 포함)

- 날짜: 2026-10-29, 브랜치 `c/onboarding-release`, 담당 C(재호)
- 기준 디자인: 시연 앱 `experiments/demo-musical` "장면 2 · 처음 설정" (`demo/midterm-musical` 4155be0). 문구는 C 실제 문자열 우선.
- **상태: JVM 시험·빌드만 통과. 실기기·TalkBack 미검증. A 화면 교체·이름·아이콘·`<queries>`는 아직 연결 안 됨(아래 4장).**
- 브랜치는 PR #61(`c/talkback-takeover`, 커밋 32a4c93) **위에** 쌓았다. "선택 창이 뜨면 안내를 맡기기" 설정(`UiVoiceSettings.talkBackTakeover`, 키 `talkback_takeover_v1`, `TalkBackTakeoverFeature`)을 같은 코드로 쓰기 위해서다.
  #61이 먼저 병합되면 이 PR은 C 변경만 남는다. #61이 바뀌면 이 브랜치를 그 위로 다시 올린다(rebase).

## 1. 처음 설정 5단계 (`UiVoiceOnboardingScreen`)

| 단계 | 화면 | [다음] 조건 |
|---|---|---|
| 1/5 환영 | 앱 아이콘, "팝업처리도우미", "팝업, 이제 듣고 고르세요.", 하는 일 한 단락 → [시작하기] | 없음 |
| 2/5 접근성 서비스 | 무엇을 읽는지·밖으로 안 보냄·**결제·구매·동의·구독·삭제는 절대 안 누름**·은행 앱 제외 → 상태 상자("꺼짐" / "켜짐 ✓") → [서비스 켜기]가 시스템 접근성 설정을 염 | 켜지면 [다음], 꺼져 있으면 [나중에 켜기] |
| 3/5 팝업 처리 방식 | 프리셋 3개(처음엔 **아무것도 안 고름**) → 중요 결정 고정 문장 → "자동 닫기" 스위치(켤 때 확인 대화상자, 넓은 범위로 바꿀 때 다시 확인) → "TalkBack 안내" 질문: "선택 창이 뜨면 안내를 맡기기" 스위치 | 프리셋을 골라야 함 |
| 4/5 도와줄 앱 | "모든 앱에서 돕기(추천)" / "직접 고르기" — 기존 `HelpAppsEditor` 재사용: **실제 설치 앱 이름·아이콘**(크롬 등), 검색·말로 찾기, 은행·결제 앱은 항상 뺌. 패키지 이름 입력 없음 | 방식을 골라야 하고, "직접 고르기"는 1개 이상. [나중에 고르기]로 넘어갈 수 있음 |
| 5/5 완료 | 큰 체크, "준비됐어요", 요약(접근성 서비스·처리 방식·자동 닫기·안내 맡기기·도와줄 앱) → [사용 시작] | 앱 목록을 다시 불러오는 중이면 잠깐 기다림 |

- **[사용 시작]을 누를 때 한 번에 저장**한다(`UiVoicePreferences.completeOnboarding(preset, 자동 닫기, 안내 맡기기)` + `HelpAppsSync.save`). 그 전에는 아무것도 저장하지 않는다. 화면 회전 시 고른 값 유지.
- 시스템 뒤로 가기 = [이전 단계]. 1단계에서는 평소처럼 앱을 나간다.
- 각 단계는 `paneTitle`이 단계 제목이라 TalkBack이 단계가 바뀔 때 제목을 알린다. **포커스는 옮기지 않는다**(사용자가 직접 탐색).
- 순수 규칙은 `OnboardingFlow`(JVM 시험), 디자인 부품은 `OnboardingDesign.kt`(설정 화면도 같이 씀).

## 2. 접근성 서비스 켜짐 확인 (읽기 전용)

`AndroidCoreSetupStatus.serviceEnabled()` — C는 A 상태를 바꾸지 않고 읽기만 한다. 둘 중 하나라도 있으면 켜짐:

1. `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)` (공개 API, A의 MainActivity와 같은 방법) 에서 `kr.closeforyou` / `kr.closeforyou.accessibility.CloseForYouAccessibilityService`
2. `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` (공개·`@Readable`, AOSP 36 소스 `Settings.java` 확인) — "pkg/cls:pkg/cls" 목록, 짧은 이름(".cls")도 처리

- 화면이 다시 보일 때마다(`LifecycleResumeEffect`) 다시 읽는다 → 설정에서 켜고 돌아오면 "켜짐 ✓". 상태 상자는 polite live region이라 TalkBack이 한 번 읽는다.
- 시스템 설정 열기: `Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)`. 못 열면 "휴대폰 설정 앱에서 접근성을 찾아…" 안내.

### 2-1. [서비스 켜기] 길 안내 (10/30, 총괄 요청 ①②, `ServiceTurnOnGuide.kt`)

TalkBack 사용자가 접근성 목록에서 우리 앱을 찾기 힘들어서, 처음 설정 2단계와 설정 화면의 [서비스 켜기]가 같은 순서로 동작한다.

1. **바로 넘어가지 않고 안내 대화상자**: "설정에서 이렇게 켜 주세요" — 위치 기준 안내(10/30 LG 피드백):
   ① TalkBack 켜짐 "오른쪽으로 계속 쓸어 서비스 아래의 *앱 이름* 항목까지 가서 두 번 탭" / 꺼짐 "화면을 아래로 내려 서비스 아래의 *앱 이름* 항목을 누르세요"
   ② 사용을 켠 뒤 확인 창에서 허용 ③ 끝나면 뒤로 가기 + "휴대폰에 따라 설치된 앱·설치된 서비스 아래일 수도". [설정 열기] / [취소]. 화면 문구와 TTS가 같은 문장.
   *앱 이름*은 A의 `R.string.app_name`을 그대로 읽는다(시스템 설정의 서비스 이름과 같은 문자열). 지금은 "닫아줄게", A가 4-2를 바꾸면 자동으로 "팝업처리도우미".
2. **음성으로도 읽기** (`ServiceGuidePolicy`): TalkBack 꺼짐 → 우리 TTS가 바로 읽음. TalkBack 켜짐 → TalkBack이 대화상자 제목을 스스로 읽고, "안내 맡기기"가 켜져 있으면 1초 뒤 우리 TTS가 단계를 읽음(꺼져 있으면 TalkBack만). [설정 열기]·[취소]·닫기 때 TTS 멈춤.
   **다 읽으면 [설정 열기]로 포커스 이동** (TalkBack·안내 맡기기 켜짐, TTS `onDone`일 때만, 시연 모드 6-0). 대화상자는 우리 앱 창이라 서비스 없이 Compose의 `AccessibilityNodeProvider`에 `ACTION_ACCESSIBILITY_FOCUS`(노드는 testTag `ui_guide_open`).
   읽는 중 사람이 손가락으로 만지거나(TYPE_VIEW_HOVER_ENTER) 쓸어서 제목이 아닌 곳으로 포커스가 가면(TYPE_VIEW_ACCESSIBILITY_FOCUSED) TTS를 멈추고 이동하지 않음. 이벤트 글자는 제목과 비교만 하고 로그에 남기지 않음.
3. **우리 서비스 항목으로 바로**: [설정 열기]는 접근성 설정을 열면서 `:settings:fragment_args_key`(+ `:settings:show_fragment_args` 안 같은 키)에 `kr.closeforyou/kr.closeforyou.accessibility.CloseForYouAccessibilityService`를 넣는다. AOSP 설정 앱은 이 키의 항목으로 스크롤·강조한다. **공개 API가 아니라** 제조사 설정 앱이 무시하면 지금처럼 목록만 열린다(안전한 대체). **LG 벨벳(Android 12)은 무시함(10/30 확인)** — 맨 위 [돌아가기]에 포커스, 스크롤·강조 없음. 다른 기기용으로 코드는 둔다.
   `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS`(서비스 상세로 바로)는 SDK 36 소스에서 `@SystemApi @hide`라 일반 앱은 쓰지 않는다.
4. **돌아오면** (켜졌을 때): TalkBack 꺼짐 → 우리 TTS "접근성 서비스가 켜졌어요. 다음을 눌러 계속해 주세요."
   TalkBack 켜짐 + 안내 맡기기 켜짐 → 1초 뒤(TalkBack이 화면 제목 "5단계 중 2단계"를 읽을 시간) 같은 문장을 TTS로, 끝까지 읽으면 포커스를 처음 설정의 **[다음]**(testTag `ui_setup_service_next`)으로. 설정 화면에는 [다음]이 없어 말만 함.
   **10/30 재호 결정으로 바꿈 (최신)**: 포커스 이동은 "켜졌어요" 낭독과 묶지 않는다. 2/5가 보일 때마다(처음 들어올 때·설정에서 돌아올 때·3/5에서 뒤로) 서비스 켜짐 + TalkBack 켜짐이면 1.5초 뒤 [다음]으로 포커스(입력 포커스 `ACTION_FOCUS` + `ACTION_ACCESSIBILITY_FOCUS`), 0.5초마다 확인해 잃었으면 다시 옮기기를 6초까지, 2번 연속 잡혀 있으면 끝. "켜졌어요" TTS는 첫 이동 1초 뒤(최대 3초 대기)에 말해 TalkBack의 "다음, 버튼"과 겹치지 않게. 만지거나 다른 항목으로 쓸면 둘 다 멈춤. 안내 맡기기 설정과 무관(재호 결정).
   이전 시도(아래)는 기록용:
   LG 10/30: TTS는 들렸지만 포커스가 "5단계 중 2단계"에 남음(설정에서 돌아온 뒤 TalkBack이 첫 항목을 다시 잡음). 그래서 이동 뒤 [다음]이 실제로 접근성 포커스를 가졌는지(`isAccessibilityFocused`) 400ms 간격으로 5번 확인하고, 잃었으면 다시 옮긴다. 이 동안 TalkBack이 단계 줄·[다음]에 스스로 포커스를 두는 것은 사람 조작으로 보지 않고, 손가락 터치나 다른 항목으로 쓸기는 사람 조작으로 보아 멈춘다.
   live region만으로는 LG에서 화면 제목 알림에 묻혀 안 들렸다(10/30). 안내 맡기기 꺼짐이면 TalkBack만(상태 상자 "켜짐").
- 시연 전용 한 번에 켜기(WRITE_SECURE_SETTINGS)는 하지 않음.
- 수집 동의: `CoreSetupStatus.collectionConsentGranted()` = `CorePreferences.currentSettings().consentGranted` 읽기만. C는 동의를 주지 않는다.
- 인터페이스 `CoreSetupStatus`의 기본값 `NONE`은 안전한 쪽(꺼짐·동의 없음). A가 자기 판정(예: 서비스 연결 상태)을 쓰고 싶으면 `UiVoiceOnboardingGate(status = …)`, `UiVoiceSettingsRoute(status = …)`로 넘긴다.
- 패키지·클래스 이름은 로그에 남기지 않는다.

### 2-2. 처음 설정이 열릴 때 미리 골라 둔 값 (10/30 정리)

- **진짜 첫 실행**(저장값 없음): 프리셋·자동 닫기·도와줄 앱 모두 **미선택**. 안내 맡기기만 기본값(시연 `DEFAULT_ON`). 시험 `nothingStoredOpensSetupWithNothingChosen`.
- **[처음 설정 다시 하기]**: `onboardingDone=false` → 첫 실행과 같이 미선택. 저장값은 [사용 시작] 때 바뀔 때까지 그대로이고, 그동안 자동 닫기 범위는 비어 있음.
- **debug 미리보기 [처음 설정 열기]**: 예전에는 저장값을 보여 줬다(LG에서 "전부 자동 닫기"가 골라져 보인 이유). 이제 `OnboardingGatePolicy.firstRunDraft`로 첫 실행과 같이 미선택.

### 2-3. 다른 단계의 "넘어갈 준비가 된 순간" 포커스 (판단)

- 1/5 환영: 화면에 버튼이 [시작하기] 하나라 TalkBack이 스스로 찾음 → 이동 안 함.
- 3/5 프리셋 고른 뒤, 4/5 앱 고른 뒤: 사람이 직접 고르는 중(스위치·카드를 더 바꿀 수 있음) → 이동 안 함. 포커스는 사용자 것(C 규칙 3-2).
- 5/5 완료: 화면이 바뀌어 [사용 시작]이 새로 생기므로 TalkBack 포커스가 요약 맨 위로 감. [사용 시작]으로 옮길지는 **재호 결정 필요**(제안: 요약을 다 들은 뒤 사람이 내려가게 두기 — 요약 확인이 이 단계의 목적).
- 2/5만 예외인 이유: 사람이 시스템 설정을 다녀와 할 일이 [다음] 하나뿐이고, 돌아올 때 TalkBack이 포커스를 맨 위로 되돌려 길을 잃기 쉬움.

## 3. 설정 화면 (`UiVoiceSettingsScreen`, 제목 "팝업처리도우미 설정")

위에서 아래로: 접근성 서비스(꺼져 있으면 [서비스 켜기]) → 팝업 처리 방식 + 자동 닫기 → 도와줄 앱 → 지금 상태 + [기본 설정 열기](A 화면) → 안내(길이·음성으로도 알리기·**안내 맡기기**·**읽는 속도**) → 음성 명령 → 기억한 팝업(하나씩 해제·전체 삭제) → [처음 설정 다시 하기].

- 큰 글씨(제목 28sp·소제목 22sp·본문 18sp, sp라 시스템 글꼴 크기 따름), 버튼·행 최소 56dp(주 버튼 60dp).
- 다크 모드: `UiVoiceTheme`(시스템 설정 따름) + 브랜드 색 `UiBrandColors`(다크에서 밝은 남색). 대비 4.5:1 이상 JVM 시험.
- TalkBack: 소제목은 heading, 라디오 카드·행은 stateDescription "선택됨"/"선택 안 됨", 스위치는 한 노드(이름·설명·켜짐/꺼짐), 꺼진 버튼은 이유를 상태로 읽음. 포커스 강제 이동 없음.
- **도와줄 앱 영역 (10/30 LG 지적 반영)**: 원인은 ① "지금 38개 앱을 돕고 있어요"(A 허용 목록 크기: 모든 앱 35개 + A 개발 화면에서 넣은 앱) ② "앱 35개를 넣었어요"(마지막 저장 결과, 메모리) ③ 아래 "앱별 자동 닫기" 스위치(`autoClosePackages`, 기본 꺼짐)가 A 허용 앱마다 한 줄씩 → "모든 앱인데 다 꺼짐"으로 보임. 대기(`pendingHelpPackages`)와는 무관.
  고친 뒤: 숫자는 한 줄 하나(`HelpAppsStatusPlan`) — 모든 앱 "모든 앱을 돕고 있어요. 빼는 앱은 N개예요." + [제외할 앱 고르기] + [도와줄 앱 바꾸기] / 고른 앱 "고른 앱 N개를 돕고 있어요." / 동의 전 대기는 숫자 없이 "준비 중이에요…" 한 줄. 저장 결과 줄은 안 보임.
- **앱별 자동 닫기 제거 (10/30 재호 결정)**: "모든 앱 돕기를 허용했으면 프리셋대로 전부 처리, 앞으로 항상 닫기는 그 팝업 하나 단위". 설정에서 "앞으로 항상 닫기를 허용할 앱" 섹션을 숨기고(`PerAppAutoCloseGateFeature.enabled = false`, 한 곳), `UiVoiceSettings.autoCloseAllowed`는 도와줄 앱이면 항상 참. 저장 키 `auto_close_packages_v1`·코드는 남겨 배포 때 `true`로 되살림. "기억한 팝업" 목록 위에 "도와줄 앱이라면 어느 앱에서든 똑같은 팝업이 다시 뜰 때 묻지 않고 닫아요" 한 줄.
  A·B 영향 확인(읽기만): `autoCloseAllowed`/`autoClosePackages`를 읽는 A·B 코드는 없음. `DecisionContext`에는 앱별 필드가 없고 B는 `matchingRule`(D 규칙)로 반복 여부를 정함 → 실제 동작 변화 없음. 배포 때 앱별 스위치를 되살리면 B가 `DecisionContext`에 새 필드로 받아야 함(합의 필요).
  (아래는 c01eaca 당시 내용) 자동 닫기는 소제목 "앞으로 항상 닫기를 허용할 앱" + 설명으로 분리, **'앞으로 항상 닫기'로 기억한 팝업이 있는 앱(또는 이미 켠 앱)만** 스위치로 보임(`AutoCloseApps`). 없으면 안내 한 줄.
- **패키지 이름 노출 금지 (10/30)**: 앱 이름은 `PackageManager.getApplicationLabel` + 아이콘. 못 읽으면(미설치·Android 11+ 가시성·빈 이름) "알 수 없는 앱". 같은 이름이 둘이면 "메모 1/메모 2". 기억한 팝업·자동 닫기·도와줄 앱 목록 모두 적용. release는 4-4 `<queries>` 전까지 많은 앱이 "알 수 없는 앱"으로 보일 수 있음.
- [처음 설정 다시 하기]는 `onboardingDone=false`만 바꾼다 → [사용 시작]까지 자동 닫기 범위가 비어 있음(안전 쪽).

## 4. A가 연결할 지점 (통합은 C가 A와 함께)

### 4-1. 첫 화면 교체 — `app/src/main/java/kr/closeforyou/app/MainActivity.kt` 55~59줄

지금은 A의 개발용 화면(패키지 이름 입력)이 바로 뜬다. 다음처럼 감싼다:

```kotlin
setContent {
    UiVoiceAppRoot {
        MaterialTheme { CoreSetupScreen(this, preferences) }
    }
}
// import kr.closeforyou.ui.voice.settings.UiVoiceAppRoot
```

- 처음 실행: 5단계 처음 설정 → [사용 시작] 뒤 C 설정 화면이 홈 → [기본 설정 열기]로 A 화면(수집 동의·분석 시작·OCR), 뒤로 가기로 홈.
- 처음 설정만 원하면 `UiVoiceOnboardingGate { MaterialTheme { CoreSetupScreen(this, preferences) } }`.
- A 화면은 그대로 두고 감싸기만 하므로 A 코드 변경은 이 몇 줄뿐. A 화면 안 "닫아줄게" 문구는 A가 정리.

### 4-2. 앱 이름 — `app/src/main/res/values/strings.xml` 3줄

`<string name="app_name">닫아줄게</string>` → `팝업처리도우미`.
런처 이름과 **시스템 접근성 설정의 서비스 이름**(매니페스트 34줄 service `android:label`)이 함께 바뀐다.
2단계 안내 문구가 "설치된 앱에서 팝업처리도우미를 골라 켠 뒤"라서 **이걸 바꾸지 않으면 안내와 실제 이름이 다르다**(통합 필수).

### 4-3. 런처 아이콘 — `app/src/main/AndroidManifest.xml` 18줄

`android:icon="@drawable/ic_launcher"` → `android:icon="@mipmap/ui_ic_launcher"` (원하면 `android:roundIcon="@mipmap/ui_ic_launcher"`도).
C가 준비한 리소스: `res/mipmap-anydpi/ui_ic_launcher.xml`(적응형), `res/drawable/ui_launcher_background.xml`(남색→청록), `res/drawable/ui_launcher_foreground.xml`(팝업 카드 + 체크 배지). 시연 앱 아이콘과 같다. minSdk 30이라 `-v26` 없이 둠.

### 4-4. 설치 앱 목록 `<queries>` — `app/src/main/AndroidManifest.xml`

Android 11+ 패키지 공개 범위 때문에, main 매니페스트의 `<queries>`에 아래 intent를 **추가**해야 release에서 "도와줄 앱" 목록이 보인다(debug 매니페스트에는 이미 있음, `docs/c-notes/app-picker.md`). `QUERY_ALL_PACKAGES`는 쓰지 않는다.

```xml
<intent>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.LAUNCHER" />
</intent>
```

없으면 release 4단계에서 "앱 목록을 불러오지 못했어요"가 뜨고 [나중에 고르기]로만 넘어갈 수 있다.

### 4-5. 수집 동의·분석 시작

C는 A의 수집 동의(`updateConsent`)와 분석 시작(`setPaused`)을 부르지 않는다. 처음 설정에서 고른 앱은 동의 전에는 "대기"로 저장되고 동의하면 자동으로 들어간다(기존 `PendingHelpAppsEffect`). 완료 화면에 "기본 설정에서 화면 정보 수집에 동의하면 적용돼요"를 보여 준다.
A가 원하면 다음 단계로 2단계(접근성 서비스)에 A 동의 문구·체크를 넣는 방안을 같이 정한다(A 결정).

## 5. debug에서 보기

> **시연 폰에는 release 빌드를 설치한다.** debug에는 C 시험용 접근성 서비스("… C 선택 창 시험")가 목록에 같이 보여 2단계 안내와 헷갈린다.

```
adb shell am start -n kr.closeforyou/.ui.voice.debug.ChoicePreviewActivity --ez ui_debug_open_onboarding true
```

5단계 처음 설정이 바로 열린다(이미 끝냈어도). [사용 시작]은 C 실제 설정과 A 허용 앱 목록(동의 전엔 대기)에 저장한다. 설정은 미리보기 첫 화면의 설정 버튼(가짜 기억 목록).

## 6. 실기기 미검증 항목 (LG 벨벳 Android 12, 갤럭시)

- ~~[서비스 켜기] → 시스템 접근성 설정 열림 → 켜고 돌아오면 "켜짐 ✓"~~ (10/30 LG 확인됨, TalkBack 없이)
- 2-1: 갤럭시에서 강조 키가 먹는지(LG는 안 먹음), 안내 TTS ✅(LG TalkBack 켜짐, 끼어들기 없음), **다 읽은 뒤 [설정 열기]로 포커스가 옮겨지는지**, 읽는 중 만지면 멈추고 안 옮기는지, 돌아온 뒤 "켜졌어요"
- 켜고 돌아오면 TalkBack이 "켜짐"을 한 번 읽는지
- 단계가 바뀔 때 TalkBack이 `paneTitle`(단계 제목)을 알리는지, 포커스가 이상한 곳에 남지 않는지
- 라디오 카드·스위치 카드 낭독 순서("팝업 처리 방식…, 선택됨, 라디오 버튼")
- 큰 글꼴(시스템 글꼴 최대)·다크 모드에서 잘림·겹침 없는지
- 실제 설치 앱 이름·아이콘(크롬 등)이 4단계에 보이는지 (debug는 `<queries>` 있음, release는 4-4 뒤)
- 아이콘이 런처에서 잘리지 않는지(4-3 적용 뒤)
