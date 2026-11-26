"""내부 HTTP 접수와 조회: bearer 호출자 검사, 수집 job 접수, 상태, readyRun bytes"""

import hashlib
import hmac
import logging
import re
from dataclasses import dataclass
from typing import Annotated, Any
from uuid import UUID

from fastapi import Body, FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException
from starlette.middleware.body_limit import RequestBodyLimitMiddleware

from collector.app import App
from collector.contract import schema
from collector.contract.canonical_json import JsonValue
from collector.contract.collection_request import CollectionRequest
from collector.db import DB_ERRORS
from collector.errors import RequestRejected
from collector.store.object_store import STORAGE_ERRORS

JOBS = "/internal/v1/collection-jobs"
MAX_BODY_BYTES = 64 * 1024
CALLER_TOKEN = re.compile(r"(?P<caller>[a-z][a-z0-9-]*):(?P<digest>[0-9a-f]{64})")

# 거부 code의 HTTP 상태, 없는 code는 422
STATUS_CODES = {
    "VALIDATION_FAILED": 400,
    "SCHEMA_UNSUPPORTED": 400,
    "AUTH_REQUIRED": 401,
    "FORBIDDEN": 403,
    "NOT_FOUND": 404,
    "ID_INPUT_CONFLICT": 409,
    "INPUT_NOT_SEALED": 409,
    "HASH_MISMATCH": 500,
    "STORAGE_FAILED": 500,
    "INTERNAL_ERROR": 500,
}
# 같은 요청을 다시 보내면 결과가 바뀔 수 있는 거부
RETRYABLE_CODES = frozenset({"INPUT_NOT_SEALED"})

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class CallerTokens:
    """호출자 이름과 토큰 SHA-256 hex 목록, 같은 호출자 여러 줄은 교체 기간"""

    entries: tuple[tuple[str, str], ...]

    @classmethod
    def parse(cls, text: str) -> "CallerTokens":
        """caller:sha256hex 쉼표 목록, 비었거나 형식이 틀리면 ValueError"""

        items = [item.strip() for item in text.split(",") if item.strip()]

        if not items:
            raise ValueError("DOCLENS_CALLER_TOKENS is empty")

        entries = []

        for item in items:
            match = CALLER_TOKEN.fullmatch(item)

            if match is None:
                raise ValueError("DOCLENS_CALLER_TOKENS entries must be <caller>:<64 lowercase hex>")

            entries.append((match["caller"], match["digest"]))

        return cls(tuple(entries))

    def caller(self, authorization: str | None) -> str | None:
        """Bearer 토큰의 호출자, 모든 항목을 상수 시간 비교"""

        scheme, _, token = (authorization or "").partition(" ")

        if scheme.lower() != "bearer" or not token:
            return None

        digest = (
            hashlib
            .sha256(token.encode("utf-8"))
            .hexdigest()
        )
        found = None

        for name, expected in self.entries:
            if hmac.compare_digest(digest, expected) and found is None:
                found = name

        return found


def allowed(
    caller: str,
    method: str,
    path: str,
) -> bool:
    """scheduler만 수집 job 접수와 조회"""

    if caller != "scheduler":
        return False

    return (method == "POST" and path == JOBS) or (method == "GET" and path.startswith(JOBS + "/"))


