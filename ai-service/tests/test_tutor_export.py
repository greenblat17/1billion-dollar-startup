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


@pytest.mark.asyncio
async def test_reviewed_synthetic_probes_can_be_excluded_but_telegram_cannot():
    probe = 'tutor:v1:synthetic-sp01-dev-21caf098f0d8'
    real = 'tutor:v1:tg-123'
    rows = FrozenRedis({probe: '{}', real: json.dumps({'learnerId': 'tg-123'})})
    with pytest.raises(RuntimeError, match='unknown tutor namespace'):
        await snapshot(rows)
    result = await snapshot(rows, [probe])
    assert [row['sourceKey'] for row in result] == [real]
    with pytest.raises(RuntimeError, match='only reviewed synthetic'):
        await snapshot(rows, [probe, real])
