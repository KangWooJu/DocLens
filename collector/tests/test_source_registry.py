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
from tests.conftest import FAKE_COMMIT, definition, git_definition


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


@pytest.mark.parametrize("rule", ["git-path-list-v1", "git-path-list-with-entry-v1"])
def test_git_blob_definition_lists_exact_paths(rule: str) -> None:
    source = parse_definition(git_definition(discovery_rule_id=rule))

    assert (source.source_type, source.discovery_rule_id) == ("git-blob", rule)
    assert source.scope.allowed_path_prefixes == (
        f"/spring-projects/spring-boot/{FAKE_COMMIT}/gradle.properties",
        f"/spring-projects/spring-boot/{FAKE_COMMIT}/docs/pages/index.adoc",
    )


OTHER_COMMIT = "f" * 40
BASE = f"/spring-projects/spring-boot/{FAKE_COMMIT}/"


@pytest.mark.parametrize(
    "change",
    [
        {"allowedPathPrefixes": [BASE + "gradle.properties", f"/spring-projects/spring-boot/{OTHER_COMMIT}/a.adoc"]},
        {"allowedPathPrefixes": [BASE + "gradle.properties", BASE + "docs/"]},
        {"allowedPathPrefixes": [BASE + "docs/pages/index.adoc"]},
        {"allowedPathPrefixes": [BASE + "gradle.properties", BASE + "a.adoc", BASE + "a.adoc"]},
        {
            "canonicalRoot": "https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.2/",
            "entryUrl": "https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.2/gradle.properties",
            "allowedPathPrefixes": ["/spring-projects/spring-boot/v4.1.2/gradle.properties"],
        },
        {"discoveryRuleId": "antora-nav-closed-v1"},
        {"sourceType": "html"},
    ],
)
def test_git_blob_definition_rejects_operator_mistakes(change: dict[str, object]) -> None:
    with pytest.raises(DefinitionInvalid):
        parse_definition({**git_definition(), **change})


def test_html_definition_rejects_git_rules() -> None:
    with pytest.raises(DefinitionInvalid):
        parse_definition({**definition(), "discoveryRuleId": "git-path-list-v1"})


def test_match_inputs_keeps_git_blob_jobs_on_one_commit() -> None:
    reference = git_definition()
    includes = git_definition(
        "spring-boot-4.1.2-reference-includes",
        discovery_rule_id="git-path-list-with-entry-v1",
    )
    other = git_definition(
        "spring-boot-4.1.2-reference-other",
        OTHER_COMMIT,
    )
    html = definition()
    approved = [row(reference), row(includes), row(other), row(html)]

    assert match_inputs([request_input(reference), request_input(includes)], approved) is None
    assert match_inputs([request_input(reference), request_input(other)], approved) == "VALIDATION_FAILED"
    assert match_inputs([request_input(reference), request_input(html)], approved) == "VALIDATION_FAILED"
