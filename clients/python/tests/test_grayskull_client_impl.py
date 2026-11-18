"""End-to-end tests: the public client against an in-process Grayskull fake."""

import threading
import time
from typing import Callable, List

import pytest
from fake_grayskull_server import FakeGrayskullServer, RunningServer

from grayskull import (BasicAuthHeaderProvider, GrayskullClientConfiguration, GrayskullClientImpl,
                       GrayskullException, SecretValue, WorkloadIdentityResolver)


class StaticWorkloadIdentityResolver(WorkloadIdentityResolver):
    """Returns a fixed workload identity."""

    def __init__(self, identity: str) -> None:
        self._identity = identity

    def resolve(self) -> str:
        return self._identity


def wait_until(predicate: Callable[[], bool], timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.02)
    raise AssertionError("condition not met within timeout")


@pytest.fixture
def grayskull():
    fake = FakeGrayskullServer()
    fake.set_secret("grayskull", "db", 1, "v1")
    running: RunningServer = fake.start()
    yield fake, running
    running.stop()


@pytest.fixture
def make_client(grayskull):
    clients: List[GrayskullClientImpl] = []
    _, running = grayskull

    def factory(**overrides) -> GrayskullClientImpl:
        settings = dict(
            host=running.url,
            polling_interval_seconds=0.05,
            heartbeat_interval_seconds=0.1,
            min_retry_delay=50,
        )
        settings.update(overrides)
        client = GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"), GrayskullClientConfiguration(**settings))
        clients.append(client)
        return client

    yield factory
    for client in clients:
        client.close()


def test_get_secret_returns_the_current_version(grayskull, make_client):
    client = make_client(heartbeat_enabled=False)
    value = client.get_secret("grayskull:db")
    assert value == SecretValue(data_version=1, public_part="v1")


def test_get_secret_rejects_malformed_references(make_client):
    client = make_client(heartbeat_enabled=False)
    for bad in ["", "no-colon", ":name", "project:"]:
        with pytest.raises(ValueError):
            client.get_secret(bad)


def test_refresh_hook_receives_server_update(grayskull, make_client):
    fake, _ = grayskull
    client = make_client(heartbeat_enabled=False)
    seen: List[SecretValue] = []
    client.register_refresh_hook("grayskull:db", seen.append)

    fake.set_secret("grayskull", "db", 2, "v2")
    wait_until(lambda: any(v.data_version == 2 for v in seen))
    assert seen[-1].public_part == "v2"


def test_hook_seeded_from_last_fetch_does_not_redeliver_the_same_version(grayskull, make_client):
    client = make_client(heartbeat_enabled=False)
    client.get_secret("grayskull:db")  # held version 1
    seen: List[SecretValue] = []
    client.register_refresh_hook("grayskull:db", seen.append)
    time.sleep(0.3)  # several poll cycles
    assert seen == []


def test_failing_hook_does_not_block_other_hooks(grayskull, make_client):
    fake, _ = grayskull
    client = make_client(heartbeat_enabled=False)

    def broken(_: SecretValue) -> None:
        raise RuntimeError("boom")

    good: List[int] = []
    client.register_refresh_hook("grayskull:db", broken)
    client.register_refresh_hook("grayskull:db", lambda v: good.append(v.data_version))

    fake.set_secret("grayskull", "db", 2, "v2")
    wait_until(lambda: 2 in good)


def test_heartbeat_reports_applied_version_after_hook_succeeds(grayskull, make_client):
    fake, running = grayskull
    client = make_client()
    delivered: List[int] = []
    client.register_refresh_hook("grayskull:db", lambda v: delivered.append(v.data_version))
    client.get_secret("grayskull:db")

    fake.set_secret("grayskull", "db", 4, "v4")
    wait_until(lambda: 4 in delivered)

    def heartbeat_has_applied(version: int) -> bool:
        for post in fake.recorded("/v1/client/heartbeat"):
            for secret in (post.body or {}).get("secrets", []):
                if secret.get("refreshHookAppliedVersion") == version:
                    return True
        return False

    wait_until(lambda: heartbeat_has_applied(4))
    last = fake.recorded("/v1/client/heartbeat")[-1].body["secrets"][0]
    assert set(last) == {"projectId", "secretName", "version", "refreshHookAppliedVersion"}
    assert last["version"] == 4 and last["refreshHookAppliedVersion"] == 4


def test_unregistering_the_last_hook_removes_the_applied_version_field(grayskull, make_client):
    fake, _ = grayskull
    client = make_client()
    handle = client.register_refresh_hook("grayskull:db", lambda v: None)
    fake.set_secret("grayskull", "db", 3, "v3")
    wait_until(lambda: any(
        s.get("refreshHookAppliedVersion") == 3
        for post in fake.recorded("/v1/client/heartbeat")
        for s in (post.body or {}).get("secrets", [])
    ))

    handle.unregister()
    handle.unregister()  # idempotent
    assert handle.is_active() is False
    before = len(fake.recorded("/v1/client/heartbeat"))
    wait_until(lambda: len(fake.recorded("/v1/client/heartbeat")) > before)
    latest = fake.recorded("/v1/client/heartbeat")[-1].body["secrets"][0]
    assert "refreshHookAppliedVersion" not in latest


def test_requests_identify_the_sdk_and_workload(grayskull, make_client):
    fake, _ = grayskull
    client = make_client(heartbeat_enabled=False, workload_identity_resolver=StaticWorkloadIdentityResolver("pod-42"))
    client.get_secret("grayskull:db")
    headers = fake.recorded("/data")[-1].headers
    assert headers["Grayskull-Workload"] == "pod-42"
    assert headers["User-Agent"].startswith("grayskull-python/")


def test_close_is_idempotent_and_blocks_new_hooks(make_client):
    client = make_client()
    client.close()
    client.close()
    with pytest.raises(RuntimeError):
        client.register_refresh_hook("grayskull:db", lambda v: None)


def test_context_manager_closes_the_client(make_client):
    client = make_client()
    with client:
        pass
    with pytest.raises(RuntimeError):
        client.register_refresh_hook("grayskull:db", lambda v: None)


def test_concurrent_gets_are_safe(make_client):
    client = make_client(heartbeat_enabled=False)
    errors: List[BaseException] = []

    def worker() -> None:
        try:
            for _ in range(20):
                client.get_secret("grayskull:db")
        except BaseException as exc:  # collected for the assertion below
            errors.append(exc)

    threads = [threading.Thread(target=worker) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert errors == []


def test_constructor_rejects_missing_arguments(grayskull):
    _, running = grayskull
    config = GrayskullClientConfiguration(host=running.url)
    with pytest.raises(ValueError):
        GrayskullClientImpl(None, config)  # type: ignore[arg-type]
    with pytest.raises(ValueError):
        GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"), None)  # type: ignore[arg-type]


def test_get_secret_with_unparseable_data_raises(grayskull, make_client):
    fake, _ = grayskull
    fake.raw_get_body = b'{"data": {"publicPart": "missing the version"}}'
    with pytest.raises(GrayskullException, match="Failed to parse response") as info:
        make_client(heartbeat_enabled=False).get_secret("grayskull:db")
    assert info.value.status_code == 500


def test_register_refresh_hook_rejects_a_non_callable_hook(make_client):
    with pytest.raises(ValueError):
        make_client(heartbeat_enabled=False).register_refresh_hook("grayskull:db", "not callable")  # type: ignore[arg-type]
