"""Grayskull client interface."""

from abc import ABC, abstractmethod
from types import TracebackType
from typing import Optional, Type

from .hooks.refresh_handler_ref import RefreshHandlerRef
from .hooks.secret_refresh_hook import SecretRefreshHook
from .models.secret_value import SecretValue


class GrayskullClient(ABC):
    """Reads secrets from Grayskull and delivers secret updates to refresh hooks."""

    @abstractmethod
    def get_secret(self, secret_ref: str) -> SecretValue:
        """Return the current value of the secret referenced as 'projectId:secretName'."""

    @abstractmethod
    def register_refresh_hook(self, secret_ref: str, hook: SecretRefreshHook) -> RefreshHandlerRef:
        """Invoke hook with each newer version of secret_ref until the returned handle is unregistered."""

    @abstractmethod
    def close(self) -> None:
        """Release background resources; safe to call more than once."""

    def __enter__(self) -> "GrayskullClient":
        return self

    def __exit__(
        self,
        exc_type: Optional[Type[BaseException]],
        exc: Optional[BaseException],
        tb: Optional[TracebackType],
    ) -> None:
        self.close()
