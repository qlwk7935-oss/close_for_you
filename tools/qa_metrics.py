#!/usr/bin/env python3
"""D: 닫아줄게 검증 지표 집계 (표준 라이브러리만 사용).

입력
  results.csv   실험 시행 기록. 양식: docs/templates/experiment_results.csv
  --key         정답지(기본 docs/templates/popup_answer_key.csv). 팝업 재현 앱 시나리오면
                기대 처리·금지 버튼을 정답지에서 자동으로 채운다.
  --logcat      `adb logcat -s PopupTest` 저장 파일(선택). 눌린 버튼을 정답지와 대조한다.
  --no-click    실제 클릭을 연결하지 않은 단계(첫 통합 실측). 판정은 셋 중 하나다.
                  실패(종료 1): 결과 CSV에 앱이 누른 기록(AUTO_HANDLED·USER_CONFIRMED)이 있거나,
                               측정 구간 logcat에 재현 앱 버튼 기록이 한 줄이라도 있음
                  미검증(종료 2): logcat 파일을 주지 않음(클릭 0건을 확인할 근거 없음)
                  통과(종료 0): 위 둘 다 아니고 측정 구간 logcat 버튼 기록 0건
                측정 구간에는 시험자가 재현 앱을 직접 누르지 않는다. logcat에는 누가 눌렀는지 남지 않으므로
                기록이 있으면 원인과 상관없이 실패로 본다(보수적).

열(선택)
  offered_press C 선택 창이 누르기 버튼을 보여 줬는지 Y/N
  known_limit   검증표에 "현재 예상(알려진 한계)"로 적힌 행이면 Y. 누르기 제안률·잘못 거부 분모에서 빼고
                따로 보고한다. 안전 판정(앱이 누름·잘못 제안·금지 버튼)에서는 빼지 않는다.
  observed_kind 관측한 B 화면 분류(COUPON·ADVERTISEMENT·NOTICE·USER_DECISION·UNKNOWN, A가 막았으면 BLOCKED).
                정답지 int1_kind(첫 통합 예상)와 대조한다. 사람 기준 종류(kind)와는 따로 본다.
  preset        시행 때 켠 프리셋(OFF·CLOSE_ALL·CLOSE_ADS_AND_COUPONS·CLOSE_ADS_ONLY). 정답지 preset_* 목표를 고른다.
  auto_executable  그 시행에서 서비스가 실제로 자동 실행할 수 있었는지 Y/N(운영 자동 닫기·동의·F9·적격 근거가 모두
                켜짐). 프리셋 목표와 따로 적는다. 목표가 CLOSE여도 N이면 묻기가 정상이다.
                목표가 CLOSE가 아닌데 AUTO_HANDLED면 단계와 상관없이 실패(종료 1)다.
                AUTO_HANDLED 행은 유효한 preset(OFF 아님)과 auto_executable=Y가 반드시 있어야 하며, 없으면 입력 문제(종료 1)다.

출력
  Markdown 보고서(stdout). 지표 정의는 docs/검증 실험 계획.md E절을 따른다.

개인정보: 결과 파일에는 시나리오 ID·코드·Y/N만 적는다. 화면 원문·계정·스크린샷 경로를 적지 않는다.
이 스크립트의 수치는 기록된 시행에 대한 집계일 뿐 범용 성능을 뜻하지 않는다.
"""
from __future__ import annotations

import argparse
import csv
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_KEY = REPO / "docs" / "templates" / "popup_answer_key.csv"

OUTCOMES = (
    "AUTO_HANDLED", "USER_CONFIRMED", "NOT_EXECUTED", "ANNOUNCED_ONLY", "MISSED", "BLOCKED", "NOT_RUN",
)
EXPECTED = ("USER_CHOICE", "AUTO_FORBIDDEN", "BLOCK_ANALYSIS", "NO_ACTION")
DANGEROUS = {"AUTO_FORBIDDEN", "BLOCK_ANALYSIS"}
APP_ACTED = {"AUTO_HANDLED", "USER_CONFIRMED"}
REFUSED = {"ANNOUNCED_ONLY", "BLOCKED"}
YES, NO = "Y", "N"
OBSERVED_KINDS = ("COUPON", "ADVERTISEMENT", "NOTICE", "USER_DECISION", "UNKNOWN", "BLOCKED")
PRESETS = {"CLOSE_ALL": "preset_close_all", "CLOSE_ADS_AND_COUPONS": "preset_close_ads_and_coupons",
           "CLOSE_ADS_ONLY": "preset_close_ads_only"}

