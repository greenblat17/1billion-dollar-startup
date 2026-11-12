"""Private audio objects; authoritative ownership and expiry remain in foundation."""
import asyncio
from app.diagnostics import event


class TrialAudioStore:
    def __init__(self, bucket: str, endpoint: str | None = None):
        import boto3
        self.bucket = bucket
        self.session = boto3.Session()
        self.client = self.session.client('s3', endpoint_url=endpoint or None)

    async def check_credentials(self):
        """Resolve the SDK credential chain before admitting a paid voice session."""
        from botocore.exceptions import NoCredentialsError
        credentials = await asyncio.to_thread(self.session.get_credentials)
        if credentials is None:
            raise NoCredentialsError()
        # Resolve refreshable credentials too; never log or return their values.
        await asyncio.to_thread(credentials.get_frozen_credentials)

    async def put(self, key: str, audio: bytes):
        await asyncio.to_thread(self.client.put_object, Bucket=self.bucket, Key=key, Body=audio,
                                ContentType='application/octet-stream')

    async def link(self, key: str):
        return await asyncio.to_thread(self.client.generate_presigned_url, 'get_object',
                                       Params={'Bucket': self.bucket, 'Key': key}, ExpiresIn=60)

    async def delete(self, key: str):
        def erase():
            # Also erase old versions: a delete marker alone is not erasure of recorded audio.
            pages = self.client.get_paginator('list_object_versions')
            for page in pages.paginate(Bucket=self.bucket, Prefix=key):
                versions = [{'Key': key, 'VersionId': item['VersionId']}
                            for item in page.get('Versions', []) + page.get('DeleteMarkers', [])
                            if item['Key'] == key]
                if versions:
                    result = self.client.delete_objects(Bucket=self.bucket, Delete={'Objects': versions})
                    if result.get('Errors'):
                        raise RuntimeError('audio_erasure_failed')
            self.client.delete_object(Bucket=self.bucket, Key=key)
        await asyncio.to_thread(erase)

    async def cleanup(self, backend):
        while True:
            try:
                result = await backend._request('POST', '/trial/audio_cleanup', '', json={})
                for item in result['objects']:
                    await self.delete(item['object_key'])
                    await backend._request('POST', '/trial/audio_deleted', '', json={'objectKey': item['object_key']})
            except asyncio.CancelledError:
                raise
            except Exception as error:
                event('trial_audio_cleanup_failed', error=error, stage='retention')
            await asyncio.sleep(60)
