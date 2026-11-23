"""session 컨테이너: PG 17 + Flyway 12.11.0 + S3Mock, 시험마다 collector 표 TRUNCATE"""

import json
import os
import sys
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import replace
from pathlib import Path
from uuid import UUID, uuid4

import psycopg
import pytest
from psycopg.types.json import Jsonb
from testcontainers.community.postgres import PostgresContainer
from testcontainers.core.container import DockerContainer
from testcontainers.core.network import Network
from testcontainers.core.wait_strategies import LogMessageWaitStrategy

from collector.app import App, build_app
from collector.config import CrawlPolicy, Settings
from collector.crawl.transport_gate import Connector, Fetch
from collector.job.job_service import ClaimedJob
from collector.registry.registry_repository import RECORD_PROBE
from collector.registry.source_registry import definition_hash
from collector.store.object_store import ObjectStore, s3_client
from tests.support.loopback_connector import LoopbackConnector

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "db" / "migration"
# 오프라인 수집 시험의 모든 시간
SCALED = CrawlPolicy().scaled(0.02)
BUCKET = "doclens"
TABLES = (
    "collector.http_sends",
    "collector.host_states",
    "collector.collection_targets",
    "collector.collection_batches",
    "collector.collection_projections",
    "collector.collection_runs",
    "collector.jobs",
    "collector.sources",
)


@pytest.fixture(scope="session")
def network() -> Iterator[Network]:
    with Network() as network:
        yield network


@pytest.fixture(scope="session")
def database_url(network: Network) -> Iterator[str]:
    postgres = (
        PostgresContainer("postgres:17", driver=None)
        .with_network(network)
        .with_network_aliases("pg")
    )

    with postgres:
        migrate(postgres, network)
        yield postgres.get_connection_url()


def migrate(
    postgres: PostgresContainer,
    network: Network,
) -> None:
    """운영과 같은 Flyway CLI 이미지로 V001 적용"""

    flyway = (
        DockerContainer("flyway/flyway:12.11.0")
        .with_network(network)
        .with_volume_mapping(str(MIGRATIONS), "/flyway/sql", "ro")
        .with_command(
            [
                f"-url=jdbc:postgresql://pg:5432/{postgres.dbname}",
                f"-user={postgres.username}",
                f"-password={postgres.password}",
                "-schemas=collector",
                "-defaultSchema=collector",
                "-createSchemas=true",
                "-locations=filesystem:/flyway/sql",
                "-cleanDisabled=true",
                "-validateMigrationNaming=true",
                "migrate",
                "validate",
            ],
        )
    )
    flyway.start()

    try:
        container = flyway.get_wrapped_container()
        result = container.wait(timeout=180)

        if result["StatusCode"] != 0:
            raise RuntimeError("flyway failed:\n" + (
                container
                .logs()
                .decode("utf-8", "replace")
            ))
    finally:
        flyway.stop()


@pytest.fixture(scope="session")
def s3_endpoint() -> Iterator[str]:
    s3mock = (
        DockerContainer("adobe/s3mock:5.2.3")
        .with_env("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", BUCKET)
        .with_exposed_ports(9090)
        .waiting_for(LogMessageWaitStrategy("Started S3MockApplication"))
    )

    with s3mock:
        yield f"http://{s3mock.get_container_host_ip()}:{s3mock.get_exposed_port(9090)}"


@pytest.fixture
def settings(
    database_url: str,
    s3_endpoint: str,
) -> Settings:
    with psycopg.connect(database_url, autocommit=True) as conn:
        conn.execute(f"TRUNCATE {', '.join(TABLES)} RESTART IDENTITY CASCADE")

    return Settings(
        db_url=database_url,
        contact="test@invalid.example",
        s3_endpoint=s3_endpoint,
        s3_bucket=BUCKET,
        s3_access_key="local",
        s3_secret_key="local",
    )


@pytest.fixture
def app(settings: Settings) -> Iterator[App]:
    """송신하지 않는 시험용, 연결은 닫힌 loopback 포트"""

    app = build_app(settings, LoopbackConnector(9))

    try:
        yield app
    finally:
        app.close()


