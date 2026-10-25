# 도와줄 앱 고르기 (C, 2026-10-24)

> Mock·debug 기준. **실제 A 연동(접근성 서비스가 고른 앱에서 팝업을 잡는지)은 미검증.** LG TalkBack 확인 전.
> 10/24 우진 조건 반영: 기본 선택 없음, 선택 전 범위 설명, 결제류 항상 제외, 동의 분리 문구, A 범위 API 자리.
> **[모든 앱]은 지금 깔린 앱을 개별 등록하는 임시 방식이다. A 분석 범위 모드·F0 연결 전까지 완료 아님.**

## 왜

A 첫 화면의 "분석할 앱 직접 허용"은 패키지 이름(`kr.example...`)을 손으로 입력한다. 시각장애인 사용자가 쓰기 어렵고, 기본 허용 앱이 없어서 이걸 못 하면 앱이 아무것도 하지 않는다. 그래서 C 첫 실행·설정에 "도와줄 앱" 단계를 넣었다. C는 A 목록을 직접 쓰지 않고 A의 공개 함수 `CorePreferences.addAllowedPackage` / `removeAllowedPackage`만 부른다(A 코드 변경 없음).

## 우진 조건 (10/24)

1. **기본 선택 없음.** "아무 동의 없이 모든 앱 분석이 기본으로 켜지게 하지 말자." → 두 라디오 모두 선택 안 된 상태로 시작한다. 하나를 골라야 [시작하기]/[저장]이 켜진다. 꺼져 있을 때는 버튼 위에 이유가 보이고, 버튼 상태로도 읽힌다("시작하기, 모든 앱에서 돕기나 직접 고르기 중 하나를 고르면 누를 수 있어요, 사용 중지됨" — 실기기 확인 필요). "(추천)" 표시는 남겼다. [건너뛰기]는 언제나 누를 수 있다.
2. **선택 전에 범위 설명** (라디오 위): 접근성 정보로 고른 앱의 팝업을 살펴본다 / 은행·증권·결제·비밀번호 관리 앱과 보호된 화면은 [모든 앱]이어도 항상 빼고, 원하면 더 뺄 수 있다 / 화면 내용은 휴대폰 밖으로 나가지 않는다.
3. **동의 분리 문구**: "도와줄 앱을 골라도 화면 사진(스크린샷)·글자 읽기(OCR)와 자동 닫기는 따로 정해요."
   - 프리셋 단계 소개 문구도 고쳤다. 전: "광고는 자동으로 닫아요" → 후: "…여기서 고른 범위에서만, 안전 확인을 통과한 팝업만 닫아요." 프리셋 아래의 고정 문구 "자동 닫기는 곧 켜질 예정이에요. 지금은 닫기 전에 물어봐요."(`PresetAutoCloseFeature.live = false`)와도 맞다. 앱별 F12 자동 닫기는 그대로 기본 꺼짐.
   - 프리셋 라디오는 기존대로 기본값(`HandlingPreset.DEFAULT`)이 선택된 채 시작한다. 이것은 분석 범위가 아니라 팝업 종류별 선호다. 우진이 이것도 기본 선택 없이 원하면 따로 바꾼다.
4. **결제류 항상 제외**: 처음 버전의 "제외된 앱 다시 넣기"는 없앴다. 은행·결제류는 어느 방식이든 대상에 들어가지 않는다. [항상 빼는 앱 보기]는 보기만 한다. 직접 고르기 목록에도 나오지 않는다. 이미 A 목록에 있던 결제류 앱은 저장할 때 뺀다.
5. **A 범위 API 자리**: `HelpAppsAndroid.kt`에 `AnalysisScopeTarget`(`setAllApps(excluded)`, `setChosenApps(packages)`) 인터페이스와 `HelpAppsSync.save`의 TODO만 두었다. 구현체는 없고 부르는 곳도 없다. 지금 동작은 `addAllowedPackage` 반복 그대로다. 사용자가 직접 뺀 앱은 C 설정 `help_apps_excluded_v1`에 저장해 둔다. A 제외 목록이 생기면 거기로 넘긴다.

## 동의 분리 (2026-10-25)

