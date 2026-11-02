"""D: live_trial.py 회귀 시험 (표준 라이브러리만 사용, adb 없이 저장된 로그로만).

실행: python3 -m unittest discover -s tools -p "test_*.py"
로그는 합성이다. 실제 기기 로그가 아니다.
"""
from __future__ import annotations

import csv
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import live_trial  # noqa: E402

SCRIPT = TOOLS / "live_trial.py"


def log(*codes: str) -> list[str]:
    return [f"D/CfySimple(12345): {c}" for c in codes] + ["D/CfySpeech(12345): RESULT len=12"]


class SummarizeTest(unittest.TestCase):
    def test_auto_close_of_a_labeled_close(self):
        eps, _ = live_trial.split_episodes(live_trial.codes_of(log(
            "SERVICE_CONNECTED", "DETECTED_BY_WINDOW_EVENT", "DETECTED_DIALOG_WINDOW", "CLASSIFIED_COUPON",
            "CANDIDATE_FOUND", "DECIDED_AUTO_CLOSE", "PRE_PRESS_RECHECK_PASSED", "CLICKED", "CLICKED_LABELED_CLOSE",
            "CLOSED_CONFIRMED", "RESULT_BANNER_SHOWN")))
        self.assertEqual(1, len(eps))
        s = live_trial.summarize(eps[0])
        self.assertEqual("DETECTED_DIALOG_WINDOW", s["detected_code"])
        self.assertEqual("COUPON", s["observed_kind"])
        self.assertEqual("CLOSED_BY_APP", s["outcome"])
        self.assertEqual("CLOSE", s["clicked_target"])
        self.assertEqual("Y", s["closed_verified"])

    def test_user_press_after_asking(self):
        s = live_trial.summarize(["DETECTED_BOTTOM_SHEET", "CLASSIFIED_DECISION", "DECIDED_ASK", "ASKED",
                                  "USER_CHOSE_PRESS", "CLICKED", "CLICKED_HIDE_FOR_A_WHILE", "NOT_CLOSED",
                                  "NOT_CLOSED_STILL_THERE"])
        self.assertEqual("USER_CONFIRMED", s["outcome"])
        self.assertEqual("HIDE_TODAY", s["clicked_target"])
        self.assertEqual("N", s["closed_verified"])

    def test_word_hint_names_an_unknown_popup(self):
        s = live_trial.summarize(["DETECTED_FRONT_LAYER", "CLASSIFIED_UNKNOWN", "WORD_HINT_AD", "DECIDED_AUTO_CLOSE",
                                  "CLICKED", "CLICKED_LABELED_CLOSE", "CLOSED_CONFIRMED"])
        self.assertEqual("ADVERTISEMENT", s["observed_kind"])
        self.assertEqual("CLASSIFIED_UNKNOWN+WORD_HINT_AD", s["classified_code"])

    def test_scam_warning_and_ask_only(self):
        self.assertEqual("WARNED", live_trial.summarize(
            ["DETECTED_DIALOG_WINDOW", "CLASSIFIED_SUSPICIOUS", "DECIDED_DENY_SUSPICIOUS", "SUSPICIOUS_ASKED"])["outcome"])
        self.assertEqual("ASKED_ONLY", live_trial.summarize(
            ["DETECTED_DIALOG_WINDOW", "CLASSIFIED_DECISION", "BLOCKED_RISKY", "DECISION_ASKED"])["outcome"])

    def test_youtube_skip_without_a_popup_root(self):
        eps, _ = live_trial.split_episodes(["VIDEO_EVENT_SEEN", "VIDEO_AD_MARKER_FOUND", "VIDEO_SKIP_WAITING",
                                            "VIDEO_SKIP_WAIT_ANNOUNCED", "VIDEO_SKIP_FOUND", "VIDEO_SKIPPED"])
        self.assertEqual(1, len(eps))
        s = live_trial.summarize(eps[0])
        self.assertEqual("VIDEO_SKIP", s["detected_code"])
        self.assertEqual("SKIP", s["clicked_target"])
        self.assertEqual("CLOSED_BY_APP", s["outcome"])

    def test_repeat_detection_without_handling_is_dropped(self):
        eps, _ = live_trial.split_episodes(["DETECTED_DIALOG_WINDOW", "CLASSIFIED_AD", "DECIDED_ASK", "ASKED",
                                            "DETECTED_DIALOG_WINDOW", "SAME_POPUP_COOLDOWN",
                                            "DETECTED_BOTTOM_BANNER", "CLASSIFIED_AD", "DECIDED_AUTO_CLOSE", "CLICKED",
                                            "CLICKED_LABELED_CLOSE", "CLOSED_CONFIRMED"])
        self.assertEqual(["DETECTED_DIALOG_WINDOW", "DETECTED_BOTTOM_BANNER"], [e[0] for e in eps])

    def test_lines_with_numbers_or_speech_are_not_codes(self):
        codes = live_trial.codes_of(["D/CfySimple( 1): DETECTED_TO_FIRST_READ=120", "D/CfySpeech( 1): RESULT len=3",
                                     "11-02 10:00:00.000  1  2 D CfySimple: NO_POPUP_ROOT"])
        self.assertEqual(["NO_POPUP_ROOT"], codes)


