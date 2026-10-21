**T23.5 documentation someone can build against** · T23.1 · `crates/rtok-plugin-sdk/README.md`, `crates/rtok-plugin-sdk/examples/`, `docs/plugin-authoring.md`
Do: crate-level docs that say what a plugin is, the required methods, the lifecycle of each event,
and the three rules that never bend for a plugin either (fail open, lossless, a saving that is not
a `Measurement` row does not exist). Every public item documented, with an example that compiles as
a doctest. `examples/` holds one complete plugin — the smallest thing that records a
`Measurement`. `docs/plugin-authoring.md` is rewritten against the crate and stops describing the
in-tree path as the normal one.
Complexity: 2/5
Status: done 2026-09-09
Model: Claude Opus 5 (anthropic/claude-opus-5)
Check result: `cargo test -p rtok-plugin-sdk --doc` green — 4 doctests, one of them the
`compile_fail` proof from T23.2 and one a whole `impl Plugin` that records a `Measurement` and
asserts the row landed. `cargo doc -p rtok-plugin-sdk --no-deps` has no warning (the one it did
have, an unresolved `[`Ctx`]` link in the new module, is fixed). `cargo run -p rtok-plugin-sdk
--example shrink` prints the rewrite, the recorded measurement and the archived original read back
by its id. `just check` green apart from the known `graph::watch` flake, which passes on its own.
The crate docs gained the event table (which method the host asks for, when, and what it may do)
and `docs/plugin-authoring.md` is rewritten: the trait comes from the crate, the in-tree wiring is
§2 rather than the whole document, and §3 is what a third party writes.
Deviation: the Check's "the example crate builds against the published version number" is not
possible before T23.6 — nothing is on crates.io yet. The example is `crates/rtok-plugin-sdk/
examples/shrink.rs`, built against the in-tree `0.0.1`, and the published-surface proof is
T23.6's `cargo publish -p rtok-plugin-sdk --dry-run`.
Deviation: a new public module, `testing`, with `MemoryHost` — a host that keeps what a plugin
records and archives and answers every other capability empty. The Check asks for an example that
records a `Measurement`, and `Ctx::new` takes `&dyn Capabilities`: without it, the example (and
any out-of-tree plugin's first unit test) would have to hand-write about thirty trait methods
before it could assert anything. It replaced the `NoConfig` fake the host tests were using.
Note: the `README.md` the crate now carries is the crates.io front page; `readme = "README.md"`
went into the manifest with it.
