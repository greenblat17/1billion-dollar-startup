"""Compatibility alias for app.realtime_voice.session."""

import sys

from app.realtime_voice import session

sys.modules[__name__] = session
