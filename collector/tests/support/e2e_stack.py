"""로컬 스택 끝-끝의 host 쪽: 합성 registry 승인과 요청 틀, loopback 합성 git 트리를 받는 상주 worker

python -m tests.support.e2e_stack prepare > ../.local/collection-request.json
python -m tests.support.e2e_stack work
"""

import contextlib
import io
import json
import sys
import tempfile
from pathlib import Path

from collector.__main__ import main
from collector.app import App, build_app
from collector.config import Settings
from collector.registry.source_registry import git_commit
from tests.support.fixture_server import serving
from tests.support.fixture_site import GIT_INCLUDES, GIT_PAGES, git_base, git_tree
from tests.support.loopback_connector import LoopbackConnector
from tests.support.run_collector import policy

REGISTRY = Path(__file__).resolve().parents[2] / "registry" / "spring-boot-4.1.2.json"
PARSER_PROFILE = "spring-boot-asciidoc-reference@1"


def synthetic_registry(directory: Path) -> tuple[Path, list[str], str]:
    """registry 파일의 host, commit, 규칙 ID는 그대로, 경로 목록만 합성 트리로 바꾼 임시 정의"""

    registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
    commit = git_commit(registry["sources"][0]["canonicalRoot"])
    base = git_base(commit)
    entry = base + "gradle.properties"
    paths = {
        "REFERENCE": [entry, *(base + path for path in GIT_PAGES)],
        "EXAMPLE": [entry, *(base + path for path in GIT_INCLUDES)],
    }

    for source in registry["sources"]:
        source["allowedPathPrefixes"] = paths[source["documentType"]]

    path = directory / "registry.json"
    path.write_text(json.dumps(registry), encoding="utf-8")
    return path, [source["sourceId"] for source in registry["sources"]], commit


def command(
    app: App,
    *argv: str | Path,
) -> list[dict[str, object]]:
    """CLI 명령 하나, stdout JSON 줄을 그대로 다시 쓰고 종료 코드가 0이 아니면 중단"""

    out = io.StringIO()

    with contextlib.redirect_stdout(out):
        code = main([str(arg) for arg in argv], app=app)

    print(out.getvalue(), end="", flush=True)

    if code != 0:
        raise SystemExit(f"{' '.join(str(arg) for arg in argv)} exited with {code}")

    return [json.loads(line) for line in out.getvalue().splitlines()]


def prepare(directory: Path) -> dict[str, object]:
    """합성 registry import, probe, approve 뒤 collectionRequest, CLI 출력은 stderr로"""

    registry, source_ids, commit = synthetic_registry(directory)

    with serving(git_tree(commit)) as server:
        app = build_app(
            Settings.from_env(),
            LoopbackConnector(server.port),
            policy=policy(None),
        )

        try:
            with contextlib.redirect_stdout(sys.stderr):
                command(
                    app,
                    "registry",
                    "import",
                    registry,
                )

                for source_id in source_ids:
                    command(
                        app,
                        "registry",
                        "probe",
                        source_id,
                    )

                for source_id in source_ids:
                    command(
                        app,
                        "registry",
                        "approve",
                        source_id,
                        "--by",
                        "ci",
                        "--parser-profile",
                        PARSER_PROFILE,
                    )

                return command(
                    app,
                    "request",
                    *source_ids,
                )[0]
        finally:
            app.close()


def work(directory: Path) -> int:
    """종료 신호까지 접수된 job을 loopback 합성 git 트리에서 수집"""

    _, _, commit = synthetic_registry(directory)

    with serving(git_tree(commit)) as server:
        app = build_app(
            Settings.from_env(),
            LoopbackConnector(server.port),
            policy=policy(None),
        )

        try:
            command(
                app,
                "work",
                "--serve",
            )
        finally:
            app.close()

    return 0


def run(argv: list[str]) -> int:
    if argv not in (["prepare"], ["work"]):
        print("usage: python -m tests.support.e2e_stack prepare|work", file=sys.stderr)
        return 2

    with tempfile.TemporaryDirectory() as directory:
        if argv == ["work"]:
            return work(Path(directory))

        request = prepare(Path(directory))

    print(json.dumps(request), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(run(sys.argv[1:]))
