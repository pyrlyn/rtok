# Bench image for rtok vs token-optimizer. Build context = repository root.
ARG BASE=rust:1.98.1-trixie
FROM ${BASE}
ARG BASE
LABEL bench.base="${BASE}"
ARG FIXTURE_REPO=https://github.com/BurntSushi/ripgrep
ARG FIXTURE_REF=14.1.1

RUN apt-get update && apt-get install -y --no-install-recommends \
      hyperfine git python3 python3-venv ca-certificates curl build-essential \
    && rm -rf /var/lib/apt/lists/*
RUN python3 -m venv /opt/tok && /opt/tok/bin/pip install --no-cache-dir tiktoken==0.12.0 \
    && /opt/tok/bin/python -c "import tiktoken; tiktoken.get_encoding('o200k_base')"

RUN printf '%s\n' '#!/bin/sh' 'echo token-optimizer-stub: install release binary to measure >&2' 'exit 1' > /usr/local/bin/token-optimizer && chmod +x /usr/local/bin/token-optimizer

WORKDIR /src/rtok
COPY . .
ENV CARGO_INCREMENTAL=0 RUSTFLAGS=""
RUN cargo build --release --locked --bin rtok && install target/release/rtok /usr/local/bin/rtok \
    && rm -rf target && rtok --version

RUN git clone --quiet "$FIXTURE_REPO" /fixture && cd /fixture && git checkout --quiet "$FIXTURE_REF" \
    && cargo fetch --locked && cargo build --locked -j4 2>/dev/null || true

COPY bench/competitors/_lib /bench/_lib
COPY bench/competitors/token-optimizer/bench.py /bench/bench.py
ENV PATH="/opt/tok/bin:${PATH}"
ENV COMPETITOR=token-optimizer
ENTRYPOINT ["python3", "/bench/bench.py"]
