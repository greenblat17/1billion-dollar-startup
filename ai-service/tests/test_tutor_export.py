import hashlib
import json
import pytest
from scripts.export_tutor_state import snapshot


class FrozenRedis:
    def __init__(self, rows):
        self.rows = rows

    async def scan_iter(self, **_):
        for key in self.rows:
            yield key

    async def get(self, key):
        return self.rows[key]


@pytest.mark.asyncio
async def test_export_reads_only_tutor_state_and_preserves_exact_source_bytes():
    raw = json.dumps({'learnerId': 'tg-123', 'revision': 7, 'personal': {'name': 'Саша'}}, ensure_ascii=False)
    entries = await snapshot(FrozenRedis({'tutor:v1:tg-123': raw, 'tutor:v1:lock:tg-123': 'lease'}))
    assert entries == [{'sourceKey': 'tutor:v1:tg-123', 'subject': '123', 'stateJson': raw,
                        'sha256': hashlib.sha256(raw.encode()).hexdigest()}]


@pytest.mark.asyncio
async def test_export_rejects_conflicting_telegram_aliases():
    rows = {'tutor:v1:' + identity: json.dumps({'learnerId': identity})
            for identity in ('tg-123', 'tg-ChatId(chatId=123)')}
    with pytest.raises(RuntimeError, match='duplicate identity'):
        await snapshot(FrozenRedis(rows))
