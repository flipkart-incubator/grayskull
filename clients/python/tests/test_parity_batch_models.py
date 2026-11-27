"""Port of Java BatchGetSecretsRequestTest and BatchGetSecretsResponseTest."""

import json

from grayskull.client_impl.models.request.batch_get_secrets_request import BatchGetSecretsEntry, BatchGetSecretsRequest
from grayskull.client_impl.models.response.batch_get_secrets_response import BatchGetSecretsResponse
from grayskull.client_api.models.secret_value import SecretValue


def test_request_serializes_to_canonical_shape():
    body = BatchGetSecretsRequest([BatchGetSecretsEntry("p1", "s1", 0), BatchGetSecretsEntry("p2", "s2", 7)]).to_dict()
    assert len(body["secrets"]) == 2
    assert body["secrets"][0] == {"projectId": "p1", "secretName": "s1", "lastKnownVersion": 0}
    assert body["secrets"][1]["lastKnownVersion"] == 7


def test_request_with_no_entries_serializes_to_an_empty_list():
    assert BatchGetSecretsRequest([]).to_dict() == {"secrets": []}


def test_request_round_trips_through_json():
    original = BatchGetSecretsRequest([BatchGetSecretsEntry("proj", "name", 42)])
    restored = json.loads(json.dumps(original.to_dict()))
    assert restored["secrets"][0] == {"projectId": "proj", "secretName": "name", "lastKnownVersion": 42}


def test_response_populates_all_fields():
    payload = {
        "updatedCount": 1,
        "updatedSecrets": [{"projectId": "p", "secretName": "s", "dataVersion": 4, "publicPart": "pub", "privatePart": "priv"}],
    }
    response = BatchGetSecretsResponse.from_response({"data": payload})
    assert len(response.updated_secrets) == 1
    updated = response.updated_secrets[0]
    assert (updated.project_id, updated.secret_name) == ("p", "s")
    assert updated.value.data_version == 4
    assert updated.value.public_part == "pub"
    assert updated.value.private_part == "priv"


def test_response_ignores_unknown_properties():
    response = BatchGetSecretsResponse.from_response(
        {"data": {"updatedCount": 0, "updatedSecrets": [], "futureField": "ignored"}})
    assert response.updated_secrets == []


def test_response_with_missing_data_is_empty():
    assert BatchGetSecretsResponse.from_response({}).updated_secrets == []


def test_response_entry_round_trips():
    value = SecretValue(data_version=9, public_part="pub", private_part="priv")
    assert value.data_version == 9 and value.private_part == "priv"


def test_private_part_is_not_in_the_repr_of_an_updated_secret():
    from grayskull.client_impl.models.response.batch_get_secrets_response import UpdatedSecret
    updated = UpdatedSecret.from_dict({"projectId": "p", "secretName": "s", "dataVersion": 1, "publicPart": "pub", "privatePart": "TOP_SECRET"})
    assert "TOP_SECRET" not in repr(updated)
