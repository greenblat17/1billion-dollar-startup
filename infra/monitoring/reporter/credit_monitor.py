"""Alert once when OpenRouter account credits cross below five USD."""

import json
import logging
import os
import time
from decimal import Decimal, InvalidOperation
from pathlib import Path
from urllib.request import Request, urlopen

from telegram_delivery import send_message


CREDITS_URL = "https://openrouter.ai/api/v1/credits"
THRESHOLD = Decimal("5")
POLL_SECONDS = 5 * 60
STATE = Path("/state/status.json")
LOG = logging.getLogger("openrouter-credit-monitor")


def remaining_credits(payload: dict) -> Decimal:
    if not isinstance(payload, dict):
        raise ValueError("Invalid OpenRouter credit response")
    data = payload.get("data") or {}
    if not isinstance(data, dict):
        raise ValueError("Invalid OpenRouter credit response")
    try:
        purchased = Decimal(str(data["total_credits"]))
        used = Decimal(str(data["total_usage"]))
    except (KeyError, TypeError, InvalidOperation) as error:
        raise ValueError("Invalid OpenRouter credit response") from error
    if not purchased.is_finite() or not used.is_finite():
        raise ValueError("Invalid OpenRouter credit response")
    return purchased - used


def fetch_balance() -> Decimal:
    request = Request(CREDITS_URL, headers={
        "Authorization": f"Bearer {os.environ['OPENROUTER_MANAGEMENT_KEY']}",
    })
    with urlopen(request, timeout=20) as response:
        payload = json.load(response)
    return remaining_credits(payload)


def balance_message(balance: Decimal, was_low: bool) -> str | None:
    if balance < THRESHOLD and not was_low:
        return f"⚠️ OpenRouter: осталось ${balance:.2f}. Баланс ниже $5."
    if balance >= THRESHOLD and was_low:
        return f"✅ OpenRouter: баланс восстановился до ${balance:.2f}."
    return None


def read_state() -> dict[str, bool]:
    try:
        data = json.loads(STATE.read_text())
        low = data["low_alerted"]
        check_error = data["check_error_alerted"]
        if not isinstance(low, bool) or not isinstance(check_error, bool):
            raise ValueError("Invalid credit monitor state")
        return {"low_alerted": low, "check_error_alerted": check_error}
    except (FileNotFoundError, ValueError, KeyError, TypeError):
        return {"low_alerted": False, "check_error_alerted": False}


def save_state(state: dict[str, bool]) -> None:
    temporary = STATE.with_suffix(".tmp")
    temporary.write_text(json.dumps(state))
    temporary.replace(STATE)


def deliver(message: str, state: dict[str, bool], field: str, value: bool) -> None:
    send_message(message)
    state[field] = value
    save_state(state)


def run() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    state = read_state()
    failed_checks = 0
    while True:
        try:
            balance = fetch_balance()
        except (OSError, ValueError, KeyError) as error:
            failed_checks += 1
            LOG.warning("OpenRouter credit check failed: %s", type(error).__name__)
            if failed_checks >= 3 and not state["check_error_alerted"]:
                try:
                    deliver("⚠️ OpenRouter: не удаётся проверить баланс три раза подряд.", state,
                            "check_error_alerted", True)
                except (OSError, RuntimeError, ValueError) as send_error:
                    LOG.warning("Credit-check alert delivery failed: %s", type(send_error).__name__)
        else:
            failed_checks = 0
            try:
                if state["check_error_alerted"]:
                    deliver("✅ OpenRouter: проверка баланса восстановлена.", state,
                            "check_error_alerted", False)
                message = balance_message(balance, state["low_alerted"])
                if message is not None:
                    deliver(message, state, "low_alerted", balance < THRESHOLD)
            except (OSError, RuntimeError, ValueError) as error:
                LOG.warning("Credit alert delivery failed: %s", type(error).__name__)
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    run()
