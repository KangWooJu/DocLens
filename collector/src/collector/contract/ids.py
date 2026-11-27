"""길이 접두 ID hash와 범위 hash"""

import hashlib
import struct
import unicodedata
from collections.abc import Sequence
from dataclasses import dataclass


@dataclass(frozen=True)
class ScopeInput:
    """scope-v1 hash에 들어가는 input 하나의 10필드"""

    source_id: str
    project: str
    exact_version: str
    source_type: str
    document_type: str
    required: bool
    applicability: str
    discovery_rule_id: str
    canonical_root: str
    include_exclude_policy_hash: str


def h(*fields: str) -> str:
    """필드마다 NFC UTF-8 bytes 앞에 4바이트 big-endian 길이, 전체 SHA-256"""

    encoded = b""

    for field in fields:
        body = (
            unicodedata
            .normalize("NFC", field)
            .encode("utf-8")
        )
        encoded += struct.pack(">I", len(body)) + body

    return "sha256:" + (
        hashlib
        .sha256(encoded)
        .hexdigest()
    )


def document_id(
    project: str,
    exact_version: str,
    source_type: str,
    document_type: str,
    canonical_url: str,
) -> str:
    return h(
        "document-v1",
        project,
        exact_version,
        source_type,
        document_type,
        canonical_url,
    )


def scope_hash(inputs: Sequence[ScopeInput]) -> str:
    """전체 run scopeHash, projection의 input만 넘기면 inputScopeKey"""

    ordered = sorted(
        inputs,
        key=lambda item: (
            item.source_id,
            item.project,
            item.exact_version,
            item.source_type,
            item.document_type,
        ),
    )
    fields = ["scope-v1", str(len(ordered))]

    for item in ordered:
        fields.extend(
            (
                item.source_id,
                item.project,
                item.exact_version,
                item.source_type,
                item.document_type,
                "true" if item.required else "false",
                item.applicability,
                item.discovery_rule_id,
                item.canonical_root,
                item.include_exclude_policy_hash,
            ),
        )

    return h(*fields)