| 동의 | 무엇을 허락하나 | 담당·위치 | 기본 |
|---|---|---|---|
| 일반 화면 분석(접근성 정보 수집) 동의 | 고른 앱의 팝업을 접근성 정보로 살펴보기 | **A** `CorePreferences.consentGranted` | 꺼짐 |
| 스크린샷·글자 읽기(OCR) 동의 | 화면 사진을 찍어 글자를 읽기 | **A** (캡처·OCR 동의) | 꺼짐 |
| 자동 닫기 동의 | 고른 프리셋의 종류를 묻지 않고 닫기 | **C** "자동 닫기" 스위치 `UiVoiceSettings.autoCloseConsent` | 꺼짐 |

- 셋은 서로 켜 주지 않는다. "모든 앱에서 돕기"를 골라도, 프리셋을 골라도 자동 닫기는 꺼져 있다(우진 10/25).
- 프리셋은 정책 입력값이다. 자동 닫기 스위치가 꺼져 있으면 `autoCloseKinds`는 빈 집합이다.
- 앱별 F12 스위치("기억한 똑같은 팝업 반복 자동")도 이 셋과 별개다.

자세한 규칙(프리셋 변경 시 등): [preset-contract-proposal.md](preset-contract-proposal.md) 4-1장.

## 흐름

첫 실행: **[1단계] 팝업 처리 방식(프리셋)** → [다음] → **[2단계] 도와줄 앱** → [시작하기] / [이전 단계] / [건너뛰기]

- 화면 순서: "2단계 중 2단계" → 제목 → 소개 → 범위 설명 → 동의 분리 문구 → 라디오 2개(둘 다 선택 안 됨) → (고른 뒤) 요약과 버튼 → 동의 안내 → [시작하기] …
- 모든 앱: "앱 N개를 도와요. 은행·결제 앱처럼 보이는 앱 M개는 항상 빼요. (직접 뺀 앱은 K개예요.)"
  - [더 뺄 앱 고르기]: 결제류 아닌 앱 목록이 나오고 모두 체크돼 있다. 체크를 끄면 뺀다.
  - [항상 빼는 앱 보기]: 보기만 하는 목록.
- 직접 고르기: "앱 N개를 골랐어요." + [앱 고르기] → 목록 화면
- 목록 화면(LazyColumn): 제목 → (도움말) → 개수 → [완료] → 검색 칸 → (검색 중) 결과 개수(Polite) → "추천" 묶음(직접 고르기만) → "모든 앱 (가나다순)"
  - 한 줄이 TalkBack 한 덩어리로 읽힌다: 아이콘(장식) + 앱 이름 + 체크 상자(`toggleable`, `Role.Checkbox`).
- 설정 화면: 프리셋 아래 "도와줄 앱" 구역 — 지금 돕는 앱 수, 방식, 동의 대기 수, 마지막 적용 결과(Polite), [도와줄 앱 바꾸기] → 같은 편집 화면 + [저장]/[취소]
- 저장 전에는 아무것도 바뀌지 않는다. 회전해도 고른 상태가 유지된다(`rememberSaveable`).

## A 동의 순서

`addAllowedPackage`는 수집 동의 전에는 false를 준다.

1. 저장할 때: 뺄 앱은 바로 뺀다(`removeAllowedPackage`는 동의 없이 된다). 넣을 앱은 동의가 없으면 C 설정(`pending_help_packages_v1`)에 보류한다.
2. 동의 뒤: `UiVoiceOnboardingGate`의 A 화면 쪽과 C 설정 화면이 `PendingHelpAppsEffect`로 동의를 지켜보다가, 보류분을 한 번 넣는다.
3. 동의 뒤에도 A가 거절한 이름은 실패로 세고 버린다(같은 이름은 다시 해도 실패한다).
4. 결과 문구: "앱 N개를 도와줄 앱에 넣었어요. 앱 N개를 뺐어요. 앱 N개는 넣지 못했어요. 화면 정보 수집에 동의하면 앱 N개를 넣어요." (0인 줄은 빠진다)
5. 목록에 없는 패키지(A debug 입력칸으로 넣은 것)는 건드리지 않는다.

앱 이름·패키지 이름·화면 글자는 로그에 남기지 않는다. 결과는 개수만, 메모리에만 둔다.

## 제외 규칙 (`SensitiveAppRules`, `HelpApps.kt` 한 곳)

