"""Corner cases for reads, hooks and the heartbeat, run through the public client against the fake."""

import time
from typing import Callable, Dict, List, Optional

import pytest
from fake_grayskull_server import FakeGrayskullServer

from grayskull import (BasicAuthHeaderProvider, GrayskullClientConfiguration, GrayskullClientImpl,
                       GrayskullException, SecretValue, WorkloadIdentityResolver)
from grayskull.client_impl.heartbeat_scheduler import HeartbeatOutcome

REF = "grayskull:db"


def wait_until(predicate: Callable[[], bool], timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.02)
    raise AssertionError("condition not met within timeout")


@pytest.fixture
def env():
    fake = FakeGrayskullServer()
    running = fake.start()
    clients: List[GrayskullClientImpl] = []

    def make(**overrides) -> GrayskullClientImpl:
        settings = dict(host=running.url, polling_interval_seconds=0.05, heartbeat_interval_seconds=3600,
                        min_retry_delay=50)
        settings.update(overrides)
        client = GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"), GrayskullClientConfiguration(**settings))
        clients.append(client)
        return client

    yield fake, make
    for c in clients:
        c.close()
    running.stop()


def held(client: GrayskullClient) -> Dict[str, dict]:
    """The heartbeat payload entries the client would send now, keyed by secret name."""
    return {s.secret_name: s.to_dict() for s in client._usage_ledger.snapshot()}


# Reads


@pytest.mark.parametrize("status", [401, 403, 404])
def test_failed_read_without_hook_reports_nothing(env, status):
    fake, make = env
    fake.get_status[("grayskull", "db")] = status
    client = make()
    with pytest.raises(GrayskullException) as info:
        client.get_secret(REF)
    assert info.value.status_code == status
    assert held(client) == {}


@pytest.mark.parametrize("status", [401, 403, 404])
def test_failed_read_with_hook_reports_nothing_until_a_version_is_held(env, status):
    fake, make = env
    fake.get_status[("grayskull", "db")] = status
    client = make(polling_interval_seconds=60)  # no batch poll during the test
    client.register_refresh_hook(REF, lambda v: None)
    with pytest.raises(GrayskullException):
        client.get_secret(REF)
    assert held(client) == {}, "a hook alone, plus a failed read, holds no version"


def test_failed_read_does_not_erase_a_version_already_held(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 3, "v3")
    client = make()
    client.get_secret(REF)
    fake.get_status[("grayskull", "db")] = 403
    with pytest.raises(GrayskullException):
        client.get_secret(REF)
    assert held(client)["db"] == {"projectId": "grayskull", "secretName": "db", "version": 3}


