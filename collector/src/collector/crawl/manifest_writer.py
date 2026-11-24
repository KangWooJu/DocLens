"""커밋된 PG 행에서 manifest bytes를 만들고 생성 직후 contracts/v1로 검증 (순수 함수)"""

import hashlib
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from uuid import UUID

from collector.contract import schema
from collector.contract.canonical_json import JsonValue, canonical_bytes, utc_text
from collector.contract.collection_request import RequestInput
from collector.crawl.crawl_repository import BatchRow, ProjectionRow, RunRow, TargetRow
from collector.job.job_model import ClaimedJob
from collector.registry.source_registry import EXACT_VERSION, GIT_BLOB, git_commit
from collector.store.object_store import ObjectRef

# 응답 없이 실패한 문서의 versionEvidence contentHash
EMPTY_HASH = "sha256:" + (
    hashlib
    .sha256(b"")
    .hexdigest()
)


@dataclass(frozen=True)
class RunInputs:
    """요청에서 오는 값: job, trace, input 목록, source별 versionRuleId"""

    job_id: UUID
    trace_id: str
    inputs: tuple[RequestInput, ...]
    version_rules: tuple[tuple[str, str], ...]

    @classmethod
    def of(cls, job: ClaimedJob) -> "RunInputs":
        return cls(
            job_id=job.lease.job_id,
            trace_id=job.request.trace_id,
            inputs=job.request.inputs,
            version_rules=tuple((source.source_id, source.version_rule_id) for source in job.sources),
        )

    def input_of(self, source_id: str) -> RequestInput:
        return next(item for item in self.inputs if item.source_id == source_id)

    def version_rule(self, source_id: str) -> str:
        return dict(self.version_rules)[source_id]


def run_targets_key(run: RunRow) -> str:
    return f"discovery/{run.run_id}/targets.jsonl"


def discovery_key(run: RunRow) -> str:
    return f"discovery/{run.run_id}/discovery.json"


def batch_targets_key(
    run: RunRow,
    batch: BatchRow,
) -> str:
    return f"manifests/{run.run_id}/{batch.batch_id}/targets.jsonl"


def outcomes_key(
    run: RunRow,
    batch: BatchRow,
) -> str:
    return f"manifests/{run.run_id}/{batch.batch_id}/outcomes.jsonl"


def envelope_key(
    run: RunRow,
    batch: BatchRow,
) -> str:
    return f"manifests/{run.run_id}/{batch.batch_id}/envelope.json"


def ready_run_key(run: RunRow) -> str:
    return f"manifests/{run.run_id}/ready-run.json"


def targets_jsonl(
    context: RunInputs,
    rows: Sequence[TargetRow],
) -> bytes:
    return _jsonl(
        "target",
        [
            {
                "documentId": row.document_id,
                "url": row.url,
                "input": _input(context, row),
            }
            for row in rows
        ],
    )


def outcomes_jsonl(
    context: RunInputs,
    run: RunRow,
    rows: Sequence[TargetRow],
) -> bytes:
    return _jsonl(
        "documentOutcome",
        [
            _outcome(
                context,
                run,
                row,
            )
            for row in rows
        ],
    )


def discovery_json(
    context: RunInputs,
    run: RunRow,
    batches: Sequence[BatchRow],
    projections: Sequence[ProjectionRow],
) -> bytes:
    assert run.sealed_at is not None and run.targets is not None
    return _json(
        "discovery",
        {
            "schemaVersion": 1,
            "runId": str(run.run_id),
            "scopeHash": run.scope_hash,
            "sourceRegistryHash": run.source_registry_hash,
            "observationSequence": run.observation_sequence,
            "state": "SEALED",
            "startedAt": utc_text(run.started_at),
            "sealedAt": utc_text(run.sealed_at),
            "discoveryMethod": "EXPLICIT_ARTIFACT_LIST" if _git_blob(context) else "NAV_CLOSED",
            "requiredInputs": [item.to_json() for item in context.inputs if item.required],
            "optionalInputs": [item.to_json() for item in context.inputs if not item.required],
            "targetCount": sum(batch.target_count for batch in batches),
            "targetList": run.targets.to_json(),
            "batches": [
                {
                    "batchId": str(batch.batch_id),
                    "targetCount": batch.target_count,
                    "targetList": _required(batch.targets).to_json(),
                }
                for batch in batches
            ],
            "officialRevision": run.official_revision,
            "deletionEvidence": [],
            "snapshotId": str(run.snapshot_id),
            "projections": [_projection(item) for item in projections],
        },
    )


