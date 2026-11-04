"""Send the approved Telegram announcement once per eligible chat ID on PROD CMP."""

import datetime
import json
import os
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path


CAMPAIGN_DIR = Path("/opt/speaking-coach/campaigns")
DB_PATH = CAMPAIGN_DIR / "2026-11-04-vocabulary-builder.sqlite3"
MESSAGE = (
    "Привет! Мы запустили <b>Vocabulary Builder</b> — новый режим, который помогает "
    "расширять словарный запас и использовать новые слова в речи.\n\n"
    "Словарик открывается командой <code>/words</code> 📚 Сейчас он пустой: "
    "ты можешь добавить слова сам или просто пообщаться со Speaky. После разговора "
    "она добавит подходящие слова из раздела Vocabulary твоего разбора.\n\n"
    "Выбери в словарике слова, которые хочешь потренировать, и начни голосовой урок. "
    "Speaky объяснит их значение, поможет составить свои фразы и в конце предложит "
    "короткую игру для повторения.\n\n"
    "А ещё мы обновили голос Speaky. Теперь она говорит живее, больше вовлечена "
    "в диалог и даже может с тобой поспорить 💙"
)
REPLY_MARKUP = {
    "keyboard": [
        [{"text": "🎙 Start call"}],
        [{"text": "🎭 Practice a situation"}],
        [{"text": "📚 Vocabulary Builder"}],
    ],
    "resize_keyboard": True,
    "is_persistent": True,
}


def bot_token() -> str:
    for line in Path("/opt/speaking-coach/.env").read_text().splitlines():
        if line.startswith("TELEGRAM_BOT_TOKEN="):
            value = line.partition("=")[2].strip().strip("\"'")
            if value:
                return value
    raise RuntimeError("TELEGRAM_BOT_TOKEN is unavailable")


def send(token: str, chat_id: int) -> str:
    body = urllib.parse.urlencode({
        "chat_id": chat_id,
        "text": MESSAGE,
        "parse_mode": "HTML",
        "disable_web_page_preview": "true",
        "reply_markup": json.dumps(REPLY_MARKUP, ensure_ascii=False),
    }).encode()
    request = urllib.request.Request(
        f"https://api.telegram.org/bot{token}/sendMessage", data=body,
    )
    for attempt in range(2):
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                result = json.load(response)
            return "sent" if result.get("ok") is True else "uncertain"
        except urllib.error.HTTPError as error:
            if error.code == 403:
                return "blocked"
            if error.code == 429 and attempt == 0:
                try:
                    retry_after = json.load(error).get("parameters", {}).get("retry_after", 1)
                except (ValueError, OSError):
                    retry_after = 1
                time.sleep(min(max(int(retry_after), 1), 30))
                continue
            return "failed" if error.code in (400, 429) else "uncertain"
        except (OSError, ValueError):
            return "uncertain"
    return "failed"


def main() -> None:
    if sys.argv[1:] == ["--status"]:
        if not DB_PATH.exists():
            print(json.dumps({"claimed": 0}))
            return
        with sqlite3.connect(f"file:{DB_PATH}?mode=ro", uri=True) as database:
            statuses = dict(database.execute(
                "SELECT status, COUNT(*) FROM delivery GROUP BY status",
            ))
        print(json.dumps({"claimed": sum(statuses.values()), **statuses}))
        return
    if sys.argv[1:]:
        raise RuntimeError("unknown option")
    recipients = sorted({int(line.strip()) for line in sys.stdin if line.strip()})
    if not recipients or any(recipient <= 0 for recipient in recipients):
        raise RuntimeError("Audience is empty or invalid")
    token = bot_token()
    os.umask(0o077)
    CAMPAIGN_DIR.mkdir(mode=0o700, exist_ok=True)
    with sqlite3.connect(DB_PATH) as database:
        database.execute("CREATE TABLE IF NOT EXISTS delivery ("
                         "chat_id INTEGER PRIMARY KEY, status TEXT NOT NULL, at_utc TEXT NOT NULL)")
        database.commit()
        counts = Counter()
        for recipient in recipients:
            now = datetime.datetime.now(datetime.timezone.utc).isoformat()
            claimed = database.execute(
                "INSERT OR IGNORE INTO delivery VALUES (?, 'pending', ?)", (recipient, now),
            ).rowcount
            database.commit()
            if not claimed:
                counts["already_claimed"] += 1
                continue
            status = send(token, recipient)
            database.execute("UPDATE delivery SET status = ?, at_utc = ? WHERE chat_id = ?",
                             (status, datetime.datetime.now(datetime.timezone.utc).isoformat(), recipient))
            database.commit()
            counts[status] += 1
            time.sleep(0.15)
    print(json.dumps({"audience": len(recipients), **counts}, ensure_ascii=False))


if __name__ == "__main__":
    main()
