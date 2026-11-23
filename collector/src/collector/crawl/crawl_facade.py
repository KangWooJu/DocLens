"""job 하나를 끝까지 또는 다음 대기 지점까지, 재실행하면 PG 상태에서 이어 감"""

import threading
from collections.abc import Iterator
from contextlib import contextmanager

from psycopg_pool import ConnectionPool

from collector.config import CrawlPolicy
from collector.crawl.crawl_repository import RunRow
from collector.crawl.discovery_service import DiscoveryService
from collector.crawl.fetch_service import FetchService
from collector.crawl.seal_service import SealService
from collector.crawl.transport_gate import Deferred, RobotsUnreachable, SourceUnapproved
from collector.db import Conn
from collector.job.job_model import Cancelled, ClaimedJob, JobOutcome, Review, Succeeded, Waiting
from collector.job.job_repository import CancelRequested, JobRepository, Lease, LeaseLost
from collector.store.object_store import STORAGE_ERRORS


class _Stopped(Exception):
    """shutdown 또는 이 job의 lease 상실, 아무것도 더 쓰지 않음"""


class CrawlFacade:
    """단계마다 fenced TX, 송신과 업로드는 TX 밖"""

    def __init__(
        self,
        pool: ConnectionPool,
        discovery: DiscoveryService,
        fetch: FetchService,
        seal: SealService,
        jobs: JobRepository,
        policy: CrawlPolicy,
    ) -> None:
        self._pool = pool
        self._discovery = discovery
        self._fetch = fetch
        self._seal = seal
        self._jobs = jobs
        self._policy = policy

    def run(
        self,
        job: ClaimedJob,
        stop: threading.Event,
    ) -> JobOutcome | None:
        """None이면 stop 또는 lease 상실, 짧은 대기는 lease를 유지한 채 job 안에서 잠"""

        try:
            return self._run(job, stop)
        except (_Stopped, LeaseLost):
            return None
        except CancelRequested:
            return Cancelled()
        except SourceUnapproved:
            return Review("SOURCE_UNAPPROVED")
        except RobotsUnreachable:
            return Review("ROBOTS_UNREACHABLE")
        except STORAGE_ERRORS:
            return Review("STORAGE_FAILED")

    def _run(
        self,
        job: ClaimedJob,
        stop: threading.Event,
    ) -> JobOutcome:
        # 복구와 run
        with self._fenced(job.lease) as conn:
            self._fetch.recover(conn, job.lease)
            run = self._discovery.ensure_run(conn, job)

        # 발견과 닫기, run이 DISCOVERING일 때만
        if run.discovery_state == "DISCOVERING":
            outcome = self._discover(
                job,
                run,
                stop,
            )

            if outcome is not None:
                return outcome

        outcome = self._objects(job, stop)

        if outcome is not None:
            return outcome

        # 문서
        while True:
            self._check(stop)

            with self._fenced(job.lease) as conn:
                due = self._fetch.next_due(conn, run.run_id)

            if due is None:
                break

            if isinstance(due, Waiting):
                outcome = self._pause(due, stop)

                if outcome is not None:
                    return outcome

                continue

            result = self._fetch.fetch(job, due)

            if isinstance(result, Deferred):
                outcome = self._pause(
                    Waiting(result.reason, result.delay_seconds),
                    stop,
                )

                if outcome is not None:
                    return outcome

                continue

            with self._fenced(job.lease) as conn:
                self._fetch.record(
                    conn,
                    job,
                    due,
                    result,
                )

        # 봉인과 readyRun
        outcome = self._objects(job, stop)

        if outcome is None:
            raise RuntimeError(f"job {job.lease.job_id} ended its documents without a readyRun")

        return outcome

    def _discover(
        self,
        job: ClaimedJob,
        run: RunRow,
        stop: threading.Event,
    ) -> Review | Waiting | None:
        while True:
            self._check(stop)
            entries = self._discovery.fetch_entries(job)

            if isinstance(entries, Review):
                return entries

            if isinstance(entries, Deferred):
                outcome = self._pause(
                    Waiting(entries.reason, entries.delay_seconds),
                    stop,
                )

                if outcome is not None:
                    return outcome

                continue

            with self._fenced(job.lease) as conn:
                self._discovery.close(
                    conn,
                    run,
                    entries,
                )

            return None

    def _objects(
        self,
        job: ClaimedJob,
        stop: threading.Event,
    ) -> Succeeded | Review | None:
        """준비된 객체를 차례로 업로드하고 기록, readyRun이면 Succeeded"""

        while True:
            self._check(stop)

            with self._fenced(job.lease) as conn:
                pending = self._seal.next_object(conn, job)

            if pending is None or isinstance(pending, Review):
                return pending

            ref = self._seal.put(pending)

            with self._fenced(job.lease) as conn:
                self._seal.record(
                    conn,
                    job,
                    pending,
                    ref,
                )

            if pending.kind == "READY_RUN":
                return Succeeded(ref)

    def _pause(
        self,
        waiting: Waiting,
        stop: threading.Event,
    ) -> Waiting | None:
        """짧은 대기는 여기서 자고 None, 긴 대기와 예산 소진은 Waiting으로 돌려줌"""

        delay = waiting.delay_seconds

        if delay is None or delay > self._policy.inline_wait_max:
            return waiting

        if stop.wait(delay):
            raise _Stopped

        return None

    @contextmanager
    def _fenced(self, lease: Lease) -> Iterator[Conn]:
        with self._pool.connection() as conn, conn.transaction():
            self._jobs.fence(conn, lease)
            yield conn

    @staticmethod
    def _check(stop: threading.Event) -> None:
        if stop.is_set():
            raise _Stopped
