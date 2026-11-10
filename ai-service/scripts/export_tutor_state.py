"""Offline export of a stopped text tutor; never reads legacy voice/vocabulary keys."""
import argparse
import asyncio
import hashlib
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from redis.asyncio import Redis

KEY = re.compile(r'tutor:v1:(tg-(?:[1-9][0-9]*|ChatId\(chatId=[1-9][0-9]*\)))\Z')


async def snapshot(redis, excluded_keys=()):
    keys = sorted([key async for key in redis.scan_iter(match='tutor:v1:*') if not key.startswith('tutor:v1:lock:')])
    entries = []
    for key in keys:
        if key in excluded_keys:
            if not re.fullmatch(r'tutor:v1:synthetic-sp01-dev-[0-9a-f]{12}', key):
                raise RuntimeError('only reviewed synthetic DEV probes may be excluded')
            continue
        if not KEY.fullmatch(key):
            raise RuntimeError('unknown tutor namespace; manual review required')
        raw = await redis.get(key)
        if raw is None:
            raise RuntimeError('source changed during export')
        state = json.loads(raw)
        if 'tutor:v1:' + state['learnerId'] != key:
            raise RuntimeError('source identity mismatch')
        subject = re.search(r'[1-9][0-9]*', state['learnerId']).group()
        entries.append(dict(sourceKey=key, subject=subject, stateJson=raw,
                            sha256=hashlib.sha256(raw.encode()).hexdigest()))
    if len({e['subject'] for e in entries}) != len(entries):
        raise RuntimeError('duplicate identity aliases; reconcile before cutover')
    return entries


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-frozen-at', required=True, help='UTC instant after stopping old tutor writers')
    parser.add_argument('--output', required=True)
    parser.add_argument('--exclude-key', action='append', default=[], help='Exact reviewed synthetic DEV probe key; never a Telegram identity')
    args = parser.parse_args()
    frozen = datetime.fromisoformat(args.source_frozen_at.replace('Z', '+00:00'))
    if frozen.tzinfo is None or frozen > datetime.now(timezone.utc):
        raise RuntimeError('invalid freeze instant')
    redis = Redis.from_url(os.environ['REDIS_URL'], decode_responses=True)
    try:
        entries = await snapshot(redis, args.exclude_key)
        if entries != await snapshot(redis, args.exclude_key):
            raise RuntimeError('source is not frozen')
        manifest = dict(schemaVersion=1, sourceFrozenAt=frozen.isoformat(), entries=entries)
        descriptor = os.open(Path(args.output), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w') as stream:
            json.dump(manifest, stream, ensure_ascii=False)
        print(f'Exported {len(entries)} tutor states; no source data changed')
    finally:
        await redis.aclose()


if __name__ == '__main__':
    asyncio.run(main())