@contextmanager
def connected_app(
    settings: Settings,
    connector: Connector,
    policy: CrawlPolicy = SCALED,
) -> Iterator[App]:
    """주어진 connector로 조립한 App, 시험 끝에 pool을 닫음"""

    app = build_app(
        settings,
        connector,
        policy=policy,
    )

    try:
        yield app
    finally:
        app.close()


def subprocess_env(settings: Settings) -> dict[str, str]:
    """run_collector subprocess의 환경 변수"""

    return {
        **os.environ,
        "COLLECTOR_DB_URL": settings.db_url,
        "COLLECTOR_CONTACT": settings.contact,
        "DOCLENS_S3_ENDPOINT": settings.s3_endpoint,
        "DOCLENS_S3_BUCKET": settings.s3_bucket,
        "DOCLENS_S3_ACCESS_KEY": settings.s3_access_key,
        "DOCLENS_S3_SECRET_KEY": settings.s3_secret_key,
    }


def run_collector_command(*args: str | int) -> list[str]:
    return [sys.executable, "-m", "tests.support.run_collector", *(str(arg) for arg in args)]


@pytest.fixture
def store(s3_endpoint: str) -> ObjectStore:
    return ObjectStore(
        s3_client(
            s3_endpoint,
            "local",
            "local",
        ),
        BUCKET,
    )


def definition(
    source_id: str = "spring-boot-4.1.3-reference",
    exact_version: str = "4.1.3",
    required: bool = True,
) -> dict[str, object]:
    project = source_id.rsplit("-", 2)[0]
    root = f"https://docs.spring.io/{project}/{exact_version.rsplit('.', 1)[0]}/reference/"
    return {
        "sourceId": source_id,
        "project": project,
        "exactVersion": exact_version,
        "sourceType": "html",
        "documentType": "REFERENCE",
        "required": required,
        "applicability": "EXACT_RELEASE",
        "entryUrl": root + "index.html",
        "canonicalRoot": root,
        "allowedHosts": ["docs.spring.io"],
        "allowedPathPrefixes": [root.removeprefix("https://docs.spring.io")],
        "allowedQueryKeys": [],
        "redirectHosts": [],
        "excludePathPrefixes": [],
        "discoveryRuleId": "antora-nav-closed-v1",
        "bodySelectorRuleId": "boot-antora-v1",
        "versionRuleId": "antora-meta-version-v1",
        "officialLinkEvidence": f"https://spring.io/projects/{project}",
    }


def registry_file(
    directory: Path,
    *entries: dict[str, object],
) -> Path:
    path = directory / "registry.json"
    path.write_text(
        json.dumps({"registryVersion": 1, "sources": list(entries)}),
        encoding="utf-8",
    )
    return path


def record_probe(
    settings: Settings,
    source_id: str,
    version: str,
) -> None:
    """loopback probe 없이 probe 통과 증거를 직접 기록"""

    with psycopg.connect(settings.db_url, autocommit=True) as conn:
        (definition_hash,) = (
            conn
            .execute(
                "SELECT definition_hash FROM collector.sources WHERE source_id = %s",
                (source_id,),
            )
            .fetchone()
        )
        conn.execute(
            RECORD_PROBE,
            {
                "state": "PASSED",
                "version": version,
                "raw_hash": "sha256:" + "a" * 64,
                "source_id": source_id,
                "probed_hash": definition_hash,
            },
        )


def approve_sources(
    app: App,
    settings: Settings,
    directory: Path,
    *entries: dict[str, object],
) -> str:
    """import, probe 기록, approve 뒤 현재 registryHash"""

    app.registry.import_file(registry_file(directory, *entries))
    registry_hash = ""

    for entry in entries:
        record_probe(
            settings,
            str(entry["sourceId"]),
            str(entry["exactVersion"]),
        )
        registry_hash = app.registry.approve(
            str(entry["sourceId"]),
            "tester",
            "spring-antora-reference@2",
        ).registry_hash

    return registry_hash


@pytest.fixture
def pg(settings: Settings) -> Iterator[psycopg.Connection]:
    with psycopg.connect(settings.db_url, autocommit=True) as conn:
        yield conn


