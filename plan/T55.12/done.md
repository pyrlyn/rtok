**T55.12 Windows `wrap_quote` corrupts apostrophes under POSIX host shells** · - · `src/plugins/cmd/hook.rs`
Do: nothing containing `'` is wrapped on `cfg!(windows)` — the PowerShell `''` rewrite concatenated under Git Bash (Claude Code's Windows Bash shell) silently drops the apostrophe; mirrors the heredoc skip, compression loss is the safe direction.
Check: pure tests `windows_apostrophe_commands_stay_unwrapped` (host-explicit `skip_wrap_host`, both host contracts pinned on one toolchain) and the parse-simulation `ps_quoting_does_not_round_trip_under_sh` (`'echo it''s fine'` splits to the single word `echo its fine`; the POSIX `'"'"'` embedding round-trips); `wrap_keeps_apostrophe_host_safe` updated to the new contract.
Complexity: 2/5 — one skip rule plus a host-explicit refactor of `skip_wrap`.
Status: done 2026-09-18
Check result: `cargo nextest run -p rtok cmd` 54/54 green (the three tests above included); full `just check` green in the isolation worktree (HEAD + own files, never /tmp).
Model: ZCode / GLM-5.3-Flash
