"""조립(composition root), 프로세스마다 한 번"""

from collections.abc import Callable
from dataclasses import dataclass

from collector.config import CrawlPolicy, Settings
from collector.db import open_pool
from collector.job.job_facade import JobFacade
from collector.job.job_repository import JobRepository
from collector.job.job_service import JobService
from collector.registry.registry_facade import RegistryFacade
from collector.registry.registry_repository import RegistryRepository
from collector.registry.registry_service import RegistryService


@dataclass(frozen=True)
class App:
    registry: RegistryFacade
    jobs: JobFacade
    close: Callable[[], None]


def build_app(
    settings: Settings,
    policy: CrawlPolicy | None = None,
) -> App:
    policy = policy or CrawlPolicy()
    pool = open_pool(settings, policy)
    registry_repository = RegistryRepository()
    return App(
        registry=RegistryFacade(
            pool,
            RegistryService(registry_repository),
        ),
        jobs=JobFacade(
            pool,
            JobService(
                JobRepository(),
                registry_repository,
                policy,
            ),
        ),
        close=pool.close,
    )
