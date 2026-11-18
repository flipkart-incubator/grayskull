import base64

import pytest

from grayskull import BasicAuthHeaderProvider


def test_get_auth_header_encodes_credentials():
    header = BasicAuthHeaderProvider("user", "p@ss:word").get_auth_header()
    assert header == "Basic " + base64.b64encode(b"user:p@ss:word").decode()


@pytest.mark.parametrize("username,password", [("", "pass"), ("user", ""), ("", "")])
def test_constructor_rejects_empty_credentials(username, password):
    with pytest.raises(ValueError):
        BasicAuthHeaderProvider(username, password)
