"""collectionRequest 메시지"""

from collections.abc import Mapping
from dataclasses import dataclass
from uuid import UUID

from collector.contract import schema
from collector.contract.canonical_json import JsonValue
from collector.errors import RequestRejected


@dataclass(frozen=True)
class RequestInput:
    source_id: str
    project: str
    exact_version: str
    source_type: str
    document_type: str
    required: bool
    applicability: str

    def to_json(self) -> dict[str, JsonValue]:
        return {
            "sourceId": self.source_id,
            "project": self.project,
            "exactVersion": self.exact_version,
            "sourceType": self.source_type,
            "documentType": self.document_type,
            "required": self.required,
            "applicability": self.applicability,
        }


@dataclass(frozen=True)
class CollectionRequest:
    """원문 문자열을 그대로 보존해 to_json의 canonical hash가 원문과 같음"""

    request_id: str
    trace_id: str
    request_kind: str
    source_registry_hash: str
    inputs: tuple[RequestInput, ...]
    requested_at: str

    @classmethod
    def parse(cls, value: JsonValue) -> "CollectionRequest":
        """schema 검증 뒤 변환, 실패는 RequestRejected"""

        try:
            schema.validate("collectionRequest", value)
        except schema.SchemaViolation as error:
            raise RequestRejected(
                _rejection_code(value),
                str(error),
                request_id=_uuid_field(value, "requestId"),
                trace_id=_uuid_field(value, "traceId"),
            ) from error

        assert isinstance(value, Mapping)
        return cls.from_json(value)

    @classmethod
    def from_json(cls, value: Mapping[str, JsonValue]) -> "CollectionRequest":
        """검증된 원문 또는 DB에 저장한 본문에서 변환"""

        return cls(
            request_id=str(value["requestId"]),
            trace_id=str(value["traceId"]),
            request_kind=str(value["requestKind"]),
            source_registry_hash=str(value["sourceRegistryHash"]),
            inputs=tuple(
                RequestInput(
                    source_id=item["sourceId"],
                    project=item["project"],
                    exact_version=item["exactVersion"],
                    source_type=item["sourceType"],
                    document_type=item["documentType"],
                    required=item["required"],
                    applicability=item["applicability"],
                )
                for item in value["inputs"]
            ),
            requested_at=str(value["requestedAt"]),
        )

    def to_json(self) -> dict[str, JsonValue]:
        return {
            "schemaVersion": 1,
            "requestId": self.request_id,
            "traceId": self.trace_id,
            "requestKind": self.request_kind,
            "sourceRegistryHash": self.source_registry_hash,
            "inputs": [item.to_json() for item in self.inputs],
            "requestedAt": self.requested_at,
        }


def _rejection_code(value: JsonValue) -> str:
    # applicability만 어긋난 요청은 계약의 별도 code
    inputs = value.get("inputs") if isinstance(value, Mapping) else None

    if isinstance(inputs, list) and any(
        isinstance(item, Mapping) and item.get("applicability", "EXACT_RELEASE") != "EXACT_RELEASE"
        for item in inputs
    ):
        return "APPLICABILITY_UNSUPPORTED"

    return "VALIDATION_FAILED"


def _uuid_field(
    value: JsonValue,
    field: str,
) -> UUID | None:
    if not isinstance(value, Mapping):
        return None

    try:
        return UUID(str(value.get(field)))
    except ValueError:
        return None
