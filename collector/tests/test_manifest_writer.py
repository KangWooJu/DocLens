"""PG 행 묶음에서 만든 manifest bytes: 결정성, contracts 검증, root100과 같은 모양"""

import json
from dataclasses import replace
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID

import pytest

from collector.contract.canonical_json import canonical_bytes
from collector.contract.collection_request import RequestInput
from collector.contract.schema import SchemaViolation
from collector.crawl import manifest_writer as writer
from collector.crawl.crawl_repository import BatchRow, ProjectionRow, RunRow, TargetRow
from collector.store.object_store import ObjectRef

ROOT100 = Path(__file__).resolve().parents[2] / "contracts" / "v1" / "examples" / "root100"
RUN_ID = UUID("11111111-1111-4111-8111-111111111111")
BATCH_ID = UUID("22222222-2222-4222-8222-222222222222")
SEALED = datetime(2026, 11, 24, 1, 2, 3, tzinfo=UTC)
BASE = "https://docs.spring.io/spring-boot/4.1/reference/"


def ref(
    key: str,
    digit: str,
) -> ObjectRef:
    return ObjectRef(
        object_key=key,
        content_hash="sha256:" + digit * 64,
        size_bytes=10,
    )


CONTEXT = writer.RunInputs(
    job_id=UUID("33333333-3333-4333-8333-333333333333"),
    trace_id="44444444-4444-4444-8444-444444444444",
    inputs=(
        RequestInput(
            source_id="spring-boot-4.1.3-reference",
            project="spring-boot",
            exact_version="4.1.3",
            source_type="html",
            document_type="REFERENCE",
            required=True,
            applicability="EXACT_RELEASE",
        ),
    ),
    version_rules=(("spring-boot-4.1.3-reference", "antora-meta-version-v1"),),
)
RUN = RunRow(
    run_id=RUN_ID,
    job_id=CONTEXT.job_id,
    snapshot_id=UUID("55555555-5555-4555-8555-555555555555"),
    scope_hash="sha256:" + "a" * 64,
    source_registry_hash="sha256:" + "b" * 64,
    observation_sequence=7,
    started_at=SEALED,
    discovery_state="SEALED",
    official_revision="v4.1.3",
    sealed_at=SEALED,
    targets=ref(f"discovery/{RUN_ID}/targets.jsonl", "1"),
    discovery=ref(f"discovery/{RUN_ID}/discovery.json", "2"),
    drift_detected=False,
    ready_run_id=UUID("66666666-6666-4666-8666-666666666666"),
    readiness_sequence=3,
    ready_created_at=SEALED,
    ready=None,
)
BATCH = BatchRow(
    batch_id=BATCH_ID,
    ordinal=0,
    project="spring-boot",
    exact_version="4.1.3",
    document_type="REFERENCE",
    target_count=3,
    targets=ref(f"manifests/{RUN_ID}/{BATCH_ID}/targets.jsonl", "3"),
    sealed_at=SEALED,
    sealed_epoch=1,
    success_count=2,
    failure_count=1,
    outcomes=ref(f"manifests/{RUN_ID}/{BATCH_ID}/outcomes.jsonl", "4"),
    envelope=ref(f"manifests/{RUN_ID}/{BATCH_ID}/envelope.json", "5"),
)
SUCCEEDED = TargetRow(
    document_id="sha256:" + "c" * 64,
    batch_id=BATCH_ID,
    source_id="spring-boot-4.1.3-reference",
    url=BASE + "index.html",
    state="SUCCEEDED",
    attempt_count=1,
    raw=ref("raw/spring-boot/4.1.3/" + "6" * 64 + ".html", "6"),
    http_status=200,
    final_url=BASE + "index.html",
    content_type="text/html",
    last_modified=None,
    etag=None,
    observed_version="4.1.3",
    observed_at=SEALED,
    error_code=None,
    needs_review=None,
    failed_at=None,
)
# 계약 패턴이 아닌 버전 표지
NON_SEMVER = replace(
    SUCCEEDED,
    document_id="sha256:" + "d" * 64,
    url=BASE + "p000.html",
    final_url=BASE + "p000.html",
    raw=ref("raw/spring-boot/4.1.3/" + "7" * 64 + ".html", "7"),
    observed_version="4.1",
)
# 응답 없이 실패
UNANSWERED = replace(
    SUCCEEDED,
    document_id="sha256:" + "e" * 64,
    url=BASE + "p001.html",
    state="FAILED",
    attempt_count=3,
    raw=None,
    http_status=None,
    final_url=None,
    content_type=None,
    observed_version=None,
    observed_at=None,
    error_code="HTTP_RETRY_EXHAUSTED",
    needs_review=True,
    failed_at=datetime(2026, 11, 24, 1, 5, 0, tzinfo=UTC),
)
ROWS = (SUCCEEDED, NON_SEMVER, UNANSWERED)
PROJECTIONS = (
    ProjectionRow(
        project="spring-boot",
        exact_version="4.1.3",
        applicability="EXACT_RELEASE",
        input_scope_key="sha256:" + "f" * 64,
        observation_sequence=7,
    ),
)


