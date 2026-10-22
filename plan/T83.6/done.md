`tests/agents_doc.rs` compares the generated `docs/agents.md` host table against the bless output; on Windows this likely differs by path separator or line endings (CRLF vs LF) rather than actual host-table content. Decide whether the generator needs `cfg(windows)` normalization or the comparison needs to normalize line endings. One family split out of the original T83; see T83.2 for the closing criterion.

Plan: the exclusion list was taken from run 35244082778 (2026-09-17), before T82's `.gitattributes` (`* text=auto eol=lf`) made the Windows checkout LF — the CRLF cause this card suspects is likely gone already, and nothing in `table()` or host `support()` branches on `cfg(windows)`. Drop the test's line from the `cfg(windows)` `default-filter` in `.config/nextest.toml` and let this PR's `windows` job run it; only if it still fails, fix from that job's diff.

Check: the test passes in the `windows` CI job; `just check` stays green.

Do (Claude Code / claude-opus-5-5, 2026-09-24): no code change. The failure came from CRLF checkouts, which T82's `.gitattributes` (`* text=auto eol=lf`) already removed. The test's line is dropped from the `cfg(windows)` `default-filter` in `.config/nextest.toml`.

Check result: a probe branch ran every `cfg(windows)`-filtered test on `windows-latest` (ci run 36032362793, 2026-09-24): `agents_doc_table_matches_the_host_code` passed there (0.077 s). `just check` green on macOS.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
