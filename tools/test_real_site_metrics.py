"""D: real_site_metrics.py 회귀 시험 (표준 라이브러리만 사용).

실행: python3 -m unittest discover -s tools -p "test_*.py"
모든 데이터는 합성이며 실제 측정 결과가 아니다.
"""
from __future__ import annotations

import csv
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
SCRIPT = TOOLS / "real_site_metrics.py"
REAL_KEY = TOOLS.parent / "docs" / "templates" / "real_site_answer_key.csv"
TEMPLATE = TOOLS.parent / "docs" / "templates" / "real_site_trials.csv"

KEY = """site_id,name,platform,entry,expected_popup,human_kind,safe_target,forbidden_targets,expected_handling,expected_kind,risk,tier,status,observed_on
L01,web a,WEB_CHROME,a.example,EVENT_POPUP,AD,CLOSE,INSTALL_APP,CLOSE_OK,ADVERTISEMENT,MID,A,CANDIDATE,
L02,web b,WEB_CHROME,b.example,APP_INSTALL_SHEET,AD,CLOSE,INSTALL_APP,ASK,USER_DECISION,HIGH,B,CANDIDATE,
L03,perm,WEB_CHROME,c.example,NOTIFICATION_PERMISSION,PERMISSION,NONE,ALLOW_PERMISSION,NO_PRESS,USER_DECISION,LOW,A,CANDIDATE,
L04,promo,WEB_CHROME,d.example,LOGIN_PROMO,AD,CLOSE,LOGIN,CLOSE_OR_ASK,ADVERTISEMENT,LOW,A,CANDIDATE,
L05,video,APP,e.example,VIDEO_AD_SKIP,AD,SKIP,AD_LINK,CLOSE_OK,ADVERTISEMENT,LOW,A,CANDIDATE,
"""

HEADER = TEMPLATE.read_text(encoding="utf-8").splitlines()[0]


def trial(tid: str, site: str, outcome: str, clicked: str = "CLOSE", closed: str = "Y", forbidden: str = "N",
          misclick: str = "N", info: str = "Y", appeared: str = "Y", detected: str = "Y", kind: str = "",
          seconds: str = "1.5", notes: str = "", browser: str = "CHROME", preset: str = "CLOSE_ALL",
          close_available: str = "Y", popup_type: str = "EVENT_POPUP") -> str:
    fields = {
        "trial_id": tid, "site_id": site, "browser": browser, "date": "2026-11-02", "tester": "D",
        "device": "synthetic", "android": "14", "app_build": "test", "talkback": "Y", "preset": preset,
        "popup_type": popup_type, "popup_appeared": appeared, "detected": detected,
        "detected_code": "DETECTED_DIALOG_WINDOW" if detected == "Y" else "", "classified_code": "",
        "observed_kind": kind, "outcome": outcome, "clicked_target": clicked, "close_available": close_available,
        "closed_verified": closed, "forbidden_click": forbidden, "misclick": misclick, "info_correct": info,
        "seconds_to_close": seconds, "rehearsal": "Y", "notes": notes,
    }
    return ",".join(fields[c] for c in HEADER.split(","))


def run(rows: list[str], *extra: str) -> subprocess.CompletedProcess:
    with tempfile.TemporaryDirectory() as tmp:
        key = Path(tmp) / "key.csv"
        key.write_text(KEY, encoding="utf-8")
        trials = Path(tmp) / "trials.csv"
        trials.write_text("\n".join([HEADER, *rows]) + "\n", encoding="utf-8")
        return subprocess.run([sys.executable, str(SCRIPT), str(trials), "--key", str(key), *extra],
                              capture_output=True, text=True, encoding="utf-8")


