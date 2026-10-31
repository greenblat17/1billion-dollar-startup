"""Send the current Moscow-day DAU and seven-day WAU to the alert chat."""

import json
import logging
import os
import time
from datetime import date, datetime, time as clock_time, timedelta
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

from telegram_delivery import send_message


MOSCOW = ZoneInfo("Europe/Moscow")
STATE = Path("/state/last_sent_day")
LOG = logging.getLogger("daily-report")


def report_message(day: date, payload: dict) -> str:
    habits = payload.get("habits") or {}
    rows = habits.get("days") or []
    row = next((item for item in rows if item.get("day") == day.isoformat()), None)
    if not habits.get("healthy") or row is None or row.get("dau") is None or row.get("wau") is None:
        raise ValueError("Habit metrics unavailable")
    start = day - timedelta(days=6)
    return (
        f"Speaky · {day:%d.%m.%Y}, 23:00 МСК\n"
        f"DAU сегодня: {int(row['dau'])} (предварительно)\n"
        f"WAU {start:%d.%m}–{day:%d.%m}: {int(row['wau'])}\n"
        "Уникальные Telegram-чаты с завершённым ходом AI."
    )


def fetch_report(day: date) -> str:
    ai_url = os.environ["AI_SERVICE_BASE_URL"].rstrip("/")
    token = Path(os.environ["AI_INTERNAL_TOKEN_FILE"]).read_text().strip()
    query = urlencode({"from": (day - timedelta(days=6)).isoformat(), "to": day.isoformat()})
    request = Request(f"{ai_url}/internal/metrics/overview?{query}", headers={"Authorization": f"Bearer {token}"})
    with urlopen(request, timeout=20) as response:
        payload = json.load(response)
    return report_message(day, payload)


def last_sent_day() -> str:
    try:
        return STATE.read_text().strip()
    except FileNotFoundError:
        return ""


def record_sent(day: date) -> None:
    temporary = STATE.with_suffix(".tmp")
    temporary.write_text(day.isoformat())
    temporary.replace(STATE)


def is_due(now: datetime, sent_day: str) -> bool:
    return now.hour >= 23 and sent_day != now.date().isoformat()


def run() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    while True:
        now = datetime.now(MOSCOW)
        day = now.date()
        target = datetime.combine(day, clock_time(23), MOSCOW)
        if not is_due(now, last_sent_day()):
            next_target = target if now < target else target + timedelta(days=1)
            time.sleep(min(max((next_target - now).total_seconds(), 1), 60))
            continue
        try:
            try:
                message = fetch_report(day)
            except (OSError, ValueError, KeyError, TypeError) as error:
                LOG.warning("Habit metrics unavailable: %s", type(error).__name__)
                message = f"Speaky · {day:%d.%m.%Y}, 23:00 МСК\nDAU и WAU: данные недоступны."
            send_message(message)
            record_sent(day)
            LOG.info("Daily report delivered for %s", day.isoformat())
        except (OSError, ValueError, KeyError, RuntimeError) as error:
            LOG.warning("Daily report delivery failed: %s; retrying", type(error).__name__)
            time.sleep(300)


if __name__ == "__main__":
    run()
