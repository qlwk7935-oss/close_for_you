#!/usr/bin/env python3
"""D: 닫아줄게 검증 지표 집계 (표준 라이브러리만 사용).

입력
  results.csv   실험 시행 기록. 양식: docs/templates/experiment_results.csv
  --key         정답지(기본 docs/templates/popup_answer_key.csv). 팝업 재현 앱 시나리오면
                기대 처리·금지 버튼을 정답지에서 자동으로 채운다.
  --logcat      `adb logcat -s PopupTest` 저장 파일(선택). 눌린 버튼을 정답지와 대조한다.

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

OUTCOMES = ("AUTO_HANDLED", "USER_CONFIRMED", "ANNOUNCED_ONLY", "MISSED", "BLOCKED", "NOT_RUN")
EXPECTED = ("USER_CHOICE", "AUTO_FORBIDDEN", "BLOCK_ANALYSIS", "NO_ACTION")
DANGEROUS = {"AUTO_FORBIDDEN", "BLOCK_ANALYSIS"}
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
            rows.append(row)
    return rows, problems


def scan_logcat(path: Path, key: dict[str, dict]) -> list[str]:
    lines = []
    counts: Counter = Counter()
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = LOG_LINE.search(raw)
        if m:
            counts[(m.group(1), m.group(2))] += 1
    if not counts:
        return ["- PopupTest 기록이 없습니다."]
    for (sid, control), n in sorted(counts.items()):
        answer = key.get(sid)
        forbidden = set(filter(None, (answer or {}).get("forbidden_targets", "").split(";")))
        correct = (answer or {}).get("correct_target", "")
        if control == "ad_landing_opened" or control in forbidden:
            verdict = "**금지 동작**"
        elif correct and control == correct:
            verdict = "정답 버튼"
        elif answer is None:
            verdict = "정답지에 없는 시나리오"
        else:
            verdict = "기타"
        lines.append(f"- {sid} `{control}` ×{n}: {verdict}")
    return lines


def report(rows: list[dict], problems: list[str], log_lines: list[str] | None) -> str:
    run = [r for r in rows if r["outcome"] != "NOT_RUN"]
    out = ["# 닫아줄게 검증 지표", ""]
    out.append(f"- 기록된 시행: {len(rows)}건 (실행 {len(run)}건, 미실시 {len(rows) - len(run)}건)")
    experiments = sorted({r.get("experiment_id", "") for r in rows if r.get("experiment_id")})
    if experiments:
        out.append(f"- 실험: {', '.join(experiments)}")
    out.append("- 수치는 기록된 시행에 대한 집계이며 범용 성능을 뜻하지 않습니다.")
    out.append("")

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
    out += ["## 시나리오별", "", "| 시나리오 | 시행 | 감지 | 금지 버튼 실행 | 결과 분포 |", "|---|---|---|---|---|"]
    for sid in sorted(by_sid):
        rs = by_sid[sid]
        detected = sum(1 for r in rs if yn(r.get("detected")))
        bad = sum(1 for r in rs if yn(r.get("forbidden_click")))
        dist = ", ".join(f"{k} {v}" for k, v in Counter(r["outcome"] for r in rs).most_common())
        out.append(f"| {sid} | {len(rs)} | {detected} | {bad} | {dist} |")
    out.append("")

    if log_lines is not None:
        out += ["## 팝업 재현 앱 버튼 기록(logcat)", ""] + log_lines + [""]
    if problems:
        out += ["## 입력 문제(집계에서 제외)", ""] + [f"- {p}" for p in problems] + [""]
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="닫아줄게 검증 지표 집계")
    parser.add_argument("results", type=Path, help="실험 결과 CSV")
    parser.add_argument("--key", type=Path, default=DEFAULT_KEY, help="팝업 재현 앱 정답지 CSV")
    parser.add_argument("--logcat", type=Path, help="adb logcat -s PopupTest 저장 파일")
    args = parser.parse_args(argv)

    key = load_key(args.key)
    rows, problems = load_results(args.results, key)
    log_lines = scan_logcat(args.logcat, key) if args.logcat else None
    print(report(rows, problems, log_lines))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
