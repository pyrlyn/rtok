# Batch 3: serena needs a language server; rust-analyzer + cargo via a minimal rustup toolchain.
FROM rtok-c14b:2
ENV RUSTUP_HOME=/opt/rustup CARGO_HOME=/opt/cargo PATH=/opt/cargo/bin:$PATH
RUN curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain 1.90.0 --component rust-analyzer,rust-src --no-modify-path \
 && chmod -R a+rwX /opt/rustup /opt/cargo && rust-analyzer --version && cargo --version
