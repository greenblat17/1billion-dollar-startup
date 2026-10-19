"""One calendar-day definition for overview, streak cohorts and Prometheus."""
from __future__ import annotations

import asyncio
import logging
import time
from collections import Counter
from datetime import date, timedelta

from app.metrics import MemoryMetricsStore, RedisMetricsStore, metrics_day, telegram_chat_id

from app.metrics_v2 import _telegram_chat_id

logger = logging.getLogger(__name__)
OFFSETS = (1, 3, 7, 30)


def calculate(active: dict[str, set[int]], since: str, start: date, end: date,
              today: date) -> dict:
    # end is the activity window end; retention is observed through yesterday.
    first: dict[int, str] = {}
    for day, users in sorted(active.items()):
        if day <= end.isoformat():
            for user in users:
                first.setdefault(user, day)
    rows = []
    day = start
    while day <= end:
        window = [(day - timedelta(days=n)).isoformat() for n in range(7)]
        counts = Counter(user for key in window for user in active.get(key, ()))
        complete = bool(since and window[-1] > since)
        tracked = bool(since and day.isoformat() > since)
        dau = len(active.get(day.isoformat(), ())) if tracked else None
        wau = len(counts) if complete else None
        groups = Counter("4_7" if n >= 4 else str(n) for n in counts.values())
        rows.append({"day": day.isoformat(), "dau": dau, "wau": wau,
                     "ratio": dau / wau if wau and dau is not None else None,
                     "complete": complete, "provisional": day >= today,
                     "distribution": [{"bucket": b, "users": groups[b] if complete else None}
                                      for b in ("1", "2", "3", "4_7")]})
        day += timedelta(days=1)
    monday = end - timedelta(days=end.weekday())

    def cohort(members: list[tuple[int, str]]) -> dict:
        rates = []
        for n in OFFSETS:
            eligible = returned = 0
            for user, first_day in members:
                target = date.fromisoformat(first_day) + timedelta(days=n)
                if target < today and since and target.isoformat() > since:
                    eligible += 1
                    returned += user in active.get(target.isoformat(), ())
            rates.append({"day": n, "eligible": eligible, "returned": returned})
        return {"size": len(members), "rates": rates}

    cohorts = []
    for n in range(8):
        week = monday - timedelta(weeks=n)
        until = week + timedelta(days=6)
        members = [(u, d) for u, d in first.items() if week.isoformat() <= d <= until.isoformat()]
        cohorts.append({"week": week.isoformat(),
                        "partialHistory": not since or week.isoformat() <= since,
                        **cohort(members)})
    return {"version": "completed_ai_turn_v1", "timezone": "Europe/Moscow",
            "asOf": end.isoformat(), "observedThrough": (today - timedelta(days=1)).isoformat(),
            "historySince": since, "days": rows, "cohorts": cohorts,
            "periodRetention": cohort([(u, d) for u, d in first.items()
                                       if start.isoformat() <= d <= end.isoformat()])["rates"]}


class HabitAnalytics:
    """Refresh outside the voice path; scrapes only read a bounded cached snapshot."""
    def __init__(self, metrics):
        self.metrics = metrics
        self.active: dict[str, set[int]] = {}
        self.since = ""
        self.updated = 0.0
        self.healthy = False
        self._attempted = 0.0
        self._lock = asyncio.Lock()
        self.export: dict | None = None

    async def refresh(self) -> None:
        async with self._lock:
            if time.monotonic() - self._attempted < 60:
                return
            self._attempted = time.monotonic()
            try:
                if isinstance(self.metrics, RedisMetricsStore):
                    redis = self.metrics.redis
                    keys = sorted([str(k) async for k in redis.scan_iter(match="metrics:dau:*", count=200)])
                    raw = {}
                    for offset in range(0, len(keys), 200):
                        batch = keys[offset:offset + 200]
                        async with redis.pipeline() as pipe:
                            for key in batch:
                                pipe.smembers(key)
                            values = await pipe.execute()
                        for key, users in zip(batch, values, strict=True):
                            raw[key.removeprefix("metrics:dau:")] = users
                elif isinstance(self.metrics, MemoryMetricsStore):
                    raw = {d: set(users) for d, users in self.metrics._dau.items()}
                else:
                    raise TypeError("Unsupported metrics store")
                active = {}
                for day, users in raw.items():
                    try:
                        date.fromisoformat(day)
                    except ValueError:
                        continue
                    active[day] = {chat for u in users if (chat := telegram_chat_id(_telegram_chat_id(str(u)))) is not None}
                today = date.fromisoformat(metrics_day(time.time()))
                self.active = {d: users for d, users in active.items() if d <= today.isoformat()}
                self.since = min(self.active, default="")
                self.updated = time.time()
                self.healthy = True
                self.export = self.report(today - timedelta(days=30), today - timedelta(days=1), today)
            except Exception:
                self.healthy = False
                logger.exception("Habit analytics refresh failed")

    def report(self, start: date, end: date, today: date | None = None) -> dict:
        today = today or date.fromisoformat(metrics_day(time.time()))
        return {**calculate(self.active, self.since, start, end, today),
                "healthy": self.healthy and time.time() - self.updated < 180, "updatedAt": self.updated}

    async def run(self) -> None:
        while True:
            await self.refresh()
            await asyncio.sleep(60)

    def prometheus(self) -> str:
        lines = [f"speaking_habit_source_healthy {int(self.healthy)}",
                 f"speaking_habit_updated_seconds {self.updated}"]
        if not self.healthy or not self.export:
            return "\n".join(lines) + "\n"
        for row in self.export["days"]:
            label = f'day="{row["day"]}"'
            lines.append(f'speaking_habit_window_complete{{{label}}} {int(row["complete"])}')
            for field in ("dau", "wau", "ratio"):
                if row[field] is not None:
                    lines.append(f'speaking_habit_{field}{{{label}}} {row[field]}')
        latest = self.export["days"][-1]
        for field in ("dau", "wau", "ratio"):
            if latest[field] is not None:
                lines.append(f'speaking_habit_current_{field} {latest[field]}')
        for group in latest["distribution"]:
            if group["users"] is not None:
                lines.append(f'speaking_habit_active_days{{bucket="{group["bucket"]}"}} {group["users"]}')
        lines.append(f'speaking_habit_as_of_seconds {date.fromisoformat(latest["day"]).toordinal() * 86400 - date(1970, 1, 1).toordinal() * 86400}')
        for row in self.export["cohorts"]:
            label = f'week="{row["week"]}"'
            lines.append(f'speaking_habit_cohort_size{{{label}}} {row["size"]}')
            lines.append(f'speaking_habit_cohort_partial_history{{{label}}} {int(row["partialHistory"])}')
            for rate in row["rates"]:
                for field in ("eligible", "returned"):
                    lines.append(f'speaking_habit_retention_{field}{{{label},offset="{rate["day"]}"}} {rate[field]}')
        return "\n".join(lines) + "\n"
