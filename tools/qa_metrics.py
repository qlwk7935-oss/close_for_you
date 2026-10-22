#!/usr/bin/env python3
"""D: 닫아줄게 검증 지표 집계 (표준 라이브러리만 사용).

입력
  results.csv   실험 시행 기록. 양식: docs/templates/experiment_results.csv
  --key         정답지(기본 docs/templates/popup_answer_key.csv). 팝업 재현 앱 시나리오면
                기대 처리·금지 버튼을 정답지에서 자동으로 채운다.
  --logcat      `adb logcat -s PopupTest` 저장 파일(선택). 눌린 버튼을 정답지와 대조한다.
  --no-click    실제 클릭을 연결하지 않은 단계(첫 통합 실측). 앱이 무언가를 누른 기록이 있으면
                안전 실패로 보고하고 종료 코드 1을 돌려준다.

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
            if row.get("offered_press") and yn(row["offered_press"]) is None:
                problems.append(f"{line_no}행: offered_press '{row['offered_press']}'은(는) Y/N이어야 합니다")
            rows.append(row)
    return rows, problems


def scan_logcat(path: Path, key: dict[str, dict]) -> tuple[list[str], int]:
    """(보고서 줄, 정답지 시나리오의 버튼 기록 수)."""
    lines = []
    counts: Counter = Counter()
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = LOG_LINE.search(raw)
        if m:
            counts[(m.group(1), m.group(2))] += 1
    if not counts:
        return ["- PopupTest 기록이 없습니다."], 0
    presses = 0
    for (sid, control), n in sorted(counts.items()):
        answer = key.get(sid)
        if answer is not None:
            presses += n
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
    return lines, presses


def no_click_section(rows: list[dict], log_presses: int | None) -> tuple[list[str], int]:
    """클릭 비활성 단계 점검. (보고서 줄, 안전 실패 수)."""
    acted = [r for r in rows if r["outcome"] in APP_ACTED]
    out = ["## 클릭 비활성 단계 점검(--no-click)", "",
           "실제 클릭·제스처·자동 닫기를 연결하지 않은 단계입니다. 앱이 무언가를 누른 기록은 모두 안전 실패입니다.", ""]
    if acted:
        out.append(f"- **안전 실패: 앱이 누른 기록 {len(acted)}건**")
        out += [f"  - {r['_line']}행 {r.get('scenario_id', '?')} {r['outcome']} `{r.get('clicked_target', '')}`"
                for r in acted]
    else:
        out.append("- 앱이 누른 기록: 0건")
    if log_presses is not None:
        out.append(f"- logcat 버튼 기록(정답지 시나리오): {log_presses}건. 시험자가 직접 누른 것만 있어야 하며, "
                   "그렇지 않은 줄이 하나라도 있으면 안전 실패입니다(기록에 누가 눌렀는지는 남지 않음).")
    out += ["- 이 단계의 '잘못 거부'는 의미가 없습니다. 아래 '누르기 제안'을 보세요.", ""]
    return out, len(acted)


def report(rows: list[dict], problems: list[str], log: tuple[list[str], int] | None, no_click: bool) -> tuple[str, int]:
    run = [r for r in rows if r["outcome"] != "NOT_RUN"]
    out = ["# 닫아줄게 검증 지표", ""]
    out.append(f"- 기록된 시행: {len(rows)}건 (실행 {len(run)}건, 미실시 {len(rows) - len(run)}건)")
    experiments = sorted({r.get("experiment_id", "") for r in rows if r.get("experiment_id")})
    if experiments:
        out.append(f"- 실험: {', '.join(experiments)}")
    out.append("- 수치는 기록된 시행에 대한 집계이며 범용 성능을 뜻하지 않습니다.")
    out.append("")

    safety_failures = 0
    if no_click:
        section, safety_failures = no_click_section(rows, log[1] if log else None)
        out += section

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
    could = [r for r in keyed if r["_app_allowed"] and r.get("expected_handling") == "USER_CHOICE"]
    refused = [r for r in could if r["outcome"] in REFUSED]
    offer_known = [r for r in keyed if yn(r.get("offered_press")) is not None]
    offered_ok = [r for r in offer_known if r["_app_allowed"] and r.get("expected_handling") == "USER_CHOICE"]
    wrong_offer = [r for r in offer_known if yn(r["offered_press"]) and not r["_app_allowed"]]
    out += ["## 앱 판단(정답지 기준)", "",
            f"- 오클릭(앱이 누른 버튼이 '앱 허용 버튼'이 아님): {ratio(len(wrong), len(acted))}",
            f"- 잘못 거부(앱이 눌러도 되는데 닫기를 제안하지 않음): {ratio(len(refused), len(could))}",
            f"- 누르기 제안률(앱 허용 버튼이 있는 시나리오에서 C가 누르기 버튼을 보여 줌): "
            f"{ratio(sum(1 for r in offered_ok if yn(r['offered_press'])), len(offered_ok))}",
            f"- **잘못 제안**(앱 허용 버튼이 없는데 누르기 버튼을 보여 줌, 예: P04·P08~P11): "
            f"{ratio(len(wrong_offer), sum(1 for r in offer_known if not r['_app_allowed']))}",
            "- '앱 허용 버튼'은 팀 규칙상 앱이 대신 누를 수 있는 버튼이다. 라벨 없는 X(P04)·그림 X(P05)는 없음이므로",
            "  앱이 누르지 않아도 잘못 거부로 세지 않는다. 누르기 제안은 `offered_press`를 적은 행만 센다.", ""]

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
    return "\n".join(out), safety_failures


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="닫아줄게 검증 지표 집계")
    parser.add_argument("results", type=Path, help="실험 결과 CSV")
    parser.add_argument("--key", type=Path, default=DEFAULT_KEY, help="팝업 재현 앱 정답지 CSV")
    parser.add_argument("--logcat", type=Path, help="adb logcat -s PopupTest 저장 파일")
    parser.add_argument("--no-click", action="store_true",
                        help="실제 클릭을 연결하지 않은 단계: 앱이 누른 기록이 있으면 안전 실패")
    args = parser.parse_args(argv)

    key = load_key(args.key)
    rows, problems = load_results(args.results, key)
    log = scan_logcat(args.logcat, key) if args.logcat else None
    text, safety_failures = report(rows, problems, log, args.no_click)
    print(text)
    return 1 if problems or safety_failures else 0


if __name__ == "__main__":
    sys.exit(main())
