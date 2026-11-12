"""The shipping AI app must not construct legacy stores or background loops."""
import subprocess
import sys


def test_runtime_starts_without_redis_and_exposes_only_foundation_routes():
    program = '''
import asyncio
import importlib.abc
import sys
class NoRedis(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if fullname == "redis" or fullname.startswith("redis."):
            raise AssertionError("shipping runtime imported Redis")
sys.meta_path.insert(0, NoRedis())
import httpx
from app.speaky_main import create_app
from app.config import Settings

async def check():
    settings = Settings.from_env()
    if True:
        app = create_app(settings)
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
                assert (await client.get('/health')).json()['runtime'] == 'speaky'
                assert (await client.post('/internal/foundation/compute', json={})).status_code == 401
                for path in ('/internal/calls/open', '/internal/vocabulary/list', '/internal/tutor/turn', '/internal/metrics'):
                    assert (await client.post(path, headers={'X-Internal-Token': 'local-test'}, json={})).status_code == 404
        assert {route.path for route in app.routes} == {'/health', '/internal/foundation/tutor/turn', '/internal/foundation/compute', '/internal/trial/followup', '/miniapp', '/miniapp/', '/miniapp/assets/{name}', '/miniapp/api', '/miniapp/voice'}
asyncio.run(check())
'''
    import os
    env = dict(os.environ, AI_INTERNAL_TOKEN='local-test', FOUNDATION_BACKEND_URL='http://127.0.0.1:1',
               OPENAI_API_KEY='local-test', REDIS_URL='redis://127.0.0.1:1')
    result = subprocess.run([sys.executable, '-c', program], env=env, capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stderr
