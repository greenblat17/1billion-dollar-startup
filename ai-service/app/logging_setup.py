from __future__ import annotations

import logging
import os

_FORMAT = "%(asctime)s %(levelname)s %(name)s %(message)s"


def configure_process_logging(level: str, log_file: str) -> None:
    logging.basicConfig(level=level, format=_FORMAT)
    root = logging.getLogger()
    root.setLevel(level)
    path = log_file.strip()
    if not path:
        return
    absolute = os.path.abspath(path)
    if any(getattr(handler, "baseFilename", None) == absolute for handler in root.handlers):
        return
    directory = os.path.dirname(absolute)
    if directory:
        os.makedirs(directory, exist_ok=True)
    handler = logging.FileHandler(absolute)
    handler.setFormatter(logging.Formatter(_FORMAT))
    root.addHandler(handler)
