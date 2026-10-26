# PORTING status — rtok (Rust) → rtok-kotlin

Status legend: ✅ ported + tests green · 🟡 partial · ⬜ not started · ❌ excluded / not portable.
Plan and per-phase checklist: [port-plan.md](port-plan.md).

| Rust source | Kotlin location | Status | Tests (Rust → Kotlin) |
|---|---|---|---|
| crates/rtok-plugin-sdk `host.rs::Class`, `NoteHit` | `:rtok-plugin-sdk` `rtok.plugin.sdk` | 🟡 (types only) | – |
| crates/rtok-plugin-sdk (rest) | `:rtok-plugin-sdk` | ⬜ | |
| crates/rtok-config `estimator.rs` | `:rtok-config` `rtok.config.Estimator` | ✅ | – |
| crates/rtok-config `root.rs` | `rtok-config/nativeMain/Root.kt` | ✅ | 7 → 7 |
| crates/rtok-config `since.rs` | `rtok-config/commonMain/Since.kt` | ✅ | 0 → 5 (new) |
| crates/rtok-config `layers.rs`, `lib.rs`, `validate.rs`, `error.rs` | `:rtok-config` | ⬜ | |
| crates/rtok-log | `rtok-log/nativeMain/Log.kt` | ✅ | 12 → 12 |
| crates/rtok-hook | `:rtok-hook` | ⬜ | |
| crates/rtok-store (SQLite ledger, 40 files) | `:rtok-store` | ⬜ | |
| crates/rtok-sys | `:rtok-sys` | ⬜ (module currently hosts `rtok.sys.Fs`, the fs-kit replacement) | |
| fs-kit (external crate) path helpers | `rtok-sys/nativeMain/Fs.kt` | 🟡 normalize/canon/ancestors/starts_with | via root tests |
| crates/rtok-mcp | `:rtok-mcp` | ⬜ | |
| crates/rtok-agent-sdk | `:rtok-agent-sdk` | ⬜ | |
| crates/rtok-wasm-demo-guest | – | ❌ wasm guest, not needed | |
| crates/rtok-webui | – | ❌ excluded (web) | |
| src/tokens.rs | `rtok/…/rtok/Tokens.kt` | ✅ | 4 → 4 |
| src/modes/ladder.rs | `rtok/…/rtok/modes/Ladder.kt` | ✅ | 3 → 3 |
| src/modes/compress.rs | `rtok/…/rtok/modes/Compress.kt` | ✅ | 16 → 16 |
| src/cli (32 files, 30 subcommands) | `rtok/…/rtok/cli` | 🟡 root command only (Clikt) | |
| src/plugins (compress, json_tree, toon, read, cmd, inject, guard, memory, measure, docs, archive, checkpoint, graph, proxy, wasm) | `rtok.plugins` | ⬜ | |
| src/proxy | `rtok.proxy` (Ktor) | ⬜ | |
| src/mcp | `rtok.mcp` | ⬜ | |
| src/hooks | `rtok.hooks` | ⬜ | |
| src/agents (40+ hosts), src/doctor | `rtok.agents`, `rtok.doctor` | ⬜ | |
| src/otel, worktree, tasks, measure, model, report, bench, completions, project, store | matching packages | ⬜ | |
| src/tui | – | ❌ excluded (TUI) | |
| src/web, agents/junk_web.rs, `rtok web/tui/dashboard` | – | ❌ excluded (web) | |
| tests/*.rs (138 integration files) | `:rtok` commonTest | ⬜ | 0 / ~138 files |

Totals: 47 Kotlin tests passing on macosArm64.