class CliTest(unittest.TestCase):
    def run_cli(self, lines: list[str], *extra: str) -> tuple[subprocess.CompletedProcess, list[dict]]:
        with tempfile.TemporaryDirectory() as tmp:
            log_file = Path(tmp) / "l.log"
            log_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
            out = Path(tmp) / "trials.csv"
            result = subprocess.run([sys.executable, str(SCRIPT), "--site", "L06", "--browser", "SAMSUNG",
                                     "--log", str(log_file), "--out", str(out), "--yes", *extra],
                                    capture_output=True, text=True, encoding="utf-8")
            rows = list(csv.DictReader(out.read_text(encoding="utf-8").splitlines())) if out.exists() else []
            return result, rows

    def test_two_popups_become_two_rows_that_metrics_accepts(self):
        result, rows = self.run_cli(log(
            "DETECTED_DIALOG_WINDOW", "CLASSIFIED_COUPON", "DECIDED_AUTO_CLOSE", "CLICKED", "CLICKED_LABELED_CLOSE",
            "CLOSED_CONFIRMED", "DETECTED_BOTTOM_SHEET", "CLASSIFIED_AD", "DECIDED_AUTO_CLOSE", "CLICKED",
            "CLICKED_STAY_ON_WEB", "CLOSED_CONFIRMED"), "--popup-type", "EVENT_POPUP")
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertEqual(["L06-SAMSUNG-1-a", "L06-SAMSUNG-1-b"], [r["trial_id"] for r in rows])
        self.assertEqual(["CLOSE", "STAY_WEB"], [r["clicked_target"] for r in rows])
        self.assertEqual("DETECTED_BOTTOM_SHEET", rows[1]["detected_code"])
        self.assertEqual(["AD", "AD"], [r["human_kind"] for r in rows])

    def test_notice_closed_by_the_app_is_flagged(self):
        result, rows = self.run_cli(log("DETECTED_DIALOG_WINDOW", "CLASSIFIED_AD", "DECIDED_AUTO_CLOSE", "CLICKED",
                                        "CLICKED_LABELED_CLOSE", "CLOSED_CONFIRMED"),
                                    "--popup-type", "NOTICE_POPUP")
        self.assertEqual("NOTICE", rows[0]["human_kind"])
        self.assertIn("WRONG_KIND_CLOSE", result.stdout)

    def test_no_popup_found_records_the_reason_codes(self):
        result, rows = self.run_cli(log("SERVICE_CONNECTED", "SKIPPED_SCOPE_NOT_CHOSEN", "NO_POPUP_ROOT"))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, len(rows))
        self.assertEqual(("MISSED", "N"), (rows[0]["outcome"], rows[0]["detected"]))
        self.assertEqual("SKIPPED_SCOPE_NOT_CHOSEN;NO_POPUP_ROOT", rows[0]["notes"])

    def test_site_code_is_kept_in_notes_for_unlisted_sites(self):
        with tempfile.TemporaryDirectory() as tmp:
            log_file = Path(tmp) / "l.log"
            log_file.write_text("\n".join(log("DETECTED_DIM_OVERLAY", "CLASSIFIED_AD", "DECIDED_ASK", "ASKED")),
                                encoding="utf-8")
            out = Path(tmp) / "trials.csv"
            subprocess.run([sys.executable, str(SCRIPT), "--site", "L90", "--browser", "WHALE", "--log", str(log_file),
                            "--out", str(out), "--yes", "--site-code", "SITE_EXAMPLE"], check=True,
                           capture_output=True, text=True, encoding="utf-8")
            row = next(csv.DictReader(out.read_text(encoding="utf-8").splitlines()))
            self.assertEqual("SITE_EXAMPLE", row["notes"])
            self.assertEqual("ASKED_ONLY", row["outcome"])


if __name__ == "__main__":
    unittest.main()
