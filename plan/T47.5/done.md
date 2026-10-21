**T47.5 Host plugin Node tests on every OS** · P2, 2/5 · `tests/node/fake-rtok.ts` (new), `plugins/pi/tests/rtok.test.ts`, `plugins/opencode/rtok.test.ts`, `plugins/pi/README.md`
Do: the pi and OpenCode unit tests skipped on Windows because their fake `rtok` was a `sh` script, and Node's `execFile`/`spawnSync` only find `.exe` there. `tests/node/fake-rtok.ts` links (or copies) the running `node` binary as `rtok[.exe]` once per process and points `NODE_OPTIONS=--require` at a per-case script that plays `rtok` with `args` and `input` in scope; node answers `--version` itself. `fakeRtok(null)` puts an empty dir on PATH. Both tests use the one helper, so no case is skipped on any OS.
Check: `tests/pi_plugin.rs::pi_extension_unit_test_with_fake_rtok`, `tests/filter.rs::opencode_plugin_unit_test_with_api_mock`.
Status: done 2026-09-17 · Model: Claude Code / Fable 5.1
Evidence: Node pi 5/5 and OpenCode 5/5 with 0 skipped on macOS; `just check` exit 0 (716 passed). Not run on Windows here.
Deviation: 4 files (≤3) — one shared helper replaces a fake in each of the two tests, and the pi README names it.
