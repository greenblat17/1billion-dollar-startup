import importlib.util
import io
import json
import urllib.error
from pathlib import Path

import pytest


SPEC = importlib.util.spec_from_file_location("verify_tutor", Path(__file__).with_name("verify-tutor.py"))
verify_tutor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify_tutor)


@pytest.mark.parametrize("log_error", ["b" * 32, "c" * 32])
def test_logging_probe_matches_response_error_to_runtime_record(monkeypatch, capsys, log_error):
    trace, error_id, revision = "a" * 32, "b" * 32, "d" * 40

    def reject(url, *, payload):
        assert url.endswith("/miniapp/api")
        assert payload == {"action": "list", "initData": ""}
        raise urllib.error.HTTPError(url, 403, "Forbidden",
                                     {"X-Diagnostic-Trace": trace, "X-Error-ID": error_id},
                                     io.BytesIO(b'{"detail":"invalid_telegram_auth"}'))

    record = {"schema_version": 1, "release": revision, "environment": "dev", "trace_id": trace,
              "event": "backend_failed", "reason_code": "backend_rejected", "error_id": log_error}
    monkeypatch.setattr(verify_tutor, "request", reject)
    monkeypatch.setattr(verify_tutor.subprocess, "check_output",
                        lambda *args, **kwargs: ("runtime_event " + json.dumps(record)).encode())

    if log_error == error_id:
        verify_tutor.logging_probe("8090", "ai-service", revision)
        assert capsys.readouterr().out.splitlines() == [f"logging_probe_trace={trace}", f"logging_probe_error_id={error_id}"]
    else:
        with pytest.raises(RuntimeError, match="logging_probe_error_id_mismatch"):
            verify_tutor.logging_probe("8090", "ai-service", revision)
        assert capsys.readouterr().out == ""
