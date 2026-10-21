**T36.6 dead read/graph surface: wire or delete** · — · `src/plugins/read/outline.rs`, `src/config/mod.rs`, `docs/config.md`
Do: `outline::supported()` is never called and `plugins.read.languages` is never read. Either restrict `mode = map|signatures` to the configured languages, or delete the key and the helper (a config key that changes nothing is worse than none — the D26 argument).
Check: whichever way, `just check` green and no documented key is unread (`docs/config.md` and the schema agree).
Complexity: 2/5
Status: done 2026-09-11 · Model: Composer 2.5
Evidence: `mise exec -- cargo test --lib default_toml_is_the_defaults` — 1 passed; `mise exec -- cargo test --lib validate::` — passed; `mise exec -- cargo test --test config_coverage` — passed; `mise exec -- cargo test --lib plugins::read::` — passed. Deviation: deleted `plugins.read.languages` (D26); kept `outline::supported()` because graph calls it.
