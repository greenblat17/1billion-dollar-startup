import logging

from app.logging_setup import configure_process_logging


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
