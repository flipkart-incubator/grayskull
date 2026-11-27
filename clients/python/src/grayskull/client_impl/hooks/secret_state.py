"""Runtime state of one secret that has refresh hooks."""

import threading
from typing import List, Optional

from ...client_api.hooks.secret_refresh_hook import SecretRefreshHook
from ...client_api.models.secret_value import SecretValue


class SecretState:
    """Staged update, last delivered version and registered hooks of one secret."""

    def __init__(self, project_id: str, secret_name: str, last_known_version: int) -> None:
        self.project_id = project_id
        self.secret_name = secret_name
        self.last_known_version = last_known_version
        self._hooks: List[SecretRefreshHook] = []
        self._lock = threading.Lock()
        self._pending_update: Optional[SecretValue] = None
        self._executing = False

    def reset_after_fork(self) -> None:
        """In a forked child: recreate the lock and release a slot held by a worker that no longer exists."""
        self._lock = threading.Lock()
        self._executing = False

    def set_pending_update(self, value: SecretValue) -> None:
        """Stage an update; a newer value replaces one not yet delivered."""
        with self._lock:
            self._pending_update = value

    def take_pending_update(self) -> Optional[SecretValue]:
        """Remove and return the staged update, if any."""
        with self._lock:
            value, self._pending_update = self._pending_update, None
            return value

    def has_pending_update(self) -> bool:
        """Return True when an update is staged."""
        with self._lock:
            return self._pending_update is not None

    def try_start_execution(self) -> bool:
        """Claim the execution slot so hooks for this secret never run concurrently."""
        with self._lock:
            if self._executing:
                return False
            self._executing = True
            return True

    def finish_execution(self) -> None:
        """Release the execution slot."""
        with self._lock:
            self._executing = False

    def add_hook(self, hook: SecretRefreshHook) -> None:
        """Attach a hook in registration order."""
        with self._lock:
            self._hooks.append(hook)

    def remove_hook(self, hook: SecretRefreshHook) -> bool:
        """Detach the first occurrence of hook; return True if it was attached."""
        with self._lock:
            for index, attached in enumerate(self._hooks):
                if attached is hook:
                    del self._hooks[index]
                    return True
            return False

    def has_hooks(self) -> bool:
        """Return True while at least one hook is attached."""
        with self._lock:
            return bool(self._hooks)

    def snapshot_hooks(self) -> List[SecretRefreshHook]:
        """Copy the hook list so hooks run without holding a lock."""
        with self._lock:
            return list(self._hooks)
