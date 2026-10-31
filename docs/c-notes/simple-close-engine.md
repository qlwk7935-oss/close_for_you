# 단순 닫기 엔진 (플랜 B) — C 메모

- 담당 C(재호), 브랜치 `c/simple-close-engine` (← origin/main 365a784), 2026-10-31.
- 근거: AGENTS.md 6-0 시연 모드, 9장 C 11/1 "크롬 1개 사이트에서 감지 → 선택 → 닫힘".
- 상태: **JVM 시험만 통과. 실기기 미검증.** A·B·D·core 코드는 바꾸지 않았다(새 파일만).

## 1. 무엇인가

우리 앱(`kr.closeforyou`) 안의 **두 번째 접근성 서비스** "팝업처리도우미 (단순 엔진)". debug 빌드에만 선언된다
(`app/src/debug/AndroidManifest.xml`). 접근성 설정에서 **"닫아줄게"(A)와 둘 중 하나만 켠다.**

```
창 이벤트(도와줄 앱) → 300ms 뒤 활성 창 트리 읽기(보이는 노드만)
→ 팝업 루트 찾기: ① Dialog 클래스 노드(크롬 role=dialog → android.app.AlertDialog)
                 ② 같은 앱의 큰 창 위에 뜬 작은 대화상자 창
                 ③ 화면을 덮는 클릭 가능한 배경(leaf) + 그 옆/안의 카드
→ 루트 안 글자·버튼으로 PopupAnalysis 만들기 → B DecisionPopupClassifier 그대로
→ 결정: 결정·사기 → 설명만 / 프리셋이 닫는 종류 + 자동 닫기 스위치 + 닫기 버튼 1개 → 바로 클릭
        / 그 밖(쿠폰·공지·판단 불가·버튼 0개 또는 2개 이상) → C 선택 창
→ 클릭 직전 같은 루트·같은 이름·같은 역할인지 다시 읽기 → ACTION_CLICK 1회(클릭 불가면 루트 안 조상까지만)
→ 1.5초 뒤 다시 읽기: 같은 지문 없음 → 닫았어요 / 있음 → 확인하지 못했어요 / 다른 앱 → LEFT_APP 문장
```

## 2. 파일

| 위치 | 역할 |
|---|---|
| `simpleclose/SimpleCloseEngineFeature.kt` (main) | 스위치 한 곳: `enabled`, `demoLabelRulesV2`, `helpAllAppsWhenUnset`, 시간 상수 |
| `simpleclose/SimpleLabelRules.kt` | 공용 `ButtonLabelRules` 먼저 → 시연 v2("○○ 닫기", "웹으로 볼래요", "배경을 눌러 닫기") → 금지어는 항상 우선. `SimpleLabelPolicy`로 C 프롬프트에 꽂음 |
| `simpleclose/PopupRootFinder.kt` | 팝업 루트 ①②③ (순수) |
| `simpleclose/CloseButtonFinder.kt` | 루트 안 클릭 가능 노드 → `CloseCandidate`(id `node:<path>`). 이름 없는 클릭 칸은 안쪽 글자로 이름 |
| `simpleclose/SimplePopupAnalysis.kt` | 노드 → `PopupSnapshot`/`PopupAnalysis` 어댑터, 지문 |
| `simpleclose/SimpleCloseDecider.kt` | AUTO_CLOSE / ASK / DENY_DECISION / DENY_SUSPICIOUS. 동의는 `UiVoiceSettings.autoCloseKinds(live = true)` |
| `simpleclose/SimpleAppAllowance.kt` | "도와줄 앱" 설정 해석 + 시스템·권한 패키지 제외 |
| `simpleclose/SimpleTrace.kt` | 로그 코드(enum만) |
| `debug/.../simpleclose/SimpleTreeCollector.kt` | Android 노드 → DTO, path로 다시 찾기 |
| `debug/.../simpleclose/SimpleCloseAccessibilityService.kt` | 서비스 본체 + C 부품 조립 |
| `debug/res/xml/ui_simple_close_service.xml`, `debug/res/values/ui_voice_debug_strings.xml` | 서비스 설정·이름 |

## 3. 끝까지 지키는 것 (6-0)

- 금지어(구매·결제·주문·동의·허용·권한·설치·구독·삭제·로그인·가입·쿠폰 받기·자세히·오늘 하루·보지 않기·취소…)가 든 버튼은 **어떤 모드에서도 안 누른다.** "○○ 닫기" 형태만 "설치"를 설명어로 허용("앱설치 안내 레이어 닫기").
- USER_DECISION·사기 의심은 B 판단 그대로 설명만(`SpeechHandlingListener` DENY 문장).
- 라벨 없는 X는 후보가 아니다(선택 창에서 "대신 닫을 수 없어요").
- 클릭 1회, 재시도·좌표 탭 없음. 클릭 직전 재확인.
- 로그는 `CfySimple` 태그에 enum 이름만. 화면 글자·패키지 원값 없음.

