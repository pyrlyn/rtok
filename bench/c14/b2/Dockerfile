# Batch 2 competitors on top of the batch-1 image (rtok 12ebeb97, python, node 24, tiktoken).
FROM rtok-c14:12ebeb9
ARG GH=https://github.com
RUN mkdir -p /opt/c && cd /opt/c \
 && curl -fsSL $GH/ojuschugh1/sqz/releases/download/v2.1.1/sqz-v2.1.1-aarch64-unknown-linux-musl.tar.gz | tar xz \
 && curl -fsSL $GH/yvgude/lean-ctx/releases/download/v3.11.4/lean-ctx-aarch64-unknown-linux-musl.tar.gz | tar xz \
 && curl -fsSL $GH/Gentleman-Programming/engram/releases/download/v3.4.0/engram_3.4.0_linux_arm64.tar.gz | tar xz \
 && curl -fsSL $GH/angelnicolasc/graymatter/releases/download/v0.20.1/graymatter_0.20.1_linux_arm64.tar.gz | tar xz \
 && curl -fsSL $GH/smart-mcp-proxy/mcpproxy-go/releases/download/v0.70.0/mcpproxy-0.70.0-linux-arm64.tar.gz | tar xz \
 && curl -fsSL $GH/colbymchenry/codegraph/releases/download/v1.6.2/codegraph-linux-arm64.tar.gz | tar xz \
 && curl -fsSL -o cbm.mcpb $GH/DeusData/codebase-memory-mcp/releases/download/v0.11.0/codebase-memory-mcp-linux-arm64-portable.mcpb \
 && git clone -q --depth 1 -b v5.13.44 $GH/alexgreensh/token-optimizer /opt/token-optimizer \
 && git clone -q --depth 1 -b v4.0.1 $GH/JuliusBrussee/caveman /opt/caveman \
 && find /opt/c -maxdepth 3 -type f -perm -u+x | sort
RUN UV_TOOL_BIN_DIR=/usr/local/bin UV_TOOL_DIR=/opt/uvtools /venv/bin/uv tool install --python 3.12 serena-agent==2.0.0
