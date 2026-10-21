From `research.md` §22: the junk map says "all 17 hosts", but `HOSTS` (`src/agents/mod.rs`) has 22. `junk_map.rs` has no rows for commandcode, cline, mimo and devin, and antigravity has Electron folders only. §22.2 also names VS Code `CachedData/<commit>` for every commit but the current one (VS Code's own cleaner removes them after about three months).

Done means: each missing host has a cited §22 row (or "not documented" where no primary source exists) and a matching `junk_map.rs` entry. VS Code gets a `CachedData` row where `clear` removes only non-current commit folders; Cursor stays list-only. The host count in §22 is corrected.

Check: `junk_map` unit tests for the new rows and the current-commit exclusion; a test that every `HOSTS` entry has a junk-map row or an explicit "none"; `just check`.

Result: `research.md` §22 now has a row for every host of `HOSTS` (24): commandcode (config files only), cline (`~/.cline/data/logs`), mimo (`~/.local/share/mimocode/log`, `~/.cache/mimocode`), devin (`~/.local/share/devin/cli/logs`), qwen (`~/.qwen`, sub-paths not documented), and antigravity and roo as "not documented" (third-party guides only, **unverified**), each with its primary URL and the check date 2026-10-08; the host count is corrected and the VS Code row names `CachedData/<commit>`. `src/agents/junk_map.rs` encodes them, adds `Spec.per_commit` for VS Code's two `CachedData` folders and `NO_ROWS` (kilo, aider, roo) with a test that every `HOSTS` entry has a row or is listed there. `junk_kinds::stale_code_caches` offers every commit folder except the newest (rtok cannot read the running commit, so the most recently modified one stands for it), under T152's idle rule and never through a link; Cursor's `CachedData` stays list-only. Not done: VS Code's own ~3 month age limit is not applied; Windows paths of the new hosts are not in the map.

Status: done 2026-10-08
Model: Claude Code / claude-sonnet-5-5
