import pytest

from collector.config import CrawlPolicy, Settings


def test_default_policy_is_consistent() -> None:
    policy = CrawlPolicy()

    assert policy.in_flight_expiry > policy.total_deadline + 2 * policy.result_margin + policy.db_connect_timeout
    assert policy.db_options() == (
        "-c statement_timeout=10000 -c idle_in_transaction_session_timeout=15000 -c transaction_timeout=30000"
    )


def test_scaled_policy_keeps_floors() -> None:
    policy = CrawlPolicy().scaled(0.02)

    assert policy.min_interval == pytest.approx(0.04)
    assert policy.total_deadline == pytest.approx(1.2)
    assert policy.retry_delays == pytest.approx((0.6, 1.2))
    assert policy.circuit_open == pytest.approx((12.0, 36.0, 72.0))
    assert policy.lease == 8.0
    assert policy.heartbeat_interval == pytest.approx(8.0 / 4.5)
    assert policy.result_margin == 5.0
    assert policy.statement_timeout == 5.0
    assert policy.db_connect_timeout == 5.0
    assert policy.in_flight_expiry == pytest.approx(17.2)
    assert policy.daily_budget == 3000
    assert policy.attempt_cap == 3


@pytest.mark.parametrize(
    "overrides",
    [
        {"in_flight_expiry": 120.0},
        {"statement_timeout": 31.0},
        {"lease": 50.0},
        {"heartbeat_interval": 40.0},
        {"attempt_cap": 4},
        {"retry_delays": (30.0,)},
    ],
)
def test_inconsistent_policy_is_rejected(overrides: dict[str, object]) -> None:
    with pytest.raises(ValueError):
        CrawlPolicy(**overrides)


def test_settings_need_database_url() -> None:
    with pytest.raises(ValueError):
        Settings.from_env({})

    settings = Settings.from_env({"COLLECTOR_DB_URL": "postgresql://localhost/doclens"})

    assert settings.contact == ""
