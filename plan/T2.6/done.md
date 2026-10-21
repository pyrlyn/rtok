**T2.6 `guard` deny duplicate Read/Bash** · T2.1, T3.1 · `src/plugins/guard/mod.rs`
Do: PreToolUse(Read) and PreToolUse(Bash): if the same path or command already ran in this session within `plugins.guard.window_turns` (default 8) and an archive id exists, `Deny` with a reason that names `rtok expand <id>`. Record `Measurement`. Never deny when there is no prior archive. Config keys in the same commit (D12).
Check: two identical Read fixtures in one session → second is Deny naming the archive id; a different path → allow; `stats --plugin guard` has a row.
Status: done 2026-09-02 · Check: `cargo test --lib plugins::guard` — first Read allow, PostToolUse archives, second identical Read Deny names `rtok expand <id>`, different path allow, `measurement_count("guard") >= 1`. `make check` green. Deviation: also `store/mod.rs` (read_cache helpers) and default `window_turns` 5→8 in config/docs.

Gate P2 · Status: done 2026-09-03 · Check: `rtok setup claude` → 7 additions; doctor `hooks 88`; second setup `no changes`; 7 `rtok hook` commands kept beside the original 81; settings JSON valid; proxy 8788→8787 unchanged.