def build() -> dict[str, bytes]:
    return {
        "targets": writer.targets_jsonl(CONTEXT, ROWS),
        "outcomes": writer.outcomes_jsonl(
            CONTEXT,
            RUN,
            ROWS,
        ),
        "discovery": writer.discovery_json(
            CONTEXT,
            RUN,
            (BATCH,),
            PROJECTIONS,
        ),
        "envelope": writer.envelope_json(
            CONTEXT,
            RUN,
            BATCH,
            ROWS,
        ),
        "ready": writer.ready_run_json(
            RUN,
            (BATCH,),
            ROWS,
            PROJECTIONS,
        ),
    }


def lines(body: bytes) -> list[dict[str, object]]:
    return [json.loads(line) for line in body.splitlines()]


def example(name: str) -> object:
    return json.loads((ROOT100 / name).read_text(encoding="utf-8"))


def example_lines(name: str) -> list[dict[str, object]]:
    return [json.loads(line) for line in (ROOT100 / name).read_text(encoding="utf-8").splitlines()]


def test_same_rows_give_the_same_bytes() -> None:
    first, second = build(), build()

    assert first == second
    assert all(body.endswith(b"\n") for body in first.values())
    assert all(canonical_bytes(json.loads(first[name])) + b"\n" == first[name] for name in ("discovery", "ready"))


def test_shape_matches_root100() -> None:
    built = build()
    outcomes = lines(built["outcomes"])
    expected_outcomes = example_lines("outcomes.jsonl")
    expected_success = next(line for line in expected_outcomes if line["outcome"] == "SUCCESS")
    expected_failed = next(line for line in expected_outcomes if line["outcome"] == "FAILED")

    assert json.loads(built["discovery"]).keys() == example("discovery.json").keys()
    assert json.loads(built["envelope"]).keys() == example("envelope.json").keys()
    assert json.loads(built["ready"]).keys() == example("ready-run.json").keys()
    assert lines(built["targets"])[0].keys() == example_lines("targets.jsonl")[0].keys()
    assert outcomes[0].keys() == expected_success.keys()
    assert outcomes[2].keys() == expected_failed.keys()


def test_retention_resources_and_protected_objects() -> None:
    built = build()
    envelope = json.loads(built["envelope"])
    ready = json.loads(built["ready"])

    def resource(item: ObjectRef) -> str:
        return "object:" + item.content_hash

    assert [item["resourceId"] for item in envelope["retentionResources"]] == [
        resource(SUCCEEDED.raw),
        resource(NON_SEMVER.raw),
        resource(BATCH.targets),
        resource(BATCH.outcomes),
        resource(RUN.discovery),
    ]
    assert [item["objectRef"]["objectKey"] for item in ready["protectedObjects"]] == [
        RUN.discovery.object_key,
        RUN.targets.object_key,
        BATCH.targets.object_key,
        BATCH.outcomes.object_key,
        BATCH.envelope.object_key,
        SUCCEEDED.raw.object_key,
        NON_SEMVER.raw.object_key,
    ]
    assert all(
        item["resourceRef"] == {"resourceId": "object:" + item["objectRef"]["contentHash"], "resourceEpoch": 1}
        for item in ready["protectedObjects"]
    )


