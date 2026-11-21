import logging

from io import StringIO
import json
import logging

from app.diagnostics import event
from app.logging_setup import configure_process_logging, _DiagnosticStreamHandler


def test_runtime_stream_loss_is_reported_on_the_next_write():
    class BrokenStream(StringIO):
        def write(self, data):
            raise OSError('private_sink_failure')

    stream = StringIO()
    writer = logging.Logger('diagnostic-sink-test')
    failed = _DiagnosticStreamHandler(BrokenStream())
    writer.addHandler(failed)
    event('sink_test', logger=writer)
    writer.removeHandler(failed)
    writer.addHandler(_DiagnosticStreamHandler(stream))
    event('sink_test', logger=writer)
    output = stream.getvalue()
    assert json.loads(output.removeprefix('runtime_event '))['diagnostic_losses'] == 1
    assert 'private_sink_failure' not in output


def test_log_file_keeps_one_handler(tmp_path) -> None:
    path = tmp_path / "app.log"
    configure_process_logging("INFO", str(path))
    configure_process_logging("INFO", str(path))
    logging.getLogger("app.logging-test").info("hello-log")
    text = path.read_text()
    assert text.count("hello-log") == 1
    root = logging.getLogger()
    for handler in list(root.handlers):
        if getattr(handler, "baseFilename", None) == str(path.resolve()):
            root.removeHandler(handler)
            handler.close()