## 4. 시연 모드 완화 (PR에 "시연 모드 완화")

- 라벨 v2(위), 처음 보는 팝업도 프리셋+스위치면 묻지 않고 닫기(A AutoEligibility 없음). (도와줄 앱 미선택 시 모든 앱은 우진 PR #65에서 제거: 미선택이면 A 허용 목록만, 민감 앱은 항상 제외.)
- 끄려면 `SimpleCloseEngineFeature`의 상수를 false로.
- **시연 모드 6-0 완화: 전부 자동 닫기에서 안전 조건을 모두 만족한 판단 불가 팝업 닫기 (재호 결정 10/31, F7, 통합 브랜치 `integration/popup-helper`).** 프리셋 "전부 자동 닫기" + 자동 닫기 스위치 ON일 때만, B가 UNKNOWN으로 본 팝업을 ① 금지어 버튼(결제·구매·동의·권한·구독·삭제·설치·앱에서 열기…) 0개 ② 검증된 버튼이 라벨 있는 닫기 정확히 1개(라벨 없는 X 제외, 6-3) ③ 사기 의심 아님 ④ USER_DECISION 아님일 때 묻지 않고 닫는다(클릭 직전 재확인 그대로). "광고·쿠폰 닫기"·"광고만 닫기"는 계속 묻는다. 결과 "팝업을 닫았어요", 로그 `DECIDED_AUTO_CLOSE_UNKNOWN`. 끄기: `DemoUnknownAutoClose.ENABLED = false`. AGENTS 6장 2번은 고치지 않음.

## 5. LG 확인 순서 (메인 세션)

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. 설정 → 접근성 → "닫아줄게" **끄기** → "팝업처리도우미 (단순 엔진)" **켜기**. (TalkBack은 켜 둠)
3. 우리 앱 처음 설정에서 프리셋 선택 + 자동 닫기 스위치 ON (바로 닫기를 보려면). 끄면 모두 선택 창.
4. `adb logcat -s CfySimple` 띄워 두기.
5. 크롬 시크릿 탭 → `m.11st.co.kr`.
6. 기대 로그: `SERVICE_CONNECTED` → `DETECTED_ALERTDIALOG` → `CLASSIFIED_*` → `BLOCKED_FORBIDDEN`(앱에서 확인) → `CANDIDATE_FOUND` → `DECIDED_ASK`(닫기 버튼 2개) → `ASKED` → 선택 창 "'앱설치 안내 레이어 닫기' 버튼 눌러 주기" / "'모바일 웹으로 볼래요' 버튼 눌러 주기" → 두 번 탭 → `USER_CHOSE_PRESS` → `CLICKED` → `CLOSED_CONFIRMED` + "닫았어요".
7. 옥션 `m.auction.co.kr`(봇 확인 화면이 아니면): `DETECTED_ALERTDIALOG` → `CLASSIFIED_AD` → `DECIDED_AUTO_CLOSE` → `CLICKED` → `CLOSED_CONFIRMED` → "광고를 닫았어요".
8. 안 되면 `NO_POPUP_ROOT`(루트 못 찾음) / `TREE_UNAVAILABLE`(크롬이 웹 노드를 안 줌) / `SKIPPED_NOT_ALLOWED_APP`(도와줄 앱 설정) 중 어느 코드에서 멈추는지.

## 5-1. LG 1차 결과 (10/31 18:47, 657577d)

- ✅ 크롬 시크릿 11번가: `DETECTED_ALERTDIALOG` → 선택 창 → 두 번 탭 → `USER_CHOSE_PRESS` → `CLICKED` → `CLOSED_CONFIRMED`, 실제 팝업 닫힘.
- ❌ 고친 것(다음 커밋): ① 팝업 없는 본 화면을 덮개로 오감지해 2초마다 `DECIDED_DENY_DECISION` → 덮개 판정 강화(글자 없는 클릭 가능 배경이 leaf이거나 "닫기"류 설명 + 카드 안에 닫기/건너뛰기 버튼 필수). ② 같은 팝업(창 + 루트 종류·path·bounds 키)은 화면에 남아 있는 동안 한 번만 처리·안내, 반복 감지는 조용히(`SAME_POPUP_COOLDOWN`). ③ 연속 같은 로그 코드는 한 번만, 우리 창 이벤트는 로그 안 함. ④ 결정·사기 DENY 문장은 같은 팝업 키에 세션당 1회(`denied`). TTS는 원래 QUEUE_FLUSH라 새 문장이 이전 문장을 대체(큐 안 쌓임); 문장은 "확인이 필요한 팝업이에요. 직접 확인해 주세요." 한 줄.

## 5-2. LG 2차 결과 (10/31 19:06, c79158d) → 3차 수정

- ✅ 반복 안내 사라짐(닫힌 뒤 `NO_POPUP_ROOT`만), 11번가 닫힘 재확인.
- ❌ 말이 많음(원문 전체 낭독), 우리 창 스와이프 느림, 선택 → 클릭 2.3초, 클릭 → 닫힘 확인 5.7초.
- 고친 것: ① 선택 창 TTS는 종류 한 문장만(`READ_POPUP_TEXT_ON_PROMPT = false`; 원문은 [내용 다시 읽기]). ② 트리 읽기를 메인 스레드 밖(`Dispatchers.Default`)으로, 선택 창이 떠 있는 동안은 2초에 한 번 루트 노드만 확인(`PROMPT_RECHECK_MILLIS`). ③ 클릭 직전 재확인은 전체 트리 대신 라이브 노드 몇 개(`rootStillPresent`·`targetMatches`), 선택 즉시 TTS 끊고 클릭. ④ 닫힘 확인은 250ms마다 루트 노드만 폴링, 사라지는 즉시 `CLOSED_CONFIRMED`(최대 1.5초). ⑤ 결과 문장 전에 진행 중 TTS 중지. ⑥ `CfySpeech` 태그: 우리 TTS 문장마다 종류(PROMPT_OR_DENY_NOTE / CONTENT_FULL / RESULT / REPEAT / STOP) + 글자 수만.

## 5-3. LG 3차 결과 (10/31 19:40, 140f6f7) → 4차 수정

- ✅ 우리 TTS 안내 1 + 결과 1(+STOP 3)로 끝. 선택 → 클릭 53ms, 클릭 → 닫힘 확인 270ms.
- ❌ 감지 40초 늦음: 첫 읽기 때 시트가 애니메이션 중이라 `NO_POPUP_ROOT`, 이후 크롬이 이벤트를 더 안 보냄. ⚠️ 닫힌 뒤 TalkBack이 DOM에 남은 팝업 내용을 계속 읽음.
- 고친 것: ① 팝업을 못 찾은 뒤 0.5/1/2/3초에 다시 읽기(`RECHECK_DELAYS_MILLIS`, 새 이벤트가 오면 처음부터; 로그 `RECHECK_SCHEDULED`). ② `CLOSED_CONFIRMED` 뒤 TalkBack 포커스를 WebView 안 첫 이름 있는 항목으로(`FOCUS_PAGE_AFTER_CLOSE`, 주소창 제외; 로그 `FOCUS_MOVED_TO_PAGE`/`FOCUS_MOVE_FAILED`). 시연 모드 완화(6-10). 효과 없으면 한계로 기록.

## 5-4. LG 4차 결과 (10/31 19:52, 3e47eee) → 5차 수정

- ✅ TalkBack이 팝업을 한 번 읽고 끝나면 우리 창 첫 버튼으로 포커스("나이스"), 스와이프 빠름, 두 번 탭 → 바로 닫힘(53ms/270ms), `FOCUS_MOVED_TO_PAGE` 찍힘.
- ❌ 닫힌 뒤 TalkBack이 팝업 내용을 한 번 더 읽음 — 포커스 대상이 DOM에 남은 팝업 레이어 글자였을 가능성.
- 고친 것: ① 닫힌 뒤 포커스 대상은 팝업 노드의 글자·bounds(`PopupMemory`, 메모리만)와 겹치지 않고 `isVisibleToUser`·넓이>0인 항목만. ② 순서: `CLOSED_CONFIRMED` → 우리 RESULT 문장을 바로, 끝까지 말함("광고를/공지를/팝업을 닫았어요", `ui_auto_closed_one`, 2초 묶음 없이) → 그 뒤 포커스 이동(TalkBack 낭독과 겹치지 않음).
- B 분류 메모(바꾸지 않음): 11번가 앱설치 유도 시트("앱에서만 제공되는 혜택…", "지금 바로 … 확인해보세요")는 B main `DecisionKinds`에서 **NOTICE**("안내" 단어). 사용자는 "광고 닫았어요"를 기대. B 브랜치 `feature/decision-web-overlay`의 `extraAdKo`(앱에서만+혜택, 앱 열기/설치/다운로드)가 들어오면 ADVERTISEMENT가 되고, 프리셋 "광고만 닫기"에서도 묻지 않고 닫힘. B(준영) 판단 사항.

## 5-5. LG 옥션 자동 닫기 (10/31 20:01, 3e47eee) → 같은 빌드에 수정

- ✅ 실제로 닫힘(`CLASSIFIED_AD → DECIDED_AUTO_CLOSE → CLICKED`). ❌ `CLOSED_CONFIRMED`·RESULT 없음.
- 원인: 자동 닫기가 **검사(inspection) 코루틴 안에서** 실행됐고, 클릭 200ms 뒤 들어온 다른 패키지 이벤트가 그 코루틴을 취소(`pending?.cancel()`)해 확인 폴링이 중간에 죽음. 사용자 선택 경로는 별도 `launch`라 멀쩡했음.
- 고친 것: `EngineJobs`(순수) — 검사 작업과 실행 작업을 분리. 이벤트는 검사만 교체하고 실행 작업은 절대 취소하지 않으며, 실행 중에는 이벤트를 무시한다. 자동·사용자 누르기 모두 `runAction`. JVM 시험 4개(`EngineJobsTest`: 다른 패키지 이벤트 뒤에도 확인·RESULT 1회 등).

## 5-6. LG 3acd2ae 결과 (10/31 20:02~20:06) → 수정

- 11번가 새 시크릿 탭에서 4분간 `NO_POPUP_ROOT`만, 선택 창 안 뜸. 코드상 감지 경로는 3e47eee와 같음. **유력 원인: 크롬 시크릿 탭은 모두 닫을 때까지 세션(쿠키)을 공유** — 19:53에 "모바일 웹으로 볼래요"로 닫은 뒤 쿠키가 남아 시트 자체가 안 떴을 가능성. 확인: 시크릿 탭 전부 닫고 다시, 또는 시트가 보이는 상태에서 `uiautomator dump`에 AlertDialog 노드가 있는지.
- 재확인 체인이 크롬 content-changed마다 리셋돼 1~2초마다 트리를 읽음 → ① 체인은 WINDOW_STATE/WINDOWS 이벤트에서만, content-changed는 1.5초에 1회 단독 읽기(`CONTENT_CHANGE_READ_MILLIS`, 로그 `CONTENT_READ_THROTTLED`).
- 옥션 두 번째 방문: 이미지만 있는 배너라 B UNKNOWN → 묻기(규칙상 정상). ② 엔진 힌트 `ImageDialogAdHint`(`IMAGE_DIALOG_AD_HINT`): Dialog 루트 + 루트 40% 이상의 클릭 가능 이미지 + 검증된 닫기 버튼 + 비클릭 글자 80자 이하 + B UNKNOWN + 사기 아님 → 광고로 취급(로그 `AD_HINT_IMAGE_DIALOG`). **B 코드가 아닌 엔진 규칙(시연 모드 완화)**: 이미지로 그린 공지도 광고로 닫힐 수 있음. JVM 시험 5개.

## 5-7. LG a946e25 결과 (10/31 20:30~20:34) → 수정

- ✅ 옥션 자동 닫기 완주: 감지 → 닫힘 확인 0.33초, "광고를 닫았어요". ✅ 11번가(시크릿 탭 전부 닫은 뒤): 감지 → 선택 창 → 두 번 탭 → 닫힘, 쿠키 추측 맞음.
- ❌ 11번가 닫힌 뒤 재낭독 원인: 우리 창이 사라지는 순간 TalkBack 포커스가 크롬의 마지막 자리(DOM에 남은 팝업 글)로 돌아가 읽기 시작; 우리 `FOCUS_MOVED_TO_PAGE`는 RESULT 뒤 9.5초(TTS 완료 콜백을 기다림).
- 고친 것: `CLOSED_CONFIRMED` 직후 **포커스 이동 먼저**(예전 팝업 노드 제외 유지), RESULT는 완료를 기다리지 않고 바로 말함(자동·사용자 경로 공통).
- 기록만(손대지 않음): 우리 창이 뜰 때 TalkBack이 이미 팝업 글을 읽는 중이면 끝까지 읽음(포커스는 우리 첫 버튼).

## 6. 남은 위험·미확인

- 크롬이 실기기에서 `AlertDialog` 노드를 주는지(10/30 LG 확인됨), 두 번째 서비스에서도 주는지.
- 두 서비스를 동시에 켜면 이벤트·선택 창이 겹친다 — 하나만 켜기.
- `rootInActiveWindow`가 크롬 큰 페이지에서 2,500 노드 한도 안에 팝업을 포함하는지(네이버).
- B main 분류가 11번가 설치 시트를 광고로 보지 않아(판단 불가) 묻는다 — B 브랜치 `feature/decision-web-overlay` 병합 뒤 광고 → 바로 닫기로 바뀜.
- 올리브영 ③ 덮개 규칙은 덤프 구조 설명만으로 짠 것. 실제 노드 구조 확인 필요.
- 선택 창 띄운 뒤 팝업이 사라졌을 때(사용자가 직접 닫음) 창 닫기: 다음 창 이벤트에서 `POPUP_GONE_PROMPT_DISMISSED`.
