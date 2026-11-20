"""HTTP header names sent by the Grayskull client."""


class GrayskullHeaders:
    """Header names used on every Grayskull request."""

    WORKLOAD = "Grayskull-Workload"
    USER_AGENT = "User-Agent"

    def __init__(self) -> None:
        raise TypeError("GrayskullHeaders is a constants holder and cannot be instantiated")
