"""Run on DEV host. Generate credentials in place; never print or rotate existing keys."""
import json
import os
from pathlib import Path
import secrets


def main():
    root = Path('/opt/speaky-storage')
    os.umask(0o077)
    private = root / 'secrets'
    private.mkdir(parents=True, exist_ok=True)
    for name in ('master', 'volumes', 'filer'):
        (root / 'data' / name).mkdir(parents=True, exist_ok=True)
    config = private / 's3.json'
    if config.exists():
        assert (private / 'ai.env').exists() and (private / 'bucket').exists(), 'partial_storage_setup'
        print('storage_credentials=preserved')
        return
    bucket = 'speaky-trial-audio-dev'
    def identity(name, actions):
        return {'name': name, 'credentials': [{'accessKey': secrets.token_hex(10),
                'secretKey': secrets.token_urlsafe(36)}], 'actions': actions}
    admin = identity('storage-admin', ['Admin', 'Read', 'Write', 'List'])
    app = identity('speaky-ai', [f'{action}:{bucket}' for action in ('Read', 'Write', 'List')])
    with config.open('x') as target:
        json.dump({'identities': [admin, app]}, target)
    key = app['credentials'][0]
    with (private / 'ai.env').open('x') as target:
        target.write(f'AWS_ACCESS_KEY_ID={key["accessKey"]}\nAWS_SECRET_ACCESS_KEY={key["secretKey"]}\n'
                     f'AWS_DEFAULT_REGION=us-east-1\nTRIAL_AUDIO_BUCKET={bucket}\n'
                     'TRIAL_S3_ENDPOINT=http://speaky-storage:8333\n')
    (private / 'bucket').write_text(bucket)
    print('storage_credentials=created; application_env=prepared_not_applied')


if __name__ == '__main__':
    main()
