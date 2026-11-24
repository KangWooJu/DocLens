import json
from datetime import UTC, datetime, timedelta, timezone

import pytest

from collector import __doc__ as package_doc
from collector.contract import schema
from collector.contract.canonical_json import canonical_bytes, content_hash, utc_text
from collector.contract.ids import ScopeInput, document_id, h, scope_hash

CONTRACTS = schema.DEFAULT_CONTRACTS_DIR


def load(name: str) -> object:
    return json.loads((CONTRACTS / name).read_text(encoding="utf-8"))


def test_package_imports() -> None:
    assert package_doc == "DocLens document collector."


def test_canonical_golden_vectors() -> None:
    for vector in load("canonical-golden-vectors.json"):
        body = canonical_bytes(vector["input"])

        assert body.hex() == vector["canonicalUtf8Hex"], vector["name"]
        assert content_hash(body) == vector["hash"], vector["name"]


def test_utc_text_truncates_to_seconds_in_utc() -> None:
    moment = datetime(
        2026,
        11,
        24,
        9,
        0,
        1,
        999999,
        tzinfo=timezone(timedelta(hours=9)),
    )

    assert utc_text(moment) == "2026-11-24T00:00:01Z"
    assert utc_text(datetime(
        2026,
        1,
        2,
        tzinfo=UTC,
    )) == "2026-01-02T00:00:00Z"


def test_id_golden_vectors() -> None:
    vectors = load("id-golden-vectors.json")

    for vector in vectors:
        assert h(*vector["fields"]) == vector["id"], vector["name"]

    names = {vector["name"] for vector in vectors}
    assert {"scope-one-input", "scope-two-inputs", "include-exclude"} <= names


@pytest.mark.parametrize("name", ["document", "document-git-blob"])
def test_document_id_matches_vector(name: str) -> None:
    vector = next(item for item in load("id-golden-vectors.json") if item["name"] == name)

    assert document_id(*vector["fields"][1:]) == vector["id"]


@pytest.mark.parametrize("name", ["scope-one-input", "scope-two-inputs", "scope-git-path-list-two-inputs"])
def test_scope_hash_sorts_inputs_and_matches_vector(name: str) -> None:
    vector = next(item for item in load("id-golden-vectors.json") if item["name"] == name)
    fields = vector["fields"]
    count = int(fields[1])
    inputs = [
        ScopeInput(
            source_id=chunk[0],
            project=chunk[1],
            exact_version=chunk[2],
            source_type=chunk[3],
            document_type=chunk[4],
            required=chunk[5] == "true",
            applicability=chunk[6],
            discovery_rule_id=chunk[7],
            canonical_root=chunk[8],
            include_exclude_policy_hash=chunk[9],
        )
        for chunk in (fields[2 + index * 10:12 + index * 10] for index in range(count))
    ]

    assert len(fields) == 2 + count * 10
    assert scope_hash(list(reversed(inputs))) == vector["id"]


def test_collection_examples() -> None:
    entries = [
        entry
        for entry in load("examples/index.json")["examples"]
        if entry["schema"] == "collection.schema.json"
    ]
    assert entries

    for entry in entries:
        example = json.loads((CONTRACTS / "examples" / entry["file"]).read_text(encoding="utf-8"))

        if entry["expect"] == "accepted":
            schema.validate(entry["definition"], example)
        else:
            with pytest.raises(schema.SchemaViolation):
                schema.validate(entry["definition"], example)


def test_schema_formats_reject_bad_values() -> None:
    error = {
        "schemaVersion": 1,
        "code": "NOT_FOUND",
        "message": "missing",
        "requestId": None,
        "retryable": False,
        "traceId": "11111111-1111-4111-8111-111111111111",
    }
    schema.validate("error", error)

    with pytest.raises(schema.SchemaViolation):
        schema.validate("error", {**error, "traceId": "not-a-uuid"})

    status = load("examples/status.accepted.json")

    with pytest.raises(schema.SchemaViolation):
        schema.validate("status", {**status, "updatedAt": "2026-02-30T00:00:00Z"})
