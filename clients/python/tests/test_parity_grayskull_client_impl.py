"""Port of Java GrayskullClientImplTest.

Java mocks GrayskullHttpClient with Mockito; these tests run the real client against FakeApi, so each
assertion is on what reached the wire. Polling is effectively off (interval 3600 configured seconds)
and tests call poll_once() directly, as the Java tests call pollOnce() by reflection.

Not ported, with the Python equivalent:
- resolveSdkVersion_* (6 tests): Java reads the version from a filtered classpath resource. Python
  reads grayskull.__version__; the User-Agent tests below and test_22 cover it.
- close_handlesNullHttpClient: Python never builds a client without an HTTP client.
"""

import json
import re
import threading

import pytest
from parity_helpers import (FakeApi, RecordingMetrics, Scripted, ScriptedServer, StaticAuth, StaticResolver,
                            batch_body, new_client, secret_body, wait_until)

from grayskull import (BasicAuthHeaderProvider, GrayskullClientConfiguration, GrayskullClientImpl,
                       GrayskullException, GrayskullHeaders, __version__)

UA = re.compile(r"^grayskull-python/\S+$")


@pytest.fixture
def web():
    server = ScriptedServer()
    yield server
    server.stop()


@pytest.fixture
def api(web):
    return FakeApi(web)


@pytest.fixture
def clients():
    made = []
    yield made
    for client in made:
        client.close()


@pytest.fixture
def client(web, api, clients):
    c = new_client(web.url)
    clients.append(c)
    return c


def poll_once(client: GrayskullClientImpl) -> None:
    client._refresh_poller.poll_once()


def sent_versions(api: FakeApi, index: int = -1):
    return {f"{e['projectId']}:{e['secretName']}": e["lastKnownVersion"] for e in api.batch_requests()[index]["secrets"]}


# ---- constructor ----

def test_constructor_null_auth_header_provider():
    with pytest.raises(ValueError, match="authHeaderProvider"):
        GrayskullClientImpl(None, GrayskullClientConfiguration(host="http://localhost"))


def test_constructor_null_configuration():
    with pytest.raises(ValueError, match="grayskullClientConfiguration"):
        GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"), None)


def test_constructor_both_null():
    with pytest.raises(ValueError):
        GrayskullClientImpl(None, None)


@pytest.mark.parametrize("header", [None, "", "   "])
def test_constructor_succeeds_but_get_secret_rejects_bad_auth_header(web, api, clients, header):
    c = new_client(web.url, auth=StaticAuth(header))
    clients.append(c)
    with pytest.raises(GrayskullException) as info:
        c.get_secret("project:secret")
    assert info.value.status_code == 0
    assert web.requests("/data") == []


def test_invalid_base_url_is_rejected_at_construction():
    with pytest.raises(ValueError, match="Invalid host URL"):
        GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"), GrayskullClientConfiguration(host="ftp://bad"))


# ---- get_secret ----

def test_get_secret_success(client, api, web):
    api.set_secret("project", "secret", 3, "pub", "priv")
    value = client.get_secret("project:secret")
    assert (value.data_version, value.public_part, value.private_part) == (3, "pub", "priv")
    assert web.requests()[0].path == "/v1/projects/project/secrets/secret/data"


@pytest.mark.parametrize("ref", [None, ""])
def test_get_secret_null_or_empty_ref(client, ref):
    with pytest.raises(ValueError, match="cannot be null or empty"):
        client.get_secret(ref)


@pytest.mark.parametrize("ref", ["no-colon", ":secret", "project:"])
def test_get_secret_invalid_format(client, web, ref):
    with pytest.raises(ValueError, match="Invalid secretRef format"):
        client.get_secret(ref)
    assert web.requests() == []


def test_get_secret_multiple_colons_keep_the_rest_in_the_secret_name(client, api, web):
    # Java leaves ':' unencoded and Python sends %3A; the server decodes both to the same name.
    api.set_secret("my-project", "secret:with:colons", 1)
    assert client.get_secret("my-project:secret:with:colons").data_version == 1
    assert web.requests()[0].path == "/v1/projects/my-project/secrets/secret%3Awith%3Acolons/data"


def test_get_secret_with_special_characters(client, api, web):
    api.set_secret("my project", "a/b?c#d", 2)
    assert client.get_secret("my project:a/b?c#d").data_version == 2
    assert web.requests()[0].path == "/v1/projects/my%20project/secrets/a%2Fb%3Fc%23d/data"


