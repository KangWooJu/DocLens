"""registry 정의 import, 승인, 철회, 수집 요청 초안"""

import json
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

from collector.contract.canonical_json import JsonValue, utc_text
from collector.contract.collection_request import CollectionRequest, RequestInput
from collector.db import Conn
from collector.errors import RequestRejected
from collector.registry.registry_repository import RegistryRepository
from collector.registry.source_registry import (
    DefinitionInvalid,
    definition_hash,
    parse_definition,
    registry_hash,
)

PARSER_PROFILE_REF = re.compile(r"[a-z0-9][a-z0-9-]*@[0-9]+")


@dataclass(frozen=True)
class ImportedSource:
    source_id: str
    definition_hash: str
    approval_state: str

    def to_json(self) -> dict[str, JsonValue]:
        return {
            "sourceId": self.source_id,
            "definitionHash": self.definition_hash,
            "approvalState": self.approval_state,
        }


@dataclass(frozen=True)
class RegistryView:
    approval_state: str
    registry_hash: str

    def to_json(self) -> dict[str, JsonValue]:
        return {
            "approvalState": self.approval_state,
            "registryHash": self.registry_hash,
        }


class RegistryService:
    def __init__(self, repository: RegistryRepository) -> None:
        self._repository = repository

    def read(self, path: Path) -> tuple[Mapping[str, JsonValue], ...]:
        """registry 정의 파일의 source entry 목록, 형식 오류는 VALIDATION_FAILED"""

        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise RequestRejected("VALIDATION_FAILED", f"cannot read registry file: {error}") from error

        if not isinstance(document, dict):
            raise RequestRejected("VALIDATION_FAILED", "registry file must be a JSON object")

        sources = document.get("sources")

        if document.get("registryVersion") != 1 or not isinstance(sources, list) or not sources:
            raise RequestRejected("VALIDATION_FAILED", "registry file needs registryVersion 1 and sources")

        if not all(isinstance(entry, dict) for entry in sources):
            raise RequestRejected("VALIDATION_FAILED", "every source entry must be an object")

        ids = [entry.get("sourceId") for entry in sources]

        if len(ids) != len(set(ids)):
            raise RequestRejected("VALIDATION_FAILED", "sourceId must be unique in a registry file")

        for entry in sources:
            try:
                parse_definition(entry)
            except DefinitionInvalid as error:
                raise RequestRejected("VALIDATION_FAILED", str(error)) from error

        return tuple(sources)

    def import_entries(
        self,
        conn: Conn,
        entries: Sequence[Mapping[str, JsonValue]],
    ) -> tuple[ImportedSource, ...]:
        """새 행은 BLOCKED, 바뀐 BLOCKED 행은 교체, 승인·철회 행과 다르면 거부"""

        imported = []

        for entry in entries:
            source_id = str(entry["sourceId"])
            new_hash = definition_hash(entry)
            current = self._repository.locked(conn, source_id)

            if current is None:
                self._repository.insert(
                    conn,
                    entry,
                    new_hash,
                )
                state = "BLOCKED"
            else:
                _, old_hash, state, _, _ = current

                if old_hash != new_hash and state != "BLOCKED":
                    raise RequestRejected(
                        "ID_INPUT_CONFLICT",
                        f"{source_id} is {state} with another definition, import it under a new sourceId",
                    )

                if old_hash != new_hash:
                    self._repository.redefine(
                        conn,
                        entry,
                        new_hash,
                    )

            imported.append(
                ImportedSource(
                    source_id=source_id,
                    definition_hash=new_hash,
                    approval_state=state,
                ),
            )

        return tuple(imported)

    def approve(
        self,
        conn: Conn,
        source_id: str,
        approved_by: str,
        parser_profile_ref: str,
    ) -> RegistryView:
        """probe 통과와 관측 버전 일치를 먼저 확인, DB CHECK가 같은 규칙을 다시 강제"""

        if not approved_by or PARSER_PROFILE_REF.fullmatch(parser_profile_ref) is None:
            raise RequestRejected("VALIDATION_FAILED", "approval needs --by and --parser-profile ID@VERSION")

        definition, _, state, probe_state, probe_version = self._locked(conn, source_id)

        if state != "BLOCKED":
            raise RequestRejected("INVALID_TRANSITION", f"{source_id} is {state}, only BLOCKED can be approved")

        if probe_state != "PASSED" or probe_version != definition["exactVersion"]:
            raise RequestRejected(
                "VERSION_UNVERIFIED",
                f"{source_id} needs a PASSED probe that observed {definition['exactVersion']}",
            )

        self._repository.approve(
            conn,
            source_id,
            approved_by,
            parser_profile_ref,
        )
        return self._view(conn, "APPROVED")

    def revoke(
        self,
        conn: Conn,
        source_id: str,
        revoked_by: str,
    ) -> RegistryView:
        if not revoked_by:
            raise RequestRejected("VALIDATION_FAILED", "revocation needs --by")

        _, _, state, _, _ = self._locked(conn, source_id)

        if state != "APPROVED":
            raise RequestRejected("INVALID_TRANSITION", f"{source_id} is {state}, only APPROVED can be revoked")

        self._repository.revoke(
            conn,
            source_id,
            revoked_by,
        )
        return self._view(conn, "REVOKED")

    def draft_request(
        self,
        conn: Conn,
        source_ids: Sequence[str],
    ) -> CollectionRequest:
        """승인 source로 새 collectionRequest, DB에 쓰지 않음"""

        approved = self._repository.approved(conn)
        by_id = {row.source.source_id: row.source for row in approved}
        missing = sorted(set(source_ids) - set(by_id))

        if missing:
            raise RequestRejected("SOURCE_UNAPPROVED", f"not approved: {', '.join(missing)}")

        return CollectionRequest(
            request_id=str(uuid4()),
            trace_id=str(uuid4()),
            request_kind="COLLECT",
            source_registry_hash=registry_hash(approved),
            inputs=tuple(
                RequestInput(
                    source_id=source.source_id,
                    project=source.project,
                    exact_version=source.exact_version,
                    source_type=source.source_type,
                    document_type=source.document_type,
                    required=source.required,
                    applicability=source.applicability,
                )
                for source in sorted(
                    (by_id[source_id] for source_id in set(source_ids)),
                    key=lambda source: source.source_id,
                )
            ),
            requested_at=utc_text(datetime.now(UTC)),
        )

    def _locked(
        self,
        conn: Conn,
        source_id: str,
    ) -> tuple[Mapping[str, JsonValue], str, str, str, str | None]:
        current = self._repository.locked(conn, source_id)

        if current is None:
            raise RequestRejected("NOT_FOUND", f"unknown source {source_id}")

        return current

    def _view(
        self,
        conn: Conn,
        approval_state: str,
    ) -> RegistryView:
        return RegistryView(
            approval_state=approval_state,
            registry_hash=registry_hash(self._repository.approved(conn)),
        )