def test_server_errors_are_retried_then_succeed(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    fake.fail_status, fake.fail_gets_remaining = 429, 2
    assert make(max_retries=3).get_secret(REF).data_version == 1
    assert len(fake.recorded("/data")) == 3


def test_malformed_response_body_raises_grayskull_error(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    fake.raw_get_body = b"not json"
    with pytest.raises(GrayskullException):
        make().get_secret(REF)


def test_response_without_data_raises_and_records_nothing(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    fake.raw_get_body = b'{"message": "ok"}'
    client = make()
    with pytest.raises(GrayskullException) as info:
        client.get_secret(REF)
    assert info.value.status_code == 500
    assert held(client) == {}


def test_secret_name_with_colon_and_special_characters_round_trips(env):
    fake, make = env
    fake.set_secret("grayskull", "api:key/v2 x", 4, "v")
    client = make()
    assert client.get_secret("grayskull:api:key/v2 x").data_version == 4
    assert "/secrets/api%3Akey%2Fv2%20x/data" in fake.recorded("/data")[-1].path
    assert held(client)["api:key/v2 x"]["version"] == 4


# Hooks and the applied version


def test_hook_before_first_fetch_is_reported_once_delivered(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 2, "v2")
    client = make()
    seen: List[int] = []
    client.register_refresh_hook(REF, lambda v: seen.append(v.data_version))
    wait_until(lambda: seen == [2])
    wait_until(lambda: held(client).get("db") == {
        "projectId": "grayskull", "secretName": "db", "version": 2, "refreshHookAppliedVersion": 2})


def test_hook_after_fetch_is_not_redelivered_and_has_no_applied_version(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 2, "v2")
    client = make()
    client.get_secret(REF)
    seen: List[int] = []
    client.register_refresh_hook(REF, lambda v: seen.append(v.data_version))
    time.sleep(0.3)
    assert seen == []
    assert held(client)["db"]["refreshHookAppliedVersion"] == 0, "registered, not yet run"


def test_failing_hook_advances_version_but_not_applied_version(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    client = make()
    client.get_secret(REF)
    calls: List[int] = []

    def hook(v: SecretValue) -> None:
        calls.append(v.data_version)
        raise RuntimeError("cannot reload")

    client.register_refresh_hook(REF, hook)
    fake.set_secret("grayskull", "db", 2, "v2")
    wait_until(lambda: calls == [2])
    time.sleep(0.15)  # several more polls: a failed version is not retried (at-most-once)
    assert calls == [2]
    assert held(client)["db"] == {"projectId": "grayskull", "secretName": "db", "version": 2,
                                  "refreshHookAppliedVersion": 0}


def test_applied_version_stays_at_last_success_when_a_later_hook_fails(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    client = make()
    client.get_secret(REF)
    fail_from = {"version": 3}
    calls: List[int] = []

    def hook(v: SecretValue) -> None:
        calls.append(v.data_version)
        if v.data_version >= fail_from["version"]:
            raise RuntimeError("boom")

    client.register_refresh_hook(REF, hook)
    fake.set_secret("grayskull", "db", 2, "v2")
    wait_until(lambda: held(client)["db"].get("refreshHookAppliedVersion") == 2)
    fake.set_secret("grayskull", "db", 3, "v3")
    wait_until(lambda: 3 in calls)
    assert held(client)["db"] == {"projectId": "grayskull", "secretName": "db", "version": 3,
                                  "refreshHookAppliedVersion": 2}


def test_one_failing_hook_blocks_the_applied_version(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    client = make()
    client.get_secret(REF)

    def broken(_: SecretValue) -> None:
        raise RuntimeError("boom")

    client.register_refresh_hook(REF, broken)
    client.register_refresh_hook(REF, lambda v: None)
    fake.set_secret("grayskull", "db", 2, "v2")
    wait_until(lambda: held(client)["db"]["version"] == 2)
    time.sleep(0.15)
    assert held(client)["db"]["refreshHookAppliedVersion"] == 0, "one consumer failed, so v2 was not applied"


def test_unregistering_one_of_two_hooks_keeps_the_applied_version(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    client = make()
    client.get_secret(REF)
    first = client.register_refresh_hook(REF, lambda v: None)
    client.register_refresh_hook(REF, lambda v: None)
    fake.set_secret("grayskull", "db", 2, "v2")
    wait_until(lambda: held(client)["db"].get("refreshHookAppliedVersion") == 2)
    first.unregister()
    assert held(client)["db"].get("refreshHookAppliedVersion") == 2


def test_unregistering_the_last_hook_drops_only_the_applied_version(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    client = make()
    client.get_secret(REF)
    handle = client.register_refresh_hook(REF, lambda v: None)
    fake.set_secret("grayskull", "db", 2, "v2")
    wait_until(lambda: held(client)["db"].get("refreshHookAppliedVersion") == 2)
    handle.unregister()
    assert held(client)["db"] == {"projectId": "grayskull", "secretName": "db", "version": 2}


def test_reregistering_starts_without_an_applied_version(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    client = make()
    client.get_secret(REF)
    handle = client.register_refresh_hook(REF, lambda v: None)
    fake.set_secret("grayskull", "db", 2, "v2")
    wait_until(lambda: held(client)["db"].get("refreshHookAppliedVersion") == 2)
    handle.unregister()
    client.register_refresh_hook(REF, lambda v: None)
    assert held(client)["db"]["refreshHookAppliedVersion"] == 0, "the new hook has not applied a version"


def test_hook_only_secret_on_a_missing_server_secret_is_never_reported(env):
    _, make = env
    client = make()
    client.register_refresh_hook("grayskull:never-created", lambda v: None)
    time.sleep(0.3)
    assert held(client) == {}


def test_closing_stops_hook_delivery(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    client = make()
    client.get_secret(REF)
    seen: List[int] = []
    client.register_refresh_hook(REF, lambda v: seen.append(v.data_version))
    client.close()
    fake.set_secret("grayskull", "db", 2, "v2")
    time.sleep(0.3)
    assert seen == []


# Heartbeat transport


@pytest.mark.parametrize("status", [401, 403, 500, 503])
def test_heartbeat_auth_and_server_errors_back_off_as_failures(env, status):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    fake.heartbeat_status = status
    client = make()
    client.get_secret(REF)
    assert client._heartbeat_scheduler._beat_once() is HeartbeatOutcome.FAILED
    assert len(fake.recorded("/v1/client/heartbeat")) == 1, "a heartbeat is a single attempt"


def test_heartbeat_is_skipped_when_only_unheld_hooks_exist(env):
    fake, make = env
    client = make(polling_interval_seconds=60)
    client.register_refresh_hook(REF, lambda v: None)
    assert client._heartbeat_scheduler._beat_once() is HeartbeatOutcome.SENT
    assert fake.recorded("/v1/client/heartbeat") == []


def test_heartbeat_payload_never_contains_secret_values(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "super-secret-value")
    client = make()
    client.get_secret(REF)
    client._heartbeat_scheduler._beat_once()
    raw = str(fake.recorded("/v1/client/heartbeat")[0].body)
    assert "super-secret-value" not in raw


def test_heartbeat_disabled_never_posts(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    client = make(heartbeat_enabled=False, heartbeat_interval_seconds=0.05)
    client.get_secret(REF)
    time.sleep(0.3)
    assert fake.recorded("/v1/client/heartbeat") == []
    assert client._heartbeat_scheduler is None


def test_heartbeat_payload_for_hook_only_matches_contract_after_delivery(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 5, "v5")
    client = make()
    client.register_refresh_hook(REF, lambda v: None)
    wait_until(lambda: held(client).get("db", {}).get("refreshHookAppliedVersion") == 5)
    client._heartbeat_scheduler._beat_once()
    entry: Optional[dict] = fake.recorded("/v1/client/heartbeat")[-1].body["secrets"][0]
    assert set(entry) == {"projectId", "secretName", "version", "refreshHookAppliedVersion"}


def test_concurrent_register_and_unregister_keeps_the_hook_flag_consistent(env):
    import threading
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    client = make(polling_interval_seconds=60)
    client.get_secret(REF)
    keeper = client.register_refresh_hook(REF, lambda v: None)
    client._usage_ledger.record_hook_applied("grayskull", "db", 1, client._usage_ledger.hook_generation("grayskull", "db"))

    def churn() -> None:
        for _ in range(300):
            client.register_refresh_hook(REF, lambda v: None).unregister()

    threads = [threading.Thread(target=churn) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    client._usage_ledger.record_hook_applied("grayskull", "db", 1, client._usage_ledger.hook_generation("grayskull", "db"))
    assert held(client)["db"].get("refreshHookAppliedVersion") == 1, "the keeper hook is still registered"
    keeper.unregister()
    assert "refreshHookAppliedVersion" not in held(client)["db"]


def test_heartbeat_failed_middle_chunk_still_sends_the_rest(env):
    fake, make = env
    client = make(polling_interval_seconds=60)
    for i in range(120):
        client._usage_ledger.record_version("grayskull", f"s{i}", 1)
    fake.heartbeat_statuses = [204, 503, 204]
    assert client._heartbeat_scheduler._beat_once() is HeartbeatOutcome.FAILED
    assert [len(r.body["secrets"]) for r in fake.recorded("/v1/client/heartbeat")] == [50, 50, 20]


def test_heartbeat_recovers_when_the_server_starts_supporting_it(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    client = make()
    client.get_secret(REF)
    fake.heartbeat_statuses = [404]
    assert client._heartbeat_scheduler._beat_once() is HeartbeatOutcome.UNSUPPORTED
    assert client._heartbeat_scheduler._beat_once() is HeartbeatOutcome.SENT


def test_close_is_bounded_while_a_heartbeat_hangs(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    fake.heartbeat_delay = 30
    client = make(heartbeat_interval_seconds=0.05, read_timeout=60_000)
    client.get_secret(REF)
    wait_until(lambda: len(fake.recorded("/v1/client/heartbeat")) >= 1)
    start = time.monotonic()
    client.close()
    assert time.monotonic() - start < 11, "close waits at most the 10s join"


def test_zero_heartbeat_interval_means_the_daily_default():
    config = GrayskullClientConfiguration(host="http://x", heartbeat_interval_seconds=0)
    assert config.heartbeat_interval_seconds == 86400


def test_adding_a_second_hook_after_an_applied_version_clears_it(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    client = make()
    client.get_secret(REF)
    client.register_refresh_hook(REF, lambda v: None)
    fake.set_secret("grayskull", "db", 7, "v7")
    wait_until(lambda: held(client)["db"].get("refreshHookAppliedVersion") == 7)
    client.register_refresh_hook(REF, lambda v: None)
    assert held(client)["db"]["refreshHookAppliedVersion"] == 0, "hook B has not applied v7"
    fake.set_secret("grayskull", "db", 8, "v8")
    wait_until(lambda: held(client)["db"].get("refreshHookAppliedVersion") == 8)


def test_hook_added_mid_delivery_keeps_the_applied_version_at_zero(env):
    fake, make = env
    fake.set_secret("grayskull", "db", 1, "v1")
    client = make()
    client.get_secret(REF)
    added: List[int] = []

    def adds_a_hook(v: SecretValue) -> None:
        if not added:
            added.append(v.data_version)
            client.register_refresh_hook(REF, lambda _: None)  # registered while this delivery runs

    client.register_refresh_hook(REF, adds_a_hook)
    fake.set_secret("grayskull", "db", 2, "v2")
    wait_until(lambda: added == [2] and held(client)["db"]["version"] == 2)
    time.sleep(0.15)
    assert held(client)["db"]["refreshHookAppliedVersion"] == 0, "the late hook never ran v2"
