from __future__ import annotations

import threading
import time

import httpx
import pytest

from conftest import RecordingMetrics, body, json_response
from grayskull import SecretValue
from grayskull._internal.heartbeat import BACKOFF_BASE, BACKOFF_CAP, next_delay
from grayskull._internal.hooks import Registry
from grayskull._internal.poller import Poller

SECRET = {"dataVersion": 3, "publicPart": "u", "privatePart": "p"}


def updated(project, name, version):
    return {"projectId": project, "secretName": name, "dataVersion": version, "publicPart": "u", "privatePart": f"v{version}"}


def batch_response(*items):
    return json_response(200, {"updatedCount": len(items), "updatedSecrets": list(items)})


def poller_of(client) -> Poller:
    return client._poller


def drain(client, timeout=2.0):
    """Wait until every queued hook dispatch has finished."""
    assert poller_of(client).wait_idle(timeout)


# -- registry / state semantics ------------------------------------------------


def test_stage_coalesces_to_newest_and_drops_stale():
    registry = Registry()
    registry.register("p", "s", lambda v: None, seed_version=2)
    state = registry.get("p:s")

    assert state.stage(SecretValue(1)) is False  # stale: <= last known
    assert state.stage(SecretValue(4)) is True  # first pending -> schedule
    assert state.stage(SecretValue(6)) is False  # already scheduled, coalesced
    assert state.stage(SecretValue(5)) is False  # older than pending: ignored
    assert state.take_next() == SecretValue(6)
    assert state.last_known_version == 6  # advanced before hooks run (at-most-once)
    assert state.take_next() is None
    assert state.stage(SecretValue(6)) is False  # duplicate after delivery


def test_unregister_is_idempotent_and_drops_state():
    registry = Registry()
    a = registry.register("p", "s", lambda v: None, 0)
    b = registry.register("p", "s", lambda v: None, 0)
    a.unregister()
    a.unregister()
    assert not a.is_active and b.is_active
    assert registry.get("p:s") is not None
    b.unregister()
    assert registry.get("p:s") is None


# -- poller end-to-end -----------------------------------------------------------


def test_hooks_receive_updates_and_failures_are_isolated(server, make_client):
    server.on("GET", "/v1/projects", lambda r: json_response(200, SECRET))
    server.on("POST", "/v1/secrets/batch", lambda r: batch_response(updated("proj", "db", 4)))
    metrics = RecordingMetrics()
    client = make_client(metrics)
    client.get_secret("proj:db")

    received = []

    def boom(value):
        raise RuntimeError("hook bug")

    client.register_refresh_hook("proj:db", boom)
    client.register_refresh_hook("proj:db", received.append)

    poller_of(client).poll_once()
    drain(client)

    assert received == [SecretValue(4, "u", "v4")]
    sent = body(server.matching("POST", "/v1/secrets/batch")[0])
    assert sent == {"secrets": [{"projectId": "proj", "secretName": "db", "lastKnownVersion": 3}]}
    assert ("hook.execute", 500) in metrics.requests and ("hook.execute", 200) in metrics.requests

    # next poll reports the advanced version and a duplicate answer is not redelivered
    poller_of(client).poll_once()
    drain(client)
    assert received == [SecretValue(4, "u", "v4")]
    assert body(server.matching("POST", "/v1/secrets/batch")[1])["secrets"][0]["lastKnownVersion"] == 4


def test_slow_hook_coalesces_intermediate_versions(server, make_client):
    versions = iter([5, 6, 7])
    server.on("POST", "/v1/secrets/batch", lambda r: batch_response(updated("p", "s", next(versions))))
    client = make_client()

    gate, entered = threading.Event(), threading.Event()
    seen = []

    def slow(value):
        seen.append(value.data_version)
        entered.set()
        gate.wait(2)

    client.register_refresh_hook("p:s", slow)
    poller = poller_of(client)
    poller.poll_once()          # v5 -> dispatched, hook blocks
    assert entered.wait(2)
    poller.poll_once()          # v6 staged
    poller.poll_once()          # v7 replaces v6
    gate.set()
    drain(client)
    assert seen == [5, 7]


def test_batches_are_chunked_by_50(server, make_client):
    server.on("POST", "/v1/secrets/batch", lambda r: batch_response())
    client = make_client()
    for i in range(120):
        client.register_refresh_hook(f"p:s{i}", lambda v: None)
    poller_of(client).poll_once()
    sizes = [len(body(r)["secrets"]) for r in server.matching("POST", "/v1/secrets/batch")]
    assert sorted(sizes) == [20, 50, 50]


