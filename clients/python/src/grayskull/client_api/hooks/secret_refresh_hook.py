"""Callback type invoked when a secret changes."""

from typing import Callable

from ..models.secret_value import SecretValue

# Called with the new secret version; raising marks the delivery as failed.
SecretRefreshHook = Callable[[SecretValue], None]
