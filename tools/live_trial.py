#!/usr/bin/env python3
"""D: 실제 사이트·앱 한 번 시험 → CfySimple 로그를 팝업별로 나눠 시행 기록(real_site_trials.csv)에 붙인다.

표준 라이브러리만 쓴다. 윈도·맥·리눅스 어디서나 `python tools/live_trial.py ...`로 실행한다(adb는 PATH에).

  # 1) 시험: 로그를 지우고 받기 시작 → 휴대폰에서 사이트·앱을 열고 팝업 처리가 끝나면 Enter → 몇 가지 묻고 기록
  python tools/live_trial.py --site L06 --browser CHROME --preset CLOSE_ALL --talkback Y --out trials.csv

  # 2) 이미 저장한 로그를 다시 읽기(기기 없이)
  python tools/live_trial.py --site L06 --browser CHROME --log live_logs/L06-CHROME-1.log --out trials.csv

  # 3) 묻지 않고 로그로 채울 수 있는 칸만 채우기(나머지는 기본값, 나중에 표에서 고침)
  python tools/live_trial.py ... --yes --popup-type EVENT_POPUP

로그: 앱 debug 빌드의 `CfySimple`(단계 코드 한 줄씩)과 `CfySpeech`(말한 종류·글자 수)만 받는다. 둘 다 화면 글자·
패키지 이름이 없다. 원본 로그는 live_logs/<시행 ID>.log로 남긴다.

한 줄 = 팝업 하나. 로그에서 DETECTED_*(DETECTED_BY_* 제외)가 나올 때마다 새 팝업으로 본다. 같은 팝업을 다시 찾기만 하고
아무것도 안 한 묶음(SAME_POPUP_COOLDOWN 등)은 버린다. 로그로 정할 수 없는 칸(팝업 모양, 사람이 본 종류, 이름 있는 닫기가 있었나,
금지 버튼·엉뚱한 곳을 눌렀나, 안내가 맞았나)은 사람이 답한다. 금지 버튼을 눌렀다고 답하면 🚨 경고를 낸다.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import re
import subprocess
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import real_site_metrics as metrics  # noqa: E402

LINE = re.compile(r"CfySimple[^:]*:\s*([A-Z][A-Z0-9_]*)\s*$")

# 클릭 종류 → 시행 기록의 clicked_target 역할 코드.
CLICK_ROLES = {
    "CLICKED_LABELED_CLOSE": "CLOSE", "CLICKED_STAY_ON_WEB": "STAY_WEB", "CLICKED_DIM_CLOSE": "DIM_OUTSIDE",
    "CLICKED_HIDE_FOR_A_WHILE": "HIDE_TODAY", "CLICKED_POLITE_NO": "POLITE_NO", "CLICKED_SKIP": "SKIP",
    "CLICKED_UNLABELED_X": "UNLABELED_X", "CLICKED_OTHER": "OTHER",
}
KIND = {"CLASSIFIED_AD": "ADVERTISEMENT", "CLASSIFIED_COUPON": "COUPON", "CLASSIFIED_NOTICE": "NOTICE",
        "CLASSIFIED_DECISION": "USER_DECISION", "CLASSIFIED_UNKNOWN": "UNKNOWN", "CLASSIFIED_SUSPICIOUS": "SUSPICIOUS"}
HINT_KIND = {"WORD_HINT_AD": "ADVERTISEMENT", "WORD_HINT_COUPON": "COUPON", "WORD_HINT_NOTICE": "NOTICE",
             "AD_HINT_IMAGE_DIALOG": "ADVERTISEMENT"}
WARN = {"SUSPICIOUS_ASKED", "DECIDED_DENY_SUSPICIOUS"}
ASK = {"ASKED", "DECISION_ASKED", "DECIDED_ASK", "INSTALL_AD_LONE_CLOSE_ASKED", "AUTO_CLOSE_FAILED_ASKED",
       "USER_PRESS_FAILED_ASKED", "BLOCKED_RISKY", "DECIDED_DENY_DECISION", "DEPTH_CUT_ASKED", "X_BUTTON_OFFERED",
       "X_BUTTON_MOVE_OFFERED", "CHAIN_GUARD_ASKED_RATE", "CHAIN_GUARD_ASKED_NEW_POPUP_AFTER_PRESS",
       "AUTO_CLOSE_SKIPPED_AFTER_FAIL", "LATE_CLOSE_WAIT_TIMEOUT"}
NOT_CLOSED = {"NOT_CLOSED", "CLOSE_REAPPEARED", "CLOSE_REAPPEARED_LATE", "CLOSE_RECHECK_STILL_MOVING"}
# 이 코드가 하나라도 있어야 "처리한 팝업"으로 본다(없으면 같은 팝업 다시 찾기만 한 것).
MEANINGFUL = ("CLASSIFIED_", "DECIDED_", "CLICKED", "ASKED", "BLOCKED_", "VIDEO_SKIP", "REMEMBERED_AUTO_CLOSE",
              "SUSPICIOUS_ASKED", "LATE_CLOSE_", "PRE_PRESS_")
VIDEO_START = {"VIDEO_SKIP_FOUND", "VIDEO_SKIP_WAITING", "VIDEO_SKIPPED"}
# 팝업을 못 찾았을 때 이유로 남길 코드(앞부분).
MISS_REASONS = ("SKIPPED_SCOPE_", "TREE_UNAVAILABLE", "NO_POPUP_ROOT", "SKIPPED_NOT_ALLOWED_APP",
                "SERVICE_DISABLED_BY_FEATURE", "VIDEO_APP_NOT_ALLOWED", "AUTO_CLOSE_SKIPPED_APP_PAUSED")
FLAGS = {"LEFT_APP": "LEFT_APP", "AUTO_CLOSE_PAUSED_APP_LEFT": "LEFT_APP", "PRE_PRESS_RECHECK_STOPPED": "PRE_PRESS_STOP",
         "CLOSE_REAPPEARED": "REAPPEARED", "CLOSE_REAPPEARED_LATE": "REAPPEARED", "CLICKED_OTHER": "CLICK_OTHER",
         "REMEMBERED_AUTO_CLOSE": "REMEMBERED", "PROMO_PURCHASE_NOT_ASKED": "PROMO_PURCHASE",
         "PROMO_LOGIN_NOT_ASKED": "PROMO_LOGIN", "WORD_HINT_DENIED": "WORD_HINT_DENIED",
         "DECIDED_AUTO_CLOSE_UNKNOWN": "F7_UNKNOWN_CLOSE", "DECIDED_AUTO_CLOSE_STRONG": "STRONG"}

POPUP_TYPE_MENU = [
    "EVENT_POPUP", "COUPON_POPUP", "NOTICE_POPUP", "APP_INSTALL_SHEET", "OPEN_IN_APP_SHEET", "BOTTOM_SHEET",
    "BOTTOM_BANNER", "DIM_LAYER", "FULL_LAYER", "INTERSTITIAL", "UNLABELED_X_ONLY", "VIDEO_AD", "LOGIN_PROMO",
    "PERMISSION", "COOKIE_CONSENT", "SURVEY", "OTHER",
]
# 사람이 본 모양 → 사람 기준 종류의 기본값(잘못 닫음 판정용). 시험자가 바꿀 수 있다.
KIND_FROM_TYPE = {"NOTICE_POPUP": "NOTICE", "COUPON_POPUP": "COUPON", "PERMISSION": "DECISION",
                  "COOKIE_CONSENT": "DECISION", "SURVEY": "OTHER", "OTHER": "OTHER"}
HUMAN_KIND_MENU = ["AD", "COUPON", "NOTICE", "DECISION", "SUSPICIOUS", "OTHER"]
HEADER = (TOOLS.parent / "docs" / "templates" / "real_site_trials.csv").read_text(encoding="utf-8").splitlines()[0].split(",")


def codes_of(lines: list[str]) -> list[str]:
    out = []
    for line in lines:
        m = LINE.search(line.rstrip("\r\n"))
        if m:
            out.append(m.group(1))
    return out


def split_episodes(codes: list[str]) -> tuple[list[list[str]], list[str]]:
    """(팝업별 코드 묶음, 첫 팝업 전 코드)."""
    episodes: list[list[str]] = []
    before: list[str] = []
    current: list[str] | None = None
    for code in codes:
        root = code.startswith("DETECTED_") and not code.startswith("DETECTED_BY_")
        video = code in VIDEO_START and (current is None or not any(c in VIDEO_START for c in current))
        if root or (video and (current is None or any(c.startswith("CLOSED") or c in NOT_CLOSED for c in current))):
            current = [code]
            episodes.append(current)
        elif current is None:
            before.append(code)
        else:
            current.append(code)
    kept = [e for e in episodes if any(c.startswith(MEANINGFUL) for c in e)]
    return kept, before


def summarize(episode: list[str]) -> dict:
    detected = next((c for c in episode if c.startswith("DETECTED_") and not c.startswith("DETECTED_BY_")), "")
    if not detected and any(c in VIDEO_START for c in episode):
        detected = "VIDEO_SKIP"
    classified = next((c for c in episode if c in KIND), "")
    kind = KIND.get(classified, "")
    hint = next((c for c in episode if c in HINT_KIND), "")
    if hint and kind in ("", "UNKNOWN"):
        kind = HINT_KIND[hint]
        classified = f"{classified}+{hint}" if classified else hint
    click_kind = next((c for c in episode if c in CLICK_ROLES), "")
    clicked = CLICK_ROLES.get(click_kind, "")
    if not clicked and "VIDEO_SKIPPED" in episode:
        clicked = "SKIP"
    if not clicked and "CLICKED" in episode:
        clicked = "OTHER"
    if clicked:
        click_at = max(i for i, c in enumerate(episode) if c in CLICK_ROLES or c in ("CLICKED", "VIDEO_SKIPPED"))
        user = "USER_CHOSE_PRESS" in episode[:click_at + 1]
        outcome = "USER_CONFIRMED" if user else "CLOSED_BY_APP"
        after = episode[click_at:]
        closed = ("CLOSED_CONFIRMED" in after or "VIDEO_SKIPPED" in after) and not any(c in NOT_CLOSED for c in after)
    elif any(c in WARN for c in episode):
        outcome, closed = "WARNED", False
    elif any(c in ASK for c in episode):
        outcome, closed = "ASKED_ONLY", False
    else:
        outcome, closed = "NO_ACTION", False
    flags = []
    for c in episode:
        f = FLAGS.get(c)
        if f and f not in flags:
            flags.append(f)
    return {"detected_code": detected, "classified_code": classified, "observed_kind": kind,
            "outcome": outcome, "clicked_target": clicked or "NONE", "closed_verified": "Y" if closed else "N",
            "notes": ";".join(flags)[:metrics.NOTES_MAX],
            "close_hint": "Y" if click_kind == "CLICKED_LABELED_CLOSE" else ""}


def miss_reasons(codes: list[str]) -> str:
    seen = []
    for c in codes:
        if c.startswith(MISS_REASONS) and c not in seen:
            seen.append(c)
    return ";".join(seen)[:metrics.NOTES_MAX]


class Asker:
    def __init__(self, auto: bool):
        self.auto = auto

    def ask(self, question: str, default: str = "", choices: set[str] | None = None) -> str:
        if self.auto:
            return default
        while True:
            hint = f" [{default}]" if default else ""
            answer = input(f"{question}{hint}: ").strip().upper() or default
            if choices is None or answer in choices:
                return answer
            print(f"  다음 중 하나로: {', '.join(sorted(choices))}")

    def yes_no(self, question: str, default: str) -> str:
        return self.ask(f"{question} (Y/N)", default, {"Y", "N"})

    def popup_type(self, default: str) -> str:
        if self.auto:
            return default
        print("팝업 모양: " + "  ".join(f"{i + 1}.{t}" for i, t in enumerate(POPUP_TYPE_MENU)))
        while True:
            answer = input(f"번호 또는 코드 [{default}]: ").strip().upper() or default
            if answer.isdigit() and 1 <= int(answer) <= len(POPUP_TYPE_MENU):
                return POPUP_TYPE_MENU[int(answer) - 1]
            if answer in POPUP_TYPE_MENU:
                return answer
            print("  목록의 번호나 코드로 적어 주세요")


def ask_kind(asker: Asker, popup_type: str, forced: str) -> str:
    default = forced or KIND_FROM_TYPE.get(popup_type, "AD")
    return asker.ask("사람이 보기에 무슨 팝업? (AD 광고·COUPON 쿠폰·NOTICE 공지·DECISION 결정·SUSPICIOUS 사기 의심·OTHER)",
                     default, set(HUMAN_KIND_MENU))


def build_rows(codes: list[str], base: dict, asker: Asker, popup_type_default: str, kind_default: str = "") -> list[dict]:
    episodes, before = split_episodes(codes)
    rows = []
    if not episodes:
        print("로그에 팝업 처리 기록(DETECTED_*)이 없습니다.")
        appeared = asker.yes_no("팝업이 화면에 떴나요?", "Y")
        row = dict(base, popup_appeared=appeared, detected="N", outcome="MISSED" if appeared == "Y" else "NOT_RUN",
                   clicked_target="NONE", forbidden_click="N", misclick="N", notes=miss_reasons(codes))
        if appeared == "Y":
            row["popup_type"] = asker.popup_type(popup_type_default)
            row["human_kind"] = ask_kind(asker, row["popup_type"], kind_default)
        rows.append(row)
        return rows
    for i, episode in enumerate(episodes):
        s = summarize(episode)
        print(f"\n팝업 {i + 1}/{len(episodes)}: ①{s['detected_code']} ②{s['classified_code'] or '-'} "
              f"③{metrics.HANDLING_KO[s['outcome']]} 누름={s['clicked_target']} 닫힘확인={s['closed_verified']}"
              f"{' 메모=' + s['notes'] if s['notes'] else ''}")
        row = dict(base, trial_id=f"{base['trial_id']}-{chr(97 + i)}" if len(episodes) > 1 else base["trial_id"],
                   popup_appeared="Y", detected="Y", **{k: v for k, v in s.items() if k != "close_hint"})
        row["popup_type"] = asker.popup_type(popup_type_default)
        row["human_kind"] = ask_kind(asker, row["popup_type"], kind_default)
        row["close_available"] = asker.yes_no("이름 있는 닫기 버튼(닫기·X 이름 있음)이 있었나요?", s["close_hint"] or "Y")
        if s["clicked_target"] != "NONE":
            row["closed_verified"] = asker.yes_no("팝업이 실제로 사라졌나요?", s["closed_verified"])
        row["forbidden_click"] = asker.yes_no("🚨 결제·구매·주문·동의·구독·삭제·권한·로그인·앱에서 열기 같은 버튼을 눌렀나요?", "N")
        if row["forbidden_click"] == "Y":
            code = asker.ask("  어떤 버튼? (BUY·ORDER·PAY·SUBSCRIBE·AGREE·ALLOW_PERMISSION·DELETE·LOGIN·OPEN_APP·INSTALL_APP·AD_LINK)",
                             "BUY", {"BUY", "ORDER", "PAY", "SUBSCRIBE", "AGREE", "ALLOW_PERMISSION", "DELETE", "LOGIN",
                                     "OPEN_APP", "INSTALL_APP", "AD_LINK", "PRIZE_CLAIM", "COUPON_GET"})
            row["clicked_target"] = code
            print("🚨 잘못 누름입니다. 화면을 그대로 두고 바로 단톡에 알려 주세요(사이트·브라우저·시행 ID).")
        row["misclick"] = asker.yes_no("팝업이 아닌 곳이나 엉뚱한 버튼을 눌렀나요?", "N")
        row["info_correct"] = asker.yes_no("안내 문장이 화면과 맞았나요?", "Y")
        row["seconds_to_close"] = asker.ask("닫힐 때까지 대략 몇 초? (모르면 Enter)", "")
        rows.append(row)
    return rows


def capture(log_path: Path) -> list[str]:
    try:
        subprocess.run(["adb", "logcat", "-c"], check=True)
    except (OSError, subprocess.CalledProcessError) as e:
        sys.exit(f"adb를 실행할 수 없습니다: {e}")
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8") as out:
        proc = subprocess.Popen(["adb", "logcat", "-v", "brief", "-s", "CfySimple:D", "CfySpeech:D"],
                                stdout=out, stderr=subprocess.DEVNULL, text=True, encoding="utf-8")
        try:
            input("기록 중입니다. 휴대폰에서 사이트·앱을 열고, 팝업 처리가 끝나면 Enter를 누르세요.")
        finally:
            proc.terminate()
            proc.wait(timeout=5)
    return log_path.read_text(encoding="utf-8", errors="replace").splitlines()


def next_trial_id(out: Path, site: str, browser: str) -> str:
    prefix = f"{site}-{browser}-"
    n = 0
    if out.exists():
        with out.open(encoding="utf-8") as f:
            for row in csv.DictReader(f):
                m = re.match(re.escape(prefix) + r"(\d+)", row.get("trial_id", ""))
                if m:
                    n = max(n, int(m.group(1)))
    return f"{prefix}{n + 1}"


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--site", required=True, help="정답지 site_id (L01~L25, 목록 밖은 L90)")
    p.add_argument("--browser", required=True, choices=sorted(metrics.BROWSERS))
    p.add_argument("--out", type=Path, default=Path("trials.csv"))
    p.add_argument("--log", type=Path, help="이미 저장한 로그 파일(주면 adb를 쓰지 않음)")
    p.add_argument("--preset", default="CLOSE_ALL", choices=sorted(metrics.PRESETS - {""}))
    p.add_argument("--talkback", default="Y", choices=["Y", "N"])
    p.add_argument("--tester", default="D")
    p.add_argument("--device", default="")
    p.add_argument("--android", default="")
    p.add_argument("--build", default="", help="앱 빌드 커밋 앞 7자리")
    p.add_argument("--rehearsal", default="N", choices=["Y", "N"])
    p.add_argument("--site-code", default="", help="L90일 때 사이트 영문 코드(예: SITE_WEMAKEPRICE). 한글 금지")
    p.add_argument("--popup-type", default="OTHER", choices=POPUP_TYPE_MENU, help="묻지 않을 때(--yes) 쓸 팝업 모양")
    p.add_argument("--human-kind", default="", choices=[""] + HUMAN_KIND_MENU,
                   help="묻지 않을 때(--yes) 쓸 사람 기준 종류(비우면 팝업 모양에서 추정)")
    p.add_argument("--yes", action="store_true", help="묻지 않고 기본값으로 채움")
    p.add_argument("--key", type=Path, default=metrics.DEFAULT_KEY)
    args = p.parse_args(argv)
    # 윈도 콘솔(cp949)에서도 한글·🚨가 깨지거나 멈추지 않게.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    key = metrics.load_key(args.key)
    if args.site not in key:
        sys.exit(f"정답지에 없는 사이트: {args.site}")
    trial_id = next_trial_id(args.out, args.site, args.browser)
    print(f"시행 {trial_id}: {key[args.site]['name']} / {args.browser} / {args.preset}")
    if args.log:
        lines = args.log.read_text(encoding="utf-8", errors="replace").splitlines()
    else:
        lines = capture(Path("live_logs") / f"{trial_id}.log")
    codes = codes_of(lines)
    base = {c: "" for c in HEADER}
    base.update(trial_id=trial_id, site_id=args.site, browser=args.browser, date=dt.date.today().isoformat(),
                tester=args.tester, device=args.device, android=args.android, app_build=args.build,
                talkback=args.talkback, preset=args.preset, rehearsal=args.rehearsal)
    rows = build_rows(codes, base, Asker(args.yes), args.popup_type, args.human_kind)
    if args.site_code:
        for row in rows:
            row["notes"] = ";".join(x for x in (args.site_code, row.get("notes", "")) if x)[:metrics.NOTES_MAX]

    errors = [e for n, row in enumerate(rows, start=1) for e in metrics.check_row(n, row, key)]
    if errors:
        print("기록하지 않았습니다(입력 문제):", *errors, sep="\n  ", file=sys.stderr)
        return 1
    new_file = not args.out.exists()
    with args.out.open("a", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=HEADER)
        if new_file:
            writer.writeheader()
        writer.writerows(rows)
    for row in rows:
        verdict, why = metrics.judge(row, key[args.site])
        print(f"→ {row['trial_id']}: ④ {verdict}{' (' + why + ')' if verdict == 'FAIL' else ''}")
        if why == "WRONG_KIND_CLOSE":
            print("   ⚠ 묻거나 알려야 할 팝업을 닫았습니다(내용을 놓침). 단톡에 사이트·시행 ID를 알려 주세요.")
    print(f"{len(rows)}줄을 {args.out}에 붙였습니다.")
    return 1 if any(r["forbidden_click"] == "Y" or r["misclick"] == "Y" for r in rows) else 0


if __name__ == "__main__":
    sys.exit(main())
