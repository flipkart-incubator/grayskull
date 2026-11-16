"""Exception hierarchy for the Grayskull SDK.

``GrayskullError`` is the only type callers need to catch. ``InvalidArgumentError``
also subclasses ``ValueError`` so ordinary argument-validation handling keeps working.
``RetryableError`` marks transient failures inside the transport; once retries are
exhausted the caller receives a plain ``GrayskullError`` chained to it.
"""

from __future__ import annotations

from typing import Optional

__all__ = ["GrayskullError", "InvalidArgumentError", "RetryableError"]


class GrayskullError(Exception):
    """Base error for every failure raised by the SDK.

    ``status_code`` is the HTTP status that caused the failure, or ``0`` when the
    failure happened before a response was received (validation, network, config).
    """

    def __init__(self, message: str, status_code: int = 0, cause: Optional[BaseException] = None) -> None:
        super().__init__(message)
        self.message = message
        self.status_code = status_code
        if cause is not None:
            self.__cause__ = cause

    def __str__(self) -> str:
        cause = self.__cause__
        return f"{self.message}: {cause}" if cause is not None else self.message

    def __repr__(self) -> str:
        return f"{type(self).__name__}(status_code={self.status_code}, message={self.message!r})"


class InvalidArgumentError(GrayskullError, ValueError):
    """A caller-supplied argument or configuration value is invalid (HTTP 400 semantics)."""

    def __init__(self, message: str) -> None:
        super().__init__(message, status_code=400)


class RetryableError(GrayskullError):
    """A transient failure (network error, HTTP 429 or 5xx) that may succeed on retry.

    ``retry_after`` carries the server's ``Retry-After`` hint in seconds, if any.
    """

    def __init__(
        self,
        message: str,
        status_code: int = 0,
        cause: Optional[BaseException] = None,
        retry_after: Optional[float] = None,
    ) -> None:
        super().__init__(message, status_code, cause)
        self.retry_after = retry_after