class RealSiteMetricsTest(unittest.TestCase):
    def test_safe_trials_pass_and_site_becomes_demo_candidate(self):
        rows = [trial(f"T{i}", "L01", "CLOSED_BY_APP", kind="ADVERTISEMENT") for i in range(3)]
        rows.append(trial("T9", "L03", "ASKED_ONLY", clicked="NONE", closed="N"))
        result = run(rows)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("| L01 | web a | CLOSE_OK | 3 | 3 | 0 | - | Y |", result.stdout)
        self.assertIn("| L03 | perm | NO_PRESS | 1 | 1 | 0 | - | N |", result.stdout)
        self.assertIn("닫힘 성공률(CLOSE_OK 사이트) | 100.0% (3/3)", result.stdout)
        self.assertIn("안전 판정: 통과", result.stdout)

    def test_forbidden_click_fails_with_exit_1_and_is_listed(self):
        result = run([trial("T1", "L01", "CLOSED_BY_APP", clicked="INSTALL_APP", forbidden="Y")])
        self.assertEqual(1, result.returncode)
        self.assertIn("FORBIDDEN_CLICK", result.stdout)
        self.assertIn("🚨 잘못 누름", result.stdout)
        self.assertIn("안전 판정: 실패", result.stdout)

    def test_payment_words_are_forbidden_on_every_row(self):
        result = run([trial("T1", "L01", "CLOSED_BY_APP", clicked="PAY", forbidden="N")])
        self.assertEqual(1, result.returncode)
        self.assertIn("forbidden_click=N", result.stderr)

    def test_hide_today_is_not_forbidden_but_must_come_after_close(self):
        ok = run([trial("T1", "L01", "CLOSED_BY_APP", clicked="HIDE_TODAY", close_available="N")])
        self.assertEqual(0, ok.returncode, ok.stderr)
        self.assertIn("금지 버튼 클릭 | 0건", ok.stdout)
        self.assertIn("| L01 | web a | CLOSE_OK | 1 | 1 | 0 | - |", ok.stdout)
        early = run([trial("T2", "L01", "CLOSED_BY_APP", clicked="HIDE_TODAY", close_available="Y")])
        self.assertEqual(0, early.returncode, early.stderr)
        self.assertIn("HIDE_BEFORE_CLOSE", early.stdout)
        self.assertIn("'오늘 하루 보지 않기' 먼저 | 1건", early.stdout)

    def test_hide_today_in_ads_only_must_be_asked(self):
        result = run([trial("T1", "L01", "CLOSED_BY_APP", clicked="HIDE_TODAY", close_available="N",
                            preset="CLOSE_ADS_ONLY")])
        self.assertIn("HIDE_IN_ADS_ONLY", result.stdout)
        asked = run([trial("T2", "L01", "USER_CONFIRMED", clicked="HIDE_TODAY", close_available="N",
                           preset="CLOSE_ADS_ONLY")])
        self.assertNotIn("HIDE_IN_ADS_ONLY", asked.stdout)

    def test_close_or_ask_accepts_both(self):
        result = run([trial("T1", "L04", "CLOSED_BY_APP"), trial("T2", "L04", "ASKED_ONLY", clicked="NONE", closed="N")])
        self.assertIn("| L04 | promo | CLOSE_OR_ASK | 2 | 2 | 0 | - |", result.stdout)
        nothing = run([trial("T3", "L04", "NO_ACTION", clicked="NONE", closed="N")])
        self.assertIn("NOT_ASKED", nothing.stdout)

    def test_skip_is_a_close_and_left_out_of_kind_accuracy(self):
        result = run([trial("T1", "L05", "CLOSED_BY_APP", clicked="SKIP", kind="UNKNOWN")])
        self.assertIn("| L05 | video | CLOSE_OK | 1 | 1 | 0 | - |", result.stdout)
        self.assertIn("기대 분류와 일치(건너뛰기 제외) | N/A (0건)", result.stdout)

    def test_screen_text_in_notes_is_rejected(self):
        result = run([trial("T1", "L01", "CLOSED_BY_APP", notes="지금 구매하기")])
        self.assertEqual(1, result.returncode)
        self.assertIn("화면 원문 금지", result.stderr)

    def test_auto_close_on_ask_site_fails(self):
        result = run([trial("T1", "L02", "CLOSED_BY_APP")])
        self.assertEqual(0, result.returncode)
        self.assertIn("AUTO_OUTSIDE_GOAL", result.stdout)

    def test_pressing_on_no_press_site_fails_but_missing_it_is_safe(self):
        self.assertIn("PRESSED", run([trial("T1", "L03", "USER_CONFIRMED", clicked="CLOSE")]).stdout)
        missed = run([trial("T2", "L03", "MISSED", clicked="NONE", detected="N", closed="N")])
        self.assertIn("| L03 | perm | NO_PRESS | 1 | 1 | 0 | - |", missed.stdout)

    def test_close_without_verification_is_not_a_pass(self):
        result = run([trial("T1", "L01", "CLOSED_BY_APP", closed="N")])
        self.assertIn("CLOSE_NOT_VERIFIED", result.stdout)

    def test_missed_popups_are_listed_by_browser(self):
        result = run([trial("T1", "L01", "MISSED", clicked="NONE", detected="N", closed="N", browser="WHALE",
                            notes="SKIPPED_SCOPE_NOT_CHOSEN")])
        self.assertIn("- L01 web a / WHALE / EVENT_POPUP / SKIPPED_SCOPE_NOT_CHOSEN", result.stdout)
        self.assertIn("| WHALE | 1 | 0 | 0 | 1 | MISSED |", result.stdout)

    def test_no_popup_rows_are_not_counted(self):
        result = run([trial("T1", "L01", "NOT_RUN", clicked="NONE", appeared="N", detected="N", popup_type="NONE")])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("| L01 | web a | CLOSE_OK | 0 | 0 | 0 | - | N |", result.stdout)

    def test_unknown_site_is_an_input_problem(self):
        result = run([trial("T1", "L99", "CLOSED_BY_APP")])
        self.assertEqual(1, result.returncode)
        self.assertIn("L99", result.stderr)

    def test_repository_answer_key_is_valid(self):
        with REAL_KEY.open(encoding="utf-8") as f:
            rows = list(csv.DictReader(f))
        ids = [r["site_id"] for r in rows]
        self.assertEqual([f"L{i:02d}" for i in range(1, 26)] + ["L90"], ids)
        for r in rows:
            self.assertIn(r["expected_handling"], {"CLOSE_OK", "CLOSE_OR_ASK", "ASK", "NO_PRESS", "WARN_NO_PRESS", "NO_POPUP"})
            forbidden = {t for t in r["forbidden_targets"].split(";") if t}
            self.assertNotIn(r["safe_target"], forbidden, r["site_id"])
            # 11/2 팀 결정: '오늘 하루 보지 않기'는 닫기 다음 순서이지 금지가 아니다.
            self.assertNotIn("HIDE_TODAY", forbidden, r["site_id"])
            self.assertIn(r["status"], {"CANDIDATE", "OBSERVED", "REHEARSED", "DROPPED"}, r["site_id"])
        decided = {r["site_id"]: r["expected_handling"] for r in rows}
        self.assertEqual("CLOSE_OR_ASK", decided["L01"])  # 로그인 홍보: 홍보 문구 있으면 닫기
        self.assertEqual("CLOSE_OR_ASK", decided["L12"])  # 구독 버튼 있으면 묻기, 리허설 때 확정
        self.assertEqual("ASK", decided["L03"])


if __name__ == "__main__":
    unittest.main()
