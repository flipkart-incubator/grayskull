"""Port of Java GrayskullClientImplHeartbeatTest: the heartbeat as seen on the wire through the public client.

conftest scales configured seconds by TEST_SECOND, so polling_interval_seconds=1 polls every 50ms and
heartbeat_interval_seconds=2 beats every 100ms.
"""

import time

import pytest
from parity_helpers import FakeApi, Scripted, ScriptedServer, StaticResolver, new_client, wait_until

from grayskull import GrayskullException

IDENTITY = "v1::c::ns::ReplicaSetOwned::app"


@pytest.fixture
def web():
    server = ScriptedServer()
    yield server
    server.stop()


@pytest.fixture
def api(web):
    fake = FakeApi(web)
    fake.set_secret("proj", "db-password", 7, "pub", "s3cret")
    return fake


@pytest.fixture
def make(web, api):
    made = []

    def build(**settings):
        config = dict(heartbeat_enabled=True, heartbeat_interval_seconds=2,
                      workload_identity_resolver=StaticResolver(IDENTITY))
        config.update(settings)
        client = new_client(web.url, **config)
        made.append(client)
        return client

    yield build
    for client in made:
        client.close()


def latest_entry(api, name):
    for body in reversed(api.heartbeats()):
        for secret in body["secrets"]:
            if secret["secretName"] == name:
                return secret
    return None


def await_entry(api, name, version, timeout=5.0):
    wait_until(lambda: (latest_entry(api, name) or {}).get("version") == version, timeout,
               f"heartbeat entry {name} at v{version}")
    return latest_entry(api, name)


def test_heartbeat_posts_the_held_secret_with_identity_headers_and_no_values(make, api, web):
    client = make()
    client.get_secret("proj:db-password")
    wait_until(lambda: api.heartbeats(), message="first heartbeat")
    seen = web.requests("/v1/client/heartbeat")[0]
    assert seen.header("Grayskull-Workload") == IDENTITY
    assert seen.header("User-Agent").startswith("grayskull-python/")
    body = seen.json()
    assert body == {"secrets": [{"projectId": "proj", "secretName": "db-password", "version": 7}]}
    assert "s3cret" not in seen.body.decode() and "pub" not in seen.body.decode()


def test_hook_only_secret_is_reported_once_the_poller_delivers_a_version(make, api):
    api.set_secret("proj", "db-password", 8)
    client = make(polling_interval_seconds=1)
    client.register_refresh_hook("proj:db-password", lambda value: None)  # never fetched
    entry = await_entry(api, "db-password", 8)
    assert entry["refreshHookAppliedVersion"] == 8


def test_unregistered_hook_is_reported_without_hook(make, api):
    client = make()
    client.get_secret("proj:db-password")
    handle = client.register_refresh_hook("proj:db-password", lambda value: None)
    handle.unregister()
    handle.unregister()  # idempotent
    wait_until(lambda: api.heartbeats())
    entry = latest_entry(api, "db-password")
    assert entry is not None  # ledger entries are never removed
    assert "refreshHookAppliedVersion" not in entry


def test_get_secret_failure_is_rethrown_unchanged_and_not_recorded(make, api):
    api.get_replies.append(Scripted(403, {"message": "forbidden"}))
    client = make()
    with pytest.raises(GrayskullException) as info:
        client.get_secret("proj:db-password")
    assert info.value.status_code == 403
    time.sleep(0.3)
    assert latest_entry(api, "db-password") is None


def test_get_secret_malformed_ref_creates_no_entry(make, api):
    client = make()
    with pytest.raises(ValueError):
        client.get_secret("malformed")
    assert client._usage_ledger.snapshot() == []


def test_nothing_sent_while_ledger_empty(make, api):
    make()
    time.sleep(0.4)
    assert api.heartbeats() == []


def test_heartbeat_disabled_sends_nothing(make, api):
    client = make(heartbeat_enabled=False)
    client.get_secret("proj:db-password")
    time.sleep(0.4)
    assert api.heartbeats() == []


def test_reports_version_applied_by_refresh_hook(make, api):
    client = make(polling_interval_seconds=1)
    client.get_secret("proj:db-password")
    client.register_refresh_hook("proj:db-password", lambda value: None)
    api.set_secret("proj", "db-password", 9)
    entry = await_entry(api, "db-password", 9)
    wait_until(lambda: latest_entry(api, "db-password").get("refreshHookAppliedVersion") == 9)
    assert entry["version"] == 9


def test_failed_hook_does_not_advance_refresh_hook_applied_version(make, api):
    api.set_secret("proj", "db-password", 8)
    client = make(polling_interval_seconds=1)

    def failing(value):
        raise RuntimeError("cannot reload pool")

    client.register_refresh_hook("proj:db-password", failing)
    entry = await_entry(api, "db-password", 8)  # version advances before the hook runs (at-most-once)
    assert entry["refreshHookAppliedVersion"] == 0


def test_one_failing_hook_blocks_the_applied_version(make, api):
    api.set_secret("proj", "db-password", 8)
    client = make(polling_interval_seconds=1)
    client.register_refresh_hook("proj:db-password", lambda value: None)

    def failing(value):
        raise RuntimeError("second hook fails")

    client.register_refresh_hook("proj:db-password", failing)
    entry = await_entry(api, "db-password", 8)
    assert entry["refreshHookAppliedVersion"] == 0


def test_get_secret_failure_with_hook_registered_reports_nothing(make, api):
    api.get_replies.append(Scripted(500, {"message": "down"}))
    client = make(max_retries=1)
    client.register_refresh_hook("proj:other", lambda value: None)
    with pytest.raises(GrayskullException):
        client.get_secret("proj:db-password")
    time.sleep(0.4)
    assert latest_entry(api, "db-password") is None
    assert latest_entry(api, "other") is None  # a hook without any delivered version holds nothing


@pytest.mark.parametrize("status", [500, 503, 404, 501])
def test_heartbeat_errors_never_reach_the_caller(make, api, status):
    api.heartbeat_replies.extend(Scripted(status) for _ in range(50))
    client = make()
    client.get_secret("proj:db-password")
    wait_until(lambda: len(api.heartbeats()) >= 1)
    assert client.get_secret("proj:db-password").data_version == 7


def test_ledger_is_shared_with_poller_and_scheduler(make):
    client = make()
    assert client._refresh_poller._usage_ledger is client._usage_ledger
    assert client._heartbeat_scheduler._held_secrets == client._usage_ledger.snapshot
