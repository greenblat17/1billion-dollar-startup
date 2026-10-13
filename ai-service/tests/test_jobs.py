import time

from app.jobs import JobStore


def test_job_lookup_does_not_scan_other_jobs(monkeypatch) -> None:
    store = JobStore(ttl_seconds=600)
    jobs = [store.create("session") for _ in range(1000)]

    def unexpected_purge() -> None:
        raise AssertionError("polling must not scan all jobs")

    monkeypatch.setattr(store, "purge", unexpected_purge)
    for _ in range(10):
        assert store.get(jobs[-1].job_id) is jobs[-1]


def test_expired_job_is_removed_on_lookup() -> None:
    store = JobStore(ttl_seconds=60)
    job = store.create("session")
    job.created_at = time.monotonic() - 61

    assert store.get(job.job_id) is None
    assert job.job_id not in store._jobs


def test_new_job_periodically_cleans_expired_jobs() -> None:
    store = JobStore(ttl_seconds=60)
    old = store.create("session")
    old.created_at = time.monotonic() - 61
    store._next_purge_at = 0

    current = store.create("session")

    assert old.job_id not in store._jobs
    assert store.get(current.job_id) is current


def test_lookup_periodically_cleans_other_expired_jobs() -> None:
    store = JobStore(ttl_seconds=60)
    old = store.create("session")
    current = store.create("session")
    old.created_at = time.monotonic() - 61
    store._next_purge_at = 0

    assert store.get(current.job_id) is current
    assert old.job_id not in store._jobs
