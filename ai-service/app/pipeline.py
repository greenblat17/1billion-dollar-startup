"""Compatibility alias for app.legacy.pipeline."""

import sys

from app.legacy import pipeline

sys.modules[__name__] = pipeline
