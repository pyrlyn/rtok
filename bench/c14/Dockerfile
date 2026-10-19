# syntax=docker/dockerfile:1
FROM rust:1.98.1-trixie AS rtok
WORKDIR /src
COPY . .
RUN cargo build --release --locked --bin rtok && cp target/release/rtok /rtok

FROM debian:trixie-slim
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates python3 python3-venv curl xz-utils universal-ctags && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL https://nodejs.org/dist/v24.10.0/node-v24.10.0-linux-arm64.tar.xz | tar -xJ -C /opt && ln -s /opt/node-v24.10.0-linux-arm64/bin/* /usr/local/bin/ && npm install -g @toon-format/cli@4.4.0 && ln -sf /opt/node-v24.10.0-linux-arm64/bin/toon /usr/local/bin/toon
RUN python3 -m venv /venv && /venv/bin/pip install --no-cache-dir tiktoken==0.12.0 code-review-graph==2.3.9  uv==0.9.2 && UV_TOOL_BIN_DIR=/usr/local/bin UV_TOOL_DIR=/opt/uvtools /venv/bin/uv tool install --python 3.12 aider-chat==0.86.2
COPY --from=rtok /rtok /usr/local/bin/rtok
ENV PATH=/venv/bin:$PATH
