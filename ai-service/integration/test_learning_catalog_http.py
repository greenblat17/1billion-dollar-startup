"""Real Python → Ktor → PostgreSQL contract; harness publishes a synthetic test release only."""
import os

import httpx
import pytest

from app.foundation_client import BackendFailure, FoundationClient


@pytest.mark.asyncio
async def test_published_catalog_filters_and_version_pinned_target():
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN'], http)
        catalog = await backend.catalog()
        assert catalog['version'] == 'synthetic-catalog-v1'
        assert len(catalog['skills']) == 6
        assert len(catalog['problems']) == 9
        assert {t['cefr'] for t in catalog['topics']} == {'A1', 'A2', 'B1', 'B2', 'C1'}
        selected = await backend.catalog(version=catalog['version'], skill='vocabulary', cefr='B1')
        assert selected['topics']
        assert all(t['skillId'] == 'vocabulary' and t['cefr'] == 'B1' for t in selected['topics'])
        closure = await backend.catalog(version=catalog['version'], topic_id='grammar-b1-second-conditional')
        ids = [t['id'] for t in closure['topics']]
        assert ids == ['grammar-b1-second-conditional']
        assert all('prerequisites' not in t for t in catalog['topics'])
        assert closure['digest'] == catalog['digest']
        with pytest.raises(BackendFailure) as error:
            await backend.catalog(version='missing-release')
        assert error.value.code == 'catalog_not_published'


@pytest.mark.asyncio
async def test_catalog_requires_service_auth_but_no_student_identity():
    async with httpx.AsyncClient() as http:
        response = await http.get(os.environ['SP00_TEST_URL'] + '/internal/foundation/v1/catalog')
        assert response.status_code == 401
        wrong = FoundationClient(os.environ['SP00_TEST_URL'], 'incorrect-token', http)
        with pytest.raises(BackendFailure) as error:
            await wrong.catalog()
        assert error.value.code == 'unauthorized'
