"""A secret version returned by Grayskull."""

from dataclasses import dataclass, field
from typing import Any, Dict, Optional


@dataclass(frozen=True)
class SecretValue:
    """A secret version: its data version and its public and private parts.

    private_part is left out of repr(), so logging a SecretValue or printing it in a traceback
    does not leak the secret.
    """

    data_version: int
    public_part: str
    private_part: Optional[str] = field(default=None, repr=False)

    @classmethod
    def from_dict(cls, payload: Dict[str, Any]) -> "SecretValue":
        """Build a SecretValue from the server's JSON object."""
        return cls(
            data_version=int(payload["dataVersion"]),
            public_part=payload.get("publicPart", ""),
            private_part=payload.get("privatePart"),
        )