def insert_source(
    pg: psycopg.Connection,
    entry: dict[str, object] | None = None,
    approved: bool = True,
) -> str:
    """probe 통과, 필요하면 승인까지 된 source 행"""

    entry = entry or definition()
    pg.execute(
        """
        INSERT INTO collector.sources (source_id, definition, definition_hash)
        VALUES (%(id)s, %(definition)s, %(hash)s)
        """,
        {"id": entry["sourceId"], "definition": Jsonb(entry), "hash": definition_hash(entry)},
    )
    pg.execute(
        """
        UPDATE collector.sources
        SET probe_state = 'PASSED', probe_version = definition ->> 'exactVersion',
            probe_raw_hash = %(raw)s, probed_at = clock_timestamp()
        WHERE source_id = %(id)s
        """,
        {"id": entry["sourceId"], "raw": "sha256:" + "a" * 64},
    )

    if approved:
        pg.execute(
            """
            UPDATE collector.sources
            SET approval_state = 'APPROVED', approved_by = 'tester', approved_at = clock_timestamp(),
                parser_profile_ref = 'spring-antora-reference@2'
            WHERE source_id = %(id)s
            """,
            {"id": entry["sourceId"]},
        )

    return str(entry["sourceId"])


def insert_job(
    pg: psycopg.Connection,
    source_id: str = "spring-boot-4.1.3-reference",
) -> UUID:
    job_id = uuid4()
    body = {"inputs": [{"sourceId": source_id}]}
    pg.execute(
        """
        INSERT INTO collector.jobs (job_id, request_id, request_hash, request_body)
        VALUES (%(job_id)s, %(request_id)s, %(hash)s, %(body)s)
        """,
        {"job_id": job_id, "request_id": uuid4(), "hash": "sha256:" + "b" * 64, "body": Jsonb(body)},
    )
    return job_id


def insert_run(
    pg: psycopg.Connection,
    job_id: UUID,
    source_id: str = "spring-boot-4.1.3-reference",
    documents: int = 2,
) -> tuple[UUID, UUID, list[str]]:
    """run 하나, batch 하나, PENDING 대상 문서들"""

    run_id = uuid4()
    batch_id = uuid4()
    pg.execute(
        """
        INSERT INTO collector.collection_runs
            (run_id, job_id, snapshot_id, scope_hash, source_registry_hash, observation_sequence)
        VALUES (%(run_id)s, %(job_id)s, %(snapshot)s, %(hash)s, %(hash)s,
                nextval('collector.observation_sequence'))
        """,
        {"run_id": run_id, "job_id": job_id, "snapshot": uuid4(), "hash": "sha256:" + "c" * 64},
    )
    pg.execute(
        """
        INSERT INTO collector.collection_batches
            (batch_id, run_id, ordinal, project, exact_version, document_type, target_count)
        VALUES (%(batch_id)s, %(run_id)s, 0, 'spring-boot', '4.1.3', 'REFERENCE', %(count)s)
        """,
        {"batch_id": batch_id, "run_id": run_id, "count": documents},
    )
    document_ids = []

    for index in range(documents):
        document_id = "sha256:" + f"{index:064x}"
        pg.execute(
            """
            INSERT INTO collector.collection_targets (run_id, document_id, batch_id, source_id, url, attempt_cap)
            VALUES (%(run_id)s, %(document_id)s, %(batch_id)s, %(source_id)s, %(url)s, 3)
            """,
            {
                "run_id": run_id,
                "document_id": document_id,
                "batch_id": batch_id,
                "source_id": source_id,
                "url": f"https://docs.spring.io/spring-boot/4.1/reference/p{index:03d}.html",
            },
        )
        document_ids.append(document_id)

    return run_id, batch_id, document_ids


def claim(
    app: App,
    settings: Settings,
    directory: Path,
) -> ClaimedJob:
    """승인 source 하나로 접수한 job을 claim"""

    approve_sources(
        app,
        settings,
        directory,
        definition(),
    )
    app.jobs.submit(app.registry.draft_request(["spring-boot-4.1.3-reference"]))
    job = app.jobs.claim(uuid4())
    assert job is not None
    return job


def page(
    job: ClaimedJob,
    name: str,
    **changes: object,
) -> Fetch:
    source = job.sources[0]
    fetch = Fetch(
        url=f"https://docs.spring.io/spring-boot/4.1/reference/{name}",
        purpose="DISCOVERY",
        source_id=source.source_id,
        scope=source.scope,
    )
    return replace(fetch, **changes)
