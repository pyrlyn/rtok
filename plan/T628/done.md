Confirmed on `ea999781d7a48733e3a5c92e711cfad291c13536`: [newest_child_dir](src/agents/plugin_install.rs#L225) sorts directory paths lexicographically and pops the last. An isolated fixture containing `0.9.0` and `0.10.0` selects `0.9.0`. [cache_probe](src/agents/plugin_install.rs#L190) uses this for Codex and CodeBuddy installed-version detection, so the outdated/list paths can report an older cached copy even when the newer copy exists. T328 corrected the separate `ketch_store_plugin` resolver; this still-unfixed cache selector does not use that ordering.

Execution plan:

1. Add installed-version probes containing `0.9.0`, `0.10.0`, prereleases, build metadata, malformed names and unrelated files; assert which manifest/version record supplies the result.
2. Prefer an authoritative active-install record wherever the host supplies one. For the documented newest-cache fallback, apply SemVer precedence and an explicit deterministic fallback for unparseable names.
3. Reuse the existing version parsing/order policy rather than adding another independent comparator. Verify Codex and CodeBuddy list/outdated behavior and keep receipt precedence intact.
4. Run plugin-install/version/outdated tests and `just check`.

Check: a newer semantic version is not hidden by the lexical order of cache folder names. Executed evidence used the exact selector over real temporary directories; host activation and update commands were not run.

Result: `agents::version_dir_key` (extracted from `ketch_store_plugin`) orders cached plugin versions by version precedence in `newest_child_dir`.

Check: `the_newest_cached_version_wins_by_semver_not_by_name`, `the_cache_probe_reads_the_manifest_of_the_newest_copy`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
