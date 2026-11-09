# 실제 사이트·앱 시행 기록과 집계 (D)

담당: 동준/D. 실제 사이트·앱으로 시험한 결과를 같은 양식으로 모으고 D가 집계해 "어디서 되고 어디서 안 되는지" 표와 증명 자료로 씁니다. 재현 앱(P·N 시나리오)은 기존 [`QA_METRICS.md`](QA_METRICS.md)를 그대로 씁니다.

**상태: 양식·기록 도구·집계 도구만 준비했습니다. 실제 시행 기록은 0건입니다.** 도구는 합성 로그·합성 기록으로만 시험했습니다(`tools/test_live_trial.py`, `tools/test_real_site_metrics.py`).

| 파일 | 용도 |
|---|---|
| [`templates/real_site_answer_key.csv`](templates/real_site_answer_key.csv) | 사이트별 정답지(L01~L25, 목록 밖 L90). 후보·예상값 |
| [`templates/real_site_trials.csv`](templates/real_site_trials.csv) | 시행 기록 양식(머리글만). 팝업 하나 = 한 줄 |
| `tools/live_trial.py` | 한 번 시험: `CfySimple` 로그를 받아 팝업별로 나누고 ①②③을 채운 뒤 몇 가지만 묻고 기록 |
| `tools/real_site_metrics.py` | 집계: 전체 지표, 잘못 누름, 못 찾은 곳, 브라우저별·팝업 종류별·사이트별 표, 시행 목록 |
| [`DEMO_CANDIDATES.md`](DEMO_CANDIDATES.md) | 후보와 고른 기준 |

## 1. 지켜야 할 것

- **화면 원문을 적지 않습니다.** 팝업 문구·상품 이름·계정·스크린샷 경로를 기록에 쓰지 않습니다. 사이트 ID·코드·Y/N·숫자만 적습니다. 도구는 `notes` 등에 한글이 있으면 기록하지 않습니다. 목록 밖 사이트는 `--site-code SITE_영문`으로 적습니다.
- `CfySimple`·`CfySpeech` 로그에는 단계 코드와 글자 수만 있고 화면 글자·패키지 이름이 없습니다(앱 debug 빌드).
- 로그인이 필요한 곳은 시험자 본인 계정으로 하고, 계정 정보는 어디에도 적지 않습니다.
- 결제·구매·주문·동의·구독·삭제·권한·로그인 버튼은 시험 중에도 사람이 누르지 않습니다(6-0 유지 항목). **앱이 누르려 했거나 눌렀으면 화면을 그대로 두고 바로 단톡에 🚨로 알립니다.**

## 2. 넓게 돌리기 (11/2~11/4, 재호 요청)

목표: 시연은 2곳이지만 "웬만한 앱·브라우저에서 되게" 만들기 위한 1차 표. 11/4까지 1차 표 → 실패 많은 유형부터 A가 고침 → 11/11 검증 실험 전에 다시 돌려 개선 전후 비교.

### 2-1. 준비

```bash
git fetch && git checkout integration/popup-helper   # 받은 커밋을 표에 적음(app_build)
./gradlew :app:assembleDebug :popup-test-app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t popup-test-app/build/outputs/apk/debug/popup-test-app-debug.apk   # 재현 앱은 -t 필요
```

- 접근성: **"팝업처리도우미"만 켜기**("닫아줄게"는 끄기).
- 처음 설정: 도와줄 앱 = 모든 앱, 처리 방식 = 전부 자동 닫기(공지는 묻기).
- TalkBack: 켜고 한 바퀴, 끄고 한 바퀴면 더 좋음(시간 없으면 켜고만). 표에 `--talkback Y|N`.
- 시크릿 탭으로 다시 띄울 때는 **시크릿 탭을 전부 닫고 새로 엽니다**(시크릿 탭끼리 쿠키를 같이 써서 같은 팝업이 안 뜰 수 있음).

### 2-2. 범위와 순서 (시간이 부족하면 위에서부터)

| 순서 | 무엇 | 사이트·앱 | 브라우저 |
|---|---|---|---|
| 1 | 재현 앱 회귀(감지 경로 바뀜) | P01·P04·P05·P33·P34·P35, 웹 W02·W03·W09·W10·W11 | 앱 / 크롬 |
| 2 | 정답지 사이트 | L01~L13 웹, L20 | 크롬 |
| 3 | 다른 브라우저 | 팝업이 잘 뜨는 5곳(예: L01·L04·L06·L08·L12) | 삼성 인터넷·네이버 앱·웨일 |
| 4 | 앱 안 브라우저 | 카톡에서 링크 열기, 인스타에서 링크 열기(사이트는 3번 중 2~3곳) | `KAKAO_INAPP`·`INSTA_INAPP` |
| 5 | 앱 자체 팝업 | L14~L19, L21~L25(쿠팡·당근·11번가·G마켓·네이버 앱) | `APP` |
| 6 | 특이 유형 찾기 | 화면 어둡게 덮는 팝업(올리브영 등), 이름 없는 X만 있는 팝업, 영상 광고, 화면 전체 광고, 아래쪽 띠 배너 | 아무 데나, 목록 밖이면 L90 |

