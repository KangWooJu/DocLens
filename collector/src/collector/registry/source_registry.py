"""source registry 정의 검사와 hash, 요청 input 대조 (순수 함수)"""

import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from urllib.parse import urlsplit

from collector.contract.canonical_json import JsonValue, canonical_bytes, content_hash
from collector.contract.collection_request import RequestInput
from collector.contract.ids import h

SOURCE_ID = re.compile(r"[a-z0-9][a-z0-9.-]{0,127}")
PROJECT = re.compile(r"[a-z][a-z0-9-]*")
EXACT_VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:\.RELEASE)?")
SOURCE_TYPES = frozenset({"html", "config-metadata", "javadoc"})
DOCUMENT_TYPES = frozenset(
    {
        "REFERENCE",
        "APPLICATION_PROPERTIES",
        "DEPENDENCY_VERSIONS",
        "PROJECT_OVERVIEW",
        "GUIDE",
        "RELEASE_NOTES",
        "MIGRATION_GUIDE",
        "CONFIG_METADATA",
        "JAVADOC",
        "EXAMPLE",
    },
)
TEXT_FIELDS = (
    "discoveryRuleId",
    "bodySelectorRuleId",
    "versionRuleId",
)
# 리다이렉트를 따르지 않고 query를 받지 않으므로 빈 목록만 허용
EMPTY_LIST_FIELDS = (
    "allowedQueryKeys",
    "redirectHosts",
)
DEFINITION_FIELDS = frozenset(
    {
        "sourceId",
        "project",
        "exactVersion",
        "sourceType",
        "documentType",
        "required",
        "applicability",
        "entryUrl",
        "canonicalRoot",
        "allowedHosts",
        "allowedPathPrefixes",
        "allowedQueryKeys",
        "redirectHosts",
        "excludePathPrefixes",
        "discoveryRuleId",
        "bodySelectorRuleId",
        "versionRuleId",
        "officialLinkEvidence",
    },
)


class DefinitionInvalid(ValueError):
    pass


@dataclass(frozen=True)
class SourceScope:
    allowed_hosts: tuple[str, ...]
    allowed_path_prefixes: tuple[str, ...]


@dataclass(frozen=True)
class Source:
    """sources.definition JSON을 읽은 불변 값"""

    source_id: str
    project: str
    exact_version: str
    source_type: str
    document_type: str
    required: bool
    applicability: str
    entry_url: str
    canonical_root: str
    scope: SourceScope
    discovery_rule_id: str
    version_rule_id: str
    include_exclude_policy_hash: str


@dataclass(frozen=True)
class SourceRow:
    """registry 표 한 행에서 대조와 hash에 쓰는 값"""

    source: Source
    definition_hash: str
    approval_state: str


