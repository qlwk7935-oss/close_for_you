#!/usr/bin/env python3
"""D: 실제 사이트·앱 시연 시행 집계 (표준 라이브러리만 사용).

최종 시연(AGENTS.md 6-0·9장)에서 재호·우진이 실제 사이트·앱으로 시험한 결과를 모아
사이트별 시험·통과·실수와 전체 지표를 Markdown으로 낸다. 재현 앱(P·N 시나리오)은 tools/qa_metrics.py를 쓴다.

  python3 tools/real_site_metrics.py trials.csv
  python3 tools/real_site_metrics.py trials.csv --rehearsal-only --min-trials 3

입력
  trials.csv  시행 한 번당 한 줄. 양식: docs/templates/real_site_trials.csv, 작성법: docs/REAL_SITE_QA.md
  --key       정답지(기본 docs/templates/real_site_answer_key.csv)

종료 코드
  0  금지 버튼·오클릭 0건, 입력 문제 없음
  1  금지 버튼 또는 오클릭이 1건이라도 있음, 또는 입력 문제(알 수 없는 사이트·값, notes에 원문으로 보이는 글자)

개인정보: 기록에는 사이트 ID·코드·Y/N·숫자만 적는다. notes와 clicked_target에 한글·화면 원문을 쓰면 입력 문제로 본다.
수치는 기록된 시행에 대한 집계일 뿐 범용 성능을 뜻하지 않는다.
"""
from __future__ import annotations

import argparse
import csv
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_KEY = REPO / "docs" / "templates" / "real_site_answer_key.csv"

MODES = {"ASK", "PRESET", "ALWAYS", "AUTO_SKIP"}
PRESETS = {"", "OFF", "CLOSE_ALL", "CLOSE_ADS_AND_COUPONS", "CLOSE_ADS_ONLY"}
OUTCOMES = {"CLOSED_BY_APP", "USER_CONFIRMED", "ASKED_ONLY", "WARNED", "MISSED", "NOT_RUN"}
HANDLINGS = {"CLOSE_OK", "ASK", "NO_PRESS", "WARN_NO_PRESS", "NO_POPUP"}
APP_PRESSED = {"CLOSED_BY_APP", "USER_CONFIRMED"}
CODE = re.compile(r"^[A-Za-z0-9_;:.\- ]*$")
NOTES_MAX = 80


def yn(value: str | None) -> bool | None:
    v = (value or "").strip().upper()
    if v == "Y":
        return True
    if v == "N":
        return False
    return None


def ratio(num: int, den: int) -> str:
    return "N/A (0건)" if den == 0 else f"{num / den:.1%} ({num}/{den})"


def load_key(path: Path) -> dict[str, dict]:
    with path.open(encoding="utf-8") as f:
        return {row["site_id"]: row for row in csv.DictReader(f)}


def targets(value: str | None) -> set[str]:
    return {t.strip().upper() for t in (value or "").split(";") if t.strip()}


def check_row(n: int, row: dict, key: dict[str, dict]) -> list[str]:
    errors = []
    where = f"{n}행({row.get('trial_id', '')})"
    site = row.get("site_id", "")
    if site not in key:
        errors.append(f"{where}: 정답지에 없는 site_id '{site}'")
    if row.get("mode", "") not in MODES:
        errors.append(f"{where}: mode는 {sorted(MODES)} 중 하나")
    if row.get("preset", "") not in PRESETS:
        errors.append(f"{where}: preset 값 오류")
    if row.get("outcome", "") not in OUTCOMES:
        errors.append(f"{where}: outcome은 {sorted(OUTCOMES)} 중 하나")
    for col in ("popup_appeared", "forbidden_click", "misclick"):
        if yn(row.get(col)) is None:
            errors.append(f"{where}: {col}는 Y/N")
    for col in ("notes", "clicked_target"):
        value = row.get(col, "") or ""
        if not CODE.match(value) or len(value) > NOTES_MAX:
            errors.append(f"{where}: {col}에는 코드만 적습니다(영문·숫자·_;: 최대 {NOTES_MAX}자, 화면 원문 금지)")
    seconds = (row.get("seconds_to_close") or "").strip()
    if seconds:
        try:
            if float(seconds) < 0:
                raise ValueError
        except ValueError:
            errors.append(f"{where}: seconds_to_close는 0 이상의 숫자")
    if site in key:
        clicked = targets(row.get("clicked_target"))
        forbidden = targets(key[site].get("forbidden_targets"))
        if clicked & forbidden and yn(row.get("forbidden_click")) is False:
            errors.append(f"{where}: clicked_target이 정답지 금지 대상인데 forbidden_click=N")
        if row.get("outcome") in APP_PRESSED and clicked <= {"NONE"}:
            errors.append(f"{where}: 앱이 누른 결과인데 clicked_target이 비어 있음")
        if row.get("outcome") == "CLOSED_BY_APP" and row.get("mode") == "ASK":
            errors.append(f"{where}: mode=ASK인데 CLOSED_BY_APP")
    return errors


