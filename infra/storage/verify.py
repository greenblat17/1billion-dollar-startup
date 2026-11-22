"""Run in an isolated AI-image helper on the storage network. Never prints secrets."""
import asyncio
import json
import os
from pathlib import Path
import sys
import time
import urllib.request
from uuid import uuid4

import boto3
from botocore import UNSIGNED
from botocore.config import Config
from botocore.exceptions import ClientError, EndpointConnectionError, ReadTimeoutError
from app.trial_audio import TrialAudioStore

ENDPOINT = 'http://speaky-storage:8333'
BUCKET = Path('/secrets/bucket').read_text()
IDENTITIES = json.loads(Path('/secrets/s3.json').read_text())['identities']
KEY = 'trial/storage-readiness/persistence.bin'
PAYLOAD = b'speaky storage readiness: synthetic data, not lesson audio'


def client(identity=None):
    credentials = identity['credentials'][0] if identity else {}
    return boto3.client('s3', endpoint_url=ENDPOINT, region_name='us-east-1',
        aws_access_key_id=credentials.get('accessKey', 'unused'),
        aws_secret_access_key=credentials.get('secretKey', 'unused'),
        config=Config(signature_version='s3v4' if identity else UNSIGNED,
                      connect_timeout=3, read_timeout=30, retries={'max_attempts': 0}))


def denied(operation):
    try:
        operation()
    except ClientError as error:
        assert error.response['ResponseMetadata']['HTTPStatusCode'] == 403
    else:
        raise AssertionError('unexpected_storage_access')


def main():
    admin, app = map(client, IDENTITIES)
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        try:
            admin.list_buckets()
            break
        except (EndpointConnectionError, ReadTimeoutError):
            time.sleep(1)
    else:
        raise RuntimeError('storage_not_ready')
    if sys.argv[1] == 'init':
        names = [item['Name'] for item in admin.list_buckets()['Buckets']]
        if BUCKET not in names:
            admin.create_bucket(Bucket=BUCKET)
        # Data survives container replacement because both filer metadata and volumes persist.
        app.put_object(Bucket=BUCKET, Key=KEY, Body=PAYLOAD)
        print('private_bucket_and_scoped_user=created; persistence_probe=written')
        return
    deadline = time.monotonic() + 45
    while True:
        try:
            assert app.get_object(Bucket=BUCKET, Key=KEY)['Body'].read() == PAYLOAD
            break
        except (EndpointConnectionError, ReadTimeoutError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(1)
    print('persistence_after_recreate=passed', flush=True)
    denied(lambda: client().get_object(Bucket=BUCKET, Key=KEY))
    denied(lambda: client().list_objects_v2(Bucket=BUCKET))
    denied(lambda: app.create_bucket(Bucket='speaky-forbidden-probe'))
    denied(lambda: app.put_object(Bucket='speaky-forbidden-probe', Key=KEY, Body=PAYLOAD))
    denied(lambda: app.put_bucket_acl(Bucket=BUCKET, ACL='public-read'))
    denied(lambda: app.put_object_acl(Bucket=BUCKET, Key=KEY, ACL='public-read'))
    other = 'speaky-permission-probe-' + uuid4().hex[:12]
    print('cross_bucket_check=started', flush=True)
    admin.create_bucket(Bucket=other)
    try:
        admin.put_object(Bucket=other, Key=KEY, Body=PAYLOAD)
        denied(lambda: app.get_object(Bucket=other, Key=KEY))
        denied(lambda: app.list_objects_v2(Bucket=other))
    finally:
        admin.delete_object(Bucket=other, Key=KEY)
        admin.delete_bucket(Bucket=other)
    credentials = IDENTITIES[1]['credentials'][0]
    print('application_storage_check=started', flush=True)
    os.environ['AWS_ACCESS_KEY_ID'] = credentials['accessKey']
    os.environ['AWS_SECRET_ACCESS_KEY'] = credentials['secretKey']
    os.environ['AWS_DEFAULT_REGION'] = 'us-east-1'
    store = TrialAudioStore(BUCKET, ENDPOINT)
    asyncio.run(store.put(KEY, PAYLOAD))
    with urllib.request.urlopen(asyncio.run(store.link(KEY)), timeout=5) as response:
        assert response.read() == PAYLOAD
    versions = app.list_object_versions(Bucket=BUCKET, Prefix=KEY)
    assert versions.get('Versions'), 'version_listing_required_for_application_erasure'
    asyncio.run(store.delete(KEY))
    versions = app.list_object_versions(Bucket=BUCKET, Prefix=KEY)
    assert not versions.get('Versions') and not versions.get('DeleteMarkers')
    print('application_put_signed_get_version_list_delete=passed')
    print('anonymous_read_list=denied; other_bucket_create_write_read_list=denied; public_acl=denied')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('storage_verification_failed=' + type(error).__name__)
        if isinstance(error, ClientError):
            print('operation=' + error.operation_name + '; code=' + error.response['Error']['Code']
                  + '; status=' + str(error.response['ResponseMetadata']['HTTPStatusCode']))
        sys.exit(1)
