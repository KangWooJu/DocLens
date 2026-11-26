"""환경 설정과 수집 정책 수치"""

import os
from collections.abc import Mapping
from dataclasses import dataclass, fields, replace


@dataclass(frozen=True)
class Settings:
    """환경 변수에서 읽은 연결 정보"""

    db_url: str
    contact: str
    s3_endpoint: str
    s3_bucket: str
    s3_access_key: str
    s3_secret_key: str
    # 호출자 토큰 hash 목록 원문, 형식 검사는 api 명령에서만
    caller_tokens: str = ""

    @classmethod
    def from_env(cls, env: Mapping[str, str] = os.environ) -> "Settings":
        db_url = env.get("COLLECTOR_DB_URL", "")

        if not db_url:
            raise ValueError("COLLECTOR_DB_URL is not set")

        return cls(
            db_url=db_url,
            contact=env.get("COLLECTOR_CONTACT", ""),
            s3_endpoint=env.get("DOCLENS_S3_ENDPOINT", ""),
            s3_bucket=env.get("DOCLENS_S3_BUCKET", ""),
            s3_access_key=env.get("DOCLENS_S3_ACCESS_KEY", ""),
            s3_secret_key=env.get("DOCLENS_S3_SECRET_KEY", ""),
            caller_tokens=env.get("DOCLENS_CALLER_TOKENS", ""),
        )


# 축척하지 않는 개수, 크기, 예산 값
_UNSCALED = frozenset(
    {
        "daily_budget",
        "attempt_cap",
        "circuit_threshold",
        "html_max_bytes",
        "robots_max_bytes",
    },
)


@dataclass(frozen=True)
class CrawlPolicy:
    """정책 수치의 유일한 출처, SQL에는 매개변수로만 전달"""

    min_interval: float = 2.0
    daily_budget: int = 3000
    attempt_cap: int = 3
    retry_delays: tuple[float, ...] = (30.0, 60.0)
    retry_jitter: float = 10.0
    recover_delay: float = 30.0
    connect_timeout: float = 10.0
    read_timeout: float = 30.0
    total_deadline: float = 60.0
    result_margin: float = 30.0
    statement_timeout: float = 10.0
    idle_in_tx_timeout: float = 15.0
    db_connect_timeout: float = 5.0
    in_flight_expiry: float = 150.0
    lease: float = 90.0
    heartbeat_interval: float = 20.0
    inline_wait_max: float = 120.0
    inline_host_wait: float = 10.0
    in_flight_poll: float = 0.5
    idle_poll: float = 30.0
    db_retry_delay: float = 5.0
    circuit_threshold: int = 5
    circuit_open: tuple[float, ...] = (600.0, 1800.0, 3600.0)
    throttle: tuple[float, ...] = (300.0, 600.0, 1200.0, 3600.0)
    robots_ttl: float = 86400.0
    html_max_bytes: int = 8 * 1024 * 1024
    robots_max_bytes: int = 512 * 1024

    def __post_init__(self) -> None:
        # 예약 TX commit 지연과 결과 TX는 각각 transaction_timeout 이하, 결과 TX 전 연결 대기는 db_connect_timeout 이하
        worst_send = self.total_deadline + 2 * self.result_margin + self.db_connect_timeout
        checks = (
            (self.in_flight_expiry > worst_send, "in_flight_expiry must exceed the worst send time"),
            (self.statement_timeout <= self.result_margin, "statement_timeout must not exceed result_margin"),
            (self.lease > self.total_deadline, "lease must exceed total_deadline"),
            (
                self.lease > self.result_margin + self.heartbeat_interval,
                "lease must exceed result_margin + heartbeat_interval",
            ),
            (self.heartbeat_interval * 3 <= self.lease, "heartbeat_interval * 3 must not exceed lease"),
            (1 <= self.attempt_cap <= 3, "attempt_cap must be between 1 and 3"),
            (len(self.retry_delays) >= self.attempt_cap - 1, "retry_delays must cover every retry"),
        )

        for passed, message in checks:
            if not passed:
                raise ValueError(message)

    def db_options(self) -> str:
        """pool 연결의 PG timeout 설정, 검사가 따지는 값과 같은 출처"""

        return (
            f"-c statement_timeout={_millis(self.statement_timeout)}"
            f" -c idle_in_transaction_session_timeout={_millis(self.idle_in_tx_timeout)}"
            f" -c transaction_timeout={_millis(self.result_margin)}"
        )

    def scaled(self, factor: float) -> "CrawlPolicy":
        """시험 전용 축척, 시간 값 전부에 같은 배율과 하한"""

        values = {}

        for field in fields(self):
            value = getattr(self, field.name)

            if field.name in _UNSCALED:
                continue

            if isinstance(value, tuple):
                values[field.name] = tuple(item * factor for item in value)
            else:
                values[field.name] = value * factor

        lease = max(self.lease * factor, 8.0)
        result_margin = max(self.result_margin * factor, 5.0)
        db_connect_timeout = max(self.db_connect_timeout * factor, 5.0)
        values.update(
            lease=lease,
            heartbeat_interval=lease / 4.5,
            result_margin=result_margin,
            statement_timeout=max(self.statement_timeout * factor, 5.0),
            idle_in_tx_timeout=max(self.idle_in_tx_timeout * factor, 5.0),
            db_connect_timeout=db_connect_timeout,
            in_flight_expiry=max(
                self.in_flight_expiry * factor,
                self.total_deadline * factor + 2 * result_margin + db_connect_timeout + 1.0,
            ),
        )
        return replace(
            self,
            **values,
        )


def _millis(seconds: float) -> int:
    return round(seconds * 1000)
