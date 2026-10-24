# 도와줄 앱 고르기 (C, 2026-10-24)

> Mock·debug 기준. **실제 A 연동(접근성 서비스가 고른 앱에서 팝업을 잡는지)은 미검증.** LG TalkBack 확인 전.

## 왜

A 첫 화면의 "분석할 앱 직접 허용"은 패키지 이름(`kr.example...`)을 손으로 입력한다. 시각장애인 사용자가 쓰기 어렵고, 기본 허용 앱이 없어서 이걸 못 하면 앱이 아무것도 하지 않는다. 그래서 C 첫 실행·설정에 "도와줄 앱" 단계를 넣었다. C는 A 목록을 직접 쓰지 않고 A의 공개 함수 `CorePreferences.addAllowedPackage` / `removeAllowedPackage`만 부른다(A 코드 변경 없음).

## 흐름

첫 실행: **[1단계] 팝업 처리 방식(프리셋)** → [다음] → **[2단계] 도와줄 앱** → [시작하기] / [이전 단계] / [건너뛰기]

- 라디오 2개: **모든 앱에서 돕기 (추천, 기본)** / **직접 고르기**
- 모든 앱: "앱 N개를 도와요. 은행·결제 앱처럼 보이는 앱 M개는 뺐어요." + [제외된 앱도 보기](체크해서 다시 넣기)
- 직접 고르기: "앱 N개를 골랐어요." + [앱 고르기] → 목록 화면
- 목록 화면(LazyColumn): 제목 → 고른 개수 → [완료] → 검색 칸 → (검색 중) 결과 개수(Polite) → "추천" 묶음 → "모든 앱 (가나다순)"
  - 한 줄 = TalkBack 한 덩어리: 아이콘(장식) + 앱 이름 (+ "결제 앱일 수 있어요") + 체크 상자(`toggleable`, `Role.Checkbox`)
  - 결제류 앱을 체크하면 아래에 "이 앱은 결제 화면이 많아요. 결제·동의는 항상 직접 확인해요."(Polite)
- 설정 화면: 프리셋 아래 "도와줄 앱" 구역 — 지금 돕는 앱 수, 방식, 동의 대기 수, 마지막 적용 결과(Polite), [도와줄 앱 바꾸기] → 같은 편집 화면 + [저장]/[취소]
- 저장 전에는 아무것도 바뀌지 않는다. 회전해도 고른 상태 유지(`rememberSaveable`).

## A 동의 순서

`addAllowedPackage`는 수집 동의 전에는 false를 준다.

1. 저장할 때: 뺄 앱은 바로 뺀다(`removeAllowedPackage`는 동의 없이 됨). 넣을 앱은 동의가 없으면 C 설정(`pending_help_packages_v1`)에 보류.
2. 동의 뒤: `UiVoiceOnboardingGate`의 A 화면 쪽과 C 설정 화면이 `PendingHelpAppsEffect`로 동의를 지켜보다가 보류분을 한 번 넣는다.
3. 동의 뒤에도 A가 거절한 이름은 실패로 세고 버린다(같은 이름은 다시 해도 실패).
4. 결과 문구: "앱 N개를 도와줄 앱에 넣었어요. 앱 N개를 뺐어요. 앱 N개는 넣지 못했어요. 화면 정보 수집에 동의하면 앱 N개를 넣어요." (0인 줄은 빠짐)
5. 직접 고르기에서 체크를 끈 앱만 뺀다. 목록에 없는 패키지(A debug 입력칸으로 넣은 것)는 건드리지 않는다.

앱 이름·패키지 이름·화면 글자는 로그에 남기지 않는다(결과는 개수만, 메모리에만).

## 제외 규칙 (`SensitiveAppRules`, `HelpApps.kt` 한 곳)

- 우리 앱 자신: 항상 목록에서 뺀다(제외 목록에도 없음).
- 은행·증권·결제·카드·간편결제·비밀번호 관리자처럼 보이는 앱: "모든 앱"에서 기본 제외.
  - `ApplicationInfo.category`에는 금융·결제 분류가 **없다**(게임·음악·영상·사진·소셜·뉴스·지도·생산성·접근성뿐). 그래서 분류는 쓰지 못하고 키워드 고정 목록만 쓴다.
  - 패키지 이름 키워드(소문자 부분 일치): bank, pay, card, wallet, finance, invest, stock, securities, trade, crypto, coin, upbit, bithumb, insur, toss, kbstar, kbank, shinhan, woori, hana, nonghyup, ibk, kiwoom, miraeasset, truefriend, samsungpop, kftc, yessign, crosscert, signkorea, smartotp, tauth, password, keepass, 1password, lastpass, bitwarden, dashlane, keeper, authenticator 등
  - 앱 이름 키워드(띄어쓰기·대소문자 무시): 은행, 뱅크, 뱅킹, 증권, 투자, 주식, 카드, 결제, 송금, 지갑, 월렛, 금융, 저축, 캐피탈, 보험, 코인, 거래소, 토스, 인증서, 비밀번호, 패스워드, bank, pay, wallet, card, otp, password, 그리고 "페이"(단 "페이스북"·"페이지"는 제외)
  - 오탐(멀쩡한 앱이 빠짐)은 체크 한 번이면 되돌릴 수 있고, 미탐(은행 앱이 분석됨)은 사용자가 모르는 사이 일어나므로 **넓게 잡는다**. F0 민감 화면 차단(A)은 그대로 따로 작동한다.
