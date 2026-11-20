"""Default handle returned for a registered refresh hook."""

import threading
from typing import Callable

from ...client_api.hooks.refresh_handler_ref import RefreshHandlerRef


class DefaultRefreshHandlerRef(RefreshHandlerRef):
    """Runs on_unregister exactly once, on the first unregister()."""

    def __init__(self, secret_ref: str, on_unregister: Callable[[], None]) -> None:
        self._secret_ref = secret_ref
        self._on_unregister = on_unregister
        self._lock = threading.Lock()
        self._active = True

    def get_secret_ref(self) -> str:
        """Return the 'projectId:secretName' the hook is registered for."""
        return self._secret_ref

    def is_active(self) -> bool:
        """Return True until unregister() is called."""
        return self._active

    def unregister(self) -> None:
        """Stop future deliveries; idempotent."""
        with self._lock:
            if not self._active:
                return
            self._active = False
        self._on_unregister()
