"""Transient failure that the HTTP client retries."""

from typing import Optional


class RetryableException(Exception):
    """A network error, 429 or 5xx response that may succeed on retry."""

    def __init__(self, status_code: int, message: str, cause: Optional[BaseException] = None) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.__cause__ = cause
