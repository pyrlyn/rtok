**T17.2 pin cargo-cache** · T17.1 · `mise.toml`
Do: `[tools]` gains `"cargo:cargo-cache"` at the version this machine already resolves. `just cache` and `just cache-autoclean` call it through `mise exec --`, the form every pinned tool in the justfile uses, but the tool was only ever installed globally — a fresh clone cannot run either recipe. cargo-dist stays unpinned on purpose; the justfile says why.
Check: the tool resolves from the repository's own config, not a global install; `just cache` prints the cargo-home summary and `just cache-autoclean` is exercised with `--dry-run` so nothing is deleted from a cargo home other sessions share; `just check` green.
Status: done 2026-09-05
Model: Opus 5
Check result: before the change `mise ls` listed `cargo:cargo-cache 0.8.3` as a global install and
`mise ls --current` did not list it at all; after, it resolves from `~/GitHub/rtok/mise.toml`.
`just cache` prints the summary — cargo home 2.17 GB, of which 1.49 GB is 1 848 crate source
checkouts and 284 MB is 2 091 crate archives — and `cargo-cache --autoclean --dry-run` completes
without removing anything, which is how it was exercised: this cargo home is shared with other
sessions, so a real autoclean was not run. `just check` green (12 test binaries).
Worth knowing: adding a tool to `[tools]` changes `PATH`, and cargo fingerprints build scripts
against it, so the next `just check` rebuilt every crate with a build script — including lbug's
C++, about nine minutes. One-time, but it is why a one-line toolchain edit is not a free check.
