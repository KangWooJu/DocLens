"""호출자에게 contracts error code로 돌려주는 거부"""

from uuid import UUID, uuid4

from collector.contract.canonical_json import JsonValue


class RequestRejected(Exception):
    def __init__(
        self,
        code: str,
        message: str,
        request_id: UUID | None = None,
        trace_id: UUID | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.request_id = request_id
        self.trace_id = trace_id

    def to_json(self, retryable: bool = False) -> dict[str, JsonValue]:
        """contracts error, traceId가 없으면 새 UUID"""

        return {
            "schemaVersion": 1,
            "code": self.code,
            "message": self.message[:500] or self.code,
            "requestId": None if self.request_id is None else str(self.request_id),
            "retryable": retryable,
            "traceId": str(self.trace_id or uuid4()),
        }
