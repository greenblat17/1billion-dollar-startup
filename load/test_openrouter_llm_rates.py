import unittest
from datetime import datetime, timezone

from openrouter_llm_rates import aggregate, generation_rows


class OpenRouterRatesTest(unittest.TestCase):
    def test_tpm_includes_all_llm_tokens_and_tps_excludes_missing_generation_time(self):
        rows = [
            {"created_at__minute": "2026-10-14 05:52:00", "request_count": "1",
             "tokens_prompt": "100", "tokens_completion": "20", "avg_generation_time": 1000},
            {"created_at__minute": "2026-10-14 05:52:00", "request_count": "1",
             "tokens_prompt": "50", "tokens_completion": "30", "avg_generation_time": 2000},
            {"created_at__minute": "2026-10-14 05:52:00", "request_count": "1",
             "tokens_prompt": "10", "tokens_completion": "5", "avg_generation_time": None},
        ]
        self.assertEqual(aggregate(rows), [{
            "minute_utc": "2026-10-14T05:52:00Z", "calls": 3, "timed_calls": 2,
            "prompt_tokens": 160, "completion_tokens": 55, "timed_completion_tokens": 50,
            "generation_ms": 3000, "tpm": 215, "tps_milli": 16667,
        }])

    def test_truncated_generation_query_is_split_without_losing_minutes(self):
        class StubAnalytics:
            def query(self, body):
                start = body["time_range"]["start"]
                end = body["time_range"]["end"]
                if start.endswith("05:00:00Z") and end.endswith("05:02:00Z"):
                    return {"truncated": True, "data": []}
                return {"truncated": False, "data": [{"created_at__minute": start.replace("T", " ")[:19]}]}

        start = datetime(2026, 10, 14, 5, 0, tzinfo=timezone.utc)
        end = datetime(2026, 10, 14, 5, 2, tzinfo=timezone.utc)
        rows = generation_rows(StubAnalytics(), start, end, ["openai/gpt-test"])
        self.assertEqual([row["created_at__minute"] for row in rows],
                         ["2026-10-14 05:00:00", "2026-10-14 05:01:00"])


if __name__ == "__main__":
    unittest.main()
