"""Raw HTTP response returned by GrayskullHttpClient."""

import json
from dataclasses import dataclass
from typing import Any, Dict

from ..exceptions.grayskull_exception import GrayskullException


@dataclass(frozen=True)
class HttpResponse:
    """Status code and body of a successful HTTP call."""

    status_code: int
    body: bytes

    def json(self) -> Dict[str, Any]:
        """Decode the body as a JSON object; an empty body decodes to {}."""
        if not self.body:
            return {}
        try:
            decoded = json.loads(self.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise GrayskullException(self.status_code, "Failed to parse response", exc) from exc
        if not isinstance(decoded, dict):
            raise GrayskullException(self.status_code, "Unexpected response body shape")
        return decoded
