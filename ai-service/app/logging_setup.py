from __future__ import annotations

import logging
import os

from app.diagnostics import count_diagnostic_loss

_FORMAT = "%(asctime)s %(levelname)s %(name)s %(message)s"


class _CountDiagnosticLoss:
    def handleError(self, record: logging.LogRecord) -> None:
        if isinstance(record.msg, str) and record.msg.startswith(('runtime_event ', 'provider_call ', 'tutor_tool ')):
            count_diagnostic_loss()


class _DiagnosticStreamHandler(_CountDiagnosticLoss, logging.StreamHandler):
    pass


class _DiagnosticFileHandler(_CountDiagnosticLoss, logging.FileHandler):
    pass


def configure_process_logging(level: str, log_file: str, *, payload_free: bool = False) -> None:
    if payload_free:
        logging.basicConfig(level=level, format=_FORMAT, handlers=[_DiagnosticStreamHandler()])
    else:
        logging.basicConfig(level=level, format=_FORMAT)
    root = logging.getLogger()
    root.setLevel(level)
    if payload_free:
        # Protocol handlers can log URLs, auth headers or frames at verbose levels.
        for name in ('httpx', 'httpcore', 'openai', 'websockets', 'botocore', 'boto3', 's3transfer'):
            logging.getLogger(name).setLevel(logging.CRITICAL + 1)
    path = log_file.strip()
    if not path:
        return
    absolute = os.path.abspath(path)
    if any(getattr(handler, "baseFilename", None) == absolute for handler in root.handlers):
        return
    directory = os.path.dirname(absolute)
    if directory:
        os.makedirs(directory, exist_ok=True)
    handler = _DiagnosticFileHandler(absolute) if payload_free else logging.FileHandler(absolute)
    handler.setFormatter(logging.Formatter(_FORMAT))
    root.addHandler(handler)