def test_get_secret_null_data_throws(client, api):
    api.get_replies.append(Scripted(body={"data": None, "message": "No Data"}))
    with pytest.raises(GrayskullException, match="No data"):
        client.get_secret("project:secret")


def test_get_secret_malformed_json_throws(client, api):
    api.get_replies.append(Scripted(body=b"{not-json"))
    with pytest.raises(GrayskullException):
        client.get_secret("project:secret")


def test_get_secret_failed_call_does_not_record_version(client, api):
    api.get_replies.append(Scripted(404, {"message": "missing"}))
    with pytest.raises(GrayskullException):
        client.get_secret("team:gone")
    assert client._usage_ledger.version("team", "gone") == 0


# ---- register_refresh_hook ----

def test_register_refresh_hook_success(client):
    ref = client.register_refresh_hook("project:secret", lambda value: None)
    assert ref.get_secret_ref() == "project:secret"
    assert ref.is_active()


def test_register_refresh_hook_can_unregister(client):
    ref = client.register_refresh_hook("project:secret", lambda value: None)
    ref.unregister()
    assert not ref.is_active()
    poll_once(client)  # nothing left to poll


@pytest.mark.parametrize("ref", [None, ""])
def test_register_refresh_hook_null_or_empty_ref(client, ref):
    with pytest.raises(ValueError):
        client.register_refresh_hook(ref, lambda value: None)


def test_register_refresh_hook_null_hook(client):
    with pytest.raises(ValueError, match="hook cannot be null"):
        client.register_refresh_hook("project:secret", None)


def test_register_refresh_hook_invalid_format_throws_before_registering(client, api):
    with pytest.raises(ValueError):
        client.register_refresh_hook("no-colon", lambda value: None)
    poll_once(client)
    assert api.batch_requests() == []


def test_register_refresh_hook_after_close_throws(client):
    client.close()
    with pytest.raises(RuntimeError, match="closed"):
        client.register_refresh_hook("project:secret", lambda value: None)


# ---- seeding the poller from get_secret ----

def test_get_secret_then_register_hook_seeds_poller_with_observed_version(client, api):
    api.set_secret("team", "db", 7)
    client.get_secret("team:db")
    client.register_refresh_hook("team:db", lambda value: None)
    poll_once(client)
    assert sent_versions(api) == {"team:db": 7}


def test_register_hook_without_prior_get_secret_starts_from_version_zero(client, api):
    client.register_refresh_hook("team:db", lambda value: None)
    poll_once(client)
    assert sent_versions(api) == {"team:db": 0}


def test_multiple_get_secret_calls_seed_uses_last_observed_version(client, api):
    api.set_secret("team", "db", 4)
    client.get_secret("team:db")
    api.set_secret("team", "db", 9)
    client.get_secret("team:db")
    client.register_refresh_hook("team:db", lambda value: None)
    api.batch_replies.append(Scripted(body=batch_body()))
    poll_once(client)
    assert sent_versions(api) == {"team:db": 9}


def test_register_hook_then_get_secret_does_not_retroactively_update_poller(client, api):
    client.register_refresh_hook("team:late-get", lambda value: None)
    api.set_secret("team", "late-get", 11)
    client.get_secret("team:late-get")
    api.batch_replies.append(Scripted(body=batch_body()))
    poll_once(client)
    assert sent_versions(api) == {"team:late-get": 0}


def test_get_secret_of_a_different_secret_does_not_seed_unrelated_hook(client, api):
    api.set_secret("team", "other", 5)
    client.get_secret("team:other")
    client.register_refresh_hook("team:db", lambda value: None)
    poll_once(client)
    assert sent_versions(api) == {"team:db": 0}


def test_two_hooks_same_secret_second_registration_leaves_version_intact(client, api):
    api.set_secret("team", "db", 3)
    client.get_secret("team:db")
    client.register_refresh_hook("team:db", lambda value: None)
    api.set_secret("team", "db", 8)
    client.get_secret("team:db")
    client.register_refresh_hook("team:db", lambda value: None)  # must not re-seed to 8
    api.batch_replies.append(Scripted(body=batch_body()))
    poll_once(client)
    assert sent_versions(api) == {"team:db": 3}


# ---- poll_once ----

def test_poll_once_error_from_http_layer_does_not_stop_the_poller(client, api, monkeypatch):
    client.register_refresh_hook("team:err", lambda value: None)
    real = client._http_client.do_post_with_retry

    def boom(*args):
        raise AssertionError("simulated low-level error")

    monkeypatch.setattr(client._http_client, "do_post_with_retry", boom)
    poll_once(client)  # contained per chunk
    monkeypatch.setattr(client._http_client, "do_post_with_retry", real)
    poll_once(client)
    assert len(api.batch_requests()) == 1