- 재현 앱 P04·W02(이름 없는 X)에서 F32 관련 코드(`DETECTED_DIM_WINDOW`·`X_BUTTON_MOVE_OFFERED`·`POPUP_ACTIVITY_HINT` 등)가 나오면 표 메모에 남기고 같이 봅니다.
- 웹 재현 페이지(W01~W11)는 PC에서 `cd popup-test-app/src/main/assets && python -m http.server 8000` → 휴대폰 브라우저에서 `http://<PC IP>:8000/popup_test_page.html#sheet` 처럼 엽니다.

### 2-3. 한 번 시험하기

```bash
python tools/live_trial.py --site L06 --browser CHROME --preset CLOSE_ALL --talkback Y --build <커밋7자리> --out trials.csv
```

1. 도구가 로그를 지우고 받기 시작합니다.
2. 휴대폰에서 사이트·앱을 엽니다. 팝업 처리가 끝나면(닫혔거나, 물어봤거나, 아무 일도 없으면 10초쯤 뒤) PC에서 Enter.
3. 도구가 팝업별로 ①찾았나(`DETECTED_*`) ②분류(`CLASSIFIED_*`) ③처리(자동 닫음/물어봄/안 함)를 보여 줍니다.
4. 팝업마다 몇 가지만 답합니다(Enter = 기본값): 팝업 모양 번호, 사람이 보기에 무슨 팝업인지(광고·쿠폰·공지·결정·사기 의심), 이름 있는 닫기가 있었나, 실제로 사라졌나, 🚨 금지 버튼을 눌렀나, 엉뚱한 곳을 눌렀나, 안내가 맞았나, 걸린 초.
5. `trials.csv`에 팝업 수만큼 줄이 붙고 ④맞게 했나(정답지 기준)를 바로 보여 줍니다. 원본 로그는 `live_logs/`에 남습니다.

①이 안 찍히면(팝업을 못 찾음) 도구가 "팝업이 떴나요?"만 묻고 못 찾은 이유 코드(`SKIPPED_SCOPE_*`·`NO_POPUP_ROOT` 등)를 메모에 남깁니다. 정답지 목록 밖 사이트는 `--site L90 --site-code SITE_영문이름`.

### 2-4. 1차 표 만들기

```bash
python tools/real_site_metrics.py trials.csv > real_site_report.md
```

보고서에 들어가는 것: 전체 지표, 🚨 잘못 누름 목록, **못 찾은 곳**(사이트·브라우저·팝업 모양·이유 코드), **브라우저별**·**팝업 종류별**·사이트별 표, 그리고 재호 양식의 시행 목록(| 사이트/앱 | 브라우저 | 팝업 종류 | ①찾았나 | ②분류 | ③처리 | ④맞게 했나 | ⑤잘못 누름 | 메모 |).

## 3. 시행 기록 열 (팝업 하나 = 한 줄)

`live_trial.py`가 채우는 칸은 ⚙, 사람이 답하는 칸은 ✋입니다.

| 열 | 값 | 설명 |
|---|---|---|
| `trial_id` ⚙ | `L06-CHROME-3-a` | 사이트-브라우저-번호(-팝업 순서) |
| `site_id`, `browser` ⚙ | `L01`~`L25`·`L90` / `CHROME`·`SAMSUNG`·`NAVER`·`WHALE`·`KAKAO_INAPP`·`INSTA_INAPP`·`APP`·`OTHER` | 명령에서 받음 |
| `date`, `tester`, `device`, `android`, `app_build`, `talkback`, `preset`, `rehearsal` ⚙ | | 명령에서 받음 |
| `popup_type` ✋ | `EVENT_POPUP`·`COUPON_POPUP`·`NOTICE_POPUP`·`APP_INSTALL_SHEET`·`OPEN_IN_APP_SHEET`·`BOTTOM_SHEET`·`BOTTOM_BANNER`·`DIM_LAYER`·`FULL_LAYER`·`INTERSTITIAL`·`UNLABELED_X_ONLY`·`VIDEO_AD`·`LOGIN_PROMO`·`PERMISSION`·`COOKIE_CONSENT`·`SURVEY`·`OTHER` | 사람이 본 모양 |
| `human_kind` ✋ | `AD`·`COUPON`·`NOTICE`·`DECISION`·`SUSPICIOUS`·`OTHER` | 사람이 보기에 무슨 팝업인지(기본값은 모양에서 추정). 잘못 닫음 판정용 |
| `popup_appeared` ✋ | Y/N | 팝업이 실제로 떴는지. N이면 집계에서 뺌 |
| `detected`, `detected_code` ⚙ | Y/N, `DETECTED_*` | ①찾았나 |
| `classified_code`, `observed_kind` ⚙ | `CLASSIFIED_*`(+`WORD_HINT_*`·`AD_HINT_IMAGE_DIALOG`) | ②분류 |
| `outcome` ⚙ | `CLOSED_BY_APP`·`USER_CONFIRMED`·`ASKED_ONLY`·`WARNED`·`NO_ACTION`·`MISSED`·`NOT_RUN` | ③처리 |
| `clicked_target` ⚙✋ | `CLOSE`·`STAY_WEB`·`DIM_OUTSIDE`·`HIDE_TODAY`·`POLITE_NO`·`SKIP`·`UNLABELED_X`·`OTHER`·`NONE`, 또는 금지 코드 | `CLICKED_*` 로그에서. 금지 버튼을 눌렀다고 답하면 그 코드 |
| `close_available` ✋ | Y/N | 이름 있는 닫기 버튼이 있었나("오늘 하루 보지 않기" 순서 판정용) |
| `closed_verified` ⚙✋ | Y/N | `CLOSED_CONFIRMED` 기준, 사람이 고칠 수 있음 |
| `forbidden_click`, `misclick` ✋ | Y/N | ⑤잘못 누름 |
| `info_correct` ✋ | Y/N | 안내 문장이 화면과 맞았는지 |
| `seconds_to_close` ✋ | 숫자 | 대략 |
| `notes` ⚙ | 코드만 | 예: `LEFT_APP;REAPPEARED`, 못 찾은 이유, `SITE_영문` |

