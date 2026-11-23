"""시험에서 CLI를 같은 프로세스로 실행"""

import json
from pathlib import Path

import pytest

from collector.__main__ import main
from collector.app import App


def cli(
    app: App,
    capsys: pytest.CaptureFixture[str],
    *argv: str | Path,
) -> tuple[int, list[dict[str, object]], list[dict[str, object]]]:
    """종료 코드, stdout JSON 줄, stderr JSON 줄"""

    capsys.readouterr()
    code = main([str(arg) for arg in argv], app=app)
    captured = capsys.readouterr()
    return (
        code,
        [json.loads(line) for line in captured.out.splitlines()],
        [json.loads(line) for line in captured.err.splitlines()],
    )