- 추천 목록(`RecommendedApps`): 유튜브, 카카오톡, 쿠팡, 네이버, 인스타그램, 틱톡(2개 지역판), 쿠팡이츠, 배달의민족, 요기요, 당근, 11번가, G마켓, 알리익스프레스, 테무, 네이버 웹툰, 카카오페이지 — 설치된 것만, 이 순서로.

## 앱 목록 조회

`PackageManager.queryIntentActivities(MAIN/LAUNCHER)` (`LauncherAppSource`), IO 스레드. Android 11+ 패키지 가시성 때문에 `<queries>`가 필요하다.

- **debug 매니페스트(`app/src/debug/AndroidManifest.xml`)에만** `<queries><intent> MAIN + LAUNCHER </intent></queries>`를 넣었다(사용자 승인). main(A 파일)은 건드리지 않았다.
- `QUERY_ALL_PACKAGES`는 쓰지 않는다. 새 권한·라이브러리 없음.
- release/main 빌드는 지금 목록이 거의 비어서 "앱 목록을 불러오지 못했어요"가 나오고, [모든 앱]을 골라도 넣을 앱이 없다(방식만 저장).

## A(우진)에게 부탁할 것

1. **main 매니페스트에 `<queries>` 한 덩어리** (launcher 앱만 보임, QUERY_ALL_PACKAGES 아님):
   ```xml
   <queries>
       <intent>
           <action android:name="android.intent.action.MAIN" />
           <category android:name="android.intent.category.LAUNCHER" />
       </intent>
   </queries>
   ```
   → 들어가면 C debug 매니페스트의 같은 줄은 지운다.
2. **'모든 앱' 모드 지원**: 지금은 C가 저장 시점의 앱을 하나씩 `addAllowedPackage`로 넣는다. 그래서 **새로 설치한 앱은 저절로 들어가지 않는다**(화면에 안내함). A가 "모든 앱(제외 목록 빼고)" 모드를 받으면 새 앱도 자동 반영된다. 제외 판정은 C의 `SensitiveAppRules`를 그대로 쓰거나 공용 계약으로 옮기는 것을 제안.
3. **패키지 직접 입력 칸은 debug에만** 남기기(일반 사용자 화면에서는 C의 "도와줄 앱"으로).
4. `UiVoiceOnboardingGate { CoreSetupScreen(...) }`로 첫 화면을 감싸면 첫 실행 → 도와줄 앱 → A 동의 화면 순서가 되고, 동의하는 순간 보류된 앱이 들어간다.

## 열어 보기 (debug)

```
adb -d shell am start -n kr.closeforyou/.ui.voice.debug.ChoicePreviewActivity --ez ui_debug_open_onboarding true
adb -d shell am start -n kr.closeforyou/.ui.voice.debug.ChoicePreviewActivity --ez ui_debug_open_app_picker true
```

debug 미리보기의 저장은 **실제 A 허용 목록**(`CorePreferences`)에 쓴다. 동의 전이면 보류만 된다.

## 시험

`HelpAppsTest` 23개(JVM, 가짜 앱 목록): 우리 앱 제외, 같은 앱 중복 한 줄, 결제류 제외 / 페이스북·페이지 오탐 없음, 가나다순, 추천 순서·설치된 것만, 추천 목록에 결제류 없음, 한글 부분 검색(띄어쓰기 무시), 모든 앱/직접 고르기 대상, 목록 밖 패키지 보존, 동의 전 보류 → 동의 후 적용·실패 개수, 이미 있는 앱 다시 안 넣음, 빈 목록, 결과 문구 순서, 문자열 형식, 저장·복원.

## 미검증 / 남은 일

- LG TalkBack 실기기 확인 전: 체크 상자 낭독, 결제 안내 Polite가 새로 나타날 때 읽히는지, 검색 결과 개수가 글자마다 겹쳐 읽히는지.
- 실제 A 서비스가 고른 앱에서 팝업을 잡는지(연동) 미검증.
- 패키지 이름 일부(배달의민족 `com.sampleapp` 등)는 공개 스토어 id 기준, 사용자 확인 필요.
