"""일일 예산: 한도에 닿으면 송신 없이 Deferred(BUDGET, None), 예약 TX rollback"""

from dataclasses import replace
from pathlib import Path

import psycopg

from collector.config import Settings
from collector.crawl.transport_gate import Deferred
from tests.conftest import SCALED, claim, connected_app, insert_run, page
from tests.support.fixture_server import serving
from tests.support.fixture_site import site
from tests.support.loopback_connector import LoopbackConnector


def test_sixth_send_over_a_budget_of_five_is_deferred(
    settings: Settings,
    pg: psycopg.Connection,
    tmp_path: Path,
) -> None:
    with (
        serving(site(pages=10)) as server,
        connected_app(settings, LoopbackConnector(server.port), replace(SCALED, daily_budget=5)) as app,
    ):
        job = claim(app, settings, tmp_path)
        run_id, _, (document_id, _) = insert_run(pg, job.lease.job_id)

        # robots 1 + 쪽 4 = 송신 5
        for index in range(4):
            assert app.gate.send(job.lease, page(job, f"p{index:03d}.html")).status == 200

        deferred = app.gate.send(
            job.lease,
            page(
                job,
                "p004.html",
                purpose="DOCUMENT",
                run_id=run_id,
                document_id=document_id,
            ),
        )

    assert deferred == Deferred("BUDGET", None)
    assert server.total() == 5
    assert (
        pg
        .execute("SELECT count(*) FROM collector.http_sends")
        .fetchone()
    ) == (5,)
    assert (
        pg
        .execute(
            "SELECT state, attempt_count FROM collector.collection_targets WHERE document_id = %s",
            (document_id,),
        )
        .fetchone()
    ) == ("PENDING", 0)
