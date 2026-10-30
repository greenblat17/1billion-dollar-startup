import unittest
from datetime import date, datetime

from daily_report import MOSCOW, is_due, report_message


class DailyReportTest(unittest.TestCase):
    def test_today_and_trailing_week_are_labeled(self) -> None:
        message = report_message(
            date(2026, 10, 30),
            {"habits": {"healthy": True, "days": [{"day": "2026-10-30", "dau": 12, "wau": 37}]}},
        )
        self.assertIn("DAU сегодня: 12 (предварительно)", message)
        self.assertIn("WAU 24.10–30.10: 37", message)

    def test_unhealthy_source_is_not_reported_as_zero(self) -> None:
        with self.assertRaises(ValueError):
            report_message(
                date(2026, 10, 30),
                {"habits": {"healthy": False, "days": [{"day": "2026-10-30", "dau": 0, "wau": 0}]}},
            )

    def test_due_once_after_2300_moscow(self) -> None:
        before = datetime(2026, 10, 30, 22, 59, tzinfo=MOSCOW)
        due = datetime(2026, 10, 30, 23, 0, tzinfo=MOSCOW)
        self.assertFalse(is_due(before, ""))
        self.assertTrue(is_due(due, ""))
        self.assertFalse(is_due(due, "2026-10-30"))


if __name__ == "__main__":
    unittest.main()
