FROM python:3.11.14-slim-bookworm@sha256:65a93d69fa75478d554f4ad27c85c1e69fa184956261b4301ebaf6dbb0a3543d AS base
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 UV_LINK_MODE=copy
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates libgomp1 && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir uv==0.8.22
WORKDIR /opt/momo/server
COPY server/pyproject.toml server/uv.lock ./
RUN uv sync --frozen --no-dev
COPY server/ ./
COPY contracts/ /opt/momo/contracts/
ENV PATH="/opt/momo/server/.venv/bin:$PATH"
RUN useradd --uid 10001 --create-home momo && mkdir -p /data/objects /data/models && chown -R momo:momo /data /opt/momo

FROM base AS api
USER momo
EXPOSE 8765
CMD ["uvicorn", "momo.api:app", "--host", "0.0.0.0", "--port", "8765"]

FROM base AS worker
RUN uv sync --frozen --no-dev --extra inference --extra cuda
ENV LD_LIBRARY_PATH="/opt/momo/server/.venv/lib/python3.11/site-packages/nvidia/cublas/lib:/opt/momo/server/.venv/lib/python3.11/site-packages/nvidia/cudnn/lib:/opt/momo/server/.venv/lib/python3.11/site-packages/nvidia/cuda_runtime/lib:/opt/momo/server/.venv/lib/python3.11/site-packages/nvidia/cufft/lib:/opt/momo/server/.venv/lib/python3.11/site-packages/nvidia/nvjitlink/lib"
ENV TF_CPP_MIN_LOG_LEVEL=2
USER momo
CMD ["celery", "-A", "momo.worker:celery", "worker", "--pool=solo", "--concurrency=1", "--loglevel=WARNING"]
