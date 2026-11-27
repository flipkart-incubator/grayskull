"""The Python client against a real Grayskull server (see conftest.py)."""

import time
from urllib.parse import quote

import pytest
from it_support import ADMIN, fixture_json

from grayskull import (BasicAuthHeaderProvider, GrayskullClientConfiguration, GrayskullClientImpl,
                       GrayskullException, WorkloadIdentityResolver)


class Fixed(WorkloadIdentityResolver):
    def resolve(self) -> str:
        return "v1::it::ns::ReplicaSetOwned::python-it"


@pytest.fixture
def make_client(proxy):
    made = []

    def build(user: str = ADMIN, **settings) -> GrayskullClientImpl:
        config = dict(host=proxy.url, heartbeat_enabled=False, polling_interval_seconds=1,
                      min_retry_delay=50, max_retries=2, workload_identity_resolver=Fixed())
        config.update(settings)
        client = GrayskullClientImpl(BasicAuthHeaderProvider(user, "any"), GrayskullClientConfiguration(**config))
        made.append(client)
        return client

    yield build
    for client in made:
        client.close()


def wait_until(predicate, timeout=20.0, message="condition"):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.1)
    raise AssertionError(f"{message} not met within {timeout}s")


def assert_same_shape(actual, expected, path="$"):
    """Every field in the fixture is present in the real response with the same JSON type."""
    if isinstance(expected, dict):
        assert isinstance(actual, dict), f"{path}: expected an object, got {actual!r}"
        for key, value in expected.items():
            assert key in actual, f"{path}.{key} missing from the server response"
            assert_same_shape(actual[key], value, f"{path}.{key}")
    elif isinstance(expected, list):
        assert isinstance(actual, list), f"{path}: expected an array, got {actual!r}"
        for item in actual:
            assert_same_shape(item, expected[0], f"{path}[]")
    elif isinstance(expected, bool) or expected is None:
        assert actual is None or isinstance(actual, type(expected)), f"{path}: {actual!r}"
    elif isinstance(expected, (int, float)):
        assert isinstance(actual, (int, float)) and not isinstance(actual, bool), f"{path}: {actual!r}"
    else:
        assert isinstance(actual, str), f"{path}: expected a string, got {actual!r}"


# ---- reads ----

def test_get_secret_returns_the_created_value(make_client, admin, unique):
    admin.create_secret(unique, "db", "db-user", "s3cret")
    value = make_client().get_secret(f"{unique}:db")
    assert (value.data_version, value.public_part, value.private_part) == (1, "db-user", "s3cret")


def test_get_secret_returns_the_latest_version_after_an_upgrade(make_client, admin, unique):
    admin.create_secret(unique, "db", "db-user", "v1")
    admin.upgrade_secret(unique, "db", "db-user", "v2")
    value = make_client().get_secret(f"{unique}:db")
    assert (value.data_version, value.private_part) == (2, "v2")


def test_unknown_secret_is_a_client_error(make_client, unique):
    # The server checks authorization against the secret before looking it up, so a secret that
    # does not exist comes back as 403 Access Denied rather than 404.
    with pytest.raises(GrayskullException) as info:
        make_client().get_secret(f"{unique}:missing")
    assert info.value.status_code in (403, 404)


def test_user_without_permission_gets_403(make_client, admin, unique):
    admin.create_secret(unique, "db", "db-user", "s3cret")
    with pytest.raises(GrayskullException) as info:
        make_client(user="nobody").get_secret(f"{unique}:db")
    assert info.value.status_code == 403


# Names the server accepts round-trip through the client's path encoding unchanged. Spring's
# StrictHttpFirewall rejects encoded '/', '%' and ';' with 400 before the controller runs; the Java
# and Go clients send the same encoded bytes, so the server rejects them for every SDK.
@pytest.mark.parametrize("name", ["a b", "a+b", "a:b", "a?b", "a#b", "näme-日本", "a_b.c-d~e"])
def test_special_characters_round_trip(make_client, admin, unique, name):
    admin.create_secret(unique, name, "pub", f"value-of-{name}")
    assert make_client().get_secret(f"{unique}:{name}").private_part == f"value-of-{name}"


@pytest.mark.parametrize("name", ["a/b", "a%b", "a;b"])
def test_characters_rejected_by_the_server_firewall_fail_cleanly(make_client, admin, unique, name):
    admin.create_secret(unique, name, "pub", "v")
    with pytest.raises(GrayskullException) as info:
        make_client().get_secret(f"{unique}:{name}")
    assert info.value.status_code == 400


# ---- refresh hooks ----

def test_refresh_hook_receives_the_upgraded_version(make_client, admin, unique):
    admin.create_secret(unique, "db", "db-user", "v1")
    client = make_client()
    assert client.get_secret(f"{unique}:db").data_version == 1
    delivered = []
    client.register_refresh_hook(f"{unique}:db", delivered.append)
    admin.upgrade_secret(unique, "db", "db-user", "v2")
    wait_until(lambda: delivered, message="hook delivery")
    assert (delivered[0].data_version, delivered[0].private_part) == (2, "v2")


