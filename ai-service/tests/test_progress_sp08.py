import json
from types import SimpleNamespace
from unittest.mock import AsyncMock
import pytest
from app.trial_policy import TrialAnalyzer
from app.learning_results import MemoryEvaluation
from app.roadmap import RoadmapResult

@pytest.mark.parametrize('schema',[MemoryEvaluation,RoadmapResult])
def test_provider_schema_requires_all_fields_even_when_legacy_decoder_allows_omission(schema):
    def check(node):
        if isinstance(node,dict):
            if node.get('type')=='object':
                assert set(node['required'])==set(node['properties'])
                assert node['additionalProperties'] is False
            for value in node.values(): check(value)
        elif isinstance(node,list):
            for value in node: check(value)
    check(schema.response_format())

@pytest.mark.asyncio
@pytest.mark.parametrize('dimension,kind,quote',[('confidence','ai_observation','I am comfortable'),('autonomy','ai_observation','invented'),('fluency','ai_observation','I am comfortable')])
async def test_analysis_rejects_invented_quotes_inferred_confidence_and_transcript_fluency(dimension,kind,quote):
    source=dict(id='source',role='user',body='I am comfortable')
    result=dict(status='evaluated',facts=[],candidates=[],notes=[],observations=[dict(key='skill',dimension=dimension,
        content='Наблюдение',uncertainty='supported',sourceId='source',evidence=dict(kind=kind,quote=quote,
        support='independent',signal='strength',situation='work'))])
    model=SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps(result)))
    with pytest.raises(ValueError): await TrialAnalyzer(model).evaluate(SimpleNamespace(data={'sourceEvents':[source]}),{})
