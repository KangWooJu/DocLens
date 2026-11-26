"""worker 프로세스 루프: 전역 lock, claim, heartbeat 스레드, 수집, 종결, 다음 기한까지 대기"""

import logging
import signal
import threading
import time
from collections.abc import Callable
from contextlib import AbstractContextManager
from types import FrameType
from uuid import UUID, uuid4

from collector.config import CrawlPolicy
from collector.crawl.crawl_facade import CrawlFacade
from collector.db import DB_ERRORS
from collector.job.job_facade import JobFacade
from collector.job.job_model import ClaimedJob, Failed, JobOutcome, Succeeded, Waiting
from collector.job.job_repository import Lease
from collector.job.job_service import JobStatus

HEARTBEAT_THREAD = "collector-heartbeat"

type WorkerLock = Callable[[], AbstractContextManager[Callable[[], bool] | None]]

log = logging.getLogger(__name__)


class HeartbeatThread(threading.Thread):
    """lease 연장, 0 row나 예외면 이 job의 lost와 stop을 켜고 끝"""

    def __init__(
        self,
        jobs: JobFacade,
        lease: Lease,
        interval: float,
        lost: threading.Event,
        stop: threading.Event,
    ) -> None:
        super().__init__(
            name=HEARTBEAT_THREAD,
            daemon=True,
        )
        self._jobs = jobs
        self._lease = lease
        self._interval = interval
        self._lost = lost
        self._stop = stop
        self._halt = threading.Event()

    def run(self) -> None:
        while not self._halt.wait(self._interval):
            try:
                held = self._jobs.heartbeat(self._lease)
            except Exception:  # 스레드 최외곽, lease 상실과 같게 처리
                log.exception("heartbeat failed for job %s", self._lease.job_id)
                held = False

            if not held:
                self._lost.set()
                self._stop.set()
                return

    def halt(self, timeout: float) -> bool:
        """정지 신호 뒤 join, 시간 안에 끝나면 True"""

        self._halt.set()
        self.join(timeout)
        return not self.is_alive()


class Worker:
    """advisory lock으로 전역 1개, claim부터 종결까지의 프로세스 루프"""

    def __init__(
        self,
        jobs: JobFacade,
        crawl: CrawlFacade,
        policy: CrawlPolicy,
        lock: WorkerLock,
    ) -> None:
        self._jobs = jobs
        self._crawl = crawl
        self._policy = policy
        self._lock = lock
        self._shutdown = threading.Event()
        self._job_stop = threading.Event()

    def run(
        self,
        max_wait: float | None,
        report: Callable[[JobStatus], None] = lambda status: None,
        serve: bool = False,
    ) -> int:
        """0 대기 job 없음(serve면 종료 신호), 6 max_wait 도달, 1 내부 오류, 5 다른 worker 실행 중"""

        started = time.monotonic()
        self._shutdown.clear()
        restore = self._install_signals()

        try:
            return self._loop(
                started,
                max_wait,
                report,
                serve,
            )
        finally:
            restore()

    def _loop(
        self,
        started: float,
        max_wait: float | None,
        report: Callable[[JobStatus], None],
        serve: bool,
    ) -> int:
        with self._lock() as alive:
            if alive is None:
                return 5

            owner_id = uuid4()

            while not self._shutdown.is_set():
                if not alive():
                    log.error("worker lock connection lost")
                    return 1

                try:
                    job = self._jobs.claim(owner_id)

                    if job is None:
                        pending, due = self._jobs.next_due()
                except DB_ERRORS:
                    log.exception("claim failed, retrying")
                    self._shutdown.wait(self._policy.db_retry_delay)
                    continue

                if job is not None:
                    if not self._process(job, report):
                        return 1

                    continue

                if pending == 0:
                    if not serve:
                        return 0

                    # 상주 worker는 새 접수를 기다림
                    self._shutdown.wait(self._policy.idle_poll)
                    continue

                due = max(due or 0.0, self._policy.in_flight_poll)

                if max_wait is not None and due > max_wait - (time.monotonic() - started):
                    return 6

                self._shutdown.wait(min(due, self._policy.idle_poll))

        return 0

    def _process(
        self,
        job: ClaimedJob,
        report: Callable[[JobStatus], None],
    ) -> bool:
        """job 하나, False면 종결 기록마저 실패해 프로세스를 끝내야 함"""

        lease = job.lease
        lost = threading.Event()
        self._job_stop = stop = threading.Event()

        if self._shutdown.is_set():
            stop.set()

        heartbeat = HeartbeatThread(
            self._jobs,
            lease,
            self._policy.heartbeat_interval,
            lost,
            stop,
        )
        heartbeat.start()
        outcome: JobOutcome | None = None
        db_failed = False

        try:
            outcome = self._crawl.run(job, stop)
        except DB_ERRORS:
            log.exception("database error in job %s", lease.job_id)
            db_failed = True
        except Exception:  # job 하나 처리의 최외곽
            log.exception("job %s failed", lease.job_id)
            outcome = Failed("INTERNAL_ERROR")
        finally:
            joined = heartbeat.halt(self._policy.heartbeat_interval * 2)

        if isinstance(outcome, Succeeded):
            # readyRun 기록 TX가 이미 종결, 그 뒤 heartbeat의 lost는 무시
            self._report(lease.job_id, report)
            return True

        if not joined or lost.is_set():
            # heartbeat가 늦게 lease를 늘릴 수 있어 반납하지 않음, lease 만료로 회수
            return True

        if db_failed:
            self._shutdown.wait(self._policy.db_retry_delay)
            return self._finish(
                lease,
                Waiting("RETRY", 0.0),
                report,
            )

        if outcome is None:
            if not self._shutdown.is_set():
                return True

            outcome = Waiting("SHUTDOWN", 0.0)

        return self._finish(
            lease,
            outcome,
            report,
        )

    def _finish(
        self,
        lease: Lease,
        outcome: JobOutcome,
        report: Callable[[JobStatus], None],
    ) -> bool:
        """DB 오류로 못 쓰면 lease 만료로 회수하고 계속, 그 밖의 실패는 False"""

        try:
            status = self._jobs.finish(lease, outcome)
        except DB_ERRORS:
            log.exception(
                "cannot record %s for job %s",
                outcome,
                lease.job_id,
            )
            return True
        except Exception:  # 종결 기록의 최외곽
            log.exception(
                "cannot record %s for job %s",
                outcome,
                lease.job_id,
            )
            return False

        if status is not None:
            report(status)

        return True

    def _report(
        self,
        job_id: UUID,
        report: Callable[[JobStatus], None],
    ) -> None:
        try:
            report(self._jobs.status(job_id))
        except DB_ERRORS:
            log.exception("cannot read status of job %s", job_id)

    def _install_signals(self) -> Callable[[], None]:
        """첫 신호는 현재 job을 SHUTDOWN 대기로 종결하고 끝, 두 번째는 즉시 종료 코드 1, 이전 처리기 복원 함수"""

        if threading.current_thread() is not threading.main_thread():
            return lambda: None

        def handle(
            signum: int,
            frame: FrameType | None,
        ) -> None:
            if self._shutdown.is_set():
                raise SystemExit(1)

            self._shutdown.set()
            self._job_stop.set()

        previous = {number: signal.signal(number, handle) for number in (signal.SIGTERM, signal.SIGINT)}

        def restore() -> None:
            for number, handler in previous.items():
                signal.signal(number, handler)

        return restore