- 우리 앱 자신: 항상 목록에서 뺀다.
- 은행·증권·결제·카드·간편결제·비밀번호 관리자처럼 보이는 앱: **어느 방식이든 항상 뺀다.**
  - `ApplicationInfo.category`에는 금융·결제 분류가 **없다**(게임·음악·영상·사진·소셜·뉴스·지도·생산성·접근성뿐). 그래서 분류는 쓰지 못하고 키워드 고정 목록만 쓴다.
  - 패키지 이름 키워드(소문자 부분 일치): bank, pay, card, wallet, finance, invest, stock, securities, trade, crypto, coin, upbit, bithumb, insur, toss, kbstar, kbank, shinhan, woori, hana, nonghyup, ibk, kiwoom, miraeasset, truefriend, samsungpop, kftc, yessign, crosscert, signkorea, smartotp, tauth, password, keepass, 1password, lastpass, bitwarden, dashlane, keeper, authenticator 등
  - 앱 이름 키워드(띄어쓰기·대소문자 무시): 은행, 뱅크, 뱅킹, 증권, 투자, 주식, 카드, 결제, 송금, 지갑, 월렛, 금융, 저축, 캐피탈, 보험, 코인, 거래소, 토스, 인증서, 비밀번호, 패스워드, bank, pay, wallet, card, otp, password, "페이"("페이스북"·"페이지"는 제외)
  - 넓게 잡는다. 대신 이제 사용자가 다시 넣을 수 없으므로, 오탐(멀쩡한 앱이 빠짐)도 고정이 된다. "보험", "hana", "coin", "trade" 같은 키워드는 우진·사용자 확인이 필요하다. F0 민감 화면 차단(A)은 그와 별개로 작동한다.
- 추천(`RecommendedApps`):
  - **패키지 id로 찾기**(확실한 것만): 유튜브 `com.google.android.youtube`, 카카오톡 `com.kakao.talk`, 쿠팡 `com.coupang.mobile`, 네이버 `com.nhn.android.search`, 인스타그램 `com.instagram.android`, 틱톡 `com.zhiliaoapp.musically`·`com.ss.android.ugc.trill`
  - **앱 이름으로 찾기**(id가 확실하지 않은 것 — 확인 필요): 쿠팡이츠, 배달의민족, 요기요, 당근, 11번가, G마켓, 알리익스프레스/AliExpress, 테무/Temu, 네이버웹툰, 카카오페이지. 기기에 보이는 이름이 다르면 추천에 안 나올 뿐이고, "모든 앱" 목록에는 그대로 나온다.
  - `com.sampleapp`(배달의민족)는 id 목록에서 뺐다. 결제류로 판정된 앱은 추천에 나오지 않는다.

## 앱 목록 조회

`PackageManager.queryIntentActivities(MAIN/LAUNCHER)` (`LauncherAppSource`)를 IO 스레드에서 부른다. Android 11+ 패키지 가시성 때문에 `<queries>`가 필요하다.

- **debug 매니페스트(`app/src/debug/AndroidManifest.xml`)에만** `<queries><intent> MAIN + LAUNCHER </intent></queries>`를 넣었다(사용자 승인). main(A 파일)은 건드리지 않았다.
- `QUERY_ALL_PACKAGES`는 쓰지 않는다. 새 권한·라이브러리 없음.
- release/main 빌드는 지금 목록이 거의 비어서 "앱 목록을 불러오지 못했어요"가 나오고 넣을 앱이 없다.

## A(우진)에게 부탁할 것

1. **main 매니페스트에 `<queries>` 한 덩어리** (launcher 앱만 보인다, QUERY_ALL_PACKAGES 아님):
   ```xml
   <queries>
       <intent>
           <action android:name="android.intent.action.MAIN" />
           <category android:name="android.intent.category.LAUNCHER" />
       </intent>
   </queries>
   ```
   → 들어가면 C debug 매니페스트의 같은 줄은 지운다.