LOG_LINE = re.compile(r"PopupTest(?:\(\s*\d+\))?\s*:\s*([A-Z]\d\d):([A-Za-z0-9_]+)")


def yn(value: str | None) -> bool | None:
    v = (value or "").strip().upper()
    if v in ("Y", "YES", "TRUE", "1"):
        return True
    if v in ("N", "NO", "FALSE", "0"):
        return False
    return None


def ratio(num: int, den: int) -> str:
    return "N/A (0건)" if den == 0 else f"{num / den:.1%} ({num}/{den})"


def load_key(path: Path) -> dict[str, dict]:
    if not path.exists():
        return {}
    with path.open(encoding="utf-8") as f:
        return {row["scenario_id"]: row for row in csv.DictReader(f)}


def load_results(path: Path, key: dict[str, dict]) -> tuple[list[dict], list[str]]:
    rows, problems = [], []
    with path.open(encoding="utf-8-sig") as f:
        for line_no, row in enumerate(csv.DictReader(f), start=2):
            row = {k.strip(): (v or "").strip() for k, v in row.items() if k}
            sid = row.get("scenario_id", "")
            answer = key.get(sid)
            row["_line"] = line_no
            row["_in_key"] = bool(answer)
            row["_app_allowed"] = (answer or {}).get("app_allowed_target", "")
            if answer:
                row.setdefault("expected_handling", "")
                if not row["expected_handling"]:
                    row["expected_handling"] = answer["expected_handling"]
                if not row.get("is_popup"):
                    row["is_popup"] = NO if answer["kind"] == "NOT_POPUP" else YES
                forbidden = set(filter(None, answer["forbidden_targets"].split(";")))
                clicked = row.get("clicked_target", "")
                if not row.get("forbidden_click") and clicked:
                    row["forbidden_click"] = YES if clicked in forbidden else NO
            outcome = row.get("outcome", "")
            if outcome not in OUTCOMES:
                problems.append(f"{line_no}행: outcome '{outcome}'은(는) {', '.join(OUTCOMES)} 중 하나여야 합니다")
                continue
            if row.get("expected_handling") and row["expected_handling"] not in EXPECTED:
                problems.append(f"{line_no}행: expected_handling '{row['expected_handling']}'을(를) 알 수 없습니다")
            if row.get("observed_kind") and row["observed_kind"] not in OBSERVED_KINDS:
                problems.append(f"{line_no}행: observed_kind '{row['observed_kind']}'은(는) {', '.join(OBSERVED_KINDS)} 중 하나여야 합니다")
            if row.get("preset") and row["preset"] != "OFF" and row["preset"] not in PRESETS:
                problems.append(f"{line_no}행: preset '{row['preset']}'은(는) OFF, {', '.join(PRESETS)} 중 하나여야 합니다")
            if outcome == "AUTO_HANDLED":
                # 자동 처리 행은 목표 밖 자동 처리 검사를 반드시 거쳐야 한다(A #57 리뷰). 빠지면 안전 검사를 못 하므로 실패.
                if row.get("preset") not in PRESETS:
                    problems.append(f"{line_no}행: 자동 처리(AUTO_HANDLED)인데 preset이 '{row.get('preset', '')}'입니다. "
                                    f"{', '.join(PRESETS)} 중 하나여야 목표 밖 자동 처리를 검사할 수 있습니다")
                if yn(row.get("auto_executable")) is not True:
                    problems.append(f"{line_no}행: 자동 처리(AUTO_HANDLED)인데 auto_executable이 Y가 아닙니다"
                                    f"('{row.get('auto_executable', '')}'). 기록을 확인하세요")
            for col in ("offered_press", "known_limit", "auto_executable"):
                if row.get(col) and yn(row[col]) is None:
                    problems.append(f"{line_no}행: {col} '{row[col]}'은(는) Y/N이어야 합니다")
            rows.append(row)
    return rows, problems


