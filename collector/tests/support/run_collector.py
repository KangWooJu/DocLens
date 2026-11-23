"""subprocess 시험 진입점: LoopbackConnector로 조립한 뒤 CLI 경로 또는 송신 반복 실행

python -m tests.support.run_collector --port P [--pause-url URL] [--budget N] registry probe SOURCE_ID
python -m tests.support.run_collector --port P send-pages COUNT
"""

import argparse
import sys
import threading
import time
from dataclasses import replace
from uuid import uuid4

from collector.__main__ import main
from collector.app import App, build_app
from collector.config import CrawlPolicy, Settings
from collector.crawl.transport_gate import Connector, Deferred, Fetch
from tests.support.loopback_connector import LoopbackConnector, PausingConnector

SCALE = 0.02


def policy(budget: int | None) -> CrawlPolicy:
    scaled = CrawlPolicy().scaled(SCALE)
    return scaled if budget is None else replace(scaled, daily_budget=budget)


def send_pages(
    app: App,
    count: int,
    crawl_policy: CrawlPolicy,
) -> int:
    """job 하나를 claim하고 승인 source의 쪽을 gate로 차례로 송신, heartbeat는 스레드로"""

    job = app.jobs.claim(uuid4())

    if job is None:
        print("NO_JOB", flush=True)
        return 1

    source = job.sources[0]
    stop = threading.Event()

    def heartbeat() -> None:
        while not stop.wait(crawl_policy.heartbeat_interval):
            app.jobs.heartbeat(job.lease)

    threading.Thread(
        target=heartbeat,
        daemon=True,
    ).start()

    try:
        for index in range(count):
            fetch = Fetch(
                url=f"{source.canonical_root}p{index:03d}.html",
                purpose="DISCOVERY",
                source_id=source.source_id,
                scope=source.scope,
            )

            while isinstance(result := app.gate.send(job.lease, fetch), Deferred):
                time.sleep(result.delay_seconds or crawl_policy.idle_poll)

            print(f"SENT {index} {result.status}", flush=True)
    finally:
        stop.set()

    return 0


def run(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--pause-url")
    parser.add_argument("--budget", type=int)
    options, rest = parser.parse_known_args(argv)
    crawl_policy = policy(options.budget)
    connector: Connector = LoopbackConnector(options.port)

    if options.pause_url:
        connector = PausingConnector(connector, options.pause_url)

    app = build_app(
        Settings.from_env(),
        connector,
        policy=crawl_policy,
    )

    try:
        if rest[0] == "send-pages":
            return send_pages(
                app,
                int(rest[1]),
                crawl_policy,
            )

        return main(rest, app=app)
    finally:
        app.close()


if __name__ == "__main__":
    sys.exit(run(sys.argv[1:]))
