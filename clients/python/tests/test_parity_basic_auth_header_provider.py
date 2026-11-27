"""Port of Java BasicAuthHeaderProviderTest."""

import base64

import pytest

from grayskull import BasicAuthHeaderProvider


def _decode(header: str) -> str:
    assert header.startswith("Basic ")
    return base64.b64decode(header[len("Basic "):]).decode("utf-8")


def test_header_encodes_username_and_password():
    header = BasicAuthHeaderProvider("testuser", "testpass").get_auth_header()
    assert _decode(header) == "testuser:testpass"


@pytest.mark.parametrize("username, password", [(None, "password"), ("username", None), (None, None), ("", ""), ("", "pw"), ("user", "")])
def test_missing_or_empty_credentials_are_rejected(username, password):
    with pytest.raises(ValueError):
        BasicAuthHeaderProvider(username, password)


def test_header_is_stable_across_calls():
    provider = BasicAuthHeaderProvider("u", "p")
    assert provider.get_auth_header() == provider.get_auth_header()


def test_non_ascii_credentials_are_encoded_as_utf8():
    header = BasicAuthHeaderProvider("josé", "pässword").get_auth_header()
    assert _decode(header) == "josé:pässword"
