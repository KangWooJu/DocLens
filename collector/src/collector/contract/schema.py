"""contracts/v1 collection schema의 $defs 검증"""

import json
import os
import re
from datetime import datetime
from functools import cache
from pathlib import Path
from urllib.parse import urlsplit
from uuid import UUID

from jsonschema import Draft202012Validator, FormatChecker

from collector.contract.canonical_json import JsonValue

# 저장소 루트의 contracts/v1, 패키지 안에 복사하지 않음
DEFAULT_CONTRACTS_DIR = Path(__file__).resolve().parents[4] / "contracts" / "v1"

UTC_TIMESTAMP = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?Z",
)

FORMAT_CHECKER = FormatChecker(formats=())


class SchemaViolation(ValueError):
    pass


@FORMAT_CHECKER.checks("date-time", raises=ValueError)
def _is_utc_timestamp(value: object) -> bool:
    if not isinstance(value, str):
        return True

    if UTC_TIMESTAMP.fullmatch(value) is None:
        return False

    datetime.fromisoformat(value[:19] + "+00:00")
    return True


@FORMAT_CHECKER.checks("uuid", raises=ValueError)
def _is_uuid(value: object) -> bool:
    if not isinstance(value, str):
        return True

    return str(UUID(value)) == value.lower()


@FORMAT_CHECKER.checks("uri")
def _is_uri(value: object) -> bool:
    if not isinstance(value, str):
        return True

    parts = urlsplit(value)
    return bool(parts.scheme) and bool(parts.netloc or parts.path)


def contracts_dir() -> Path:
    return Path(os.environ.get("COLLECTOR_CONTRACTS_DIR", DEFAULT_CONTRACTS_DIR))


def validate(
    definition: str,
    value: JsonValue,
) -> None:
    """$defs 이름으로 검증, 실패는 SchemaViolation"""

    error = next(
        _validator(definition).iter_errors(value),
        None,
    )

    if error is not None:
        path = "/".join(str(part) for part in error.absolute_path)
        raise SchemaViolation(f"{definition} {path or '/'}: {error.message}")


@cache
def _validator(definition: str) -> Draft202012Validator:
    schema = json.loads(
        (contracts_dir() / "collection.schema.json").read_text(encoding="utf-8"),
    )

    if definition not in schema["$defs"]:
        raise KeyError(definition)

    return Draft202012Validator(
        {
            "$schema": schema["$schema"],
            "$defs": schema["$defs"],
            "$ref": f"#/$defs/{definition}",
        },
        format_checker=FORMAT_CHECKER,
    )
