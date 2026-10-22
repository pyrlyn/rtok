**T19.4 WASM webui renders every `model::pages()` entry** · T19.3 · `crates/rtok-webui/**`, `src/web/`
Do: the Slint WASM UI still shows the Plugins strip only. Sessions / Calls / Logs / Doctor (and Overview) ride the D23 snapshot and `model::pages()` and render on `rtok tui`, but are not rendered in `crates/rtok-webui` — a page that exists on one surface and not the other is a D23 defect. Bring the WASM UI up to the model page set.
Check: every id from `model::pages()` appears in the WASM UI; a snapshot carrying Sessions/Calls/Logs/Doctor makes those pages visible; T15.10 surface-parity still green; `just check` green.
Complexity: 3/5
Status: done 2026-09-10 · Model: GLM-5.3
Check result: green. WASM UI tab bar is `PAGE_IDS` = every `model::pages()` id (overview/plugins/calls/sessions/doctor/logs); Slint bodies for each; snapshot parser paints Sessions/Calls/Logs/Doctor from the `/ws` frame. `cargo test --manifest-path crates/rtok-webui/Cargo.toml` 3/3; `cargo test --test surface_parity` 3/3 (incl. `wasm_ui_renders_every_model_page`); T15.10 still green; `just check` green (isolated target dir — shared target raced with another worktree).
