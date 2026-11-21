import pytest

from app.realtime_voice.lesson_flow import goodbye_played
from app.trial_sp24 import Stage


@pytest.mark.parametrize(
    ('v55', 'sp24', 'checkpoint', 'complete', 'finishing_reply', 'has_audio_and_text', 'incomplete', 'ends'),
    [
        (False, False, None, True, True, False, False, True),
        (False, False, None, False, True, True, False, False),
        (True, False, None, True, True, False, False, False),
        (True, False, None, True, True, True, False, True),
        (True, True, Stage.GOODBYE, True, False, True, False, False),
        (True, True, Stage.REGULAR_OFFER, True, True, True, False, False),
        (True, True, Stage.GOODBYE, True, True, True, True, False),
        (True, True, Stage.GOODBYE, True, True, True, False, True),
    ],
)
def test_only_the_expected_goodbye_completes_the_lesson(
    v55, sp24, checkpoint, complete, finishing_reply, has_audio_and_text, incomplete, ends,
):
    assert goodbye_played(
        complete=complete, finishing_reply=finishing_reply, has_audio_and_text=has_audio_and_text,
        incomplete=incomplete, checkpoint=checkpoint, v55=v55, sp24=sp24,
    ) is ends