def judge(row: dict, key_row: dict) -> tuple[str, str]:
    """(PASS|FAIL|SKIP, 사유 코드)."""
    if yn(row.get("popup_appeared")) is False or row.get("outcome") == "NOT_RUN":
        return "SKIP", "NO_POPUP_OR_NOT_RUN"
    if yn(row.get("forbidden_click")):
        return "FAIL", "FORBIDDEN_CLICK"
    if yn(row.get("misclick")):
        return "FAIL", "MISCLICK"
    if yn(row.get("info_correct")) is False:
        return "FAIL", "INFO_WRONG"
    outcome = row.get("outcome")
    closed = yn(row.get("closed_verified")) is True
    clicked = targets(row.get("clicked_target")) - {"NONE"}
    expected = key_row.get("expected_handling", "")
    if outcome == "MISSED":
        return "FAIL", "MISSED"
    if expected == "CLOSE_OK":
        if outcome in APP_PRESSED and closed:
            return "PASS", "CLOSED"
        if outcome in APP_PRESSED:
            return "FAIL", "CLOSE_NOT_VERIFIED"
        return "FAIL", "NOT_CLOSED"
    if expected == "ASK":
        if outcome == "CLOSED_BY_APP":
            return "FAIL", "AUTO_OUTSIDE_GOAL"
        if outcome == "USER_CONFIRMED" and not closed:
            return "FAIL", "CLOSE_NOT_VERIFIED"
        return "PASS", "ASKED"
    if expected == "NO_PRESS":
        return ("FAIL", "PRESSED") if clicked or outcome in APP_PRESSED else ("PASS", "NOT_PRESSED")
    if expected == "WARN_NO_PRESS":
        if clicked or outcome in APP_PRESSED:
            return "FAIL", "PRESSED"
        return ("PASS", "WARNED") if outcome == "WARNED" else ("FAIL", "NOT_WARNED")
    return "SKIP", "NO_POPUP_EXPECTED"


