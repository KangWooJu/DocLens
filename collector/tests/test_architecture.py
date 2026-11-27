"""TX 경계와 단일 송신 통로의 AST 규칙, 검사 범위는 src/collector"""

import ast
from pathlib import Path

import pytest

SOURCE = Path(__file__).resolve().parents[1] / "src" / "collector"
GATE = "crawl/transport_gate.py"
NETWORK = ("urllib3", "socket", "ssl", "http.client")
S3 = ("boto3", "botocore")
POSTGRES = ("psycopg", "psycopg_pool")


def imported(tree: ast.AST) -> set[str]:
    names = set()

    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            names.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            names.add(node.module)

    return names


def uses(
    names: set[str],
    roots: tuple[str, ...],
) -> bool:
    return any(name == root or name.startswith(root + ".") for name in names for root in roots)


def violations(
    path: str,
    source: str,
) -> list[str]:
    """path는 src/collector 기준 상대 경로"""

    tree = ast.parse(source)
    names = imported(tree)
    name = Path(path).name
    found = []
    is_facade = name.endswith("_facade.py")
    is_service = name.endswith("_service.py")

    calls_transaction = any(
        isinstance(node, ast.Attribute) and node.attr == "transaction" for node in ast.walk(tree)
    )
    if calls_transaction and not (is_facade or path == GATE):
        found.append("transaction() outside a facade")

    if uses(names, NETWORK) and path != GATE:
        found.append("network import outside the transport gate")

    if uses(names, S3) and path not in ("store/object_store.py", "app.py"):
        found.append("S3 import outside the object store")

    if is_service and any(other.endswith("_service") for other in names):
        found.append("service imports another service")

    # HTTP 진입부는 App의 Facade만 부름
    if path == "api.py" and any(other.endswith(("_service", "_repository")) for other in names):
        found.append("api imports a service or repository")

    allowed_postgres = is_facade or name.endswith("_repository.py") or path in ("db.py", GATE, "worker.py")
    if uses(names, POSTGRES) and not allowed_postgres:
        found.append("psycopg import outside facade, repository, db, gate, worker")

    return found


def test_source_tree_follows_rules() -> None:
    problems = {
        str(path.relative_to(SOURCE)): violations(
            str(path.relative_to(SOURCE)),
            path.read_text(encoding="utf-8"),
        )
        for path in SOURCE.rglob("*.py")
    }

    assert {path: found for path, found in problems.items() if found} == {}


def test_service_may_use_connection_alias() -> None:
    assert violations("job/job_service.py", "from collector.db import Conn\n") == []


@pytest.mark.parametrize(
    ("path", "source"),
    [
        ("job/job_service.py", "import psycopg\n"),
        ("job/job_service.py", "from collector.registry.registry_service import RegistryService\n"),
        ("job/job_service.py", "def f(conn):\n    with conn.transaction():\n        pass\n"),
        ("crawl/fetch_service.py", "import urllib3\n"),
        ("job/job_repository.py", "import boto3\n"),
        ("api.py", "from collector.job.job_service import JobService\n"),
        ("api.py", "from collector.job.job_repository import JobRepository\n"),
        ("api.py", "import psycopg\n"),
        ("api.py", "def f(conn):\n    with conn.transaction():\n        pass\n"),
    ],
)
def test_rule_violations_are_found(
    path: str,
    source: str,
) -> None:
    assert violations(path, source) != []
