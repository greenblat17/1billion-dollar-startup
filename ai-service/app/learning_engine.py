"""Compute/apply boundary. Model adapters are injected; persistence belongs to the backend."""
from __future__ import annotations

from typing import Protocol
import hashlib

from app.context_builder import ContextBuilder, TutorContext
from app.foundation_client import FoundationClient


class LessonAnalyzer(Protocol):
    async def evaluate(self, context: TutorContext, inputs: dict) -> dict: ...


class LessonPlanner(Protocol):
    async def prepare(self, context: TutorContext, inputs: dict) -> dict: ...


class PersonalCandidateExtractor:
    """Reuse the validated extractor; its output is always pending, never confirmed memory."""
    def __init__(self, model):
        self.model = model

    async def extract(self, context: TutorContext, inputs: dict) -> dict:
        source = next((event for event in context.data['sourceEvents'] if event['id'] == inputs['sourceId']), None)
        if source is None:
            raise ValueError('source_expired')
        person = await self.model.update_person_once({}, [source['body']])
        candidates = [{'key': key, 'value': person[key]} for key in ('name', 'work', 'leisure') if person.get(key)]
        candidates += [{'key': 'fact_' + hashlib.sha256(value.encode()).hexdigest()[:24].translate(str.maketrans('0123456789', 'ghijklmnop')), 'value': value}
                       for value in person.get('facts', [])]
        return {'candidates': candidates}


class LearningEngine:
    def __init__(self, backend: FoundationClient, contexts: ContextBuilder,
                 analyzer: LessonAnalyzer | None = None, planner: LessonPlanner | None = None,
                 extractor: PersonalCandidateExtractor | None = None, roadmap: LessonPlanner | None = None):
        self.backend, self.contexts = backend, contexts
        self.analyzer, self.planner = analyzer, planner
        self.extractor = extractor
        self.roadmap = roadmap

    async def compute(self, scope: str, job: dict) -> dict:
        if job.get('schema_version') != 1:
            raise ValueError('unsupported_job_version')
        context = await self.contexts.build(scope, 'learning', job.get('target_id'))
        if job['kind'] == 'roadmap.prepare' and self.roadmap is not None:
            return await self.roadmap.prepare(context, job['input_refs'])
        if job['kind'] == 'memory.extract' and self.extractor is not None:
            return await self.extractor.extract(context, job['input_refs'])
        if job['kind'] == 'lesson.evaluate' and self.analyzer is not None:
            return await self.analyzer.evaluate(context, job['input_refs'])
        if job['kind'] == 'lesson.prepare' and self.planner is not None:
            return await self.planner.prepare(context, job['input_refs'])
        raise ValueError('handler_unavailable')

    async def apply(self, scope: str, job: dict, result: dict) -> dict:
        return await self.backend.command(scope, f"job:{job['id']}:{job['fence']}:apply", 'job.apply', {
            'jobId': job['id'], 'fence': job['fence'], 'result': result,
        })
