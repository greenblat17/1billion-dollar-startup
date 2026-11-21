"""Read-only SP-17C Topic Knowledge handoff for the future SP-20 review.

The product has not set sufficiency criteria for Known or Not Known. This
component preserves the evidence trail and explicitly declines a state change.
"""
from __future__ import annotations


async def topic_knowledge_advisory(backend, scope: str, topic_id: str) -> dict:
    if not topic_id:
        raise ValueError('topic_id_required')
    evidence = []
    offset = 0
    while True:
        page = await backend.learning_observations(scope, topic_id=topic_id, offset=offset)
        for row in page['items']:
            evidence.append({'observationId': row['id'], 'revision': row['revision'],
                             'lessonId': row['document'].get('lessonId'),
                             'outcome': row['document']['outcome'],
                             'quality': row['document']['quality']['status'],
                             'evidenceUsable': row['evidenceUsable'],
                             'sources': row['sources']})
        offset = page.get('nextOffset')
        if offset is None:
            break
    return {'schemaVersion': 1, 'topicId': topic_id, 'decision': 'insufficient_evidence',
            'value': None, 'rationale': 'Criteria for changing Topic Knowledge after regular lessons are not approved.',
            'evidence': evidence}
