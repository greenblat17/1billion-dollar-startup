# DEV audio storage

SeaweedFS 4.53, pinned by image digest. Single-node S3 on the DEV AI host, `/opt/speaky-storage`.
Compose persists master, filer metadata and volume data under `data/`; no host ports are published.
Only S3 listens on the private Docker network `speaky-storage`; internal management APIs bind to container loopback.
Memory is capped at 512 MiB. This is one disk without replication; persistent storage is not a backup.

`prepare.py` generates the `storage-admin` and `speaky-ai` credentials **on the host**.
The application identity has only Read/Write/List on `speaky-trial-audio-dev`, without Admin or ACL grants.
`secrets/s3.json` and `secrets/ai.env` are mode 0600 under a mode-0700 directory. Never print or commit them.
Re-running preparation preserves keys. The prepared `ai.env` is not automatically applied to AI or GitHub secrets.

## Install and verify

Copy `compose.yml`, `filer.toml`, `master.toml`, `prepare.py`, `verify.py` to `/opt/speaky-storage` on DEV, then run there:

```sh
python3 prepare.py
docker compose config --quiet
docker compose up -d
docker run --rm --network speaky-storage -e PYTHONPATH=/app \
  --mount type=bind,src=/opt/speaky-storage/secrets,dst=/secrets,readonly \
  --mount type=bind,src=/opt/speaky-storage/verify.py,dst=/verify.py,readonly \
  --entrypoint python ai-service:local /verify.py init
docker compose up -d --force-recreate
docker run --rm --network speaky-storage -e PYTHONPATH=/app \
  --mount type=bind,src=/opt/speaky-storage/secrets,dst=/secrets,readonly \
  --mount type=bind,src=/opt/speaky-storage/verify.py,dst=/verify.py,readonly \
  --entrypoint python ai-service:local /verify.py verify
```

The isolated helper uses the deployed AI image and its real `TrialAudioStore`, without model calls.
It checks persistence, put, signed get, version listing and erasure, anonymous rejection,
cross-bucket rejection and denial of public ACL changes. Synthetic test data is deleted on success.
Versioning is not enabled; these checks cover the null version, not multiple historical versions.
Container health is a process check, not proof of bucket permissions.

## Remaining integration

The current approved scope creates storage, bucket and application credentials only.
AI still needs the private Docker network and the values from `secrets/ai.env` in its durable deployment configuration.
The local AI deploy script now adds the `speaky-storage` network when the env contains exactly
`TRIAL_S3_ENDPOINT=http://speaky-storage:8333`; it checks that network before replacing AI.
This deploy-script change is not yet shipped. A temporary helper on both networks verified S3 DNS/auth and a default route.
Update the GitHub source of its env too; changing only the host `.env` would be lost on redeploy.
Use the newly created bucket name when applying the application configuration.
Do not expose internal endpoints through downloadable presigned URLs: browser downloads need a deliberate HTTPS endpoint/proxy design.
Retention/lifecycle, backups and a live voice/reconnect check remain separate work. No application redeploy or PROD change is part of this setup.

References: [SeaweedFS S3 credentials](https://github.com/seaweedfs/seaweedfs/wiki/S3-Credentials),
[release 4.53](https://github.com/seaweedfs/seaweedfs/releases/tag/4.53).
