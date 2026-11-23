"""끝-끝 수집: loopback 합성 사이트를 CLI 경로로 받고 마지막 줄에 readyRun objectRef

python -m tests.support.e2e_crawl
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
from tests.support.fixture_server import serving
from tests.support.fixture_site import site
from tests.support.loopback_connector import LoopbackConnector
from tests.support.run_collector import policy

REGISTRY = Path(__file__).resolve().parents[2] / "registry" / "spring-boot-4.1.json"
SOURCE_ID = "spring-boot-4.1.3-reference"
# entry와 문서 세 쪽
PAGES = 4


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


def run() -> int:
    settings = Settings.from_env()

    with serving(site(pages=PAGES)) as server:
        app = build_app(
            settings,
            LoopbackConnector(server.port),
            policy=policy(None),
        )

        try:
            command(
                app,
                "registry",
                "import",
                REGISTRY,
            )
            command(
                app,
                "registry",
                "probe",
                SOURCE_ID,
            )
            command(
                app,
                "registry",
                "approve",
                SOURCE_ID,
                "--by",
                "ci",
                "--parser-profile",
                "spring-antora-reference@2",
            )
            request = command(
                app,
                "request",
                SOURCE_ID,
            )[0]

            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "request.json"
                path.write_text(json.dumps(request), encoding="utf-8")
                job_id = str(
                    command(
                        app,
                        "submit",
                        path,
                    )[0]["jobId"],
                )

            command(
                app,
                "work",
                "--max-wait",
                "600",
            )
            status = command(
                app,
                "status",
                job_id,
            )[0]
        finally:
            app.close()

    if status["state"] != "SUCCEEDED" or status["result"] is None:
        raise SystemExit(f"job {job_id} ended in {status['state']}")

    print(
        json.dumps(
            status["result"],
            sort_keys=True,
        ),
        flush=True,
    )
    return 0


if __name__ == "__main__":
    sys.exit(run())
