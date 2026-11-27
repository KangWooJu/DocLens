"""kill 지점용 저장소: 지정 key 업로드 전 또는 후에 PAUSED 한 줄을 쓰고 프로세스가 죽을 때까지 대기"""

import threading
from collections.abc import Callable
from typing import Any

from collector.store.object_store import ObjectRef, ObjectStore


class PausingObjectStore(ObjectStore):
    """key에 pause 문자열이 들어간 첫 put_once에서 멈춤"""

    def __init__(
        self,
        client: Any,
        bucket: str,
        pause: str,
        after: bool,
        notify: Callable[[str], None] = lambda line: print(line, flush=True),
    ) -> None:
        super().__init__(client, bucket)
        self._pause = pause
        self._after = after
        self._notify = notify

    def put_once(
        self,
        key: str,
        body: bytes,
    ) -> ObjectRef:
        if self._pause in key and not self._after:
            self._wait(key)

        ref = super().put_once(key, body)

        if self._pause in key and self._after:
            self._wait(key)

        return ref

    def _wait(self, key: str) -> None:
        self._notify(f"PAUSED {key}")
        (
            threading
            .Event()
            .wait()
        )
