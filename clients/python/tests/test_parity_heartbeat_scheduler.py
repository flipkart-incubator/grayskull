"""Port of Java HeartbeatSchedulerTest.

Not ported: close_interruptedWhileAwaitingTermination_restoresInterruptFlag (Python threads have no
interrupt flag) and constructor_invalidBaseUrl_throws (the Python client validates the host once, in
GrayskullClientConfiguration, before the scheduler is built).
"""

import threading
import time

import pytest
from parity_helpers import RecordingMetrics, Scripted, ScriptedServer, StaticAuth, wait_until

from grayskull import GrayskullClientConfiguration
from grayskull.client_impl import heartbeat_scheduler as hb
from grayskull.client_impl.grayskull_http_client import GrayskullHttpClient
from grayskull.client_impl.heartbeat_scheduler import HeartbeatOutcome, HeartbeatScheduler, next_failures
from grayskull.client_impl.models.request.heartbeat_request import HeldSecret


@pytest.fixture
def web():
    server = ScriptedServer()
    server.route = lambda seen: Scripted(204)
    yield server
    server.stop()


def held(n: int, project: str = "p"):
    return [HeldSecret(project, f"s{i}", i + 1) for i in range(n)]


def scheduler(web, secrets=None, interval=3600.0, metrics=None, supplier=None, **settings) -> HeartbeatScheduler:
    config = GrayskullClientConfiguration(host=web.url, min_retry_delay=50, **settings)
    http = GrayskullHttpClient(StaticAuth(), config, RecordingMetrics())
    return HeartbeatScheduler(http, web.url, interval, supplier or (lambda: list(secrets or [])),
                              metrics or RecordingMetrics())


def test_beat_once_nothing_fetched_sends_nothing(web):
    assert scheduler(web, [])._beat_once() is HeartbeatOutcome.SENT
    assert web.requests() == []


def test_beat_once_posts_held_secrets_to_heartbeat_api(web):
    metrics = RecordingMetrics()
    assert scheduler(web, held(2), metrics=metrics)._beat_once() is HeartbeatOutcome.SENT
    seen = web.requests()[0]
    assert (seen.method, seen.path) == ("POST", "/v1/client/heartbeat")
    assert seen.json() == {"secrets": [
        {"projectId": "p", "secretName": "s0", "version": 1},
        {"projectId": "p", "secretName": "s1", "version": 2}]}
    assert metrics.statuses("heartbeat") == [204]


def test_beat_once_body_holds_only_secrets(web):
    scheduler(web, [HeldSecret("p", "s", 3, 3)])._beat_once()
    assert list(web.requests()[0].json()) == ["secrets"]


def test_beat_once_chunks_at_50(web):
    scheduler(web, held(120))._beat_once()
    assert [len(s.json()["secrets"]) for s in web.requests()] == [50, 50, 20]
    assert len({s.header("X-Request-Id") for s in web.requests()}) == 3  # one id per heartbeat request


def test_beat_once_server_error_fails_without_inline_retry(web):
    web.route = lambda seen: Scripted(503)
    assert scheduler(web, held(1))._beat_once() is HeartbeatOutcome.FAILED
    assert len(web.requests()) == 1


def test_beat_once_client_error_fails_and_is_not_retried(web):
    web.route = lambda seen: Scripted(400, {"message": "bad"})
    assert scheduler(web, held(1))._beat_once() is HeartbeatOutcome.FAILED
    assert len(web.requests()) == 1


def test_beat_once_serialization_failure_fails_without_sending(web):
    broken = [HeldSecret("p", "s", object())]  # not JSON serializable
    assert scheduler(web, broken)._beat_once() is HeartbeatOutcome.FAILED
    assert web.requests() == []


@pytest.mark.parametrize("status,unsupported", [
    (404, True), (405, True), (501, True), (400, False), (500, False), (503, False)])
def test_is_unsupported_only_for_404_405_501(web, status, unsupported):
    web.route = lambda seen: Scripted(status)
    outcome = scheduler(web, held(1))._beat_once()
    assert outcome is (HeartbeatOutcome.UNSUPPORTED if unsupported else HeartbeatOutcome.FAILED)


def test_beat_once_unsupported_status_stops_sending_the_round(web):
    web.route = lambda seen: Scripted(404)
    assert scheduler(web, held(120))._beat_once() is HeartbeatOutcome.UNSUPPORTED
    assert len(web.requests()) == 1