def test_poll_failure_is_logged_not_raised(server, make_client):
    server.on("POST", "/v1/secrets/batch", lambda r: httpx.Response(500))
    metrics = RecordingMetrics()
    client = make_client(metrics, max_retries=1)
    client.register_refresh_hook("p:s", lambda v: None)
    poller_of(client).poll_once()
    assert ("batchGetSecrets", 500) in metrics.requests


def test_background_poller_runs_on_interval(server, make_client):
    server.on("POST", "/v1/secrets/batch", lambda r: batch_response(updated("p", "s", 1)))
    got = threading.Event()
    client = make_client(polling_interval=0.05)
    client.register_refresh_hook("p:s", lambda v: got.set())
    assert got.wait(2)


def test_poller_is_lazy(make_client):
    client = make_client()
    assert client._poller is None


def test_blocked_hook_does_not_block_close(server, make_client):
    server.on("POST", "/v1/secrets/batch", lambda r: batch_response(updated("p", "s", 1)))
    client = make_client()
    entered, release = threading.Event(), threading.Event()
    client.register_refresh_hook("p:s", lambda v: (entered.set(), release.wait(10)))
    poller = poller_of(client)
    poller.poll_once()
    assert entered.wait(2)

    start = time.monotonic()
    assert poller.close(timeout=0.2) is False  # reports the abandoned hook
    assert time.monotonic() - start < 1.0
    assert all(w.daemon for w in poller._workers)  # cannot hold the interpreter at exit
    release.set()


def test_hook_may_close_its_own_client(server, make_client):
    server.on("POST", "/v1/secrets/batch", lambda r: batch_response(updated("p", "s", 1)))
    client = make_client()
    done = threading.Event()

    def closer(value):
        client.close()
        done.set()

    client.register_refresh_hook("p:s", closer)
    poller = poller_of(client)
    start = time.monotonic()
    poller.poll_once()
    assert done.wait(2)
    assert time.monotonic() - start < 1.0


# -- heartbeat ------------------------------------------------------------------


def test_heartbeat_reports_held_versions(server, make_client):
    server.on("GET", "/v1/projects", lambda r: json_response(200, SECRET))
    server.on("POST", "/v1/secrets/batch", lambda r: batch_response(updated("a", "x:y", 9)))
    server.on("POST", "/v1/heartbeat", lambda r: httpx.Response(200))
    client = make_client(heartbeat_enabled=True)

    assert client._heartbeat is None  # lazy until something is held
    client.get_secret("a:x:y")
    client.get_secret("b:z")
    client.register_refresh_hook("a:x:y", lambda v: None)
    poller_of(client).poll_once()
    drain(client)

    assert client._heartbeat.beat() is True
    secrets = body(server.matching("POST", "/v1/heartbeat")[0])["secrets"]
    assert sorted(secrets, key=lambda s: s["projectId"]) == [
        {"projectId": "a", "secretName": "x:y", "version": 9, "refreshHook": True},
        {"projectId": "b", "secretName": "z", "version": 3, "refreshHook": False},
    ]


def test_heartbeat_sends_nothing_when_nothing_held(server, make_client):
    client = make_client(heartbeat_enabled=True)
    client._ensure_heartbeat_locked()
    assert client._heartbeat.beat() is True
    assert server.matching("POST", "/v1/heartbeat") == []


def test_heartbeat_failure_returns_false(server, make_client):
    server.on("GET", "/v1/projects", lambda r: json_response(200, SECRET))
    server.on("POST", "/v1/heartbeat", lambda r: httpx.Response(503))
    client = make_client(heartbeat_enabled=True, max_retries=1)
    client.get_secret("p:s")
    assert client._heartbeat.beat() is False


@pytest.mark.parametrize(
    "failures, low, high",
    [
        (0, 86400 * 23 / 24, 86400 * 25 / 24),
        (1, BACKOFF_BASE * 0.9, BACKOFF_BASE * 1.1),
        (2, 2 * BACKOFF_BASE * 0.9, 2 * BACKOFF_BASE * 1.1),
        (30, BACKOFF_CAP * 0.9, BACKOFF_CAP * 1.1),
    ],
)
def test_heartbeat_schedule(failures, low, high):
    for rng in (lambda: 0.0, lambda: 0.5, lambda: 0.999999):
        assert low <= next_delay(86400, failures, rng) <= high


def test_heartbeat_backoff_never_exceeds_interval():
    assert next_delay(60, 5, lambda: 0.5) == pytest.approx(60)