2. **분석 범위 모드 + 제외 목록** (우진 계획): C의 자리 `AnalysisScopeTarget`이 받을 모양은 `setAllApps(excluded)` / `setChosenApps(packages)`, 동의 전 false다. 들어가면 [모든 앱] 저장이 앱별 추가 대신 한 번에 되고, **새로 설치한 앱도 자동 반영**된다. 제외 목록 = C `SensitiveAppRules` 결과 + 사용자가 직접 뺀 앱(`help_apps_excluded_v1`). 판정 규칙을 공용 계약으로 옮길지 정해야 한다.
3. **패키지 직접 입력 칸은 debug에만** 남기기(일반 사용자는 C의 "도와줄 앱"으로).
4. `UiVoiceOnboardingGate { CoreSetupScreen(...) }`로 첫 화면을 감싸면 첫 실행 → 도와줄 앱 → A 동의 화면 순서가 되고, 동의하는 순간 보류된 앱이 들어간다.

## 열어 보기 (debug)

```
adb -d shell am start -n kr.closeforyou/.ui.voice.debug.ChoicePreviewActivity --ez ui_debug_open_onboarding true
adb -d shell am start -n kr.closeforyou/.ui.voice.debug.ChoicePreviewActivity --ez ui_debug_open_app_picker true
```

debug 미리보기의 저장은 **실제 A 허용 목록**(`CorePreferences`)에 쓴다. 동의 전이면 보류만 된다.

## 시험

`HelpAppsTest` 30개(JVM, 가짜 앱 목록):
- 목록 만들기: 우리 앱 제외, 같은 앱 중복을 한 줄로, 결제류 판정, 페이스북·페이지 오탐 없음, 가나다순
- 추천: id·이름 추천과 순서, id 불확실 앱은 이름으로, 추천에 결제류 없음
- 검색: 한글 부분 일치(띄어쓰기 무시)
- 저장 대상: **처음엔 선택 없음·저장 불가**, 모든 앱에서 직접 빼기, **결제류는 어느 방식이든 대상 아님**, 이미 허용된 결제류는 저장 시 뺌, 목록 밖 패키지 보존
- 동의 순서: 동의 전 보류 → 동의 후 적용·실패 개수, 이미 있는 앱 다시 안 넣음, 빈 목록
- 문구: 결과 문구 순서, 문자열 형식, **범위 설명·동의 분리 문구 내용, 소개 문구가 무조건 자동 닫기를 약속하지 않음**
- 설정 저장·복원

## 말로 찾기 (2026-10-24, LG 시험 사용자 의견 반영)

검색 칸 옆 **[말로 찾기]**("말로 찾기, 버튼", 56dp)를 누르면 선택 창 [말하기]와 같은 기기 안 인식(`OnDeviceRecognition` 공용, API 31+, 원격 인식 없음)으로 **한 번만** 듣고, 들은 말을 `VoiceSearchQuery.from`으로 정리("찾아 줘"·"검색"·"앱"·"을/를" 꼬리 제거, 남는 게 없으면 검색 안 함)해 검색 칸에 넣는다 → 기존 "앱 ○개를 찾았어요"가 읽힌다. 듣는 중엔 "듣고 있어요."(Polite 1회)와 버튼 이름 "듣기 멈추기, 듣는 중", 다시 누르면 취소(검색어 그대로). 못 알아들음·아무 말 없음·오류는 짧은 고정 문장 하나로 끝(명령어 금지 시험 대상 `ui_voice_search_*`). 버튼 표시는 `SpeakButtonPolicy` 그대로 — 지원 안 되는 기기(LG)·권한 없음·음성 명령 꺼짐·release(RECORD_AUDIO 없음)면 숨기고 "키보드의 음성 입력 버튼으로도 찾을 수 있어요." 한 줄, 권한만 없으면 설정 화면과 같은 [마이크 허용]도 보인다. 화면을 떠나거나 앱이 멈추면 조용히 듣기를 멈춘다. 들은 문장·검색어는 로그에 남기지 않는다. 시험 `VoiceSearchTest`. 미검증: S26 실기기 인식·TalkBack 낭독이 마이크로 들어가는지, 같은 말을 두 번 하면 결과 개수가 다시 읽히지 않는 점(검색어가 안 바뀜).

## 미검증 / 남은 일

- LG TalkBack 실기기 확인 전: 체크 상자 낭독, 비활성 [시작하기]의 stateDescription 낭독, 검색 결과 개수가 글자마다 겹쳐 읽히는지.
- 실제 A 서비스가 고른 앱에서 팝업을 잡는지(연동) 미검증.
- 추천 이름 목록과 기기 표시 이름이 맞는지 확인 필요.