def test_poll_once_no_registered_secrets_does_not_post(client, api):
    poll_once(client)
    assert api.batch_requests() == []


def test_poll_once_single_chunk_empty_updated_secrets_one_post(client, api):
    client.register_refresh_hook("acme:one", lambda value: None)
    poll_once(client)
    assert len(api.batch_requests()) == 1


@pytest.mark.parametrize("body", [{"data": None, "message": "ok"}, {"data": {"updatedCount": 0}, "message": "ok"}])
def test_poll_once_null_data_or_missing_updated_secrets_continues(client, api, body):
    calls = []
    client.register_refresh_hook("acme:one", calls.append)
    api.batch_replies.append(Scripted(body=body))
    poll_once(client)
    poll_once(client)
    assert len(api.batch_requests()) == 2
    assert calls == []


def test_poll_once_fifty_one_secrets_two_sequential_posts(client, api, web):
    for i in range(51):
        client.register_refresh_hook(f"corp:svc-{i}", lambda value: None)
    poll_once(client)
    sizes = [len(body["secrets"]) for body in api.batch_requests()]
    assert sizes == [50, 1]
    ids = {s.header("X-Request-Id") for s in web.requests("/v1/secrets/batch")}
    assert len(ids) == 1  # one request id per cycle


def test_poll_once_first_chunk_fails_second_chunk_still_runs_and_failure_status_is_recorded(web, api, clients):
    metrics = RecordingMetrics()
    c = new_client(web.url, metrics=metrics, max_retries=1)
    clients.append(c)
    for i in range(51):
        c.register_refresh_hook(f"corp:svc-{i}", lambda value: None)
    api.batch_replies.append(Scripted(503, {"message": "batch unavailable"}))
    poll_once(c)
    assert len(api.batch_requests()) == 2
    assert metrics.statuses("batchGetSecrets") == [503, 200]


def test_poll_once_chunk_returns_grayskull_error(client, api):
    calls = []
    client.register_refresh_hook("acme:one", calls.append)
    api.batch_replies.append(Scripted(400, {"message": "bad"}))
    poll_once(client)
    assert calls == []


def test_poll_once_chunk_invalid_json_is_contained(client, api):
    client.register_refresh_hook("acme:one", lambda value: None)
    api.batch_replies.append(Scripted(body=b"{not-json"))
    poll_once(client)
    assert len(api.batch_requests()) == 1


def test_poll_once_batch_returns_updated_secret_invokes_hook(client, api):
    delivered = []
    client.register_refresh_hook("acme:one", delivered.append)
    api.set_secret("acme", "one", 2, "new-pub")
    poll_once(client)
    wait_until(lambda: delivered, message="hook delivery")
    assert (delivered[0].data_version, delivered[0].public_part) == (2, "new-pub")


def test_poll_once_without_get_secret_sends_last_known_version_zero(client, api):
    client.register_refresh_hook("acme:one", lambda value: None)
    poll_once(client)
    assert api.batch_requests()[0] == {"secrets": [{"projectId": "acme", "secretName": "one", "lastKnownVersion": 0}]}


def test_poll_once_multiple_hooks_same_secret_run_in_registration_order(client, api):
    order = []
    for name in ("first", "second", "third"):
        client.register_refresh_hook("acme:one", lambda value, n=name: order.append(n))
    api.set_secret("acme", "one", 1)
    poll_once(client)
    wait_until(lambda: len(order) == 3, message="all hooks")
    assert order == ["first", "second", "third"]


def test_poll_once_hook_throws_is_swallowed_and_others_continue(client, api):
    seen = []

    def bad(value):
        raise RuntimeError("hook failed")

    client.register_refresh_hook("acme:one", bad)
    client.register_refresh_hook("acme:one", seen.append)
    api.set_secret("acme", "one", 1)
    poll_once(client)
    wait_until(lambda: seen, message="second hook")
    api.set_secret("acme", "one", 2)
    poll_once(client)
    wait_until(lambda: len(seen) == 2, message="next delivery")
    assert [v.data_version for v in seen] == [1, 2]


def test_two_hooks_same_secret_unregister_one_other_survives(client, api):
    first, second = [], []
    ref = client.register_refresh_hook("acme:one", first.append)
    client.register_refresh_hook("acme:one", second.append)
    ref.unregister()
    api.set_secret("acme", "one", 1)
    poll_once(client)
    wait_until(lambda: second, message="surviving hook")
    assert first == []


