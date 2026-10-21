**T0.7 CI** · T0.1 · `.github/workflows/ci.yml`, `Makefile`
Do: `make check` = fmt --check, clippy -D warnings, test. CI runs it on macOS + Linux.
Check: `make check` exits 0 locally.
Status: done 2026-09-01 · `make check` also runs the single-feature build; CI (`ubuntu-latest`, `macos-latest`) installs the toolchain from `mise.toml` via `jdx/mise-action` and runs `make check` + `make example`. Lints pinned in `Cargo.toml` `[lints]` (`unsafe_code = forbid`, clippy `all` as warnings → errors under `-D warnings`); `rustfmt.toml` sets `style_edition = "2024"`; `.editorconfig` added.

Also delivered with P0 (not numbered tasks): `architecture.md`, `README.md`, `docs/plugin-authoring.md`, `examples/hello_plugin.rs` (asserts a `Measurement` row is written), this file.
