import pytest

from collector.contract.collection_request import RequestInput
from collector.contract.ids import h
from collector.registry.source_registry import (
    DefinitionInvalid,
    SourceRow,
    definition_hash,
    match_inputs,
    parse_definition,
    registry_hash,
)
from tests.conftest import definition


def row(entry: dict[str, object]) -> SourceRow:
    return SourceRow(
        source=parse_definition(entry),
        definition_hash=definition_hash(entry),
        approval_state="APPROVED",
    )


def request_input(entry: dict[str, object]) -> RequestInput:
    return RequestInput(
        source_id=entry["sourceId"],
        project=entry["project"],
        exact_version=entry["exactVersion"],
        source_type=entry["sourceType"],
        document_type=entry["documentType"],
        required=entry["required"],
        applicability=entry["applicability"],
    )


def test_parse_definition_reads_scope_and_policy_hash() -> None:
    source = parse_definition(definition())

    assert source.scope.allowed_hosts == ("docs.spring.io",)
    assert source.scope.allowed_path_prefixes == ("/spring-boot/4.1/reference/",)
    assert source.include_exclude_policy_hash == h(
        "include-exclude-v1",
        "/spring-boot/4.1/reference/",
        "exclude",
    )


@pytest.mark.parametrize(
    "change",
    [
        {"sourceId": "Spring Boot"},
        {"exactVersion": "4.1"},
        {"sourceType": "pdf"},
        {"applicability": "MINOR_LINE"},
        {"required": "yes"},
        {"entryUrl": "http://docs.spring.io/spring-boot/4.1/reference/index.html"},
        {"entryUrl": "https://docs.spring.io/other/index.html"},
        {"canonicalRoot": "https://docs.spring.io:8443/spring-boot/4.1/reference/"},
        {"allowedHosts": []},
        {"allowedHosts": ["Docs.Spring.io"]},
        {"allowedPathPrefixes": []},
        {"allowedPathPrefixes": ["spring-boot/"]},
        {"allowedQueryKeys": ["page"]},
        {"redirectHosts": ["spring.io"]},
        {"discoveryRuleId": ""},
        {"unexpected": True},
    ],
)
def test_parse_definition_rejects_invalid_fields(change: dict[str, object]) -> None:
    with pytest.raises(DefinitionInvalid):
        parse_definition({**definition(), **change})


def test_parse_definition_rejects_missing_field() -> None:
    entry = definition()
    del entry["versionRuleId"]

    with pytest.raises(DefinitionInvalid):
        parse_definition(entry)


def test_definition_hash_ignores_key_order() -> None:
    entry = definition()

    assert definition_hash(entry) == definition_hash(dict(reversed(list(entry.items()))))


def test_registry_hash_ignores_row_order_and_tracks_fields() -> None:
    boot = definition()
    framework = definition(
        "spring-framework-7.0.2-reference",
        "7.0.2",
        required=False,
    )
    current = registry_hash([row(boot), row(framework)])

    assert registry_hash([row(framework), row(boot)]) == current
    assert registry_hash([row({**boot, "versionRuleId": "other"}), row(framework)]) != current
    assert registry_hash([row(boot)]) != current


def test_match_inputs_codes() -> None:
    boot = definition()
    framework = definition(
        "spring-framework-7.0.2-reference",
        "7.0.2",
        required=False,
    )
    approved = [row(boot), row(framework)]

    assert match_inputs([request_input(boot), request_input(framework)], approved) is None
    assert match_inputs([request_input(framework)], approved) == "VALIDATION_FAILED"
    assert match_inputs([request_input(boot), request_input(boot)], approved) == "VALIDATION_FAILED"
    assert match_inputs([request_input({**boot, "exactVersion": "4.1.4"})], approved) == "SOURCE_UNAPPROVED"
    assert match_inputs([request_input(boot)], [row(framework)]) == "SOURCE_UNAPPROVED"