def report(rows: list[dict], key: dict[str, dict], min_trials: int) -> tuple[str, bool]:
    lines = ["# 실제 사이트·앱 시행 집계", "",
             "기록된 시행만 집계했습니다. 범용 성능이 아닙니다.", ""]
    counted = [(r, *judge(r, key[r["site_id"]])) for r in rows]
    run = [(r, v, why) for r, v, why in counted if v != "SKIP"]
    appeared = [r for r in rows if yn(r.get("popup_appeared"))]
    detected = [r for r in appeared if yn(r.get("detected"))]
    close_ok = [(r, v) for r, v, _ in run if key[r["site_id"]]["expected_handling"] == "CLOSE_OK"]
    forbidden = sum(1 for r in rows if yn(r.get("forbidden_click")))
    misclick = sum(1 for r in rows if yn(r.get("misclick")))
    info = [r for r, _, _ in run if yn(r.get("info_correct")) is not None]
    secs = [float(r["seconds_to_close"]) for r, v, _ in run
            if v == "PASS" and (r.get("seconds_to_close") or "").strip()]
    kind_rows = [r for r, _, _ in run if r.get("observed_kind")]
    kind_ok = sum(1 for r in kind_rows if r["observed_kind"] == key[r["site_id"]].get("expected_kind"))

    lines += ["## 전체", "", "| 지표 | 값 |", "|---|---|",
              f"| 시행(전체 줄) | {len(rows)} |",
              f"| 팝업이 뜬 시행 | {len(appeared)} |",
              f"| 감지율 | {ratio(len(detected), len(appeared))} |",
              f"| 정답지 기대 분류와 일치 | {ratio(kind_ok, len(kind_rows))} |",
              f"| 닫힘 성공률(CLOSE_OK 사이트) | {ratio(sum(1 for _, v in close_ok if v == 'PASS'), len(close_ok))} |",
              f"| 시행 통과율 | {ratio(sum(1 for _, v, _ in run if v == 'PASS'), len(run))} |",
              f"| 금지 버튼 클릭 | {forbidden}건 (0이어야 함) |",
              f"| 오클릭 | {misclick}건 (0이어야 함) |",
              f"| 안내 정확도 | {ratio(sum(1 for r in info if yn(r['info_correct'])), len(info))} |",
              f"| 닫힘까지 걸린 시간 중앙값(통과 시행) | {f'{statistics.median(secs):.1f}초' if secs else 'N/A'} |",
              ""]

    by_site: dict[str, list] = defaultdict(list)
    for r, v, why in counted:
        by_site[r["site_id"]].append((v, why))
    lines += ["## 사이트별 (시험·통과·실수)", "",
              "| 사이트 | 이름 | 기대 처리 | 시험 | 통과 | 실수 | 실패 사유 | 시연 후보 |",
              "|---|---|---|---|---|---|---|---|"]
    ready = []
    for site in sorted(by_site):
        results = by_site[site]
        tried = [x for x in results if x[0] != "SKIP"]
        passed = sum(1 for v, _ in tried if v == "PASS")
        failed = len(tried) - passed
        reasons = sorted({why for v, why in tried if v == "FAIL"})
        ok = len(tried) >= min_trials and failed == 0
        if ok:
            ready.append(site)
        k = key[site]
        lines.append(f"| {site} | {k['name']} | {k['expected_handling']} | {len(tried)} | {passed} | {failed} | "
                     f"{', '.join(reasons) or '-'} | {'Y' if ok else 'N'} |")
    lines += ["", f"시연 후보 기준: 시험 {min_trials}회 이상, 실수 0회. 해당: {', '.join(ready) or '없음'}", ""]

    safe = forbidden == 0 and misclick == 0
    lines.append("**안전 판정: 통과** (금지 버튼·오클릭 0건)" if safe
                 else "**안전 판정: 실패** (금지 버튼 또는 오클릭 있음)")
    return "\n".join(lines) + "\n", safe


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("trials", type=Path)
    parser.add_argument("--key", type=Path, default=DEFAULT_KEY)
    parser.add_argument("--rehearsal-only", action="store_true", help="rehearsal=Y 행만 집계")
    parser.add_argument("--min-trials", type=int, default=3, help="시연 후보로 볼 최소 시험 횟수(기본 3)")
    args = parser.parse_args(argv)

    key = load_key(args.key)
    bad_key = [s for s, k in key.items() if k.get("expected_handling") not in HANDLINGS]
    if bad_key:
        print(f"입력 문제: 정답지 expected_handling 오류 {bad_key}", file=sys.stderr)
        return 1
    with args.trials.open(encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    errors = [e for n, row in enumerate(rows, start=2) for e in check_row(n, row, key)]
    if errors:
        print("입력 문제:", *errors, sep="\n  ", file=sys.stderr)
        return 1
    if args.rehearsal_only:
        rows = [r for r in rows if yn(r.get("rehearsal"))]
    text, safe = report(rows, key, args.min_trials)
    sys.stdout.write(text)
    return 0 if safe else 1


if __name__ == "__main__":
    sys.exit(main())
