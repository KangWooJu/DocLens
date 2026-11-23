"""collector.sources SQL"""

from collections.abc import Mapping, Sequence

from psycopg.types.json import Jsonb

from collector.contract.canonical_json import JsonValue
from collector.db import Conn
from collector.registry.source_registry import SourceRow, parse_definition

SELECT_SOURCE_FOR_UPDATE = """
SELECT definition, definition_hash, approval_state, probe_state, probe_version
FROM collector.sources
WHERE source_id = %(source_id)s
FOR UPDATE
"""

INSERT_SOURCE = """
INSERT INTO collector.sources (source_id, definition, definition_hash)
VALUES (%(source_id)s, %(definition)s, %(definition_hash)s)
"""

# 승인 전 행만 정의 교체, probe 증거는 비움
REDEFINE_SOURCE = """
UPDATE collector.sources
SET definition = %(definition)s, definition_hash = %(definition_hash)s,
    probe_state = 'NONE', probe_version = NULL, probe_raw_hash = NULL, probed_at = NULL
WHERE source_id = %(source_id)s AND approval_state = 'BLOCKED'
"""

SELECT_APPROVED = """
SELECT definition, definition_hash, approval_state
FROM collector.sources
WHERE approval_state = 'APPROVED'
ORDER BY source_id
"""

SELECT_SOURCES = """
SELECT definition, definition_hash, approval_state
FROM collector.sources
WHERE source_id = ANY(%(source_ids)s)
ORDER BY source_id
"""

APPROVE = """
UPDATE collector.sources
SET approval_state = 'APPROVED', approved_by = %(by)s, approved_at = clock_timestamp(),
    parser_profile_ref = %(profile)s
WHERE source_id = %(source_id)s AND approval_state = 'BLOCKED'
RETURNING definition_hash
"""

REVOKE = """
UPDATE collector.sources
SET approval_state = 'REVOKED', revoked_by = %(by)s, revoked_at = clock_timestamp()
WHERE source_id = %(source_id)s AND approval_state = 'APPROVED'
RETURNING definition_hash
"""


class RegistryRepository:
    def locked(
        self,
        conn: Conn,
        source_id: str,
    ) -> tuple[Mapping[str, JsonValue], str, str, str, str | None] | None:
        """정의, definition_hash, 승인 상태, probe 상태, probe 버전"""

        return (
            conn
            .execute(
                SELECT_SOURCE_FOR_UPDATE,
                {"source_id": source_id},
            )
            .fetchone()
        )

    def insert(
        self,
        conn: Conn,
        definition: Mapping[str, JsonValue],
        definition_hash: str,
    ) -> None:
        conn.execute(
            INSERT_SOURCE,
            {
                "source_id": definition["sourceId"],
                "definition": Jsonb(dict(definition)),
                "definition_hash": definition_hash,
            },
        )

    def redefine(
        self,
        conn: Conn,
        definition: Mapping[str, JsonValue],
        definition_hash: str,
    ) -> None:
        conn.execute(
            REDEFINE_SOURCE,
            {
                "source_id": definition["sourceId"],
                "definition": Jsonb(dict(definition)),
                "definition_hash": definition_hash,
            },
        )

    def approved(self, conn: Conn) -> tuple[SourceRow, ...]:
        return _rows(
            conn
            .execute(SELECT_APPROVED)
            .fetchall()
        )

    def sources(
        self,
        conn: Conn,
        source_ids: Sequence[str],
    ) -> tuple[SourceRow, ...]:
        return _rows(
            (
                conn
                .execute(
                    SELECT_SOURCES,
                    {"source_ids": list(source_ids)},
                )
                .fetchall()
            ),
        )

    def approve(
        self,
        conn: Conn,
        source_id: str,
        approved_by: str,
        parser_profile_ref: str,
    ) -> bool:
        row = (
            conn
            .execute(
                APPROVE,
                {
                    "source_id": source_id,
                    "by": approved_by,
                    "profile": parser_profile_ref,
                },
            )
            .fetchone()
        )
        return row is not None

    def revoke(
        self,
        conn: Conn,
        source_id: str,
        revoked_by: str,
    ) -> bool:
        row = (
            conn
            .execute(
                REVOKE,
                {
                    "source_id": source_id,
                    "by": revoked_by,
                },
            )
            .fetchone()
        )
        return row is not None


def _rows(rows: Sequence[tuple[Mapping[str, JsonValue], str, str]]) -> tuple[SourceRow, ...]:
    return tuple(
        SourceRow(
            source=parse_definition(definition),
            definition_hash=definition_hash,
            approval_state=approval_state,
        )
        for definition, definition_hash, approval_state in rows
    )
