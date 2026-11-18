"""Handle to a registered refresh hook."""

from abc import ABC, abstractmethod


class RefreshHandlerRef(ABC):
    """Handle returned by register_refresh_hook; unregister() stops future deliveries."""

    @abstractmethod
    def get_secret_ref(self) -> str:
        """Return the 'projectId:secretName' the hook is registered for."""

    @abstractmethod
    def is_active(self) -> bool:
        """Return True until unregister() is called."""

    @abstractmethod
    def unregister(self) -> None:
        """Stop future deliveries to the hook; idempotent."""
