"""D: qa_metrics.py CLI 회귀 시험 (표준 라이브러리만 사용).

실행: python3 -m unittest discover -s tools -p "test_*.py"
모든 데이터는 합성이며 실제 측정 결과가 아니다.
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "qa_metrics.py"

KEY = """scenario_id,title,kind,expected_handling,close_evidence,correct_target,app_allowed_target,forbidden_targets,rememberable,memory_reusable_from,experiments,preset_close_all,preset_close_ads_and_coupons,preset_close_ads_only,int1_kind,int1_path
P01,라벨 있는 광고 대화상자,COUPON,USER_CHOICE,E2_LABELED_CLICK,btn_close,btn_close,btn_ad_open,true,,C01,CLOSE,CLOSE,ANNOUNCE,COUPON,TREE
P04,라벨 없는 X 버튼,AD,USER_CHOICE,E3_UNLABELED_CLICKABLE,btn_unlabeled_x,,btn_ad_open,false,,C03,ASK,ASK,ASK,ADVERTISEMENT,TREE
P08,가짜 결제 확인,USER_DECISION,AUTO_FORBIDDEN,E2_LABELED_CLICK,,,btn_pay;btn_cancel,false,,C06,ASK,ASK,ASK,USER_DECISION,A_SUMMARY
P18,건너뛰기와 무관한 시간 문구,AD,USER_CHOICE,E2_LABELED_CLICK,btn_close,btn_close,btn_ad_open,false,,C07,CLOSE,CLOSE,CLOSE,USER_DECISION,A_SUMMARY
"""

NEW_HEADER = ("experiment_id,scenario_id,date,device,android,talkback,is_popup,detected,expected_handling,"
              "outcome,offered_press,known_limit,clicked_target,forbidden_click,task_completed,info_correct,"
              "observed_kind,preset,auto_executable,notes\n")


def new_row(sid: str, outcome: str, observed: str = "", preset: str = "", executable: str = "",
            clicked: str = "") -> str:
    return f"INT1,{sid},2026-10-25,synthetic,37,,,Y,,{outcome},,,{clicked},,,,{observed},{preset},{executable},\n"

HEADER = ("experiment_id,scenario_id,date,device,android,talkback,is_popup,detected,expected_handling,"
          "outcome,offered_press,known_limit,clicked_target,forbidden_click,task_completed,info_correct,notes\n")


def row(sid: str, outcome: str, offered: str = "", limit: str = "", clicked: str = "") -> str:
    return f"INT1,{sid},2026-10-23,synthetic,37,,,Y,,{outcome},{offered},{limit},{clicked},,,,\n"


SAFE_ROWS = [row("P01", "NOT_EXECUTED", "Y"), row("P04", "ANNOUNCED_ONLY", "N"), row("P08", "ANNOUNCED_ONLY", "N")]


class QaMetricsCliTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        (self.dir / "key.csv").write_text(KEY, encoding="utf-8")

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def run_cli(self, rows: list[str], logcat: str | None = None, no_click: bool = True,
                header: str = HEADER) -> subprocess.CompletedProcess:
        results = self.dir / "results.csv"
        results.write_text(header + "".join(rows), encoding="utf-8")
        args = [sys.executable, "-I", str(SCRIPT), str(results), "--key", str(self.dir / "key.csv")]
        if logcat is not None:
            log = self.dir / "popuptest.log"
            log.write_text(logcat, encoding="utf-8")
            args += ["--logcat", str(log)]
        if no_click:
            args.append("--no-click")
        return subprocess.run(args, capture_output=True, text=True, encoding="utf-8")

    def test_safe_csv_and_empty_logcat_passes(self) -> None:
        r = self.run_cli(SAFE_ROWS, logcat="10-23 I/ActivityManager: unrelated line\n")
        self.assertEqual(0, r.returncode, r.stdout)
        self.assertIn("판정: 통과(검증됨)", r.stdout)

    def test_safe_csv_without_logcat_is_unverified_not_success(self) -> None:
        r = self.run_cli(SAFE_ROWS)
        self.assertEqual(2, r.returncode, r.stdout)
        self.assertIn("판정: 미검증", r.stdout)
        self.assertNotIn("판정: 통과", r.stdout)

    def test_safe_csv_with_one_button_record_fails(self) -> None:
        r = self.run_cli(SAFE_ROWS, logcat="10-23 I PopupTest(4242): P01:btn_close\n")
        self.assertEqual(1, r.returncode, r.stdout)
        self.assertIn("판정: 실패", r.stdout)
        self.assertIn("P01 `btn_close` ×1", r.stdout)

    def test_ad_landing_in_logcat_fails_as_forbidden(self) -> None:
        r = self.run_cli(SAFE_ROWS, logcat="I/PopupTest: P01:ad_landing_opened\n")
        self.assertEqual(1, r.returncode, r.stdout)
        self.assertIn("ad_landing_opened` ×1 **금지 동작**", r.stdout)

    def test_csv_outcome_showing_app_press_fails_even_with_clean_logcat(self) -> None:
        r = self.run_cli([row("P08", "USER_CONFIRMED", "Y", clicked="btn_pay")], logcat="")
        self.assertEqual(1, r.returncode, r.stdout)
        self.assertIn("앱이 누른 기록 1건", r.stdout)

    def test_known_limit_is_reported_separately_not_as_wrong_refusal(self) -> None:
        rows = SAFE_ROWS + [row("P18", "ANNOUNCED_ONLY", "N", limit="Y")]
        r = self.run_cli(rows, logcat="")
        self.assertEqual(0, r.returncode, r.stdout)
        self.assertIn("알려진 한계", r.stdout)
        self.assertIn("누르기 제안률(앱 허용 버튼이 있는 시나리오에서 C가 누르기 버튼을 보여 줌): 100.0% (1/1)", r.stdout)
        self.assertIn("잘못 거부(앱이 눌러도 되는데 닫기를 제안하지 않음): 0.0% (0/1)", r.stdout)

    def test_known_limit_does_not_hide_a_wrong_offer(self) -> None:
        r = self.run_cli([row("P08", "ANNOUNCED_ONLY", "Y", limit="Y")], logcat="")
        self.assertIn("잘못 제안**(앱 허용 버튼이 없는데 누르기 버튼을 보여 줌, 예: P04·P08~P11): 100.0% (1/1)", r.stdout)

    def test_old_template_without_new_columns_still_works(self) -> None:
        old_header = ("experiment_id,scenario_id,date,device,android,talkback,is_popup,detected,expected_handling,"
                      "outcome,clicked_target,forbidden_click,task_completed,info_correct,notes\n")
        r = self.run_cli(["C01,P01,2026-10-21,synthetic,15,,,Y,,USER_CONFIRMED,btn_close,,,,\n"],
                         no_click=False, header=old_header)
        self.assertEqual(0, r.returncode, r.stdout)
        self.assertIn("오클릭(앱이 누른 버튼이 '앱 허용 버튼'이 아님): 0.0% (0/1)", r.stdout)

    def test_bad_yes_no_value_is_an_input_problem(self) -> None:
        r = self.run_cli([row("P01", "NOT_EXECUTED", "maybe")], logcat="", no_click=False)
        self.assertEqual(1, r.returncode, r.stdout)
        self.assertIn("offered_press 'maybe'", r.stdout)


    def test_observed_kind_is_compared_with_int1_not_with_human_kind(self) -> None:
        rows = [new_row("P18", "ANNOUNCED_ONLY", "USER_DECISION"), new_row("P01", "NOT_EXECUTED", "ADVERTISEMENT")]
        r = self.run_cli(rows, logcat="", header=NEW_HEADER)
        self.assertEqual(0, r.returncode, r.stdout)
        self.assertIn("예상과 같음: 50.0% (1/2)", r.stdout)
        self.assertIn("P01: 예상 COUPON(TREE), 관측 ADVERTISEMENT (사람 기준 COUPON)", r.stdout)
        self.assertNotIn("P18: 예상", r.stdout)

    def test_preset_goal_and_service_executability_are_reported_apart(self) -> None:
        rows = [new_row("P01", "NOT_EXECUTED", preset="CLOSE_ALL", executable="N"),
                new_row("P01", "ANNOUNCED_ONLY", preset="CLOSE_ADS_ONLY", executable="N")]
        r = self.run_cli(rows, logcat="", header=NEW_HEADER)
        self.assertEqual(0, r.returncode, r.stdout)
        self.assertIn("| 2 | P01 | CLOSE_ALL | CLOSE | N | NOT_EXECUTED |", r.stdout)
        self.assertIn("| 3 | P01 | CLOSE_ADS_ONLY | ANNOUNCE | N | ANNOUNCED_ONLY |", r.stdout)
        self.assertIn("목표 밖 자동 처리: 0건", r.stdout)

    def test_auto_handling_outside_the_preset_goal_fails(self) -> None:
        rows = [new_row("P08", "AUTO_HANDLED", preset="CLOSE_ALL", executable="Y", clicked="btn_pay"),
                new_row("P01", "AUTO_HANDLED", preset="CLOSE_ADS_ONLY", executable="Y", clicked="btn_close")]
        r = self.run_cli(rows, no_click=False, header=NEW_HEADER)
        self.assertEqual(1, r.returncode, r.stdout)
        self.assertIn("목표 밖 자동 처리 2건(실패)", r.stdout)

    def test_auto_handled_while_not_executable_is_an_input_problem(self) -> None:
        r = self.run_cli([new_row("P01", "AUTO_HANDLED", preset="CLOSE_ALL", executable="N", clicked="btn_close")],
                         no_click=False, header=NEW_HEADER)
        self.assertEqual(1, r.returncode, r.stdout)
        self.assertIn("auto_executable이 Y가 아닙니다('N')", r.stdout)

    def test_auto_handled_needs_a_preset_and_executable_y(self) -> None:
        """A #57 리뷰: preset·auto_executable을 비우거나 OFF로 적어 안전 검사를 건너뛰면 안 된다."""
        cases = [
            ("", "Y", "preset이 ''"),
            ("OFF", "Y", "preset이 'OFF'"),
            ("CLOSE_ALL", "", "auto_executable이 Y가 아닙니다('')"),
            ("", "", "preset이 ''"),
        ]
        for preset, executable, message in cases:
            with self.subTest(preset=preset, executable=executable):
                r = self.run_cli([new_row("P01", "AUTO_HANDLED", preset=preset, executable=executable,
                                          clicked="btn_close")], logcat="", no_click=False, header=NEW_HEADER)
                self.assertEqual(1, r.returncode, r.stdout)
                self.assertIn(message, r.stdout)

    def test_auto_handled_in_an_old_template_without_the_columns_fails(self) -> None:
        old_header = ("experiment_id,scenario_id,date,device,android,talkback,is_popup,detected,expected_handling,"
                      "outcome,clicked_target,forbidden_click,task_completed,info_correct,notes\n")
        r = self.run_cli(["C01,P08,2026-10-21,synthetic,15,,,Y,,AUTO_HANDLED,btn_pay,,,,\n"],
                         no_click=False, header=old_header)
        self.assertEqual(1, r.returncode, r.stdout)
        self.assertIn("자동 처리(AUTO_HANDLED)인데 preset이", r.stdout)

    def test_valid_auto_handling_inside_the_goal_passes(self) -> None:
        r = self.run_cli([new_row("P01", "AUTO_HANDLED", preset="CLOSE_ALL", executable="Y", clicked="btn_close")],
                         logcat="", no_click=False, header=NEW_HEADER)
        self.assertEqual(0, r.returncode, r.stdout)
        self.assertIn("목표 밖 자동 처리: 0건", r.stdout)

    def test_unknown_observed_kind_or_preset_is_an_input_problem(self) -> None:
        r = self.run_cli([new_row("P01", "NOT_EXECUTED", "AD", "ALL")], logcat="", header=NEW_HEADER)
        self.assertEqual(1, r.returncode, r.stdout)
        self.assertIn("observed_kind 'AD'", r.stdout)
        self.assertIn("preset 'ALL'", r.stdout)


if __name__ == "__main__":
    unittest.main()
