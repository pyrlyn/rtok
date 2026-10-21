**T48.2 pi install hint reaches the model** · P1, 1/5 · `plugins/pi/extensions/rtok.ts`, `plugins/pi/tests/rtok.test.ts`, `plugins/pi/README.md`

From I-36. `pi.appendEntry` is TUI-only (pi docs: "do NOT participate in LLM context"), so the missing-rtok ketch hint never reached the model.

Do: the extension now calls `pi.sendMessage({customType: "rtok-missing", content: KETCH_HINT, display: true})` once per session (module guard flag; falls back to `appendEntry` only when `sendMessage` is missing for old pi). Fail-open holds: the bash command still runs unchanged.
Check: `plugins/pi/tests/rtok.test.ts` asserts one `sendMessage` with the ketch hint, once per session across two calls, zero `appendEntry` when `sendMessage` exists, plus the fallback case; `plugins/pi/README.md` states the `sendMessage` path.
Status: done 2026-09-17 · Model: OpenCode / Muse Spark 1.3
Evidence: `node --test plugins/pi/tests/rtok.test.ts` 6 passed; `cargo nextest run --test pi_plugin` 5 passed. `just check` not green in this tree: `cargo clippy --all-features` fails on uncommitted concurrent work (`src/plugins/graph/mod.rs` T52.4 `symbol_dead_candidates` errors), untouched by this task; `cargo fmt --check` shows the same pre-existing diff.
Deviation: none; 3 files, ≤200 LOC.
