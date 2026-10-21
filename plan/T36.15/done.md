**T36.15 the web Doctor page carries the instruction audit** · — · `crates/rtok-webui/src/lib.rs`
Do: `doctor_of` stops after `autoCompactWindow`; `doctor::Report::to_text` appends the `instructions` section (per-file rows and duplicates) that the snapshot already carries, so the D23 parity claim is false for that page.
Check: the page renders the same instruction rows in the same order as `rtok doctor`; `tests/surface_parity.rs` covers it.
Complexity: 2/5
Status: done 2026-09-11 · Model: Composer 2.5
Evidence: `mise exec -- cargo test --test surface_parity` — 4 passed, including `web_doctor_instruction_audit_matches_cli_order`; `mise exec -- cargo test --manifest-path crates/rtok-webui/Cargo.toml doctor_of` — 1 passed (`doctor_of_renders_instruction_audit`).
