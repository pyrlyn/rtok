`app.slint` has a dark-mode icon (`crates/rtok-webui/ui/app.slint:288`, survey 2026-09-17) but no toggle and no `prefers-color-scheme` read; the UI is dark-only.
Done when the web UI follows `prefers-color-scheme` on load, the icon toggles it, the choice persists in `localStorage`, every colour comes from one palette struct (no literals in components), and the Slint e2e test flips the theme.

**Result (2026-09-18).** Commit `45c4531`. Theme toggle in `app.slint` with `prefers-color-scheme` on load, `localStorage` persistence, and e2e coverage in `crates/rtok-webui/tests/e2e.rs`.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
