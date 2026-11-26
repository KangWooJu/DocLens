# collector-api와 collector-worker가 함께 쓰는 이미지
# 저장소 루트의 contracts는 추가 build context로 받음
# docker build --build-context contracts=contracts -t doclens-collector:local collector
FROM python:3.13-slim

COPY --from=ghcr.io/astral-sh/uv:0.13.5 /uv /bin/uv

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    PYTHONUNBUFFERED=1 \
    PATH=/app/collector/.venv/bin:$PATH

WORKDIR /app/collector

# 의존성 층을 소스 층과 분리
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project

COPY src ./src
RUN uv sync --locked --no-dev

# schema.py가 읽는 저장소 루트 기준 contracts/v1 위치
COPY --from=contracts v1 /app/contracts/v1

RUN useradd --system --uid 10001 collector
USER collector

EXPOSE 8000

# exec form이라 python이 PID 1로 SIGTERM을 직접 받음
CMD ["python", "-m", "collector", "api", "--host", "0.0.0.0", "--port", "8000"]
