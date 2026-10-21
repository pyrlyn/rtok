**T28.2 implement optional compress / memory extractor** · T28.1 · plugin sources under `src/plugins/`
Do: implement the chosen path behind the flag. Record a `Measurement`. When the source is not regenerable, `expand` still recovers the original. Default-off path is byte-identical to today's lossless behaviour.
Check: with the flag off, proxy/archive/inject bytes match pre-P28; with the flag on, a fixture compresses and `expand` recovers where required.
Complexity: 4/5
Status: done 2026-09-12 · Model: Composer 2.5
Check result: `plugins::compress` 4 passed (default-off byte-identical after archive; fixture shrinks + `get_archive` recovers original; deterministic). `registry_matches_catalogue`, `every_plugin_has_a_plan`, `cargo fmt --check`, `cargo clippy --lib -D warnings` green. Deviation: 5 files (added `tests/plugin_plans.rs` SURVEYS drop + `Cargo.toml` feature); `compress/mod.rs` 248 LOC (extractive ranker + wire tests). Memory observation extractor deferred (optional per PLAN). `just check` hit unrelated flakes (`agents`, `otel`, `rtok-agent-sdk` backup) on this machine — re-run on landing.
