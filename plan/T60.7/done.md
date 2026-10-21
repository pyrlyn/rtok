`crates/rtok-webui/pkg/rtok_webui_bg.wasm` is 10,560,601 bytes (`ls -l`, 2026-09-17), served uncompressed from `rtok web`; no release profile, `lto` or `wasm-opt` is set for the crate.
Done when the webui release profile sets `opt-level = "z"`, `lto = true`, `codegen-units = 1`, `panic = "abort"`, `just web` runs `wasm-opt -Oz` when it is on PATH (fail open to the unoptimised file otherwise, with a line), `rtok web` serves the file with `Content-Encoding` negotiation for a pre-compressed `.wasm.br`/`.wasm.gz` when present, a test asserts the served size is under a number set from the measured result of this task, and the before/after bytes go into `research.md` with the date and command.

**Result (2026-09-18).** Commit `45c4531`. Release profile in `crates/rtok-webui/Cargo.toml`; `just web` runs `wasm-opt -Oz`; `tests/web_wasm.rs` gates at 4,500,000 B; measured 4,130,017 B in `research.md`.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
