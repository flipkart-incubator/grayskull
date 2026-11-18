"""Per-secret usage for the heartbeat; entries are never removed."""

import threading
from typing import Dict, List, Tuple

from .models.request.heartbeat_request import HeldSecret


class _Entry:
    __slots__ = ("version", "hook_registered", "hook_applied_version", "hook_generation")

    def __init__(self) -> None:
        self.version = 0
        self.hook_registered = False
        self.hook_applied_version = 0
        self.hook_generation = 0  # changes whenever hooks are added or removed


class UsageLedger:
    """Thread-safe record of the newest version held and the refresh-hook outcome per secret."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._entries: Dict[Tuple[str, str], _Entry] = {}

    def record_fetch(self, project_id: str, secret_name: str, version: int) -> None:
        """Record a successful get_secret."""
        self.record_version(project_id, secret_name, version)

    def record_version(self, project_id: str, secret_name: str, version: int) -> None:
        """Record a version seen by the poller; never lowers the held version."""
        with self._lock:
            entry = self._entry(project_id, secret_name)
            entry.version = max(entry.version, version)

    def set_hook_registered(self, project_id: str, secret_name: str, registered: bool) -> None:
        """Record whether any refresh hook is still registered for the secret."""
        with self._lock:
            entry = self._entry(project_id, secret_name)
            entry.hook_registered = registered
            entry.hook_generation += 1
            if not registered:
                entry.hook_applied_version = 0  # a hook registered later must apply a version itself

    def record_hook_added(self, project_id: str, secret_name: str) -> None:
        """Record a newly added hook; it has applied nothing yet, so the applied version is cleared."""
        with self._lock:
            entry = self._entry(project_id, secret_name)
            entry.hook_registered = True
            entry.hook_applied_version = 0
            entry.hook_generation += 1

    def hook_generation(self, project_id: str, secret_name: str) -> int:
        """Return the current hook generation; capture it before reading the hook list."""
        with self._lock:
            entry = self._entries.get((project_id, secret_name))
            return entry.hook_generation if entry is not None else 0

    def record_hook_applied(self, project_id: str, secret_name: str, version: int, hook_generation: int) -> None:
        """Record that every hook succeeded for version; ignored if hooks changed since hook_generation."""
        with self._lock:
            entry = self._entry(project_id, secret_name)
            if entry.hook_generation == hook_generation:
                entry.hook_applied_version = max(entry.hook_applied_version, version)

    def version(self, project_id: str, secret_name: str) -> int:
        """Return the newest version held, or 0."""
        with self._lock:
            entry = self._entries.get((project_id, secret_name))
            return entry.version if entry is not None else 0

    def snapshot(self) -> List[HeldSecret]:
        """Return entries that hold a version; the applied version is present while a hook is registered."""
        with self._lock:
            return [
                HeldSecret(
                    project_id=key[0],
                    secret_name=key[1],
                    version=entry.version,
                    refresh_hook_applied_version=entry.hook_applied_version if entry.hook_registered else None,
                )
                for key, entry in self._entries.items()
                if entry.version > 0
            ]

    def _entry(self, project_id: str, secret_name: str) -> _Entry:
        key = (project_id, secret_name)
        entry = self._entries.get(key)
        if entry is None:
            entry = _Entry()
            self._entries[key] = entry
        return entry