def create_api(
    app: App,
    callers: CallerTokens,
) -> FastAPI:
    """route는 평범한 def라 threadpool에서 실행, 응답은 Facade TX commit 뒤"""

    api = FastAPI(
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    # chunked 본문 상한, 인증 middleware 안쪽이라 인증이 먼저
    api.add_middleware(
        RequestBodyLimitMiddleware,
        max_body_size=MAX_BODY_BYTES,
    )

    @api.middleware("http")
    async def authenticate(request: Request, call_next: Any) -> Response:
        """본문을 읽기 전에 인증, 허용 경로, 선언 크기 확인"""

        path = request.url.path

        if path.startswith("/internal/"):
            caller = callers.caller(request.headers.get("authorization"))

            if caller is None:
                return rejected(
                    RequestRejected("AUTH_REQUIRED", "a valid bearer token is required"),
                    headers={"WWW-Authenticate": "Bearer"},
                )

            if not allowed(
                caller,
                request.method,
                path,
            ):
                return rejected(RequestRejected("FORBIDDEN", f"{caller} may not call {request.method} {path}"))

            length = request.headers.get("content-length", "")

            if length.isdigit() and int(length) > MAX_BODY_BYTES:
                return too_large()

        return await call_next(request)

    @api.exception_handler(RequestRejected)
    def on_rejected(request: Request, error: RequestRejected) -> JSONResponse:
        return rejected(error)

    @api.exception_handler(RequestValidationError)
    def on_invalid(request: Request, error: RequestValidationError) -> JSONResponse:
        problems = "; ".join(
            f"{'/'.join(str(part) for part in item['loc'])}: {item['msg']}" for item in error.errors()[:3]
        )
        return rejected(RequestRejected("VALIDATION_FAILED", f"invalid request: {problems}"))

    @api.exception_handler(HTTPException)
    def on_http(request: Request, error: HTTPException) -> JSONResponse:
        if error.status_code == 413:
            return too_large()

        code = "NOT_FOUND" if error.status_code == 404 else "VALIDATION_FAILED"
        return rejected(
            RequestRejected(code, str(error.detail)),
            status_code=error.status_code,
        )

    def on_unavailable(request: Request, error: Exception) -> JSONResponse:
        log.warning(
            "%s %s failed on an unavailable dependency",
            request.method,
            request.url.path,
            exc_info=error,
        )
        return rejected(
            RequestRejected("INTERNAL_ERROR", "database or object store unavailable"),
            status_code=503,
            retryable=True,
        )

    # 일시 장애만 다시 보내도 되는 503
    for kind in DB_ERRORS + STORAGE_ERRORS:
        api.add_exception_handler(kind, on_unavailable)

    @api.exception_handler(Exception)
    def on_error(request: Request, error: Exception) -> JSONResponse:
        return rejected(RequestRejected("INTERNAL_ERROR", "internal error"))

    @api.get("/healthz")
    def health() -> dict[str, str]:
        return {"status": "UP"}

    @api.post(JOBS)
    def submit(body: Annotated[dict[str, Any], Body()]) -> JSONResponse:
        """202 새 job, 200 같은 requestId 재생"""

        accept = app.jobs.submit(CollectionRequest.parse(body))
        return reply(
            "accept",
            accept.to_json(),
            200 if accept.duplicate else 202,
        )

    @api.get(JOBS + "/{job_id}")
    def status(job_id: UUID) -> JSONResponse:
        return reply(
            "status",
            app.jobs.status(job_id).to_json(),
            200,
        )

    @api.get(JOBS + "/{job_id}/ready-run")
    def ready_run(job_id: UUID) -> Response:
        """저장한 readyRun bytes 그대로"""

        return Response(
            content=app.jobs.ready_run(job_id),
            media_type="application/json",
        )

    return api


def reply(
    definition: str,
    value: JsonValue,
    status_code: int,
) -> JSONResponse:
    """contracts 정의로 검증한 응답"""

    schema.validate(definition, value)
    return JSONResponse(
        value,
        status_code=status_code,
    )


def rejected(
    error: RequestRejected,
    status_code: int | None = None,
    retryable: bool | None = None,
    headers: dict[str, str] | None = None,
) -> JSONResponse:
    """contracts error 본문"""

    return JSONResponse(
        error.to_json(error.code in RETRYABLE_CODES if retryable is None else retryable),
        status_code=status_code or STATUS_CODES.get(error.code, 422),
        headers=headers,
    )


def too_large() -> JSONResponse:
    return rejected(
        RequestRejected("VALIDATION_FAILED", f"request body exceeds {MAX_BODY_BYTES} bytes"),
        status_code=413,
    )
