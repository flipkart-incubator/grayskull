"""After-fork recovery and edge branches, exercised in-process.

test_review_regressions.py checks fork recovery end to end, but that code runs in the forked child,
where coverage is not measured. These tests call the same after-fork hooks directly.
"""

import gc
import sys
import threading
import time
import weakref

import pytest
from parity_helpers import RecordingMetrics, ScriptedServer, StaticAuth, new_client

from grayskull import GrayskullClientConfiguration
from grayskull.client_impl import grayskull_client_impl as impl_module
from grayskull.client_impl.grayskull_http_client import GrayskullHttpClient, _ConnectionPool, _DeadlineExceeded
from grayskull.client_impl.heartbeat_scheduler import HeartbeatScheduler
from grayskull.client_impl.hook_refresh_poller import HookRefreshPoller
from grayskull.client_impl.metrics.prometheus_metrics_recorder import prometheus_available
from grayskull.client_impl.request_context import current_request_id, request_id_scope
from grayskull.client_impl.usage_ledger import UsageLedger


@pytest.fixture
def web():
    server = ScriptedServer()
    yield server
    server.stop()


class FakeConnection:
    def __init__(self) -> None:
        self.closed = False

    def close(self) -> None:
        self.closed = True


def test_after_fork_hook_restarts_a_live_client(web, monkeypatch):
    client = new_client(web.url)
    calls = []
    monkeypatch.setattr(client, "_after_fork_in_child", lambda: calls.append(1))
    try:
        impl_module._after_fork_in_child(weakref.ref(client))
        assert calls == [1]
    finally:
        client.close()


def test_after_fork_hook_ignores_a_collected_client():
    class Gone:
        pass

    obj = Gone()
    ref = weakref.ref(obj)
    del obj
    gc.collect()
    impl_module._after_fork_in_child(ref)  # the client was garbage-collected: nothing to restart


def test_pool_reset_after_fork_closes_inherited_idle_connections():
    pool = _ConnectionPool(2)
    inherited = [FakeConnection(), FakeConnection()]
    for conn in inherited:
        pool.put_idle(("http", "host", 80), conn)
    pool.reset_after_fork()
    assert all(conn.closed for conn in inherited)
    assert pool.take_idle(("http", "host", 80)) is None
    assert pool.acquire_slot(time.monotonic() + 1) and pool.acquire_slot(time.monotonic() + 1)  # fresh slots


def test_read_timeout_check_raises_once_the_attempt_deadline_has_passed(web):
    http = GrayskullHttpClient(StaticAuth(), GrayskullClientConfiguration(host=web.url), RecordingMetrics())
    with pytest.raises(_DeadlineExceeded, match="deadline"):
        http._apply_read_timeout(None, time.monotonic() - 1)


def test_closed_heartbeat_scheduler_is_not_restarted_after_fork(web):
    http = GrayskullHttpClient(StaticAuth(), GrayskullClientConfiguration(host=web.url), RecordingMetrics())
    scheduler = HeartbeatScheduler(http, web.url, 3600, lambda: [], RecordingMetrics())
    scheduler.start()
    scheduler.close()
    stopped_thread = scheduler._scheduler
    scheduler.restart_after_fork()
    assert scheduler._scheduler is stopped_thread
    assert not scheduler._scheduler.is_alive()


def test_closed_poller_is_not_restarted_after_fork(web):
    http = GrayskullHttpClient(StaticAuth(), GrayskullClientConfiguration(host=web.url), RecordingMetrics())
    poller = HookRefreshPoller(http, web.url, 3600, UsageLedger(), RecordingMetrics())
    poller.start()
    poller.register("p", "s", lambda value: None, 0)
    poller.close(2)
    before = threading.active_count()
    poller.restart_after_fork()
    assert threading.active_count() == before
    assert not poller._scheduler.is_alive()


def test_prometheus_is_reported_unavailable_when_it_cannot_be_imported(monkeypatch):
    monkeypatch.setitem(sys.modules, "prometheus_client", None)  # makes the import raise ImportError
    assert prometheus_available() is False


def test_current_request_id_follows_the_scope():
    assert current_request_id() is None
    with request_id_scope("req-7"):
        assert current_request_id() == "req-7"
    assert current_request_id() is None
