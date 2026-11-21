"""Compatibility alias for app.legacy.trial_session."""

import sys

from app.legacy import trial_session

sys.modules[__name__] = trial_session