def scan_logcat(path: Path, key: dict[str, dict]) -> tuple[list[str], list[tuple[str, str, int]]]:
    """(보고서 줄, 버튼 기록 [(시나리오, 버튼, 횟수)])."""
    lines = []
    counts: Counter = Counter()
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = LOG_LINE.search(raw)
        if m:
            counts[(m.group(1), m.group(2))] += 1
    if not counts:
        return ["- PopupTest 기록이 없습니다."], []
    records = []
    for (sid, control), n in sorted(counts.items()):
        answer = key.get(sid)
        records.append((sid, control, n))
        forbidden = set(filter(None, (answer or {}).get("forbidden_targets", "").split(";")))
        correct = (answer or {}).get("correct_target", "")
        allowed = (answer or {}).get("app_allowed_target", "")
        if control == "ad_landing_opened" or control in forbidden:
            verdict = "**금지 동작**"
        elif allowed and control == allowed:
            verdict = "정답 버튼(앱 허용)"
        elif correct and control == correct:
            verdict = "사람 기준 정답(앱은 누르면 안 됨)"
        elif answer is None:
            verdict = "정답지에 없는 시나리오"
        else:
            verdict = "기타"
        lines.append(f"- {sid} `{control}` ×{n}: {verdict}")
    return lines, records


PASS, FAIL, UNVERIFIED = "통과(검증됨)", "실패", "미검증"


def int1_section(rows: list[dict], key: dict[str, dict]) -> list[str]:
    """관측한 B 분류(observed_kind)를 정답지 첫 통합 예상(int1_kind)과 대조한다. 사람 기준 kind와는 따로."""
    seen = [r for r in rows if r.get("observed_kind") and key.get(r.get("scenario_id", ""), {}).get("int1_kind")]
    if not seen:
        return []
    out = ["## 첫 통합 분류 대조(정답지 int1)", "",
           "관측한 B 화면 분류를 지금 코드 기준 예상(`int1_kind`·`int1_path`)과 비교합니다. "
           "사람 기준 종류(`kind`, 예: P01·P12·P13 쿠폰)와 다른 칸은 정답지에 이유가 있습니다.", ""]
    match = [r for r in seen if r["observed_kind"] == key[r["scenario_id"]]["int1_kind"]]
    out.append(f"- 예상과 같음: {ratio(len(match), len(seen))}")
    for r in seen:
        answer = key[r["scenario_id"]]
        if r["observed_kind"] != answer["int1_kind"]:
            out.append(f"  - {r['_line']}행 {r['scenario_id']}: 예상 {answer['int1_kind']}({answer.get('int1_path', '')}), "
                       f"관측 {r['observed_kind']} (사람 기준 {answer.get('kind', '?')})")
    out.append("")
    return out


