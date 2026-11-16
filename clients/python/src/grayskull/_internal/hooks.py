"""Per-secret refresh-hook state.

Delivery semantics (identical to the Go/Java SDKs):

* **Coalescing** - each secret has one pending slot; a newer version replaces an
  undelivered older one, so slow hooks see the latest value, not a backlog.
* **Non-reentrant** - at most one dispatcher runs a given secret's hooks at a time.
* **At-most-once** - ``last_known_version`` advances *before* hooks run, so a hook
  that raises is not retried for that version; the next server bump is delivered.
"""

from __future__ import annotations

import logging
import threading
from typing import Callable, Dict, List, Optional, Tuple

from grayskull.api import SecretRefreshHook, SecretValue

_log = logging.getLogger("grayskull.hooks")


class HandlerRef:
    """Concrete :class:`grayskull.api.RefreshHandlerRef`."""

    __slots__ = ("_secret_ref", "_active", "_on_unregister", "_lock")

    def __init__(self, secret_ref: str, on_unregister: Callable[["HandlerRef"], None]) -> None:
        self._secret_ref = secret_ref
        self._active = True
        self._on_unregister = on_unregister
        self._lock = threading.Lock()

    @property
    def secret_ref(self) -> str:
        return self._secret_ref

    @property
    def is_active(self) -> bool:
        return self._active

    def unregister(self) -> None:
        with self._lock:
            if not self._active:
                return
            self._active = False
        self._on_unregister(self)
        _log.debug("unregistered refresh hook for %s", self._secret_ref)

    def __repr__(self) -> str:
        return f"HandlerRef(secret_ref={self._secret_ref!r}, active={self._active})"


class SecretState:
    __slots__ = ("project_id", "secret_name", "secret_ref", "_lock", "_last_known", "_pending", "_scheduled", "_hooks")

    def __init__(self, project_id: str, secret_name: str, last_known_version: int) -> None:
        self.project_id = project_id
        self.secret_name = secret_name
        self.secret_ref = f"{project_id}:{secret_name}"
        self._lock = threading.Lock()
        self._last_known = last_known_version
        self._pending: Optional[SecretValue] = None
        # True while a drain is queued or running; replaces Go's isExecuting + resubmit dance.
        self._scheduled = False
        self._hooks: List[Tuple[HandlerRef, SecretRefreshHook]] = []

    @property
    def last_known_version(self) -> int:
        return self._last_known

    def stage(self, value: SecretValue) -> bool:
        """Stage ``value``; return True if the caller must schedule a drain.

        Stale or duplicate versions (e.g. a poll that raced with a delivery) are dropped.
        """
        with self._lock:
            if value.data_version <= self._last_known:
                return False
            if self._pending is None or value.data_version >= self._pending.data_version:
                self._pending = value
            if self._scheduled:
                return False
            self._scheduled = True
            return True

    def take_next(self) -> Optional[SecretValue]:
        """Pop the pending value and advance ``last_known_version``; release the slot if empty."""
        with self._lock:
            value = self._pending
            if value is None:
                self._scheduled = False
                return None
            self._pending = None
            self._last_known = max(self._last_known, value.data_version)
            return value

    def release(self) -> None:
        with self._lock:
            self._scheduled = False

    def hooks(self) -> Tuple[SecretRefreshHook, ...]:
        with self._lock:
            return tuple(hook for _, hook in self._hooks)

    def has_hooks(self) -> bool:
        return bool(self._hooks)

    def _add(self, ref: HandlerRef, hook: SecretRefreshHook) -> None:
        with self._lock:
            self._hooks.append((ref, hook))

    def _remove(self, ref: HandlerRef) -> bool:
        with self._lock:
            self._hooks = [entry for entry in self._hooks if entry[0] is not ref]
            return not self._hooks

    def _reset_after_fork(self) -> None:
        self._lock = threading.Lock()
        self._scheduled = False  # dispatcher threads did not survive the fork


class Registry:
    """Thread-safe map of secretRef -> :class:`SecretState`."""

    __slots__ = ("_lock", "_states")

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._states: Dict[str, SecretState] = {}

    def register(self, project_id: str, secret_name: str, hook: SecretRefreshHook, seed_version: int) -> HandlerRef:
        """Attach ``hook``. ``seed_version`` only applies when the secret is first watched."""
        secret_ref = f"{project_id}:{secret_name}"
        with self._lock:
            state = self._states.get(secret_ref)
            if state is None:
                state = self._states[secret_ref] = SecretState(project_id, secret_name, seed_version)
            ref = HandlerRef(secret_ref, self._unregister)
            state._add(ref, hook)
        return ref

    def _unregister(self, ref: HandlerRef) -> None:
        with self._lock:
            state = self._states.get(ref.secret_ref)
            if state is not None and state._remove(ref):
                del self._states[ref.secret_ref]

    def get(self, secret_ref: str) -> Optional[SecretState]:
        return self._states.get(secret_ref)

    def snapshot(self) -> List[SecretState]:
        with self._lock:
            return list(self._states.values())

    def __len__(self) -> int:
        return len(self._states)

    def reset_after_fork(self) -> None:
        self._lock = threading.Lock()
        for state in self._states.values():
            state._reset_after_fork()
