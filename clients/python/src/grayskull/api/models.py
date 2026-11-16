"""Public value types."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, NamedTuple

from grayskull.errors import InvalidArgumentError

__all__ = ["SecretRef", "SecretValue"]


class SecretRef(NamedTuple):
    """A parsed ``"projectId:secretName"`` reference.

    Only the first colon separates the parts, so secret names may themselves
    contain colons (``"proj:db:primary"`` -> ``("proj", "db:primary")``).
    """

    project_id: str
    secret_name: str

    @classmethod
    def parse(cls, secret_ref: str) -> "SecretRef":
        if not isinstance(secret_ref, str) or not secret_ref:
            raise InvalidArgumentError("secretRef cannot be empty")
        project_id, sep, secret_name = secret_ref.partition(":")
        if not sep:
            raise InvalidArgumentError(
                f"invalid secretRef format. Expected 'projectId:secretName', got: {secret_ref}"
            )
        if not project_id or not secret_name:
            raise InvalidArgumentError(f"projectId and secretName cannot be empty in secretRef: {secret_ref}")
        return cls(project_id, secret_name)

    def __str__(self) -> str:
        return f"{self.project_id}:{self.secret_name}"


@dataclass(frozen=True, repr=False)
class SecretValue:
    """An immutable, versioned secret.

    ``repr()`` masks ``private_part`` so a secret never leaks through logs,
    tracebacks or debugger output by accident.
    """

    data_version: int
    public_part: str = ""
    private_part: str = ""

    @classmethod
    def from_wire(cls, data: Mapping[str, Any]) -> "SecretValue":
        return cls(
            data_version=int(data.get("dataVersion") or 0),
            public_part=data.get("publicPart") or "",
            private_part=data.get("privatePart") or "",
        )

    def __repr__(self) -> str:
        masked = "'***'" if self.private_part else "''"
        return f"SecretValue(data_version={self.data_version}, public_part={self.public_part!r}, private_part={masked})"

    __str__ = __repr__