def envelope_json(
    context: RunInputs,
    run: RunRow,
    batch: BatchRow,
    rows: Sequence[TargetRow],
) -> bytes:
    """rows는 이 batch의 대상, retentionResources는 raw, targetList, jsonl, discovery 순"""

    assert batch.sealed_at is not None and batch.sealed_epoch is not None
    targets = _required(batch.targets)
    outcomes = _required(batch.outcomes)
    discovery = _required(run.discovery)
    referenced = [*_raws(rows), targets, outcomes, discovery]
    return _json(
        "manifestEnvelope",
        {
            "schemaVersion": 1,
            "runId": str(run.run_id),
            "batchId": str(batch.batch_id),
            "discovery": discovery.to_json(),
            "scopeHash": run.scope_hash,
            "targetList": targets.to_json(),
            "jsonl": outcomes.to_json(),
            "targetCount": batch.target_count,
            "successCount": batch.success_count,
            "failureCount": batch.failure_count,
            "sealedAt": utc_text(batch.sealed_at),
            "producingJobId": str(context.job_id),
            "leaseEpoch": batch.sealed_epoch,
            "retentionResources": _unique(
                [_resource(ref) for ref in referenced],
                "resourceId",
            ),
            "snapshotId": str(run.snapshot_id),
        },
    )


def ready_run_json(
    run: RunRow,
    batches: Sequence[BatchRow],
    rows: Sequence[TargetRow],
    projections: Sequence[ProjectionRow],
) -> bytes:
    """protectedObjects는 discovery, 회차 targets, batch별 targets, outcomes, envelope, raw 순"""

    assert run.ready_run_id is not None and run.readiness_sequence is not None and run.ready_created_at is not None
    discovery = _required(run.discovery)
    protected = [discovery, _required(run.targets)]

    for batch in batches:
        protected += [_required(batch.targets), _required(batch.outcomes), _required(batch.envelope)]

    protected += _raws(rows)
    return _json(
        "readyRun",
        {
            "schemaVersion": 1,
            "readyRunId": str(run.ready_run_id),
            "readinessSequence": run.readiness_sequence,
            "runId": str(run.run_id),
            "snapshotId": str(run.snapshot_id),
            "discovery": discovery.to_json(),
            "envelopes": [_required(batch.envelope).to_json() for batch in batches],
            "supplements": [],
            "protectedObjects": _unique(
                [{"objectRef": ref.to_json(), "resourceRef": _resource(ref)} for ref in protected],
                "objectRef",
            ),
            "projections": [_projection(item) for item in projections],
            "createdAt": utc_text(run.ready_created_at),
        },
    )


def evidence_version(observed: str | None) -> str | None:
    """계약 exactVersion 형식인 관측값만, 아니면 null"""

    if observed is None or EXACT_VERSION.fullmatch(observed) is None:
        return None

    return observed


