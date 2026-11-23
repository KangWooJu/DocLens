"""session 컨테이너: PG 17 + Flyway 12.11.0 + S3Mock, 시험마다 collector 표 TRUNCATE"""

import json
from collections.abc import Iterator
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
from collector.config import Settings
from collector.registry.source_registry import definition_hash
from collector.store.object_store import ObjectStore, s3_client
from tests.support.pending_sql import RECORD_PROBE

MIGRATIONS = Path(__file__).resolve().parents[1] / "db" / "migration"
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
def settings(database_url: str) -> Settings:
    with psycopg.connect(database_url, autocommit=True) as conn:
        conn.execute(f"TRUNCATE {', '.join(TABLES)} RESTART IDENTITY CASCADE")

    return Settings(
        db_url=database_url,
        contact="test@invalid.example",
        s3_endpoint="",
        s3_bucket="",
        s3_access_key="",
        s3_secret_key="",
    )


@pytest.fixture
def app(settings: Settings) -> Iterator[App]:
    app = build_app(settings)

    try:
        yield app
    finally:
        app.close()


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
    """probe 명령 전까지 시험이 probe 통과 증거를 직접 기록"""

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
