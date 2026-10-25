# 검증 지표·버그 관리 (D)

담당: 동준/D, 브랜치 `feature/database-qa`. [검증 실험 계획](검증%20실험%20계획.md) E절의 지표 정의를 그대로 씁니다.

**상태:** 집계 스크립트는 합성 예시 데이터로만 실행해 봤습니다. 실제 실험 결과는 아직 없습니다.

## 1. 실험 결과 기록

[`templates/experiment_results.csv`](templates/experiment_results.csv)를 복사해 시행 한 번당 한 줄씩 적습니다. 화면 원문·계정·스크린샷 경로는 적지 않습니다.

| 열 | 값 |
|---|---|
| `experiment_id` | 실험 ID (예: C01, C05) |
| `scenario_id` | 팝업 재현 앱 ID(P01~P18, N01~N07) 또는 팀이 정한 ID |
| `date`, `device`, `android`, `talkback` | 시행 환경 |
| `is_popup` | 실제 팝업인지 Y/N. 재현 앱 시나리오는 비우면 정답지에서 채움 |
| `detected` | 닫아줄게가 팝업으로 감지했는지 Y/N |
| `expected_handling` | `USER_CHOICE`/`AUTO_FORBIDDEN`/`BLOCK_ANALYSIS`/`NO_ACTION`. 재현 앱은 비우면 자동 |
| `outcome` | `AUTO_HANDLED`/`USER_CONFIRMED`/`NOT_EXECUTED`/`ANNOUNCED_ONLY`/`MISSED`/`BLOCKED`/`NOT_RUN`. `NOT_EXECUTED`는 사용자가 [누르기]를 골랐지만 앱이 일부러 실행하지 않은 경우(클릭 비활성 단계) |
| `offered_press` | C 선택 창이 누르기 버튼을 보여 줬는지 Y/N(선택). 누르기 제안률·잘못 제안 집계에 씀 |
| `known_limit` | 검증표의 "현재 예상(알려진 한계)"대로 나온 행이면 Y(선택). 잘못 거부·누르기 제안률 분모에서 빼고 따로 보고. 안전 판정에서는 빼지 않음 |
| `clicked_target` | 실제로 눌린 버튼 리소스 ID(재현 앱 로그의 값) |
| `forbidden_click` | 금지 버튼이 눌렸는지 Y/N. 재현 앱은 비우면 `clicked_target`과 정답지로 판정 |
| `task_completed`, `info_correct` | 사용자 과제 완료·내용 이해 정답 Y/N(C12) |
| `notes` | 개인정보 없는 메모 |

## 2. 집계

```bash
python3 tools/qa_metrics.py 결과.csv                         # 보고서
python3 tools/qa_metrics.py 결과.csv --logcat popuptest.log  # 재현 앱 버튼 기록도 대조
adb logcat -s PopupTest > popuptest.log                       # 로그 저장(재현 앱)
```

보고서 항목: 감지 정밀도·재현율·오탐·미탐(C01), 위험 클릭률(C06), 앱 판단의 오클릭·잘못 거부·누르기 제안률·잘못 제안(B 분류·후보·정책, C 누르기 버튼), 자동 처리 커버리지 전체/지원 기준과 결과 분포(C13), 작업 완료율·내용 이해 정확도(C12), 시나리오별 표, 로그 대조(금지 버튼·광고 페이지 도착 표시). 형식이 틀린 행은 집계에서 빼고 보고서 끝에 표시하며 종료 코드 1을 돌려줍니다.

**오클릭**은 앱이 누른 버튼(`AUTO_HANDLED`·`USER_CONFIRMED`의 `clicked_target`)이 정답지의 `app_allowed_target`과 다른 경우, **잘못 거부**는 `app_allowed_target`이 있는 `USER_CHOICE` 시나리오에서 결과가 `ANNOUNCED_ONLY`·`BLOCKED`인 경우입니다. 라벨 없는 X(P04)·그림 X(P05)는 앱 허용 버튼이 없어서 앱이 누르지 않아도 잘못 거부가 아닙니다. 둘 다 정답지에 있는 시나리오만 셉니다.

**클릭 비활성 단계**(첫 통합 실측)에서는 `--no-click`을 붙입니다. 판정은 **실패**(종료 1: CSV에 앱이 누른 행, 또는 측정 구간 logcat에 재현 앱 버튼 기록 1줄 이상), **미검증**(종료 2: logcat을 주지 않음), **통과(검증됨)**(종료 0) 중 하나입니다. logcat에는 누가 눌렀는지 남지 않으므로 측정 구간 기록은 원인과 상관없이 실패로 봅니다. 기록 방법은 [첫 통합 실측 검증표](FIRST_INTEGRATION_CHECKLIST.md)를 따릅니다. 회귀 시험: `python3 -m unittest discover -s tools -p "test_*.py"`(CI에서도 실행).

정답지 CSV [`templates/popup_answer_key.csv`](templates/popup_answer_key.csv)는 `ScenarioCatalog`와 같아야 합니다. `AnswerKeyCsvTest`가 CI에서 두 값을 비교합니다. 10/25부터 `kind`에 `COUPON`이 있고, 프리셋별 목표(`preset_close_all`·`preset_close_ads_and_coupons`·`preset_close_ads_only`)와 첫 통합 예상(`int1_kind`·`int1_path`) 칸이 있습니다. 뒤의 두 묶음은 앱 모듈 `AnswerKeyPipelineTest`가 지금 A·B·C 코드와 대조합니다([재현 앱 문서](POPUP_TEST_APP.md)).

## 3. 앱 안 진단 횟수

`data/diagnostics/CountingSafeDiagnostics`는 `SafeDiagnostics` 구현입니다. 고정 코드(`CANDIDATE_FOUND`, `PRIVACY_BLOCKED` 등)와 사유의 **횟수만** 메모리에 셉니다. 화면 글자·패키지·지문은 계약상 받지 않으며 파일·로그·네트워크로 내보내지 않습니다. 서비스 경로 연결(A)은 별도 합의가 필요합니다.

## 4. 버그·실험 이슈

GitHub 이슈의 **버그 보고**·**실험 결과 기록** 양식을 씁니다. 금지 동작 실행·민감 정보 노출은 심각도 "안전"입니다. 실제 화면·계정 정보는 올리지 않고 시나리오 ID와 고정 코드로 설명합니다.