def test_outcome_evidence() -> None:
    success, non_semver, unanswered = lines(build()["outcomes"])

    assert success["versionEvidence"]["exactVersion"] == "4.1.3"
    assert success["versionEvidence"]["method"] == "PAGE_MARKER"
    assert non_semver["versionEvidence"]["exactVersion"] is None
    assert unanswered["observedAt"] == unanswered["failedAt"] == "2026-11-24T01:05:00Z"
    assert unanswered["finalUrl"] is None and unanswered["httpStatus"] is None
    assert unanswered["versionEvidence"]["contentHash"] == writer.EMPTY_HASH
    assert unanswered["versionEvidence"]["method"] == "OBSERVATION_ONLY"


def test_rows_outside_the_contract_are_rejected_at_generation() -> None:
    broken = replace(UNANSWERED, error_code="NOT_A_CODE")

    with pytest.raises(SchemaViolation):
        writer.outcomes_jsonl(
            CONTEXT,
            RUN,
            (broken,),
        )


COMMIT = "0123456789abcdef0123456789abcdef01234567"
RAW_ROOT = f"https://raw.githubusercontent.com/spring-projects/spring-boot/{COMMIT}/"
GIT_CONTEXT = writer.RunInputs(
    job_id=CONTEXT.job_id,
    trace_id=CONTEXT.trace_id,
    inputs=(
        RequestInput(
            source_id="spring-boot-4.1.2-reference",
            project="spring-boot",
            exact_version="4.1.2",
            source_type="git-blob",
            document_type="REFERENCE",
            required=True,
            applicability="EXACT_RELEASE",
        ),
    ),
    version_rules=(("spring-boot-4.1.2-reference", "gradle-properties-version-v1"),),
)
# 쪽 원본에는 버전 표지가 없음
GIT_SUCCEEDED = replace(
    SUCCEEDED,
    source_id="spring-boot-4.1.2-reference",
    url=RAW_ROOT + "docs/pages/index.adoc",
    final_url=RAW_ROOT + "docs/pages/index.adoc",
    raw=ref("raw/spring-boot/4.1.2/" + "6" * 64 + ".txt", "6"),
    content_type="text/plain",
    observed_version=None,
)
GIT_FAILED = replace(
    UNANSWERED,
    source_id="spring-boot-4.1.2-reference",
    url=RAW_ROOT + "docs/pages/missing.adoc",
)


def test_git_blob_outcomes_pin_the_commit_and_the_input_version() -> None:
    success, failed = lines(
        writer.outcomes_jsonl(
            GIT_CONTEXT,
            RUN,
            (GIT_SUCCEEDED, GIT_FAILED),
        ),
    )

    assert success["sourceCommit"] == failed["sourceCommit"] == COMMIT
    assert success["contentType"] == "text/plain"
    assert success["versionEvidence"] == {
        "level": "UNVERIFIED",
        "method": "VERSION_PINNED_URL",
        "exactVersion": "4.1.2",
        "sourceUrl": GIT_SUCCEEDED.url,
        "observedAt": "2026-11-24T01:02:03Z",
        "contentHash": GIT_SUCCEEDED.raw.content_hash,
        "selectorRuleId": "gradle-properties-version-v1",
    }
    assert failed["versionEvidence"]["method"] == "OBSERVATION_ONLY"
    assert failed["versionEvidence"]["exactVersion"] is None


def test_git_blob_discovery_is_an_explicit_list_at_the_commit() -> None:
    discovery = json.loads(
        writer.discovery_json(
            GIT_CONTEXT,
            replace(RUN, official_revision=COMMIT),
            (BATCH,),
            PROJECTIONS,
        ),
    )
    html = json.loads(build()["discovery"])

    assert (discovery["discoveryMethod"], discovery["officialRevision"]) == ("EXPLICIT_ARTIFACT_LIST", COMMIT)
    assert html["discoveryMethod"] == "NAV_CLOSED"
