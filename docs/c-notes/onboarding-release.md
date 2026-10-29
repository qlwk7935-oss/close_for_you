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

1. **바로 넘어가지 않고 안내 대화상자**: "설정에서 이렇게 켜 주세요" — ① 목록에서 *앱 이름* 항목을 찾아 두 번 탭 ② 사용 스위치를 켜고 확인 창에서 허용 ③ 다 되면 뒤로 가기 + "목록 위치는 휴대폰마다 달라요". [설정 열기] / [취소].
   *앱 이름*은 A의 `R.string.app_name`을 그대로 읽는다(시스템 설정의 서비스 이름과 같은 문자열). 지금은 "닫아줄게", A가 4-2를 바꾸면 자동으로 "팝업처리도우미".
2. **음성으로도 읽기** (`ServiceGuidePolicy`): TalkBack 꺼짐 → 우리 TTS가 바로 읽음. TalkBack 켜짐 → TalkBack이 대화상자 제목을 스스로 읽고, "안내 맡기기"가 켜져 있으면 1초 뒤 우리 TTS가 단계를 읽음(꺼져 있으면 TalkBack만). [설정 열기]·[취소]·닫기 때 TTS 멈춤.
3. **우리 서비스 항목으로 바로**: [설정 열기]는 접근성 설정을 열면서 `:settings:fragment_args_key`(+ `:settings:show_fragment_args` 안 같은 키)에 `kr.closeforyou/kr.closeforyou.accessibility.CloseForYouAccessibilityService`를 넣는다. AOSP 설정 앱은 이 키의 항목으로 스크롤·강조한다. **공개 API가 아니라** 제조사 설정 앱이 무시하면 지금처럼 목록만 열린다(안전한 대체).
   `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS`(서비스 상세로 바로)는 SDK 36 소스에서 `@SystemApi @hide`라 일반 앱은 쓰지 않는다.
4. **돌아오면**: 켜졌으면 TalkBack 꺼짐 → TTS "접근성 서비스가 켜졌어요. 다음을 눌러 계속해 주세요.", TalkBack 켜짐 → 상태 상자 live region을 TalkBack이 읽음(두 목소리 겹침 방지).
- 시연 전용 한 번에 켜기(WRITE_SECURE_SETTINGS)는 하지 않음.
- 수집 동의: `CoreSetupStatus.collectionConsentGranted()` = `CorePreferences.currentSettings().consentGranted` 읽기만. C는 동의를 주지 않는다.
- 인터페이스 `CoreSetupStatus`의 기본값 `NONE`은 안전한 쪽(꺼짐·동의 없음). A가 자기 판정(예: 서비스 연결 상태)을 쓰고 싶으면 `UiVoiceOnboardingGate(status = …)`, `UiVoiceSettingsRoute(status = …)`로 넘긴다.
- 패키지·클래스 이름은 로그에 남기지 않는다.

## 3. 설정 화면 (`UiVoiceSettingsScreen`, 제목 "팝업처리도우미 설정")

위에서 아래로: 접근성 서비스(꺼져 있으면 [서비스 켜기]) → 팝업 처리 방식 + 자동 닫기 → 도와줄 앱 → 지금 상태 + [기본 설정 열기](A 화면) → 앱별 자동 닫기 → 안내(길이·음성으로도 알리기·**안내 맡기기**·**읽는 속도**) → 음성 명령 → 기억한 팝업(하나씩 해제·전체 삭제) → [처음 설정 다시 하기].

- 큰 글씨(제목 28sp·소제목 22sp·본문 18sp, sp라 시스템 글꼴 크기 따름), 버튼·행 최소 56dp(주 버튼 60dp).
- 다크 모드: `UiVoiceTheme`(시스템 설정 따름) + 브랜드 색 `UiBrandColors`(다크에서 밝은 남색). 대비 4.5:1 이상 JVM 시험.
- TalkBack: 소제목은 heading, 라디오 카드·행은 stateDescription "선택됨"/"선택 안 됨", 스위치는 한 노드(이름·설명·켜짐/꺼짐), 꺼진 버튼은 이유를 상태로 읽음. 포커스 강제 이동 없음.
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

```
adb shell am start -n kr.closeforyou/.ui.voice.debug.ChoicePreviewActivity --ez ui_debug_open_onboarding true
```

5단계 처음 설정이 바로 열린다(이미 끝냈어도). [사용 시작]은 C 실제 설정과 A 허용 앱 목록(동의 전엔 대기)에 저장한다. 설정은 미리보기 첫 화면의 설정 버튼(가짜 기억 목록).

## 6. 실기기 미검증 항목 (LG 벨벳 Android 12, 갤럭시)

- ~~[서비스 켜기] → 시스템 접근성 설정 열림 → 켜고 돌아오면 "켜짐 ✓"~~ (10/30 LG 확인됨, TalkBack 없이)
- 2-1: LG·갤럭시에서 강조 키가 먹어 우리 항목으로 스크롤·강조되는지, 안내 TTS가 들리는지, TalkBack 켜짐에서 대화상자 낭독과 우리 TTS가 겹치지 않는지, 돌아온 뒤 "켜졌어요"
- 켜고 돌아오면 TalkBack이 "켜짐"을 한 번 읽는지
- 단계가 바뀔 때 TalkBack이 `paneTitle`(단계 제목)을 알리는지, 포커스가 이상한 곳에 남지 않는지
- 라디오 카드·스위치 카드 낭독 순서("팝업 처리 방식…, 선택됨, 라디오 버튼")
- 큰 글꼴(시스템 글꼴 최대)·다크 모드에서 잘림·겹침 없는지
- 실제 설치 앱 이름·아이콘(크롬 등)이 4단계에 보이는지 (debug는 `<queries>` 있음, release는 4-4 뒤)
- 아이콘이 런처에서 잘리지 않는지(4-3 적용 뒤)
