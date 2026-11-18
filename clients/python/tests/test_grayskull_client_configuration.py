import pytest

from grayskull import DefaultWorkloadIdentityResolver, GrayskullClientConfiguration


def test_defaults():
    config = GrayskullClientConfiguration(host="https://grayskull.example.com")
    assert (config.connection_timeout, config.read_timeout) == (10_000, 30_000)
    assert (config.max_retries, config.min_retry_delay) == (3, 100)
    assert config.metrics_enabled is True
    assert config.polling_interval_seconds == 60
    assert config.heartbeat_enabled is True
    assert config.heartbeat_interval_seconds == 86400
    assert isinstance(config.workload_identity_resolver, DefaultWorkloadIdentityResolver)


def test_host_trailing_slash_is_removed():
    assert GrayskullClientConfiguration(host="http://localhost:8080/").host == "http://localhost:8080"


def test_zero_heartbeat_interval_means_the_daily_default():
    assert GrayskullClientConfiguration(host="http://x", heartbeat_interval_seconds=0).heartbeat_interval_seconds == 86400


@pytest.mark.parametrize("kwargs", [
    {"host": ""},
    {"host": "grayskull.example.com"},
    {"host": "ftp://grayskull.example.com"},
    {"host": "http://x", "connection_timeout": 0},
    {"host": "http://x", "read_timeout": 0},
    {"host": "http://x", "max_retries": 0},
    {"host": "http://x", "max_retries": 11},
    {"host": "http://x", "min_retry_delay": 49},
    {"host": "http://x", "polling_interval_seconds": 0},
    {"host": "http://x", "heartbeat_interval_seconds": -1},
])
def test_invalid_settings_are_rejected(kwargs):
    with pytest.raises(ValueError):
        GrayskullClientConfiguration(**kwargs)


def test_default_headers_are_copied_and_ignore_empty_values():
    config = GrayskullClientConfiguration(host="http://x")
    config.add_default_header("X-Team", "payments")
    config.add_default_header("X-Empty", "")
    headers = config.get_default_headers()
    headers["X-Team"] = "changed"
    assert config.get_default_headers() == {"X-Team": "payments"}


def test_default_workload_identity_resolver_falls_back_to_unknown():
    def fails() -> str:
        raise OSError("no hostname")

    assert DefaultWorkloadIdentityResolver(fails).resolve() == "UNKNOWN"
    assert DefaultWorkloadIdentityResolver(lambda: "  ").resolve() == "UNKNOWN"
    assert DefaultWorkloadIdentityResolver(lambda: "pod-1").resolve() == "pod-1"


def test_missing_workload_identity_resolver_falls_back_to_the_default():
    config = GrayskullClientConfiguration(host="http://x", workload_identity_resolver=None)  # type: ignore[arg-type]
    assert isinstance(config.workload_identity_resolver, DefaultWorkloadIdentityResolver)
