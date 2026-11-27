"""The Python client against the shared wire fixtures in clients/testdata.

Requests must serialize to exactly the fixture body; responses must parse into the expected values.
The integration suite checks the same response fixtures against the real server.
"""

import json
from pathlib import Path

from parity_helpers import Scripted, ScriptedServer, new_client

from grayskull.client_api.models.secret_value import SecretValue
from grayskull.client_impl.models.request.batch_get_secrets_request import (BatchGetSecretsEntry,
                                                                            BatchGetSecretsRequest)
from grayskull.client_impl.models.request.heartbeat_request import HeartbeatRequest, HeldSecret
from grayskull.client_impl.models.response.batch_get_secrets_response import BatchGetSecretsResponse

TESTDATA = Path(__file__).resolve().parents[2] / "testdata"


def fixture(name: str):
    return json.loads((TESTDATA / name).read_text(encoding="utf-8"))


def test_batch_request_matches_fixture():
    request = BatchGetSecretsRequest([BatchGetSecretsEntry("p", "db", 2), BatchGetSecretsEntry("p", "never-fetched", 0)])
    assert request.to_dict() == fixture("wire/batch_get_secrets_request_v1.json")


def test_heartbeat_request_matches_fixture():
    request = HeartbeatRequest([HeldSecret("p", "s", 5, 5), HeldSecret("p", "hook-pending", 3, 0),
                                HeldSecret("p", "no-hook", 2)])
    assert request.to_dict() == fixture("heartbeat/client_heartbeat_v1.json")


def test_batch_response_fixture_parses():
    response = BatchGetSecretsResponse.from_response(fixture("wire/batch_get_secrets_response_v1.json"))
    [updated] = response.updated_secrets
    assert (updated.project_id, updated.secret_name) == ("p", "db")
    assert updated.value == SecretValue(3, "db-user", "n3w")


def test_get_secret_fixture_parses_through_the_client():
    web = ScriptedServer()
    web.enqueue(Scripted(body=fixture("wire/get_secret_data_response_v1.json")))
    client = new_client(web.url)
    try:
        assert client.get_secret("p:db") == SecretValue(2, "db-user", "s3cret")
    finally:
        client.close()
        web.stop()


def test_poller_sends_the_fixture_body_on_the_wire():
    web = ScriptedServer()
    web.enqueue(Scripted(body=fixture("wire/get_secret_data_response_v1.json")))
    client = new_client(web.url)
    try:
        client.get_secret("p:db")
        client.register_refresh_hook("p:db", lambda value: None)
        client.register_refresh_hook("p:never-fetched", lambda value: None)
        client._refresh_poller.poll_once()
        [batch] = web.requests("/v1/secrets/batch")
        assert batch.json() == fixture("wire/batch_get_secrets_request_v1.json")
    finally:
        client.close()
        web.stop()
