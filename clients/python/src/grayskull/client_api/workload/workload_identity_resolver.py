"""Resolves the identity sent in the Grayskull-Workload header."""

from abc import ABC, abstractmethod


class WorkloadIdentityResolver(ABC):
    """Returns the workload identity reported to Grayskull."""

    @abstractmethod
    def resolve(self) -> str:
        """Return the workload identity."""
