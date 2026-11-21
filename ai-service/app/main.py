"""Compatibility alias for the retained AI runtime."""

import sys

from app.legacy import main as legacy_main

sys.modules[__name__] = legacy_main
