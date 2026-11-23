"""운영자 CLI: python -m collector, 출력은 stdout JSON, 거부는 stderr contracts error"""

import argparse
import json
import sys
from collections.abc import Sequence
from pathlib import Path
from uuid import UUID, uuid4

from collector.app import App, build_app
from collector.config import Settings
from collector.contract import schema
from collector.contract.canonical_json import JsonValue
from collector.contract.collection_request import CollectionRequest
from collector.errors import RequestRejected

# 명령별 거부 code의 종료 코드, 없는 code는 default
EXIT_CODES: dict[str, tuple[dict[str, int], int]] = {
    "registry import": ({"VALIDATION_FAILED": 2}, 3),
    "registry approve": ({"NOT_FOUND": 2}, 3),
    "registry revoke": ({"NOT_FOUND": 2}, 3),
    "request": ({}, 2),
    "submit": ({"ID_INPUT_CONFLICT": 3}, 2),
    "status": ({"NOT_FOUND": 4}, 3),
    "cancel": ({"NOT_FOUND": 4}, 3),
    "resume": ({"NOT_FOUND": 4}, 3),
}


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(prog="python -m collector")
    commands = root.add_subparsers(dest="command", required=True)

    registry_parser = commands.add_parser("registry")
    registry = registry_parser.add_subparsers(dest="registry_command", required=True)
    import_parser = registry.add_parser("import")
    import_parser.add_argument("file", type=Path)
    approve = registry.add_parser("approve")
    approve.add_argument("source_id")
    approve.add_argument("--by", required=True)
    approve.add_argument("--parser-profile", required=True)
    revoke = registry.add_parser("revoke")
    revoke.add_argument("source_id")
    revoke.add_argument("--by", required=True)

    request = commands.add_parser("request")
    request.add_argument("source_ids", nargs="+")
    submit = commands.add_parser("submit")
    submit.add_argument("file", type=Path)

    for name in ("status", "cancel", "resume"):
        job_parser = commands.add_parser(name)
        job_parser.add_argument("job_id", type=UUID)

    return root


def main(
    argv: Sequence[str] | None = None,
    app: App | None = None,
) -> int:
    args = parser().parse_args(argv)
    command = args.command if args.command != "registry" else f"registry {args.registry_command}"
    owned = app is None
    app = app or build_app(Settings.from_env())

    try:
        for definition, value in run(app, args):
            emit(
                definition,
                value,
                sys.stdout,
            )
    except RequestRejected as rejected:
        emit(
            "error",
            {
                "schemaVersion": 1,
                "code": rejected.code,
                "message": rejected.message[:500] or rejected.code,
                "requestId": None if rejected.request_id is None else str(rejected.request_id),
                "retryable": False,
                "traceId": str(rejected.trace_id or uuid4()),
            },
            sys.stderr,
        )
        codes, default = EXIT_CODES[command]
        return codes.get(rejected.code, default)
    finally:
        if owned:
            app.close()

    return 0


def run(
    app: App,
    args: argparse.Namespace,
) -> list[tuple[str | None, JsonValue]]:
    """명령 하나를 실행하고 (schema $defs 이름, 출력 JSON) 목록을 돌려줌"""

    registry_command = getattr(
        args,
        "registry_command",
        None,
    )

    match args.command, registry_command:
        case "registry", "import":
            return [(None, row.to_json()) for row in app.registry.import_file(args.file)]
        case "registry", "approve":
            view = app.registry.approve(
                args.source_id,
                args.by,
                args.parser_profile,
            )
            return [(None, view.to_json())]
        case "registry", "revoke":
            view = app.registry.revoke(args.source_id, args.by)
            return [(None, view.to_json())]
        case "request", _:
            request = app.registry.draft_request(args.source_ids)
            return [("collectionRequest", request.to_json())]
        case "submit", _:
            accept = app.jobs.submit(read_request(args.file))
            return [("accept", accept.to_json())]
        case "status", _:
            status = app.jobs.status(args.job_id)
            return [("status", status.to_json())]
        case "cancel", _:
            status = app.jobs.cancel(args.job_id)
            return [("status", status.to_json())]
        case "resume", _:
            status = app.jobs.resume(args.job_id)
            return [("status", status.to_json())]

    raise AssertionError(args)


def read_request(path: Path) -> CollectionRequest:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise RequestRejected("VALIDATION_FAILED", f"cannot read request file: {error}") from error

    return CollectionRequest.parse(value)


def emit(
    definition: str | None,
    value: JsonValue,
    stream: object,
) -> None:
    """contracts 정의가 있는 출력은 쓰기 전에 검증"""

    if definition is not None:
        schema.validate(definition, value)

    print(
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
        ),
        file=stream,
        flush=True,
    )


if __name__ == "__main__":
    sys.exit(main())
