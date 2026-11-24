"""run 생성, entry nav 발견, 대상 목록 닫기"""

from dataclasses import dataclass
from itertools import groupby
from uuid import uuid4

from collector.config import CrawlPolicy
from collector.contract.ids import ScopeInput, document_id, scope_hash
from collector.crawl.crawl_repository import CrawlRepository, NewBatch, NewTarget, ProjectionRow, RunRow
from collector.crawl.page_markers import PageMarkers
from collector.crawl.send_policy import Accepted, Response, TransportFailed, classify
from collector.crawl.transport_gate import Deferred, Fetch, RobotsDenied, TransportGate, UrlDenied
from collector.crawl.urls import canonical_url
from collector.db import Conn
from collector.job.job_model import ClaimedJob, Review
from collector.registry.source_registry import Source

BATCH_SIZE = 100


@dataclass(frozen=True)
class EntryPage:
    source: Source
    response: Response
    markers: PageMarkers


class DiscoveryService:
    def __init__(
        self,
        gate: TransportGate,
        repository: CrawlRepository,
        policy: CrawlPolicy,
    ) -> None:
        self._gate = gate
        self._repository = repository
        self._policy = policy

    def ensure_run(
        self,
        conn: Conn,
        job: ClaimedJob,
    ) -> RunRow:
        """없으면 run과 projection 생성, snapshotId와 scopeHash와 observationSequence 고정"""

        job_id = job.lease.job_id
        existing = self._repository.run(conn, job_id)

        if existing is not None:
            return existing

        run_id = uuid4()
        sequence = self._repository.insert_run(
            conn,
            run_id=run_id,
            job_id=job_id,
            scope_hash=scope_hash([_scope_input(source) for source in job.sources]),
            source_registry_hash=job.request.source_registry_hash,
            snapshot_id=uuid4(),
        )
        assert sequence is not None

        def pack(source: Source) -> tuple[str, str]:
            return source.project, source.exact_version

        for (project, exact_version), group in groupby(sorted(job.sources, key=pack), key=pack):
            members = list(group)
            self._repository.insert_projection(
                conn,
                run_id,
                ProjectionRow(
                    project=project,
                    exact_version=exact_version,
                    applicability=members[0].applicability,
                    input_scope_key=scope_hash([_scope_input(source) for source in members]),
                    observation_sequence=sequence,
                ),
            )

        run = self._repository.run(conn, job_id)
        assert run is not None
        return run

    def fetch_entries(self, job: ClaimedJob) -> tuple[EntryPage, ...] | Review | Deferred:
        """TX 밖: source마다 robots와 entry를 gate로, entry gate 검사"""

        entries = []

        for source in job.sources:
            entry = self._fetch_entry(job, source)

            if not isinstance(entry, EntryPage):
                return entry

            entries.append(entry)

        return tuple(entries)

    def close(
        self,
        conn: Conn,
        run: RunRow,
        entries: tuple[EntryPage, ...],
    ) -> None:
        """entry nav와 entry 자신을 대상으로, (project, exactVersion, documentType)별 URL 순 100개씩 batch"""

        targets: dict[str, tuple[tuple[str, str, str], NewTarget]] = {}

        for entry in entries:
            source = entry.source
            links = {
                canonical_url(
                    source.entry_url,
                    href,
                    source.scope,
                )
                for href in (source.entry_url, *entry.markers.nav_hrefs)
            }

            for url in sorted(link for link in links if link is not None):
                identity = document_id(
                    source.project,
                    source.exact_version,
                    source.source_type,
                    source.document_type,
                    url,
                )
                targets.setdefault(
                    identity,
                    (
                        (source.project, source.exact_version, source.document_type),
                        NewTarget(
                            document_id=identity,
                            source_id=source.source_id,
                            url=url,
                        ),
                    ),
                )

        ordered = sorted(targets.values(), key=lambda item: (item[0], item[1].url))
        batches = []

        for (project, exact_version, document_type), group in groupby(ordered, key=lambda item: item[0]):
            members = [target for _, target in group]

            for start in range(
                0,
                len(members),
                BATCH_SIZE,
            ):
                batches.append(
                    NewBatch(
                        batch_id=uuid4(),
                        ordinal=len(batches),
                        project=project,
                        exact_version=exact_version,
                        document_type=document_type,
                        targets=tuple(members[start:start + BATCH_SIZE]),
                    ),
                )

        self._repository.close(
            conn,
            run.run_id,
            batches,
            self._policy.attempt_cap,
            entries[0].markers.source_tag,
        )

    def _fetch_entry(
        self,
        job: ClaimedJob,
        source: Source,
    ) -> EntryPage | Review | Deferred:
        fetch = Fetch(
            url=source.entry_url,
            purpose="DISCOVERY",
            source_id=source.source_id,
            scope=source.scope,
        )

        try:
            response: Response | Deferred | TransportFailed = self._gate.send(job.lease, fetch)
        except RobotsDenied:
            return Review("ROBOTS_DENIED")
        except UrlDenied:
            return Review("URL_DENIED")
        except TransportFailed as failed:
            response = failed

        if isinstance(response, Deferred):
            return response

        # entry는 시도 행이 없어 재시도하지 않음, 실패 코드만 문서 분류와 같게
        decision = classify(
            response,
            self._policy.attempt_cap,
            self._policy,
            source.source_type,
        )

        if not isinstance(decision, Accepted):
            return Review(decision.error_code)

        if decision.markers.version != source.exact_version:
            return Review("VERSION_UNVERIFIED")

        if not decision.markers.nav_hrefs:
            return Review("INVALID_BODY")

        return EntryPage(
            source=source,
            response=decision.response,
            markers=decision.markers,
        )


def _scope_input(source: Source) -> ScopeInput:
    return ScopeInput(
        source_id=source.source_id,
        project=source.project,
        exact_version=source.exact_version,
        source_type=source.source_type,
        document_type=source.document_type,
        required=source.required,
        applicability=source.applicability,
        discovery_rule_id=source.discovery_rule_id,
        canonical_root=source.canonical_root,
        include_exclude_policy_hash=source.include_exclude_policy_hash,
    )

