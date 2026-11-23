"""호출자에게 contracts error code로 돌려주는 거부"""

from uuid import UUID


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
