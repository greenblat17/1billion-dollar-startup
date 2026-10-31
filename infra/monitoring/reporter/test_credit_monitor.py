import unittest
from decimal import Decimal

from credit_monitor import balance_message, remaining_credits


class CreditMonitorTest(unittest.TestCase):
    def test_balance_uses_account_credits_minus_usage(self) -> None:
        self.assertEqual(
            Decimal("4.99"),
            remaining_credits({"data": {"total_credits": 20, "total_usage": 15.01}}),
        )

    def test_alert_only_when_crossing_threshold(self) -> None:
        self.assertIsNotNone(balance_message(Decimal("4.99"), False))
        self.assertIsNone(balance_message(Decimal("4.99"), True))
        self.assertIsNone(balance_message(Decimal("5.00"), False))
        self.assertIsNotNone(balance_message(Decimal("5.00"), True))

    def test_invalid_response_never_becomes_zero_balance(self) -> None:
        with self.assertRaises(ValueError):
            remaining_credits({"data": {"total_credits": 20}})


if __name__ == "__main__":
    unittest.main()
