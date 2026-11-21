"""SP-20 worker handoff: review never calculates a second score or Topic state."""
import pytest

from app.learning_engine import LearningEngine


class Priority:
    def __init__(self):
        self.triggers = []

    async def plan(self, scope, *, trigger):
        self.triggers.append((scope, trigger))
        return {'expectedRevision': 2, 'decision': {'trigger': trigger}}


@pytest.mark.asyncio
async def test_review_is_backend_owned_and_priority_uses_post_lesson_trigger():
    priority = Priority()
    engine = LearningEngine(None, None, roadmap_priority=priority)
    review = {'schema_version': 1, 'kind': 'lesson.review', 'target_id': 'lesson-1',
              'input_refs': {'transcriptRevision': 3, 'evidenceDigest': 'saved-evidence'}}
    assert await engine.compute('student', review) == {}
    assert priority.triggers == []
    result = await engine.compute('student', {'schema_version': 1, 'kind': 'roadmap.prioritize',
                                             'input_refs': {'trigger': 'post_lesson_review'}})
    assert result['decision']['trigger'] == 'post_lesson_review'
    assert priority.triggers == [('student', 'post_lesson_review')]
