"""claim한 job과 job 종결 결과, crawl과 worker가 함께 쓰는 값"""

from dataclasses import dataclass
from typing import Literal

from collector.contract.collection_request import CollectionRequest
from collector.job.job_repository import Lease
from collector.registry.source_registry import Source
from collector.store.object_store import ObjectRef

type WaitReason = Literal["BUDGET", "HOST", "RETRY", "SHUTDOWN"]


@dataclass(frozen=True)
class ClaimedJob:
    lease: Lease
    request: CollectionRequest
    sources: tuple[Source, ...]

    def source(self, source_id: str) -> Source:
        return next(source for source in self.sources if source.source_id == source_id)


@dataclass(frozen=True)
class Succeeded:
    result: ObjectRef


@dataclass(frozen=True)
class Waiting:
    """None이면 다음 KST 00:00, next_attempt_at은 PG 시계로 계산"""

    reason: WaitReason
    delay_seconds: float | None


@dataclass(frozen=True)
class Review:
    code: str


@dataclass(frozen=True)
class Failed:
    code: str


@dataclass(frozen=True)
class Cancelled:
    pass


type JobOutcome = Succeeded | Waiting | Review | Failed | Cancelled