def test_beat_once_failed_middle_chunk_still_sends_the_rest_and_fails_the_round(web):
    web.enqueue(Scripted(204), Scripted(503), Scripted(204))
    web.route = None
    assert scheduler(web, held(120))._beat_once() is HeartbeatOutcome.FAILED
    assert len(web.requests()) == 3


def test_beat_once_unsupported_then_supported_server_recovers(web):
    s = scheduler(web, held(1))
    web.route = lambda seen: Scripted(404)
    assert s._beat_once() is HeartbeatOutcome.UNSUPPORTED
    web.route = lambda seen: Scripted(204)
    assert s._beat_once() is HeartbeatOutcome.SENT


def test_next_delay_success_is_interval_with_jitter(web):
    s = scheduler(web, interval=2400.0)
    delays = [s._next_delay_seconds(0) for _ in range(200)]
    assert all(2300 <= d <= 2500 for d in delays)  # interval +- 1/24
    assert len(set(delays)) > 1


def test_next_delay_after_a_failure_retries_once_in_about_an_hour_then_waits_the_interval(web):
    s = scheduler(web, interval=86400.0)
    assert all(3240 <= s._next_delay_seconds(1) <= 3960 for _ in range(200))  # 1h +- 10%
    assert all(82800 <= s._next_delay_seconds(2) <= 90000 for _ in range(200))


def test_next_delay_retry_never_exceeds_interval(web):
    s = scheduler(web, interval=600.0)
    assert all(s._next_delay_seconds(1) <= 660 for _ in range(200))


def test_run_failed_beats_alternate_between_one_retry_and_the_next_interval():
    failures, seen = 0, []
    for _ in range(5):
        failures = next_failures(failures)
        seen.append(failures)
    assert seen == [1, 2, 1, 2, 1]


def test_schedule_keeps_sending_after_failures(web):
    web.route = lambda seen: Scripted(503)
    s = scheduler(web, held(1), interval=0.05)
    s.start()
    try:
        wait_until(lambda: len(web.requests()) >= 4, message="repeated heartbeats")
    finally:
        s.close()


def test_run_unexpected_exception_counts_failure_and_does_not_propagate(web):
    calls = []

    def supplier():
        calls.append(1)
        if len(calls) == 1:
            raise RuntimeError("ledger broke")
        return held(1)

    s = scheduler(web, supplier=supplier, interval=0.05)
    s.start()
    try:
        wait_until(lambda: len(web.requests()) >= 1, message="beat after the failure")
        assert s._scheduler.is_alive()
    finally:
        s.close()


def test_run_unsupported_waits_full_interval_and_logs_once(web, caplog, monkeypatch):
    web.route = lambda seen: Scripted(404)
    delays = []
    original = HeartbeatScheduler._next_delay_seconds

    def spy(self, failures):
        delays.append(failures)
        return original(self, failures)

    monkeypatch.setattr(HeartbeatScheduler, "_next_delay_seconds", spy)
    s = scheduler(web, held(1), interval=0.05)
    with caplog.at_level("INFO", logger=hb.__name__):
        s.start()
        try:
            wait_until(lambda: len(web.requests()) >= 3, message="unsupported beats")
        finally:
            s.close()
    assert set(delays) == {0}  # never the retry delay
    assert sum("does not support client heartbeats" in r.getMessage() for r in caplog.records) == 1


def test_close_stops_heartbeat(web):
    s = scheduler(web, held(1), interval=0.05)
    s.start()
    wait_until(lambda: len(web.requests()) >= 1)
    s.close()
    count = len(web.requests())
    time.sleep(0.2)
    assert len(web.requests()) == count
    assert not s._scheduler.is_alive()


def test_start_after_close_is_ignored(web):
    s = scheduler(web, held(1), interval=0.05)
    s.close()
    s.start()
    time.sleep(0.2)
    assert web.requests() == []


def test_close_returns_promptly_while_a_request_hangs(web):
    web.route = lambda seen: Scripted(204, delay=3)
    s = scheduler(web, held(1), interval=0.01)
    s.start()
    wait_until(lambda: len(web.requests()) >= 1)
    start = time.monotonic()
    s.close(timeout_seconds=0.3)
    assert time.monotonic() - start < 1.0
