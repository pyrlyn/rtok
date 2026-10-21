Cloud review finding (2026-10-08): `guard::check_json` in `src/plugins/guard/mod.rs` has no callers; the CLI (`rtok guard check`) calls `guard::check`. Removed it; its imports were all shared with the rest of the module.

Check: grep for `check_json` over `src`, `tests`, `crates`, `docs`, `benches`, `examples` found only the definition; `just fmt-check`, `just lint`, `cargo nextest run -p rtok -E 'test(guard) | test(plan)'`.
