import httpx
import pytest

from app.foundation_client import BackendFailure, FoundationClient


@pytest.mark.asyncio
async def test_catalog_passes_version_and_filters_without_student_scope():
    async def handle(request):
        assert request.url.path == '/internal/foundation/v1/catalog'
        assert request.headers['X-Internal-Token'] == 'test-token'
        assert request.headers['X-Execution-Scope'] == ''
        assert dict(request.url.params) == {
            'version': 'test-v1', 'skill': 'grammar', 'cefr': 'B1',
            'topicId': 'grammar-b1-second-conditional',
        }
        return httpx.Response(200, json={'ok': True, 'result': {'schemaVersion': 2, 'version': 'test-v1', 'topics': []}})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
        result = await FoundationClient('http://backend', 'test-token', http).catalog(
            version='test-v1', skill='grammar', cefr='B1', topic_id='grammar-b1-second-conditional')
        assert result['version'] == 'test-v1'


@pytest.mark.asyncio
@pytest.mark.parametrize('status,payload,expected', [
    (400, {'ok': False, 'error': 'catalog_not_published'}, 'catalog_not_published'),
    (200, {'ok': True, 'result': {'schemaVersion': 1}}, 'unsupported_catalog_schema'),
])
async def test_catalog_never_falls_back_to_unreviewed_content(status, payload, expected):
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(status, json=payload))) as http:
        with pytest.raises(BackendFailure) as error:
            await FoundationClient('http://backend', 'test-token', http).catalog()
        assert error.value.code == expected