def test_more_than_fifty_hooked_secrets_are_polled_in_chunks(make_client, admin, unique):
    client = make_client()
    delivered = {}
    for i in range(55):
        admin.create_secret(unique, f"s{i}", "pub", "v1")
        client.register_refresh_hook(f"{unique}:s{i}", lambda value, i=i: delivered.setdefault(i, value))
    wait_until(lambda: len(delivered) == 55, timeout=30, message="all 55 hooks")  # first delivery is version 1
    assert {v.data_version for v in delivered.values()} == {1}


# ---- wire contract against the shared fixtures ----

def test_get_data_response_matches_the_wire_fixture(admin, unique):
    admin.create_secret(unique, "db", "db-user", "s3cret")
    status, body = admin.call("GET", f"/v1/projects/{unique}/secrets/db/data")
    assert status == 200
    assert_same_shape(body, fixture_json("wire/get_secret_data_response_v1.json"))


def test_batch_response_matches_the_wire_fixture(admin, unique):
    admin.create_secret(unique, "db", "db-user", "v1")
    admin.upgrade_secret(unique, "db", "db-user", "v2")
    admin.upgrade_secret(unique, "db", "db-user", "v3")
    admin.create_secret(unique, "never-fetched", "pub", "v1")
    request = fixture_json("wire/batch_get_secrets_request_v1.json")
    for entry in request["secrets"]:
        entry["projectId"] = unique
    status, body = admin.call("POST", "/v1/secrets/batch", request)
    assert status == 200, body
    assert_same_shape(body, fixture_json("wire/batch_get_secrets_response_v1.json"))
    updated = sorted((s["secretName"], s["dataVersion"]) for s in body["data"]["updatedSecrets"])
    assert updated == [("db", 3), ("never-fetched", 1)]  # the fixture sends db at v2


def test_batch_with_one_missing_secret_fails_the_whole_request(admin, unique):
    # Pins current server behaviour: one deleted or unknown secret turns the whole chunk into a 404,
    # so the other secrets in that chunk get no refresh until the hook for the missing one is removed.
    admin.create_secret(unique, "db", "db-user", "v1")
    status, body = admin.call("POST", "/v1/secrets/batch", {"secrets": [
        {"projectId": unique, "secretName": "db", "lastKnownVersion": 0},
        {"projectId": unique, "secretName": "missing", "lastKnownVersion": 0}]})
    assert status == 404, body


def test_batch_request_over_fifty_entries_is_rejected_by_the_server(admin, unique):
    entries = [{"projectId": unique, "secretName": f"s{i}", "lastKnownVersion": 0} for i in range(51)]
    status, _ = admin.call("POST", "/v1/secrets/batch", {"secrets": entries})
    assert status == 400  # why the client chunks at 50


# ---- heartbeat (stubbed until the server has the endpoint) ----

def test_heartbeat_reports_held_secrets_in_the_fixture_shape(make_client, admin, proxy, unique):
    admin.create_secret(unique, "db", "db-user", "s3cret")
    admin.create_secret(unique, "hooked", "pub", "v1")
    client = make_client(heartbeat_enabled=True, heartbeat_interval_seconds=1)
    client.get_secret(f"{unique}:db")
    client.get_secret(f"{unique}:hooked")
    client.register_refresh_hook(f"{unique}:hooked", lambda value: None)
    wait_until(lambda: proxy.recorded(), timeout=10, message="first heartbeat")
    headers, body = proxy.recorded()[-1]
    assert headers.get("Grayskull-Workload") == Fixed().resolve()
    assert headers.get("User-Agent", "").startswith("grayskull-python/")
    expected = fixture_json("heartbeat/client_heartbeat_v1.json")
    assert set(body) == set(expected)
    by_name = {s["secretName"]: s for s in body["secrets"]}
    assert by_name["db"] == {"projectId": unique, "secretName": "db", "version": 1}
    assert by_name["hooked"]["refreshHookAppliedVersion"] == 0
    assert "s3cret" not in str(body)


def test_reads_keep_working_when_the_heartbeat_endpoint_is_missing(make_client, admin, proxy, unique):
    proxy.heartbeat_status = 404  # what today's server answers
    admin.create_secret(unique, "db", "db-user", "s3cret")
    client = make_client(heartbeat_enabled=True, heartbeat_interval_seconds=1)
    client.get_secret(f"{unique}:db")
    wait_until(lambda: proxy.recorded(), timeout=10, message="heartbeat attempt")
    assert client.get_secret(f"{unique}:db").private_part == "s3cret"


def test_server_without_heartbeat_endpoint_answers_404(admin):
    status, _ = admin.call("POST", "/v1/client/heartbeat", {"secrets": []})
    assert status in (404, 405)  # flip GRAYSKULL_IT_HEARTBEAT_STUB=0 once this starts passing as 2xx
