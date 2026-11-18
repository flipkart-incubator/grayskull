"""Supplies the Authorization header for Grayskull requests."""

from abc import ABC, abstractmethod


class GrayskullAuthHeaderProvider(ABC):
    """Returns the Authorization header value; called on every request and must be thread-safe."""

    @abstractmethod
    def get_auth_header(self) -> str:
        """Return the full Authorization header value."""
