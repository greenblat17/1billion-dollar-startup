import importlib.util
from pathlib import Path


def _module():
    path = Path(__file__).with_name("analyze-llm-usage.py")
    spec = importlib.util.spec_from_file_location("analyze_llm_usage", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_usage_summary_filters_run_and_counts_rolling_window(tmp_path):
    log = tmp_path / "ai.log"
    log.write_text("\n".join([
        "2026-10-14 06:00:00,000 INFO app.llm load_test_llm_usage "
        "session=loadtest-run1-1 purpose=reply model=test outcome=ok elapsed_ms=100 "
        "prompt_tokens=100 completion_tokens=20 usage_present=True",
        "2026-10-14 06:00:30,000 INFO app.llm load_test_llm_usage "
        "session=loadtest-run1-2 purpose=notes model=test outcome=ok elapsed_ms=200 "
        "prompt_tokens=150 completion_tokens=30 usage_present=True",
        "2026-10-14 06:01:00,000 INFO app.llm load_test_llm_usage "
        "session=loadtest-run1-1 purpose=reply model=test outcome=ok elapsed_ms=150 "
        "prompt_tokens=200 completion_tokens=40 usage_present=True",
        "2026-10-14 06:01:01,000 INFO app.llm load_test_llm_usage "
        "session=loadtest-run2-1 purpose=reply model=test outcome=ok elapsed_ms=100 "
        "prompt_tokens=999 completion_tokens=999 usage_present=True",
    ]) + "\n")
    module = _module()
    summary = module.summarize(module.read_events(log, "run1"))
    assert summary["successful_llm_calls"] == 3
    assert summary["prompt_tokens_total"] == 450
    assert summary["completion_tokens_total"] == 90
    assert summary["peak_rolling_60s_tokens"]["total"] == 420
    assert summary["peak_output_tokens_per_second"] == 1.17
