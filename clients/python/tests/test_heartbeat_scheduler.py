import json
from typing import List

import pytest

from grayskull import BasicAuthHeaderProvider, GrayskullClientConfiguration, NoopMetricsRecorder
from grayskull.client_impl.grayskull_http_client import GrayskullHttpClient
from grayskull.client_impl.heartbeat_scheduler import MAX_BATCH_SECRETS, HeartbeatOutcome, HeartbeatScheduler
from grayskull.client_impl.models.request.heartbeat_request import HeldSecret


def held(n: int) -> List[HeldSecret]:
    return [HeldSecret(project_id="p", secret_name=f"s{i}", version=i + 1) for i in range(n)]


def new_scheduler(url: str, secrets: List[HeldSecret], interval_seconds: float = 3600) -> HeartbeatScheduler:
    config = GrayskullClientConfiguration(host=url, min_retry_delay=50)
    http_client = GrayskullHttpClient(BasicAuthHeaderProvider("u", "p"), config, NoopMetricsRecorder())
    return HeartbeatScheduler(http_client, url, interval_seconds, lambda: secrets, NoopMetricsRecorder())


def test_posts_in_chunks_of_fifty(server):
    new_scheduler(server.url, held(MAX_BATCH_SECRETS * 2 + 7))._beat_once()
    assert [len(p.body["secrets"]) for p in server.recorded("/v1/client/heartbeat")] == [50, 50, 7]


def test_omits_applied_version_when_no_hook_is_registered(server):
    new_scheduler(server.url, [HeldSecret("p", "no-hook", 2)])._beat_once()
    body = server.recorded("/v1/client/heartbeat")[0].body
    assert body == {"secrets": [{"projectId": "p", "secretName": "no-hook", "version": 2}]}


def test_sends_nothing_when_no_secrets_are_held(server):
    assert new_scheduler(server.url, [])._beat_once() is HeartbeatOutcome.SENT
    assert server.recorded("/v1/client/heartbeat") == []


@pytest.mark.parametrize("status", [404, 405, 501])
def test_unsupported_status_stops_the_round(server, status):
    server.heartbeat_status = status
    assert new_scheduler(server.url, held(120))._beat_once() is HeartbeatOutcome.UNSUPPORTED
    assert len(server.recorded("/v1/client/heartbeat")) == 1


@pytest.mark.parametrize("status", [401, 403, 500, 503])
def test_auth_and_server_errors_are_failures(server, status):
    server.heartbeat_status = status
    assert new_scheduler(server.url, held(1))._beat_once() is HeartbeatOutcome.FAILED
    assert len(server.recorded("/v1/client/heartbeat")) == 1, "a heartbeat is a single attempt"


def test_failed_middle_chunk_still_sends_the_rest(server):
    server.heartbeat_statuses = [204, 503, 204]
    assert new_scheduler(server.url, held(120))._beat_once() is HeartbeatOutcome.FAILED
    assert [len(r.body["secrets"]) for r in server.recorded("/v1/client/heartbeat")] == [50, 50, 20]


def test_recovers_when_the_server_starts_supporting_heartbeats(server):
    server.heartbeat_statuses = [404]
    scheduler = new_scheduler(server.url, held(1))
    assert scheduler._beat_once() is HeartbeatOutcome.UNSUPPORTED
    assert scheduler._beat_once() is HeartbeatOutcome.SENT


def test_next_delay_is_the_interval_after_success_and_backs_off_after_failures():
    scheduler = new_scheduler("http://x", [], interval_seconds=86400)
    assert 86400 - 3600 <= scheduler._next_delay_seconds(0) <= 86400 + 3600
    assert 300 * 0.9 <= scheduler._next_delay_seconds(1) <= 300 * 1.1
    assert scheduler._next_delay_seconds(50) <= 3600 * 1.1, "capped at one hour"


def test_body_is_the_wire_shape(server):
    new_scheduler(server.url, [HeldSecret("p", "s", 5, 4)])._beat_once()
    expected = json.loads(json.dumps({"secrets": [HeldSecret("p", "s", 5, 4).to_dict()]}))
    assert server.recorded("/v1/client/heartbeat")[0].body == expected


def test_close_before_start_is_safe():
    new_scheduler("http://x", []).close()


def test_unexpected_error_does_not_stop_the_schedule():
    calls = []

    def broken_snapshot():
        calls.append(1)
        raise RuntimeError("ledger bug")

    config = GrayskullClientConfiguration(host="http://x", min_retry_delay=50)
    http_client = GrayskullHttpClient(BasicAuthHeaderProvider("u", "p"), config, NoopMetricsRecorder())
    scheduler = HeartbeatScheduler(http_client, "http://x", 0.02, broken_snapshot, NoopMetricsRecorder())
    scheduler._next_delay_seconds = lambda failures: 0.02  # keep retrying quickly despite the failure backoff
    scheduler.start()
    import time
    deadline = time.monotonic() + 5
    while len(calls) < 3 and time.monotonic() < deadline:
        time.sleep(0.01)
    scheduler.close()
    assert len(calls) >= 3


def test_unsupported_status_is_logged_only_once(server, caplog):
    server.heartbeat_status = 404
    scheduler = new_scheduler(server.url, held(1))
    with caplog.at_level("INFO"):
        scheduler._beat_once()
        scheduler._beat_once()
    assert sum("does not support client heartbeats" in r.getMessage() for r in caplog.records) == 1
