From run 35576438155. Windows printed `unchanged since …` where the test expects hunks — the cache is treating a changed file as unchanged, likely an mtime-resolution or path-normalization difference on Windows (e.g. FAT/NTFS timestamp granularity, or a `\`-vs-`/` cache key mismatch). Read `plugins/read/cache.rs`'s change-detection key and decide whether it needs a Windows-safe granularity/path fix or the test needs to force a large-enough mtime delta. One family split out of the original T83; see T83.2 for the closing criterion.

Do (Claude Code / claude-opus-5-5, 2026-09-25): not mtime and not the cache — the test. Its third case plants a stale row under `key("ws/c.txt", …)`, but `read_with` keys by the path `resolve_with` builds from `PathBuf` components, `ws\c.txt` on Windows; the planted row missed, the real row still matched, and the read said `unchanged since …`. The test now builds the planted key from `resolve_with`, as `read_with` does. Its line left the `cfg(windows)` filter in `.config/nextest.toml`.

Check: the test passes in the `windows` CI job; `just check` stays green.

Check result: `just check` on macOS passed every test but `pi_plugin::setup_pi_yes_links_remove_unlinks`, the known vitest flake, which timed out again on rerun while the host load average sat near 150 on 16 cores; the PR's `check` and `windows` CI jobs run the full suite.

Status: done 2026-09-25
Model: Claude Code / claude-opus-5-5
