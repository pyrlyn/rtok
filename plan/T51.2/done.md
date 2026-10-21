**T51.2 Anthropic native context editing** · P3, 3/5 · `src/proxy/anthropic.rs`, `src/proxy/mod.rs`, `src/config/mod.rs`, `config/default.toml`, `docs/config.md`, `tests/proxy.rs`, `tests/trycmd/config-show.stdout`, `README.md`

From I-10. Anthropic can clear old tool uses server-side (`context_management`, `clear_tool_uses_*`), which competes with or complements `archive`.

Do: `[proxy] context_management = false` (opt-in; env `RTOK_PROXY_CONTEXT_MANAGEMENT`; no CLI flag) arms server-side clearing on Anthropic Messages only — the proxy adds `context_management.edits: [{type: clear_tool_uses_20250919}]` plus the `context-management-2025-06-27` beta header (API shape verified against the platform docs 2026-09-17), never overwriting a caller-set field, in both proxy modes. Each armed request records a zero-delta `proxy/context_management` Measurement naming the path (semantic-cache precedent; the platform's saving is not locally observable so none is claimed, D3). `archive` stands down on armed Anthropic requests (no double-shrink, no cache churn). No `Wire` trait change.
Check: `tests/proxy.rs::proxy_anthropic_context_edits_arm_platform_path` (mock upstream matches the beta header; body carries the field; old turns unrewritten; 0 archive rows; 1 path row; 1 usage row) plus `anthropic.rs::context_edits_are_opt_in_and_never_overwrite`.
Status: done 2026-09-17 · Model: OpenCode / Muse Spark 1.3

Evidence: `cargo test --test proxy` 24 passed; `--test wrap/cli_trycmd/config_coverage` green; `--lib proxy/config` green; `fmt --check`, `build-min`, `jscpd` (exit 0) green. README compares both paths on the same six-turn request: archive 103 729 → 70 837 B with 4 Measurements; platform 103 729 → 103 798 B (+69 B field) with 1 path row.

Deviation: 8 files / ~240 LOC (card needs config + docs per D12, a proxy test, and the README comparison). `just check` clippy stays red on another agent's untracked `src/agents/windsurf/mod.rs` (`unused import std::fs`), left for its owner.
