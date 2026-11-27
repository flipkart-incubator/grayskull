"""HTTP Basic authentication."""

import base64

from .grayskull_auth_header_provider import GrayskullAuthHeaderProvider


class BasicAuthHeaderProvider(GrayskullAuthHeaderProvider):
    """Authenticates with a fixed username and password."""

    def __init__(self, username: str, password: str) -> None:
        if not username or not password:
            raise ValueError("Username and password cannot be empty")
        token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
        self._auth_header = f"Basic {token}"

    def get_auth_header(self) -> str:
        """Return the precomputed Basic Authorization header."""
        return self._auth_header
