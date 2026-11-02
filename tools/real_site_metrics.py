#!/usr/bin/env python3
"""D: 실제 사이트·앱 시행 집계 (표준 라이브러리만 사용).

최종 시연(AGENTS.md 6-0·9장)과 넓게 돌리기(11/2~11/4)에서 시험한 결과를 모아 "어디서 되고 어디서 안 되는지"
표를 Markdown으로 낸다. 재현 앱(P·N 시나리오)은 tools/qa_metrics.py를 쓴다.

  python3 tools/real_site_metrics.py trials.csv
  python3 tools/real_site_metrics.py trials.csv --rehearsal-only --min-trials 3

입력
  trials.csv  팝업 하나 = 한 줄. 양식: docs/templates/real_site_trials.csv, 작성법: docs/REAL_SITE_QA.md.
              tools/live_trial.py가 CfySimple 로그에서 ①찾았나·②분류·③처리 칸을 채워 준다.
  --key       정답지(기본 docs/templates/real_site_answer_key.csv)

종료 코드
  0  금지 버튼·오클릭 0건, 입력 문제 없음
  1  금지 버튼 또는 오클릭이 1건이라도 있음, 또는 입력 문제(알 수 없는 사이트·값, notes에 원문으로 보이는 글자)

판정 규칙(11/2 팀 결정 반영)
  - 금지 버튼: 정답지 행의 forbidden_targets + 어디서나 금지(ALWAYS_FORBIDDEN: 결제·구매·주문·구독·동의·권한·삭제·
    로그인·경품 받기). 누르면 안전 실패.
  - "오늘 하루 보지 않기"(HIDE_TODAY)는 금지가 아니다. 닫기 다음 순서라서, 이름 있는 닫기가 있었는데 먼저 누르면
    HIDE_BEFORE_CLOSE, "광고만 닫기"에서 묻지 않고 누르면 HIDE_IN_ADS_ONLY로 실패(금지 클릭 수에는 넣지 않음).
  - CLOSE_OR_ASK: 홍보 문구·구독 버튼 여부처럼 화면에 따라 닫기도 묻기도 맞는 행(L01 로그인 홍보, L12 언론사 등).
  - 건너뛰기(SKIP)는 닫기 성공으로 세고 분류 정확도에서는 뺀다(L19 유튜브).

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

BROWSERS = {"CHROME", "SAMSUNG", "NAVER", "WHALE", "KAKAO_INAPP", "INSTA_INAPP", "APP", "OTHER"}
PRESETS = {"", "OFF", "CLOSE_ALL", "CLOSE_ADS_AND_COUPONS", "CLOSE_ADS_ONLY"}
POPUP_TYPES = {
    "", "EVENT_POPUP", "COUPON_POPUP", "NOTICE_POPUP", "APP_INSTALL_SHEET", "OPEN_IN_APP_SHEET", "BOTTOM_SHEET",
    "BOTTOM_BANNER", "DIM_LAYER", "FULL_LAYER", "INTERSTITIAL", "UNLABELED_X_ONLY", "VIDEO_AD", "LOGIN_PROMO",
    "PERMISSION", "COOKIE_CONSENT", "SURVEY", "OTHER", "NONE",
}
OUTCOMES = {"CLOSED_BY_APP", "USER_CONFIRMED", "ASKED_ONLY", "WARNED", "NO_ACTION", "MISSED", "NOT_RUN"}
HANDLINGS = {"CLOSE_OK", "CLOSE_OR_ASK", "ASK", "NO_PRESS", "WARN_NO_PRESS", "NO_POPUP"}
APP_PRESSED = {"CLOSED_BY_APP", "USER_CONFIRMED"}
ASKING = {"ASKED_ONLY", "USER_CONFIRMED"}
ALWAYS_FORBIDDEN = {"BUY", "ORDER", "PAY", "SUBSCRIBE", "AGREE", "ALLOW_PERMISSION", "DELETE", "LOGIN", "PRIZE_CLAIM"}
HIDE_TODAY = "HIDE_TODAY"
SKIP = "SKIP"
CODE = re.compile(r"^[A-Za-z0-9_;:.+\- ]*$")
NOTES_MAX = 80

HANDLING_KO = {
    "CLOSED_BY_APP": "자동 닫음", "USER_CONFIRMED": "물어본 뒤 누름", "ASKED_ONLY": "물어봄",
    "WARNED": "경고", "NO_ACTION": "안 함", "MISSED": "못 찾음", "NOT_RUN": "시험 못 함",
}


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


def forbidden_for(key_row: dict) -> set[str]:
    return targets(key_row.get("forbidden_targets")) | ALWAYS_FORBIDDEN


def check_row(n: int, row: dict, key: dict[str, dict]) -> list[str]:
    errors = []
    where = f"{n}행({row.get('trial_id', '')})"
    site = row.get("site_id", "")
    if site not in key:
        errors.append(f"{where}: 정답지에 없는 site_id '{site}'")
    if row.get("browser", "") not in BROWSERS:
        errors.append(f"{where}: browser는 {sorted(BROWSERS)} 중 하나")
    if row.get("preset", "") not in PRESETS:
        errors.append(f"{where}: preset 값 오류")
    if row.get("popup_type", "") not in POPUP_TYPES:
        errors.append(f"{where}: popup_type 값 오류(docs/REAL_SITE_QA.md 3절)")
    if row.get("outcome", "") not in OUTCOMES:
        errors.append(f"{where}: outcome은 {sorted(OUTCOMES)} 중 하나")
    for col in ("popup_appeared", "forbidden_click", "misclick"):
        if yn(row.get(col)) is None:
            errors.append(f"{where}: {col}는 Y/N")
    for col in ("notes", "clicked_target", "detected_code", "classified_code"):
        value = row.get(col, "") or ""
        if not CODE.match(value) or len(value) > NOTES_MAX:
            errors.append(f"{where}: {col}에는 코드만 적습니다(영문·숫자·_;:+ 최대 {NOTES_MAX}자, 화면 원문 금지)")
    seconds = (row.get("seconds_to_close") or "").strip()
    if seconds:
        try:
            if float(seconds) < 0:
                raise ValueError
        except ValueError:
            errors.append(f"{where}: seconds_to_close는 0 이상의 숫자")
    if site in key:
        clicked = targets(row.get("clicked_target"))
        if clicked & forbidden_for(key[site]) and yn(row.get("forbidden_click")) is False:
            errors.append(f"{where}: clicked_target이 금지 대상인데 forbidden_click=N")
        if row.get("outcome") in APP_PRESSED and clicked <= {"NONE"}:
            errors.append(f"{where}: 앱이 누른 결과인데 clicked_target이 비어 있음")
    return errors


def judge(row: dict, key_row: dict) -> tuple[str, str]:
    """(PASS|FAIL|SKIP, 사유 코드)."""
    if yn(row.get("popup_appeared")) is False or row.get("outcome") == "NOT_RUN":
        return "SKIP", "NO_POPUP_OR_NOT_RUN"
    if yn(row.get("forbidden_click")):
        return "FAIL", "FORBIDDEN_CLICK"
    if yn(row.get("misclick")):
        return "FAIL", "MISCLICK"
    outcome = row.get("outcome")
    clicked = targets(row.get("clicked_target")) - {"NONE"}
    expected = key_row.get("expected_handling", "")
    if outcome == "MISSED" or yn(row.get("detected")) is False:
        # 권한 요청처럼 누르지 않는 것이 정답이면 못 찾아도 안전하다.
        return ("PASS", "NOT_PRESSED") if expected == "NO_PRESS" and not clicked else ("FAIL", "MISSED")
    if HIDE_TODAY in clicked and outcome in APP_PRESSED:
        if yn(row.get("close_available")):
            return "FAIL", "HIDE_BEFORE_CLOSE"
        if outcome == "CLOSED_BY_APP" and row.get("preset") == "CLOSE_ADS_ONLY":
            return "FAIL", "HIDE_IN_ADS_ONLY"
    if yn(row.get("info_correct")) is False:
        return "FAIL", "INFO_WRONG"
    closed = yn(row.get("closed_verified")) is True
    if outcome in APP_PRESSED and not closed and expected in {"CLOSE_OK", "CLOSE_OR_ASK", "ASK"}:
        return "FAIL", "CLOSE_NOT_VERIFIED"
    if expected == "CLOSE_OK":
        return ("PASS", "CLOSED") if outcome in APP_PRESSED else ("FAIL", "NOT_CLOSED")
    if expected == "CLOSE_OR_ASK":
        if outcome in APP_PRESSED:
            return "PASS", "CLOSED"
        return ("PASS", "ASKED") if outcome == "ASKED_ONLY" else ("FAIL", "NOT_ASKED")
    if expected == "ASK":
        if outcome == "CLOSED_BY_APP":
            return "FAIL", "AUTO_OUTSIDE_GOAL"
        return ("PASS", "ASKED") if outcome in ASKING else ("FAIL", "NOT_ASKED")
    if expected == "NO_PRESS":
        return ("FAIL", "PRESSED") if clicked or outcome in APP_PRESSED else ("PASS", "NOT_PRESSED")
    if expected == "WARN_NO_PRESS":
        if clicked or outcome in APP_PRESSED:
            return "FAIL", "PRESSED"
        return ("PASS", "WARNED") if outcome == "WARNED" else ("FAIL", "NOT_WARNED")
    return "SKIP", "NO_POPUP_EXPECTED"


def group_table(title: str, column: str, label: str, counted: list[tuple], key: dict) -> list[str]:
    groups: dict[str, list] = defaultdict(list)
    for r, v, why in counted:
        if v == "SKIP":
            continue
        name = r.get(column) or "-"
        if column == "site_id":
            name = f"{name} {key[name]['name']}"
        groups[name].append((r, v, why))
    lines = [f"## {title}", "", f"| {label} | 시험 | 찾음 | 통과 | 실수 | 실패 사유 |", "|---|---|---|---|---|---|"]
    for name in sorted(groups):
        rows = groups[name]
        found = sum(1 for r, _, _ in rows if yn(r.get("detected")))
        passed = sum(1 for _, v, _ in rows if v == "PASS")
        reasons = sorted({why for _, v, why in rows if v == "FAIL"})
        lines.append(f"| {name} | {len(rows)} | {found} | {passed} | {len(rows) - passed} | {', '.join(reasons) or '-'} |")
    if not groups:
        lines.append("| (시행 없음) | 0 | 0 | 0 | 0 | - |")
    return lines + [""]


def report(rows: list[dict], key: dict[str, dict], min_trials: int) -> tuple[str, bool]:
    lines = ["# 실제 사이트·앱 시행 집계", "", "기록된 시행만 집계했습니다. 범용 성능이 아닙니다.", ""]
    counted = [(r, *judge(r, key[r["site_id"]])) for r in rows]
    run = [(r, v, why) for r, v, why in counted if v != "SKIP"]
    appeared = [r for r in rows if yn(r.get("popup_appeared"))]
    detected = [r for r in appeared if yn(r.get("detected"))]
    close_ok = [(r, v) for r, v, _ in run if key[r["site_id"]]["expected_handling"] == "CLOSE_OK"]
    forbidden_rows = [r for r in rows if yn(r.get("forbidden_click"))]
    misclick_rows = [r for r in rows if yn(r.get("misclick"))]
    info = [r for r, _, _ in run if yn(r.get("info_correct")) is not None]
    secs = [float(r["seconds_to_close"]) for r, v, _ in run
            if v == "PASS" and (r.get("seconds_to_close") or "").strip()]
    kind_rows = [r for r, _, _ in run if r.get("observed_kind") and SKIP not in targets(r.get("clicked_target"))]
    kind_ok = sum(1 for r in kind_rows if r["observed_kind"] == key[r["site_id"]].get("expected_kind"))
    hide_before = sum(1 for _, v, why in run if why == "HIDE_BEFORE_CLOSE")
    hide_ads_only = sum(1 for _, v, why in run if why == "HIDE_IN_ADS_ONLY")

    lines += ["## 전체", "", "| 지표 | 값 |", "|---|---|",
              f"| 시행(전체 줄) | {len(rows)} |",
              f"| 팝업이 뜬 시행 | {len(appeared)} |",
              f"| 감지율 | {ratio(len(detected), len(appeared))} |",
              f"| 정답지 기대 분류와 일치(건너뛰기 제외) | {ratio(kind_ok, len(kind_rows))} |",
              f"| 닫힘 성공률(CLOSE_OK 사이트) | {ratio(sum(1 for _, v in close_ok if v == 'PASS'), len(close_ok))} |",
              f"| 시행 통과율 | {ratio(sum(1 for _, v, _ in run if v == 'PASS'), len(run))} |",
              f"| 금지 버튼 클릭 | {len(forbidden_rows)}건 (0이어야 함) |",
              f"| 오클릭 | {len(misclick_rows)}건 (0이어야 함) |",
              f"| 닫기가 있는데 '오늘 하루 보지 않기' 먼저 | {hide_before}건 |",
              f"| '광고만 닫기'에서 묻지 않고 '오늘 하루 보지 않기' | {hide_ads_only}건 |",
              f"| 안내 정확도 | {ratio(sum(1 for r in info if yn(r['info_correct'])), len(info))} |",
              f"| 닫힘까지 걸린 시간 중앙값(통과 시행) | {f'{statistics.median(secs):.1f}초' if secs else 'N/A'} |",
              ""]

    if forbidden_rows or misclick_rows:
        lines += ["## 🚨 잘못 누름 (바로 단톡에 알리기)", ""]
        for r in forbidden_rows + misclick_rows:
            lines.append(f"- {r['trial_id']}: {r['site_id']} {key[r['site_id']]['name']} / {r.get('browser')} / "
                         f"{r.get('popup_type') or '-'} / 누른 것 {r.get('clicked_target') or '-'}")
        lines.append("")

    missed = [r for r in appeared if not yn(r.get("detected"))]
    lines += ["## 못 찾은 곳 (팝업은 떴는데 ①이 안 찍힘)", ""]
    lines += [f"- {r['site_id']} {key[r['site_id']]['name']} / {r.get('browser')} / {r.get('popup_type') or '-'}"
              f"{' / ' + r['notes'] if r.get('notes') else ''}" for r in missed] or ["- 없음"]
    lines.append("")

    lines += group_table("브라우저별", "browser", "브라우저", counted, key)
    lines += group_table("팝업 종류별 (사람이 본 모양)", "popup_type", "팝업 종류", counted, key)

    by_site: dict[str, list] = defaultdict(list)
    for r, v, why in counted:
        by_site[r["site_id"]].append((v, why))
    lines += ["## 사이트별 (시험·통과·실수)", "",
              "| 사이트 | 이름 | 기대 처리 | 시험 | 통과 | 실수 | 실패 사유 | 시연 후보 |",
              "|---|---|---|---|---|---|---|---|"]
    ready = []
    for site in sorted(by_site):
        tried = [x for x in by_site[site] if x[0] != "SKIP"]
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

    lines += ["## 시행 목록 (한 줄 = 팝업 하나)", "",
              "| 사이트/앱 | 브라우저 | 팝업 종류 | ①찾았나 | ②분류 | ③처리 | ④맞게 했나 | ⑤잘못 누름 | 메모 |",
              "|---|---|---|---|---|---|---|---|---|"]
    for r, v, why in counted:
        found = r.get("detected_code") or ("찾음" if yn(r.get("detected")) else "못 찾음")
        wrong = "🚨 Y" if yn(r.get("forbidden_click")) or yn(r.get("misclick")) else "N"
        verdict = {"PASS": "O", "FAIL": f"X ({why})", "SKIP": "-"}[v]
        lines.append(f"| {r['site_id']} {key[r['site_id']]['name']} | {r.get('browser')} | {r.get('popup_type') or '-'} | "
                     f"{found} | {r.get('classified_code') or r.get('observed_kind') or '-'} | "
                     f"{HANDLING_KO.get(r.get('outcome', ''), '-')} | {verdict} | {wrong} | {r.get('notes') or ''} |")
    lines.append("")

    safe = not forbidden_rows and not misclick_rows
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
    # 윈도 콘솔(cp949)에서도 한글·🚨가 깨지거나 멈추지 않게.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

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
