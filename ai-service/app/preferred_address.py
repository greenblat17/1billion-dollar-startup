"""Shared address preference schema for text and voice tools."""
from pydantic import BaseModel, ConfigDict, Field


class PreferredAddress(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)
    value: str = Field(min_length=1, max_length=100)
    evidence: str = Field(min_length=1, max_length=2000)


DESCRIPTION = (
    'Save the exact way the learner explicitly chose to be addressed, including a later change. '
    'Copy their chosen form and a verbatim supporting quote from the learner message. '
    'A Telegram display name, username, name mentioned in passing, and refusal are not confirmation. '
    'Use the returned value naturally after saving.'
)
ADDRESS_POLICY = (
    'Use context.preferredAddress.value as the learner’s confirmed form of address, sparingly. '
    'Save an explicit choice or change via set_preferred_address promptly. Its latest successful result replaces the snapshot value. '
    'Do not infer a preference from a display name, username or unrelated mention.'
)
TEXT_TOOL = {'type': 'function', 'function': {'name': 'set_preferred_address',
    'description': DESCRIPTION, 'parameters': PreferredAddress.model_json_schema(), 'strict': True}}


class VoicePreferredAddress(PreferredAddress):
    sourceId: str


VOICE_TOOL = {'type': 'function', 'name': 'set_preferred_address',
    'description': DESCRIPTION + ' Use the saved learner transcript sourceId.',
    'parameters': VoicePreferredAddress.model_json_schema()}
