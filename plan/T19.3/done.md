**T19.3 Slint WASM UI** · files: `crates/rtok-webui/**`
Do: one `.slint` app, plugin list, per-plugin page, `TokenStatsWidget`; `web_sys::WebSocket` to `/ws`.
**Check:** `wasm-pack build crates/rtok-webui --dev --target web` (or skip if wasm-pack missing; crate still present).
Status: done 2026-09-08
Model: Cursor Grok 4.6
Check result: `crates/rtok-webui` present (`ui/app.slint` + `web_sys::WebSocket` client). `wasm-pack` is not on PATH, so the WASM pack step is skipped as the Check allows; `cargo check` in that crate compiles the `.slint` (native lib). `just dashboard` still serves `/ws` and warns until `wasm-pack` is installed.
Follow-up 2026-09-09: wasm-pack 0.15.0 present — `wasm-pack build --release --target web --out-dir pkg` green (48 s); `rtok dashboard --port 3334` serves `/` 200 with canvas + module, `pkg/rtok_webui.js` 200 (91 KB), wasm 200 (10.5 MB), `/ws` 10 plugins with `saves_tokens` stats. Gate P19 passed (no human eyeball on pixels; everything an agent can verify is green).
