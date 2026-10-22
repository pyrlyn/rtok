`just spa-e2e` sometimes failed in fixture setup with `rtok mcp exited null:` (empty stderr): `spawnSync` killed the seed's `rtok mcp` at the 30 s `SEED_TIMEOUT_MS`. `rtok mcp` does not hang: after stdin EOF it exits at once. Under the suite's load it waited in the kernel. A `sample` of a stalled seed showed the main thread inside `mem_save` → `Store::auto_add_project` → `fs_kit::path::is_unwalkable_root` → `canonicalize` → `realpath` → `getattrlist` for the whole window, on the throwaway home in `os.tmpdir()`. On the creator's Mac that directory holds ~94,000 entries, mostly leaked test dirs (T644). While the suite ran, `realpath` on a dir in `$TMPDIR` took p50 165 ms, p99 1.8 s, max 2.7 s. In `/tmp` it took max 3 ms; `stat` in `$TMPDIR` took max 5 ms; a dir nested a level deeper in `$TMPDIR` was just as slow. Without the suite, `realpath` in `$TMPDIR` stayed under 120 ms. Every `canonicalize` rtok does on the home pays this, so a seed and other `rtok` calls ran past their timeouts.

Plan:
1. `web/e2e/rtok.ts`: each home is made under `/tmp` (Windows keeps `os.tmpdir()`), through one helper.
2. `Rtok.run`: a timed-out call says `timed out after 30 s` instead of `exited null`.
3. Test: `web/e2e/fixture.spec.ts`. Kernel slowness cannot be reproduced in CI; the measurements above are the evidence.

Result: `makeHome` creates every home under `/tmp`; `failure` turns a killed `spawnSync` into `timed out after N s` (or the spawn error) and keeps `exited N` for a real exit code.

Check: `the throwaway home lives under /tmp, not the per-user temp dir` failed before (home under `/var/folders/…/T/`) and passes after; `a call killed by its timeout says it timed out`; `just js`, `web` typecheck. Three full `spa-e2e` runs with every `rtok mcp` call timed: no seed failed and none read `exited null` (before: one in each of the first runs); `Rtok.create` took 0.4 s instead of 17.6 s. The longest call was the 500-call burst at 22 s under load average 52, spread over its calls rather than one stall. Not fixed here and failing with `main`'s fixture too: `Compare colours …` and `Export downloads …` time out even alone; `hosts page …` and `clear safe junk …` pass alone and fail only under that load.

Status: done 2026-10-12
Model: Claude Code / claude-opus-5-5