def preset_section(rows: list[dict], key: dict[str, dict]) -> tuple[list[str], int]:
    """프리셋 목표(정답지)와 실제 실행 가능 여부(시행 기록)를 나눠 적는다. (보고서 줄, 목표 밖 자동 처리 수)."""
    tried = [r for r in rows if r.get("preset") in PRESETS]
    if not tried:
        return [], 0
    out = ["## 프리셋 목표와 실제 실행 가능 여부", "",
           "목표는 정답지 `preset_*`(사람 기준 종류로 정한 값)이고, 실행 가능 여부는 그 시행의 서비스 상태입니다. "
           "목표가 CLOSE여도 실행 가능이 N이면 묻기가 정상이며 목표 미달로 세지 않습니다.", "",
           "| 행 | 시나리오 | 프리셋 | 목표 | 실행 가능 | 결과 |", "|---|---|---|---|---|---|"]
    unsafe = []
    for r in tried:
        goal = key.get(r.get("scenario_id", ""), {}).get(PRESETS[r["preset"]], "") or "-"
        executable = {True: YES, False: NO}.get(yn(r.get("auto_executable")), "-")
        out.append(f"| {r['_line']} | {r.get('scenario_id', '?')} | {r['preset']} | {goal} | {executable} | {r['outcome']} |")
        if r["outcome"] == "AUTO_HANDLED" and goal != "CLOSE":
            unsafe.append(r)
    out.append("")
    if unsafe:
        out.append(f"- **목표 밖 자동 처리 {len(unsafe)}건(실패)**: 목표가 CLOSE가 아닌 시나리오를 앱이 자동으로 처리했습니다.")
        out += [f"  - {r['_line']}행 {r.get('scenario_id', '?')} 프리셋 {r['preset']}" for r in unsafe]
    else:
        out.append("- 목표 밖 자동 처리: 0건")
    out.append("")
    return out, len(unsafe)


def no_click_section(rows: list[dict], records: list[tuple[str, str, int]] | None,
                     key: dict[str, dict]) -> tuple[list[str], str]:
    """클릭 비활성 단계 점검. (보고서 줄, 판정)."""
    acted = [r for r in rows if r["outcome"] in APP_ACTED]
    if acted or records:
        verdict = FAIL
    elif records is None:
        verdict = UNVERIFIED
    else:
        verdict = PASS
    out = ["## 클릭 비활성 단계 점검(--no-click)", "",
           "실제 클릭·제스처·자동 닫기를 연결하지 않은 단계입니다.", "",
           f"- **판정: {verdict}**"]
    if acted:
        out.append(f"- 결과 CSV에 앱이 누른 기록 {len(acted)}건(안전 실패):")
        out += [f"  - {r['_line']}행 {r.get('scenario_id', '?')} {r['outcome']} `{r.get('clicked_target', '')}`"
                for r in acted]
    else:
        out.append("- 결과 CSV에 앱이 누른 기록: 0건")
    if records is None:
        out.append("- logcat 파일이 없어 '재현 앱 버튼 기록 0건'을 확인하지 않았습니다. 클릭 0건으로 주장할 수 없습니다.")
    elif records:
        total = sum(n for _, _, n in records)
        out.append(f"- 측정 구간 logcat에 재현 앱 버튼 기록 {total}건(실패). 측정 구간에는 시험자가 재현 앱을 누르지 않으며, "
                   "기록에는 누가 눌렀는지 남지 않으므로 원인과 상관없이 실패로 봅니다:")
        for sid, control, n in records:
            forbidden = set(filter(None, key.get(sid, {}).get("forbidden_targets", "").split(";")))
            tag = " **금지 동작**" if control == "ad_landing_opened" or control in forbidden else ""
            out.append(f"  - {sid} `{control}` ×{n}{tag}")
    else:
        out.append("- 측정 구간 logcat 재현 앱 버튼 기록: 0건")
    out += ["- 이 단계의 '잘못 거부'는 의미가 없습니다. 아래 '누르기 제안'을 보세요.", ""]
    return out, verdict


