"""Body of POST /v1/secrets/batch."""

from dataclasses import dataclass
from typing import Any, Dict, List


@dataclass(frozen=True)
class BatchGetSecretsEntry:
    """One secret to check, with the version this client already has."""

    project_id: str
    secret_name: str
    last_known_version: int

    def to_dict(self) -> Dict[str, Any]:
        """Serialize to the wire shape."""
        return {"projectId": self.project_id, "secretName": self.secret_name, "lastKnownVersion": self.last_known_version}


@dataclass(frozen=True)
class BatchGetSecretsRequest:
    """Secrets to check for newer versions."""

    secrets: List[BatchGetSecretsEntry]

    def to_dict(self) -> Dict[str, Any]:
        """Serialize to the wire shape."""
        return {"secrets": [entry.to_dict() for entry in self.secrets]}
