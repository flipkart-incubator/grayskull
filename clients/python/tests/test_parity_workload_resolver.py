"""Port of Java DefaultWorkloadIdentityResolverTest."""

import socket

import pytest

from grayskull import DefaultWorkloadIdentityResolver, WorkloadIdentityResolver
from grayskull.client_impl.workload.default_workload_identity_resolver import UNKNOWN_HOST


def test_resolves_the_local_hostname_by_default():
    assert DefaultWorkloadIdentityResolver().resolve() == socket.gethostname()


def test_uses_the_supplied_hostname():
    assert DefaultWorkloadIdentityResolver(lambda: "test-host-1").resolve() == "test-host-1"


def test_value_is_resolved_once_and_cached():
    calls = []

    def supplier():
        calls.append(1)
        return "cached-host"

    resolver = DefaultWorkloadIdentityResolver(supplier)
    assert resolver.resolve() == resolver.resolve() == "cached-host"
    assert len(calls) == 1


@pytest.mark.parametrize("supplier", [
    lambda: (_ for _ in ()).throw(OSError("no dns")),
    lambda: (_ for _ in ()).throw(SecurityError_("blocked")),
    lambda: None,
    lambda: "",
    lambda: "   ",
])
def test_falls_back_to_unknown_when_the_supplier_fails_or_is_blank(supplier):
    assert DefaultWorkloadIdentityResolver(supplier).resolve() == UNKNOWN_HOST


def test_never_returns_none():
    assert DefaultWorkloadIdentityResolver(lambda: None).resolve() is not None


def test_implements_the_workload_identity_contract():
    resolver = DefaultWorkloadIdentityResolver(lambda: "x")
    assert isinstance(resolver, WorkloadIdentityResolver)
    assert resolver.resolve() == "x"


def test_unknown_host_constant_is_stable():
    # The value is part of the telemetry contract shared with Java and Go.
    assert UNKNOWN_HOST == "UNKNOWN"


class SecurityError_(Exception):
    """Stands in for Java's SecurityException, which any supplier failure must survive."""
