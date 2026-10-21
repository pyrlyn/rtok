Read `plugins/cmd/formatters.rs`'s AWS-key redaction and decide whether the regex/format assumes a Unix-shaped command line (quoting, path separators) that differs on Windows, or the test fixture itself is Unix-only. One family split out of the original T83; see T83.2 for the closing criterion.

Check: the test passes in the `windows` CI job; `just check` stays green.

Do (Claude Code / claude-opus-5-5, 2026-09-24): no code change. The test already passes on Windows; the September failure did not recur and the formatter is unchanged. The test's line is dropped from the `cfg(windows)` `default-filter` in `.config/nextest.toml`.

Check result: a probe branch ran every `cfg(windows)`-filtered test on `windows-latest` (ci run 36032362793, 2026-09-24): `ten_families_and_aws_key_unredacted` passed there (0.044 s). `just check` green on macOS.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
