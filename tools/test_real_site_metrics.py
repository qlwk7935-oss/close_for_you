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
L01,web a,WEB_CHROME,a.example,EVENT_POPUP,AD,CLOSE,BUY;HIDE_TODAY,CLOSE_OK,ADVERTISEMENT,MID,A,CANDIDATE,
L02,web b,WEB_CHROME,b.example,APP_INSTALL_SHEET,AD,CLOSE,BUY,ASK,USER_DECISION,HIGH,B,CANDIDATE,
L03,perm,WEB_CHROME,c.example,NOTIFICATION_PERMISSION,PERMISSION,NONE,ALLOW_PERMISSION,NO_PRESS,USER_DECISION,LOW,A,CANDIDATE,
"""

HEADER = TEMPLATE.read_text(encoding="utf-8").splitlines()[0]


def trial(tid: str, site: str, outcome: str, clicked: str = "CLOSE", closed: str = "Y", forbidden: str = "N",
          misclick: str = "N", info: str = "Y", appeared: str = "Y", mode: str = "PRESET", kind: str = "",
          seconds: str = "1.5", notes: str = "") -> str:
    fields = {
        "trial_id": tid, "site_id": site, "date": "2026-11-01", "tester": "C", "device": "synthetic",
        "android": "14", "app_build": "test", "talkback": "Y", "mode": mode, "preset": "CLOSE_ALL",
        "popup_appeared": appeared, "detected": "Y", "observed_kind": kind, "outcome": outcome,
        "clicked_target": clicked, "closed_verified": closed, "forbidden_click": forbidden, "misclick": misclick,
        "info_correct": info, "seconds_to_close": seconds, "rehearsal": "Y", "notes": notes,
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

    def test_forbidden_click_fails_with_exit_1(self):
        result = run([trial("T1", "L01", "CLOSED_BY_APP", clicked="BUY", forbidden="Y")])
        self.assertEqual(1, result.returncode)
        self.assertIn("FORBIDDEN_CLICK", result.stdout)
        self.assertIn("안전 판정: 실패", result.stdout)

    def test_forbidden_target_marked_N_is_an_input_problem(self):
        result = run([trial("T1", "L01", "CLOSED_BY_APP", clicked="BUY", forbidden="N")])
        self.assertEqual(1, result.returncode)
        self.assertIn("forbidden_click=N", result.stderr)

    def test_screen_text_in_notes_is_rejected(self):
        result = run([trial("T1", "L01", "CLOSED_BY_APP", notes="지금 구매하기")])
        self.assertEqual(1, result.returncode)
        self.assertIn("화면 원문 금지", result.stderr)

    def test_auto_close_on_ask_site_fails(self):
        result = run([trial("T1", "L02", "CLOSED_BY_APP")])
        self.assertEqual(0, result.returncode)
        self.assertIn("AUTO_OUTSIDE_GOAL", result.stdout)

    def test_pressing_on_no_press_site_fails(self):
        result = run([trial("T1", "L03", "USER_CONFIRMED", clicked="CLOSE")])
        self.assertIn("PRESSED", result.stdout)

    def test_close_without_verification_is_not_a_pass(self):
        result = run([trial("T1", "L01", "CLOSED_BY_APP", closed="N")])
        self.assertIn("CLOSE_NOT_VERIFIED", result.stdout)

    def test_no_popup_rows_are_not_counted(self):
        result = run([trial("T1", "L01", "NOT_RUN", clicked="NONE", appeared="N")])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("| L01 | web a | CLOSE_OK | 0 | 0 | 0 | - | N |", result.stdout)

    def test_unknown_site_is_an_input_problem(self):
        result = run([trial("T1", "L99", "CLOSED_BY_APP")])
        self.assertEqual(1, result.returncode)
        self.assertIn("L99", result.stderr)

    def test_repository_answer_key_is_valid(self):
        with REAL_KEY.open(encoding="utf-8") as f:
            rows = list(csv.DictReader(f))
        self.assertEqual(20, len(rows))
        self.assertEqual([f"L{i:02d}" for i in range(1, 21)], [r["site_id"] for r in rows])
        for r in rows:
            self.assertIn(r["expected_handling"], {"CLOSE_OK", "ASK", "NO_PRESS", "WARN_NO_PRESS", "NO_POPUP"})
            self.assertNotIn(r["safe_target"], {t for t in r["forbidden_targets"].split(";")}, r["site_id"])
            self.assertIn(r["status"], {"CANDIDATE", "OBSERVED", "REHEARSED", "DROPPED"}, r["site_id"])


if __name__ == "__main__":
    unittest.main()