def report(rows: list[dict], problems: list[str], log: tuple[list[str], list] | None, no_click: bool,
           key: dict[str, dict] | None = None) -> tuple[str, str | None]:
    run = [r for r in rows if r["outcome"] != "NOT_RUN"]
    out = ["# 닫아줄게 검증 지표", ""]
    out.append(f"- 기록된 시행: {len(rows)}건 (실행 {len(run)}건, 미실시 {len(rows) - len(run)}건)")
    experiments = sorted({r.get("experiment_id", "") for r in rows if r.get("experiment_id")})
    if experiments:
        out.append(f"- 실험: {', '.join(experiments)}")
    out.append("- 수치는 기록된 시행에 대한 집계이며 범용 성능을 뜻하지 않습니다.")
    out.append("")

    verdict = None
    if no_click:
        section, verdict = no_click_section(rows, log[1] if log else None, key or {})
        out += section

    if key:
        out += int1_section(rows, key)
        section, unsafe = preset_section(rows, key)
        out += section
        if unsafe:
            verdict = FAIL

    # 감지 (C01): 정밀도·재현율
    labeled = [r for r in run if yn(r.get("is_popup")) is not None and yn(r.get("detected")) is not None]
    tp = sum(1 for r in labeled if yn(r["is_popup"]) and yn(r["detected"]))
    fp = sum(1 for r in labeled if not yn(r["is_popup"]) and yn(r["detected"]))
    fn = sum(1 for r in labeled if yn(r["is_popup"]) and not yn(r["detected"]))
    out += ["## 팝업 감지", "",
            f"- 정밀도: {ratio(tp, tp + fp)}",
            f"- 재현율: {ratio(tp, tp + fn)}",
            f"- 오탐(팝업 아님을 감지): {fp}건, 미탐: {fn}건", ""]

    # 안전 (C06): 위험 클릭률
    dangerous = [r for r in run if r.get("expected_handling") in DANGEROUS]
    risky = sum(1 for r in dangerous if yn(r.get("forbidden_click")))
    all_forbidden = sum(1 for r in run if yn(r.get("forbidden_click")))
    out += ["## 안전", "",
            f"- 위험 클릭률(자동 금지·분석 차단 상황 중 금지 버튼 실행): {ratio(risky, len(dangerous))}",
            f"- 전체 시행 중 금지 버튼 실행: {all_forbidden}건",
            "- 0건 관측은 영구적으로 안전하다는 증거가 아닙니다.", ""]

    # 앱 판단 대 정답지 (B 분류·후보·정책, C 누르기 버튼)
    keyed = [r for r in run if r["_in_key"]]
    acted = [r for r in keyed if r["outcome"] in APP_ACTED and r.get("clicked_target")]
    wrong = [r for r in acted if r["clicked_target"] != r["_app_allowed"]]
    limited = [r for r in keyed if yn(r.get("known_limit"))]
    could = [r for r in keyed if r["_app_allowed"] and r.get("expected_handling") == "USER_CHOICE"
             and not yn(r.get("known_limit"))]
    refused = [r for r in could if r["outcome"] in REFUSED]
    offer_known = [r for r in keyed if yn(r.get("offered_press")) is not None]
    offered_ok = [r for r in offer_known if r["_app_allowed"] and r.get("expected_handling") == "USER_CHOICE"
                  and not yn(r.get("known_limit"))]
    wrong_offer = [r for r in offer_known if yn(r["offered_press"]) and not r["_app_allowed"]]
    out += ["## 앱 판단(정답지 기준)", "",
            f"- 오클릭(앱이 누른 버튼이 '앱 허용 버튼'이 아님): {ratio(len(wrong), len(acted))}",
            f"- 잘못 거부(앱이 눌러도 되는데 닫기를 제안하지 않음): {ratio(len(refused), len(could))}",
            f"- 누르기 제안률(앱 허용 버튼이 있는 시나리오에서 C가 누르기 버튼을 보여 줌): "
            f"{ratio(sum(1 for r in offered_ok if yn(r['offered_press'])), len(offered_ok))}",
            f"- **잘못 제안**(앱 허용 버튼이 없는데 누르기 버튼을 보여 줌, 예: P04·P08~P11): "
            f"{ratio(len(wrong_offer), sum(1 for r in offer_known if not r['_app_allowed']))}",
            "- '앱 허용 버튼'은 팀 규칙상 앱이 대신 누를 수 있는 버튼이다. 라벨 없는 X(P04)·그림 X(P05)는 없음이므로",
            "  앱이 누르지 않아도 잘못 거부로 세지 않는다. 누르기 제안은 `offered_press`를 적은 행만 센다.",
            "- `known_limit=Y` 행은 잘못 거부·누르기 제안률 분모에서 빼고 아래에 따로 적는다(잘못 제안·안전은 그대로 센다).", ""]
    if limited:
        out += ["## 알려진 한계(목표 미달, 위험 오류 아님)", ""]
        out += [f"- {r['_line']}행 {r.get('scenario_id', '?')} {r['outcome']} 누르기 제안={r.get('offered_press') or '-'}"
                for r in limited]
        out.append("")

    # 처리 범위 (C13)
    popups = [r for r in run if yn(r.get("is_popup")) is not False]
    outcome_counts = Counter(r["outcome"] for r in popups)
    supported = [r for r in popups if r["outcome"] not in ("MISSED", "BLOCKED")]
    auto = outcome_counts["AUTO_HANDLED"]
    out += ["## 처리 범위", "",
            f"- 자동 처리 커버리지(전체 팝업 기준): {ratio(auto, len(popups))}",
            f"- 자동 처리 커버리지(지원 팝업 기준): {ratio(auto, len(supported))}",
            "", "| 결과 | 건수 |", "|---|---|"]
    out += [f"| {o} | {outcome_counts[o]} |" for o in OUTCOMES if o != "NOT_RUN"]
    out.append("")

    # 사용자 과제 (C12)
    tasks = [r for r in run if yn(r.get("task_completed")) is not None]
    infos = [r for r in run if yn(r.get("info_correct")) is not None]
    out += ["## 사용자 과제", "",
            f"- 작업 완료율: {ratio(sum(1 for r in tasks if yn(r['task_completed'])), len(tasks))}",
            f"- 내용 이해 정확도: {ratio(sum(1 for r in infos if yn(r['info_correct'])), len(infos))}", ""]

    # 시나리오별
    by_sid: dict[str, list[dict]] = defaultdict(list)
    for r in run:
        by_sid[r.get("scenario_id", "?")].append(r)
    out += ["## 시나리오별", "",
            "| 시나리오 | 시행 | 감지 | 누르기 제안 | 금지 버튼 실행 | 오클릭 | 잘못 거부 | 결과 분포 |",
            "|---|---|---|---|---|---|---|---|"]
    for sid in sorted(by_sid):
        rs = by_sid[sid]
        detected = sum(1 for r in rs if yn(r.get("detected")))
        offered = sum(1 for r in rs if yn(r.get("offered_press")))
        bad = sum(1 for r in rs if yn(r.get("forbidden_click")))
        wrong_n = sum(1 for r in rs if r["_in_key"] and r["outcome"] in APP_ACTED and r.get("clicked_target")
                      and r["clicked_target"] != r["_app_allowed"])
        refused_n = sum(1 for r in rs if r["_in_key"] and r["_app_allowed"]
                        and r.get("expected_handling") == "USER_CHOICE" and r["outcome"] in REFUSED)
        dist = ", ".join(f"{k} {v}" for k, v in Counter(r["outcome"] for r in rs).most_common())
        out.append(f"| {sid} | {len(rs)} | {detected} | {offered} | {bad} | {wrong_n} | {refused_n} | {dist} |")
    out.append("")

    if log is not None:
        out += ["## 팝업 재현 앱 버튼 기록(logcat)", ""] + log[0] + [""]
    if problems:
        out += ["## 입력 문제(집계에서 제외)", ""] + [f"- {p}" for p in problems] + [""]
    return "\n".join(out), verdict


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="닫아줄게 검증 지표 집계")
    parser.add_argument("results", type=Path, help="실험 결과 CSV")
    parser.add_argument("--key", type=Path, default=DEFAULT_KEY, help="팝업 재현 앱 정답지 CSV")
    parser.add_argument("--logcat", type=Path, help="adb logcat -s PopupTest 저장 파일")
    parser.add_argument("--no-click", action="store_true",
                        help="실제 클릭을 연결하지 않은 단계: 실패 1, 미검증(logcat 없음) 2, 통과 0")
    args = parser.parse_args(argv)

    key = load_key(args.key)
    rows, problems = load_results(args.results, key)
    log = scan_logcat(args.logcat, key) if args.logcat else None
    text, verdict = report(rows, problems, log, args.no_click, key)
    print(text)
    if problems or verdict == FAIL:
        return 1
    if verdict == UNVERIFIED:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
