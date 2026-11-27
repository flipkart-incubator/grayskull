"""Body returned by POST /v1/secrets/batch."""

from dataclasses import dataclass
from typing import Any, Dict, List, Optional

from ....client_api.models.secret_value import SecretValue


@dataclass(frozen=True)
class UpdatedSecret:
    """A secret whose server version is newer than the version the client sent."""

    project_id: str
    secret_name: str
    value: SecretValue

    @classmethod
    def from_dict(cls, payload: Dict[str, Any]) -> "UpdatedSecret":
        """Build an UpdatedSecret from one entry of the response."""
        return cls(payload["projectId"], payload["secretName"], SecretValue.from_dict(payload))


@dataclass(frozen=True)
class BatchGetSecretsResponse:
    """Secrets that changed since the versions the client sent."""

    updated_secrets: List[UpdatedSecret]

    @classmethod
    def from_response(cls, response: Dict[str, Any]) -> "BatchGetSecretsResponse":
        """Build the response from the server's {data, message} envelope."""
        data: Optional[Dict[str, Any]] = response.get("data") or {}
        return cls([UpdatedSecret.from_dict(item) for item in data.get("updatedSecrets") or []])
