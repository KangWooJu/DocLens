"""registry 명령의 TX 범위"""

from collections.abc import Sequence
from pathlib import Path

from psycopg_pool import ConnectionPool

from collector.contract.collection_request import CollectionRequest
from collector.registry.registry_service import ImportedSource, ProbeResult, RegistryService, RegistryView


class RegistryFacade:
    """registry import, probe, 승인, 철회, 요청 초안의 TX 범위"""

    def __init__(
        self,
        pool: ConnectionPool,
        service: RegistryService,
    ) -> None:
        self._pool = pool
        self._service = service

    def import_file(self, path: Path) -> tuple[ImportedSource, ...]:
        entries = self._service.read(path)

        with self._pool.connection() as conn, conn.transaction():
            return self._service.import_entries(conn, entries)

    def probe(self, source_id: str) -> ProbeResult:
        """BLOCKED 확인 TX, TX 밖 표본 송신, 같은 정의일 때만 기록하는 TX"""

        with self._pool.connection() as conn, conn.transaction():
            source, probed_hash = self._service.probe_target(conn, source_id)

        result = self._service.probe_entry(source)

        with self._pool.connection() as conn, conn.transaction():
            self._service.record_probe(
                conn,
                source_id,
                probed_hash,
                result,
            )

        return result

    def approve(
        self,
        source_id: str,
        approved_by: str,
        parser_profile_ref: str,
    ) -> RegistryView:
        with self._pool.connection() as conn, conn.transaction():
            return self._service.approve(
                conn,
                source_id,
                approved_by,
                parser_profile_ref,
            )

    def revoke(
        self,
        source_id: str,
        revoked_by: str,
    ) -> RegistryView:
        with self._pool.connection() as conn, conn.transaction():
            return self._service.revoke(
                conn,
                source_id,
                revoked_by,
            )

    def draft_request(self, source_ids: Sequence[str]) -> CollectionRequest:
        with self._pool.connection() as conn, conn.transaction():
            return self._service.draft_request(conn, source_ids)
