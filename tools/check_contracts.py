"""Language-neutral contract checks for contracts/v1.

Checks schema validity, every example against its declared schema, that every
example file is listed in examples/index.json, and that the golden vectors are
internally consistent. Language implementations are tested against the same
files in their own test suites.
"""

import hashlib
import json
import re
import struct
import sys
import unicodedata
from datetime import datetime
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import ValidationError

CONTRACTS = Path(__file__).resolve().parents[1] / "contracts" / "v1"
EXAMPLES = CONTRACTS / "examples"

# Contract timestamps are UTC with a trailing Z and at most 9 fractional digits.
UTC_TIMESTAMP = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?Z"
)
FORMAT_CHECKER = FormatChecker()


@FORMAT_CHECKER.checks("date-time", raises=ValueError)
def is_utc_timestamp(value: object) -> bool:
    if not isinstance(value, str):
        return True

    if UTC_TIMESTAMP.fullmatch(value) is None:
        return False

    # Real calendar check on the seconds part (rejects 2026-02-29, hour 24).
    datetime.fromisoformat(value[:19] + "+00:00")
    return True


def load_json(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8"))


def load_schemas() -> dict[str, dict]:
    schemas = {}

    for path in sorted(CONTRACTS.glob("*.schema.json")):
        schema = load_json(path)
        Draft202012Validator.check_schema(schema)
        schemas[path.name] = schema

    return schemas


def validator_for(
    schema: dict,
    definition: str | None,
) -> Draft202012Validator:
    if definition is None:
        return Draft202012Validator(
            schema,
            format_checker=FORMAT_CHECKER,
        )

    if definition not in schema["$defs"]:
        raise SystemExit(f"unknown definition: {definition}")

    target = {
        "$schema": schema["$schema"],
        "$defs": schema["$defs"],
        "$ref": f"#/$defs/{definition}",
    }
    return Draft202012Validator(
        target,
        format_checker=FORMAT_CHECKER,
    )


def check_examples(schemas: dict[str, dict]) -> int:
    entries = load_json(EXAMPLES / "index.json")["examples"]
    listed = [entry["file"] for entry in entries]

    if len(listed) != len(set(listed)):
        raise SystemExit("index.json lists a file more than once")

    present = {
        path.name
        for path in EXAMPLES.glob("*.json")
        if path.name != "index.json"
    }
    if present != set(listed):
        raise SystemExit(
            "index.json does not match example files: "
            f"unlisted={sorted(present - set(listed))} "
            f"missing={sorted(set(listed) - present)}"
        )

    for entry in entries:
        validator = validator_for(
            schemas[entry["schema"]],
            entry["definition"],
        )
        errors = list(validator.iter_errors(load_json(EXAMPLES / entry["file"])))

        if entry["expect"] == "accepted" and errors:
            raise SystemExit(f"{entry['file']} should pass: {errors[0].message}")

        if entry["expect"] == "rejected" and not errors:
            raise SystemExit(f"{entry['file']} should be rejected")

        if entry["expect"] not in ("accepted", "rejected"):
            raise SystemExit(f"{entry['file']}: unknown expect {entry['expect']}")

    return len(entries)


def nfc(value: object) -> object:
    if isinstance(value, str):
        return unicodedata.normalize("NFC", value)

    if isinstance(value, list):
        return [nfc(item) for item in value]

    if isinstance(value, dict):
        return {key: nfc(item) for key, item in value.items()}

    return value


def check_canonical_vectors() -> int:
    vectors = load_json(CONTRACTS / "canonical-golden-vectors.json")

    for vector in vectors:
        encoded = json.dumps(
            nfc(vector["input"]),
            sort_keys=True,
            ensure_ascii=False,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")

        if encoded.hex() != vector["canonicalUtf8Hex"]:
            raise SystemExit(f"canonical vector {vector['name']}: bytes differ")

        if "sha256:" + hashlib.sha256(encoded).hexdigest() != vector["hash"]:
            raise SystemExit(f"canonical vector {vector['name']}: hash differs")

    return len(vectors)


def check_id_vectors() -> int:
    vectors = load_json(CONTRACTS / "id-golden-vectors.json")

    for vector in vectors:
        encoded = b""

        for field in vector["fields"]:
            body = unicodedata.normalize("NFC", field).encode("utf-8")
            encoded += struct.pack(">I", len(body)) + body

        if encoded.hex() != vector["encodedHex"]:
            raise SystemExit(f"id vector {vector['name']}: encoding differs")

        if "sha256:" + hashlib.sha256(encoded).hexdigest() != vector["id"]:
            raise SystemExit(f"id vector {vector['name']}: id differs")

    return len(vectors)


def main() -> None:
    schemas = load_schemas()
    examples = check_examples(schemas)
    canonical = check_canonical_vectors()
    ids = check_id_vectors()

    print(
        f"contracts ok: {len(schemas)} schemas, {examples} examples, "
        f"{canonical} canonical vectors, {ids} id vectors"
    )


if __name__ == "__main__":
    try:
        main()
    except ValidationError as error:
        sys.exit(f"invalid schema: {error.message}")
