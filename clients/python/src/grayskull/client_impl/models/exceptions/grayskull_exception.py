"""Error raised when a Grayskull operation fails."""

from typing import Optional


class GrayskullException(Exception):
    """A failed Grayskull operation with its HTTP status (0 when no response was received)."""

    def __init__(self, status_code: int, message: str, cause: Optional[BaseException] = None) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.__cause__ = cause