def parse_definition(entry: Mapping[str, JsonValue]) -> Source:
    """필드와 형식 검사, 실패는 DefinitionInvalid"""

    unknown = set(entry) - DEFINITION_FIELDS
    missing = DEFINITION_FIELDS - set(entry)

    if unknown or missing:
        raise DefinitionInvalid(f"unknown fields {sorted(unknown)}, missing fields {sorted(missing)}")

    source_id = _matching(
        entry,
        "sourceId",
        SOURCE_ID,
    )
    canonical_root = _https(entry, "canonicalRoot")
    entry_url = _https(entry, "entryUrl")
    _https(entry, "officialLinkEvidence")
    hosts = _text_list(
        entry,
        "allowedHosts",
        allow_empty=False,
    )
    prefixes = _text_list(
        entry,
        "allowedPathPrefixes",
        allow_empty=False,
    )
    excludes = _text_list(
        entry,
        "excludePathPrefixes",
        allow_empty=True,
    )

    if not canonical_root.endswith("/") or not entry_url.startswith(canonical_root):
        raise DefinitionInvalid(f"{source_id}: entryUrl must be under canonicalRoot ending with /")

    if not all(prefix.startswith("/") for prefix in (*prefixes, *excludes)):
        raise DefinitionInvalid(f"{source_id}: path prefixes must start with /")

    if any(host != host.lower() or "/" in host or ":" in host for host in hosts):
        raise DefinitionInvalid(f"{source_id}: allowedHosts must be lowercase host names")

    for field in EMPTY_LIST_FIELDS:
        if _text_list(
            entry,
            field,
            allow_empty=True,
        ):
            raise DefinitionInvalid(f"{source_id}: {field} must be empty")

    if entry["sourceType"] not in SOURCE_TYPES or entry["documentType"] not in DOCUMENT_TYPES:
        raise DefinitionInvalid(f"{source_id}: unknown sourceType or documentType")

    if entry["applicability"] != "EXACT_RELEASE":
        raise DefinitionInvalid(f"{source_id}: applicability must be EXACT_RELEASE")

    if not isinstance(entry["required"], bool):
        raise DefinitionInvalid(f"{source_id}: required must be a boolean")

    for field in TEXT_FIELDS:
        if not isinstance(entry[field], str) or not entry[field]:
            raise DefinitionInvalid(f"{source_id}: {field} must be a non-empty string")

    return Source(
        source_id=source_id,
        project=_matching(
            entry,
            "project",
            PROJECT,
        ),
        exact_version=_matching(
            entry,
            "exactVersion",
            EXACT_VERSION,
        ),
        source_type=str(entry["sourceType"]),
        document_type=str(entry["documentType"]),
        required=entry["required"],
        applicability="EXACT_RELEASE",
        entry_url=entry_url,
        canonical_root=canonical_root,
        scope=SourceScope(
            allowed_hosts=hosts,
            allowed_path_prefixes=prefixes,
        ),
        discovery_rule_id=str(entry["discoveryRuleId"]),
        version_rule_id=str(entry["versionRuleId"]),
        include_exclude_policy_hash=h(
            "include-exclude-v1",
            *sorted(prefixes),
            "exclude",
            *sorted(excludes),
        ),
    )


def definition_hash(definition: Mapping[str, JsonValue]) -> str:
    return content_hash(canonical_bytes(dict(definition)))


def registry_hash(approved: Sequence[SourceRow]) -> str:
    """승인 행 수와 sourceId 순 definitionHash, 행 순서와 무관"""

    ordered = sorted(
        approved,
        key=lambda row: row.source.source_id,
    )
    return h(
        "registry-v1",
        str(len(ordered)),
        *(row.definition_hash for row in ordered),
    )


def match_inputs(
    inputs: Sequence[RequestInput],
    approved: Sequence[SourceRow],
) -> str | None:
    """None이면 모든 input이 승인 행과 일치, 아니면 contracts error code"""

    by_id = {row.source.source_id: row.source for row in approved}
    ids = [item.source_id for item in inputs]

    if len(ids) != len(set(ids)) or not any(item.required for item in inputs):
        return "VALIDATION_FAILED"

    for item in inputs:
        source = by_id.get(item.source_id)

        if source is None or _input_of(source) != item:
            return "SOURCE_UNAPPROVED"

    return None


def _input_of(source: Source) -> RequestInput:
    return RequestInput(
        source_id=source.source_id,
        project=source.project,
        exact_version=source.exact_version,
        source_type=source.source_type,
        document_type=source.document_type,
        required=source.required,
        applicability=source.applicability,
    )


def _matching(
    entry: Mapping[str, JsonValue],
    field: str,
    pattern: re.Pattern[str],
) -> str:
    value = entry[field]

    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise DefinitionInvalid(f"{field} has an invalid value")

    return value


def _https(
    entry: Mapping[str, JsonValue],
    field: str,
) -> str:
    value = entry[field]

    if not isinstance(value, str):
        raise DefinitionInvalid(f"{field} must be a string")

    parts = urlsplit(value)

    if parts.scheme != "https" or not parts.hostname or parts.username or parts.port or parts.query:
        raise DefinitionInvalid(f"{field} must be a plain https URL")

    return value


def _text_list(
    entry: Mapping[str, JsonValue],
    field: str,
    allow_empty: bool,
) -> tuple[str, ...]:
    value = entry[field]

    if not isinstance(value, list) or not all(isinstance(item, str) and item for item in value):
        raise DefinitionInvalid(f"{field} must be a list of non-empty strings")

    if not value and not allow_empty:
        raise DefinitionInvalid(f"{field} must not be empty")

    return tuple(value)
