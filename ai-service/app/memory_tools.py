"""Native memory tools shared by Telegram and server-owned Realtime."""
import hashlib
import json
from typing import Literal
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field


class MemoryArgs(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)


class SearchMemory(MemoryArgs):
    query: str = Field(max_length=200)
    offset: int = Field(ge=0, le=10000)


class FactRef(MemoryArgs):
    category: Literal['personal', 'learning']
    key: str = Field(pattern=r'^[a-z][a-z_]{0,49}$')
    expectedVersion: int = Field(ge=0)
    evidence: str = Field(min_length=1, max_length=12000)


class ForgetTarget(MemoryArgs):
    category: Literal['personal', 'learning']
    key: str = Field(pattern=r'^[a-z][a-z_]{0,49}$')
    expectedVersion: int = Field(ge=1)


class ForgetMemory(MemoryArgs):
    targets: list[ForgetTarget] = Field(min_length=1, max_length=20)
    evidence: str = Field(min_length=1, max_length=12000)


class RecordMemory(FactRef):
    value: str = Field(min_length=1, max_length=1000)
    kind: Literal['user_report', 'self_assessment']


class MemoryPolicy(FactRef):
    mentionPolicy: Literal['natural', 'context_only']
    validUntil: str | None


class Candidate(MemoryArgs):
    candidateId: str
    expectedVersion: int = Field(ge=1)
    evidence: str = Field(min_length=1, max_length=12000)


class Review(MemoryArgs):
    key: str
    expectedVersion: int = Field(ge=0)
    evidence: str = Field(min_length=1, max_length=12000)
    status: Literal['disputed', 'resolved']
    explanation: str = Field(min_length=1, max_length=1000)


MEMORY_MODELS = {
    'search_memory': (SearchMemory, 'memory.search', 'Read current facts, never candidates. Literal query, or empty to browse; paginate with nextOffset.'),
    'discuss_memory': (SearchMemory, 'memory.discuss', 'Read unconfirmed candidates ONLY for a topic already naturally discussed. Nonempty query required. Not a fact lookup.'),
    'record_memory': (RecordMemory, 'memory.record', 'Save or correct an explicit user fact using its existing key/version and verbatim evidence. Never save an inference as a fact.'),
    'set_memory_policy': (MemoryPolicy, 'memory.policy', 'Set explicit do-not-discuss restriction or stated expiry. context_only permits indirect preparation, not direct mention. validUntil is ISO timestamp or null.'),
    'review_observation': (Review, 'memory.review', 'Record learner disagreement as disputed with explanation; resolve only on explicit clarification. key is the observation_key.'),
    'confirm_memory': (Candidate, 'memory.confirm', 'Confirm a previously discussed candidate from a new explicit learner statement.'),
    'reject_memory': (Candidate, 'memory.reject_requested', 'Reject an incorrect candidate on explicit learner correction.'),
    'forget_memory': (ForgetMemory, 'memory.forget_requested', 'Explicit deletion only. Clears derived copies and revokes this conversation context; do not continue using old memory.'),
}
MEMORY_TOOLS = [{'type': 'function', 'function': {'name': name, 'description': desc,
    'parameters': model.model_json_schema(), 'strict': True}}
    for name, (model, _, desc) in MEMORY_MODELS.items()]


async def execute_memory(backend, scope, request_id, source, name, arguments):
    model, action, _ = MEMORY_MODELS[name]
    args = model.model_validate(arguments).model_dump()
    if name in ('search_memory', 'discuss_memory'):
        return await backend.command(scope, uuid4().hex, action, args)
    if not source or args['evidence'] not in source['body']:
        raise ValueError('invalid_evidence')
    identity = hashlib.sha256(json.dumps([request_id, name, arguments], sort_keys=True).encode()).hexdigest()
    return await backend.command(scope, identity, action, {'sourceId': source['id'], **args})
