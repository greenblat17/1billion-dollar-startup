import unittest
from datetime import date, datetime
from decimal import Decimal
from unittest.mock import patch

from daily_report import MOSCOW, current_balance_line, is_due, report_message


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

    def test_daily_balance_uses_fresh_credit_check(self) -> None:
        with patch("daily_report.fetch_balance", return_value=Decimal("4.99")) as fetch:
            self.assertRegex(current_balance_line(), r"Баланс OpenRouter \(\d{2}:\d{2} МСК\): \$4\.99\.")
        fetch.assert_called_once_with()

    def test_daily_balance_failure_is_not_reported_as_zero(self) -> None:
        with patch("daily_report.fetch_balance", side_effect=OSError):
            self.assertEqual("Баланс OpenRouter: данные недоступны.", current_balance_line())


if __name__ == "__main__":
    unittest.main()
