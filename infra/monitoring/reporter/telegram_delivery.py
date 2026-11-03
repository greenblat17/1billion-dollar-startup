"""Send plain operational messages to the configured Telegram chat."""

import json
import os
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def send_message(message: str) -> None:
    token = os.environ["TELEGRAM_ALERT_BOT_TOKEN"]
    body = urlencode({"chat_id": os.environ["TELEGRAM_ALERT_CHAT_ID"], "text": message}).encode()
    request = Request(f"https://api.telegram.org/bot{token}/sendMessage", data=body)
    with urlopen(request, timeout=20) as response:
        if not json.load(response).get("ok"):
            raise RuntimeError("Telegram rejected operational message")
