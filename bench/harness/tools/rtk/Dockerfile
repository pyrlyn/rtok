FROM rtokbench/base
ARG RTK_VERSION=v1.0.1
RUN set -eux; arch=$(uname -m); \
    base=https://github.com/rtk-ai/rtk/releases/download/${RTK_VERSION}; \
    f=rtk-${arch}-unknown-linux-gnu.tar.gz; \
    curl -fsSLO "$base/$f"; curl -fsSLO "$base/checksums.txt"; \
    grep " $f\$" checksums.txt | sha256sum -c -; \
    tar -xzf "$f" -C /usr/local/bin rtk; rm -f "$f" checksums.txt; rtk --version
