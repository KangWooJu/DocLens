"""조립(composition root), 프로세스마다 한 번"""

from collections.abc import Callable
from dataclasses import dataclass

from collector.config import CrawlPolicy, Settings
from collector.crawl.transport_gate import Connector, TransportGate
from collector.db import open_pool
from collector.job.job_facade import JobFacade
from collector.job.job_repository import JobRepository
from collector.job.job_service import JobService
from collector.registry.registry_facade import RegistryFacade
from collector.registry.registry_repository import RegistryRepository
from collector.registry.registry_service import RegistryService
from collector.store.object_store import ObjectStore, s3_client


@dataclass(frozen=True)
class App:
    registry: RegistryFacade
    jobs: JobFacade
    gate: TransportGate
    close: Callable[[], None]


def build_app(
    settings: Settings,
    connector: Connector,
    store: ObjectStore | None = None,
    policy: CrawlPolicy | None = None,
) -> App:
    """store가 None이면 settings로 만든 S3 저장소"""

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
    return App(
        registry=RegistryFacade(
            pool,
            RegistryService(
                registry_repository,
                gate,
                store,
            ),
        ),
        jobs=JobFacade(
            pool,
            JobService(
                JobRepository(),
                registry_repository,
                policy,
            ),
        ),
        gate=gate,
        close=pool.close,
    )