# ---- close ----

def test_close_cleans_up_resources(client):
    poller = client._refresh_poller._scheduler
    client.close()
    poller.join(2)
    assert not poller.is_alive()
    assert client._http_client._pool._closed


def test_close_called_twice_is_idempotent(client):
    client.close()
    client.close()


# ---- identity headers ----

def test_constructor_populates_workload_header_from_default_resolver(web, api, clients):
    config = GrayskullClientConfiguration(host=web.url, heartbeat_enabled=False)
    c = GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"), config)
    clients.append(c)
    assert config.get_default_headers()[GrayskullHeaders.WORKLOAD] == config.workload_identity_resolver.resolve()


def test_constructor_populates_workload_header_from_custom_resolver(web, api, clients):
    c = new_client(web.url, workload_identity_resolver=StaticResolver("custom-pod"))
    clients.append(c)
    api.set_secret("p", "s", 1)
    c.get_secret("p:s")
    assert web.requests("/data")[0].header(GrayskullHeaders.WORKLOAD) == "custom-pod"


def test_constructor_resolves_identity_exactly_once(web, api, clients):
    resolver = StaticResolver("pod-1")
    c = new_client(web.url, workload_identity_resolver=resolver)
    clients.append(c)
    api.set_secret("p", "s", 1)
    c.get_secret("p:s")
    c.get_secret("p:s")
    assert resolver.calls == 1


def test_constructor_populates_user_agent_sdk_product_and_version_only(web, clients):
    config = GrayskullClientConfiguration(host=web.url, heartbeat_enabled=False,
                                          workload_identity_resolver=StaticResolver("pod-1"))
    clients.append(GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"), config))
    assert config.get_default_headers()[GrayskullHeaders.USER_AGENT] == f"grayskull-python/{__version__}"


@pytest.mark.parametrize("identity", [
    "spiffe://flipkart/ns/payments/sa/checkout", "pod-with (parens) and spaces", "plain-pod"])
def test_user_agent_does_not_leak_identity_and_is_stable_across_shapes(web, clients, identity):
    config = GrayskullClientConfiguration(host=web.url, heartbeat_enabled=False,
                                          workload_identity_resolver=StaticResolver(identity))
    clients.append(GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"), config))
    user_agent = config.get_default_headers()[GrayskullHeaders.USER_AGENT]
    assert UA.match(user_agent)
    assert identity not in user_agent


def test_sdk_user_agent_wins_over_user_seeded_user_agent(web, clients):
    config = GrayskullClientConfiguration(host=web.url, heartbeat_enabled=False,
                                          workload_identity_resolver=StaticResolver("pod-1"))
    config.add_default_header(GrayskullHeaders.USER_AGENT, "my-app/1.0")
    clients.append(GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"), config))
    assert config.get_default_headers()[GrayskullHeaders.USER_AGENT] == f"grayskull-python/{__version__}"


def test_sdk_workload_wins_over_user_seeded_workload_header(web, clients):
    config = GrayskullClientConfiguration(host=web.url, heartbeat_enabled=False,
                                          workload_identity_resolver=StaticResolver("resolved-pod"))
    config.add_default_header(GrayskullHeaders.WORKLOAD, "user-seeded")
    clients.append(GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"), config))
    assert config.get_default_headers()[GrayskullHeaders.WORKLOAD] == "resolved-pod"


def test_get_secret_emits_single_user_agent_header_on_wire(client, api, web):
    api.set_secret("p", "s", 1)
    client.get_secret("p:s")
    assert web.requests("/data")[0].all("User-Agent") == [f"grayskull-python/{__version__}"]


def test_get_secret_records_one_request_metric_with_the_secret_ref(web, api, clients):
    metrics = RecordingMetrics()
    c = new_client(web.url, metrics=metrics)
    clients.append(c)
    api.set_secret("p", "s", 1)
    c.get_secret("p:s")
    assert metrics.names() == ["getSecret.p:s"]
    assert metrics.statuses("getSecret.p:s") == [200]


def test_concurrent_get_secret_calls_are_safe(client, api):
    api.set_secret("p", "s", 1)
    errors = []

    def worker():
        try:
            for _ in range(20):
                assert client.get_secret("p:s").data_version == 1
        except Exception as exc:  # pragma: no cover - reported below
            errors.append(exc)

    threads = [threading.Thread(target=worker) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(10)
    assert errors == []
