"""Body of POST /v1/client/heartbeat."""

from dataclasses import dataclass
from typing import Any, Dict, List, Optional


@dataclass(frozen=True)
class HeldSecret:
    """One usage-ledger entry: a secret this client holds and its versions."""

    project_id: str
    secret_name: str
    version: int
    # Newest version all registered hooks applied; 0 means no delivery since registration, None means no hook.
    refresh_hook_applied_version: Optional[int] = None

    def to_dict(self) -> Dict[str, Any]:
        """Serialize to the wire shape; the applied version is omitted when None."""
        body: Dict[str, Any] = {"projectId": self.project_id, "secretName": self.secret_name, "version": self.version}
        if self.refresh_hook_applied_version is not None:
            body["refreshHookAppliedVersion"] = self.refresh_hook_applied_version
        return body


@dataclass(frozen=True)
class HeartbeatRequest:
    """Secrets this client holds, sent in chunks."""

    secrets: List[HeldSecret]

    def to_dict(self) -> Dict[str, Any]:
        """Serialize to the wire shape."""
        return {"secrets": [secret.to_dict() for secret in self.secrets]}
