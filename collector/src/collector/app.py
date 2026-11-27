"""조립(composition root), 프로세스마다 한 번"""

from collections.abc import Callable
from dataclasses import dataclass

from collector.config import CrawlPolicy, Settings
from collector.crawl.crawl_facade import CrawlFacade
from collector.crawl.crawl_repository import CrawlRepository
from collector.crawl.discovery_service import DiscoveryService
from collector.crawl.fetch_service import FetchService
from collector.crawl.seal_service import SealService
from collector.crawl.transport_gate import Connector, TransportGate
from collector.db import open_pool, worker_lock
from collector.job.job_facade import JobFacade
from collector.job.job_repository import JobRepository
from collector.job.job_service import JobService
from collector.registry.registry_facade import RegistryFacade
from collector.registry.registry_repository import RegistryRepository
from collector.registry.registry_service import RegistryService
from collector.store.object_store import ObjectStore, s3_client
from collector.worker import Worker, WorkerLock


@dataclass(frozen=True)
class App:
    registry: RegistryFacade
    jobs: JobFacade
    gate: TransportGate
    crawl: CrawlFacade
    worker: Worker
    close: Callable[[], None]


def build_app(
    settings: Settings,
    connector: Connector,
    store: ObjectStore | None = None,
    policy: CrawlPolicy | None = None,
    lock: WorkerLock | None = None,
) -> App:
    """store가 None이면 settings로 만든 S3 저장소, lock이 None이면 PG advisory lock"""

    policy = policy or CrawlPolicy()
    store = store or ObjectStore(
        s3_client(
            settings.s3_endpoint,
            settings.s3_access_key,
            settings.s3_secret_key,
        ),
        settings.s3_bucket,
    )
    pool = open_pool(settings, policy)
    gate = TransportGate(
        pool,
        policy,
        connector,
        settings.contact,
    )
    registry_repository = RegistryRepository()
    job_repository = JobRepository()
    crawl_repository = CrawlRepository()
    jobs = JobFacade(
        pool,
        JobService(
            job_repository,
            registry_repository,
            policy,
        ),
        store,
    )
    crawl = CrawlFacade(
        pool,
        DiscoveryService(
            gate,
            crawl_repository,
            policy,
        ),
        FetchService(
            gate,
            store,
            crawl_repository,
            job_repository,
            policy,
        ),
        SealService(
            store,
            crawl_repository,
            job_repository,
        ),
        job_repository,
        policy,
    )
    return App(
        registry=RegistryFacade(
            pool,
            RegistryService(
                registry_repository,
                gate,
                store,
            ),
        ),
        jobs=jobs,
        gate=gate,
        crawl=crawl,
        worker=Worker(
            jobs,
            crawl,
            policy,
            lock or (lambda: worker_lock(settings, policy)),
        ),
        close=pool.close,
    )