def _outcome(
    context: RunInputs,
    run: RunRow,
    row: TargetRow,
) -> dict[str, JsonValue]:
    item = context.input_of(row.source_id)
    pinned = item.source_type == GIT_BLOB
    common: dict[str, JsonValue] = {
        "schemaVersion": 1,
        "eventType": "DocumentCollected",
        "runId": str(run.run_id),
        "batchId": str(row.batch_id),
        "documentId": row.document_id,
        "input": item.to_json(),
        "url": row.url,
        "traceId": context.trace_id,
        "attemptCount": row.attempt_count,
        "sourceCommit": git_commit(row.url) if pinned else None,
        "snapshotId": str(run.snapshot_id),
    }
    rule = context.version_rule(row.source_id)

    if row.state == "SUCCEEDED":
        raw = _required(row.raw)
        assert row.observed_at is not None
        observed_at = utc_text(row.observed_at)
        # commit 고정 URL은 입력 exactVersion, 같은 run의 entry 관문이 그 값을 확인함
        return {
            **common,
            "finalUrl": row.final_url,
            "observedAt": observed_at,
            "versionEvidence": {
                "level": "UNVERIFIED",
                "method": "VERSION_PINNED_URL" if pinned else "PAGE_MARKER",
                "exactVersion": item.exact_version if pinned else evidence_version(row.observed_version),
                "sourceUrl": row.url,
                "observedAt": observed_at,
                "contentHash": raw.content_hash,
                "selectorRuleId": rule,
            },
            "outcome": "SUCCESS",
            "raw": raw.to_json(),
            "contentType": row.content_type,
            "httpStatus": row.http_status,
            "etag": row.etag,
            "lastModified": row.last_modified,
            "reusedFromContentHash": None,
        }

    assert row.state == "FAILED" and row.failed_at is not None
    failed_at = utc_text(row.failed_at)
    observed_at = failed_at if row.observed_at is None else utc_text(row.observed_at)
    return {
        **common,
        "finalUrl": row.url if row.http_status is not None else None,
        "observedAt": observed_at,
        "versionEvidence": {
            "level": "UNVERIFIED",
            "method": "OBSERVATION_ONLY",
            "exactVersion": None,
            "sourceUrl": row.url,
            "observedAt": observed_at,
            "contentHash": EMPTY_HASH,
            "selectorRuleId": rule,
        },
        "outcome": "FAILED",
        "errorCode": row.error_code,
        "failedAt": failed_at,
        "needsReview": row.needs_review,
        "httpStatus": row.http_status,
    }


def _git_blob(context: RunInputs) -> bool:
    """git-blob job은 모든 input이 git-blob"""

    return any(item.source_type == GIT_BLOB for item in context.inputs)


def _input(
    context: RunInputs,
    row: TargetRow,
) -> dict[str, JsonValue]:
    return (
        context
        .input_of(row.source_id)
        .to_json()
    )


def _projection(row: ProjectionRow) -> dict[str, JsonValue]:
    return {
        "packScope": {
            "project": row.project,
            "exactVersion": row.exact_version,
            "applicability": row.applicability,
        },
        "inputScopeKey": row.input_scope_key,
        "observationSequence": row.observation_sequence,
    }


def _raws(rows: Sequence[TargetRow]) -> list[ObjectRef]:
    return [row.raw for row in rows if row.raw is not None]


def _resource(ref: ObjectRef) -> dict[str, JsonValue]:
    # 보존 원장이 없는 동안의 결정적 자원 ID
    return {
        "resourceId": "object:" + ref.content_hash,
        "resourceEpoch": 1,
    }


def _unique(
    items: Sequence[Mapping[str, JsonValue]],
    field: str,
) -> list[JsonValue]:
    """field 값이 처음 나온 항목만, 순서 유지"""

    seen: set[bytes] = set()
    unique: list[JsonValue] = []

    for item in items:
        identity = canonical_bytes(item[field])

        if identity not in seen:
            seen.add(identity)
            unique.append(dict(item))

    return unique


def _required(ref: ObjectRef | None) -> ObjectRef:
    assert ref is not None
    return ref


def _json(
    definition: str,
    value: dict[str, JsonValue],
) -> bytes:
    schema.validate(definition, value)
    return canonical_bytes(value) + b"\n"


def _jsonl(
    definition: str,
    lines: Sequence[dict[str, JsonValue]],
) -> bytes:
    for line in lines:
        schema.validate(definition, line)

    return b"".join(canonical_bytes(line) + b"\n" for line in lines)
