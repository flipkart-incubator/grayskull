"""Built-in authentication header providers."""

from __future__ import annotations

import base64

from grayskull.errors import InvalidArgumentError

__all__ = ["BasicAuthHeaderProvider"]


class BasicAuthHeaderProvider:
    """HTTP Basic auth. The header is computed once; credentials are immutable."""

    __slots__ = ("_header",)

    def __init__(self, username: str, password: str) -> None:
        if not username or not password:
            raise InvalidArgumentError("username and password cannot be empty")
        token = base64.b64encode(f"{username}:{password}".encode()).decode("ascii")
        self._header = f"Basic {token}"

    def get_auth_header(self) -> str:
        return self._header

    def __repr__(self) -> str:
        return "BasicAuthHeaderProvider(***)"