## 4. 판정 기준 (11/2 팀 결정 반영)

| 정답지 기대 처리 | 통과 |
|---|---|
| `CLOSE_OK` | 앱이 누르고 실제로 사라짐 |
| `CLOSE_OR_ASK` | 위 또는 물어봄(화면에 따라 둘 다 맞는 곳: L01 로그인 홍보는 홍보 문구 있으면 닫기, L12 언론사는 구독 버튼 있으면 묻기, L17 배민·L21 쿠팡 앱 등) |
| `ASK` | 물어봄(물어본 뒤 사용자가 누른 것 포함). 앱이 알아서 닫으면 실패 |
| `NO_PRESS` | 아무것도 누르지 않음(못 찾아도 통과) |

어느 경우든 아래면 실패입니다.

- **금지 버튼**(🚨, 안전 실패, 종료 코드 1): 정답지 행의 `forbidden_targets` + 어디서나 금지(결제·구매·주문·구독·동의·권한·삭제·로그인·경품 받기).
- **오클릭**(🚨): 팝업이 아닌 곳·엉뚱한 버튼.
- **"오늘 하루 보지 않기"**(금지가 아님, 닫기 다음 순서): 이름 있는 닫기가 있었는데 먼저 누르면 `HIDE_BEFORE_CLOSE`, "광고만 닫기"에서 묻지 않고 누르면 `HIDE_IN_ADS_ONLY`. 금지 클릭 수에는 넣지 않고 따로 셉니다.
- **잘못 닫음**(`WRONG_KIND_CLOSE`, 내용을 놓침): 앱이 묻지 않고 닫았는데 사람이 본 종류가 그 처리 방식에서 닫는 대상이 아님. 전부 자동 닫기·광고와 쿠폰 닫기는 광고·쿠폰만, 광고만 닫기는 광고만 닫아도 됩니다(공지는 시연 설정상 어디서나 묻기). 공지·결정·사기 의심을 닫으면 여기에 걸립니다. 보고서에 따로 목록(사람 기준 → 앱 분류)이 나오고, 판단 불가(UNKNOWN)인데 자동으로 닫은 수(F7)도 따로 셉니다. 금지 버튼과 달리 종료 코드에는 넣지 않습니다.
- 틀린 안내(`INFO_WRONG`), 누르고도 안 사라짐(`CLOSE_NOT_VERIFIED`), 못 찾음(`MISSED`).
- 건너뛰기(L19 유튜브)는 닫기 성공으로 세고 **분류 정확도에서는 뺍니다**(B 분류 전에 처리될 수 있음).

사이트에 실제로 뜬 팝업이 정답지 예상과 달라도 정답지는 그대로 두고, 시행 기록의 `popup_type`에 실제 모양을 적습니다(팝업은 날마다 바뀜). 리허설 결과가 쌓이면 정답지 `status`를 `OBSERVED`로 바꿉니다.

## 5. 일정 (AGENTS.md 9장, D)

| 날짜 | D 할 일 | 상태 |
|---|---|---|
| 10/31 | 사이트별 재현 방법, 실제 화면 정답지 v1 | 2절·정답지 v0.2(실측 없음) |
| 11/1 | 로그 수집 양식, 지표 표 틀 | 완료(3·4절, 도구) |
| 11/2~11/4 | 넓게 돌리기 1차 표 | 도구 준비됨, **실측 필요** |
| 11/3 | 앞으로 항상 닫기 지문(실제 사이트) | 실제 화면 구조가 있어야 함 |
| 11/4 | 리허설 결과 집계 | `--rehearsal-only` |
| 11/5 | 증명 자료 v1 | 지표 + [조사 1차](research/BLIND_USER_RESEARCH_V1.md) |
| 11/11 전 | 개선 뒤 다시 돌려 전후 비교 | 같은 명령, 다른 `--build` |
